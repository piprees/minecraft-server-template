#!/usr/bin/env bash
# =============================================================================
# roll-all.sh - Fully automated parallel seed roller for EVERY dimension
# =============================================================================
#
# Boots N Docker containers in SEED_ROLL_MODE (the custom-dimensions mod
# skips boot-time dimension creation), splits the rollable dimensions from
# config/multiverse_config.json across them, and measures M candidate seeds
# per dimension via `customdim create -> measure -> customdim destroy`
# (scripts/seed/seed_worker.py does the per-candidate work over a native
# RCON socket). Scoring is per-dimension and philosophy-driven
# (scripts/seed/dimension_profiles.py).
#
# On completion — or on Ctrl+C — it merges the per-worker CSVs, renders the
# top candidates per dimension (BlueMap), auto-picks the best seed per
# dimension, WRITES the winners into config/multiverse_config.json (with a
# .bak.TIMESTAMP backup), generates .seedtest/viewer.html and opens it.
#
# Usage:
#   ./roll-all.sh                                  # all dims, 16 candidates, 6 workers
#   ./roll-all.sh --dims the_gauntlet,the_boneyard --candidates 4 --workers 1
#   ./roll-all.sh --render all                     # render every candidate (slow)
#   ./roll-all.sh --render off --no-write          # measure + score only
#   ./roll-all.sh --clean                          # rebuild worker dirs from data/
#
# Options:
#   --candidates N   target measured candidates per dimension (default 16;
#                    resumable — already-measured candidates count)
#   --workers N      parallel containers (default 6; ~6G memory each)
#   --dims a,b,c     roll a subset of dimensions
#   --render MODE    winners (default: top --render-top per dim after
#                    scoring) | all (inline, slow) | off
#   --render-top N   renders per dimension in winners mode (default 3)
#   --no-write       don't write winners into multiverse_config.json
#   --fresh          discard previous measurements first
#   --clean          rebuild seedtest-all worker dirs from data/
#
# Environment: ROLL_MEMORY (default 6G/container), RCON_TIMEOUT (300s),
#              ROLL_IMAGE (itzg image pin — must match seed_worker.py default)
#
# Gotchas:
#   - Requires the fork custom-dimensions jar (customdim + SEED_ROLL_MODE)
#     in data/mods/ — ./dev up installs it.
#   - collective and bluemap are deliberately NOT stripped (dependencies /
#     renders); c2me DFC is forced off per worker (per-dimension seed trap).
#   - macOS bash 3.2 — no declare -A / mapfile.
# =============================================================================
set -euo pipefail

ROLL_MEMORY="${ROLL_MEMORY:-6G}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

CONFIG="$PROJECT_ROOT/config/multiverse_config.json"
LOCAL_DATA="$PROJECT_ROOT/data"
# EVERYTHING seedtest-related (measurements, renders, viewer, worker boot
# dirs) lives under .seedtest/ — nothing else lands in the consumer repo.
SEEDTEST="$PROJECT_ROOT/.seedtest"
WORK_BASE="$SEEDTEST/base"
MEASUREMENTS="$SEEDTEST/measurements.csv"

CANDIDATES=12
WORLD_CANDIDATES=12
WORKERS=6
DIMS=""
RENDER="all"
RENDER_TOP=3
WRITE_CONFIG=1
SKIP_WORLDS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidates) CANDIDATES="$2"; shift 2 ;;
    --world-candidates) WORLD_CANDIDATES="$2"; shift 2 ;;
    --no-worlds) SKIP_WORLDS=1; shift ;;
    --workers) WORKERS="$2"; shift 2 ;;
    --dims) DIMS="$2"; shift 2 ;;
    --render) RENDER="$2"; shift 2 ;;
    --render-top) RENDER_TOP="$2"; shift 2 ;;
    --no-write) WRITE_CONFIG=0; shift ;;
    --fresh) rm -f "$MEASUREMENTS" "$SEEDTEST"/worker-*.csv; shift ;;
    --clean) rm -rf "$SEEDTEST/base" "$SEEDTEST"/w[0-9]*; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v docker > /dev/null || { echo "Error: docker not found" >&2; exit 1; }
command -v python3 > /dev/null || { echo "Error: python3 required" >&2; exit 1; }
[[ -f "$CONFIG" ]] || { echo "Error: $CONFIG not found" >&2; exit 1; }
ls "$LOCAL_DATA/mods/"*.jar > /dev/null 2>&1 \
  || { echo "Error: no mods in data/mods — run ./dev up first" >&2; exit 1; }
ls "$LOCAL_DATA"/mods/customdimensions*.jar > /dev/null 2>&1 \
  || { echo "Error: customdimensions jar missing from data/mods" >&2; exit 1; }

mkdir -p "$SEEDTEST"

