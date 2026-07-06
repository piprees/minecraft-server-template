#!/usr/bin/env bash
# =============================================================================
# roll-seeds.sh - Batch-test Minecraft seeds against the real modded server
# =============================================================================
#
# Reuses your existing local server setup (Fabric, mods, configs) so nothing
# is re-downloaded between seeds. Copies mods from data/ once into
# seedtest-data/, then for each seed: deletes only world data, boots with
# the new seed, checks spawn biome (skips bad spawns immediately), runs a
# /locate battery over RCON, scores, and logs to CSV.
# Resumable - skips seeds already scored.
#
# Optimisations:
#   - Biome-first: checks spawn biome before any structure locates (~97% of
#     seeds are rejected in ~40s instead of ~5 min)
#   - No Chunky: /locate works from seed maths, no pre-generated chunks needed
#   - Correct modded structure IDs (betterstrongholds, betterfortresses)
#   - Harmless mod errors filtered from error count
#
# Usage:
#   ./roll-seeds.sh                      # Run with defaults (1 batch of 128)
#   BATCH_SIZE=50 PARALLEL_WORKERS=1 ./roll-seeds.sh
#   ./roll-seeds.sh --clean              # Force re-copy of mods from data/
#
# Environment variables:
#   BATCH_SIZE    - seeds per batch (default: 128)
#   BATCH_COUNT   - max batches to run (default: 1)
#   ROLL_MEMORY   - container memory allocation (default: 6G)
#   RCON_TIMEOUT  - seconds to wait for RCON readiness (default: 300)
#
# Requires: Docker, a working local server (run dev-up.sh first)
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
# Parallel mode requires flock (Linux). On macOS: brew install util-linux

# Java heap = Docker memory minus 1G headroom for JVM/native overhead
if [[ "$ROLL_MEMORY" =~ ^([0-9]+)G$ ]]; then
  _mem_gb=${BASH_REMATCH[1]}
  JAVA_MEMORY="$((_mem_gb > 2 ? _mem_gb - 1 : _mem_gb))G"
else
  JAVA_MEMORY="$ROLL_MEMORY"
fi

RCON_PW="seedroll"
IMAGE="itzg/minecraft-server:latest"
CONTAINER_NAME="seedroll"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"

RESULTS_CSV="$PROJECT_ROOT/seed-results.csv"
ERROR_LOG="$PROJECT_ROOT/seed-errors.log"
WORK_DIR="$PROJECT_ROOT/seedtest-data"
LOCAL_DATA="$PROJECT_ROOT/data"
SCORER="$SCRIPT_DIR/score-seed.sh"

# ---------------------------------------------------------------------------
# Logging - all progress to stderr (visible), score to stdout (captured).
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

if [[ ! -f "$SCORER" ]]; then
  echo "Error: Scoring script not found at $SCORER" >&2
  exit 1
fi

chmod +x "$SCORER"

if ! ls "$LOCAL_DATA/mods/"*.jar &> /dev/null 2>&1; then
  echo "Error: No mods found in data/mods/." >&2
  echo "  Run ./dev up (or ./scripts/dev-up.sh) first to download the modpack," >&2
  echo "  then re-run this script." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# --clean flag: force re-copy of server files from data/
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--clean" ]]; then
  echo "Cleaning seedtest-data (will re-copy from local server)..."
  rm -rf "$WORK_DIR" "$WORK_DIR"-w*
  shift
fi

if [[ "${1:-}" == "--fresh" ]]; then
  echo "Clearing old results (starting fresh CSV)..."
  rm -f "$RESULTS_CSV"
  shift
fi

# ---------------------------------------------------------------------------
# Prepare seedtest-data: copy Fabric + mods + configs from the local server.
# This runs once; subsequent invocations reuse the existing directory.
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

  for dir in config defaultconfigs; do
    if [[ -d "$LOCAL_DATA/$dir" ]]; then
      cp -a "$LOCAL_DATA/$dir" "$WORK_DIR/"
    fi
  done

  for dir in moonlight-global-datapacks villagerpacks; do
    if [[ -d "$LOCAL_DATA/$dir" ]]; then
      cp -a "$LOCAL_DATA/$dir" "$WORK_DIR/"
    fi
  done

  touch "$WORK_DIR/.seedroll-ready"
  echo "  Ready - Fabric + mods + configs cached in seedtest-data/"
}

