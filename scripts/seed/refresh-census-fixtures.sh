#!/usr/bin/env bash
# refresh-census-fixtures.sh - Re-dump the Java/Python parity fixtures from a
# running server's LIVE structure placement calculator.
#
# Context: test_noise_parity.py diffs scripts/seed/testdata/census/*.json
# against the Python mirror with zero tolerance. Those files are dumps of the
# real calculator, so any change to the placement maths in
# NoiseFieldIndex/StructureNoise makes every one of them stale. The test
# compares each fixture's stackVersion against the running stack and names
# this script when they disagree, so it is the one documented way to
# regenerate them.
#
# Usage:
#   ./scripts/seed/refresh-census-fixtures.sh [dimension ...]
#
#   dimension   namespaced or bare slug (e.g. adventure:the_overgrowth or
#               the_overgrowth). Defaults to whatever fixtures already exist,
#               so a plain run refreshes exactly the current set.
#
# Environment:
#   MC_CONTAINER   container to talk to (default: mc)
#
# Needs a running server with the CURRENT mod jar installed — a dump from an
# old jar is exactly the staleness this exists to fix. Build and install
# first (mods/AGENTS.md verification loop), then run this.
#
# Gotchas: the dump is written inside the container and copied out, so the
# container's namespace decides the filename (adventure__<slug>.json). A
# configured dimension has no ServerWorld until something enters it, so each
# one is loaded first and the census retried while the queued load drains on
# END_SERVER_TICK. Must run on macOS bash 3.2 - no mapfile, no ${var,,}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/testdata/census"
MC_CONTAINER="${MC_CONTAINER:-mc}"

if ! docker inspect "$MC_CONTAINER" > /dev/null 2>&1; then
  echo "ERROR: container '$MC_CONTAINER' not found." >&2
  echo "       Start the local stack first (./dev up in a consumer repo)." >&2
  exit 1
fi

NS="$(docker exec "$MC_CONTAINER" sh -c \
  'cat /data/config/custom-dimensions/settings.json 2>/dev/null' \
  | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("namespace", "adventure"))
except Exception:
    print("adventure")' 2> /dev/null || echo adventure)"

DIMS=""
if [[ $# -gt 0 ]]; then
  DIMS="$*"
else
  # Default to the fixtures already committed, so a bare run is a refresh
  # rather than a redefinition of what is covered. The filename carries the
  # namespace it was dumped under, so keep it: dropping it and re-adding $NS
  # would aim a minecraft__ fixture at the configured namespace instead.
  for f in "$FIXTURES"/*.json; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f" .json)"
    DIMS="$DIMS ${base%%__*}:${base#*__}"
  done
fi

if [[ -z "${DIMS// /}" ]]; then
  echo "No fixtures in $FIXTURES and no dimensions given." >&2
  echo "Usage: $(basename "$0") <dimension> [dimension ...]" >&2
  exit 1
fi

echo "Dumping structure censuses from container '$MC_CONTAINER' (ns: $NS)"
# The copy below takes the whole census directory, so anything left there by
# an earlier session would arrive as a fixture nobody asked for.
docker exec "$MC_CONTAINER" sh -c \
  'rm -f /data/config/custom-dimensions/census/*.json' 2> /dev/null || true
# Queue every world first, so the loads drain in parallel with each other
# rather than one round-trip at a time.
for dim in $DIMS; do
  bare="${dim##*:}"
  docker exec -i "$MC_CONTAINER" rcon-cli "customdim load $bare" > /dev/null
done

COUNT=0
for dim in $DIMS; do
  case "$dim" in
    *:*) full="$dim" ;;
    *) full="$NS:$dim" ;;
  esac
  attempt=0
  while :; do
    out="$(docker exec -i "$MC_CONTAINER" rcon-cli \
      "customdim structure-census $full" 2>&1 || true)"
    case "$out" in
      *"structure-census $full:"*) break ;;
    esac
    attempt=$((attempt + 1))
    if [[ $attempt -ge 15 ]]; then
      echo "ERROR: $full never became loadable after $attempt attempts." >&2
      echo "       Last response: $out" >&2
      exit 1
    fi
    sleep 2
  done
  echo "  ${out%% | *}"
  COUNT=$((COUNT + 1))
done

mkdir -p "$FIXTURES"
docker cp "$MC_CONTAINER:/data/config/custom-dimensions/census/." "$FIXTURES/"

# The dump is the whole point, so prove every fixture landed stamped rather
# than trusting the copy.
python3 - "$FIXTURES" <<'PYEOF'
import json
import sys
from pathlib import Path

fixtures = Path(sys.argv[1])
files = sorted(fixtures.glob("*.json"))
stamps = {}
for f in files:
    stamps[f.name] = json.loads(f.read_text()).get("stackVersion")

missing = [n for n, v in stamps.items() if v is None]
if missing:
    print("\nERROR: fixtures carry no stackVersion — the running server is on"
          " a jar that predates stamping.")
    for name in missing:
        print("  " + name)
    raise SystemExit(1)

distinct = sorted(set(stamps.values()))
if len(distinct) > 1:
    print("\nERROR: fixtures came from more than one stack: %s"
          % ", ".join(repr(d) for d in distinct))
    raise SystemExit(1)
print("\nAll %d fixture(s) at stackVersion %r." % (len(files), distinct[0]))
PYEOF

echo "Dumped $COUNT dimension(s) into $FIXTURES"
echo "Now run: python3 -m pytest scripts/seed/test_noise_parity.py -q"