# ---------------------------------------------------------------------------
# Shared boot dir + per-worker dirs (Fabric + mods + configs from data/)
# ---------------------------------------------------------------------------
# Mods that shape worldgen/structures stay; pure gameplay/cosmetic mods are
# stripped for boot speed. collective (9+ mods depend on it) and bluemap
# (renders) are NOT stripped.
STRIP_PATTERNS="DistantHorizons-* dcintegration-* voicechat-* LuckPerms-*
ledger-* styled-chat-* essential_commands-* NoChatReports-* packetfixer-*
sound-physics-remastered-* appleskin-* bettercombat-* player-animation-lib-*
carryon-* netherportalfix-* netherportalspread-* FallingTree-*
letmedespawn-* Almanac-* fabric-seasons-* open-parties-and-claims-*
chipped-* DramaticDoors-* handcrafted-*"

prepare_base_dir() {
  if [[ -f "$WORK_BASE/.ready" ]]; then
    return 0
  fi
  echo "Preparing $WORK_BASE from local server data (one-time copy)..."
  mkdir -p "$WORK_BASE/mods"
  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    [[ -e "$LOCAL_DATA/$item" ]] && cp -a "$LOCAL_DATA/$item" "$WORK_BASE/"
  done
  cp "$LOCAL_DATA"/fabric-server-mc.*.jar "$WORK_BASE/" 2> /dev/null || true
  cp "$LOCAL_DATA/mods/"*.jar "$WORK_BASE/mods/"
  for dir in config defaultconfigs moonlight-global-datapacks villagerpacks; do
    [[ -d "$LOCAL_DATA/$dir" ]] && cp -a "$LOCAL_DATA/$dir" "$WORK_BASE/"
  done
  if [[ -d "$LOCAL_DATA/world/datapacks" ]]; then
    mkdir -p "$WORK_BASE/world-datapacks-template"
    cp -a "$LOCAL_DATA/world/datapacks/." "$WORK_BASE/world-datapacks-template/"
  fi
  local removed=0 pattern f
  for pattern in $STRIP_PATTERNS; do
    for f in "$WORK_BASE/mods/"$pattern; do
      [[ -f "$f" ]] && rm "$f" && removed=$((removed + 1))
    done
  done
  rm -rf "$WORK_BASE/mods/luckperms"
  # Per-dimension state from the live server breaks fresh rolls (AGENTS.md).
  rm -rf "$WORK_BASE/config/bluemap" "$WORK_BASE/config/DistantHorizons"
  echo "  $(ls "$WORK_BASE/mods/"*.jar | wc -l | tr -d ' ') mod JARs ($removed stripped)"
  touch "$WORK_BASE/.ready"
}

prepare_worker_dir() {
  local wdir="$SEEDTEST/w$1"
  [[ -f "$wdir/.ready" ]] && return 0
  mkdir -p "$wdir/mods"
  local jar
  for jar in "$WORK_BASE/mods/"*.jar; do
    ln "$jar" "$wdir/mods/" 2> /dev/null || cp "$jar" "$wdir/mods/"
  done
  local item
  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    [[ -e "$WORK_BASE/$item" ]] && cp -a "$WORK_BASE/$item" "$wdir/"
  done
  cp "$WORK_BASE"/fabric-server-mc.*.jar "$wdir/" 2> /dev/null || true
  local dir
  for dir in config defaultconfigs moonlight-global-datapacks villagerpacks world-datapacks-template; do
    [[ -d "$WORK_BASE/$dir" ]] && cp -a "$WORK_BASE/$dir" "$wdir/"
  done
  touch "$wdir/.ready"
}

# ---------------------------------------------------------------------------
# CSV merge (dedup exact rows; header once)
# ---------------------------------------------------------------------------
merge_measurements() {
  {
    echo "target,seed,metric,value"
    for f in "$MEASUREMENTS" "$SEEDTEST"/worker-*.csv; do
      # Unmatched globs / missing files must not trip set -e -o pipefail
      # (grep also exits 1 on a fully-filtered file).
      [[ -f "$f" ]] || continue
      grep -v '^target,seed,metric,value$' "$f" || true
    done | awk '!seen[$0]++'
  } > "$MEASUREMENTS.tmp"
  mv "$MEASUREMENTS.tmp" "$MEASUREMENTS"
}

# ---------------------------------------------------------------------------
# Worker fleets
# ---------------------------------------------------------------------------
WORKER_PIDS=""

run_fleet() {
  local mode="$1" prefix="$2" nworkers="$3"
  WORKER_PIDS=""
  local w
  for w in $(seq 0 $((nworkers - 1))); do
    local manifest="$SEEDTEST/work-${prefix}${w}.txt"
    local mvconfig="$SEEDTEST/mvconfig-${prefix}${w}.json"
    [[ -s "$manifest" ]] || continue
    prepare_worker_dir "$w"
    python3 "$SCRIPT_DIR/seed_worker.py" \
      --worker-id "$w" \
      --workdir "$SEEDTEST/w$w" \
      --manifest "$manifest" \
      --mvconfig "$mvconfig" \
      --base-config "$CONFIG" \
      --seedtest "$SEEDTEST" \
      --mode "$mode" \
      --memory "$ROLL_MEMORY" &
    WORKER_PIDS="$WORKER_PIDS $!"
  done
  local pid rc=0
  for pid in $WORKER_PIDS; do
    wait "$pid" || rc=1
  done
  WORKER_PIDS=""
  return "$rc"
}

