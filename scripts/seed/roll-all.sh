#!/usr/bin/env bash
# =============================================================================
# roll-all.sh - Fully automated parallel seed roller for EVERY dimension
# =============================================================================
#
# Boots N Docker containers in SEED_ROLL_MODE (the custom-dimensions mod
# skips boot-time dimension creation), splits the rollable dimensions from
# config/custom-dimensions/ across them, and measures M candidate seeds
# (scripts/seed/seed_worker.py does the per-candidate work over a native
# RCON socket). Scoring is per-dimension and philosophy-driven
# (scripts/seed/dimension_profiles.py).
#
# Winners are AUTO-WRITTEN into config/custom-dimensions/dimensions/ as the roll
# goes (every 45s, one .bak.TIMESTAMP backup per session; --no-write
# disables). The live viewer is served on http://127.0.0.1:8765/viewer.html
# (viewer-server.py) where '☆ make winner' pins a human pick over the score
# ranking (persisted in .seedtest/winner-overrides.json). Completion — or
# Ctrl+C — merges the per-worker CSVs and finalises the same way.
#
# Runs INDEFINITELY: workers cycle their dimension rotation (one accepted
# candidate per dimension per cycle, unbounded attempts per acceptance) and
# roll the shared world seed as parallel clones; a dedicated boot stream
# covers paradise_lost. Every acceptance/rejection is reported live; the
# viewer regenerates every 45s; Ctrl+C finalises with everything measured.
#
# Usage:
#   ./roll-all.sh                                  # everything, 3 workers
#   ./roll-all.sh --dims the_gauntlet --workers 1  # focused session
#   ./roll-all.sh --render off --no-write          # measure + score only
#   ./roll-all.sh --render all                     # render every accepted seed
#   ./roll-all.sh --clean                          # rebuild worker dirs from data/
#
# Options:
#   --workers N      parallel containers (default 3; ~10G memory each)
#   --dims a,b,c     roll a subset of dimensions (skips the world streams)
#   --render MODE    on (default: render each accepted seed inline) | off
#   --no-worlds      skip the world-seed boot stream
#   --no-write       don't write winners into dimension configs
#   --fresh          discard worker CSV spools (candidate bank persists)
#   --reset          wipe ALL seed data: candidates, scores, measurements, renders
#   --clean          rebuild seedtest worker dirs from data/
#
# Environment: ROLL_MEMORY (default 10G/container), RCON_TIMEOUT (120s),
#              ROLL_IMAGE (itzg image pin — must match seed_worker.py default)
#              ROLL_SPAWN_GATE_RADIUS (default 768) — max distance to a
#              configured spawn biome; closer candidates score higher
#
# Gotchas:
#   - Requires the fork custom-dimensions jar (customdim + SEED_ROLL_MODE)
#     in data/mods/ — ./dev up installs it.
#   - collective and bluemap are deliberately NOT stripped (dependencies /
#     renders); c2me DFC is forced off per worker (per-dimension seed trap).
#   - macOS bash 3.2 — no declare -A / mapfile.
# =============================================================================
set -euo pipefail

ROLL_MEMORY="${ROLL_MEMORY:-10G}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

LOCAL_DATA="$PROJECT_ROOT/data"

# v4 config resolution (score-dimensions.py / seed_worker.py accept any of
# these as --config):
#   1. $PROJECT_ROOT/config/custom-dimensions — a platform checkout (or a
#      consumer keeping a full local copy). Winners write straight into
#      those dimension files.
#   2. $LOCAL_DATA/config/custom-dimensions — CONSUMER MODE: the merged
#      view the server actually boots (bundle defaults + staged overlay,
#      seeded by ./dev up). Winners write into the consumer repo's
#      overlay/config/custom-dimensions/ as {"overrides"} files — the
#      bundle's platform files are replaced on every update, so the
#      overlay is the only durable consumer-owned home for them.
#   3. The deprecated monolithic multiverse_config.json.
CONFIG_DIR="$PROJECT_ROOT/config/custom-dimensions"
WINNER_FLAG=""
if [[ -d "$CONFIG_DIR/dimensions" ]]; then
  CONFIG="$CONFIG_DIR"