# ---------------------------------------------------------------------------
# strip_seedroll_mods - remove JARs that don't affect worldgen/structures.
# Speeds up boot by ~10s and avoids DH race-condition crashes.
# Run every time (idempotent) so new exclusions take effect without --clean.
# ---------------------------------------------------------------------------
SEEDROLL_EXCLUDE_PATTERNS=(
  "DistantHorizons-*"          # LOD renderer - SQLite init slows boot, causes crashes
  "bluemap-*"                  # web map - no worldgen impact
  "dcintegration-*"            # Discord bridge - JDA login wastes 5s+ per boot
  "voicechat-*"                # voice chat server
  "LuckPerms-*"                # permissions system
  "ledger-*"                   # block change logging
  "styled-chat-*"              # chat formatting
  "essential_commands-*"       # /home, /tpa etc.
  "NoChatReports-*"            # chat signing removal
  "packetfixer-*"              # packet handling
  "sound-physics-remastered-*" # audio reverb/occlusion
  "appleskin-*"                # food saturation overlay (client-side)
  "bettercombat-*"             # combat mechanics
  "player-animation-lib-*"     # animation (bettercombat dep)
  "carryon-*"                  # block carrying
  "netherportalfix-*"          # portal linking fix
  "netherportalspread-*"       # nether block spread
  "collective-*"               # shared lib (only for netherportalspread)
  "FallingTree-*"              # tree chopping
  "letmedespawn-*"             # mob despawn control
  "Almanac-*"                  # shared lib (only for letmedespawn)
  "fabric-seasons-*"           # seasons visuals - no worldgen impact
  "open-parties-and-claims-*"  # land claiming
  "chipped-*"                  # block variants (14MB, no worldgen)
  "DramaticDoors-*"            # tall doors (12MB, no worldgen)
  "handcrafted-*"              # furniture (7MB, no worldgen)
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
  # Also remove luckperms data dir if present
  rm -rf "$dir/mods/luckperms" 2> /dev/null || true
  if ((removed > 0)); then
    echo "  Stripped $removed non-worldgen mod JARs for faster boot"
  fi
}

# ---------------------------------------------------------------------------
# Locking helpers for thread-safe file writes in parallel mode.
# ---------------------------------------------------------------------------
write_csv_row() {
  (
    flock -x 200
    echo "$1" >> "$RESULTS_CSV"
  ) 200> "${RESULTS_CSV}.lock"
}

write_error_log() {
  (
    flock -x 200
    cat >> "$ERROR_LOG"
  ) 200> "${ERROR_LOG}.lock"
}

# ---------------------------------------------------------------------------
# prepare_worker_dir - create per-worker directory with hard-linked mods.
# ---------------------------------------------------------------------------
prepare_worker_dir() {
  local worker_id=$1
  local worker_dir="${WORK_DIR}-w${worker_id}"

  if [[ -d "$worker_dir" && -f "$worker_dir/.worker-ready" ]]; then
    return 0
  fi

  mkdir -p "$worker_dir/mods"
  # Hard-link mods (saves disk, instant)
  for jar in "$WORK_DIR/mods/"*.jar; do
    [[ -f "$jar" ]] && ln "$jar" "$worker_dir/mods/" 2> /dev/null || cp "$jar" "$worker_dir/mods/"
  done
  # Copy server files
  for item in .fabric libraries versions .install-fabric.env eula.txt; do
    [[ -e "$WORK_DIR/$item" ]] && cp -a "$WORK_DIR/$item" "$worker_dir/"
  done
  cp "$WORK_DIR"/fabric-server-mc.*.jar "$worker_dir/" 2> /dev/null || true
  for dir in config defaultconfigs moonlight-global-datapacks villagerpacks; do
    [[ -d "$WORK_DIR/$dir" ]] && cp -a "$WORK_DIR/$dir" "$worker_dir/"
  done
  touch "$worker_dir/.worker-ready"
}

prepare_seedtest_dir
strip_seedroll_mods "$WORK_DIR"

