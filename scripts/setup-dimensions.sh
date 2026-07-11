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
#
# Idempotency: "dimension create" already rejects duplicates server-side
# (MultiverseConfig lookup by name), but only after a full RCON round trip —
# every boot was re-attempting all ~57 dimensions, each failing with
# "already exists" and burning its 0.5s sleep. dimension_exists() below reads
# the mod's persisted state directly to skip the create call entirely for
# dimensions that already exist. Portal linking still runs unconditionally
# for every dimension every boot (portal state can legitimately need
# relinking), matching "portal link"'s own idempotent duplicate check.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

# SERVER_DIR = where data/ actually lives. In production this script runs
# from the bundle cache (.stack/current/stack/scripts/), so PROJECT_DIR
# (derived from this script's own location) resolves to the bundle root, not
# the server root. Same derivation as deploy.sh/infra-deploy.sh — keep in sync.
if [[ "$SCRIPT_DIR" == *"/.stack/"* ]]; then
  SERVER_DIR="${SCRIPT_DIR%%/.stack/*}"
else
  SERVER_DIR="${SERVER_DIR:-$PROJECT_DIR}"
fi

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
  log "Dry run — commands will be printed but not executed"
fi

DIMENSIONS_FILE="$PROJECT_DIR/config/dimensions.txt"
[[ -f "$DIMENSIONS_FILE" ]] || die "Dimensions file not found: $DIMENSIONS_FILE"

# Persisted by com.customdimensions.config.MultiverseConfig — the source of
# truth for which dimensions already exist. There's no RCON "dimension list"
# command, so we read this file directly instead of round-tripping RCON.
DIMENSIONS_CONFIG="$SERVER_DIR/data/config/multiverse_config.json"

# True (exit 0) if a dimension with this name is already persisted.
dimension_exists() {
  local name="$1"
  [[ -f "$DIMENSIONS_CONFIG" ]] || return 1
  if command -v jq &> /dev/null; then
    jq -e --arg n "$name" 'any(.dimensions[]?; .name == $n)' "$DIMENSIONS_CONFIG" > /dev/null 2>&1
  else
    python3 - "$DIMENSIONS_CONFIG" "$name" << 'PYEOF'
import json, sys
path, name = sys.argv[1], sys.argv[2]
try:
    with open(path) as f:
        data = json.load(f)
except Exception:
    sys.exit(1)
sys.exit(0 if any(d.get("name") == name for d in data.get("dimensions", [])) else 1)
PYEOF
  fi
}

run_rcon() {
  if $DRY_RUN; then
    echo "  [dry-run] rcon $1"
  else
    local start elapsed response
    start=$(date +%s)
    # Call docker exec directly instead of lib.sh's rcon() helper — that
    # helper redirects stderr to /dev/null internally, which was also
    # swallowing the actual command feedback ("Created dimension...",
    # "already exists", connection timeouts) before we ever saw it here.
    response=$(docker exec "${CONTAINER_PREFIX:-}mc" rcon-cli "$1" 2>&1)
    elapsed=$(($(date +%s) - start))
    echo "  <- ${response:-(no response)} (${elapsed}s)"
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
existing=0
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

  # Skip the create round trip entirely for dimensions the mod already has
  # persisted — still queued above for the portal-link pass below.
  if dimension_exists "$name"; then
    log "Dimension already exists, skipping create: $name"
    existing=$((existing + 1))
    continue
  fi

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
log "Done. Created: $created, Already existed: $existing, Skipped: $skipped, Portals linked: $linked"
