#!/usr/bin/env bash
# RETEST item 3 — the End exit portal.
#
# Purpose: prove the natural End exit portal returns the player to the
#          overworld, and that the mod does not refuse it.
# Context: run this straight after item 2, while the player is still in the End.
# Usage:   ./dev launch --dev-bridge   then   ./e2e-item3-end-exit.sh
# Gotchas: the return lands on the player's OWN spawn, not a projected
#          position, so an unexpected arrival coordinate is not a defect. The
#          failure to watch for is still reading minecraft:the_end after
#          contact. The exit portal only exists once the dragon is dead — that
#          is a precondition, and this refuses rather than reporting a pass.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="item3"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

banner "RETEST item 3 — the End exit portal"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
require_bridge

say "Precondition — the player is in the End"
state_refresh
DIM="$(player_dimension)"
case "$DIM" in
  minecraft:the_end) note "player dimension: $DIM" ;;
  *) printf '\033[31mPRECONDITION NOT MET\033[0m — %s is not in the End.\n' "$PLAYER" >&2
     printf 'e2e-state says the dimension is: %s\n' "$DIM" >&2
     printf 'Run item 2 first. This is not a pass.\n' >&2
     exit 1 ;;
esac
shot "00-in-the-end"

# This world's End comes from the modded preset, so vanilla's dragon fight never
# ran and there is no natural exit portal to find — no dragon, no bedrock, no
# end_portal at the centre, measured. What item 3 tests is the MOD's handling of
# an End exit portal, not how one came to exist, so the portal is built as a rig.
# It is bedrock-floored and three by three, the shape vanilla's own exit portal
# has, and it is rebuilt from air on every run.
# shellcheck disable=SC2034  # lib.sh's assert_block reads it
ASSERT_DIM="minecraft:the_end"
say "Build the exit portal rig"
# The player stands well clear on their own bedrock pad. Building under their
# feet drops them into the void, which kills them and respawns them in the
# overworld — the precondition this script just checked.
rcon "execute in minecraft:the_end run tp $PLAYER 12.5 70 0.5" >/dev/null
wait_for_chunk 12 70 0 minecraft:the_end 60
rcon "execute in minecraft:the_end run fill 10 55 -2 14 55 2 minecraft:bedrock" >/dev/null
rcon "execute in minecraft:the_end run tp $PLAYER 12.5 56 0.5" >/dev/null
sleep 6
rcon "execute in minecraft:the_end run fill -3 54 -3 3 62 3 minecraft:air" >/dev/null
rcon "execute in minecraft:the_end run fill -2 54 -2 2 54 2 minecraft:bedrock" >/dev/null
rcon "execute in minecraft:the_end run fill -1 55 -1 1 55 1 minecraft:end_portal" >/dev/null
sleep 2
assert_block "the exit portal rig stands" "0" "55" "0" "minecraft:end_portal" present

say "Precondition — the exit portal exists"
# The exit portal sits at x0 z0. Scan a bounded Y window for a portal block
# rather than assuming a height. No portal means the dragon is still alive.
EXIT_Y=""
for y in $(seq 70 -1 50); do
  out="$(rcon "execute in minecraft:the_end run execute if block 0 $y 0 minecraft:end_portal")"
  case "$out" in *"Test passed"*) EXIT_Y="$y"; break ;; esac
done
if [ -z "$EXIT_Y" ]; then
  printf '\033[31mPRECONDITION NOT MET\033[0m — no minecraft:end_portal found at 0 y 0 (y50-70).\n' >&2
  printf 'The End exit portal only opens once the dragon is dead. This is not a pass.\n' >&2
  exit 1
fi
note "exit portal found at 0 $EXIT_Y 0"
_record ok "the End exit portal exists" "an end_portal block at 0 y 0" "found at y=$EXIT_Y"

say "Step into it"
rcon "execute in minecraft:the_end run tp $PLAYER 0.5 $EXIT_Y 0.5" >/dev/null
sleep 5
shot "01-after-contact"

# The arrival COORDINATE is the player's own spawn and is deliberately not
# asserted. The real failure is the mod refusing the exit portal, which shows
# as the player still reading the_end after contact — and the dimension is one
# exact value, so "returned to the overworld" already excludes it. There is no
# separate negative to make.
state_refresh
assert_player_dimension "returned to the overworld" minecraft:overworld

note "waiting 10s — a single reading on the arrival tick cannot tell a return from a bounce"
sleep 10
state_refresh
assert_player_dimension "NEGATIVE: still in the overworld ten seconds later" minecraft:overworld
bridge_state
assert_bridge_player "the client agrees the player is in the overworld" '.dimension' minecraft:overworld
shot "02-settled"

finish
