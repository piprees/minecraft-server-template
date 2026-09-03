#!/usr/bin/env bash
# RETEST item 2 — the stronghold and the twelve-eye ritual.
#
# Purpose: prove the gateway fix (both halves) then run the twelve-eye ritual.
# Context: drives Pip's real client. Aim is the tp; the click is only a trigger.
# Usage:   ./e2e-item2-stronghold.sh
# Gotchas: `give` puts an item in the inventory, not the hand — every ignition
#          here uses `item replace ... weapon.mainhand` or the click does
#          nothing. Re-runnable: it counts sockets already filled first.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item2"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

banner "RETEST item 2 — stronghold, twelve-eye ritual, framed gateways"

# The ring this script BUILDS, in clear ground. It used to name the world's own
# stronghold, and that made the run one-shot: an eye cannot be taken back out of
# a socket, so once twelve were in, "still dark after eleven" could never be
# measured again. A rig can be rebuilt from air every time.
RIG_X=250; RIG_Y=131; RIG_Z=310
PORTAL_X=$RIG_X; PORTAL_Y=$RIG_Y; PORTAL_Z=$RIG_Z
FRAMES="$((RIG_X - 1)) $((RIG_Z - 2))
$((RIG_X)) $((RIG_Z - 2))
$((RIG_X + 1)) $((RIG_Z - 2))
$((RIG_X - 1)) $((RIG_Z + 2))
$((RIG_X)) $((RIG_Z + 2))
$((RIG_X + 1)) $((RIG_Z + 2))
$((RIG_X - 2)) $((RIG_Z - 1))
$((RIG_X - 2)) $((RIG_Z))
$((RIG_X - 2)) $((RIG_Z + 1))
$((RIG_X + 2)) $((RIG_Z - 1))
$((RIG_X + 2)) $((RIG_Z))
$((RIG_X + 2)) $((RIG_Z + 1))"

# Framed-gateway test rig, in already-verified clear ground.
GW_IX=234; GW_IY=131; GW_IZ=297          # enclosed cell
BARE_X=240; BARE_Y=130; BARE_Z=297       # lone block, no ring

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online

say "Build the rig"
# Chunks load from a PLAYER, so the player goes first or every setblock answers
# "position not loaded" and does nothing.
# A player is not a reliable chunk ticket here: teleported in before the floor
# exists, they fall, take the ticket with them, and every fill after the
# readiness probe answers "position not loaded" while the probe said ready.
# forceload holds the region regardless of where anybody is standing.
rcon "execute in minecraft:overworld run forceload add $((RIG_X - 8)) $((RIG_Z - 8)) $((RIG_X + 8)) $((RIG_Z + 8))" >/dev/null
rcon "execute in minecraft:overworld run tp $PLAYER $RIG_X.5 $((RIG_Y + 1)) $RIG_Z.5" >/dev/null
wait_for_chunk "$RIG_X" "$((RIG_Y + 1))" "$RIG_Z" minecraft:overworld 60
rcon "execute in minecraft:overworld run fill $((RIG_X - 4)) $((RIG_Y - 1)) $((RIG_Z - 4)) $((RIG_X + 4)) $((RIG_Y + 3)) $((RIG_Z + 4)) minecraft:air" >/dev/null
rcon "execute in minecraft:overworld run fill $((RIG_X - 4)) $((RIG_Y - 1)) $((RIG_Z - 4)) $((RIG_X + 4)) $((RIG_Y - 1)) $((RIG_Z + 4)) minecraft:stone" >/dev/null
# Each frame faces the centre, which is what vanilla's completed-frame pattern
# requires; a ring of correctly placed but wrongly facing frames never lights.
for x in $((RIG_X - 1)) $RIG_X $((RIG_X + 1)); do
  rcon "execute in minecraft:overworld run setblock $x $RIG_Y $((RIG_Z - 2)) minecraft:end_portal_frame[facing=south,eye=false]" >/dev/null
  rcon "execute in minecraft:overworld run setblock $x $RIG_Y $((RIG_Z + 2)) minecraft:end_portal_frame[facing=north,eye=false]" >/dev/null
done
for z in $((RIG_Z - 1)) $RIG_Z $((RIG_Z + 1)); do
  rcon "execute in minecraft:overworld run setblock $((RIG_X - 2)) $RIG_Y $z minecraft:end_portal_frame[facing=east,eye=false]" >/dev/null
  rcon "execute in minecraft:overworld run setblock $((RIG_X + 2)) $RIG_Y $z minecraft:end_portal_frame[facing=west,eye=false]" >/dev/null
done
sleep 1
assert_block "rig frame placed and eyeless" "$((RIG_X - 2))" "$RIG_Y" "$RIG_Z" "minecraft:end_portal_frame[eye=false]" present
assert_block "rig interior is clear" "$RIG_X" "$RIG_Y" "$RIG_Z" "minecraft:air" present

