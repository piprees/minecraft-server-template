#!/usr/bin/env bash
# apply.sh - Apply the Minecraft 26.3 cutover to a checkout of the template.
#
# Purpose: performs every item of docs/design/rebuild-26/cutover-removals.md
#   (the v6.0.0 switch, decisions.md RB-05) and deletes the 1.21.1 mod with
#   what only served it, so the switch is one reviewed, mechanical commit.
#
# Context: template-only, run once by the lead on the PR branch after the
#   dimension work finishes. It writes files and leaves staging and the
#   commit to the operator. Exact-text edits live in edits.py beside it; files
#   that needed judgement (a workflow, a script rewrite, the docs and skills)
#   live under files/<repo path> (stored names: README.md) with the sha256 of
#   the repo file each was written against in files.sha256, so a file that
#   changed since is reported, not overwritten. Run from the repo's own
#   cutover/, its last step deletes cutover/.
#
# Usage:
#   cutover/apply.sh [--repo DIR] --dry-run    # list every add, change and delete
#   cutover/apply.sh [--repo DIR]              # apply (refuses a dirty tree)
#   cutover/apply.sh [--repo DIR] --no-network # apply, but leave the resolve cache
#                                              # for a later gen-resolve-cache.py
#   cutover/apply.sh [--repo DIR] --accept-drift  # overwrite drifted files/ targets
#
# Output: one line per file, "A" add, "M" change, "D" delete, "R" move
#   (old -> new), "=" already done; a count by kind at the end.
#
# Gotchas:
#   - Idempotent: a second run prints "=" lines and changes nothing.
#   - --dry-run runs on a dirty tree (it writes nothing) and says so.
#   - The resolve cache step calls the Modrinth API (one call per new pin).
#   - Run from anywhere; --repo defaults to the repository holding this
#     script's parent, else the current directory's.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES="$HERE/files"
EDITS="$HERE/edits.py"
DRY=0
NETWORK=1
ACCEPT_DRIFT=0
REPO=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --no-network) NETWORK=0 ;;
    --accept-drift) ACCEPT_DRIFT=1 ;;
    --repo)
      REPO="$2"
      shift
      ;;
    -h | --help)
      sed -n '/^# Usage:/,/^# Output:/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "ERROR: unknown argument $1" >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -z "$REPO" ]]; then
  REPO="$(git -C "$PWD" rev-parse --show-toplevel 2> /dev/null || true)"
fi
[[ -n "$REPO" && -f "$REPO/.minecraft-version" && -d "$REPO/.git" ]] || {
  echo "ERROR: $REPO is not a checkout of the template (no .minecraft-version or .git)" >&2
  exit 2
}
REPO="$(cd "$REPO" && pwd)"
cd "$REPO"

N_A=0 N_M=0 N_D=0 N_R=0 N_SAME=0 DRIFT=0
out() { printf '  %s %s\n' "$1" "$2"; }
count() {
  case "$1" in
    A) N_A=$((N_A + 1)) ;;
    M) N_M=$((N_M + 1)) ;;
    D) N_D=$((N_D + 1)) ;;
    R) N_R=$((N_R + 1)) ;;
    =) N_SAME=$((N_SAME + 1)) ;;
  esac
}
step() { printf '\n==> %s\n' "$1"; }

# files_under <path> - every file at or under a path, sorted.
files_under() {
  if [[ -d "$1" ]]; then find "$1" -type f | LC_ALL=C sort; elif [[ -e "$1" ]]; then echo "$1"; fi
}

# del <path>... - delete files or directories.
del() {
  local p f
  for p in "$@"; do
    if [[ ! -e "$p" ]]; then
      out "=" "$p (absent)"
      count "="
      continue
    fi
    while IFS= read -r f; do
      out D "$f"
      count D
    done < <(files_under "$p")
    [[ $DRY -eq 1 ]] || rm -rf "$p"
  done
}

