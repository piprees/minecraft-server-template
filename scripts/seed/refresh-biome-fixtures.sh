#!/usr/bin/env bash
# refresh-biome-fixtures.sh - Re-dump the biome parity fixtures from a running
# server's LIVE biome source.
#
# Context: test_biome_parity.py diffs scripts/seed/testdata/biome_grid/*.csv
# against the Python biome sampler (build_from_spec) with zero tolerance.
# Those files are dumps of the real getBiome() call chain at quart y=16
# (block y=64), so any change to the biome sampling in the mod, the noise
# configs, or the biome parameter table makes them stale. The test compares
# each fixture's stackVersion against the running stack and names this
# script when they disagree.
#
# Usage:
#   ./scripts/seed/refresh-biome-fixtures.sh [dimension ...]
#
#   dimension   namespaced or bare slug (e.g. adventure:the_overgrowth or
#               the_overgrowth). Defaults to the 5 census-fixture dimensions.
#
# Environment:
#   MC_CONTAINER   container to talk to (default: mc)
#
# The server MUST have the CURRENT mod jar installed and every target
# dimension loaded. Build and install first (mods/AGENTS.md verification
# loop), then run this.
#
# Gotchas: sample-biome-grid writes a SINGLE biome_grid.csv (overwritten
# each call), so this script copies and renames after each dim. Must run
# on macOS bash 3.2 - no mapfile, no ${var,,}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/testdata/biome_grid"
MC_CONTAINER="${MC_CONTAINER:-mc}"

RADIUS=768
STEP=64

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

# Default: the same 5 dimensions the census fixtures cover.
DEFAULT_DIMS="the_blackstone_keep the_dustbowl the_gilded_pit the_luminous_caverns the_overgrowth"

DIMS=""
if [[ $# -gt 0 ]]; then
  DIMS="$*"
else
  DIMS="$DEFAULT_DIMS"
fi

if [[ -z "${DIMS// /}" ]]; then
  echo "No dimensions given." >&2
  echo "Usage: $(basename "$0") <dimension> [dimension ...]" >&2
  exit 1
fi

echo "Dumping biome grids from container '$MC_CONTAINER' (ns: $NS, radius=$RADIUS step=$STEP)"

# Queue every world first so loads drain in parallel.
for dim in $DIMS; do
  bare="${dim##*:}"
  docker exec -i "$MC_CONTAINER" rcon-cli "customdim load $bare" > /dev/null
done

# Brief pause for queued loads to drain (END_SERVER_TICK).
sleep 3

mkdir -p "$FIXTURES"
COUNT=0
for dim in $DIMS; do
  case "$dim" in
    *:*) full="$dim" ;;
    *) full="$NS:$dim" ;;
  esac
  bare="${full##*:}"

  attempt=0
  while :; do
    out="$(docker exec -i "$MC_CONTAINER" rcon-cli \
      "customdim sample-biome-grid $full $RADIUS $STEP" 2>&1 || true)"
    case "$out" in
      *"grid "*" points"*) break ;;
    esac
    attempt=$((attempt + 1))
    if [[ $attempt -ge 15 ]]; then
      echo "ERROR: $full never became loadable after $attempt attempts." >&2
      echo "       Last response: $out" >&2
      exit 1
    fi
    sleep 2
  done

  # Copy the single biome_grid.csv out with a dim-specific name.
  dest="$FIXTURES/${full%%:*}__${bare}.csv"
  docker cp "$MC_CONTAINER:/data/config/custom-dimensions/biome_grid.csv" "$dest"
  echo "  $full -> $(basename "$dest") ($out)"
  COUNT=$((COUNT + 1))
done

# Validate every fixture carries a stackVersion stamp.
python3 - "$FIXTURES" <<'PYEOF'
import sys
from pathlib import Path

fixtures = Path(sys.argv[1])
files = sorted(fixtures.glob("*.csv"))
stamps = {}
for f in files:
    version = None
    for line in f.read_text().splitlines():
        if line.startswith("# stackVersion="):
            # Parse "# stackVersion=X.Y.Z kind=... generatedAt=..."
            for part in line[2:].split():
                if part.startswith("stackVersion="):
                    version = part.split("=", 1)[1]
                    break
            break
    stamps[f.name] = version

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
echo "Now run: python3 -m pytest scripts/seed/test_biome_parity.py -q"
