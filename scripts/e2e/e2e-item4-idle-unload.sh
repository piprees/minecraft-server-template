#!/usr/bin/env bash
# RETEST item 4 — idle unload.
#
# Purpose: prove the_crucible unloads after the last use, and does NOT unload
#          while the player is still near its portal.
# Context: run item 5 FIRST. `customdim list` answering 0 from a cold start
#          proves nothing — the instrument has to be seen reading 1.
# Usage:   ./e2e-item4-idle-unload.sh [wait-seconds]   (default 330)
# Gotchas: both halves are needed. An earlier build held the dimension open
#          forever, and only a walk-away caught it; a build that unloads too
#          eagerly is caught only by the stay-near half.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item4"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

WAIT_SECS="${1:-330}"
NEAR_X=229.0; NEAR_Y=129; NEAR_Z=310      # ~13 blocks from the frame
FAR_X=-64;    FAR_Y=179;  FAR_Z=640       # world spawn, ~450 blocks away

banner "RETEST item 4 — idle unload (wait ${WAIT_SECS}s per half)"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online

say "Negative control FIRST — the instrument must read 1 before a 0 means anything"
LIST="$(rcon 'customdim list' | tr -d '\r\n')"
note "customdim list: $LIST"
case "$LIST" in
  *"0 custom dimension(s) loaded"*)
    printf '\033[31mPRECONDITION NOT MET\033[0m — no custom dimension is resident.\n' >&2
    printf 'customdim list answered: %s\n' "$LIST" >&2
    printf 'Run item 5 first and cross into the crucible. A 0 here measures nothing.\n' >&2
    exit 1 ;;
esac
_record ok "a custom dimension is resident before the test starts" \
  "not '0 custom dimension(s) loaded'" "$LIST"
shot "00-resident"

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
assert_not_contains "NEGATIVE: dimension stays resident while the player is near" \
  "customdim list" "0 custom dimension(s) loaded"
shot "01-near-hold"

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
while [ "$POLLED" -lt 300 ]; do
  case "$(rcon "customdim list")" in *the_crucible*) : ;; *) UNLOADED=1; break ;; esac
  sleep 15
  POLLED=$((POLLED + 15))
done
note "the_crucible unloaded $POLLED s after the walk-away wait (bound 300s)"
# Another dimension may legitimately be held open by an unrelated test; what
# item 4 measures is that THE CRUCIBLE idled out, not that the server is empty.
if [ "$UNLOADED" -eq 1 ]; then
  _record ok "the dimension idled out after the last use" "the_crucible absent from customdim list" "gone after $((WAIT_SECS + POLLED))s"
else
  _record fail "the dimension idled out after the last use" "the_crucible absent from customdim list" "still loaded after $((WAIT_SECS + POLLED))s"
fi
shot "02-unloaded"

finish