# move <src> <dst> - move a file, or a directory's files into dst (merging,
# replacing same-named files).
move() {
  local src="$1" dst="$2" f rel
  if [[ ! -e "$src" ]]; then
    if [[ -e "$dst" ]]; then
      out "=" "$dst (moved)"
      count "="
      return 0
    fi
    echo "ERROR: neither $src nor $dst exists" >&2
    exit 1
  fi
  if [[ -f "$src" ]]; then
    out R "$src -> $dst"
    count R
    if [[ $DRY -eq 0 ]]; then
      mkdir -p "$(dirname "$dst")"
      mv -f "$src" "$dst"
    fi
    return 0
  fi
  while IFS= read -r f; do
    rel="${f#"$src"/}"
    out R "$f -> $dst/$rel"
    count R
    if [[ $DRY -eq 0 ]]; then
      mkdir -p "$(dirname "$dst/$rel")"
      mv -f "$f" "$dst/$rel"
    fi
  done < <(files_under "$src")
  [[ $DRY -eq 1 ]] || find "$src" -depth -type d -empty -delete
}

sha() { if command -v sha256sum > /dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# stored <repo-path> - where files/ keeps a repo path's replacement: a leading
# dot of any component becomes "dot-" and AGENTS.md becomes AGENTS.next.md, so
# no tool takes a stored workflow, skill or agent contract for a live one.
stored() {
  printf '%s\n' "$1" | sed -E 's#(^|/)\.#\1dot-#g; s#(^|/)AGENTS\.md$#\1AGENTS.next.md#'
}

# put <repo-path> - copy the stored replacement over the repo file. The repo
# file must still be the one the replacement was written against
# (files.sha256).
put() {
  local rel="$1" src base cur
  src="$FILES/$(stored "$1")"
  [[ -f "$src" ]] || {
    echo "ERROR: $src is missing" >&2
    exit 1
  }
  if [[ -f "$rel" ]] && cmp -s "$src" "$rel"; then
    out "=" "$rel"
    count "="
    return 0
  fi
  base="$(awk -v p="$rel" '$2 == p { print $1 }' "$HERE/files.sha256")"
  if [[ -f "$rel" ]]; then
    cur="$(sha "$rel")"
    # A dry run has not applied the edits a port's base sha was taken after.
    if [[ $DRY -eq 1 && -n "$base" && "$base" != "$cur" ]]; then
      cur="$(python3 "$EDITS" --sha "$REPO" "$rel")"
    fi
    if [[ -n "$base" && "$base" != new && "$base" != "$cur" && $ACCEPT_DRIFT -eq 0 ]]; then
      out '!' "$rel drifted since its replacement was written (base $base, now $cur): review, then --accept-drift"
      DRIFT=1
      return 0
    fi
    out M "$rel"
    count M
  else
    out A "$rel"
    count A
  fi
  if [[ $DRY -eq 0 ]]; then
    mkdir -p "$(dirname "$rel")"
    cp "$src" "$rel"
  fi
}

# edits <step> - the exact-text edits of one step (edits.py).
edits() {
  local line flag=""
  [[ $DRY -eq 1 ]] && flag="--dry-run"
  # shellcheck disable=SC2086 # flag is empty or one word
  python3 "$EDITS" "$REPO" "$1" $flag > "$HERE/.edits-$1.log" || DRIFT=1
  local seen=""
  while IFS= read -r line; do
    case "$line" in
      "  ~ "*)
        line="${line#  ~ }"
        line="${line%%:*}"
        case " $seen " in *" $line "*) continue ;; esac
        seen="$seen $line"
        out M "$line (edit)"
        count M
        ;;
      "  = "*)
        count "="
        ;;
      *DRIFT*) printf '  ! %s\n' "${line#*DRIFT }" ;;
    esac
  done < "$HERE/.edits-$1.log"
  rm -f "$HERE/.edits-$1.log"
}

# write <repo-path> <text> - a small generated file.
write() {
  local rel="$1" text="$2"
  if [[ -f "$rel" ]] && [[ "$(cat "$rel")" == "$text" ]]; then
    out "=" "$rel"
    count "="
    return 0
  fi
  if [[ -f "$rel" ]]; then out M "$rel"; count M; else out A "$rel"; count A; fi
  [[ $DRY -eq 1 ]] || printf '%s\n' "$text" > "$rel"
}

