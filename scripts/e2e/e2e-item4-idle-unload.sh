#!/usr/bin/env bash
# RETEST item 4 — idle unload.
#
# Purpose: prove the_crucible unloads after the last use, and does NOT unload
#          while the player is still near its portal.
# Context: run item 5 FIRST. A world absent from a cold start proves nothing —
#          the instrument has to be seen reading it present.
# Usage:   ./e2e-item4-idle-unload.sh [wait-seconds]   (default 330)
#          The only one of these that needs no client: residency is a server
#          fact, so there is nothing here for a screenshot to prove.
# Gotchas: both halves are needed. An earlier build held the dimension open
#          forever, and only a walk-away caught it; a build that unloads too
#          eagerly is caught only by the stay-near half.
#          Residency is read from e2e-state's `dimensions`, which lists every
#          loaded world by id — not from a sentence about how many there are.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item4"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

WAIT_SECS="${1:-330}"
DESTINATION="adventure:the_crucible"
RESIDENT_FILTER="[.dimensions[] | select(.id==\"$DESTINATION\")] | length"
NEAR_X=229.0; NEAR_Y=130; NEAR_Z=310      # on the lane floor, ~13 blocks from the frame
FAR_X=-64;    FAR_Y=179;  FAR_Z=640       # world spawn, ~450 blocks away

banner "RETEST item 4 — idle unload (wait ${WAIT_SECS}s per half)"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online

say "Negative control FIRST — the instrument must read 1 before a 0 means anything"
state_refresh
RESIDENT="$(state_read "$RESIDENT_FILTER")"
if [ "$RESIDENT" != "1" ]; then
  printf '\033[31mPRECONDITION NOT MET\033[0m — %s is not resident.\n' "$DESTINATION" >&2
  printf 'e2e-state lists these loaded worlds: %s\n' "$(state_read '.dimensions[] | .id')" >&2
  printf 'Run item 5 first and cross into the crucible. A 0 here measures nothing.\n' >&2
  exit 1
fi
_record ok "the crucible is resident before the test starts" "1 loaded world with that id" "$RESIDENT"
report_metric "managed worlds loaded at the start" "$(state_read '[.dimensions[] | select(.managed)] | length')" \
  "every world this mod owns that the server currently holds open"

wait_with_progress() { # seconds label
  local left="$1" chunk=30
  while [ "$left" -gt 0 ]; do
    [ "$left" -lt "$chunk" ] && chunk="$left"
    sleep "$chunk"
    left=$((left - chunk))
    printf '      %s: %ds remaining\n' "$2" "$left"
  done
}

say "Half one — stay NEAR the portal; it must NOT unload"
rcon "execute in minecraft:overworld run tp $PLAYER $NEAR_X $NEAR_Y $NEAR_Z 0 0" >/dev/null
note "player parked $NEAR_X $NEAR_Y $NEAR_Z, about 13 blocks from the frame"
wait_with_progress "$WAIT_SECS" "near-portal hold"
state_refresh
assert_state "NEGATIVE: the crucible stays resident while the player is near" "$RESIDENT_FILTER" 1

say "Half two — walk away; it must unload"
rcon "execute in minecraft:overworld run tp $PLAYER $FAR_X $FAR_Y $FAR_Z 0 0" >/dev/null
note "player moved to world spawn, ~450 blocks from every portal"
wait_with_progress "$WAIT_SECS" "walk-away hold"
# A single reading at a fixed time measures the clock, not the feature: the idle
# window and this wait are close enough that the same build passes one run and
# fails the next. Poll until it unloads, with a bound, and report how long it
# actually took — that number is worth more than the pass.
UNLOADED=0
POLLED=0
UNREADABLE=""
while [ "$POLLED" -lt 300 ]; do
  # One failure for one root cause: a server that stops answering ends the
  # poll rather than recording a failed assertion every fifteen seconds.
  if ! state_refresh; then UNREADABLE="$STATE_REASON"; break; fi
  if [ "$(state_read "$RESIDENT_FILTER")" = "0" ]; then UNLOADED=1; break; fi
  sleep 15
  POLLED=$((POLLED + 15))
done
# Another dimension may legitimately be held open by an unrelated test; what
# item 4 measures is that THE CRUCIBLE idled out, not that the server is empty.
if [ -n "$UNREADABLE" ]; then
  _record fail "the dimension idled out after the last use" "0 loaded worlds with that id" \
    "the poll stopped after ${POLLED}s: $UNREADABLE"
elif [ "$UNLOADED" -eq 1 ]; then
  note "the_crucible unloaded ${POLLED}s after the walk-away wait (bound 300s)"
  _record ok "the dimension idled out after the last use" "0 loaded worlds with that id" \
    "gone after $((WAIT_SECS + POLLED))s"
else
  _record fail "the dimension idled out after the last use" "0 loaded worlds with that id" \
    "still loaded after $((WAIT_SECS + POLLED))s"
fi

finish
