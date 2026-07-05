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
MODS_FILE="$PROJECT_DIR/config/modrinth-mods.txt"

for arg in "$@"; do
  case "$arg" in
    --version)
      shift
      TARGET_VERSION="$1"
      shift
      ;;
    --apply) APPLY=1 ;;
  esac
done

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
LINE_ORIGINALS=()
SLUGS_ONLY=()

while IFS= read -r line; do
  stripped="${line%%#*}"
  stripped="$(echo "$stripped" | xargs 2>/dev/null || echo "")"

  if [[ -z "$stripped" ]] || [[ "$stripped" == datapack:* ]] || [[ "$stripped" == resourcepack:* ]]; then
    LINE_TYPES+=("passthrough")
    LINE_SLUGS+=("")
    LINE_ORIGINALS+=("$line")
  else
    slug="${stripped%%:*}"
    LINE_TYPES+=("mod")
    LINE_SLUGS+=("$slug")
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
    OUTPUT_LINES+=("${slug}:${ver_id}")
  else
    echo "  $slug - ✗ no 1.21.x build found - keeping as-is"
    OUTPUT_LINES+=("# FIXME: no 1.21.x build - $slug")
    OUTPUT_LINES+=("${LINE_ORIGINALS[$i]}")
  fi
done

echo ""
echo "=================================================================="

# --- output -------------------------------------------------------------------
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