# ---------------------------------------------------------------------------
# Live report: regenerate viewer.html every 45s while fleets run, so the
# report can be watched in a browser (it meta-refreshes itself).
# ---------------------------------------------------------------------------
REPORTER_PID=""
start_reporter() {
  (
    while true; do
      sleep 45
      merge_measurements 2> /dev/null || true
      python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
        --config "$CONFIG" --seedtest "$SEEDTEST" \
        ${DIMS:+--dims "$DIMS"} --viewer > /dev/null 2>&1 || true
    done
  ) &
  REPORTER_PID=$!
  echo "Live report: $SEEDTEST/viewer.html (regenerates every 45s — open it now)"
}

stop_reporter() {
  [[ -n "$REPORTER_PID" ]] && kill "$REPORTER_PID" 2> /dev/null || true
  REPORTER_PID=""
}

# ---------------------------------------------------------------------------
# Finalise (also runs on Ctrl+C): merge -> score -> write config -> viewer
# ---------------------------------------------------------------------------
FINALISED=0
finalise() {
  [[ "$FINALISED" == 1 ]] && return 0
  FINALISED=1
  stop_reporter
  echo ""
  echo ">>> Finalising: merging measurements + scoring..."
  merge_measurements
  local write_flag=""
  [[ "$WRITE_CONFIG" == 1 ]] && write_flag="--write-config"
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    ${DIMS:+--dims "$DIMS"} $write_flag --viewer --open-viewer || true
  # Consumer copy must mirror the template config exactly (AGENTS.md).
  if [[ "$WRITE_CONFIG" == 1 && -f "$LOCAL_DATA/config/multiverse_config.json" ]]; then
    cp "$LOCAL_DATA/config/multiverse_config.json" \
       "$LOCAL_DATA/config/multiverse_config.json.bak.$(date +%Y%m%d-%H%M%S)"
    cp "$CONFIG" "$LOCAL_DATA/config/multiverse_config.json"
    echo "Synced data/config/multiverse_config.json (backup kept)"
  fi
  echo ""
  echo "Artefacts: $MEASUREMENTS"
  echo "           $SEEDTEST/viewer.html"
}

cleanup() {
  local code=$?
  trap - INT TERM EXIT
  echo ""
  echo "Stopping workers..."
  stop_reporter
  local pid
  for pid in $WORKER_PIDS; do kill "$pid" 2> /dev/null || true; done
  sleep 1
  docker ps -a --format '{{.Names}}' | grep '^seedrollall-' \
    | xargs -I{} docker rm -f {} 2> /dev/null || true
  finalise
  exit "$code"
}
trap cleanup INT TERM
trap 'finalise' EXIT

# ===========================================================================
# Main
# ===========================================================================
echo ""
echo "=============================================="
echo "  Multiverse seed roller (all dimensions)"
echo "=============================================="
echo "  Candidates/dim: $CANDIDATES   Workers: $WORKERS"
echo "  Render:         $RENDER (top $RENDER_TOP)"
echo "  Memory:         $ROLL_MEMORY per container"
echo "  Config:         $CONFIG"
echo "  Output:         $SEEDTEST"
echo "=============================================="
echo ""

prepare_base_dir
merge_measurements

python3 "$SCRIPT_DIR/score-dimensions.py" manifest \
  --config "$CONFIG" --seedtest "$SEEDTEST" \
  --workers "$WORKERS" --candidates "$CANDIDATES" ${DIMS:+--dims "$DIMS"}

start_reporter

MODE="measure"
[[ "$RENDER" == "all" ]] && MODE="measure+render"
run_fleet "$MODE" "" "$WORKERS" || echo "WARNING: one or more workers failed — scoring what was measured"
merge_measurements

# World seeds (overworld/nether/end/paradise_lost share ONE seed; every
# candidate costs a boot, so the pool is smaller). --dims skips this phase.
if [[ "$SKIP_WORLDS" == 0 && -z "$DIMS" ]]; then
  echo ""
  echo ">>> World-seed pass ($WORLD_CANDIDATES accepted candidates)..."
  python3 "$SCRIPT_DIR/score-dimensions.py" world-manifest \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    --workers "$WORKERS" --candidates "$WORLD_CANDIDATES" --spawn-attempts 4
  run_fleet "world" "v" "$WORKERS" || echo "WARNING: world pass incomplete"
  merge_measurements
fi

if [[ "$RENDER" == "winners" ]]; then
  echo ""
  echo ">>> Winners render pass (top $RENDER_TOP per dimension)..."
  python3 "$SCRIPT_DIR/score-dimensions.py" render-manifest \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    --workers "$WORKERS" --top "$RENDER_TOP" ${DIMS:+--dims "$DIMS"}
  run_fleet "render" "r" "$WORKERS" || echo "WARNING: render pass incomplete"
fi

finalise
