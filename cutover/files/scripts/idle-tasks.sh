#!/usr/bin/env bash
# idle-tasks.sh - Run maintenance when no players are connected.
#
# Runs bind-mounted in the idle-tasks container (cloud profile). Polls RCON
# for the player count; when the server stays empty for IDLE_GRACE minutes:
# save-all flush, spark gc, then pre-generation across every dimension a
# player has actually visited.
#
# Pre-generation runs in two stages per dimension, in queue order:
#   1. Chunky, out to the dimension's borders.generation — real chunks, so no
#      generation stutter where players actually go, and region files for
#      the web map's explored layer.
#   2. Distant Horizons `/dh pregen`, out to the larger of the dimension's
#      player and generation borders — LOD only, and what a player sees past
#      the Chunky radius. The mod answers DH's generation requests from the
#      dimension's noise (lod/DhLodGenerator): terrain, surface blocks,
#      fluids and biomes, without structures, trees or carvers, and DH's
#      distantGeneratorMode does not change that answer. Where Chunky already
#      made chunks, DH builds its LODs from those chunks as they load.
#
# Chunky's extent is `borders.generation` in the dimension's own definition
# (falling back to `borders.player`, then settings.json's defaults); DH's is
# the larger of `borders.player` and `borders.generation`, each falling back
# to settings.json's default. Storage is quadratic in radius, so those
# borders are the whole cost control — there is no separate cap. A lazy
# dimension is opened with `customdim activate` before each stage starts in it.
#
# Only visited dimensions are pre-generated, detected by a region directory
# holding an .mca over 8k.
# A dimension nobody has entered has no region files and costs nothing.
#
# Completion is tracked by per-dimension marker files that persist across
# restarts, under data/: .chunky-<id>-complete and .dhpregen-<id>-complete,
# with ':' and '/' in the id mapped to '_'. Delete one to force that stage to
# re-run (e.g. after a borders.generation increase).
# Pre-gen pauses when a player joins and resumes next idle.
#
# While either stage runs, the script creates /data/.skip-pause — the itzg
# image's built-in autopause bypass. The autopause daemon checks for this
# file and stays in the "established" state (won't freeze the JVM) as
# long as it exists. Removed when pre-gen pauses or the queue empties.
# Pause detection: the JVM's process state (T = SIGSTOPped) is checked via
# `docker exec mc ps` BEFORE any RCON call — the autopause daemon resumes
# the JVM whenever an rcon-cli process exists in the mc container, so an
# RCON poll against a paused server wakes it. RCON timing out while the JVM
# runs means BUSY, not paused — state is kept and the poll retried.
#
# The web map (map.DOMAIN) is exported by the mod: once pre-generation has
# nothing left to do, and at most every MAP_INTERVAL, an idle pass sends
# `customdim map export all`, holds .skip-pause-map while it runs, and drops
# it when the mod stamps /data/map-web/.export-finished (or after
# MAP_EXPORT_MAX_MINUTES). The export redraws each dimension's generated
# layers only when its fingerprint changed and its explored layer only from
# region files changed since the last export. The command goes out over the
# same RCON poll the idle timer uses, only while the server is awake and
# empty; nothing here wakes a paused server for it.
#
# Usage: no arguments. Configured entirely by environment:
#   PREGEN_BORDER_RADIUS blocks, default 8192. Last-resort border, used only
#                        when neither the definition nor settings.json's
#                        defaults.borders gives one.
#   DH_PREGEN            "true"/"false", default true. The stage-2 switch.
#   MAP_INTERVAL         sleep(1) syntax (30m, 6h, 1d), default 6h; 0 turns
#                        the web map export off.
#   MAP_EXPORT_MAX_MINUTES  minutes after which a running export stops
#                        holding autopause off, default 360.
#   IDLE_GRACE           minutes of empty server before maintenance starts.
#   POLL_INTERVAL        seconds between polls.
#   DEPLOY_STALE_MINUTES age at which a deploy sentinel is treated as dead.
#
# Gotchas: the definitions are read as staged in data/config (the consumer
# overlay already merged in by stage-dimensions.py), with jq per dimension.
# The mod unloads an empty lazy level after idleUnloadMinutes, and a stage
# whose level unloads under it stops with it (TROUBLESHOOTING.md TP10-2).
# `/dh pregen` takes a radius in CHUNKS with a minimum of 32 and requires
# permission level 4, which RCON has. Must run on macOS bash 3.2 - no mapfile,
# no ${var,,}, no declare -A.
set -euo pipefail

