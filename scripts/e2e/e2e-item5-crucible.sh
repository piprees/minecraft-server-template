#!/usr/bin/env bash
# RETEST item 5 — the crucible via the test frame.
#
# Purpose: build the frame, ignite it, walk in on the real client, and prove
#          BOTH companion-suppress lines appear for the one crossing.
# Context: a Carpet bot cannot run this — the pass condition is in the CLIENT
#          log and a bot has no client. Zero markers from a bot is the bot.
# Usage:   ./dev launch --dev-bridge   then   ./e2e-item5-crucible.sh
# Gotchas: one suppress line is a FAIL, not a pass. Only startWorldLoading
#          builds the screen the player sees; a lone joinWorld is the old
#          half-hooked behaviour reporting success.
#          The walk is judged by the SERVER's dimension after each step, not by
#          the walk's own `arrived`: crossing moves the player far enough that
#          any walk reads as arrived.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item5"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

banner "RETEST item 5 — the crucible via the test frame"

DESTINATION="adventure:the_crucible"
IX_A=228; IX_B=229; IZ=297            # interior columns, frame plane
IY_LO=130; IY_HI=132                  # interior rows
RING_W=227; RING_E=230
RING_BOT=129; RING_TOP=133
APPROACH_Z=337                        # 40 blocks south of the frame
WALK_YAW=180                          # yaw 180 is north, straight down the lane
WALK_STEP=12                          # blocks per walk, four steps clears 40
WALK_STEPS=6

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
require_bridge
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
note "mc StartedAt: $STARTED_AT"

say "Load the site"
# Chunks load from a PLAYER, not from RCON. setblock into an unloaded chunk
# answers "That position is not loaded" and silently does nothing, so the
# player goes to the site before anything is built.
# `tp` alone keeps the player's current dimension, so a run started from the
# crucible builds the rig at these coordinates in the WRONG world and every fill
# lands in an unloaded chunk.
# Three blocks SOUTH of the frame plane, never inside it: the frame may still
# be lit from a previous run, and landing in the interior crosses instantly —
# the site assertion then reads the crucible and every later fill is unloaded.
rcon "execute in minecraft:overworld run tp $PLAYER ${IX_A}.5 $((RING_BOT + 1)) $((IZ + 3)).5" >/dev/null
wait_for_chunk "$IX_A" "$((RING_BOT + 1))" "$IZ" minecraft:overworld 60
state_refresh
assert_player_dimension "the player is in the overworld at the build site" minecraft:overworld
assert_state "the player is standing on the frame's own column" \
  "$PLAYER_SELECT | .blockPos[0]" "$IX_A"

say "Build the frame"
BUILD="$(cd "$(dirname "$0")" && pwd)/build-test-frame.sh"
if [ -x "$BUILD" ]; then
  if "$BUILD"; then note "frame build reported success"; else
    _record fail "build-test-frame.sh completed" "exit 0" "non-zero exit"; fi
else
  _record fail "build-test-frame.sh is present and executable" "$BUILD" "missing or not executable"
fi

say "Verify the ring — ten cells"
for y in $IY_LO 131 $IY_HI; do
  assert_block "ring west $RING_W $y $IZ"  "$RING_W" "$y" "$IZ" minecraft:copper_block present
  assert_block "ring east $RING_E $y $IZ"  "$RING_E" "$y" "$IZ" minecraft:copper_block present
done
for x in $IX_A $IX_B; do
  assert_block "ring bottom $x $RING_BOT $IZ" "$x" "$RING_BOT" "$IZ" minecraft:copper_block present
  assert_block "ring top $x $RING_TOP $IZ"    "$x" "$RING_TOP" "$IZ" minecraft:copper_block present
done

say "Verify the interior is clear — six cells"
for x in $IX_A $IX_B; do
  for y in $IY_LO 131 $IY_HI; do
    assert_block "interior clear $x $y $IZ" "$x" "$y" "$IZ" minecraft:air present
  done
done
shot "00-frame-built"

say "Ignite"
rcon "item replace entity $PLAYER weapon.mainhand with minecraft:diamond 1" >/dev/null
# Stand in the interior's bottom cell and use the top face of the bottom ring
# block. This goes through the interaction manager's own interactBlock, which
# is the only use that reaches this game — a synthetic right-click does not.
stand_and_use "$IX_B" "$RING_BOT" "$IZ" up
sleep 2
# A SOURCE portal has no blocks at all — only an ARRIVAL portal is built from
# real nether_portal blocks. The oracle for ignition is the registered zone,
# and its frame is checked in the same round trip.
assert_block "source interior stays empty (source portals have no blocks)" \
  "$IX_A" "131" "$IZ" minecraft:air present
