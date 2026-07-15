#!/usr/bin/env bash
# =============================================================================
# roll-seeds.sh - Batch-MEASURE Minecraft seeds against the real modded server
# =============================================================================
#
# The measurement half of the measure/score split: boots seeds, gathers raw
# facts (spawn biome, profile-driven /locate battery, terrain height/water
# grid, error count) and appends them to a LONG-format CSV:
#
#     target,seed,metric,value        (seed-measurements.csv)
#
# NO scoring happens here — judgement is applied at report time by
# report-top.sh --profile <name> (scripts/seed/score-report.py), so
# re-weighting or new profiles never require re-rolling. Profiles live in
# scripts/seed/profiles/*.profile (format documented in classic.profile);
# the profile also drives WHAT is measured (locate battery, grid, early
# spawn rejection).
#
# Two modes:
#   World roll (default): one boot per world seed. The custom-dimensions
#     config is emptied for the roll (74 runtime dimensions add boot time
#     without being measured). Biome-first rejection preserved.
#   Dimension roll: --dimension <name> clones ONE dimension definition from
#     config/multiverse_config.json N times with fresh candidate seeds into
#     a temporary roll config, boots ONCE, and measures every clone —
#     orders of magnitude more throughput per boot. The winning seed is
#     hand-written into the real multiverse_config.json by a human.
#
# Resumable - skips (target,seed) pairs already measured. flock-guarded
# writes for parallel workers (world mode only). Snapshot logging only.
#
# Usage:
#   ./roll-seeds.sh                                   # world roll, classic profile
#   ./roll-seeds.sh --profile overworld-natural       # world roll, v3 profile
#   ./roll-seeds.sh --dimension the_gauntlet --profile dim-hard-overworld \
#                   --candidates 16 --rounds 4
#   BATCH_SIZE=50 PARALLEL_WORKERS=2 ./roll-seeds.sh --profile overworld-natural
#   ./roll-seeds.sh --clean                           # force re-copy of mods
#
# Environment variables:
#   BATCH_SIZE    - world seeds per batch (default: 128)
#   BATCH_COUNT   - max batches (default: 1)
#   ROLL_MEMORY   - container memory (default: 6G)
#   RCON_TIMEOUT  - seconds to wait for RCON readiness (default: 300)
#
# Gotchas:
#   - macOS bash 3.2: no declare -A / mapfile. Parallel mode needs flock
#     (brew install util-linux).
#   - c2me's density-function compiler is forced OFF in the roll dir —
#     with it on, every custom dimension silently clones the main world
#     (mods/AGENTS.md).
#   - The old wide seed-results.csv is NOT written any more; score-seed.sh
#     remains as a legacy standalone calculator only.
# =============================================================================
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
BATCH_SIZE="${BATCH_SIZE:-128}"
BATCH_COUNT="${BATCH_COUNT:-1}"
ROLL_MEMORY="${ROLL_MEMORY:-6G}"
RCON_TIMEOUT="${RCON_TIMEOUT:-300}"
PARALLEL_WORKERS="${PARALLEL_WORKERS:-1}"

if [[ "$ROLL_MEMORY" =~ ^([0-9]+)G$ ]]; then
  _mem_gb=${BASH_REMATCH[1]}
  JAVA_MEMORY="$((_mem_gb > 2 ? _mem_gb - 1 : _mem_gb))G"
else
  JAVA_MEMORY="$ROLL_MEMORY"
fi

RCON_PW="seedroll"
IMAGE="itzg/minecraft-server:2026.7.0-java21"
CONTAINER_NAME="seedroll"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

RESULTS_CSV="$PROJECT_ROOT/seed-measurements.csv"
ERROR_LOG="$PROJECT_ROOT/seed-errors.log"
WORK_DIR="$PROJECT_ROOT/seedtest-data"
LOCAL_DATA="$PROJECT_ROOT/data"
PROFILE_DIR="$SCRIPT_DIR/profiles"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
PROFILE_NAME="classic"
ROLL_DIMENSION=""
DIM_CANDIDATES=16
DIM_ROUNDS=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean)
      echo "Cleaning seedtest-data (will re-copy from local server)..."
      rm -rf "$WORK_DIR" "$WORK_DIR"-w*
      shift
      ;;
    --fresh)
      echo "Clearing old measurements (starting fresh CSV)..."
      rm -f "$RESULTS_CSV"
      shift
      ;;
    --profile)
      PROFILE_NAME="$2"
      shift 2
      ;;
    --dimension)
      ROLL_DIMENSION="$2"
      shift 2
      ;;
    --candidates)
      DIM_CANDIDATES="$2"
      shift 2
      ;;
    --rounds)
      DIM_ROUNDS="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

PROFILE_FILE="$PROFILE_DIR/${PROFILE_NAME}.profile"
[[ -f "$PROFILE_NAME" ]] && PROFILE_FILE="$PROFILE_NAME"