# ---------------------------------------------------------------------------
# CSV setup
# ---------------------------------------------------------------------------
if [[ ! -f "$RESULTS_CSV" ]]; then
  echo "seed,stronghold_dist,village_dist,fortress_dist,bastion_dist,portal_dist,fortress_x,fortress_z,bastion_x,bastion_z,spawn_biome,score" \
    > "$RESULTS_CSV"
  echo "Created new results file: $RESULTS_CSV"
else
  echo "Resuming from existing results: $RESULTS_CSV"
  echo "  $(($(wc -l < "$RESULTS_CSV") - 1)) seeds already scored."
fi

# ---------------------------------------------------------------------------
# Error log setup
# ---------------------------------------------------------------------------
echo "" >> "$ERROR_LOG"
echo "=== Seed roll session: $(date '+%Y-%m-%d %H:%M:%S') ===" >> "$ERROR_LOG"
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
  rm -rf "$WORK_DIR/world"
  rm -f "$WORK_DIR/server.properties"
  rm -f "${RESULTS_CSV}.lock" "${ERROR_LOG}.lock" "${RESULTS_CSV}.sorting"
  if ((exit_code != 0)); then
    echo "Script interrupted. Results saved so far in $RESULTS_CSV"
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
# is_seed_scored
# ---------------------------------------------------------------------------
is_seed_scored() {
  grep -q "^${1}," "$RESULTS_CSV" 2> /dev/null
}

# ---------------------------------------------------------------------------
# generate_seed
# ---------------------------------------------------------------------------
generate_seed() {
  od -An -tu8 -N8 /dev/urandom | tr -d ' '
}

# ---------------------------------------------------------------------------
# container_alive - check the seedroll container is still running.
# ---------------------------------------------------------------------------
container_alive() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2> /dev/null)" == "true" ]]
}

# ---------------------------------------------------------------------------
# dump_crash_log - save the last N lines of container logs to the error log.
# ---------------------------------------------------------------------------
dump_crash_log() {
  local seed="$1" reason="$2"
  {
    echo ""
    echo "--- SEED $seed: $reason ($(date '+%H:%M:%S')) ---"
    docker logs --tail 50 "$CONTAINER_NAME" 2>&1 || echo "(no logs available)"
    echo "--- END ---"
  } >> "$ERROR_LOG"
}

# ---------------------------------------------------------------------------
# rcon - run an RCON command with crash detection.
# ---------------------------------------------------------------------------
rcon() {
  if ! container_alive; then
    return 1
  fi
  docker exec "$CONTAINER_NAME" rcon-cli --password "$RCON_PW" "$@" 2> /dev/null
}

# ---------------------------------------------------------------------------
# wait_for_rcon - poll until RCON responds, container dies, or timeout.
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# parse_locate
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
# Spawn biome lists - "green" biomes are ideal, "ok" are acceptable.
# Anything not listed here is a bad spawn and gets skipped early.
# ---------------------------------------------------------------------------
GREEN_SPAWN_BIOMES=(
  "minecraft:plains" "minecraft:sunflower_plains" "minecraft:meadow"
  "minecraft:savanna" "minecraft:forest" "minecraft:birch_forest"
  "minecraft:flower_forest" "minecraft:cherry_grove"
  "minecraft:old_growth_birch_forest" "minecraft:sparse_jungle"
  "minecraft:savanna_plateau"
  "terralith:blooming_valley" "terralith:lush_valley"
  "terralith:lavender_valley" "terralith:blooming_plateau"
  "terralith:sakura_valley" "terralith:sakura_grove"
  "terralith:temperate_highlands" "terralith:brushland" "terralith:steppe"
  "terralith:shrubland" "terralith:moonlight_valley" "terralith:moonlight_grove"
  "terralith:orchid_swamp" "terralith:alpine_grove"
  "terralith:lush_desert" "terralith:arid_highlands"
  "terralith:forested_highlands" "terralith:birch_taiga"
  "terralith:shield" "terralith:shield_clearing"
)