elif [[ -d "$LOCAL_DATA/config/custom-dimensions/dimensions" ]]; then
  CONFIG="$LOCAL_DATA/config/custom-dimensions"
  WINNER_FLAG="--winner-overlay $PROJECT_ROOT/overlay/config/custom-dimensions"
  echo "Consumer mode: rolling against $CONFIG"
  echo "  Winners will be written to overlay/config/custom-dimensions/dimensions/ as \"overrides\" files"
else
  echo "Error: no dimension config found — expected one of:" >&2
  echo "  $CONFIG_DIR/dimensions/  (platform checkout)" >&2
  echo "  $LOCAL_DATA/config/custom-dimensions/dimensions/  (consumer — run ./dev up first)" >&2
  exit 1
fi
# EVERYTHING seedtest-related (measurements, renders, viewer, worker boot
# dirs) lives under .seedtest/ — nothing else lands in the consumer repo.
SEEDTEST="$PROJECT_ROOT/.seedtest"
WORK_BASE="$SEEDTEST/base"
MEASUREMENTS="$SEEDTEST/measurements.csv"

WORKERS="${ROLL_WORKERS:-3}"
DIMS=""
RENDER="${ROLL_RENDER:-on}"
WRITE_CONFIG=1
SKIP_WORLDS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    # --candidates / --world-candidates are accepted for
    # backwards compatibility; the roller is indefinite now.
    --candidates | --world-candidates) shift 2 ;;
    --no-worlds)
      SKIP_WORLDS=1
      shift
      ;;
    --workers)
      WORKERS="$2"
      shift 2
      ;;
    --dims)
      DIMS="$2"
      shift 2
      ;;
    --render)
      RENDER="$2"
      shift 2
      ;;
    --no-write)
      WRITE_CONFIG=0
      shift
      ;;
    --fresh)
      rm -f "$MEASUREMENTS" "$SEEDTEST"/worker-*.csv "$SEEDTEST"/abandoned-worker-*.csv
      shift
      ;;
    --reset)
      echo "Resetting ALL seed data (candidates, scores, measurements, renders)..."
      rm -rf "$SEEDTEST"
      if [[ -d "$CONFIG" ]]; then
        rm -rf "$CONFIG/candidates"
      fi
      if [[ -d "$LOCAL_DATA/config/custom-dimensions/candidates" ]]; then
        rm -rf "$LOCAL_DATA/config/custom-dimensions/candidates"
      fi
      echo "  Done. Next roll starts from zero."
      shift
      ;;
    --clean)
      rm -rf "$SEEDTEST/base" "$SEEDTEST"/w[0-9]*
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$RENDER" != "on" && "$RENDER" != "off" ]]; then
  echo "Error: --render must be on or off" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v docker > /dev/null || {
  echo "Error: docker not found" >&2
  exit 1
}
command -v python3 > /dev/null || {
  echo "Error: python3 required" >&2
  exit 1
}
[[ -d "$CONFIG" ]] || {
  echo "Error: config directory not found: $CONFIG" >&2
  exit 1
}
ls "$LOCAL_DATA/mods/"*.jar > /dev/null 2>&1 \
  || {
    echo "Error: no mods in data/mods — run ./dev up first" >&2
    exit 1
  }
ls "$LOCAL_DATA"/mods/customdimensions*.jar > /dev/null 2>&1 \
  || {
    echo "Error: customdimensions jar missing from data/mods" >&2
    exit 1
  }

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
  # Roll boots must use the generated mvconfig (legacy single-file format);
  # a copied custom-dimensions/ directory would win and boot all 74 dims.
  rm -rf "$WORK_BASE/config/custom-dimensions"
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
# Measurement storage (v4 Phase 5): score-dimensions.py reads the worker
# CSV spools directly and persists everything into per-dimension candidate
# files (config/custom-dimensions/candidates/{slug}.json) on every
# finalise — the old merged measurements.csv is gone (a leftover one is
# still read as a one-time import source).
# ---------------------------------------------------------------------------
# Worker fleets — indefinite: each worker cycles its rotation until the
# stop file appears (Ctrl+C); a crashed worker process is logged and
# respawned so the roll survives anything short of the host dying.
# ---------------------------------------------------------------------------
WORKER_PIDS=""
STOP_FILE="$SEEDTEST/.stop"