# ---------------------------------------------------------------------------
# Logging - all progress to stderr (visible), values to stdout (captured).
# ---------------------------------------------------------------------------
log() { printf "    %s\n" "$*" >&2; }
logn() { printf "    %s" "$*" >&2; }
warn() { printf "    ⚠ %s\n" "$*" >&2; }

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
if ! command -v docker &> /dev/null; then
  echo "Error: Docker is not installed or not in PATH." >&2
  exit 1
fi
if ! command -v python3 &> /dev/null; then
  echo "Error: python3 is required (roll config cloning + report scoring)." >&2
  exit 1
fi
if [[ ! -f "$PROFILE_FILE" ]]; then
  echo "Error: profile not found: $PROFILE_FILE" >&2
  echo "  Available: $(ls "$PROFILE_DIR" 2> /dev/null | tr '\n' ' ')" >&2
  exit 1
fi
if ! ls "$LOCAL_DATA/mods/"*.jar &> /dev/null 2>&1; then
  echo "Error: No mods found in data/mods/." >&2
  echo "  Run ./dev up first to download the modpack, then re-run." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Profile parsing (bash 3.2 - plain indexed arrays only).
# ---------------------------------------------------------------------------
LOCATE_NAMES=()
LOCATE_WHERES=()
LOCATE_IDS=()
GREEN_BIOMES=()
OK_BIOMES=()
OPT_REJECT_BAD="false"
OPT_GRID_N=0
OPT_GRID_PITCH=0

# shellcheck disable=SC2034  # _rest absorbs trailing fields some lines carry
while IFS='|' read -r kind a b c _rest; do
  case "$kind" in
    locate)
      LOCATE_NAMES+=("$a")
      LOCATE_WHERES+=("$b")
      LOCATE_IDS+=("$c")
      ;;
    biome)
      if [[ "$a" == "green" ]]; then GREEN_BIOMES+=("$b"); else OK_BIOMES+=("$b"); fi
      ;;
    option)
      case "$a" in
        reject_bad_spawn) OPT_REJECT_BAD="$b" ;;
        grid)
          if [[ "$b" != "off" ]]; then
            OPT_GRID_N="$b"
            OPT_GRID_PITCH="$c"
          fi
          ;;
      esac
      ;;
  esac
done < <(grep -v '^\s*#' "$PROFILE_FILE" | grep -v '^\s*$')

# ---------------------------------------------------------------------------
# Prepare seedtest-data: copy Fabric + mods + configs from the local server.
# ---------------------------------------------------------------------------
prepare_seedtest_dir() {
  if [[ -f "$WORK_DIR/.seedroll-ready" ]]; then
    local mod_count
    mod_count=$(ls "$WORK_DIR/mods/"*.jar 2> /dev/null | wc -l | tr -d ' ')
    echo "Reusing seedtest-data ($mod_count mod JARs, Fabric pre-installed)"
    return 0
  fi

  echo "Preparing seedtest-data from local server (one-time copy)..."
  mkdir -p "$WORK_DIR"

  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    if [[ -e "$LOCAL_DATA/$item" ]]; then
      cp -a "$LOCAL_DATA/$item" "$WORK_DIR/"
    fi
  done
  cp "$LOCAL_DATA"/fabric-server-mc.*.jar "$WORK_DIR/" 2> /dev/null || true

  mkdir -p "$WORK_DIR/mods"
  cp "$LOCAL_DATA/mods/"*.jar "$WORK_DIR/mods/"
  local mod_count
  mod_count=$(ls "$WORK_DIR/mods/"*.jar | wc -l | tr -d ' ')
  echo "  Copied $mod_count mod JARs"

  for dir in config defaultconfigs moonlight-global-datapacks villagerpacks; do
    if [[ -d "$LOCAL_DATA/$dir" ]]; then
      cp -a "$LOCAL_DATA/$dir" "$WORK_DIR/"
    fi
  done

  # World datapacks (structure tuning) ride along so rolls measure the
  # same worldgen production runs.
  if [[ -d "$LOCAL_DATA/world/datapacks" ]]; then
    mkdir -p "$WORK_DIR/world-datapacks-template"
    cp -a "$LOCAL_DATA/world/datapacks/." "$WORK_DIR/world-datapacks-template/"
  fi

  touch "$WORK_DIR/.seedroll-ready"
  echo "  Ready - Fabric + mods + configs cached in seedtest-data/"
}