OK_SPAWN_BIOMES=(
  "minecraft:dark_forest" "minecraft:taiga" "minecraft:old_growth_pine_taiga"
  "minecraft:old_growth_spruce_taiga" "minecraft:jungle" "minecraft:bamboo_jungle"
  "minecraft:windswept_hills" "minecraft:windswept_forest"
  "minecraft:windswept_gravelly_hills" "minecraft:wooded_badlands"
  "minecraft:river" "minecraft:beach" "minecraft:stony_shore"
  "minecraft:mangrove_swamp" "minecraft:snowy_plains" "minecraft:snowy_taiga"
  "minecraft:grove" "minecraft:desert"
  "terralith:cloud_forest" "terralith:haze_mountain" "terralith:rocky_mountains"
  "terralith:caldera" "terralith:mirage_isles" "terralith:granite_cliffs"
  "terralith:highlands" "terralith:basalt_cliffs" "terralith:hot_shrubland"
  "terralith:desert_canyon" "terralith:desert_oasis"
  "terralith:fractured_savanna" "terralith:red_oasis"
  "terralith:savanna_badlands" "terralith:savanna_slopes" "terralith:white_cliffs"
)

# ---------------------------------------------------------------------------
# detect_spawn_biome - uses instant "execute if biome" check at 0 64 0.
# Returns the biome ID or "unknown" if no known biome matches.
# ---------------------------------------------------------------------------
detect_spawn_biome() {
  local biome result

  for biome in "${GREEN_SPAWN_BIOMES[@]}"; do
    if ! container_alive; then
      echo "unknown"
      return
    fi
    result=$(rcon "execute if biome 0 64 0 $biome" 2> /dev/null || echo "")
    if [[ -n "$result" ]] && ! echo "$result" | grep -qi "fail\|could not\|unknown"; then
      echo "$biome"
      return
    fi
  done

  for biome in "${OK_SPAWN_BIOMES[@]}"; do
    if ! container_alive; then
      echo "unknown"
      return
    fi
    result=$(rcon "execute if biome 0 64 0 $biome" 2> /dev/null || echo "")
    if [[ -n "$result" ]] && ! echo "$result" | grep -qi "fail\|could not\|unknown"; then
      echo "$biome"
      return
    fi
  done

  echo "unknown"
}

