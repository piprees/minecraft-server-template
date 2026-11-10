#!/usr/bin/env bash
# rig-frames-lib.sh - Frames built and lit in the 26.x rig world, for the portal checks.
#
# Purpose: helpers e2e-vanilla-portal.sh and e2e-companion-portal.sh share:
#          open a rig level, stand a frame of a rig destination on one axis
#          (X, Z or flat Y) in adventure:e2e_one, light it as the player
#          through `customdim use`, and the points a check stands at, enters
#          by and retreats to.
# Context: sourced after lib.sh. 26.x only; the rig pair and its additions
#          (scripts/e2e/rig-26/) are the only worlds whose blocks it edits.
# Usage:   rig_open e2e_one; rig_frame X 20 24 minecraft:magenta_concrete
#          rig_light minecraft:amethyst_shard; then $FRAME_STAND etc.
# Gotchas: e2e_one is flat with its top block at y -61, so an upright frame's
#          bottom ring replaces the floor and a flat frame is sunk flush into
#          it. The level must be open before anything is built: `fill` into a
#          level that is not loaded answers and places nothing (T114). Every
#          frame stands in a force-loaded chunk so a player leaving does not
#          take the chunk with them.

# shellcheck disable=SC2034  # FRAME_* are read by the scripts that source this
RIG_NS="${RIG_NS:-adventure}"
RIG_WORLD="$RIG_NS:e2e_one"
FLOOR_Y=-61

rig_rcon() { # command, run in the rig world
  rcon "execute in $RIG_WORLD run $1"
}

# Opens a lazy rig level and waits, capped, until e2e-state reports it open.
rig_open() { # slug [max-seconds]
  local slug="$1" max="${2:-90}" waited=0 open
  rcon "customdim activate $slug" >/dev/null
  while [ "$waited" -le "$max" ]; do
    if state_refresh; then
      open="$(state_read "[.dimensions[] | select(.slug==\"$slug\") | .open] | first")"
      if [ "$open" = "true" ]; then
        note "$slug open after ${waited}s"
        return 0
      fi
    fi
    sleep 3
    waited=$((waited + 3))
  done
  note "$slug still not open after ${max}s"
  return 1
}

# Stands an unlit frame; sets FRAME_CELL (an interior cell), FRAME_USE (the
# block and face the ignition click uses), FRAME_STAND and FRAME_LOOK (in
# front of it, facing it, within reach and within the approach title's range),
# FRAME_ENTER (inside the opening) and FRAME_AWAY (far enough to reset the
# approach). X faces along z, Z along x, Y is flush with the floor.
rig_frame() { # axis x0 z0 frame-block
  local axis="$1" x0="$2" z0="$3" frame="$4" y0=$((FLOOR_Y + 1))
  rig_rcon "forceload add $((x0 - 8)) $((z0 - 8)) $((x0 + 8)) $((z0 + 8))" >/dev/null
  case "$axis" in
    X)
      rig_rcon "fill $((x0 - 1)) $FLOOR_Y $z0 $((x0 + 2)) $((y0 + 3)) $z0 $frame" >/dev/null
      rig_rcon "fill $x0 $y0 $z0 $((x0 + 1)) $((y0 + 2)) $z0 minecraft:air" >/dev/null
      FRAME_CELL="$x0 $y0 $z0"
      FRAME_USE="$x0 $FLOOR_Y $z0 up"
      FRAME_STAND="$((x0 + 1)).0 $y0 $((z0 - 4)).5"
      FRAME_LOOK="0 0"
      FRAME_ENTER="$((x0 + 1)).0 $y0 $z0.5"
      FRAME_AWAY="$((x0 + 1)).0 $y0 $((z0 - 20)).5"
      ;;
    Z)
      rig_rcon "fill $x0 $FLOOR_Y $((z0 - 1)) $x0 $((y0 + 3)) $((z0 + 2)) $frame" >/dev/null
      rig_rcon "fill $x0 $y0 $z0 $x0 $((y0 + 2)) $((z0 + 1)) minecraft:air" >/dev/null
      FRAME_CELL="$x0 $y0 $z0"
      FRAME_USE="$x0 $FLOOR_Y $z0 up"
      FRAME_STAND="$((x0 - 4)).5 $y0 $((z0 + 1)).0"
      FRAME_LOOK="-90 0"
      FRAME_ENTER="$x0.5 $y0 $((z0 + 1)).0"
      FRAME_AWAY="$((x0 - 20)).5 $y0 $((z0 + 1)).0"
      ;;
    Y)
      rig_rcon "fill $((x0 - 1)) $FLOOR_Y $((z0 - 1)) $((x0 + 2)) $FLOOR_Y $((z0 + 2)) $frame" >/dev/null
      rig_rcon "fill $x0 $FLOOR_Y $z0 $((x0 + 1)) $FLOOR_Y $((z0 + 1)) minecraft:air" >/dev/null
      FRAME_CELL="$x0 $FLOOR_Y $z0"
      FRAME_USE="$((x0 - 1)) $FLOOR_Y $z0 up"
      FRAME_STAND="$((x0 + 1)).0 $y0 $((z0 - 3)).5"
      FRAME_LOOK="0 55"
      FRAME_ENTER="$((x0 + 1)).0 $FLOOR_Y $((z0 + 1)).0"
      FRAME_AWAY="$((x0 + 1)).0 $y0 $((z0 - 20)).5"
      ;;
    *) note "rig_frame: unknown axis $axis"; return 1 ;;
  esac
}

rig_tp() { # "x y z" "yaw pitch"
  rig_rcon "tp $PLAYER $1 $2" >/dev/null
}

# The igniter in the main hand, the player at the frame, and the click.
rig_light() { # igniter
  rcon "item replace entity $PLAYER weapon.mainhand with $1 1" >/dev/null
  rig_tp "$FRAME_STAND" "$FRAME_LOOK"
  sleep 2
  # shellcheck disable=SC2086  # FRAME_USE is "x y z face" on purpose
  use_block $FRAME_USE
}

# The source zone over FRAME_CELL in the rig world, as a jq selection.
rig_zone_select() {
  # shellcheck disable=SC2086  # FRAME_CELL is "x y z" on purpose
  set -- $FRAME_CELL
  printf '.zones[] | select(.kind=="source" and .world=="%s" and any(.interior[]; . == [%s,%s,%s]))' \
    "$RIG_WORLD" "$1" "$2" "$3"
}