RCON_HOST="${RCON_HOST:-mc}"
RCON_PASSWORD="${RCON_PASSWORD:-}"
IDLE_GRACE="${IDLE_GRACE:-1}"
POLL_INTERVAL="${POLL_INTERVAL:-30}"

# The border for a dimension whose config cannot be read.
PREGEN_BORDER_RADIUS="${PREGEN_BORDER_RADIUS:-${WORLD_BORDER_RADIUS:-8192}}"
DH_PREGEN="${DH_PREGEN:-true}"

WORLD_DIR="/data/world"
DIM_DIR="/data/config/custom-dimensions/dimensions"
SETTINGS_FILE="$DIM_DIR/settings.json"

# Autopause suspension uses owner files reconciled into the aggregate the
# itzg daemon actually checks (/data/.skip-pause). idle-tasks owns
# .skip-pause-idle; deploy.sh owns .skip-pause-deploying. The aggregate
# exists iff ANY owner file exists, so each owner can clean up its own
# suspension without clobbering the other's.
SKIP_PAUSE_FILE="/data/.skip-pause"
SKIP_PAUSE_OWN="/data/.skip-pause-idle"
SKIP_PAUSE_OTHER="/data/.skip-pause-deploying"
SKIP_PAUSE_MAP="/data/.skip-pause-map"
# While .skip-pause-deploying exists, idle-tasks goes fully dormant — no
# RCON, no pre-gen, no maintenance. A deploy owns the server (restarts,
# dimension setup, config sync); idle work would race it. deploy.sh
# heartbeats the sentinel (its rcon() touches it on every call), so mtime
# staleness distinguishes a live deploy from one that died without running
# its EXIT trap (e.g. dropped SSH session). A stale sentinel is cleaned up
# here — otherwise autopause would stay suppressed forever.
DEPLOY_STALE_MINUTES="${DEPLOY_STALE_MINUTES:-60}"

pregen_active=false
pregen_stage=""      # "chunky" | "dh"
pregen_target=""     # dimension id currently being generated
tasks_done=false

# Bounded at 30s: a wedged main thread never answers, and this loop must
# keep running (and keep its own sentinel fresh) rather than block on it (K6).
rcon() {
  timeout 30 docker exec mc rcon-cli "$@" 2> /dev/null || true
}

# True when the JVM is SIGSTOPped by autopause (process state T).
# CRITICAL: check this BEFORE any rcon call. The autopause daemon resumes
# the JVM whenever an rcon-cli process exists inside the mc container
# (autopause-daemon.sh state S), so polling a paused server via
# `docker exec mc rcon-cli` wakes it — that was a permanent 4-minute
# wake/pause churn loop in production. A `ps` exec doesn't wake anything.
java_paused() {
  local stat
  stat=$(docker exec mc ps -ax -o stat,comm 2> /dev/null | grep java | awk '{print $1}' || echo "")
  [[ "$stat" == T* ]]
}