# ---------------------------------------------------------------------------
# test_seed - the core loop body.
#
# Steps: boot > RCON > biome check (skip if bad) > Nether forceload >
#        locate battery > score > teardown.
#
# Returns: 0 = scored, 1 = crash/failure, 2 = bad biome (skipped early).
# ---------------------------------------------------------------------------
test_seed() {
  local seed="$1"

  # --- Step 1: Clean world data ---
  rm -rf "$WORK_DIR/world"
  rm -f "$WORK_DIR/server.properties"
  log "Cleaned world data"

  # --- Step 2: Start container ---
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
  log "Container started"

  # --- Step 3: Wait for RCON ---
  if ! wait_for_rcon; then
    dump_crash_log "$seed" "Failed to boot / RCON never ready"
    warn "SKIP - server failed to start (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  # --- Step 3b: Freeze ticks to reduce CPU/memory during evaluation ---
  rcon "tick freeze" > /dev/null 2>&1 || true
  rcon "gamerule doMobSpawning false" > /dev/null 2>&1 || true

  # --- Step 4: Check spawn biome FIRST (instant, no chunk gen needed) ---
  log "Checking spawn biome..."
  local spawn_biome
  spawn_biome=$(detect_spawn_biome)
  log "  Biome: $spawn_biome"

  if [[ "$spawn_biome" == "unknown" ]]; then
    log "Bad spawn biome - skipping seed"
    docker rm -f "$CONTAINER_NAME" > /dev/null 2>&1 || true
    return 2
  fi

  # --- Step 5: Force-load Nether chunk (no Chunky needed - /locate works from seed maths) ---
  log "Loading Nether chunk..."
  if ! rcon "execute in minecraft:the_nether run forceload add 0 0" > /dev/null; then
    dump_crash_log "$seed" "Crashed loading Nether"
    warn "SKIP - crashed loading Nether (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi
  sleep 3

  if ! container_alive; then
    dump_crash_log "$seed" "Died after Nether load"
    warn "SKIP - container died after Nether load (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  # --- Step 6: Structure locate battery (modded IDs for replaced structures) ---
  log "Running locate commands..."
  local sh_raw vi_raw fo_raw ba_raw rp_raw

  logn "  Stronghold... "
  sh_raw=$(rcon "execute in minecraft:overworld run locate structure betterstrongholds:stronghold" || echo "")
  echo "${sh_raw:-(not found)}" | head -1 >&2

  logn "  Village... "
  vi_raw=$(rcon "execute in minecraft:overworld run locate structure #minecraft:village" || echo "")
  echo "${vi_raw:-(not found)}" | head -1 >&2

  logn "  Ruined portal... "
  rp_raw=$(rcon "execute in minecraft:overworld run locate structure minecraft:ruined_portal" || echo "")
  echo "${rp_raw:-(not found)}" | head -1 >&2

  logn "  Fortress... "
  fo_raw=$(rcon "execute in minecraft:the_nether run locate structure betterfortresses:fortress" || echo "")
  echo "${fo_raw:-(not found)}" | head -1 >&2

  logn "  Bastion... "
  ba_raw=$(rcon "execute in minecraft:the_nether run locate structure minecraft:bastion_remnant" || echo "")
  echo "${ba_raw:-(not found)}" | head -1 >&2

  if ! container_alive; then
    dump_crash_log "$seed" "Crashed during locate battery"
    warn "SKIP - crashed during locates (see $ERROR_LOG)"
    docker rm -f "$CONTAINER_NAME" 2> /dev/null || true
    return 1
  fi

  # --- Step 7: Parse results ---
  local sh_parsed vi_parsed fo_parsed ba_parsed rp_parsed
  sh_parsed=$(parse_locate "$sh_raw")
  vi_parsed=$(parse_locate "$vi_raw")
  fo_parsed=$(parse_locate "$fo_raw")
  ba_parsed=$(parse_locate "$ba_raw")
  rp_parsed=$(parse_locate "$rp_raw")

  local sh_dist vi_dist fo_dist ba_dist rp_dist
  local fo_x fo_z ba_x ba_z

  sh_dist=$(echo "$sh_parsed" | awk '{print $1}')
  vi_dist=$(echo "$vi_parsed" | awk '{print $1}')
  fo_dist=$(echo "$fo_parsed" | awk '{print $1}')
  ba_dist=$(echo "$ba_parsed" | awk '{print $1}')
  rp_dist=$(echo "$rp_parsed" | awk '{print $1}')

  fo_x=$(echo "$fo_parsed" | awk '{print $2}')
  fo_z=$(echo "$fo_parsed" | awk '{print $3}')
  ba_x=$(echo "$ba_parsed" | awk '{print $2}')
  ba_z=$(echo "$ba_parsed" | awk '{print $3}')

  # --- Step 8: Score ---
  local score
  score=$("$SCORER" \
    "${sh_dist:-}" "${vi_dist:-}" "${fo_dist:-}" "${ba_dist:-}" "${rp_dist:-}" \
    "${fo_x:-}" "${fo_z:-}" "${ba_x:-}" "${ba_z:-}" "$spawn_biome" 2> /dev/null)

  write_csv_row "${seed},${sh_dist:-},${vi_dist:-},${fo_dist:-},${ba_dist:-},${rp_dist:-},${fo_x:-},${fo_z:-},${ba_x:-},${ba_z:-},${spawn_biome},${score}"

  # --- Step 9: Count non-trivial errors (filter harmless mod noise) ---
  local error_count
  error_count=$(docker logs "$CONTAINER_NAME" 2>&1 \
    | grep -i "ERROR" \
    | grep -v -e "No data fixer registered" \
      -e "Error loading class" \
      -e "Block-attached entity at invalid position" \
      -e "template pool reference" \
    | wc -l | tr -d ' ')
  if ((error_count > 0)); then
    {
      echo ""
      echo "--- SEED $seed: $error_count non-trivial errors (score: $score) ---"
      docker logs "$CONTAINER_NAME" 2>&1 \
        | grep -i "ERROR" \
        | grep -v -e "No data fixer registered" \
          -e "Error loading class" \
          -e "Block-attached entity at invalid position" \
          -e "template pool reference" \
        | head -20
      echo "--- END ---"
    } | write_error_log
  fi

  # --- Step 10: Teardown ---
  docker rm -f "$CONTAINER_NAME" > /dev/null 2>&1 || true

  log "Score: $score (SH=${sh_dist:-n/a} VI=${vi_dist:-n/a} FO=${fo_dist:-n/a} BA=${ba_dist:-n/a} RP=${rp_dist:-n/a} biome=$spawn_biome)"

  echo "$score"
  return 0
}