state_refresh
assert_source_zone "the frame's own zone leads to the crucible" \
  "$IX_A" "$IY_LO" "$IZ" "$DESTINATION"
assert_source_zone_unique "only one source zone over this frame — a second is the re-ignition duplicate" \
  "$IX_A" "$IY_LO" "$IZ"
assert_source_zone_frame_stands "the crucible's source frame stands" "$IX_A" "$IY_LO" "$IZ"
shot "01-lit"

# The player is standing IN the portal when it lights, so the ignition crossing
# may fire immediately. That crossing never exercised the approach preload, so
# it is not the one being measured.
note "returning to the approach point — the ignition crossing is not the measured crossing"
# Feet at 130: the lane floor is the block at y129, so its top face is y130 and
# that is where the frame's interior starts. Standing a block low leaves the
# player inside the floor and the walk begins by being pushed out of it.
rcon "execute in minecraft:overworld run tp $PLAYER ${IX_B}.0 $IY_LO $APPROACH_Z $WALK_YAW 0" >/dev/null
sleep 3
state_refresh
assert_player_dimension "back in the overworld before the measured crossing" minecraft:overworld
assert_state "back at the approach point, 40 blocks out" \
  "$PLAYER_SELECT | .blockPos[2]" "$APPROACH_Z"

say "Baseline the client log"
BEFORE="$RUN_DIR/client-before.log"
dev client-log --tail 4000 > "$BEFORE" 2>/dev/null || true
BEFORE_LINES="$(wc -l < "$BEFORE" | tr -d ' ')"
note "client log baseline: $BEFORE_LINES lines"

say "The measured crossing — walk 40 blocks in on the real client"
# The client walks on its own feet, driven through the companion mod's key
# bindings. Facing is set first because a walk holds forward, and forward is
# wherever the client is looking.
if bridge_look "$WALK_YAW" 0; then
  assert_json "the client is facing down the lane" "$BRIDGE_LAST" \
    '.after.player.rotation.facing' north
else
  _record fail "the client is facing down the lane" "yaw $WALK_YAW" "$BRIDGE_REASON"
fi

if walk_to_dimension "$DESTINATION" "$WALK_STEP" "$WALK_STEPS"; then
  _record ok "the player walked in and crossed" "$DESTINATION within $WALK_STEPS steps" \
    "crossed, see the per-step travel above"
else
  _record fail "the player walked in and crossed" "$DESTINATION within $WALK_STEPS steps" "$WALK_NOTE"
fi
sleep 3
shot "02-after-walk"

say "Client-side markers"
AFTER="$RUN_DIR/client-after.log"
dev client-log --tail 8000 > "$AFTER" 2>/dev/null || true
NEW="$RUN_DIR/client-new.log"
tail -n "+$((BEFORE_LINES + 1))" "$AFTER" > "$NEW" 2>/dev/null || cp "$AFTER" "$NEW"
note "new client lines this crossing: $(wc -l < "$NEW" | tr -d ' ')"

assert_count_at_least "companion-suppress at site=joinWorld" \
  "$NEW" "companion-suppress:arrival-screen site=joinWorld" 1
assert_count_at_least "NEGATIVE (one line is a FAIL): companion-suppress at site=startWorldLoading" \
  "$NEW" "companion-suppress:arrival-screen site=startWorldLoading" 1

say "Server-side marker"
SRV="$RUN_DIR/mc-since.log"
(cd "$CONSUMER_DIR" && docker logs mc --since 5m > "$SRV" 2>&1) || true
assert_file_contains "server sent the preloaded transfer" \
  "$SRV" "companion-send:preloaded-transfer player=$PLAYER dimension=$DESTINATION"

say "Arrival — both sides agree"
state_refresh
assert_player_dimension "the server has the player in the crucible" "$DESTINATION"
bridge_state
assert_bridge_player "the client has the player in the crucible" '.dimension' "$DESTINATION"
# ArrivalScreen closes on its own first tick, so seconds later it must be gone.
# One that is still up is a crossing the player is still staring at.
assert_bridge "the arrival screen closed itself" '.client.arrivalScreen' false
report_metric "screen up after the crossing" "$(bridge_read '.client.currentScreen')" \
  "reported, not asserted — an open inventory is not a defect"
shot "03-arrived"

say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

finish