# ---------------------------------------------------------------------------
# strip_seedroll_mods - remove JARs that don't affect worldgen/structures.
# The custom-dimensions jar is deliberately NOT stripped: dimension rolls
# depend on it, and it must shape world rolls exactly as in production.
# ---------------------------------------------------------------------------
SEEDROLL_EXCLUDE_PATTERNS=(
  "DistantHorizons-*"
  "bluemap-*"
  "dcintegration-*"
  "voicechat-*"
  "LuckPerms-*"
  "ledger-*"
  "styled-chat-*"
  "essential_commands-*"
  "NoChatReports-*"
  "packetfixer-*"
  "sound-physics-remastered-*"
  "appleskin-*"
  "bettercombat-*"
  "player-animation-lib-*"
  "carryon-*"
  "netherportalfix-*"
  "netherportalspread-*"
  "collective-*"
  "FallingTree-*"
  "letmedespawn-*"
  "Almanac-*"
  "fabric-seasons-*"
  "open-parties-and-claims-*"
  "chipped-*"
  "DramaticDoors-*"
  "handcrafted-*"
)

strip_seedroll_mods() {
  local dir="$1"
  local removed=0
  for pattern in "${SEEDROLL_EXCLUDE_PATTERNS[@]}"; do
    for f in "$dir/mods/"$pattern; do
      if [[ -f "$f" ]]; then
        rm "$f"
        removed=$((removed + 1))
      fi
    done
  done
  rm -rf "$dir/mods/luckperms" 2> /dev/null || true
  if ((removed > 0)); then
    echo "  Stripped $removed non-worldgen mod JARs for faster boot"
  fi
}

# ---------------------------------------------------------------------------
# enforce_roll_configs - per roll dir: c2me DFC off (per-dimension seed
# trap), world datapacks in place, and the roll's multiverse config.
#   $1 = roll dir, $2 = multiverse mode: "empty" (world rolls) or a path to
#   a prepared roll config (dimension rolls).
# ---------------------------------------------------------------------------
enforce_roll_configs() {
  local dir="$1" mv_mode="$2"
  mkdir -p "$dir/config"
  printf '[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n' \
    > "$dir/config/c2me.toml"

  if [[ "$mv_mode" == "empty" ]]; then
    python3 - "$PROJECT_ROOT/config/multiverse_config.json" "$dir/config/multiverse_config.json" << 'PYEOF'
import json, sys
cfg = json.load(open(sys.argv[1]))
cfg["dimensions"] = []
cfg["portals"] = []
json.dump(cfg, open(sys.argv[2], "w"), indent=2)
PYEOF
  else
    cp "$mv_mode" "$dir/config/multiverse_config.json"
  fi

  if [[ -d "$WORK_DIR/world-datapacks-template" ]]; then
    mkdir -p "$dir/world/datapacks"
    cp -a "$WORK_DIR/world-datapacks-template/." "$dir/world/datapacks/"
  fi
}

# ---------------------------------------------------------------------------
# Locking helpers for thread-safe file writes in parallel mode.
# ---------------------------------------------------------------------------
write_csv_rows() {
  # stdin: pre-formatted CSV rows. One lock per target keeps rows grouped.
  (
    flock -x 200
    cat >> "$RESULTS_CSV"
  ) 200> "${RESULTS_CSV}.lock"
}

write_error_log() {
  (
    flock -x 200
    cat >> "$ERROR_LOG"
  ) 200> "${ERROR_LOG}.lock"
}

# ---------------------------------------------------------------------------
# prepare_worker_dir - per-worker directory with hard-linked mods.
# ---------------------------------------------------------------------------
prepare_worker_dir() {
  local worker_id=$1
  local worker_dir="${WORK_DIR}-w${worker_id}"

  if [[ -d "$worker_dir" && -f "$worker_dir/.worker-ready" ]]; then
    return 0
  fi

  mkdir -p "$worker_dir/mods"
  for jar in "$WORK_DIR/mods/"*.jar; do
    [[ -f "$jar" ]] && ln "$jar" "$worker_dir/mods/" 2> /dev/null || cp "$jar" "$worker_dir/mods/"
  done
  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    [[ -e "$WORK_DIR/$item" ]] && cp -a "$WORK_DIR/$item" "$worker_dir/"
  done
  cp "$WORK_DIR"/fabric-server-mc.*.jar "$worker_dir/" 2> /dev/null || true
  for dir in config defaultconfigs moonlight-global-datapacks villagerpacks world-datapacks-template; do
    [[ -d "$WORK_DIR/$dir" ]] && cp -a "$WORK_DIR/$dir" "$worker_dir/"
  done
  touch "$worker_dir/.worker-ready"
}

prepare_seedtest_dir
strip_seedroll_mods "$WORK_DIR"

# ---------------------------------------------------------------------------
# CSV setup (long format)
# ---------------------------------------------------------------------------
if [[ ! -f "$RESULTS_CSV" ]]; then
  echo "target,seed,metric,value" > "$RESULTS_CSV"
  echo "Created new measurements file: $RESULTS_CSV"
