#!/usr/bin/env bash
# =============================================================================
# roll-all.sh — Seed roller: pure-Python scoring + MC server renders
# =============================================================================
#
# Three phases:
#   1. WARMUP (one-time): extract structure sets from mod JARs, dump biome
#      params per dimension family from a short-lived MC server boot (~90s).
#   2. ROLL: fast_roller.py — pure Python structure screening + biome/terrain
#      scoring. Thousands of candidates/sec. No Docker, no RCON.
#   3. RENDER: top N per dimension get flat top-down map images via a
#      short-lived MC server (forceload + save-all) + unmined-cli (native,
#      ~1s/render). No Docker render containers needed.
#
# Usage:
#   ./roll-all.sh                            # full run
#   ./roll-all.sh --pool 10000 --count 200   # bigger screening pool
#   ./roll-all.sh --dims the_gauntlet        # single dimension
#   ./roll-all.sh --no-render                # skip renders
#   ./roll-all.sh --render-only              # skip rolling, render top candidates
#   ./roll-all.sh --no-write                 # don't write winners to configs
#   ./roll-all.sh --reset                    # wipe all seed data
#
# Environment:
#   ROLL_MEMORY      memory per render container (default 10G)
#   ROLL_POOL        tier-1 pool per dimension (default 5000)
#   ROLL_COUNT       candidates to keep per dimension (default 100)
#   ROLL_RENDER_TOP  candidates to render per dimension (default 10)
#   ROLL_RENDER_SIZE render area in blocks (default 512)
#   ROLL_RENDER_ZOOM unmined-cli zoom level (default 0; -1=wider, 1=closer)
# =============================================================================
set -euo pipefail

ROLL_MEMORY="${ROLL_MEMORY:-10G}"
ROLL_POOL="${ROLL_POOL:-5000}"
ROLL_COUNT="${ROLL_COUNT:-100}"
ROLL_RENDER_TOP="${ROLL_RENDER_TOP:-10}"
ROLL_RENDER_SIZE="${ROLL_RENDER_SIZE:-512}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
LOCAL_DATA="$PROJECT_ROOT/data"

# Config resolution
CONFIG_DIR="$PROJECT_ROOT/config/custom-dimensions"
WINNER_FLAG=""
if [[ -d "$CONFIG_DIR/dimensions" ]]; then
  CONFIG="$CONFIG_DIR"
elif [[ -d "$LOCAL_DATA/config/custom-dimensions/dimensions" ]]; then
  CONFIG="$LOCAL_DATA/config/custom-dimensions"
  WINNER_FLAG="--winner-overlay $PROJECT_ROOT/overlay/config/custom-dimensions"
  echo "Consumer mode: rolling against $CONFIG"
  echo "  Winners → overlay/config/custom-dimensions/dimensions/"
else
  echo "Error: no dimension config found — run ./dev up first" >&2
  exit 1
fi

SEEDTEST="$PROJECT_ROOT/.seedtest"
DIMS=""
WRITE_CONFIG=1
DO_RENDER=1
RENDER_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pool)        ROLL_POOL="$2"; shift 2 ;;
    --count)       ROLL_COUNT="$2"; shift 2 ;;
    --render-top)  ROLL_RENDER_TOP="$2"; shift 2 ;;
    --render-size) ROLL_RENDER_SIZE="$2"; shift 2 ;;
    --dims)        DIMS="$2"; shift 2 ;;
    --no-write)    WRITE_CONFIG=0; shift ;;
    --no-render)   DO_RENDER=0; shift ;;
    --render-only) RENDER_ONLY=1; shift ;;
    --reset)
      echo "Resetting ALL seed data..."
      rm -rf "$SEEDTEST"
      for d in "$CONFIG/candidates" "$LOCAL_DATA/config/custom-dimensions/candidates"; do
        [[ -d "$d" ]] && rm -rf "$d"
      done
      echo "  Done."
      shift ;;
    --clean)
      rm -rf "$SEEDTEST/base" "$SEEDTEST"/w[0-9]* "$SEEDTEST"/wr
      shift ;;
    # Backwards compat: silently accept old flags
    --fast) shift ;;
    --fast-count | --fast-pool | --candidates | --world-candidates | --workers)
      shift 2 ;;
    --no-worlds | --fresh) shift ;;
    --render) shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v python3 > /dev/null || { echo "Error: python3 required" >&2; exit 1; }
[[ -d "$CONFIG" ]] || { echo "Error: config not found: $CONFIG" >&2; exit 1; }
mkdir -p "$SEEDTEST"

# Mod strip patterns (render workers only)
STRIP_PATTERNS="DistantHorizons-* dcintegration-* voicechat-* LuckPerms-*
ledger-* styled-chat-* essential_commands-* NoChatReports-* packetfixer-*
sound-physics-remastered-* appleskin-* bettercombat-* player-animation-lib-*
carryon-* netherportalfix-* netherportalspread-* FallingTree-*
letmedespawn-* Almanac-* fabric-seasons-* open-parties-and-claims-*
chipped-* DramaticDoors-* handcrafted-* c2me-* bluemap-*"