# ---------------------------------------------------------------------------
# get_top_scores / print_top_seeds
# ---------------------------------------------------------------------------
get_top_scores() {
  local n="${1:-3}"
  tail -n +2 "$RESULTS_CSV" | sort -t',' -k12 -rn | head -n "$n"
}

print_top_seeds() {
  local n="${1:-3}"
  echo ""
  echo "=== Top $n seeds so far ==="
  echo "Seed                 | Score  | SH    | VI    | FO    | BA    | Biome"
  echo "---------------------|--------|-------|-------|-------|-------|------"
  # shellcheck disable=SC2034
  get_top_scores "$n" | while IFS=',' read -r seed sh vi fo ba rp fx fz bx bz biome score; do
    printf "%-20s | %6s | %5s | %5s | %5s | %5s | %s\n" \
      "$seed" "$score" "${sh:-n/a}" "${vi:-n/a}" "${fo:-n/a}" "${ba:-n/a}" "$biome"
  done
  echo ""
}

# ---------------------------------------------------------------------------
# sort_csv - sort results by score (highest first), keeping header.
# ---------------------------------------------------------------------------
sort_csv() {
  if [[ ! -f "$RESULTS_CSV" ]] || (($(wc -l < "$RESULTS_CSV") < 2)); then
    return
  fi
  local tmp="${RESULTS_CSV}.sorting"
  head -1 "$RESULTS_CSV" > "$tmp"
  tail -n +2 "$RESULTS_CSV" | sort -t',' -k12 -rn >> "$tmp"
  mv "$tmp" "$RESULTS_CSV"
}

# ---------------------------------------------------------------------------
# run_sequential - the original sequential main loop.
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

      if is_seed_scored "$seed"; then
        seeds_skipped=$((seeds_skipped + 1))
        continue
      fi

      seeds_tested=$((seeds_tested + 1))
      total_tested=$(($(tail -n +2 "$RESULTS_CSV" | wc -l) + 1))
      echo "--- Seed $seeds_tested/$BATCH_SIZE (total #$total_tested): $seed ---"

      test_result=0
      score=$(test_seed "$seed") || test_result=$?

      if ((test_result == 2)); then
        seeds_biome_rejected=$((seeds_biome_rejected + 1))
      elif ((test_result != 0)); then
        seeds_failed=$((seeds_failed + 1))
        echo "    (skipped - moving to next seed)" >&2
      fi

      echo ""
    done

    scored_count=$((seeds_tested - seeds_failed - seeds_biome_rejected))
    echo "Batch $batch complete: $seeds_tested tested, $scored_count scored, $seeds_biome_rejected bad-biome skips, $seeds_failed failed."
    print_top_seeds 3

    if ((batch == 1)); then
      best_batch1_score=$(get_top_scores 1 | cut -d',' -f12)
      best_batch1_score="${best_batch1_score:-0.00}"
      echo "Best score from batch 1: $best_batch1_score"
    fi

    if ((batch >= 2)); then
      local_best=$(get_top_scores 1 | cut -d',' -f12)
      local_best="${local_best:-0.00}"

      improvement=$(awk "BEGIN { print (${local_best} > ${best_batch1_score}) ? 1 : 0 }")

      if ((improvement == 0)); then
        echo ""
        echo "Batch $batch didn't beat the top score from earlier batches."
        echo "Stopping early - diminishing returns."
        echo ""
        break
      else
        echo "New best score: $local_best (was $best_batch1_score)"
        best_batch1_score="$local_best"
      fi
    fi
  done
}

