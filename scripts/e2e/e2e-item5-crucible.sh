#!/usr/bin/env bash
# RETEST item 5 — the crucible via the test frame.
#
# Purpose: build the frame, ignite it, walk in on the real client, and prove
#          BOTH companion-suppress lines appear for the one crossing.
# Context: a Carpet bot cannot run this — the pass condition is in the CLIENT
#          log and a bot has no client. Zero markers from a bot is the bot.
# Usage:   ./e2e-item5-crucible.sh
# Gotchas: one suppress line is a FAIL, not a pass. Only startWorldLoading
#          builds the screen the player sees; a lone joinWorld is the old
#          half-hooked behaviour reporting success.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item5"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

banner "RETEST item 5 — the crucible via the test frame"

IX_A=228; IX_B=229; IZ=297            # interior columns, frame plane
IY_LO=130; IY_HI=132                  # interior rows
RING_W=227; RING_E=230
RING_BOT=129; RING_TOP=133
APPROACH_Z=337                        # 40 blocks south of the frame

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
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
rcon "execute in minecraft:overworld run tp FLUXXINATED ${IX_A}.5 $((RING_BOT + 1)) $((IZ + 3)).5" >/dev/null
wait_for_chunk "$IX_A" "$((RING_BOT + 1))" "$IZ" minecraft:overworld 60
assert_contains "player is at the build site" \
  "data get entity FLUXXINATED Pos" "228"

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
# Stand in the interior's bottom cell, look straight down at the bottom ring
# block. The aim is the tp; the click is only the trigger.
aim_and_use "229.0" "$IY_LO" "297.5" 0 90 "ignite the crucible frame"
sleep 2
# A SOURCE portal has no blocks at all — only an ARRIVAL portal is built from
# real nether_portal blocks. The oracle for ignition is the registered zone.
assert_block "source interior stays empty (source portals have no blocks)" \
  "$IX_A" "131" "$IZ" minecraft:air present
assert_file_contains "a zone was registered for the crucible" \
  "$CONSUMER_DIR/data/config/portal_links.json" "the_crucible"
shot "01-lit"

# The player is standing IN the portal when it lights, so the ignition crossing
# may fire immediately. That crossing never exercised the approach preload, so
# it is not the one being measured.
note "returning to the approach point — the ignition crossing is not the measured crossing"
rcon "execute in minecraft:overworld run tp $PLAYER 229.0 129 $APPROACH_Z 180 0" >/dev/null
sleep 3
assert_contains "back in the overworld before the measured crossing" \
  "execute as $PLAYER run data get entity @s Dimension" "minecraft:overworld"

say "Baseline the client log"
BEFORE="$RUN_DIR/client-before.log"
dev client-log --tail 4000 > "$BEFORE" 2>/dev/null || true
BEFORE_LINES="$(wc -l < "$BEFORE" | tr -d ' ')"
note "client log baseline: $BEFORE_LINES lines"

say "The measured crossing — walk 40 blocks in on the real client"
# walk_forward carries both findings: a long synthetic key-down is dropped, and
# a walk stops dead on a one-block rise. build-test-frame.sh flattens the lane;
# the jump inside the helper is belt and braces only.
APPROACH_CELL="${IX_A}.5 $((IY_LO)) ${IZ}.5"
export APPROACH_CELL
walk_forward "$((IZ + 2))" 8 "the_crucible" || \
  _record fail "the player reached the frame" "z <= $((IZ + 2)) within 8 steps" "walk did not arrive — check the lane is flat"
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
  "$SRV" "companion-send:preloaded-transfer player=FLUXXINATED dimension=adventure:the_crucible"

say "Arrival"
assert_contains "arrived in the crucible" \
  "execute as $PLAYER run data get entity @s Dimension" "adventure:the_crucible"
shot "03-arrived"

say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

finish
