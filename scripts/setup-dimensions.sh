#!/usr/bin/env bash
# setup-dimensions.sh - Create custom dimensions and link portals via RCON.
#
# One-time setup script. Run after the server is healthy with the
# custom-dimensions mod loaded. Creates each dimension from
# config/dimensions.txt, then links portal blocks to them.
#
# Usage:
#   ./scripts/setup-dimensions.sh              # execute via RCON
#   ./scripts/setup-dimensions.sh --dry-run    # print commands without executing
#
# Prerequisites:
#   - Server running and RCON responding (not autopaused)
#   - custom-dimensions mod installed
#   - docker exec access to the mc container
#
# The seeded-dimensions mod handles per-dimension seeds separately via
# config/seeded-dimensions.json — this script only creates dimensions
# and links portals.
#
# Portal link command syntax is best-effort — verify against the mod's
# actual /portal link usage if the commands fail.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  log "Dry run — commands will be printed but not executed"
fi

DIMENSIONS_FILE="$PROJECT_DIR/config/dimensions.txt"
[[ -f "$DIMENSIONS_FILE" ]] || die "Dimensions file not found: $DIMENSIONS_FILE"

run_rcon() {
  if $DRY_RUN; then
    echo "  [dry-run] rcon $1"
  else
    rcon "$1"
  fi
}

created=0
skipped=0
linked=0

declare -a names=()
declare -a types=()
declare -a scales=()
declare -a portal_blocks=()
declare -a ignitors=()

log "Reading dimensions from $DIMENSIONS_FILE..."

while IFS='|' read -r name type scale seed portal_block ignitor group; do
  [[ "$name" =~ ^#.*$ || -z "$name" ]] && continue

  if [[ -n "${SEED:-}" && "$seed" == "$SEED" ]]; then
    warn "Skipping $name — seed matches main server seed"
    skipped=$((skipped + 1))
    continue
  fi

  names+=("$name")
  types+=("$type")
  scales+=("$scale")
  portal_blocks+=("$portal_block")
  ignitors+=("$ignitor")

  log "Creating dimension: $name ($type)"
  run_rcon "dimension create $name $type"
  created=$((created + 1))
  sleep 0.5
done < "$DIMENSIONS_FILE"

log "Linking portals..."

i=0
while [[ $i -lt ${#names[@]} ]]; do
  # Portal link syntax may vary — check custom-dimensions mod docs
  run_rcon "portal link ${portal_blocks[$i]} ${names[$i]} ${ignitors[$i]} ${scales[$i]}"
  linked=$((linked + 1))
  sleep 0.3
  i=$((i + 1))
done

echo ""
log "Done. Created: $created, Skipped: $skipped, Portals linked: $linked"