# ---------------------------------------------------------------------------
# run_parallel - dispatch seeds across N workers.
# Each worker gets its own container name and work directory.
# ---------------------------------------------------------------------------
run_parallel() {
  local nworkers=$PARALLEL_WORKERS
  echo "Parallel mode: $nworkers workers"
  echo ""

  # Prepare per-worker directories (hard-linked mods)
  for wid in $(seq 0 $((nworkers - 1))); do
    prepare_worker_dir "$wid"
  done
  echo "Worker directories ready"
  echo ""

  for batch in $(seq 1 "$BATCH_COUNT"); do
    echo ">>> Batch $batch of $BATCH_COUNT ($BATCH_SIZE seeds, $nworkers parallel)"
    echo ""

    # Pre-generate seeds for this batch, filtering already-scored
    local batch_seeds=()
    local gen_attempts=0
    while ((${#batch_seeds[@]} < BATCH_SIZE && gen_attempts < BATCH_SIZE * 2)); do
      local s
      s=$(generate_seed)
      gen_attempts=$((gen_attempts + 1))
      if ! is_seed_scored "$s"; then
        batch_seeds+=("$s")
      fi
    done

    if ((${#batch_seeds[@]} == 0)); then
      echo "No new seeds to test."
      break
    fi

    echo "Testing ${#batch_seeds[@]} seeds across $nworkers workers..."
    echo ""

    # Partition seeds round-robin across workers
    local worker_pids=()
    for wid in $(seq 0 $((nworkers - 1))); do
      (
        # Worker subshell: override globals
        CONTAINER_NAME="seedroll-${wid}"
        WORK_DIR="${WORK_DIR_BASE}-w${wid}"
        local w_tested=0 w_scored=0 w_biome_skip=0 w_failed=0

        for idx in $(seq "$wid" "$nworkers" $((${#batch_seeds[@]} - 1))); do
          local seed="${batch_seeds[$idx]}"
          echo "[W${wid}] Seed: $seed"

          local tr=0
          test_seed "$seed" > /dev/null 2>&1 || tr=$?
          w_tested=$((w_tested + 1))

          if ((tr == 0)); then
            w_scored=$((w_scored + 1))
            echo "[W${wid}]   SCORED"
          elif ((tr == 2)); then
            w_biome_skip=$((w_biome_skip + 1))
            echo "[W${wid}]   bad biome - skipped"
          else
            w_failed=$((w_failed + 1))
            echo "[W${wid}]   FAILED"
          fi
        done

        echo "[W${wid}] Done: $w_tested tested, $w_scored scored, $w_biome_skip biome skips, $w_failed failed"
      ) &
      worker_pids+=($!)
    done

    # Wait for all workers
    local any_failed=0
    for pid in "${worker_pids[@]}"; do
      wait "$pid" || any_failed=$((any_failed + 1))
    done

    sort_csv
    print_top_seeds 5
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
echo "  Minecraft Seed Roller"
echo "============================================="
echo "  Batch size:    $BATCH_SIZE seeds"
echo "  Max batches:   $BATCH_COUNT"
echo "  Workers:       $PARALLEL_WORKERS"
echo "  Memory:        $ROLL_MEMORY per container"
echo "  RCON timeout:  ${RCON_TIMEOUT}s"
echo "  Strategy:      biome-first (skip bad spawns before locates)"
echo "  Mods:          $mod_count JARs (non-worldgen stripped)"
echo "  Results:       $RESULTS_CSV"
echo "  Error log:     $ERROR_LOG"
echo "============================================="
echo ""

best_batch1_score="0.00"

if ((PARALLEL_WORKERS > 1)); then
  run_parallel
else
  run_sequential
fi

# ===========================================================================
# Sort CSV by score and final report
# ===========================================================================
sort_csv

echo ""
echo "============================================="
echo "  Seed rolling complete!"
echo "============================================="
echo ""

total_scored=$(tail -n +2 "$RESULTS_CSV" | wc -l | tr -d ' ')
total_errors=$(grep -c "^--- SEED" "$ERROR_LOG" 2> /dev/null || echo "0")
echo "Total seeds scored: $total_scored"
echo "Total error entries: $total_errors (see $ERROR_LOG)"
echo "Results saved to: $RESULTS_CSV (sorted by score, highest first)"
echo ""

print_top_seeds 25

echo ""
echo "Next steps:"
echo "  1. Run ./scripts/seed/report-top.sh to generate a markdown report"
echo "  2. Review $ERROR_LOG for mod errors to fix before production"
echo "  3. Pick your favourite seed and set SEED= in .env"
echo "  4. Boot the server with that seed and fly it in spectator to confirm"
echo ""