say "Baseline"
CREDITS_BEFORE="$(rcon "data get entity $PLAYER seenCredits" | tr -d '\r\n')"
note "seenCredits before: $CREDITS_BEFORE"
case "$CREDITS_BEFORE" in
  *" 1b"*|*"1b"*) note "ALREADY 1b — the credits oracle is blind for this player; item 2's credits check is visual only." ;;
esac
rcon "item replace entity $PLAYER weapon.mainhand with minecraft:ender_eye 16" >/dev/null
shot "00-baseline"

# ---------------------------------------------------------------------------
say "Phase A, half one — an eye sockets, no gateway appears"
# Aim at the first empty socket from directly above it, looking straight down.
FIRST_X=""; FIRST_Z=""
while read -r fx fz; do
  [ -z "$fx" ] && continue
  out="$(rcon "execute if block $fx $PORTAL_Y $fz minecraft:end_portal_frame[eye=true]")"
  case "$out" in *"Test failed"*) FIRST_X="$fx"; FIRST_Z="$fz"; break ;; esac
done <<EOF
$FRAMES
EOF

if [ -n "$FIRST_X" ]; then
  note "socketing one eye at $FIRST_X $PORTAL_Y $FIRST_Z"
  stand_and_use "$FIRST_X" "$PORTAL_Y" "$FIRST_Z" >/dev/null
  sleep 1
  assert_block "the eye socketed into the frame" "$FIRST_X" "$PORTAL_Y" "$FIRST_Z" "minecraft:end_portal_frame[eye=true]" present
  assert_block "NEGATIVE: no gateway on the clicked face" "$FIRST_X" "$((PORTAL_Y + 1))" "$FIRST_Z" "minecraft:end_gateway" absent
  shot "01-half-one"
else
  note "all twelve sockets already carry an eye — half one has nothing to place"
fi

# ---------------------------------------------------------------------------
say "Phase A, half two — a FRAMED gateway still ignites"
note "building a mud_bricks ring enclosing exactly one cell at $GW_IX $GW_IY $GW_IZ"
for cell in "$GW_IX $((GW_IY - 1)) $GW_IZ" "$GW_IX $((GW_IY + 1)) $GW_IZ" \
            "$((GW_IX - 1)) $GW_IY $GW_IZ" "$((GW_IX + 1)) $GW_IY $GW_IZ"; do
  # shellcheck disable=SC2086
  rcon "setblock $cell minecraft:mud_bricks" >/dev/null
done
rcon "setblock $GW_IX $GW_IY $GW_IZ minecraft:air" >/dev/null
assert_block "ring bottom is mud_bricks" "$GW_IX" "$((GW_IY - 1))" "$GW_IZ" "minecraft:mud_bricks" present
assert_block "enclosed cell is clear before ignition" "$GW_IX" "$GW_IY" "$GW_IZ" "minecraft:air" present

rcon "item replace entity $PLAYER weapon.mainhand with minecraft:ender_eye 16" >/dev/null
stand_and_use "$GW_IX" "$((GW_IY - 1))" "$GW_IZ" >/dev/null
sleep 2
rcon "execute in minecraft:overworld run tp $PLAYER 229.0 130 310 0 0" >/dev/null
sleep 1
# A portal is a frame with nothing in its interior, gateways included, so the
# proof that one ignited is the zone below — not a block. Asserting a block here
# tests the behaviour the frame-only rule removed.
assert_block "framed gateway leaves its interior empty" "$GW_IX" "$GW_IY" "$GW_IZ" "minecraft:end_gateway" absent
assert_file_contains "a zone was registered for the framed gateway" \
  "$CONSUMER_DIR/data/config/portal_links.json" "the_crumbling_reaches"
shot "02-half-two"

say "Phase A, half two NEGATIVE — a bare block does not ignite"
rcon "setblock $BARE_X $BARE_Y $BARE_Z minecraft:mud_bricks" >/dev/null
rcon "setblock $BARE_X $((BARE_Y + 1)) $BARE_Z minecraft:air" >/dev/null
rcon "item replace entity $PLAYER weapon.mainhand with minecraft:ender_eye 16" >/dev/null
stand_and_use "$BARE_X" "$BARE_Y" "$BARE_Z" >/dev/null
sleep 1
assert_block "NEGATIVE: no gateway on a bare block with no ring" \
  "$BARE_X" "$((BARE_Y + 1))" "$BARE_Z" "minecraft:end_gateway" absent
shot "03-bare-negative"

# ---------------------------------------------------------------------------
say "Phase B — the twelve-eye ritual"
rcon "item replace entity $PLAYER weapon.mainhand with minecraft:ender_eye 16" >/dev/null