else
  if [[ "$(head -1 "$RESULTS_CSV")" != "target,seed,metric,value" ]]; then
    echo "Error: $RESULTS_CSV is not a long-format measurements CSV." >&2
    echo "  Move it aside (or run with --fresh) and re-roll." >&2
    exit 1
  fi
  echo "Resuming from existing measurements: $RESULTS_CSV"
fi

echo "" >> "$ERROR_LOG"
echo "=== Seed roll session: $(date '+%Y-%m-%d %H:%M:%S') (profile: $PROFILE_NAME) ===" >> "$ERROR_LOG"
echo "Error log: $ERROR_LOG"

# ---------------------------------------------------------------------------
# Cleanup trap
# ---------------------------------------------------------------------------
cleanup() {
  local exit_code=$?
  echo ""
  echo "Cleaning up containers..."
  docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
  for wid in $(seq 0 $((PARALLEL_WORKERS - 1))); do
    docker rm -f "seedroll-${wid}" 2> /dev/null || true
  done
  rm -rf "$WORK_DIR/world/region" "$WORK_DIR/world/entities" "$WORK_DIR/world/poi"
  rm -f "$WORK_DIR/server.properties"
  rm -f "${RESULTS_CSV}.lock" "${ERROR_LOG}.lock"
  if ((exit_code != 0)); then
    echo "Script interrupted. Measurements saved so far in $RESULTS_CSV"
    echo "Re-run to resume from where you left off."
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
for wid in $(seq 0 9); do
  docker rm -f "seedroll-${wid}" 2> /dev/null || true
done

# ---------------------------------------------------------------------------
# is_measured - has this (target,seed) already been measured?
# ---------------------------------------------------------------------------
is_measured() {
  grep -q "^${1},${2},spawn_biome," "$RESULTS_CSV" 2> /dev/null
}

# generate_seed        - unsigned 64-bit (world seeds, matches old behaviour)
# generate_signed_seed - signed 64-bit (dimension config seeds are signed)
generate_seed() {
  od -An -tu8 -N8 /dev/urandom | tr -d ' '
}
generate_signed_seed() {
  od -An -td8 -N8 /dev/urandom | tr -d ' '
}

container_alive() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2> /dev/null)" == "true" ]]
}

dump_crash_log() {
  local label="$1" reason="$2"
  {
    echo ""
    echo "--- $label: $reason ($(date '+%H:%M:%S')) ---"
    docker logs --tail 50 "$CONTAINER_NAME" 2>&1 || echo "(no logs available)"
    echo "--- END ---"
  } >> "$ERROR_LOG"
}

rcon() {
  if ! container_alive; then
    return 1
  fi
  docker exec "$CONTAINER_NAME" rcon-cli --password "$RCON_PW" "$@" 2> /dev/null
}

wait_for_rcon() {
  local elapsed=0
  local interval=5
  local last_log=""

  while ((elapsed < RCON_TIMEOUT)); do
    if ! container_alive; then
      log "Container crashed during boot (${elapsed}s)"
      return 1
    fi
    if docker exec "$CONTAINER_NAME" rcon-cli --password "$RCON_PW" "list" &> /dev/null; then
      printf "\r\033[K" >&2
      log "Server ready (${elapsed}s)"
      return 0
    fi
    local log_line
    log_line=$(docker logs --tail 1 "$CONTAINER_NAME" 2> /dev/null | tr -d '\n' || true)
    if [[ -n "$log_line" && "$log_line" != "$last_log" ]]; then
      last_log="$log_line"
      printf "\r\033[K    [%3ds] %.100s" "$elapsed" "$log_line" >&2
    fi
    sleep "$interval"
    elapsed=$((elapsed + interval))
  done

  printf "\r\033[K" >&2
  log "Timed out waiting for RCON after ${RCON_TIMEOUT}s"
  return 1
}

start_container() {
  local seed="$1"
  docker run -d \
    --name "$CONTAINER_NAME" \
    --memory "${ROLL_MEMORY}" \
    --log-opt max-size=2m --log-opt max-file=1 \
    -e EULA=TRUE \
    -e TYPE=FABRIC \
    -e VERSION=1.21.1 \
    -e SEED="$seed" \
    -e MEMORY="${JAVA_MEMORY}" \
    -e ENABLE_RCON=TRUE \
    -e RCON_PASSWORD="$RCON_PW" \
    -e ONLINE_MODE=FALSE \
    -e ENABLE_AUTOPAUSE=FALSE \
    -e OVERRIDE_SERVER_PROPERTIES=true \
    -v "$WORK_DIR:/data" \
    "$IMAGE" > /dev/null 2>&1
}

