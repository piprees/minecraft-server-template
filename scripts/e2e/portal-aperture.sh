#!/usr/bin/env bash
#
# portal-aperture.sh — does an immersive portal read as an OPENING?
#
# Purpose:  End-to-end check of the portal aperture effects against a real
#           client. Builds a scratch portal, lights it, and asserts the three
#           properties the effects exist for: the opening is never filled, the
#           frame is lit, and the light and colour reaching it come from the
#           dimension on the other side. Then walks through it, because a
#           gateway you cannot use is a picture.
#
# Context:  Local dev stack only (~/Projects/elfydd), linked to a platform
#           checkout via `./dev link`. Needs Pip's client CONNECTED with the
#           dev bridge open — every assertion that involves seeing something
#           is driven through it. No Carpet bots: a bot has no client.
#
# Usage:    ./dev launch --dev-bridge   then
#           ./scripts/e2e/portal-aperture.sh [--keep]
#             --keep   leave the scratch portal standing for a look
#
# Gotchas:  - Screenshots land in the run directory and are the point; read
#             them, the script can only prove they were taken.
#           - The scratch site is deliberately far from Pip's own portals
#             and at POSITIVE coordinates: a block's centre is block + 0.5,
#             and getting that wrong on a negative coordinate has walked
#             people through portals they meant to stand beside.
#           - The approach floor is level with the frame's bottom ring, so the
#             walk has no one-block rise to stop dead on. Vanilla step height
#             is 0.6: a player auto-steps a slab, never a block.
#           - Fill share is read from the mod's own `aperture:` heartbeat,
#             which is DEBUG. A silent run means log level, not a dead pass.
#

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="aperture"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

# Scratch site: positive coordinates, far from anything hand-built.
SX=2048; SY=90; SZ=2048
# the_basalt_spires: basalt frame, flint and steel, standard shape.
FRAME="minecraft:basalt"
IGNITER="minecraft:flint_and_steel"
APPROACH_Z=$((SZ - 4))                 # four blocks north of the frame plane
FILL_SHARE_CEILING=50                  # over half the plane and it reads as a surface
# The opening's bottom cell, which is how the scratch zone is picked out of
# every zone the server holds.
AY=$((SY + 1))

banner "portal-aperture — does an immersive portal read as an OPENING?"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
require_bridge

RESTARTS="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.RestartCount}}' 2>/dev/null)"
if [ "$RESTARTS" = "0" ]; then
  _record ok "mc has not restarted" "0 restarts" "$RESTARTS"
else
  _record fail "mc has not restarted" "0 restarts" "${RESTARTS:-unreadable}"
fi

if (cd "$CONSUMER_DIR" && docker exec mc sh -c 'unzip -l /data/mods/customdimensions-*.jar' 2>/dev/null) \
    | grep -q 'PortalAperture.class'; then
  _record ok "the served jar carries the aperture code under test" "PortalAperture.class" "present"
else
  _record fail "the served jar carries the aperture code under test" "PortalAperture.class" \
    "absent — run ./dev up first"
fi

# Every log assertion below reads only lines written after this point.
MARK="$(cd "$CONSUMER_DIR" && docker logs mc 2>&1 | wc -l | tr -d ' ')"
since() { (cd "$CONSUMER_DIR" && docker logs mc 2>&1 | tail -n "+$((MARK + 1))"); }

say "Build and light a scratch portal"
rcon "execute in minecraft:overworld run forceload add $SX $SZ" >/dev/null
rcon "execute in minecraft:overworld run tp $PLAYER $((SX)).5 $((SY + 1)) $((APPROACH_Z)).5 0 0" >/dev/null
wait_for_chunk "$SX" "$SY" "$SZ" minecraft:overworld 60
# A standard frame: 4 wide, 5 tall, hollow 2x3 interior, plane along X.
rcon "execute in minecraft:overworld run fill $((SX-1)) $SY $SZ $((SX+2)) $((SY+4)) $SZ $FRAME" >/dev/null
rcon "execute in minecraft:overworld run fill $SX $((SY+1)) $SZ $((SX+1)) $((SY+3)) $SZ minecraft:air" >/dev/null
# The approach floor sits at the bottom ring's own level and stops one block
# short of the frame plane, so the player's feet are already level with the
# interior's lowest cell when they reach it.
rcon "execute in minecraft:overworld run fill $((SX-1)) $SY $((SZ-6)) $((SX+2)) $SY $((SZ-1)) minecraft:smooth_stone" >/dev/null
rcon "execute in minecraft:overworld run fill $((SX-1)) $((SY+1)) $((SZ-6)) $((SX+2)) $((SY+2)) $((SZ-1)) minecraft:air" >/dev/null
assert_block "the frame interior is clear before ignition" \
  "$SX" "$((SY+1))" "$SZ" minecraft:air present

rcon "item replace entity $PLAYER weapon.mainhand with $IGNITER 1" >/dev/null
rcon "execute in minecraft:overworld run tp $PLAYER $((SX)).5 $((SY + 1)) $((APPROACH_Z)).5 0 25" >/dev/null
sleep 2
assert_shot "a screenshot of the unlit frame" "1-unlit"

