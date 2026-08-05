#!/usr/bin/env bash
# clean-rig.sh — boot a minimal itzg server (fabric-api + customdimensions
# only) with a dimension config pre-seeded, and prove the dimension
# registers and becomes queryable.
#
# The rig answers the question a full stack cannot answer cleanly: does a
# configured dimension's boot registration work, with no other mod's mixins
# in the way? Two ordering rules make or break it, both enforced here:
#
#   1. The dimension JSON must be in place BEFORE boot — MultiverseConfig
#      loads once, at createWorlds. A file added later is never read, and
#      `customdim load` of its slug returns silently (the mod WARNs about
#      the missing DimensionOptions).
#   2. SEED_ROLL_MODE must be UNSET — with it set, WorldLoaderMixin skips
#      registerDimensions() and only `/customdim create` dimensions work.
#
# Usage:
#   ./scripts/seed/clean-rig.sh <dimension-json> [--slug NAME]
#        [--base DIR] [--container NAME] [--memory 6G] [--keep]
#
#   dimension-json  a config/custom-dimensions/dimensions/<slug>.json file
#   --slug          dimension name (default: json filename stem)
#   --base          a prepared warmup base dir to clone the launcher,
#                   libraries and configs from (default:
#                   <consumer>/.seedtest/base via CONSUMER_DIR, falling
#                   back to ~/Projects/elfydd/.seedtest/base)
#   --keep          leave the container and workdir up for interactive
#                   probing (docker exec <name> rcon-cli ...)
#
# Template-only: NOT in the bundle MANIFEST. Needs Docker and a warmed
# base dir (./dev seed-roll --warmup-only creates one).
set -euo pipefail

DIM_JSON="${1:-}"
[[ -n "$DIM_JSON" && -f "$DIM_JSON" ]] || {
  echo "Usage: $0 <dimension-json> [--slug NAME] [--base DIR] [--keep]" >&2
  exit 1
}
shift

SLUG="$(basename "$DIM_JSON" .json)"
BASE="${CONSUMER_DIR:-$HOME/Projects/elfydd}/.seedtest/base"
CONTAINER="cleanrig"
MEMORY="6G"
KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slug) SLUG="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --container) CONTAINER="$2"; shift 2 ;;
    --memory) MEMORY="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

[[ -d "$BASE/mods" ]] || {
  echo "ERROR: no warmed base dir at $BASE — run ./dev seed-roll --warmup-only first" >&2
  exit 1
}

WORK="$(mktemp -d /tmp/cleanrig.XXXXXX)"
cleanup() {
  if [[ "$KEEP" == 1 ]]; then
    echo "kept: container '$CONTAINER', workdir $WORK"
  else
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

echo "== clean rig: $SLUG =="
# Launcher, libraries, and base configs from the warmed dir; mods reduced
# to exactly fabric-api + customdimensions (the point of the rig).
rsync -a --exclude 'mods' --exclude 'world*' "$BASE/" "$WORK/"
mkdir -p "$WORK/mods"
cp "$BASE"/mods/fabric-api-*.jar "$WORK/mods/" 2> /dev/null \
  || cp "$BASE"/mods/fabric_api*.jar "$WORK/mods/"
cp "$BASE"/mods/customdimensions*.jar "$WORK/mods/"
mkdir -p "$WORK/config/custom-dimensions/dimensions"
# Rule 1: config in place before boot.
cp "$DIM_JSON" "$WORK/config/custom-dimensions/dimensions/$SLUG.json"

NS="$(python3 -c "
import json, sys
try:
    print(json.load(open('$WORK/config/custom-dimensions/settings.json')).get('namespace', 'adventure'))
except Exception:
    print('adventure')")"

docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
# Rule 2: no SEED_ROLL_MODE. Flags mirror seed_worker.start_container's
# clean-boot set (MAX_TICK_TIME=-1: no watchdog kill on a slow first tick).
docker run -d --name "$CONTAINER" \
  --memory "$MEMORY" \
  --log-opt max-size=5m --log-opt max-file=1 \
  -e EULA=TRUE -e TYPE=FABRIC -e VERSION=1.21.1 \
  -e SEED=1 -e MEMORY="$MEMORY" \
  -e USE_AIKAR_FLAGS=false \
  -e ENABLE_RCON=TRUE -e RCON_PASSWORD=cleanrig \
  -e ONLINE_MODE=FALSE -e ENABLE_AUTOPAUSE=FALSE \
  -e OVERRIDE_SERVER_PROPERTIES=true \
  -e MAX_TICK_TIME=-1 \
  -e VIEW_DISTANCE=6 -e SIMULATION_DISTANCE=4 \
  -e SPAWN_CHUNK_RADIUS=0 \
  -v "$WORK:/data" itzg/minecraft-server:latest > /dev/null

echo "booting (cap 240s)..."
n=0
until docker exec "$CONTAINER" rcon-cli list > /dev/null 2>&1; do
  n=$((n + 1))
  if [[ $n -ge 48 ]]; then
    echo "ERROR: server never became ready" >&2
    docker logs "$CONTAINER" --tail 30 >&2
    exit 1
  fi
  sleep 5
done

echo "server ready; loading $NS:$SLUG"
docker exec "$CONTAINER" rcon-cli "customdim load $SLUG" > /dev/null
RESULT=""
for _ in $(seq 1 12); do
  OUT="$(docker exec "$CONTAINER" rcon-cli "execute in $NS:$SLUG run seed" 2>&1 || true)"
  case "$OUT" in
    *Seed*) RESULT="$OUT"; break ;;
  esac
  sleep 2
done

echo "-- registration log lines --"
docker logs "$CONTAINER" 2>&1 \
  | grep -iE "registered dimension|Created runtime|No DimensionOptions" \
  | tail -5 || true

if [[ -n "$RESULT" ]]; then
  echo "PASS: $NS:$SLUG is queryable ($RESULT)"
  exit 0
fi
echo "FAIL: $NS:$SLUG never became queryable — check the log lines above" >&2
exit 1
