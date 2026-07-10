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
# Command grammar (from the mod's brigadier trees — keep in sync with
# DimensionCommand.java and PortalCommand.java):
#   dimension create <name> <type> [<seed>] ["<biomes>"] [<peaceful>]
#   portal link <id> <frame> <igniter> <target> <color> <light> [<scale>] [<cooldown>]
# Notes:
#   - <biomes> is a QUOTED string (comma lists contain chars illegal in
#     unquoted brigadier strings).
#   - <target> is adventure:<name> — the mod registers all custom
#     dimensions under the adventure: namespace.
#   - <color> is a 6-digit hex WITHOUT '#'; <light> is 0-15.
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

# Portal particle colour by dimension group (6-digit hex, no '#').
portal_color() {
  case "$1" in
    nether) echo "FF5555" ;;
    end) echo "AA00AA" ;;
    paradise_lost) echo "FFD700" ;;
    void) echo "00FFFF" ;;
    *) echo "55FF55" ;;
  esac
}

PORTAL_LIGHT=11

created=0
skipped=0
linked=0

declare -a names=()
declare -a scales=()
declare -a portal_blocks=()
declare -a ignitors=()
declare -a groups=()

log "Reading dimensions from $DIMENSIONS_FILE..."

# shellcheck disable=SC2034
while IFS='|' read -r name type scale seed portal_block ignitor group biome peaceful; do
  [[ "$name" =~ ^#.*$ || -z "$name" ]] && continue

  if [[ "$seed" != "server" && -n "${SEED:-}" && "$seed" == "$SEED" ]]; then
    warn "Skipping $name — seed matches main server seed"
    skipped=$((skipped + 1))
    continue
  fi

  # Resolve "server" to the actual server seed
  dim_seed="$seed"
  if [[ "$seed" == "server" ]]; then
    dim_seed="${SEED:-}"
  fi
  if [[ -z "$dim_seed" ]]; then
    warn "Skipping $name — no seed (set SEED in .env or pin one in dimensions.txt)"
    skipped=$((skipped + 1))
    continue
  fi

  names+=("$name")
  scales+=("$scale")
  portal_blocks+=("$portal_block")
  ignitors+=("$ignitor")
  groups+=("$group")

  # Brigadier tree is positional: seed must precede biome, biome must
  # precede peaceful. Biome is a quoted string; use "" as the explicit
  # empty placeholder when only peaceful is set.
  dim_args="$name $type $dim_seed"
  if [[ -n "$biome" ]]; then
    dim_args="$dim_args \"$biome\""
  elif [[ "$peaceful" == "true" ]]; then
    dim_args="$dim_args \"\""
  fi
  [[ "$peaceful" == "true" ]] && dim_args="$dim_args true"

  log "Creating dimension: $name ($type${biome:+, biome: $biome}${peaceful:+, peaceful})"
  run_rcon "dimension create $dim_args"
  created=$((created + 1))
  sleep 0.5
done < "$DIMENSIONS_FILE"

log "Linking portals..."

i=0
while [[ $i -lt ${#names[@]} ]]; do
  # portal link <id> <frame> <igniter> <target> <color> <light> <scale>
  # The dimension name doubles as the portal id.
  run_rcon "portal link ${names[$i]} ${portal_blocks[$i]} ${ignitors[$i]} adventure:${names[$i]} $(portal_color "${groups[$i]}") $PORTAL_LIGHT ${scales[$i]}"
  linked=$((linked + 1))
  sleep 0.3
  i=$((i + 1))
done

echo ""
log "Done. Created: $created, Skipped: $skipped, Portals linked: $linked"