# True while a live deploy holds .skip-pause-deploying. Removes the
# sentinel (and the aggregate, if we don't own it) when it's gone stale —
# a deploy killed hard (SSH drop = no EXIT trap) must not leave idle-tasks
# dormant and autopause suppressed forever.
deploy_in_progress() {
  [[ -f "$SKIP_PAUSE_OTHER" ]] || return 1
  local now mtime age
  now=$(date +%s)
  mtime=$(stat -c %Y "$SKIP_PAUSE_OTHER" 2> /dev/null || echo 0)
  age=$((now - mtime))
  if [[ $age -gt $((DEPLOY_STALE_MINUTES * 60)) ]]; then
    echo "[$(date '+%H:%M:%S')] Deploy sentinel is ${age}s old (> ${DEPLOY_STALE_MINUTES}min) - the deploy died without cleanup; clearing it"
    rm -f "$SKIP_PAUSE_OTHER"
    [[ -f "$SKIP_PAUSE_OWN" ]] || rm -f "$SKIP_PAUSE_FILE"
    # A dead deploy may have left the server in quiet-boot mode (spawning/
    # ticking off — same rule set as pre-gen mode). Restore once RCON responds.
    pregen_dirty=true
    # ...and may have left mc-backup stopped (deploys pause it). Restart it.
    docker start mc-backup > /dev/null 2>&1 || true
    return 1
  fi
  return 0
}

get_player_count() {
  local result
  if java_paused; then
    echo "-2"
    return
  fi
  result=$(rcon "list" 2> /dev/null || echo "")
  if [[ -z "$result" ]]; then
    echo "-1"
    return
  fi
  echo "$result" | grep -oE 'There are [0-9]+' | grep -oE '[0-9]+' || echo "-1"
}

enter_pregen_mode() {
  rcon "gamerule randomTickSpeed 0"
  rcon "gamerule doDaylightCycle false"
  rcon "gamerule doWeatherCycle false"
  rcon "gamerule doMobSpawning false"
  rcon "gamerule doFireTick false"
  echo "  Pre-gen mode: simulation reduced (ticks/mobs/weather/daylight off)"
}

exit_pregen_mode() {
  rcon "gamerule randomTickSpeed 3"
  rcon "gamerule doDaylightCycle true"
  rcon "gamerule doWeatherCycle true"
  rcon "gamerule doMobSpawning true"
  rcon "gamerule doFireTick true"
  echo "  Pre-gen mode off: simulation restored"
}

enable_skip_pause() {
  if [[ ! -f "$SKIP_PAUSE_OWN" ]]; then
    touch "$SKIP_PAUSE_OWN"
    touch "$SKIP_PAUSE_FILE"
    echo "  Created .skip-pause-idle (autopause bypassed while pre-gen runs)"
  fi
}

disable_skip_pause() {
  if [[ -f "$SKIP_PAUSE_OWN" ]]; then
    rm -f "$SKIP_PAUSE_OWN"
    if [[ ! -f "$SKIP_PAUSE_OTHER" && ! -f "$SKIP_PAUSE_MAP" ]]; then
      rm -f "$SKIP_PAUSE_FILE"
      echo "  Removed .skip-pause-idle (autopause re-enabled)"
    else
      echo "  Removed .skip-pause-idle (deploy suspension still active)"
    fi
  fi
}

run_idle_tasks() {
  echo "[$(date '+%H:%M:%S')] Server empty for ${IDLE_GRACE}min - running idle maintenance"

  echo "  Saving world..."
  rcon "save-all flush"
  sleep 5

  echo "  Requesting garbage collection..."
  rcon "spark gc" || true

  echo "  Idle maintenance complete."
}

# --- The dimension queue ------------------------------------------------------

# A dimension id as a filename fragment: ':' and '/' are legal in ids and not
# in the marker names built from them.
marker_id() {
  echo "$1" | tr ':/' '__'
}

chunky_marker() {
  echo "/data/.chunky-$(marker_id "$1")-complete"
}

dh_marker() {
  echo "/data/.dhpregen-$(marker_id "$1")-complete"
}

