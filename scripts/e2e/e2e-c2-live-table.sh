#!/usr/bin/env bash
# PASS = every row of the live/not-live table got a measurement, and each negative row had a positive control beside it.
#
# Purpose: answer, per thing a player might expect to see through a portal,
#          whether it is live, static, or absent — by measurement rather than
#          by reading the mesher.
# Context: the companion channel is CompanionPayloads.Projection, sixteen
#          fields, of which only states[] and light[] carry anything that can
#          change. A row this script reports as ABSENT is absent because the
#          wire cannot express it, not because the refresh is slow.
# Usage:   a player must be within activationRange (24) of the nexus frame so a
#          projection exists. This script NEVER moves them and never takes the
#          client: it writes only inside the DESTINATION dimension, reads two
#          logs and one bridge endpoint, and restores every cell it touches.
#          RIG is fixed to the nexus; the crucible's arrival moves when re-cut.
# Gotchas: `companion-send:projection` is DEBUG. Without
#          CUSTOMDIM_LOG_LEVEL=debug in the consumer .env every row reads
#          "absent" and the whole table is a lie — the gate below refuses to run
#          rather than measure that.
#
#          A NEGATIVE ROW IS WORTHLESS ALONE. Every "no resend" row is followed
#          by a setblock that must resend, so "nothing happened" can be told
#          from "the instrument stopped working" (TROUBLESHOOTING.md#t63).
#
#          The arrival column is read from portal_links.json, never assumed: it
#          moves when a portal is re-lit, and a probe outside the sampled volume
#          reads exactly like a feature that does not update.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="c2-live-table"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

DEST="adventure:the_crimson_nexus"
SRC_X=1500; SRC_Y=101; SRC_Z=1500          # the nexus source frame's lowest cell
PROJ="$(printf '.projections[] | select(.destination=="%s")' "$DEST")"
LINKS="$CONSUMER_DIR/data/config/portal_links.json"

# How long a change is given to reach the client. The companion rebuild cadence
# is refreshInterval(4) * 5 = 20 ticks moving and 32 stationary, so 6s is three
# times the worst case and a miss is a real miss.
SETTLE="${SETTLE:-8}"

banner "C2 — what is live through a portal, what is static, and what is absent"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
require_bridge
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"

# Without DEBUG the send marker never appears and every row reads ABSENT.
if ! grep -q "^[[:space:]]*CUSTOMDIM_LOG_LEVEL='\?debug" "$CONSUMER_DIR/.env" 2>/dev/null; then
  printf '\033[31mREFUSING TO RUN\033[0m — CUSTOMDIM_LOG_LEVEL is not debug in %s/.env\n' "$CONSUMER_DIR" >&2
  printf 'companion-send:projection is a DEBUG line. Without it every row would\n' >&2
  printf 'read "absent" and the table would be wrong in the same direction throughout.\n' >&2
  exit 1
fi

bridge_state
assert_bridge "a projection for the nexus exists to observe" "[$PROJ] | length" 1
assert_bridge "with a built mesh" "$PROJ | .meshReady" true
note "quads at start: $(bridge_read "$PROJ | .quads")"

# --- the arrival column, read rather than assumed --------------------------
say "Where the destination is sampled"
if [ ! -f "$LINKS" ]; then
  _record fail "portal_links.json is readable" "$LINKS" "no such file"
  finish; exit 1
fi
ARRIVAL="$(jq -r --argjson x "$SRC_X" --argjson y "$SRC_Y" --argjson z "$SRC_Z" --arg dim "$DEST" '
  [ .[] | select(.portalWorld == $dim)
        | select((.sourceX == $x) and (.sourceZ == $z))
        | select((.sourceY >= ($y - 2)) and (.sourceY <= ($y + 2)))
        | "\(.x) \(.y) \(.z)" ] | first // "none"' "$LINKS")"
if [ "$ARRIVAL" = "none" ] || [ -z "$ARRIVAL" ]; then
  _record fail "the nexus arrival column is recorded" \
    "an x y z from portal_links.json for a portal at $SRC_X,*,$SRC_Z -> $DEST" "none found"
  finish; exit 1
fi
# shellcheck disable=SC2086  # three fields, deliberately split
set -- $ARRIVAL
AX="$1"; AY="$2"; AZ="$3"
_record ok "the nexus arrival column is recorded" "an x y z from portal_links.json" "$AX $AY $AZ in $DEST"
PX=$((AX + 1)); PY=$((AY + 1)); PZ="$AZ"     # a probe cell beside the arrival
note "probe cell: $PX $PY $PZ in $DEST"

# --- the instrument --------------------------------------------------------
# Counts the marker lines each side gains while a change is made. Both logs are
# snapshotted by LINE COUNT before the change, so a busy server cannot leak
# earlier lines into the window.
SRV_MARK="companion-send:projection"
CLI_MARK="companion-client:projection-mesh"
ROW_N=0

