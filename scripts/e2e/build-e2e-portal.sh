#!/usr/bin/env bash
# build-e2e-portal.sh - Rebuild the rig portal from e2e_one into e2e_two.
#
# Purpose: the e2e pair is the only fixture whose blocks may be edited, and its
#          portal is what every destination measurement reads through. When its
#          link is missing from portal_links.json there is no projection, the
#          feed reads destinationChunks 0, and every fill behind it writes into
#          an unloaded chunk while the script reports success ([T114]).
# Context: template-only, LOCAL RIG ONLY. Edits blocks in elfydd:e2e_one.
#          Never point it at the overworld or adventure:the_amplified_reaches.
# Usage:   build-e2e-portal.sh [--dry-run]
# Gotchas: A frame is made of the DESTINATION's frameBlock, so the frame here is
#          magenta_concrete (e2e_two's), not lime. Both dimensions must be
#          loaded first - `/customdim load <name>`, never `execute in` ([T114]).
#          The igniter is an amethyst shard: `give` fills the inventory without
#          selecting the slot, so the shard is placed into hotbar.0 explicitly.
set -uo pipefail
export PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin
CD="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
DRY="${1:-}"

SRC="elfydd:e2e_one"
FRAME="minecraft:magenta_concrete"   # e2e_two's own frameBlock
IGNITER="minecraft:amethyst_shard"
# Opening x 4-5, y -60..-58 on the floor at y -61; plane at z=4, normal +z, so
# the camera at (5.5, -60, 1.0) yaw 0 looks straight through it.
Z=4; X0=4; X1=5; Y0=-60; Y1=-58

rc() { (cd "$CD" && timeout 20 docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r' | sed 's/^> //'); }

say() { printf '%s\n' "$*"; }

if [ "$DRY" = "--dry-run" ]; then
  say "DRY RUN - frame $FRAME at $SRC z=$Z, opening x $X0-$X1 y $Y0-$Y1"
  exit 0
fi

say "=== load both dimensions (T114: execute-in does not create one) ==="
rc "customdim load e2e_one"; rc "customdim load e2e_two"; sleep 10

say "=== bot FIRST: a loaded dimension is not resident chunks, and a fill"
say "    into a chunk nothing tickets answers 'That position is not loaded' ==="
rc "carpet commandPlayer true" >/dev/null
# Carpet's `facing` takes a rotation, not coordinates. Yaw 0 looks towards +z.
rc "player RigBot spawn at 4.5 $Y0 2.5 facing 0 20 in $SRC" | head -c 70; echo
sleep 4
rc "gamemode creative RigBot" >/dev/null
sleep 2
say "  chunk resident: $(rc "execute in $SRC run data get block 4 -61 $Z" | head -c 55)"

say "=== frame ring at z=$Z ==="
# Ring = the 4x5 slab minus the 2x3 opening, so the opening stays air.
rc "execute in $SRC run fill $((X0-1)) $((Y0-1)) $Z $((X1+1)) $((Y1+1)) $Z $FRAME" | head -c 70; echo
rc "execute in $SRC run fill $X0 $Y0 $Z $X1 $Y1 $Z minecraft:air" | head -c 70; echo

say "=== ignite from inside the opening ==="
rc "player RigBot tp 4.5 $Y0 $((Z)).5" >/dev/null 2>&1
sleep 2
# hotbar.0 explicitly: `give` fills the inventory without selecting the slot.
rc "item replace entity RigBot hotbar.0 with $IGNITER" | head -c 70; echo
sleep 2
rc "player RigBot look at 4.5 $((Y0-1)) $((Z)).5" >/dev/null
sleep 1
rc "player RigBot use once" | head -c 70; echo
sleep 6

say "=== did a link appear? ==="
LINKS=$( (cd "$CD" && docker exec mc sh -c 'cat /data/config/portal_links.json 2>/dev/null') | tr -d '\n' )
COUNT=$(printf '%s' "$LINKS" | grep -o 'e2e_two' | wc -l | tr -d ' ')
say "  portal_links.json mentions e2e_two: $COUNT"
rc "player RigBot kill" >/dev/null 2>&1

if [ "${COUNT:-0}" -gt 0 ]; then
  say "PORTAL-BUILT"
else
  say "PORTAL-NOT-LINKED - the frame is placed but ignition did not take."
  say "  Check the opening is clear (an igniter item PLACED in it refuses with"
  say "  OPENING_NOT_ENCLOSED), and that $IGNITER matches the dimension config."
fi