launch_worker() {
  local wid="$1" mode="$2" manifest="$3"
  prepare_worker_dir "$wid"
  (
    while [[ ! -f "$STOP_FILE" ]]; do
      python3 "$SCRIPT_DIR/seed_worker.py" \
        --worker-id "$wid" \
        --workdir "$SEEDTEST/w$wid" \
        --manifest "$manifest" \
        --mvconfig "$SEEDTEST/mvconfig-roll.json" \
        --base-config "$CONFIG" \
        --seedtest "$SEEDTEST" \
        --mode "$mode" \
        --memory "$ROLL_MEMORY" || true
      if [[ ! -f "$STOP_FILE" ]]; then
        echo "[W$wid] worker process exited — respawning in 10s"
        sleep 10
      fi
    done
  ) &
  WORKER_PIDS="$WORKER_PIDS $!"
}

# ---------------------------------------------------------------------------
# Live report: regenerate viewer.html every 45s while fleets run, so the
# report can be watched in a browser (it meta-refreshes itself).
# ---------------------------------------------------------------------------
REPORTER_PID=""
VIEWER_PID=""
start_reporter() {
  # Winners auto-write AS THE ROLL GOES (--no-write disables): the config
  # always holds the current best per dimension, and human picks from the
  # viewer (POST /pick) pin over the ranking.
  local write_flag=""
  [[ "$WRITE_CONFIG" == 1 ]] && write_flag="--write-config"
  (
    while true; do
      sleep 45
      # shellcheck disable=SC2086
      python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
        --config "$CONFIG" --seedtest "$SEEDTEST" \
        ${DIMS:+--dims "$DIMS"} $write_flag $WINNER_FLAG --viewer > /dev/null 2>&1 || true
    done
  ) &
  REPORTER_PID=$!
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/viewer-server.py" \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    --port "${ROLL_VIEWER_PORT:-8765}" $write_flag $WINNER_FLAG &
  VIEWER_PID=$!
  echo "Live report: http://127.0.0.1:${ROLL_VIEWER_PORT:-8765}/viewer.html"
  echo "  (regenerates every 45s; '☆ make winner' pins your pick into the config)"
}

stop_reporter() {
  [[ -n "$REPORTER_PID" ]] && kill "$REPORTER_PID" 2> /dev/null || true
  [[ -n "$VIEWER_PID" ]] && kill "$VIEWER_PID" 2> /dev/null || true
  REPORTER_PID=""
  VIEWER_PID=""
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
  echo ">>> Finalising: merging worker spools into candidates + scoring..."
  local write_flag=""
  [[ "$WRITE_CONFIG" == 1 ]] && write_flag="--write-config"
  # shellcheck disable=SC2086
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    ${DIMS:+--dims "$DIMS"} $write_flag $WINNER_FLAG --viewer --open-viewer || true
  # Consumer mode: restage the freshly written overlay into the merged view
  # so the NEXT local boot (and re-rolls) pick the winners up immediately.
  if [[ "$WRITE_CONFIG" == 1 && -n "$WINNER_FLAG" \
    && -d "$PROJECT_ROOT/overlay/config/custom-dimensions" ]]; then
    rm -rf "$LOCAL_DATA/config/custom-dimensions/overlay"
    mkdir -p "$LOCAL_DATA/config/custom-dimensions/overlay"
    cp -R "$PROJECT_ROOT/overlay/config/custom-dimensions/." \
      "$LOCAL_DATA/config/custom-dimensions/overlay/"
    echo "Restaged overlay into data/config/custom-dimensions/overlay/"
  fi
  # Platform checkout: consumer copy must mirror the template config exactly
  # (AGENTS.md). Never runs in consumer mode — CONFIG *is* the data copy.
  if [[ "$WRITE_CONFIG" == 1 && -d "$CONFIG" && -z "$WINNER_FLAG" \
    && "$CONFIG" != "$LOCAL_DATA/config/custom-dimensions" \
    && -d "$LOCAL_DATA/config/custom-dimensions" ]]; then
    # Directory mode: replace dimensions/ + settings.json, leave overlay/ alone.
    STAMP="$(date +%Y%m%d-%H%M%S)"
    cp -R "$LOCAL_DATA/config/custom-dimensions/dimensions" \
      "$LOCAL_DATA/config/custom-dimensions/dimensions.bak.$STAMP"
    rm -rf "$LOCAL_DATA/config/custom-dimensions/dimensions"
    cp -R "$CONFIG/dimensions" "$LOCAL_DATA/config/custom-dimensions/dimensions"
    [[ -f "$CONFIG/settings.json" ]] \
      && cp "$CONFIG/settings.json" "$LOCAL_DATA/config/custom-dimensions/settings.json"
    echo "Synced data/config/custom-dimensions/ (backup: dimensions.bak.$STAMP)"
  fi
  echo ""
  if [[ -d "$CONFIG" ]]; then
    echo "Artefacts: $CONFIG/candidates/ (measurements + scores + winners)"
  else
    echo "Artefacts: $MEASUREMENTS"
  fi
  echo "           $SEEDTEST/viewer.html"
}