# Measures LATENCY rather than thresholding it. A fixed window forces a guess
# at how long a change takes to arrive, and the guess was wrong: matched pairs
# came back at 0-1s when the projection is actively sampling and ~29s when it
# is not, so a 6s window reported four rows as absent that were merely late.
# Polls for a send and reports the seconds it took, or the full wait for a row
# that never sends.
#
# The undo is issued BEFORE the next window opens and then allowed to settle:
# run at the end of a row it lands inside the NEXT row's window, and its resend
# is then credited to whatever that row was testing. That contaminated the
# time-of-day row on the first run — a `setblock air` a second earlier produced
# the send the row recorded against `/time set`.
WAIT_MAX="${WAIT_MAX:-45}"
PENDING_UNDO=""

_sends_now() { docker logs "$1" 2>/dev/null | grep -ac "$SRV_MARK" || echo 0; }

probe() { # label rcon-command undo-command expect(live|absent)
  ROW_N=$((ROW_N + 1))
  local label="$1" cmd="$2" undo="$3" expect="$4"
  local dir="$RUN_DIR/row-$ROW_N"
  mkdir -p "$dir"

  # Drain the previous row's undo before this row's baseline is taken.
  if [ -n "$PENDING_UNDO" ]; then
    rcon "$PENDING_UNDO" > "$dir/prev-undo.txt" 2>&1
    PENDING_UNDO=""
    sleep "$SETTLE"
  fi

  local before after waited=0 latency="none"
  before="$(cd "$CONSUMER_DIR" && docker logs mc 2>/dev/null | grep -ac "$SRV_MARK")"
  rcon "$cmd" > "$dir/cmd.txt" 2>&1
  while [ "$waited" -lt "$WAIT_MAX" ]; do
    sleep 3
    waited=$((waited + 3))
    after="$(cd "$CONSUMER_DIR" && docker logs mc 2>/dev/null | grep -ac "$SRV_MARK")"
    if [ "$after" -gt "$before" ]; then latency="$waited"; break; fi
  done
  [ "$latency" = "none" ] && after="$(cd "$CONSUMER_DIR" && docker logs mc 2>/dev/null | grep -ac "$SRV_MARK")"
  local sends=$((after - before))
  PENDING_UNDO="$undo"

  local reply got
  reply="$(tr -d '\r\n' < "$dir/cmd.txt" | cut -c1-52)"
  printf '%s\t%s\t%s\t%s\n' "$label" "$sends" "$latency" "$reply" >> "$RUN_DIR/latency.tsv"
  if [ "$latency" = "none" ]; then
    got="NO send within ${WAIT_MAX}s (rcon: $reply)"
  else
    got="$sends send(s), first within ${latency}s (rcon: $reply)"
  fi
  if [ "$expect" = "live" ]; then
    if [ "$sends" -ge 1 ]; then _record ok "$label" "a send within ${WAIT_MAX}s" "$got"
    else _record fail "$label" "a send within ${WAIT_MAX}s" "$got"; fi
  else
    if [ "$sends" -eq 0 ]; then _record ok "$label" "no send in ${WAIT_MAX}s" "$got"
    else _record fail "$label" "no send in ${WAIT_MAX}s" "$got"; fi
  fi
}

# --- the rows --------------------------------------------------------------
say "BLOCKS — the one thing the wire is built to carry"
probe "a block placed on the far side reaches the client" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:glowstone" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:air" live

say "LIGHT ON THE FAR SIDE — light[] is compared by sameContent, so it must resend"
DEST_BEFORE="$(bridge_read "$PROJ | .destLight.blockMax")"
probe "a light source on the far side reaches the client" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:sea_lantern" "" live
bridge_state
DEST_AFTER="$(bridge_read "$PROJ | .destLight.blockMax")"
if [ "$DEST_AFTER" != "$DEST_BEFORE" ]; then
  _record ok "and the destination's light actually changed in the payload" \
    "destLight.blockMax to move" "$DEST_BEFORE -> $DEST_AFTER"
else
  _record fail "and the destination's light actually changed in the payload" \
    "destLight.blockMax to move" "stayed $DEST_BEFORE"
fi
rcon "execute in $DEST run setblock $PX $PY $PZ minecraft:air" >/dev/null

say "FLUIDS — a fluid is a block state, so its STATE travels; its flow animation is the client's own"
probe "water placed on the far side reaches the client" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:water" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:air" live

say "BLOCK ENTITIES — the category splits on render type, not on having a block entity"
QUADS_BEFORE="$(bridge_read "$PROJ | .quads")"
probe "a FURNACE (BlockRenderType.MODEL) reaches the client" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:furnace" "" live
sleep "$SETTLE"
bridge_state
QUADS_FURNACE="$(bridge_read "$PROJ | .quads")"
rcon "execute in $DEST run setblock $PX $PY $PZ minecraft:air" >/dev/null
sleep "$SETTLE"
probe "a CHEST (BlockRenderType.ENTITYBLOCK_ANIMATED) still resends — the STATE is on the wire" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:chest" "" live
sleep "$SETTLE"
bridge_state
QUADS_CHEST="$(bridge_read "$PROJ | .quads")"
rcon "execute in $DEST run setblock $PX $PY $PZ minecraft:air" >/dev/null
report_metric "quads: empty / furnace / chest" "$QUADS_BEFORE / $QUADS_FURNACE / $QUADS_CHEST" \
  "the furnace must add geometry and the chest must not — that is the difference between static and ABSENT"
