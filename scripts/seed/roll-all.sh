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
  # The in-house mods are NOT in the shared cache and never will be: that
  # cache holds what sync-mods.sh pulls from Modrinth, and local-mods ship in
  # the stack bundle instead. Without them the warmup server has no
  # `customdim` command at all, so `customdim load` / `dump-biome-params` /
  # `dump-structure-pools` fail for every dimension and warmup silently
  # degrades to the shipped biome table and no structure pools (2026-07-30:
  # 77/77 dimensions failed to queue, structure_pools.json never written).
  # The bundle copy is authoritative; data/mods is the platform-checkout
  # fallback, where dev-up.sh has already installed the same jars.
  local local_mods
  local_mods="$(cd "$SCRIPT_DIR/../.." 2> /dev/null && pwd)/local-mods"
  if compgen -G "$local_mods/*.jar" > /dev/null; then
    cp "$local_mods/"*.jar "$WORK_BASE/mods/"
  elif compgen -G "$LOCAL_DATA/mods/customdimensions*.jar" > /dev/null; then
    cp "$LOCAL_DATA/mods/"customdimensions*.jar "$WORK_BASE/mods/"
  else
    echo "  WARNING: no in-house mod jars found (looked in $local_mods and" >&2
    echo "  $LOCAL_DATA/mods) — customdim commands will be unavailable and" >&2
    echo "  warmup will fall back to the shipped biome table." >&2
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

  # Live table in .seedtest, seeded from the bundle's shipped copy. Writing
  # the warmed table back over the shipped one buried ~90s of Docker warmup
  # inside .stack/<version>/, which the next `./dev update` replaces
  # wholesale — and inside the git tree for anyone running from a checkout.
  local biome_params="$SEEDTEST/biome_params.json"
  if [[ ! -f "$biome_params" && -f "$SCRIPT_DIR/biome_params.json" ]]; then
    cp "$SCRIPT_DIR/biome_params.json" "$biome_params"
  fi
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
  # Jars come from the shared cache first, data/mods only as a fallback —
  # the same order prepare_base_dir uses. Checking data/mods alone made a
  # consumer with a full cache and no data/ (a world reset deletes it) fail
  # warmup with "run ./dev up first" while the jars sat right there.
  if ! compgen -G "$(mod_cache_dir)/*.jar" > /dev/null \
     && ! compgen -G "$LOCAL_DATA/mods/*.jar" > /dev/null; then
    echo "Error: no mod jars in $(mod_cache_dir) or $LOCAL_DATA/mods" >&2
    echo "  Run ./dev up once to populate the mod cache." >&2
    exit 1
  fi

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

  # Structure pools: which structures each dimension's groups can actually draw
  # from. Only the server knows — membership depends on each structure's own
  # biome list against the dimension's biome source. Without it, scoring can
  # only ask about a GROUP, which credited a Village want for any settlement and
  # left 167 shuns permanently unsatisfiable.
  #
  # A failure here is NOT fatal and a partial dump is fine: a dimension the dump
  # missed falls back to the group-level reading, which is exactly the behaviour
  # from before pools existed (census_scoring.weight_share).
  local pools="$SEEDTEST/structure_pools.json"
  if [[ ! -f "$pools" ]]; then
    if [[ ! -f "$SEEDTEST/mvconfig-roll.json" ]]; then
      python3 "$SCRIPT_DIR/score-dimensions.py" manifest \
        --config "$CONFIG" --seedtest "$SEEDTEST" --workers 1 --no-worlds 2> /dev/null || true
    fi
    echo "  Dumping structure pools (one-time, loads each dimension once)..."
    prepare_base_dir

    python3 "$SCRIPT_DIR/warmup_structure_pools.py" \
      --workdir "$SEEDTEST/base" \
      --mvconfig "$SEEDTEST/mvconfig-roll.json" \
      --seedtest "$SEEDTEST" \
      --config "$CONFIG" \
      --output "$pools" \
      --memory "$ROLL_MEMORY" || {
        echo "  WARNING: structure pool dump failed — wants and shuns will be" >&2
        echo "  scored per GROUP rather than per structure (the old behaviour)" >&2
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
  OVERLAY_FLAG=""
  # --no-write has to suppress BOTH flags. --winner-overlay is set during
  # config resolution, before argument parsing, and on its own it is enough
  # for finalise to write winners into the consumer's overlay — so gating
  # only --write-config left --no-write completely ineffective in a consumer
  # repo while appearing to work in a platform checkout, where the overlay
  # flag is empty anyway (2026-07-30: a --no-write roll rewrote 33 of
  # elfydd's overlay dimension files).
  if [[ "$WRITE_CONFIG" == 1 ]]; then
    WRITE_FLAG="--write-config"
    OVERLAY_FLAG="$WINNER_FLAG"
  fi
  # shellcheck disable=SC2086
  python3 "$SCRIPT_DIR/score-dimensions.py" finalise \
    --config "$CONFIG" --seedtest "$SEEDTEST" \
    ${DIMS:+--dims "$DIMS"} $WRITE_FLAG $OVERLAY_FLAG --viewer || true
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