FILLED=0
while read -r fx fz; do
  [ -z "$fx" ] && continue
  out="$(rcon "execute if block $fx $PORTAL_Y $fz minecraft:end_portal_frame[eye=true]")"
  case "$out" in *"Test passed"*) FILLED=$((FILLED + 1)) ;; esac
done <<EOF
$FRAMES
EOF
note "sockets already filled: $FILLED of 12"

# An eye goes in by right-clicking the socket's top face, so anything standing
# in the cell above it takes the click instead and the socket silently refuses.
# Two of these twelve had exactly that. Clearing the column is rig preparation;
# the assertion is still against the frames themselves.
while read -r fx fz; do
  [ -z "$fx" ] && continue
  rcon "execute in minecraft:overworld run setblock $fx $((PORTAL_Y + 1)) $fz minecraft:air" >/dev/null
done <<EOF
$FRAMES
EOF
note "cleared the cell above every socket"

# Fill up to ELEVEN, then assert the portal is still dark. That control is the
# whole point: lighting on fewer than twelve means the vanillaManaged
# reservation on the_end is not holding.
while read -r fx fz; do
  [ -z "$fx" ] && continue
  [ "$FILLED" -ge 11 ] && break
  out="$(rcon "execute if block $fx $PORTAL_Y $fz minecraft:end_portal_frame[eye=true]")"
  case "$out" in *"Test passed"*) continue ;; esac
  # Count what LANDED, not what was clicked. A click that misses still leaves
  # the socket empty, and an optimistic count reaches eleven with ten eyes in
  # the frame — the portal then cannot light and the failure reads as the mod's.
  tries=0
  while [ "$tries" -lt 3 ]; do
    stand_and_use "$fx" "$PORTAL_Y" "$fz" >/dev/null
    tries=$((tries + 1))
    out="$(rcon "execute if block $fx $PORTAL_Y $fz minecraft:end_portal_frame[eye=true]")"
    case "$out" in *"Test passed"*) break ;; esac
    note "socket $fx $fz did not take on attempt $tries"
  done
  case "$out" in
    *"Test passed"*) FILLED=$((FILLED + 1)); note "sockets filled: $FILLED" ;;
    *) note "socket $fx $fz REFUSED after $tries attempts — not counted" ;;
  esac
done <<EOF
$FRAMES
EOF

assert_block "NEGATIVE (the one that matters): interior is still DARK after eleven eyes" \
  "$PORTAL_X" "$PORTAL_Y" "$PORTAL_Z" "minecraft:end_portal" absent
shot "04-after-eleven"

say "The twelfth eye"
while read -r fx fz; do
  [ -z "$fx" ] && continue
  out="$(rcon "execute if block $fx $PORTAL_Y $fz minecraft:end_portal_frame[eye=true]")"
  case "$out" in *"Test failed"*)
    stand_and_use "$fx" "$PORTAL_Y" "$fz" >/dev/null
    break ;;
  esac
done <<EOF
$FRAMES
EOF
sleep 1

while read -r fx fz; do
  [ -z "$fx" ] && continue
  assert_block "socket $fx $fz carries an eye" "$fx" "$PORTAL_Y" "$fz" "minecraft:end_portal_frame[eye=true]" present
done <<EOF
$FRAMES
EOF
assert_block "the portal is lit" "$PORTAL_X" "$PORTAL_Y" "$PORTAL_Z" "minecraft:end_portal" present
shot "05-lit"

say "Crossing, and staying"
rcon "tp $PLAYER $((PORTAL_X)).5 $PORTAL_Y $((PORTAL_Z)).5" >/dev/null
sleep 4
assert_contains "arrived in the End" "execute as $PLAYER run data get entity @s Dimension" "minecraft:the_end"
shot "06-arrived"
note "waiting 10s — a single reading on the arrival tick cannot tell a fixed arrival from an ejection"
sleep 10
assert_contains "NEGATIVE: still in the End ten seconds later" \
  "execute as $PLAYER run data get entity @s Dimension" "minecraft:the_end"
shot "07-still-there"

say "Credits"
CREDITS_AFTER="$(rcon "data get entity $PLAYER seenCredits" | tr -d '\r\n')"
if [ "$CREDITS_AFTER" = "$CREDITS_BEFORE" ]; then
  _record ok "no premature credits (seenCredits unchanged)" "$CREDITS_BEFORE" "$CREDITS_AFTER"
else
  _record fail "no premature credits (seenCredits unchanged)" "$CREDITS_BEFORE" "$CREDITS_AFTER"
fi
note "If the baseline was already 1b this assertion cannot detect the defect — see RETEST.md."

rcon "execute in minecraft:overworld run forceload remove $((RIG_X - 8)) $((RIG_Z - 8)) $((RIG_X + 8)) $((RIG_Z + 8))" >/dev/null

finish