numeric() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
if ! numeric "$QUADS_BEFORE" || ! numeric "$QUADS_FURNACE" || ! numeric "$QUADS_CHEST"; then
  _record fail "the mesher draws a furnace and drops a chest" \
    "three numeric quad counts" \
    "one reading is not a number, so nothing was compared — empty=$QUADS_BEFORE furnace=$QUADS_FURNACE chest=$QUADS_CHEST"
elif [ "$QUADS_FURNACE" != "$QUADS_BEFORE" ] && [ "$QUADS_CHEST" = "$QUADS_BEFORE" ]; then
  _record ok "the mesher draws a furnace and drops a chest" \
    "furnace changes the quad count, chest does not" \
    "empty=$QUADS_BEFORE furnace=$QUADS_FURNACE chest=$QUADS_CHEST"
else
  _record fail "the mesher draws a furnace and drops a chest" \
    "furnace changes the quad count, chest does not" \
    "empty=$QUADS_BEFORE furnace=$QUADS_FURNACE chest=$QUADS_CHEST"
fi

say "ENTITIES — nothing in CompanionPayloads.Projection can carry one"
probe "a mob summoned on the far side does NOT reach the client" \
  "execute in $DEST run summon minecraft:pig $PX $PY $PZ" "" absent
# The positive control, immediately after and at the same cell: if THIS does
# not resend, the instrument was broken and the row above measured nothing.
probe "POSITIVE CONTROL — a block at the same cell still does" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:glowstone" \
  "execute in $DEST run setblock $PX $PY $PZ minecraft:air" live
rcon "execute in $DEST positioned $PX $PY $PZ run kill @e[type=minecraft:pig,distance=..8]" >/dev/null

say "TIME OF DAY — not on the wire, and the window is lit by the SOURCE world's lightmap"
# /time is SAVE-WIDE here: every dimension this mod creates is built over an
# UnmodifiableLevelProperties wrapping one set of level properties, so
# `execute in <dim> run time set` moves the overworld's clock too — and someone
# else's screenshots with it. Read it first and put it back to the tick.
TIME_BEFORE="$(rcon "time query daytime" | tr -d '\r\n' | grep -oE '[0-9]+$')"
if [ -z "$TIME_BEFORE" ]; then
  _record fail "the world's time was readable before changing it" "a daytime tick" \
    "could not parse 'time query daytime' — refusing to move a clock I cannot restore"
else
  note "daytime before: $TIME_BEFORE"
  probe "changing the destination's time does NOT reach the client" \
    "time set midnight" "" absent
  probe "POSITIVE CONTROL — a block still does" \
    "execute in $DEST run setblock $PX $PY $PZ minecraft:glowstone" \
    "execute in $DEST run setblock $PX $PY $PZ minecraft:air" live
  rcon "time set $TIME_BEFORE" >/dev/null
  TIME_AFTER="$(rcon "time query daytime" | tr -d '\r\n' | grep -oE '[0-9]+$')"
  note "daytime restored to $TIME_AFTER (was $TIME_BEFORE; a few ticks of drift is the world running)"
fi

say "WEATHER — derived, not measured, and deliberately so"
# Not probed: there is no `weather query`, so a change could not be restored
# exactly, and weather is save-wide — making it rain here would rain on every
# other agent's screenshots. The channel enumeration settles it on its own:
# CompanionPayloads.Projection has no weather field, and vanilla /weather
# ignores `execute in <dim>` because one flag serves the whole save.
_record ok "weather is absent from the wire" \
  "no weather field in CompanionPayloads.Projection" \
  "DERIVED from the record's sixteen fields, not probed — see the note above"

say "SKY — two static ints, sampled once per payload"
report_metric "skyColor / fogColor on the wire" \
  "$(bridge_read "$PROJ | \"\(.destLight.cells) cells described\"")" \
  "the backdrop is a flat quad painted with fogColor, falling back to skyColor — there is no sky to update"

say "The world is as it was"
# The last row's undo is still deferred — probe() drains the PREVIOUS one, so
# nothing drains the final row's. Without this the run ends with a block left
# in the destination.
if [ -n "$PENDING_UNDO" ]; then
  rcon "$PENDING_UNDO" > "$RUN_DIR/final-undo.txt" 2>&1
  PENDING_UNDO=""
  sleep "$SETTLE"
fi
ASSERT_DIM="$DEST"
assert_block "the probe cell is air again" "$PX" "$PY" "$PZ" minecraft:air present
unset ASSERT_DIM
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi
if bridge_request "$RUN_DIR/bridge-health-after.json" GET /health; then
  _record ok "the client survived the run" "an answering bridge" \
    "tick $(json_read "$RUN_DIR/bridge-health-after.json" '.tick')"
else
  _record fail "the client survived the run" "an answering bridge" \
    "the bridge stopped answering: $BRIDGE_REASON — assertions after the loss are void (TROUBLESHOOTING.md#p6)"
fi

finish