# ---------------------------------------------------------------------------
# parse_locate - "<dist> <x> <z>" or "" from /locate output.
# ---------------------------------------------------------------------------
parse_locate() {
  local output="$1"

  if [[ -z "$output" ]] || echo "$output" | grep -qi -- "could not"; then
    echo ""
    return
  fi

  local dist
  dist=$(echo "$output" | grep -oE '\(([0-9]+) blocks away\)' | grep -oE '[0-9]+' | head -1)

  local x z
  x=$(echo "$output" | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' | grep -oE -- '-?[0-9]+' | head -1)
  z=$(echo "$output" | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' | grep -oE -- '-?[0-9]+' | tail -1)

  if [[ -n "$dist" ]]; then
    echo "$dist $x $z"
  else
    echo ""
  fi
}

# ---------------------------------------------------------------------------
# detect_biome - profile-list biome probe at 0 ~ 0 in a dimension.
# ---------------------------------------------------------------------------
detect_biome() {
  local dim="$1" biome result

  for biome in "${GREEN_BIOMES[@]:-}" "${OK_BIOMES[@]:-}"; do
    [[ -z "$biome" ]] && continue
    if ! container_alive; then
      echo "unknown"
      return
    fi
    result=$(rcon "execute in $dim if biome 0 64 0 $biome" 2> /dev/null || echo "")
    if [[ -n "$result" ]] && ! echo "$result" | grep -qi "fail\|could not\|unknown"; then
      echo "$biome"
      return
    fi
  done

  echo "unknown"
}

# ---------------------------------------------------------------------------
# resolve_where - map a profile 'where' to a dimension id.
#   $1 = where (overworld|nether|end|self), $2 = self dimension id
# ---------------------------------------------------------------------------
resolve_where() {
  case "$1" in
    overworld) echo "minecraft:overworld" ;;
    nether) echo "minecraft:the_nether" ;;
    end) echo "minecraft:the_end" ;;
    self) echo "$2" ;;
    *) echo "$2" ;;
  esac
}

# ---------------------------------------------------------------------------
# surface_height - binary-search the top non-replaceable block at X,Z.
# ~9 RCON calls for ±1 accuracy across -60..444. Assumes the chunk is
# force-loaded by the caller. Echoes the height (or "" on failure).
# ---------------------------------------------------------------------------
surface_height() {
  local dim="$1" x="$2" z="$3"
  local lo=-60 hi=444 mid result

  # If even the bottom is replaceable (void dims), report nothing.
  result=$(rcon "execute in $dim if block $x $lo $z #minecraft:replaceable" || echo "")
  if [[ -n "$result" ]] && ! echo "$result" | grep -qi "fail\|could not"; then
    echo ""
    return
  fi

  while ((hi - lo > 1)); do
    mid=$(((lo + hi) / 2))
    result=$(rcon "execute in $dim if block $x $mid $z #minecraft:replaceable" || echo "")
    if [[ -n "$result" ]] && ! echo "$result" | grep -qi "fail\|could not"; then
      hi=$mid # replaceable = above ground
    else
      lo=$mid # solid = at/below ground
    fi
  done
  echo "$lo"
}

