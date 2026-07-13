#!/usr/bin/env bash
# pin-mod-versions.sh - Re-pin every mod in modrinth-mods.txt to its latest
# Modrinth build for the target MC version (exact match first, then falling
# back down the 1.21.x chain). Pinning makes deploys reproducible - the itzg
# image downloads exactly slug:versionId, never "latest".
#
# This is what the weekly mod-updates.yml workflow runs (--apply) to build
# its dependency-update PR; run it manually when bumping MC_VERSION.
# Mods with no 1.21.x build are kept as-is with a "# FIXME" comment above
# them (which build-mod-update-report.py surfaces in the PR body).
#
# Usage:
#   ./scripts/pin-mod-versions.sh                   # uses MC_VERSION; writes
#                                                   # config/modrinth-mods.pinned.txt for review
#   ./scripts/pin-mod-versions.sh --version 1.21.1  # override target version
#   ./scripts/pin-mod-versions.sh --apply           # also overwrite modrinth-mods.txt
#   ./scripts/pin-mod-versions.sh --file <path>     # re-pin an arbitrary mod list
#                                                   # IN PLACE (consumer overlay
#                                                   # mods-extra.txt; review via git diff)
#
# Comment-only lines survive re-pinning; inline comments on mod lines do NOT
# (lines are rewritten as bare slug:versionId) - comment above the mod instead.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

# --- load .env ----------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

TARGET_VERSION="${MC_VERSION:-1.21.1}"
APPLY=0
FILE_OVERRIDE=0
MODS_FILE="$PROJECT_DIR/config/modrinth-mods.txt"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      TARGET_VERSION="$2"
      shift 2
      ;;
    --apply)
      APPLY=1
      shift
      ;;
    --file)
      MODS_FILE="$2"
      FILE_OVERRIDE=1
      shift 2
      ;;
    *) shift ;;
  esac
done

if [[ ! -f "$MODS_FILE" ]]; then
  echo "ERROR: mod list not found: $MODS_FILE" >&2
  exit 1
fi

# Build a list of 1.21.x versions to try, in priority order:
# exact match first, then descending from 1.21.1 down to 1.21
FALLBACK_VERSIONS=("$TARGET_VERSION")
# Extract minor number (e.g. 11 from 1.21.1)
MINOR="${TARGET_VERSION##1.21.}"
if [[ "$MINOR" == "$TARGET_VERSION" ]]; then
  MINOR="0"
fi
for ((i = MINOR - 1; i >= 0; i--)); do
  if [[ $i -eq 0 ]]; then
    FALLBACK_VERSIONS+=("1.21")
  else
    FALLBACK_VERSIONS+=("1.21.$i")
  fi
done

echo "Target: $TARGET_VERSION"
FALLBACK_CSV=$(IFS=,; echo "${FALLBACK_VERSIONS[*]}")
echo "Fallback chain: ${FALLBACK_VERSIONS[*]}"
echo ""

# --- collect slugs and preserve file structure --------------------------------
LINE_TYPES=()
LINE_SLUGS=()
LINE_SUFFIXES=()
LINE_ORIGINALS=()
SLUGS_ONLY=()

while IFS= read -r line; do
  stripped="${line%%#*}"
  stripped="$(echo "$stripped" | xargs 2>/dev/null || echo "")"

  if [[ -z "$stripped" ]] || [[ "$stripped" == datapack:* ]] || [[ "$stripped" == resourcepack:* ]]; then
    LINE_TYPES+=("passthrough")
    LINE_SLUGS+=("")
    LINE_SUFFIXES+=("")
    LINE_ORIGINALS+=("$line")
  else
    # A trailing ? marks the mod optional - preserve it through re-pinning
    suffix=""
    if [[ "$stripped" == *\? ]]; then
      suffix="?"
      stripped="${stripped%\?}"
    fi
    slug="${stripped%%:*}"
    LINE_TYPES+=("mod")
    LINE_SLUGS+=("$slug")
    LINE_SUFFIXES+=("$suffix")
    LINE_ORIGINALS+=("$line")
    SLUGS_ONLY+=("$slug")
  fi
