#!/usr/bin/env bash
# idle-tasks.sh - Run maintenance when no players are connected.
#
# Runs bind-mounted in the idle-tasks container (cloud profile). Polls RCON
# for the player count; when the server stays empty for IDLE_GRACE minutes:
# save-all flush, BlueMap render, spark gc, then Chunky pre-generation -
# one dimension at a time (overworld -> nether -> end -> paradise_lost), to
# the world border + margin so Distant Horizons has LOD data ready.
#
# Completion is tracked by marker files that persist across restarts:
#   data/.chunky-complete, .chunky-nether-complete, .chunky-end-complete,
#   .chunky-paradise-lost-complete
# Delete a marker to force that dimension to re-generate (e.g. after a
# border change). Pre-gen pauses when a player joins, resumes next idle.
#
# While Chunky runs, the script creates /data/.skip-pause — the itzg
# image's built-in autopause bypass. The autopause daemon checks for this
# file and stays in the "established" state (won't freeze the JVM) as
# long as it exists. Removed when Chunky pauses or all dimensions finish.
# RCON returning nothing = server paused; treated as "not idle" rather
# than an error.
set -euo pipefail

RCON_HOST="${RCON_HOST:-mc}"
RCON_PASSWORD="${RCON_PASSWORD:-}"
IDLE_GRACE="${IDLE_GRACE:-1}"
POLL_INTERVAL="${POLL_INTERVAL:-30}"

# Chunky pre-generation: border radius + 128 chunks (2048 blocks) for DH LOD
PREGEN_BORDER_RADIUS="${PREGEN_BORDER_RADIUS:-${WORLD_BORDER_RADIUS:-8192}}"
CHUNKY_OVERWORLD_RADIUS=$((PREGEN_BORDER_RADIUS + 2048))
CHUNKY_NETHER_RADIUS=$((PREGEN_BORDER_RADIUS / 8 + 256))
CHUNKY_END_RADIUS=$((4096 + 512))
CHUNKY_PL_RADIUS=$((4096 + 512))
CHUNKY_MARKER="/data/.chunky-complete"
CHUNKY_NETHER_MARKER="/data/.chunky-nether-complete"
CHUNKY_END_MARKER="/data/.chunky-end-complete"
CHUNKY_PL_MARKER="/data/.chunky-paradise-lost-complete"
SKIP_PAUSE_FILE="/data/.skip-pause"
C2ME_PARALLEL_MARKER="/data/.c2me-parallel"
C2ME_PLAYER_THRESHOLD="${C2ME_PLAYER_THRESHOLD:-10}"
chunky_active=false
chunky_dimension="overworld"
tasks_done=false

rcon() {
  docker exec mc rcon-cli "$@" 2> /dev/null || true
}

