#!/usr/bin/env bash
# clean-dev-state.sh - Delete named categories of regenerable local state.
#
# Context: runs on a developer Mac, invoked as `./dev clean` in a consumer
# repo. It deletes only the paths this platform generates, each one named in
# the table below. It is NOT "delete everything git does not track": notes,
# scratch directories, agent state, wizard state and any other untracked file
# you keep in the repo survive, because nothing here names them.
#
# Usage:
#   clean-dev-state.sh [<consumer-dir>] [target...] [options]
#
# Targets (default: stack pack cache mods config):
#   stack     .stack/                       pulled bundles and the ./dev link farm
#   pack      modpack-dist/, modpack/dist/  built client pack and mod mirror
#   cache     cache/                        Docker image / mod jar snapshots
#   mods      data/mods/                    re-seeded and re-downloaded on the next up
#   config    data/config/                  re-seeded from the bundle on the next up
#   world     data/world/ and friends       OPT-IN. The same set ./dev reset-world
#                                           deletes: map renders, Chunky markers,
#                                           dynamic data packs, portal/fingerprint state
#   seeds     .seed-rolling/                OPT-IN. Candidate bank, renders, lint output
#   backups   backups/                      OPT-IN. Local backup archives
#
# Options:
#   --all          every target, including world, seeds and backups
#   --dry-run      list what would go, delete nothing
#   --yes          skip the confirmation prompt
#   --list         print the target table and exit
#   --platform DIR also clean build output in a platform checkout:
#                  mods/*/build, mods/*/.gradle, mods/*/run, mods/*/.fabric,
#                  dist/, modpack/dist/
#
# Confirmation: any run that includes world, seeds or backups asks first, and
# --all asks for a typed CLEAN. The default target set deletes nothing that
# takes human effort to recreate, so it just runs.
#
# The world target refuses while the mc container runs: mc rewrites data/world
# on shutdown, so the delete looks done and is undone by the next stop (T34).
#
# A linked platform checkout is never cleaned implicitly. Deleting its
# mods/*/build leaves `./dev link`'s jar symlinks pointing at nothing, and the
# next `./dev up` aborts copying local-mods — so it takes an explicit
# --platform, and afterwards you rebuild and re-run `./dev link`.
#
# Never touched by any target: .env, overlay/, config/, .claude/, scratch/,
# and every path not listed above.
#
# Must run on macOS bash 3.2 - no mapfile, no ${var,,}, no declare -A.
set -euo pipefail

CONSUMER=""
PLATFORM=""
DRY_RUN=0
ASSUME_YES=0
TARGETS=""

DEFAULT_TARGETS="stack pack cache mods config"
OPTIONAL_TARGETS="world seeds backups"
ALL_TARGETS="$DEFAULT_TARGETS $OPTIONAL_TARGETS"

print_targets() {
  sed -n '/^# Targets/,/^# Options:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# Paths a target owns, one per line, relative to the consumer directory.
# Every path is literal: no globs to expand into something unintended, and
# nothing here is a parent of a directory the user authors.
target_paths() {
  case "$1" in
    stack) echo ".stack" ;;
    pack)
      echo "modpack-dist"
      echo "modpack/dist"
      ;;
    cache) echo "cache" ;;
    mods) echo "data/mods" ;;
    config) echo "data/config" ;;
    seeds) echo ".seed-rolling" ;;
    backups) echo "backups" ;;
    world)
      # Kept identical to the `reset-world` case in examples/consumer/dev, so
      # the two commands cannot disagree about what "the world" is.
      echo "data/world"
      echo "data/unmined-web/maps"
      echo "data/unmined-web/index.html"
      echo "data/unmined-web/manifest.json"
      echo "data/dynamic-data-pack-cache"
      echo "data/config/chunky/tasks"
      echo "data/.chunky-complete"
      echo "data/.chunky-nether-complete"
      echo "data/.chunky-end-complete"
      echo "data/.chunky-paradise-lost-complete"
      echo "data/.skip-pause"
      echo "data/config/portal_links.json"
      echo "data/config/custom-dimensions-fingerprints.json"
      ;;
    *) return 1 ;;
  esac
}