done < "$MODS_FILE"

# --- resolve all versions via single Python process (connection reuse) --------
API_RESULTS=$(printf '%s\n' "${SLUGS_ONLY[@]}" | python3 "$SCRIPT_DIR/modrinth-api.py" pin "$TARGET_VERSION" "$FALLBACK_CSV")

# --- build output from API results -------------------------------------------
OUTPUT_LINES=()
slug_idx=0

for i in "${!LINE_TYPES[@]}"; do
  if [[ "${LINE_TYPES[$i]}" == "passthrough" ]]; then
    OUTPUT_LINES+=("${LINE_ORIGINALS[$i]}")
    continue
  fi

  slug="${LINE_SLUGS[$i]}"
  result=$(echo "$API_RESULTS" | sed -n "$((slug_idx + 1))p")
  slug_idx=$((slug_idx + 1))

  status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','none'))" 2>/dev/null || echo "none")

  if [[ "$status" == "found" ]]; then
    ver_id=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
    ver_num=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])" 2>/dev/null)
    matched_ver=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['matched'])" 2>/dev/null)

    if [[ "$matched_ver" == "$TARGET_VERSION" ]]; then
      echo "  $slug - ✓ $ver_num (exact $TARGET_VERSION)"
    else
      echo "  $slug - ~ $ver_num (fallback $matched_ver)"
    fi
    OUTPUT_LINES+=("${slug}:${ver_id}${LINE_SUFFIXES[$i]}")
  else
    echo "  $slug - ✗ no 1.21.x build found - keeping as-is"
    OUTPUT_LINES+=("# FIXME: no 1.21.x build - $slug")
    OUTPUT_LINES+=("${LINE_ORIGINALS[$i]}")
  fi
done

echo ""
echo "=================================================================="

# --- output -------------------------------------------------------------------
if [[ $FILE_OVERRIDE -eq 1 ]]; then
  # In-place re-pin of an arbitrary list (consumer overlay) - review via git diff
  printf '%s\n' "${OUTPUT_LINES[@]}" > "$MODS_FILE"
  echo "Re-pinned in place: $MODS_FILE"
  echo "Review with: git diff $MODS_FILE"
  exit 0
fi

OUTPUT_FILE="$PROJECT_DIR/config/modrinth-mods.pinned.txt"
printf '%s\n' "${OUTPUT_LINES[@]}" > "$OUTPUT_FILE"
echo "Pinned mod list written to: config/modrinth-mods.pinned.txt"

if [[ $APPLY -eq 1 ]]; then
  cp "$OUTPUT_FILE" "$MODS_FILE"
  echo "Applied to: config/modrinth-mods.txt"
fi

echo ""
echo "Review the output, then apply with:"
echo "  cp config/modrinth-mods.pinned.txt config/modrinth-mods.txt"

# --- re-pin the client manifest (adventure.mrpack.json) -----------------------
# Every slug:versionId entry in _clientMods gets its versionId bumped to the
# newest build for TARGET_VERSION, exactly like modrinth-mods.txt. Bare slugs
# (legacy) are pinned too. Slugs listed in _clientMods.holds keep their current
# pin (holds map slug -> reason; used when a newer build breaks a dependant,
# e.g. Xaero WM 1.42 vs maplink). Resource/shader packs are left alone (they
# follow their own resolution in build-modpack.sh and don't cause registry
# mismatches).
MANIFEST="$PROJECT_DIR/modpack/adventure.mrpack.json"
if [[ -f "$MANIFEST" ]]; then
  echo ""
  echo "==> Re-pinning client manifest ($MANIFEST)..."

  python3 - "$MANIFEST" "$TARGET_VERSION" "$FALLBACK_CSV" << 'MANIFEST_PIN'
import json, sys, time, urllib.request

manifest_path, target, fallback_csv = sys.argv[1:4]
fallbacks = fallback_csv.split(",")
m = json.load(open(manifest_path))
ua = "adventure/pin-mod-versions"
updated = 0
holds = m.get("_clientMods", {}).get("holds", {})

