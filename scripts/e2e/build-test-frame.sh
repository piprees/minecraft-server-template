#!/usr/bin/env bash
# Build the_crucible test portal frame at x228-229 / y130-132 / z297 (overworld).
# Frame block minecraft:copper_block, igniter minecraft:diamond, scale 4.0 —
# read from config/custom-dimensions/dimensions/the_crucible.json, not assumed.
# Idempotent: re-running re-asserts every cell. Verifies each placement.
set -uo pipefail
export PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin
CD=/Users/pip/Projects/elfydd
DRY="${1:-}"
FAIL=0

rc() { (cd "$CD" && timeout 20 docker exec -i mc rcon-cli "$1" 2>&1); }

place() { # x y z block
  local cmd="setblock $1 $2 $3 $4"
  if [ "$DRY" = "--dry-run" ]; then printf '  DRY  %s\n' "$cmd"; return; fi
  rc "$cmd" >/dev/null
  local out; out=$(rc "execute if block $1 $2 $3 $4")
  local p f
  p=$(printf '%s' "$out" | grep -c "Test passed" || true)
  f=$(printf '%s' "$out" | grep -c "Test failed" || true)
  if [ "$p" -ge 1 ] && [ "$f" -eq 0 ]; then printf '  OK    %-34s -> %s\n' "$1 $2 $3" "$4"
  else printf '  FAIL  %-34s -> %s   [%s]\n' "$1 $2 $3" "$4" "$(printf '%s' "$out" | tr -d '\n' | tail -c 70)"; FAIL=$((FAIL+1)); fi
}

echo "== interior cleared to air (x228-229, y130-132, z297)"
for x in 228 229; do for y in 130 131 132; do place $x $y 297 minecraft:air; done; done

echo "== frame ring, minecraft:copper_block"
for y in 130 131 132; do place 227 $y 297 minecraft:copper_block; done   # west side
for y in 130 131 132; do place 230 $y 297 minecraft:copper_block; done   # east side
for x in 228 229; do place $x 129 297 minecraft:copper_block; done       # bottom, flush with ground
for x in 228 229; do place $x 133 297 minecraft:copper_block; done       # top

# The approach lane. A walk STOPS DEAD on a one-block rise — vanilla step
# height is 0.6, so a player auto-steps a slab but not a block — and this site
# was chosen as "flat within +/-1", which is exactly the terrain that halts one.
# Flattening it here is what makes the walk repeatable instead of dependent on
# where the terrain happens to sit.
LANE_X1=227; LANE_X2=229          # the walk line is x229.0, spanning blocks 228-229
LANE_Z1=298; LANE_Z2=337          # stops at 298: z297 is the frame plane, do not touch it
LANE_FLOOR=129                    # the frame's ground level, so feet stay at y130 throughout
LANE_HEAD1=130; LANE_HEAD2=131    # two cells above the floor, cleared

fill_verified() { # x1 y1 z1 x2 y2 z2 block
  local x1=$1 y1=$2 z1=$3 x2=$4 y2=$5 z2=$6 blk=$7 out x y z bad=0 n=0
  if [ "$DRY" = "--dry-run" ]; then
    printf '  DRY  fill %s %s %s %s %s %s %s\n' "$x1" "$y1" "$z1" "$x2" "$y2" "$z2" "$blk"; return
  fi
  out=$(rc "fill $x1 $y1 $z1 $x2 $y2 $z2 $blk")
  case "$out" in
    *"not loaded"*)
      printf '  FAIL  fill %s..%s -> %s   [POSITION NOT LOADED — stand a player at the site first]\n' \
        "$z1" "$z2" "$blk"; FAIL=$((FAIL+1)); return ;;
  esac
  # Verify EVERY cell. The bug this guards against placed nothing at all and
  # let 22 downstream assertions fail against a frame that was never built.
  y=$y1
  while [ "$y" -le "$y2" ]; do
    z=$z1
    while [ "$z" -le "$z2" ]; do
      x=$x1
      while [ "$x" -le "$x2" ]; do
        n=$((n+1))
        out=$(rc "execute if block $x $y $z $blk")
        case "$out" in
          *"Test passed"*) ;;
          *) bad=$((bad+1)); [ "$bad" -le 5 ] && printf '  FAIL  %-20s -> %s   [%s]\n' \
               "$x $y $z" "$blk" "$(printf '%s' "$out" | tr -d '\n' | tail -c 50)" ;;
        esac
        x=$((x+1))
      done
      z=$((z+1))
    done
    y=$((y+1))
  done
  if [ "$bad" -eq 0 ]; then printf '  OK    %d cells -> %s\n' "$n" "$blk"
  else printf '  FAIL  %d of %d cells wrong -> %s (first 5 shown)\n' "$bad" "$n" "$blk"; FAIL=$((FAIL+bad)); fi
}

echo "== approach lane floor (x$LANE_X1-$LANE_X2, y$LANE_FLOOR, z$LANE_Z1-$LANE_Z2)"
fill_verified $LANE_X1 $LANE_FLOOR $LANE_Z1 $LANE_X2 $LANE_FLOOR $LANE_Z2 minecraft:grass_block

echo "== approach lane clearance (y$LANE_HEAD1-$LANE_HEAD2)"
fill_verified $LANE_X1 $LANE_HEAD1 $LANE_Z1 $LANE_X2 $LANE_HEAD2 $LANE_Z2 minecraft:air

echo
if [ "$DRY" = "--dry-run" ]; then echo "dry run only, nothing placed"; exit 0; fi
if [ "$FAIL" -eq 0 ]; then echo "frame and approach lane built and verified, 0 failures"; else echo "FAILURES: $FAIL — do not run the retest"; fi
exit "$FAIL"
