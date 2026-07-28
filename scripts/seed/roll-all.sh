#!/usr/bin/env bash
# =============================================================================
# roll-all.sh — Seed roller: pure-Python scoring
# =============================================================================
#
# Two phases:
#   1. WARMUP (one-time): extract structure sets from mod JARs, dump biome
#      params per dimension family from a short-lived MC server boot (~90s).
#   2. ROLL: fast_roller.py — pure Python structure screening + biome/terrain
#      scoring. Thousands of candidates/sec. No Docker, no RCON.
#
# Rendering is handled by the viewer: ./dev seed-viewer (background threads
# render all top candidates on startup; --refresh to wipe and regenerate).
#
# Usage:
#   ./roll-all.sh                            # full run
#   ./roll-all.sh --pool 10000 --count 200   # bigger screening pool
#   ./roll-all.sh --dims the_gauntlet        # single dimension
#   ./roll-all.sh --no-write                 # don't write winners to configs
#   ./roll-all.sh --reset                    # wipe all seed data
#
# Environment:
#   ROLL_MEMORY      memory per warmup container (default 10G)
#   ROLL_POOL        tier-1 pool per dimension (default 5000)
#   ROLL_COUNT       candidates to keep per dimension (default 100)
# =============================================================================
set -euo pipefail

ROLL_MEMORY="${ROLL_MEMORY:-10G}"
ROLL_POOL="${ROLL_POOL:-5000}"
ROLL_COUNT="${ROLL_COUNT:-100}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/../lib.sh"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
# data/ belongs to the mc container. The roller reads exactly one thing from
# it — the installed mod jars, to boot the warmup container — and writes
# nothing. All seed-rolling state lives in .seedtest.
LOCAL_DATA="$PROJECT_ROOT/data"

CONFIG_DIR="$PROJECT_ROOT/config/custom-dimensions"
BUNDLE_CONFIG="$(cd "$SCRIPT_DIR/../.." 2> /dev/null && pwd)/config"
WINNER_FLAG=""

# Config resolution. The roller reads the SAME sources the server does —
# platform defaults and the consumer's overlay — straight from where they
# live. It never reads (or writes) data/: that tree belongs to the mc
# container and is wiped to reset a world, which must not disturb a
# separate application.
if [[ -d "$CONFIG_DIR/dimensions" ]]; then
  CONFIG="$CONFIG_DIR"                      # platform repo
else
  CONFIG="$BUNDLE_CONFIG/custom-dimensions" # consumer: the stack bundle
  if [[ ! -d "$CONFIG/dimensions" ]]; then
    echo "Error: no dimension config found." >&2
    echo "  Looked in: $CONFIG_DIR/dimensions" >&2
    echo "             $CONFIG/dimensions" >&2
    echo "  Run ./dev update to fetch the stack bundle." >&2
    exit 1
  fi
  if [[ -d "$PROJECT_ROOT/overlay/config/custom-dimensions" ]]; then
    SEED_OVERLAY_DIR="$PROJECT_ROOT/overlay/config/custom-dimensions"
    export SEED_OVERLAY_DIR
  fi
  WINNER_FLAG="--winner-overlay $PROJECT_ROOT/overlay/config/custom-dimensions"
  echo "Consumer mode: platform dims from $CONFIG"
  echo "  Overlay: ${SEED_OVERLAY_DIR:-none}"
  echo "  Winners → overlay/config/custom-dimensions/dimensions/"
fi

SEEDTEST="$PROJECT_ROOT/.seedtest"
DIMS=""
WRITE_CONFIG=1
WARMUP_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pool)        ROLL_POOL="$2"; shift 2 ;;
    --count)       ROLL_COUNT="$2"; shift 2 ;;
    --dims)        DIMS="$2"; shift 2 ;;
    --no-write)    WRITE_CONFIG=0; shift ;;
    # Warmup is a prerequisite the viewer drives itself, not a step a user
    # should have to know about — this exposes it without rolling anything.
    --warmup-only) WARMUP_ONLY=1; shift ;;
    --reset)
      echo "Resetting ALL seed data..."
      # Everything the roller owns is in here, so this is the whole reset.
      rm -rf "$SEEDTEST"
      echo "  Done."
      shift ;;
    --clean)
      rm -rf "$SEEDTEST/base" "$SEEDTEST"/w[0-9]* "$SEEDTEST"/wr
      shift ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v python3 > /dev/null || { echo "Error: python3 required" >&2; exit 1; }
[[ -d "$CONFIG" ]] || { echo "Error: config not found: $CONFIG" >&2; exit 1; }
mkdir -p "$SEEDTEST"

# Mod strip patterns (warmup container — strip client-only / unnecessary mods)
STRIP_PATTERNS="DistantHorizons-* dcintegration-* voicechat-* LuckPerms-*
ledger-* styled-chat-* essential_commands-* NoChatReports-* packetfixer-*
sound-physics-remastered-* appleskin-* bettercombat-* player-animation-lib-*
carryon-* netherportalfix-* netherportalspread-* FallingTree-*
letmedespawn-* Almanac-* fabric-seasons-* open-parties-and-claims-*
chipped-* DramaticDoors-* handcrafted-* c2me-*"

prepare_base_dir() {
  local WORK_BASE="$SEEDTEST/base"
  [[ -f "$WORK_BASE/.ready" ]] && return 0
  echo "  Preparing base dir from local server data..."
  mkdir -p "$WORK_BASE/mods"
  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    [[ -e "$LOCAL_DATA/$item" ]] && cp -a "$LOCAL_DATA/$item" "$WORK_BASE/"
  done
  cp "$LOCAL_DATA"/fabric-server-mc.*.jar "$WORK_BASE/" 2> /dev/null || true
  # Mods come from the shared cache, not from data/mods: the roller is a
  # separate application from the server and must not depend on the
  # container's tree being populated. Fall back to data/mods only when the
  # cache has not been filled yet.
  local jar_cache
  jar_cache="$(mod_cache_dir)"
  if compgen -G "$jar_cache/*.jar" > /dev/null; then
    cp "$jar_cache/"*.jar "$WORK_BASE/mods/"
  elif compgen -G "$LOCAL_DATA/mods/*.jar" > /dev/null; then
    echo "  (mod cache empty — falling back to data/mods; run ./dev up or"
    echo "   ./dev sync-mods to fill $jar_cache)"
    cp "$LOCAL_DATA/mods/"*.jar "$WORK_BASE/mods/"
  else
    echo "Error: no mod jars in $jar_cache or $LOCAL_DATA/mods" >&2
    exit 1
  fi
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
  rm -rf "$WORK_BASE/config/DistantHorizons"
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
# Finalise: write winners + generate viewer
# ---------------------------------------------------------------------------
finalise() {
  WRITE_FLAG=""
  [[ "$WRITE_CONFIG" == 1 ]] && WRITE_FLAG="--write-config"
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    ${DIMS:+--dims "$DIMS"} $WRITE_FLAG $WINNER_FLAG --viewer || true
}

# ===========================================================================
# Main
# ===========================================================================
warmup

if [[ "$WARMUP_ONLY" == 1 ]]; then
  echo "Warmup complete."
  exit 0
fi

roll
finalise

echo ""
echo "Artefacts:"
echo "  Candidates: $SEEDTEST/candidates/"
echo "  Viewer:     $SEEDTEST/index.html"
echo ""
echo "To view results and render maps: ./dev seed-viewer"
echo "  (renders all top candidates in background; --refresh to regenerate)"