cleanup() {
  local code=$?
  trap - INT TERM EXIT
  echo ""
  echo "Stopping workers (finalising with everything measured so far)..."
  touch "$STOP_FILE"
  stop_reporter
  local pid
  for pid in $WORKER_PIDS; do kill "$pid" 2> /dev/null || true; done
  pkill -TERM -f "seed_worker.py" 2> /dev/null || true
  # Workers finish their current RCON call before noticing the stop file —
  # give them a bounded grace period, then KILL stragglers. Orphaned
  # workers previously kept REBOOTING containers after Ctrl+C (2026-07-17:
  # a runaway fleet needed a host reboot), so this must be watertight.
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    pgrep -f "seed_worker.py" > /dev/null 2>&1 || break
    sleep 2
  done
  pkill -KILL -f "seed_worker.py" 2> /dev/null || true
  # Container teardown AFTER the processes that respawn them are gone;
  # retry — a container mid-boot can survive the first rm.
  for _ in 1 2 3; do
    docker ps -a --format '{{.Names}}' | grep '^seedrollall-' \
      | xargs -I{} docker rm -f {} 2> /dev/null || true
    docker ps -a --format '{{.Names}}' | grep -q '^seedrollall-' || break
    sleep 2
  done
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
echo "  Mode:           indefinite (Ctrl+C to finish)   Workers: $WORKERS"
echo "  Render:         $RENDER"
echo "  Memory:         $ROLL_MEMORY per container"
echo "  Config:         $CONFIG"
echo "  Output:         $SEEDTEST"
echo "=============================================="
echo ""

prepare_base_dir

rm -f "$STOP_FILE"
# Fresh backup marker per session: the first auto-write this run takes one
# timestamped config backup, later 45s re-writes don't spam .bak files.
rm -f "$SEEDTEST/.config-backed-up"

NO_WORLDS_FLAG=""
[[ "$SKIP_WORLDS" == 1 ]] && NO_WORLDS_FLAG="--no-worlds"
python3 "$SCRIPT_DIR/score-dimensions.py" manifest \
  --config "$CONFIG" --seedtest "$SEEDTEST" \
  --workers "$WORKERS" ${DIMS:+--dims "$DIMS"} $NO_WORLDS_FLAG

start_reporter

MODE="measure+render"
[[ "$RENDER" == "off" ]] && MODE="measure"

for w in $(seq 0 $((WORKERS - 1))); do
  [[ -s "$SEEDTEST/work-$w.txt" ]] && launch_worker "$w" "$MODE" "$SEEDTEST/work-$w.txt"
done
# All four worlds (incl. paradise_lost — generic dimension cloning) roll as
# runtime clones inside the @worlds rotation slots; no boot stream needed.

echo ""
echo "Rolling indefinitely across $WORKERS workers (+world boot stream)."
echo "Watch: $SEEDTEST/viewer.html — Ctrl+C finalises with everything measured."
wait
finalise