# ---------------------------------------------------------------------------
# measure_target - the core measurement battery for one (target,seed).
# Writes long-format rows. $1 target key, $2 seed label, $3 self dim id.
# Returns 0 on success. The container must be up with ticks frozen.
# ---------------------------------------------------------------------------
measure_target() {
  local target="$1" seed="$2" dim="$3"
  local rows=""

  # --- spawn biome (also the world-mode early-reject input) ---
  local biome
  biome=$(detect_biome "$dim")
  rows="${target},${seed},spawn_biome,${biome}"

  # --- locate battery (from the profile) ---
  local i name where id ldim raw parsed dist lx lz
  for i in $(seq 0 $((${#LOCATE_NAMES[@]} - 1))); do
    [[ ${#LOCATE_NAMES[@]} -eq 0 ]] && break
    name="${LOCATE_NAMES[$i]}"
    where="${LOCATE_WHERES[$i]}"
    id="${LOCATE_IDS[$i]}"
    ldim=$(resolve_where "$where" "$dim")

    logn "  locate $name ($id in $ldim)... "
    raw=$(rcon "execute in $ldim run locate structure $id" || echo "")
    echo "${raw:-(not found)}" | head -1 >&2

    parsed=$(parse_locate "$raw")
    if [[ -n "$parsed" ]]; then
      dist=$(echo "$parsed" | awk '{print $1}')
      lx=$(echo "$parsed" | awk '{print $2}')
      lz=$(echo "$parsed" | awk '{print $3}')
      rows="${rows}
${target},${seed},structure_${name}_dist,${dist}
${target},${seed},structure_${name}_x,${lx}
${target},${seed},structure_${name}_z,${lz}"
    else
      rows="${rows}
${target},${seed},structure_${name}_dist,-1"
    fi

    if ! container_alive; then
      dump_crash_log "$target/$seed" "Crashed during locate battery"
      return 1
    fi
  done

  # --- terrain grid (heights + water) ---
  if ((OPT_GRID_N > 0)); then
    log "  terrain grid ${OPT_GRID_N}x${OPT_GRID_N} @ ${OPT_GRID_PITCH} blocks"
    local half r c x z h wres wval
    half=$(((OPT_GRID_N - 1) / 2))
    for r in $(seq 0 $((OPT_GRID_N - 1))); do
      for c in $(seq 0 $((OPT_GRID_N - 1))); do
        x=$(((c - half) * OPT_GRID_PITCH))
        z=$(((r - half) * OPT_GRID_PITCH))
        rcon "execute in $dim run forceload add $x $z" > /dev/null 2>&1 || true
        h=$(surface_height "$dim" "$x" "$z")
        if [[ -n "$h" ]]; then
          rows="${rows}
${target},${seed},height_r${r}c${c},${h}"
        fi
        wres=$(rcon "execute in $dim if block $x 62 $z minecraft:water" || echo "")
        if [[ -n "$wres" ]] && ! echo "$wres" | grep -qi "fail\|could not"; then
          wval=1
        else
          wval=0
        fi
        rows="${rows}
${target},${seed},water_r${r}c${c},${wval}"
        rcon "execute in $dim run forceload remove $x $z" > /dev/null 2>&1 || true
        if ! container_alive; then
          dump_crash_log "$target/$seed" "Crashed during terrain grid"
          return 1
        fi
      done
    done
  fi

  # --- error count (filtered) ---
  local error_count
  error_count=$(docker logs "$CONTAINER_NAME" 2>&1 \
    | grep -i "ERROR" \
    | grep -v -e "No data fixer registered" \
      -e "Error loading class" \
      -e "Block-attached entity at invalid position" \
      -e "template pool reference" \
    | wc -l | tr -d ' ')
  rows="${rows}
${target},${seed},errors,${error_count}"

  printf '%s\n' "$rows" | write_csv_rows
  echo "$biome"
  return 0
}

# ---------------------------------------------------------------------------
# test_world_seed - boot one world seed and measure it.
# Returns: 0 measured, 1 crash/failure, 2 bad-spawn early reject.
# ---------------------------------------------------------------------------
test_world_seed() {
  local seed="$1"

  rm -rf "$WORK_DIR/world"
  rm -f "$WORK_DIR/server.properties"
  enforce_roll_configs "$WORK_DIR" "empty"
  log "Cleaned world data"

  start_container "$seed"
  log "Container started"

  if ! wait_for_rcon; then
    dump_crash_log "world/$seed" "Failed to boot / RCON never ready"
    warn "SKIP - server failed to start (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  rcon "tick freeze" > /dev/null 2>&1 || true
  rcon "gamerule doMobSpawning false" > /dev/null 2>&1 || true

  # Biome-first rejection: ~97% of seeds die here in ~40s, not ~5min.
  if [[ "$OPT_REJECT_BAD" == "true" ]]; then
    log "Checking spawn biome..."
    local spawn_biome
    spawn_biome=$(detect_biome "minecraft:overworld")
    log "  Biome: $spawn_biome"
    if [[ "$spawn_biome" == "unknown" ]]; then
      log "Bad spawn biome - skipping seed"
      docker rm -f "$CONTAINER_NAME" > /dev/null 2>&1 || true
      return 2
    fi
  fi

  # Nether locates work from seed maths but need the dimension present.
  log "Loading Nether chunk..."
  if ! rcon "execute in minecraft:the_nether run forceload add 0 0" > /dev/null; then
    dump_crash_log "world/$seed" "Crashed loading Nether"
    warn "SKIP - crashed loading Nether (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi
  sleep 3

  if ! container_alive; then
    dump_crash_log "world/$seed" "Died after Nether load"
    warn "SKIP - container died after Nether load (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  local biome
  if ! biome=$(measure_target "world" "$seed" "minecraft:overworld"); then
    warn "SKIP - measurement failed (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  docker rm -f "$CONTAINER_NAME" > /dev/null 2>&1 || true
  log "Measured world seed $seed (spawn: $biome)"
  return 0
}

# ---------------------------------------------------------------------------
# run_dimension_roll - clone one dimension N times per boot and measure all.
# ---------------------------------------------------------------------------
run_dimension_roll() {
  local dim_name="$ROLL_DIMENSION"
  local ns
  ns=$(python3 -c "import json; print(json.load(open('$PROJECT_ROOT/config/multiverse_config.json')).get('namespace','adventure'))")

  echo ""
  echo ">>> Dimension roll: $dim_name x$DIM_CANDIDATES candidates x$DIM_ROUNDS round(s)"
  echo "    Profile: $PROFILE_NAME"
  echo ""

  local round
  for round in $(seq 1 "$DIM_ROUNDS"); do
    echo "--- Round $round of $DIM_ROUNDS ---"

    # Fresh candidate seeds for this round (skip already-measured).
    local seeds=()
    while ((${#seeds[@]} < DIM_CANDIDATES)); do
      local s
      s=$(generate_signed_seed)
      if ! is_measured "$dim_name" "$s"; then
        seeds+=("$s")
      fi
    done

    # Build the roll config: ONLY the cloned candidates (fast boot), with
    # idle unloading effectively disabled for the measurement window.
    # Seeds pass as argv — a stdin pipe would be swallowed by the heredoc
    # that carries the script itself (SC2259).
    local roll_cfg="$WORK_DIR/.roll-multiverse.json"
    python3 - "$PROJECT_ROOT/config/multiverse_config.json" "$roll_cfg" "$dim_name" "${seeds[@]}" << 'PYEOF'
import json, sys
src, dst, name = sys.argv[1], sys.argv[2], sys.argv[3]
seeds = [int(s) for s in sys.argv[4:]]
cfg = json.load(open(src))
base = next((d for d in cfg["dimensions"] if d["name"] == name), None)
if base is None:
    sys.exit(f"dimension not found in multiverse_config.json: {name}")
ns = cfg.get("namespace", "adventure")
clones = []
for i, seed in enumerate(seeds, 1):
    c = dict(base)
    c["name"] = f"{name}__s{i:02d}"
    c["dimensionId"] = f"{ns}:{name}__s{i:02d}"
    c["seed"] = seed
    clones.append(c)
cfg["dimensions"] = clones
cfg["portals"] = []
cfg["idleUnloadMinutes"] = 9999
json.dump(cfg, open(dst, "w"), indent=2)
print(f"roll config: {len(clones)} clones of {name}", file=sys.stderr)
PYEOF

    rm -rf "$WORK_DIR/world"
    rm -f "$WORK_DIR/server.properties"
    enforce_roll_configs "$WORK_DIR" "$roll_cfg"

    # Fixed world seed: dimension quality is independent of it (explicit
    # per-dimension seeds feed noise + structure placement).
    start_container "1"
    log "Container started (world seed fixed, $DIM_CANDIDATES clones)"

    if ! wait_for_rcon; then
      dump_crash_log "$dim_name/round$round" "Failed to boot"
      warn "Round $round failed to boot - see $ERROR_LOG"
      docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
      continue
    fi

    rcon "tick freeze" > /dev/null 2>&1 || true
    rcon "gamerule doMobSpawning false" > /dev/null 2>&1 || true

    local i clone_id measured=0
    for i in $(seq 1 "${#seeds[@]}"); do
      local idx=$((i - 1))
      local seed="${seeds[$idx]}"
      clone_id="${ns}:${dim_name}__s$(printf '%02d' "$i")"
      echo "  [${i}/${#seeds[@]}] $clone_id (seed $seed)"

      # Prove the clone exists before measuring (boot creation is queued).
      local tries=0
      while ((tries < 12)); do
        if rcon "execute in $clone_id run seed" | grep -q "Seed"; then
          break
        fi
        tries=$((tries + 1))
        sleep 5
      done
      if ((tries >= 12)); then
        warn "  clone $clone_id never appeared - skipping"
        continue
      fi

      if measure_target "$dim_name" "$seed" "$clone_id" > /dev/null; then
        measured=$((measured + 1))
      fi
    done

    docker rm -f "$CONTAINER_NAME" > /dev/null 2>&1 || true
    echo "  Round $round: measured $measured/${#seeds[@]} candidates"
    echo ""
  done

  echo "Dimension roll complete. Score with:"
  echo "  ./scripts/seed/report-top.sh --profile $PROFILE_NAME --target $dim_name"
}

# ---------------------------------------------------------------------------
# run_sequential / run_parallel - world-seed batch loops.
# ---------------------------------------------------------------------------
run_sequential() {
  for batch in $(seq 1 "$BATCH_COUNT"); do
    echo ">>> Batch $batch of $BATCH_COUNT ($BATCH_SIZE seeds)"
    echo ""

    seeds_tested=0
    seeds_skipped=0
    seeds_failed=0
    seeds_biome_rejected=0

    for _i in $(seq 1 "$BATCH_SIZE"); do
      seed=$(generate_seed)

      if is_measured "world" "$seed"; then
        seeds_skipped=$((seeds_skipped + 1))
        continue
      fi

      seeds_tested=$((seeds_tested + 1))
      echo "--- Seed $seeds_tested/$BATCH_SIZE: $seed ---"

      test_result=0
      test_world_seed "$seed" || test_result=$?

      if ((test_result == 2)); then
        seeds_biome_rejected=$((seeds_biome_rejected + 1))
      elif ((test_result != 0)); then
        seeds_failed=$((seeds_failed + 1))
        echo "    (skipped - moving to next seed)" >&2
      fi

      echo ""
    done

    measured_count=$((seeds_tested - seeds_failed - seeds_biome_rejected))
    echo "Batch $batch complete: $seeds_tested tested, $measured_count measured, $seeds_biome_rejected bad-biome skips, $seeds_failed failed."
    echo "Score any time with: ./scripts/seed/report-top.sh --profile $PROFILE_NAME"
    echo ""
  done
}

run_parallel() {
  local nworkers=$PARALLEL_WORKERS
  echo "Parallel mode: $nworkers workers"
  echo ""

  for wid in $(seq 0 $((nworkers - 1))); do
    prepare_worker_dir "$wid"
  done
  echo "Worker directories ready"
  echo ""

  for batch in $(seq 1 "$BATCH_COUNT"); do
    echo ">>> Batch $batch of $BATCH_COUNT ($BATCH_SIZE seeds, $nworkers parallel)"
    echo ""

    local batch_seeds=()
    local gen_attempts=0
    while ((${#batch_seeds[@]} < BATCH_SIZE && gen_attempts < BATCH_SIZE * 2)); do
      local s
      s=$(generate_seed)
      gen_attempts=$((gen_attempts + 1))
      if ! is_measured "world" "$s"; then
        batch_seeds+=("$s")
      fi
    done

    if ((${#batch_seeds[@]} == 0)); then
      echo "No new seeds to test."
      break
    fi

    echo "Testing ${#batch_seeds[@]} seeds across $nworkers workers..."
    echo ""

    local worker_pids=()
    for wid in $(seq 0 $((nworkers - 1))); do
      (
        CONTAINER_NAME="seedroll-${wid}"
        WORK_DIR="${WORK_DIR_BASE}-w${wid}"
        local w_tested=0 w_measured=0 w_biome_skip=0 w_failed=0

        for idx in $(seq "$wid" "$nworkers" $((${#batch_seeds[@]} - 1))); do
          local seed="${batch_seeds[$idx]}"
          echo "[W${wid}] Seed: $seed"

          local tr=0
          test_world_seed "$seed" > /dev/null 2>&1 || tr=$?
          w_tested=$((w_tested + 1))

          if ((tr == 0)); then
            w_measured=$((w_measured + 1))
            echo "[W${wid}]   MEASURED"
          elif ((tr == 2)); then
            w_biome_skip=$((w_biome_skip + 1))
            echo "[W${wid}]   bad biome - skipped"
          else
            w_failed=$((w_failed + 1))
            echo "[W${wid}]   FAILED"
          fi
        done

        echo "[W${wid}] Done: $w_tested tested, $w_measured measured, $w_biome_skip biome skips, $w_failed failed"
      ) &
      worker_pids+=($!)
    done

    for pid in "${worker_pids[@]}"; do
      wait "$pid" || true
    done

    echo "Batch $batch complete."
    echo ""
  done
}

# ===========================================================================
# Main
# ===========================================================================
WORK_DIR_BASE="$WORK_DIR"
mod_count=$(ls "$WORK_DIR/mods/"*.jar 2> /dev/null | wc -l | tr -d ' ')

echo ""
echo "============================================="
echo "  Minecraft Seed Roller (measure-only)"
echo "============================================="
if [[ -n "$ROLL_DIMENSION" ]]; then
  echo "  Mode:          dimension roll ($ROLL_DIMENSION)"
  echo "  Candidates:    $DIM_CANDIDATES per boot x $DIM_ROUNDS round(s)"
else
  echo "  Mode:          world roll"
  echo "  Batch size:    $BATCH_SIZE seeds"
  echo "  Max batches:   $BATCH_COUNT"
  echo "  Workers:       $PARALLEL_WORKERS"
fi
echo "  Profile:       $PROFILE_NAME (drives battery + grid + rejection)"
echo "  Memory:        $ROLL_MEMORY per container"
echo "  Mods:          $mod_count JARs (non-worldgen stripped)"
echo "  Measurements:  $RESULTS_CSV"
echo "  Error log:     $ERROR_LOG"
echo "============================================="
echo ""

if [[ -n "$ROLL_DIMENSION" ]]; then
  run_dimension_roll
elif ((PARALLEL_WORKERS > 1)); then
  run_parallel
else
  run_sequential
fi

echo ""
echo "============================================="
echo "  Rolling complete - measurements banked."
echo "============================================="
echo ""
echo "Next steps:"
echo "  1. ./scripts/seed/report-top.sh --profile $PROFILE_NAME${ROLL_DIMENSION:+ --target $ROLL_DIMENSION}"
echo "  2. Review the report; a human picks winners (nothing auto-applies)."
echo "  3. World seed -> SEED= in .env; dimension seeds -> the dimension's"
echo "     entry in config/multiverse_config.json."
echo ""