def resolve_latest(slug):
    """Return (version_id, version_number, matched_mc) or None."""
    for mc in fallbacks:
        url = f"https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%22{mc}%22%5D&loaders=%5B%22fabric%22%5D"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua})
            versions = json.loads(urllib.request.urlopen(req, timeout=30).read())
            if versions:
                v = versions[0]
                return v["id"], v["version_number"], mc
        except Exception:
            pass
        time.sleep(0.35)
    return None

for key in ("required", "optional"):
    entries = m.get("_clientMods", {}).get(key, [])
    new_entries = []
    for entry in entries:
        if not isinstance(entry, str):
            new_entries.append(entry)
            continue
        slug = entry.split(":")[0]
        if slug in holds:
            print(f"  {slug}: HELD - {holds[slug]}")
            new_entries.append(entry)
            continue
        result = resolve_latest(slug)
        if result:
            vid, ver, mc = result
            old_vid = entry.split(":")[1] if ":" in entry else "unpinned"
            if old_vid != vid:
                print(f"  {slug}: {old_vid} -> {vid} ({ver}, {mc})")
                updated += 1
            else:
                print(f"  {slug}: up to date ({ver})")
            new_entries.append(f"{slug}:{vid}")
        else:
            print(f"  {slug}: no {target} build found - keeping as-is")
            new_entries.append(entry)
        time.sleep(0.35)
    m["_clientMods"][key] = new_entries

def resolve_pack(slug):
    """Resolve a resource/shader pack — no loader filter, MC version preferred."""
    for mc in fallbacks:
        url = f"https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%22{mc}%22%5D&limit=1"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": ua})
            versions = json.loads(urllib.request.urlopen(req, timeout=30).read())
            if versions:
                v = versions[0]
                return v["id"], v["version_number"], mc
        except Exception:
            pass
        time.sleep(0.35)
    # Fallback: no MC version filter (some packs aren't tagged)
    url = f"https://api.modrinth.com/v2/project/{slug}/version?limit=1"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": ua})
        versions = json.loads(urllib.request.urlopen(req, timeout=30).read())
        if versions:
            v = versions[0]
            return v["id"], v["version_number"], "any"
    except Exception:
        pass
    return None

def repin_packs(section_name):
    """Re-pin slug:versionId entries in _resourcePacks or _shaderPacks."""
    packs = m.get(section_name, {}).get("packs", [])
    new_packs = []
    count = 0
    for p in packs:
        if isinstance(p, dict):
            slug_field = p.get("slug", "")
            slug = slug_field.split(":")[0]
            result = resolve_pack(slug)
            if result:
                vid, ver, mc = result
                old_vid = slug_field.split(":")[1] if ":" in slug_field else "unpinned"
                if old_vid != vid:
                    print(f"  {slug}: {old_vid} -> {vid} ({ver})")
                    count += 1
                else:
                    print(f"  {slug}: up to date ({ver})")
                p["slug"] = f"{slug}:{vid}"
            else:
                print(f"  {slug}: no build found - keeping as-is")
            new_packs.append(p)
            time.sleep(0.35)
        elif isinstance(p, str):
            slug = p.split(":")[0]
            result = resolve_pack(slug)
            if result:
                vid, ver, mc = result
                old_vid = p.split(":")[1] if ":" in p else "unpinned"
                if old_vid != vid:
                    print(f"  {slug}: {old_vid} -> {vid} ({ver})")
                    count += 1
                else:
                    print(f"  {slug}: up to date ({ver})")
                new_packs.append(f"{slug}:{vid}")
            else:
                print(f"  {slug}: no build found - keeping as-is")
                new_packs.append(p)
            time.sleep(0.35)
        else:
            new_packs.append(p)
    m[section_name]["packs"] = new_packs
    return count

rp_count = repin_packs("_resourcePacks") if "_resourcePacks" in m else 0
sp_count = repin_packs("_shaderPacks") if "_shaderPacks" in m else 0

with open(manifest_path, "w") as f:
    json.dump(m, f, indent=2)
    f.write("\n")
print(f"\n  {updated} client mod(s), {rp_count} resource pack(s), {sp_count} shader pack(s) updated in {manifest_path}")
MANIFEST_PIN
fi