# `customdim use` runs the interaction manager's own interactBlock as the
# player, which is the only use that reaches this game. It needs reach, so the
# player stands in the opening's bottom cell and uses the ring block below.
stand_and_use "$SX" "$SY" "$SZ" up
sleep 3

LIT=0
WAITED=0
while [ "$WAITED" -lt 20 ]; do
  if since | grep -q "aperture: emitted"; then LIT=1; break; fi
  sleep 4
  WAITED=$((WAITED + 4))
done
state_refresh
if [ "$LIT" -eq 1 ]; then
  _record ok "the portal lit and is emitting" "an 'aperture: emitted' heartbeat" "after ${WAITED}s"
else
  _record fail "the portal lit and is emitting" "an 'aperture: emitted' heartbeat" \
    "nothing in ${WAITED}s — the ignition may have missed the frame, or DEBUG logging is off"
fi
assert_source_zone_unique "one source zone was registered at the scratch site" "$SX" "$AY" "$SZ"
assert_source_zone_frame_stands "and its frame stands" "$SX" "$AY" "$SZ"
DESTINATION="$(state_read "$(source_zone_select "$SX" "$AY" "$SZ") | .targetWorld")"

say "The opening reads as an opening"
# Rule: NOTHING in the frame. The effects are the whole visible portal.
assert_block "no portal blocks in the frame — the frame is the thing" \
  "$SX" "$((SY+1))" "$SZ" minecraft:nether_portal absent

FILLLINE="$(since | grep 'aperture: emitted' | tail -1)"
if [ -n "$FILLLINE" ]; then
  note "$(printf '%s' "$FILLLINE" | sed 's/.*aperture: /aperture: /')"
  SHARE="$(printf '%s' "$FILLLINE" | sed -n 's/.*(\([0-9]*\)%).*/\1/p')"
  case "$SHARE" in
    ''|*[!0-9]*)
      _record fail "the opening is under half filled" "< ${FILL_SHARE_CEILING}%" \
        "no percentage in the heartbeat: $FILLLINE" ;;
    *)
      if [ "$SHARE" -lt "$FILL_SHARE_CEILING" ]; then
        _record ok "the opening is under half filled, so it reads through" \
          "< ${FILL_SHARE_CEILING}%" "${SHARE}%"
      else
        _record fail "the opening is under half filled, so it reads through" \
          "< ${FILL_SHARE_CEILING}%" "${SHARE}% — an opening this full reads as a surface"
      fi ;;
  esac
else
  _record fail "the opening is under half filled" "< ${FILL_SHARE_CEILING}%" \
    "no aperture fill heartbeat (is the mod's DEBUG logging on?)"
fi

if since | grep -q "immersive: edge particles"; then
  _record ok "the frame ring is lit" "an 'immersive: edge particles' line" \
    "$(since | grep 'immersive: edge particles' | tail -1 | sed 's/.*immersive: /immersive: /')"
else
  _record fail "the frame ring is lit" "an 'immersive: edge particles' line" \
    "none — the frame is not being drawn"
fi

say "The light comes from the far side"
GLOW="$(since | grep 'immersive: destination glow' | tail -1)"
if [ -n "$GLOW" ]; then
  _record ok "the destination's light and colour reached the opening" \
    "an 'immersive: destination glow' line" \
    "$(printf '%s' "$GLOW" | sed 's/.*immersive: /immersive: /')"
else
  _record fail "the destination's light and colour reached the opening" \
    "an 'immersive: destination glow' line" \
    "none — the opening is showing its configured colour only"
fi

rcon "execute in minecraft:overworld run tp $PLAYER $((SX)).5 $((SY + 1)) $((APPROACH_Z)).5 0 0" >/dev/null
sleep 2
assert_shot "a screenshot of the lit opening" "2-lit"

say "It is a gateway, not a picture"
state_refresh
BEFORE_DIM="$(player_dimension)"
note "before the walk: $BEFORE_DIM"
# Yaw 0 is south, which is the way the frame is from the approach point.
if bridge_look 0 0; then
  assert_json "the client is facing the frame" "$BRIDGE_LAST" '.after.player.rotation.facing' south
else
  _record fail "the client is facing the frame" "yaw 0" "$BRIDGE_REASON"
fi
case "$DESTINATION" in
  adventure:*)
    if walk_to_dimension "$DESTINATION" 6 3; then
      state_refresh
      _record ok "the player walked through it" "$BEFORE_DIM -> $DESTINATION on foot" \
        "arrived in $(player_dimension)"
    else
      _record fail "the player walked through it" "$BEFORE_DIM -> $DESTINATION on foot" "$WALK_NOTE"
    fi ;;
  *)
    _record fail "the player walked through it" "a zone naming where it leads" \
      "no destination to walk to: $DESTINATION" ;;
esac
assert_shot "a screenshot of the arrival" "3-arrival"

if [ "$KEEP" = "0" ]; then
  say "Clean up"
  rcon "execute in minecraft:overworld run fill $((SX-1)) $SY $SZ $((SX+2)) $((SY+4)) $SZ minecraft:air" >/dev/null
  rcon "execute in minecraft:overworld run forceload remove $SX $SZ" >/dev/null
  note "scratch frame removed"
fi

finish