get_player_count() {
  local result
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

enable_c2me_parallel() {
  if [[ ! -f "$C2ME_PARALLEL_MARKER" ]]; then
    touch "$C2ME_PARALLEL_MARKER"
    echo "  Created .c2me-parallel (deploy.sh will enable full parallelism on next restart)"
  fi
}

enable_skip_pause() {
  if [[ ! -f "$SKIP_PAUSE_FILE" ]]; then
    touch "$SKIP_PAUSE_FILE"
    echo "  Created .skip-pause (autopause bypassed while Chunky runs)"
  fi
}

disable_skip_pause() {
  if [[ -f "$SKIP_PAUSE_FILE" ]]; then
    rm -f "$SKIP_PAUSE_FILE"
    echo "  Removed .skip-pause (autopause re-enabled)"
  fi
}

run_idle_tasks() {
  echo "[$(date '+%H:%M:%S')] Server empty for ${IDLE_GRACE}min - running idle maintenance"

  echo "  Saving world..."
  rcon "save-all flush"
  sleep 5

  echo "  Triggering BlueMap render..."
  rcon "bluemap update"

  echo "  Requesting garbage collection..."
  rcon "spark gc" || true

  echo "  Idle maintenance complete."
}

# --- Chunky pre-generation ----------------------------------------------------

start_chunky() {
  # Find the next dimension that needs pre-generation
  local world="" radius=""
  if [[ ! -f "$CHUNKY_MARKER" ]]; then
    world="minecraft:overworld"
    radius="$CHUNKY_OVERWORLD_RADIUS"
    chunky_dimension="overworld"
  elif [[ ! -f "$CHUNKY_NETHER_MARKER" ]]; then
    world="minecraft:the_nether"
    radius="$CHUNKY_NETHER_RADIUS"
    chunky_dimension="nether"
  elif [[ ! -f "$CHUNKY_END_MARKER" ]]; then
    world="minecraft:the_end"
    radius="$CHUNKY_END_RADIUS"
    chunky_dimension="end"
  elif [[ ! -f "$CHUNKY_PL_MARKER" ]]; then
    world="paradise_lost:paradise_lost"
    radius="$CHUNKY_PL_RADIUS"
    chunky_dimension="paradise_lost"
  else
    # Explicit 0 matters: a bare `return` here inherits the exit status of the
    # failed `[[ ! -f ... ]]` test above (1), which under `set -e` kills the
    # whole script - the container then restarts in an endless loop once all
    # dimensions finish pre-generating.
    return 0
  fi

  echo "[$(date '+%H:%M:%S')] Starting Chunky pre-generation: ${chunky_dimension} (radius: ${radius})"

  # Bypass autopause BEFORE the first RCON call that wakes the server,
  # so the daemon sees the file on its next poll and stays in state E.
  enable_skip_pause

  # Try to resume a paused task first
  local resume_result
  resume_result=$(rcon "chunky continue" 2> /dev/null || echo "")
  enter_pregen_mode

  if echo "$resume_result" | grep -qi "continuing\|resumed"; then
    echo "  Resumed paused task"
    chunky_active=true
    return
  fi

  # No paused task - start fresh
  echo "  No paused task found, starting fresh"
  rcon "chunky cancel"
  rcon "chunky confirm"
  sleep 1
  rcon "chunky world $world"
  rcon "chunky center 0 0"
  rcon "chunky radius $radius"
  rcon "chunky start"
  chunky_active=true
}

pause_chunky() {
  if [[ "$chunky_active" == true ]]; then
    echo "[$(date '+%H:%M:%S')] Pausing Chunky pre-generation (${chunky_dimension})"
    rcon "chunky pause"
    exit_pregen_mode
    chunky_active=false
    disable_skip_pause
  fi
}

check_chunky_complete() {
  if [[ "$chunky_active" != true ]]; then
    return 0
  fi

  local status
  status=$(rcon "chunky progress" 2> /dev/null || echo "")
  if echo "$status" | grep -qiE "Task finished|complete|100%|No tasks running"; then
    echo "[$(date '+%H:%M:%S')] Chunky pre-generation complete (${chunky_dimension})"
    chunky_active=false

    # Mark current dimension done
    case "$chunky_dimension" in
      overworld) touch "$CHUNKY_MARKER" ;;
      nether) touch "$CHUNKY_NETHER_MARKER" ;;
      end) touch "$CHUNKY_END_MARKER" ;;
      paradise_lost) touch "$CHUNKY_PL_MARKER" ;;
    esac

    echo "  Triggering BlueMap update for newly generated chunks..."
    rcon "bluemap update"

    # Start the next dimension if any remain; if none left,
    # start_chunky returns without setting chunky_active, so
    # we re-enable autopause.
    start_chunky
    if [[ "$chunky_active" != true ]]; then
      echo "[$(date '+%H:%M:%S')] All dimensions pre-generated"
      enable_c2me_parallel
      exit_pregen_mode
      disable_skip_pause
    fi
  fi
}

# --- Main loop ----------------------------------------------------------------

# Completion markers persist across restarts. To force Chunky to re-run
# (e.g. after a world border change), delete the markers manually:
#   rm -f data/.chunky-complete data/.chunky-nether-complete data/.chunky-end-complete data/.chunky-paradise-lost-complete

cleanup() {
  exit_pregen_mode
  disable_skip_pause
}
trap cleanup EXIT

echo "Idle task monitor started (grace: ${IDLE_GRACE}min, poll: ${POLL_INTERVAL}s)"
echo "  Chunky will pre-generate when idle:"
echo "    Overworld: ${CHUNKY_OVERWORLD_RADIUS} block radius"
echo "    Nether:    ${CHUNKY_NETHER_RADIUS} block radius"
echo "    End:       ${CHUNKY_END_RADIUS} block radius"
echo "    Paradise:  ${CHUNKY_PL_RADIUS} block radius"

empty_since=""

while true; do
  count=$(get_player_count)

  if [[ "$count" == "-1" ]]; then
    # Server not responding - might be paused or starting.
    # If Chunky was active, the server likely paused or crashed;
    # clean up .skip-pause so autopause works normally on recovery.
    if [[ "$chunky_active" == true ]]; then
      chunky_active=false
      exit_pregen_mode
      disable_skip_pause
    fi
    empty_since=""
    tasks_done=false
    sleep "$POLL_INTERVAL"
    continue
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
      start_chunky
      tasks_done=true
    fi

    check_chunky_complete
  else
    if [[ -n "$empty_since" ]]; then
      echo "[$(date '+%H:%M:%S')] Player joined - cancelling idle timer"
      pause_chunky
    fi
    if [[ "$count" -ge "$C2ME_PLAYER_THRESHOLD" ]]; then
      enable_c2me_parallel
    fi
    empty_since=""
    tasks_done=false
  fi

  sleep "$POLL_INTERVAL"
done