prepare_base_dir() {
  local WORK_BASE="$SEEDTEST/base"
  [[ -f "$WORK_BASE/.ready" ]] && return 0
  echo "  Preparing base dir from local server data..."
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
  rm -rf "$WORK_BASE/config/bluemap" "$WORK_BASE/config/DistantHorizons"
  rm -rf "$WORK_BASE/config/custom-dimensions"
  echo "  $(ls "$WORK_BASE/mods/"*.jar | wc -l | tr -d ' ') mod JARs ($removed stripped)"
  touch "$WORK_BASE/.ready"
}

# ---------------------------------------------------------------------------
# Phase 1: Warmup — structure sets + biome params
# ---------------------------------------------------------------------------
warmup() {
  local need_warmup=0
  [[ ! -d "$SEEDTEST/.structure_sets" ]] && need_warmup=1

  local biome_params="$SCRIPT_DIR/biome_params.json"
  local nether_count=0
  if [[ -f "$biome_params" ]]; then
    nether_count=$(python3 -c "
import json
params = json.load(open('$biome_params'))
tagged = sum(1 for e in params if 'family' in e)
nether = sum(1 for e in params if e.get('family') == 'nether')
print(nether if tagged > 0 else 0)
" 2>/dev/null || echo 0)
    [[ "$nether_count" -lt 5 ]] && need_warmup=1
  else
    need_warmup=1
  fi

  [[ "$need_warmup" == 0 ]] && return 0

  echo ""
  echo "=== Warmup: extracting structure sets + biome params ==="

  command -v docker > /dev/null || {
    echo "Error: docker needed for first-time warmup (structure set extraction)" >&2
    exit 1
  }
  ls "$LOCAL_DATA/mods/"*.jar > /dev/null 2>&1 || {
    echo "Error: no mods in data/mods — run ./dev up first" >&2
    exit 1
  }

  prepare_base_dir

  if [[ ! -d "$SEEDTEST/.structure_sets" ]]; then
    echo "  Extracting structure sets from mod JARs..."
    python3 -c "
import sys; sys.path.insert(0, '$SCRIPT_DIR')
from seed_worker import _load_structure_sets_once
_load_structure_sets_once('$SEEDTEST')
print('  Done.')
"
  fi

  if [[ "$nether_count" -lt 5 ]]; then
    # Ensure the roll boot config exists (normally created by manifest step,
    # but warmup runs before that).
    if [[ ! -f "$SEEDTEST/mvconfig-roll.json" ]]; then
      python3 "$SCRIPT_DIR/score-dimensions.py" manifest \
        --config "$CONFIG" --seedtest "$SEEDTEST" --workers 1 --no-worlds 2> /dev/null || true
    fi
    echo "  Dumping biome params for ALL families (one-time, ~90s server boot)..."
    prepare_base_dir

    python3 "$SCRIPT_DIR/warmup_biomes.py" \
      --workdir "$SEEDTEST/base" \
      --mvconfig "$SEEDTEST/mvconfig-roll.json" \
      --seedtest "$SEEDTEST" \
      --output "$biome_params" \
      --memory "$ROLL_MEMORY" || {
        echo "  ERROR: biome param dump failed — seed scoring will be incomplete" >&2
      }

    docker ps -a --format '{{.Names}}' | grep '^seedrollall-warmup' \
      | xargs -I{} docker rm -f {} 2> /dev/null || true
  fi

  echo "=== Warmup complete ==="
  echo ""
}

# ---------------------------------------------------------------------------
# Phase 2: Roll — pure Python
# ---------------------------------------------------------------------------
roll() {
  echo ""
  echo "=============================================="
  echo "  Seed roller"
  echo "=============================================="
  echo "  Pool:    $ROLL_POOL seeds/dimension (structure screening)"
  echo "  Keep:    $ROLL_COUNT candidates/dimension"
  echo "  Config:  $CONFIG"
  echo "=============================================="
  echo ""

  rm -f "$SEEDTEST/fast-roller.csv"
  python3 "$SCRIPT_DIR/fast_roller.py" \
    --config "$CONFIG" \
    --seedtest "$SEEDTEST" \
    --count "$ROLL_COUNT" \
    --tier1-pool "$ROLL_POOL" \
    ${DIMS:+--dims "$DIMS"}
}

# ---------------------------------------------------------------------------
# Phase 3: Render — MC server + unmined-cli (flat top-down map)
# ---------------------------------------------------------------------------
render() {
  command -v docker > /dev/null || {
    echo "Skipping renders: docker not available"
    return 0
  }
  ls "$LOCAL_DATA"/mods/customdimensions*.jar > /dev/null 2>&1 || {
    echo "Skipping renders: customdimensions jar missing"
    return 0
  }
  # unmined-cli renders on the host (no Docker) — check it exists
  if [[ -z "${UNMINED_CLI:-}" ]]; then
    UNMINED_CLI=$(find "$HOME/.unmined" -name "unmined-cli" -type f 2>/dev/null | sort -r | head -1)
  fi
  if [[ ! -x "${UNMINED_CLI:-}" ]]; then
    echo "Skipping renders: unmined-cli not found (install to ~/.unmined/ or set UNMINED_CLI)"
    return 0
  fi
  export UNMINED_CLI

  echo ""
  echo "=== Rendering top $ROLL_RENDER_TOP candidates per dimension ==="
  echo "  unmined-cli: $UNMINED_CLI"
  echo "  render size: ${ROLL_RENDER_SIZE} blocks, zoom ${ROLL_RENDER_ZOOM:-0}"

  # Clear old renders so the manifest doesn't skip broken BlueMap tiles
  if [[ -d "$SEEDTEST/renders" ]]; then
    local old_count
    old_count=$(find "$SEEDTEST/renders" -name "*.png" -type f 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$old_count" -gt 0 ]]; then
      echo "  Clearing $old_count existing renders (re-rendering with unmined-cli)"
      rm -rf "$SEEDTEST/renders"
    fi
  fi

  # Generate render manifest
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" render-manifest \
    --config "$CONFIG" --seedtest "$SEEDTEST" --workers 1 \
    --top "$ROLL_RENDER_TOP" ${DIMS:+--dims "$DIMS"} || true

  if [[ ! -s "$SEEDTEST/work-r0.txt" ]]; then
    echo "  All top candidates already have renders."
    return 0
  fi

  RENDER_COUNT=$(wc -l < "$SEEDTEST/work-r0.txt" | tr -d ' ')
  echo "  $RENDER_COUNT candidates to render"

  prepare_base_dir

  # Prepare render worker dir
  local RENDER_DIR="$SEEDTEST/wr"
  if [[ ! -f "$RENDER_DIR/.ready" ]]; then
    local WORK_BASE="$SEEDTEST/base"
    mkdir -p "$RENDER_DIR/mods"
    local jar
    for jar in "$WORK_BASE/mods/"*.jar; do
      ln "$jar" "$RENDER_DIR/mods/" 2> /dev/null || cp "$jar" "$RENDER_DIR/mods/"
    done
    local item
    for item in .fabric libraries versions .install-fabric.env eula.txt; do
      [[ -e "$WORK_BASE/$item" ]] && cp -a "$WORK_BASE/$item" "$RENDER_DIR/"
    done
    cp "$WORK_BASE"/fabric-server-mc.*.jar "$RENDER_DIR/" 2> /dev/null || true
    local dir
    for dir in config defaultconfigs moonlight-global-datapacks villagerpacks world-datapacks-template; do
      [[ -d "$WORK_BASE/$dir" ]] && cp -a "$WORK_BASE/$dir" "$RENDER_DIR/"
    done
    touch "$RENDER_DIR/.ready"
  fi

  rm -f "$SEEDTEST/.stop"
  python3 "$SCRIPT_DIR/seed_worker.py" \
    --worker-id "r" \
    --workdir "$RENDER_DIR" \
    --manifest "$SEEDTEST/work-r0.txt" \
    --mvconfig "$SEEDTEST/mvconfig-roll.json" \
    --base-config "$CONFIG" \
    --seedtest "$SEEDTEST" \
    --mode shortlist \
    --memory "$ROLL_MEMORY" || true

  # Cleanup containers
  docker ps -a --format '{{.Names}}' | grep '^seedrollall-' \
    | xargs -I{} docker rm -f {} 2> /dev/null || true

  echo "=== Renders complete ==="
}

# ---------------------------------------------------------------------------
# Finalise: write winners + generate viewer
# ---------------------------------------------------------------------------
finalise() {
  WRITE_FLAG=""
  [[ "$WRITE_CONFIG" == 1 ]] && WRITE_FLAG="--write-config"
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    ${DIMS:+--dims "$DIMS"} $WRITE_FLAG $WINNER_FLAG --viewer || true

  # Consumer mode: restage overlay into merged view
  if [[ "$WRITE_CONFIG" == 1 && -n "$WINNER_FLAG" \
    && -d "$PROJECT_ROOT/overlay/config/custom-dimensions" ]]; then
    rm -rf "$LOCAL_DATA/config/custom-dimensions/overlay"
    mkdir -p "$LOCAL_DATA/config/custom-dimensions/overlay"
    cp -R "$PROJECT_ROOT/overlay/config/custom-dimensions/." \
      "$LOCAL_DATA/config/custom-dimensions/overlay/"
    echo "Restaged overlay into data/config/custom-dimensions/overlay/"
  fi
}

# ===========================================================================
# Main
# ===========================================================================
if [[ "$RENDER_ONLY" == 1 ]]; then
  render
  finalise
else
  warmup
  roll
  [[ "$DO_RENDER" == 1 ]] && render
  finalise
fi

echo ""
echo "Artefacts:"
echo "  Candidates: $CONFIG/candidates/"
echo "  Viewer:     $SEEDTEST/viewer.html"
echo "  Renders:    $SEEDTEST/renders/"
