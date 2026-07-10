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
# Pause detection: the JVM's process state (T = SIGSTOPped) is checked via
# `docker exec mc ps` BEFORE any RCON call — the autopause daemon resumes
# the JVM whenever an rcon-cli process exists in the mc container, so an
# RCON poll against a paused server wakes it (4-minute wake/pause churn in
# production, 2026-07-10). RCON timing out while the JVM runs means BUSY,
# not paused — state is kept and the poll retried.
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
# Autopause suspension uses owner files reconciled into the aggregate the
# itzg daemon actually checks (/data/.skip-pause). idle-tasks owns
# .skip-pause-idle; deploy.sh owns .skip-pause-deploying. The aggregate
# exists iff ANY owner file exists, so each owner can clean up its own
# suspension without clobbering the other's.
SKIP_PAUSE_FILE="/data/.skip-pause"
SKIP_PAUSE_OWN="/data/.skip-pause-idle"
SKIP_PAUSE_OTHER="/data/.skip-pause-deploying"
chunky_active=false
chunky_dimension="overworld"
tasks_done=false

rcon() {
  docker exec mc rcon-cli "$@" 2> /dev/null || true
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
    echo "  Created .skip-pause-idle (autopause bypassed while Chunky runs)"
  fi
}

disable_skip_pause() {
  if [[ -f "$SKIP_PAUSE_OWN" ]]; then
    rm -f "$SKIP_PAUSE_OWN"
    if [[ ! -f "$SKIP_PAUSE_OTHER" ]]; then
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
  if echo "$status" | grep -qiE "Task finished|complete|100%"; then
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
rcon_failures=0
pregen_dirty=false

while true; do
  count=$(get_player_count)

  if [[ "$count" == "-2" ]]; then
    # Server is paused (JVM SIGSTOPped). Do NOT touch RCON — waking it
    # defeats autopause. If Chunky was active we shouldn't be here
    # (.skip-pause keeps the daemon from pausing), so treat it as an
    # external pause and reset cleanly WITHOUT rcon calls.
    if [[ "$chunky_active" == true ]]; then
      echo "[$(date '+%H:%M:%S')] Server paused externally mid-pre-gen - resetting state"
      chunky_active=false
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
    # Chunky, boot), not paused. Tearing down .skip-pause here abandoned a
    # running Chunky task in production and started a wake/pause churn
    # loop. Keep state, retry; only give up after sustained failure
    # (e.g. a crashed-but-unpaused JVM).
    rcon_failures=$((rcon_failures + 1))
    if [[ $rcon_failures -ge 10 && "$chunky_active" == true ]]; then
      echo "[$(date '+%H:%M:%S')] RCON unresponsive for $rcon_failures polls - resetting pre-gen state"
      chunky_active=false
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
  if [[ "${pregen_dirty:-false}" == true && "$chunky_active" != true ]]; then
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
      start_chunky
      tasks_done=true
    fi

    check_chunky_complete
  else
    if [[ -n "$empty_since" ]]; then
      echo "[$(date '+%H:%M:%S')] Player joined - cancelling idle timer"
      pause_chunky
    fi
    empty_since=""
    tasks_done=false
  fi

  sleep "$POLL_INTERVAL"
done