# =============================================================================
step "Preflight"
echo "  repo: $REPO ($(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD))"
echo "  .minecraft-version: $(cat .minecraft-version)"
if [[ -n "$(git status --porcelain)" ]]; then
  if [[ $DRY -eq 1 ]]; then
    echo "  note: the tree is dirty; a real run refuses it ($(git status --porcelain | wc -l | tr -d ' ') paths)"
  else
    echo "ERROR: the working tree is dirty; commit or park the changes first (git status)" >&2
    exit 1
  fi
fi
command -v python3 > /dev/null || {
  echo "ERROR: python3 is required" >&2
  exit 1
}
[[ $DRY -eq 1 ]] && echo "  dry run: nothing is written"

# =============================================================================
step "1. Version: .minecraft-version, the compose, workflow and consumer fallbacks, the version table"
write .minecraft-version "26.3"
edits version

# =============================================================================
step "2. Mod lists, pack manifest, in-house mod manifests"
[[ ! -f config/modrinth-mods-26.3.txt ]] || edits lists
if [[ -f config/modrinth-mods-26.3.txt ]]; then
  out R "config/modrinth-mods-26.3.txt -> config/modrinth-mods.txt (replaces the 1.21.1 list)"
  count R
  [[ $DRY -eq 1 ]] || mv -f config/modrinth-mods-26.3.txt config/modrinth-mods.txt
else
  out "=" "config/modrinth-mods.txt (the 26.3 list)"
  count "="
fi
if [[ -f modpack/adventure-26.3.mrpack.json ]]; then
  out R "modpack/adventure-26.3.mrpack.json -> modpack/adventure.mrpack.json (replaces the 1.21.1 manifest)"
  count R
  [[ $DRY -eq 1 ]] || mv -f modpack/adventure-26.3.mrpack.json modpack/adventure.mrpack.json
else
  out "=" "modpack/adventure.mrpack.json (the 26.3 manifest)"
  count "="
fi
for kind in local client; do
  if [[ -f "mods/$kind-mods-26.3.manifest" ]]; then
    out R "mods/$kind-mods-26.3.manifest -> mods/$kind-mods.manifest (replaces the 1.21.1 manifest)"
    count R
    [[ $DRY -eq 1 ]] || mv -f "mods/$kind-mods-26.3.manifest" "mods/$kind-mods.manifest"
  else
    out "=" "mods/$kind-mods.manifest"
    count "="
  fi
done

step "2b. Resolve cache, regenerated from the 26.3 list"
if [[ $NETWORK -eq 0 ]]; then
  out '!' "config/modrinth-resolve-cache.json left as it is (--no-network): run python3 scripts/gen-resolve-cache.py before committing"
elif [[ $DRY -eq 1 ]]; then
  list=config/modrinth-mods-26.3.txt
  [[ -f "$list" ]] || list=config/modrinth-mods.txt
  missing="$(python3 - "$list" << 'PYEOF'
import json, sys
cache = json.load(open("config/modrinth-resolve-cache.json"))
pins = []
for line in open(sys.argv[1]):
    entry = line.split("#")[0].strip()
    if entry.startswith("datapack:"):
        entry = entry[len("datapack:"):]
    entry = entry.rstrip("?")
    if ":" in entry:
        pins.append(entry)
print(sum(1 for p in pins if p not in cache), len(pins), len(cache))
PYEOF
)"
  read -r n_missing n_pins n_cache <<< "$missing"
  if [[ "$n_missing" -eq 0 && "$n_cache" -eq "$n_pins" ]]; then
    out "=" "config/modrinth-resolve-cache.json ($n_pins pins, all cached)"
    count "="
  else
    out M "config/modrinth-resolve-cache.json (rebuilt from $list: $n_pins pins, $n_missing not yet cached; the Modrinth API answers each)"
    count M
  fi