# settings.json's `defaults.borders.generation`, falling back to `.player` —
# the same default the mod merges under a dimension naming no border of its
# own. Read once at startup.
DEFAULT_BORDER=""
DEFAULT_BORDERS='{}'
resolve_default_border() {
  local value=""
  if [[ -f "$SETTINGS_FILE" ]]; then
    value=$(jq -r '(.defaults.borders.generation // .defaults.borders.player // empty)' "$SETTINGS_FILE" 2> /dev/null || echo "")
  fi
  if [[ -z "$value" || "$value" == "null" ]]; then
    value="$PREGEN_BORDER_RADIUS"
    echo "  No defaults.borders in settings.json — falling back to PREGEN_BORDER_RADIUS ${value}"
  fi
  DEFAULT_BORDER="$value"
  if [[ -f "$SETTINGS_FILE" ]]; then
    DEFAULT_BORDERS=$(jq -c '.defaults.borders // {}' "$SETTINGS_FILE" 2> /dev/null || echo '{}')
  fi
}

# How far to pre-generate this dimension: its own `borders.generation`, else
# `borders.player`, else settings.json's default.
border_for() {
  local slug="$1" value=""
  if [[ -f "$DIM_DIR/$slug.json" ]]; then
    value=$(jq -r '(.borders.generation // .borders.player // empty)' "$DIM_DIR/$slug.json" 2> /dev/null || echo "")
  fi
  if [[ -z "$value" || "$value" == "null" ]]; then
    value="$DEFAULT_BORDER"
  fi
  echo "$value"
}

# How far DH pre-generates this dimension: the larger of its player and
# generation borders, each falling back to settings.json's default — the
# reach the mod's LOD generator answers at full detail (LevelMaths.lodReach).
lod_reach_for() {
  local slug="$1" value=""
  local larger='[(.borders.player // $d.player), (.borders.generation // $d.generation)] | map(select(. != null)) | max // empty'
  if [[ -f "$DIM_DIR/$slug.json" ]]; then
    value=$(jq -r --argjson d "$DEFAULT_BORDERS" "$larger" "$DIM_DIR/$slug.json" 2> /dev/null || echo "")
  else
    value=$(echo '{}' | jq -r --argjson d "$DEFAULT_BORDERS" "$larger" 2> /dev/null || echo "")
  fi
  if [[ -z "$value" || "$value" == "null" ]]; then
    value="$DEFAULT_BORDER"
  fi
  echo "$value"
}

# True when a player has been here: the same test unmined-render uses to
# decide a dimension is worth drawing.
visited() {
  # -print -quit, never `| head` under pipefail (TROUBLESHOOTING.md#t47).
  [[ -n "$(find "$1" -maxdepth 1 -name '*.mca' -size +8k -print -quit 2> /dev/null)" ]]
}