platform_paths() {
  local dir="$1" d
  for d in "$dir"/mods/*/; do
    [[ -d "$d" ]] || continue
    echo "mods/$(basename "$d")/build"
    echo "mods/$(basename "$d")/.gradle"
    echo "mods/$(basename "$d")/run"
    echo "mods/$(basename "$d")/.fabric"
  done
  echo "dist"
  echo "modpack/dist"
}

is_target() {
  local t
  for t in $ALL_TARGETS; do
    [[ "$t" == "$1" ]] && return 0
  done
  return 1
}

has_target() {
  local t
  for t in $TARGETS; do
    [[ "$t" == "$1" ]] && return 0
  done
  return 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) TARGETS="$ALL_TARGETS"; shift ;;
    --platform) PLATFORM="${2:-}"; shift 2 ;;
    --dry-run | -n) DRY_RUN=1; shift ;;
    --yes | -y) ASSUME_YES=1; shift ;;
    --list) print_targets; exit 0 ;;
    -h | --help) sed -n '2,45p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown argument: $1" >&2; exit 2 ;;
    *)
      if [[ -z "$CONSUMER" && -d "$1" ]] && ! is_target "$1"; then
        CONSUMER="$1"
      elif is_target "$1"; then
        TARGETS="$TARGETS $1"
      else
        echo "Unknown target: $1" >&2
        echo "Valid targets: $ALL_TARGETS" >&2
        exit 2
      fi
      shift
      ;;
  esac
done

[[ -n "$CONSUMER" ]] || CONSUMER="${CONSUMER_DIR:-$(pwd)}"
CONSUMER="$(cd "$CONSUMER" && pwd)"
TARGETS="$(echo "${TARGETS:-$DEFAULT_TARGETS}" | tr ' ' '\n' | grep -v '^$' | sort -u | tr '\n' ' ')"

# A consumer repo is where dev/ops live. Refusing elsewhere stops a mistyped
# path deleting a data/ or cache/ that belongs to something else entirely.
if [[ ! -f "$CONSUMER/dev" && ! -f "$CONSUMER/ops" ]]; then
  echo "Not a consumer repo (no dev or ops entry point): $CONSUMER" >&2
  exit 1
fi

# mc writes data/world on shutdown, so cleaning it under a live stack frees the
# space and hands the same world back on the next stop (see TROUBLESHOOTING T34).
if has_target world && [[ $DRY_RUN -eq 0 ]] \
  && docker ps --format '{{.Names}}' 2> /dev/null | grep -qE 'mc$'; then
  echo "Refusing to clean 'world': the mc container is running and its shutdown" >&2
  echo "save restores data/world. Run ./dev down first." >&2
  exit 1
fi

# Every deletion is confined to the root it was resolved against. A path that
# escapes - through .. or a symlinked parent - is skipped rather than removed.
resolve_inside() {
  local root="$1" rel="$2" parent base resolved
  parent="$(dirname "$CONSUMER/$rel")"
  base="$(basename "$rel")"
  [[ -d "$parent" ]] || return 1
  parent="$(cd "$parent" && pwd -P)"
  resolved="$parent/$base"
  case "$resolved" in
    "$root"/*) printf '%s\n' "$resolved" ;;
    *) return 1 ;;
  esac
}

# Sub-megabyte paths print as 0M under integer division, which reads as
# "nothing there" next to a path that is about to be deleted.
fmt_size() {
  if [[ "$1" -ge 1024 ]]; then
    echo "$(( $1 / 1024 ))M"
  else
    echo "$1K"
  fi
}

size_kb() {
  local p="$1" k
  k=$(du -sk "$p" 2> /dev/null | awk '{print $1}')
  echo "${k:-0}"
}

# Collect what exists, per target, before deleting anything.
CONSUMER_REAL="$(cd "$CONSUMER" && pwd -P)"
PENDING=""
TOTAL_KB=0
for t in $TARGETS; do
  while IFS= read -r rel; do
    [[ -n "$rel" ]] || continue
    full="$(resolve_inside "$CONSUMER_REAL" "$rel")" || continue
    # -e is false for a broken symlink; -L catches those so a dangling
    # .stack/current is still cleared.
    [[ -e "$full" || -L "$full" ]] || continue
    kb=$(size_kb "$full")
    TOTAL_KB=$((TOTAL_KB + kb))
    PENDING="${PENDING}${t}|${CONSUMER_REAL}|${full}|${kb}
"
  done <<< "$(target_paths "$t")"
done

PLATFORM_PENDING=""
if [[ -n "$PLATFORM" ]]; then
  if [[ ! -d "$PLATFORM" ]]; then
    echo "Platform checkout not found: $PLATFORM" >&2
    exit 1
  fi
  PLATFORM="$(cd "$PLATFORM" && pwd -P)"
  if [[ ! -f "$PLATFORM/docker-compose.yml" || ! -d "$PLATFORM/scripts" ]]; then
    echo "Not a platform checkout (needs docker-compose.yml and scripts/): $PLATFORM" >&2
    exit 1
  fi
  while IFS= read -r rel; do
    [[ -n "$rel" ]] || continue
    full="$PLATFORM/$rel"
    [[ -e "$full" || -L "$full" ]] || continue
    case "$full" in "$PLATFORM"/*) ;; *) continue ;; esac
    kb=$(size_kb "$full")
    TOTAL_KB=$((TOTAL_KB + kb))
    PLATFORM_PENDING="${PLATFORM_PENDING}build|${PLATFORM}|${full}|${kb}
"
  done <<< "$(platform_paths "$PLATFORM")"
fi

if [[ -z "$PENDING$PLATFORM_PENDING" ]]; then
  echo "Nothing to clean (targets: $TARGETS)."
  exit 0
fi

echo "Consumer: $CONSUMER"
echo "Targets:  $TARGETS"
[[ -n "$PLATFORM" ]] && echo "Platform: $PLATFORM (build output)"
echo ""
printf '%s%s' "$PENDING" "$PLATFORM_PENDING" | while IFS='|' read -r t root path kb; do
  [[ -n "$path" ]] || continue
  printf '  %-8s %7s  %s\n' "$t" "$(fmt_size "$kb")" "${path#"$root"/}"
done
echo ""
echo "Total: $(fmt_size "$TOTAL_KB")"

if [[ $DRY_RUN -eq 1 ]]; then
  echo "(dry run - nothing deleted)"
  exit 0
fi

# The default set costs a download or a boot to rebuild. World, seed bank and
# backups cost human time, so those ask.
NEEDS_CONFIRM=0
for t in $OPTIONAL_TARGETS; do
  has_target "$t" && NEEDS_CONFIRM=1
done

if [[ $NEEDS_CONFIRM -eq 1 && $ASSUME_YES -eq 0 ]]; then
  echo ""
  echo "This includes state that does not come back on its own:"
  has_target world && echo "  world   - the local world and player data"
  has_target seeds && echo "  seeds   - the seed candidate bank and its renders"
  has_target backups && echo "  backups - local backup archives"
  read -rp "Type CLEAN to confirm: " CONFIRM
  if [[ "$CONFIRM" != "CLEAN" ]]; then
    echo "Confirmation not received. Nothing deleted."
    exit 1
  fi
fi

echo ""
printf '%s%s' "$PENDING" "$PLATFORM_PENDING" | while IFS='|' read -r t root path kb; do
  [[ -n "$path" ]] || continue
  rm -rf "$path"
  printf '  removed  %s\n' "${path#"$root"/}"
done

# The local profile keeps the world in a named volume, off the Docker Desktop
# file share, so the host paths above hold nothing. Matched by suffix because
# compose prefixes the project name.
if has_target world; then
  for vol in $(docker volume ls -q 2>/dev/null | grep -E '(^|_)local-world$' || true); do
    if docker volume rm "$vol" >/dev/null 2>&1; then
      printf '  removed  volume %s\n' "$vol"
    else
      printf '  WARN     volume %s is still in use — run ./dev down first\n' "$vol" >&2
    fi
  done
fi

echo ""
echo "Freed roughly $(fmt_size "$TOTAL_KB")."
if [[ -n "$PLATFORM" ]]; then
  echo "Platform build output is gone: rebuild the mods, then re-run ./dev link"
  echo "so the farm's jar symlinks point at real files again."
fi
echo "Next: ./dev pull (or ./dev link) then ./dev up"