else
  cp config/modrinth-resolve-cache.json "$HERE/.resolve-cache.prev"
  echo '{}' > config/modrinth-resolve-cache.json
  if python3 scripts/gen-resolve-cache.py; then
    if cmp -s config/modrinth-resolve-cache.json "$HERE/.resolve-cache.prev"; then
      out "=" config/modrinth-resolve-cache.json
      count "="
    else
      out M config/modrinth-resolve-cache.json
      count M
    fi
    rm -f "$HERE/.resolve-cache.prev"
  else
    mv -f "$HERE/.resolve-cache.prev" config/modrinth-resolve-cache.json
    echo "ERROR: gen-resolve-cache.py failed; the cache is restored. Re-run with --no-network to continue without it." >&2
    exit 1
  fi
fi

step "2c. The client pack's 26.3 overrides fold into modpack/overrides/"
if [[ -f modpack/versions/26.3/overrides-remove.txt ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(printf '%s' "$line" | tr -d '[:space:]')"
    [[ -n "$line" ]] || continue
    del "modpack/overrides/$line"
  done < modpack/versions/26.3/overrides-remove.txt
fi
if [[ -d modpack/versions/26.3/overrides ]]; then
  while IFS= read -r f; do
    rel="${f#modpack/versions/26.3/overrides/}"
    if [[ -f "modpack/overrides/$rel" ]]; then
      out M "modpack/overrides/$rel (from $f)"
      count M
    else
      out A "modpack/overrides/$rel (from $f)"
      count A
    fi
    if [[ $DRY -eq 0 ]]; then
      mkdir -p "$(dirname "modpack/overrides/$rel")"
      cp "$f" "modpack/overrides/$rel"
    fi
  done < <(files_under modpack/versions/26.3/overrides)
fi
del modpack/versions/26.3

# =============================================================================
step "3. Configs of cut and replaced mods"
del config/betterdays config/lootr config/boring_default_game_rules config/tectonic.json \
  config/bountiful config/seasons.json config/configurable-difficulty config/bluemapsignmarkers

step "3b. config/versions/26.3/ folds into config/"
V=config/versions/26.3
# Collective 26.3 moves a flat config/collective.json5 or config/collective/
# over config/serilum/collective.json on every boot, so neither copy stays.
del "$V/collective.json5" "$V/collective"
for d in serilum servercore Geyser-Fabric floodgate; do
  if [[ -e "$V/$d" ]]; then move "$V/$d" "config/$d"; fi
done
del config/collective config/collective.json5 config/doubledoors config/firespreadtweaks \
  config/healingcampfire config/nametagtweaks config/nutritiousmilk config/youritemsaresafe \
  config/starterkit.json5
if [[ -d "$V" ]]; then
  left="$(files_under "$V")"
  if [[ -n "$left" && $DRY -eq 0 ]]; then
    echo "ERROR: $V still holds files this plan does not name:" >&2
    echo "$left" >&2
    exit 1
  fi
  [[ $DRY -eq 1 ]] || find "$V" -depth -type d -empty -delete
fi
put config/versions/README.md

step "3c. defaults-seed COPY lines"
edits seed

# =============================================================================
step "4. The v2 definitions and bridges move into config/custom-dimensions/"
CD=config/custom-dimensions
if [[ -d mods/custom-dimensions-26/bridges ]] || ! grep -qs '"schemaVersion"' "$CD/dimensions/settings.json"; then
  # The 1.21.1 definitions and their settings.json stay as the conversion's
  # record beside the id mapping, in the v1 layout: review-packet.py reads each
  # dimension's 1.21.1 description there, and the v1 config tests of core/ load it.
  for f in "$CD"/dimensions/*.json; do
    [[ -f "$f" ]] || continue
    case "$f" in *_thumb.json) continue ;; esac
    move "$f" "mods/custom-dimensions-26/definitions/v1/dimensions/$(basename "$f")"
  done
  [[ ! -f "$CD/settings.json" ]] || move "$CD/settings.json" "mods/custom-dimensions-26/definitions/v1/settings.json"
  # The 1.21.1 set and the data only the 1.21.1 mod and its tools read.
  # extractors/ stays: gen-unmined-blocktags.py and gen-open-water-guard.py
  # read it (it is never synced to data/config).
  del "$CD/dimensions" "$CD/display-names.json" "$CD/climate-axes.json" \
    "$CD/structure-groups.json" "$CD/structure-sizes.json" "$CD/structure-type-defaults.json" \
    "$CD/grids-41"
  for f in mods/custom-dimensions-26/definitions/*.json; do
    [[ -f "$f" ]] || continue
    move "$f" "$CD/dimensions/$(basename "$f")"
  done
  move mods/custom-dimensions-26/bridges "$CD/bridges"
else
  out "=" "$CD/dimensions and $CD/bridges (v2)"
  count "="
fi
edits mod

# =============================================================================
step "5. The smoke test: smoke-test-26.yml becomes smoke-test.yml; dep-review's join26 folds into smoke"
put .github/workflows/smoke-test.yml
del .github/workflows/smoke-test-26.yml
put scripts/smoke.sh
[[ $DRY -eq 1 || ! -f scripts/smoke.sh ]] || chmod +x scripts/smoke.sh
del scripts/smoke-26.sh
edits depreview

# =============================================================================
step "6. Deploy, local stack and bundle: bridges sync, game-rule note, carpet strip"
edits deploy
put scripts/patch-mod-data.py

step "6b. discord-sync: /mc border without chunkyborder, /mc status on tick query"
edits discord

# =============================================================================
step "7. The 1.21.1 mod, its companion, its build workflow and the tools only they served"
del mods/custom-dimensions mods/custom-dimensions-client mods/mise.toml .github/workflows/mod-build.yml
# v1-schema tooling: each reads config/custom-dimensions/ as the 1.21.1 mod
# wrote it, and nothing outside docs and its own test calls it.
del scripts/check-band-reach.py scripts/check-band-share.py scripts/check-biome-bands.py \
  scripts/check-spawn-filters.py scripts/score-biome-table.py scripts/sample-climate-grid.sh \
  scripts/effective-spawn.py scripts/export-seed-winners.py scripts/check-roll-winners.py \
  scripts/check-content-coverage.py scripts/audit-terrain-adaptation.py scripts/gen-void-preset.py \
  scripts/tests/test_biome_band_partitions.py scripts/tests/test_score_biome_table.py
edits tests

# =============================================================================
step "8. Docs and skills for 26.3"
edits docs
# Skill references written for the 1.21.1 mod and its v1 schema; each
# SKILL.md that cited them is replaced below.
del .claude/skills/custom-dimension-authoring/references/schema-reference.md \
  .claude/skills/custom-dimension-authoring/references/scoring-internals.md \
  .claude/skills/custom-dimension-authoring/references/structure-names.md \
  .claude/skills/custom-dimension-authoring/references/portals-and-exits.md \
  .claude/skills/custom-dimension-authoring/references/biome-catalogue.md \
  .claude/skills/worldgen-tuning/references \
  .claude/skills/dimension-lifecycle-operations/references \
  .claude/skills/fabric-mod-development/references/jar-verification.md \
  .claude/skills/fabric-mod-development/references/runtime-invariants.md
while IFS= read -r line; do
  rel="${line#* }"
  case "$rel" in
    config/versions/README.md | .github/workflows/smoke-test.yml | scripts/smoke.sh | scripts/patch-mod-data.py) continue ;;
  esac
  put "$rel"
done < <(grep -vE '^[[:space:]]*(#|$)' "$HERE/files.sha256" | awk '{ print $1 " " $2 }')

# =============================================================================
# Last: the kit itself, once everything it carries is in place.
if [[ "$HERE" == "$REPO/cutover" && $DRIFT -eq 0 ]]; then
  step "9. The kit: cutover/ goes"
  del cutover
fi

# =============================================================================
step "Summary"
printf '  add %s, change %s, delete %s, move %s, already done %s\n' "$N_A" "$N_M" "$N_D" "$N_R" "$N_SAME"
if [[ $DRIFT -ne 0 ]]; then
  echo "  DRIFT: the lines marked ! need review before the switch" >&2
  exit 1
fi
if [[ $DRY -eq 0 ]]; then
  echo "  next: ./scripts/test-scripts.sh --quick, then checklist.md § After (git show HEAD:cutover/checklist.md)"
fi