# Every visited dimension, one "<id>|<region dir>|<border>|<LOD reach>" per line.
# Emitted in a stable order so the queue is deterministic across restarts.
dimension_queue() {
  local slug border reach
  for slug in overworld the_nether the_end; do
    local region
    case "$slug" in
      overworld) region="$WORLD_DIR/region" ;;
      the_nether) region="$WORLD_DIR/DIM-1/region" ;;
      the_end) region="$WORLD_DIR/DIM1/region" ;;
    esac
    if [[ -d "$region" ]] && visited "$region"; then
      border=$(border_for "$slug")
      reach=$(lod_reach_for "$slug")
      case "$slug" in
        overworld) echo "minecraft:overworld|$region|$border|$reach" ;;
        the_nether) echo "minecraft:the_nether|$region|$border|$reach" ;;
        the_end) echo "minecraft:the_end|$region|$border|$reach" ;;
      esac
    fi
  done

  local nsdir dimdir ns
  for nsdir in "$WORLD_DIR"/dimensions/*; do
    [[ -d "$nsdir" ]] || continue
    ns=$(basename "$nsdir")
    for dimdir in "$nsdir"/*; do
      [[ -d "$dimdir/region" ]] || continue
      slug=$(basename "$dimdir")
      visited "$dimdir/region" || continue
      border=$(border_for "$slug")
      reach=$(lod_reach_for "$slug")
      echo "$ns:$slug|$dimdir/region|$border|$reach"
    done
  done
}

# A lazy dimension's level must be open before Chunky or DH can target it;
# the base worlds are always loaded.
open_level() {
  case "$1" in
    minecraft:* | *:the_aviary) ;;
    *) rcon "customdim activate ${1#*:}" ;;
  esac
}

# --- Pre-generation -----------------------------------------------------------

start_pregen() {
  local id region border reach radius chunk_radius

  # Stage 1: the first visited dimension with no Chunky marker.
  while IFS='|' read -r id region border reach; do
    [[ -n "$id" ]] || continue
    [[ -f "$(chunky_marker "$id")" ]] && continue
    radius="$border"
    echo "[$(date '+%H:%M:%S')] Chunky pre-generation: ${id} (radius ${radius})"
    enable_skip_pause
    local resumed
    resumed=$(rcon "chunky continue" 2> /dev/null || echo "")
    enter_pregen_mode
    if echo "$resumed" | grep -qi "continuing\|resumed"; then
      echo "  Resumed paused task"
    else
      rcon "chunky cancel"
      rcon "chunky confirm"
      sleep 1
      open_level "$id"
      rcon "chunky world $id"
      rcon "chunky center 0 0"
      rcon "chunky radius $radius"
      rcon "chunky start"
    fi
    pregen_active=true
    pregen_stage="chunky"
    pregen_target="$id"
    return 0
  done <<< "$(dimension_queue)"

  # Stage 2: LOD out to the LOD reach, once a dimension's chunks are done.
  if [[ "$DH_PREGEN" == "true" ]]; then
    while IFS='|' read -r id region border reach; do
      [[ -n "$id" ]] || continue
      [[ -f "$(chunky_marker "$id")" ]] || continue
      [[ -f "$(dh_marker "$id")" ]] && continue
      chunk_radius=$(((reach + 15) / 16))
      [[ "$chunk_radius" -lt 32 ]] && chunk_radius=32
      echo "[$(date '+%H:%M:%S')] DH LOD pre-generation: ${id} (${chunk_radius} chunks, reach ${reach}, Chunky border ${border})"
      enable_skip_pause
      enter_pregen_mode
      rcon "dh pregen stop"
      open_level "$id"
      rcon "dh pregen start $id 0 0 $chunk_radius"
      pregen_active=true
      pregen_stage="dh"
      pregen_target="$id"
      return 0
    done <<< "$(dimension_queue)"
  fi

  # Explicit 0: a bare `return` inherits the exit status of the last failed
  # test above, which under `set -e` kills the script — the container then
  # restarts in an endless loop once every dimension is finished.
  return 0
}

pause_pregen() {
  if [[ "$pregen_active" == true ]]; then
    echo "[$(date '+%H:%M:%S')] Pausing pre-generation (${pregen_stage}: ${pregen_target})"
    case "$pregen_stage" in
      chunky) rcon "chunky pause" ;;
      dh) rcon "dh pregen stop" ;;
    esac
    exit_pregen_mode
    pregen_active=false
    disable_skip_pause
  fi
}

check_pregen_complete() {
  if [[ "$pregen_active" != true ]]; then
    return 0
  fi

  local status done=false
  case "$pregen_stage" in
    chunky)
      status=$(rcon "chunky progress" 2> /dev/null || echo "")
      echo "$status" | grep -qiE "Task finished|complete|100%" && done=true
      ;;
    dh)
      status=$(rcon "dh pregen status" 2> /dev/null || echo "")
      # A finished or absent task both mean this dimension is done: DH reports
      # no in-progress pregen either way, and a stage that cannot report is not
      # a stage worth blocking the queue on.
      echo "$status" | grep -qiE "complete|finished|not running|no pregen" && done=true
      ;;
  esac
  [[ "$done" == true ]] || return 0

  echo "[$(date '+%H:%M:%S')] ${pregen_stage} complete (${pregen_target})"
  case "$pregen_stage" in
    chunky) touch "$(chunky_marker "$pregen_target")" ;;
    dh) touch "$(dh_marker "$pregen_target")" ;;
  esac
  pregen_active=false

  # Newly pre-generated chunks are picked up by the next unmined-render pass.
  start_pregen
  if [[ "$pregen_active" != true ]]; then
    echo "[$(date '+%H:%M:%S')] Every visited dimension is pre-generated"
    exit_pregen_mode
    disable_skip_pause
  fi
}

# --- The web map ----------------------------------------------------------------

MAP_INTERVAL="${MAP_INTERVAL:-6h}"
MAP_EXPORT_MAX_MINUTES="${MAP_EXPORT_MAX_MINUTES:-360}"
MAP_STARTED="/data/.map-export-started"
MAP_FINISHED="/data/map-web/.export-finished"
map_export_active=false

# MAP_INTERVAL in seconds: a number with an optional s, m, h or d; 0 is off.
map_interval_seconds() {
  local v="$MAP_INTERVAL" n unit
  n="${v%[smhd]}"
  unit="${v#"$n"}"
  [[ "$n" =~ ^[0-9]+$ ]] || n=21600 unit=s
  case "$unit" in
    m) echo $((n * 60)) ;;
    h) echo $((n * 3600)) ;;
    d) echo $((n * 86400)) ;;
    *) echo "$n" ;;
  esac
}

file_age_seconds() {
  local mtime
  mtime=$(stat -c %Y "$1" 2> /dev/null || stat -f %m "$1" 2> /dev/null || echo 0)
  echo $(($(date +%s) - mtime))
}

map_export_due() {
  local every
  every=$(map_interval_seconds)
  [[ "$every" -gt 0 ]] || return 1
  [[ "$map_export_active" != true && "$pregen_active" != true ]] || return 1
  [[ -f "$MAP_STARTED" ]] || return 0
  [[ $(file_age_seconds "$MAP_STARTED") -ge $every ]]
}

start_map_export() {
  echo "[$(date '+%H:%M:%S')] Exporting the web map"
  touch "$MAP_STARTED"
  touch "$SKIP_PAUSE_MAP"
  touch "$SKIP_PAUSE_FILE"
  if rcon "customdim map export all" > /dev/null 2>&1; then
    map_export_active=true
  else
    finish_map_export "the export command was not accepted"
  fi
}

finish_map_export() {
  echo "[$(date '+%H:%M:%S')] Web map export: $1"
  map_export_active=false
  rm -f "$SKIP_PAUSE_MAP"
  if [[ ! -f "$SKIP_PAUSE_OWN" && ! -f "$SKIP_PAUSE_OTHER" ]]; then
    rm -f "$SKIP_PAUSE_FILE"
  fi
}

check_map_export() {
  [[ "$map_export_active" == true ]] || return 0
  if [[ -f "$MAP_FINISHED" && "$MAP_FINISHED" -nt "$MAP_STARTED" ]]; then
    finish_map_export "finished ($(head -c 200 "$MAP_FINISHED" 2> /dev/null || echo "no summary"))"
  elif [[ $(file_age_seconds "$MAP_STARTED") -ge $((MAP_EXPORT_MAX_MINUTES * 60)) ]]; then
    finish_map_export "still running after ${MAP_EXPORT_MAX_MINUTES} min; autopause allowed again"
  fi
}

# --- Main loop ----------------------------------------------------------------

cleanup() {
  exit_pregen_mode
  disable_skip_pause
  rm -f "$SKIP_PAUSE_MAP"
}
trap cleanup EXIT

echo "Idle task monitor started (grace: ${IDLE_GRACE}min, poll: ${POLL_INTERVAL}s)"
resolve_default_border
echo "  Default generation border from settings.json: ${DEFAULT_BORDER}"
echo "  Chunky radius: each dimension's borders.generation"
echo "  DH LOD pre-generation: ${DH_PREGEN} (to the larger of each dimension's player and generation borders)"
echo "  Dimensions are pre-generated once a player has visited them."
echo "  Web map export: every ${MAP_INTERVAL} once pre-generation is done (0 = off)"

empty_since=""
rcon_failures=0
pregen_dirty=false
deploy_dormant=false

while true; do
  # A deploy owns the server: no RCON, no pre-gen, no maintenance until its
  # sentinel clears. Checked before get_player_count so a paused-or-busy
  # server mid-deploy never accrues rcon_failures or idle timers here.
  if deploy_in_progress; then
    if [[ "$pregen_active" == true ]]; then
      echo "[$(date '+%H:%M:%S')] Deploy in progress - abandoning pre-gen state (no RCON sent)"
      pregen_active=false
      disable_skip_pause
      pregen_dirty=true
    fi
    if [[ "$deploy_dormant" != true ]]; then
      echo "[$(date '+%H:%M:%S')] Deploy in progress - idle-tasks dormant until it completes"
      deploy_dormant=true
    fi
    empty_since=""
    tasks_done=false
    rcon_failures=0
    sleep "$POLL_INTERVAL"
    continue
  fi
  if [[ "$deploy_dormant" == true ]]; then
    echo "[$(date '+%H:%M:%S')] Deploy finished - resuming idle monitoring"
    deploy_dormant=false
  fi

  count=$(get_player_count)

  if [[ "$count" == "-2" ]]; then
    # Server is paused (JVM SIGSTOPped). Do NOT touch RCON — waking it
    # defeats autopause. If pre-gen was active we shouldn't be here
    # (.skip-pause keeps the daemon from pausing), so treat it as an
    # external pause and reset cleanly WITHOUT rcon calls.
    if [[ "$pregen_active" == true ]]; then
      echo "[$(date '+%H:%M:%S')] Server paused externally mid-pre-gen - resetting state"
      pregen_active=false
      disable_skip_pause
      pregen_dirty=true   # gamerules still in pre-gen mode; restore when server responds
    fi
    rcon_failures=0
    empty_since=""
    tasks_done=false
    sleep "$POLL_INTERVAL"
    continue
  fi

  if [[ "$count" == "-1" ]]; then
    # RCON timed out but the JVM is running — the server is BUSY (worldgen,
    # pre-gen, boot), not paused. Tearing down .skip-pause here abandoned a
    # running task in production and started a wake/pause churn loop. Keep
    # state, retry; only give up after sustained failure (e.g. a
    # crashed-but-unpaused JVM).
    rcon_failures=$((rcon_failures + 1))
    if [[ $rcon_failures -ge 10 && "$pregen_active" == true ]]; then
      echo "[$(date '+%H:%M:%S')] RCON unresponsive for $rcon_failures polls - resetting pre-gen state"
      pregen_active=false
      disable_skip_pause
      pregen_dirty=true
      rcon_failures=0
    fi
    empty_since=""
    tasks_done=false
    sleep "$POLL_INTERVAL"
    continue
  fi
  rcon_failures=0

  # Server responding again after a state reset: restore normal gamerules
  # if a pre-gen session was torn down while RCON was unreachable.
  if [[ "${pregen_dirty:-false}" == true && "$pregen_active" != true ]]; then
    exit_pregen_mode
    pregen_dirty=false
  fi

  if [[ "$count" == "0" ]]; then
    if [[ -z "$empty_since" ]]; then
      empty_since=$(date +%s)
      tasks_done=false
      echo "[$(date '+%H:%M:%S')] Server empty - waiting ${IDLE_GRACE}min before maintenance"
    fi

    now=$(date +%s)
    elapsed=$(((now - empty_since) / 60))

    if [[ $elapsed -ge $IDLE_GRACE ]] && [[ "$tasks_done" != true ]]; then
      run_idle_tasks
      start_pregen
      tasks_done=true
    fi

    check_pregen_complete
    if [[ "$tasks_done" == true ]] && map_export_due; then
      start_map_export
    fi
  else
    if [[ -n "$empty_since" ]]; then
      echo "[$(date '+%H:%M:%S')] Player joined - cancelling idle timer"
      pause_pregen
    fi
    empty_since=""
    tasks_done=false
  fi

  check_map_export
  sleep "$POLL_INTERVAL"
done
