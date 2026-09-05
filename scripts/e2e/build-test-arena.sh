#!/usr/bin/env bash
# build-test-arena.sh - A flat, single-colour, frozen scene where the only thing
#                       that can differ between two frames is the portal.
#
# Purpose: remove every source of frame-to-frame change that is not the thing
#          under test. Terrain variety, biome tint, mobs, growth, water
#          animation and weather have each moved a reading here by more than
#          the effects being measured.
# Context: template-only, LOCAL RIG ONLY. It overwrites blocks, so it takes an
#          explicit centre and an explicit --confirm; there is no default site
#          and it refuses to guess one.
# Usage:   build-test-arena.sh --dim DIM --centre "X Y Z" --confirm
#                              [--radius 24] [--floor minecraft:gray_concrete]
#                              [--walls|--no-walls] [--height 24]
#                              [--marker "X Y Z"]   magenta ring round an aperture
#
#          Walls box the scene in so no distant terrain, sky gradient or Distant
#          Horizons tile is in frame — those move between poses and none of them
#          is the thing under test.
#
#          --marker rings an aperture in magenta concrete. That makes the
#          opening findable BY COLOUR in the image, so a crop no longer depends
#          on apertureSample being correct. It does NOT change the dimension's
#          frameBlock: doing that would stop every portal already built from the
#          old block being recognised.
# Gotchas: FREEZE LAST. `/tick freeze` stops the server building the projection,
#          so the scene must be built and the projection settled BEFORE the
#          freeze, or you photograph an empty opening. The script freezes at the
#          end and prints how to thaw.
#          Animated textures (water, fire, portals) are client-side and a freeze
#          does not stop them — site the arena away from water.
set -euo pipefail

CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
DIM=""; CENTRE=""; RADIUS=24; FLOOR="minecraft:gray_concrete"; CONFIRM=0
WALLS=1; HEIGHT=24; WALL="minecraft:gray_concrete"; MARKER=""
MARKER_BLOCK="minecraft:magenta_concrete"
while [ $# -gt 0 ]; do
  case "$1" in
    --dim) DIM="$2"; shift 2;;
    --centre) CENTRE="$2"; shift 2;;
    --radius) RADIUS="$2"; shift 2;;
    --floor) FLOOR="$2"; shift 2;;
    --walls) WALLS=1; shift;;
    --no-walls) WALLS=0; shift;;
    --height) HEIGHT="$2"; shift 2;;
    --wall-block) WALL="$2"; shift 2;;
    --marker) MARKER="$2"; shift 2;;
    --confirm) CONFIRM=1; shift;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$DIM" ] && [ -n "$CENTRE" ] || {
  echo "usage: $0 --dim DIM --centre \"X Y Z\" --confirm [--radius N] [--floor BLOCK]" >&2
  echo "  There is no default site. This overwrites blocks in a world somebody plays in." >&2
  exit 2; }
if [ "$CONFIRM" -ne 1 ]; then
  echo "REFUSED: this overwrites every block in a ${RADIUS}-block radius of $CENTRE in $DIM." >&2
  echo "  Re-run with --confirm once you are sure the site is empty." >&2
  exit 1
fi

# shellcheck disable=SC2086  # deliberate split: --centre is "X Y Z"
set -- $CENTRE
CX="$1"; CY="$2"; CZ="$3"

say() { printf '[arena] %s\n' "$1"; }
rc() { docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r'; }

X0=$((CX - RADIUS)); X1=$((CX + RADIUS))
Z0=$((CZ - RADIUS)); Z1=$((CZ + RADIUS))
CLEAR_TOP=$((CY + 40))

say "arena in $DIM, centre $CX $CY $CZ, radius $RADIUS, floor $FLOOR"

# Thaw first: a frozen server will not run fills.
rc "tick unfreeze" >/dev/null

say "stilling the world"
rc "gamerule doMobSpawning false" >/dev/null
rc "gamerule doFireTick false" >/dev/null
rc "gamerule randomTickSpeed 0" >/dev/null
rc "gamerule doDaylightCycle false" >/dev/null
rc "gamerule doWeatherCycle false" >/dev/null
rc "gamerule doPatrolSpawning false" >/dev/null
rc "gamerule doTraderSpawning false" >/dev/null
rc "time set 6000" >/dev/null
rc "weather clear" >/dev/null
rc "execute in $DIM run kill @e[type=!player,distance=..$((RADIUS * 2))]" >/dev/null

say "clearing the volume to air"
rc "execute in $DIM run fill $X0 $((CY + 1)) $Z0 $X1 $CLEAR_TOP $Z1 minecraft:air" >/dev/null

say "laying the floor"
rc "execute in $DIM run fill $X0 $CY $Z0 $X1 $CY $Z1 $FLOOR" >/dev/null
# One block below too, so a hole in the original terrain cannot show through.
rc "execute in $DIM run fill $X0 $((CY - 1)) $Z0 $X1 $((CY - 1)) $Z1 $FLOOR" >/dev/null

if [ "$WALLS" -eq 1 ]; then
  say "walling the scene in ($WALL, ${HEIGHT} high) so no distant terrain is in frame"
  WTOP=$((CY + HEIGHT))
  rc "execute in $DIM run fill $X0 $CY $Z0 $X0 $WTOP $Z1 $WALL" >/dev/null
  rc "execute in $DIM run fill $X1 $CY $Z0 $X1 $WTOP $Z1 $WALL" >/dev/null
  rc "execute in $DIM run fill $X0 $CY $Z0 $X1 $WTOP $Z0 $WALL" >/dev/null
  rc "execute in $DIM run fill $X0 $CY $Z1 $X1 $WTOP $Z1 $WALL" >/dev/null
fi

if [ -n "$MARKER" ]; then
  # shellcheck disable=SC2086  # deliberate split: --marker is "x y z"
  set -- $MARKER
  MX="$1"; MY="$2"; MZ="$3"
  say "magenta ring round the aperture at $MX $MY $MZ — the crop can key on colour"
  rc "execute in $DIM run fill $((MX - 1)) $((MY - 1)) $((MZ - 1)) $((MX + 1)) $((MY + 1)) $((MZ + 1)) $MARKER_BLOCK hollow" >/dev/null
fi

say "verifying, not assuming"
FAIL=0
for probe in "$CX $CY $CZ" "$X0 $CY $Z0" "$X1 $CY $Z1" "$CX $((CY + 1)) $CZ"; do
  # shellcheck disable=SC2086  # deliberate split: probe is "x y z"
  set -- $probe
  want="$FLOOR"; [ "$2" -gt "$CY" ] && want="minecraft:air"
  out="$(rc "execute in $DIM run execute if block $1 $2 $3 $want")"
  case "$out" in
    *"Test passed"*) printf '  OK    %-18s %s\n' "$1 $2 $3" "$want";;
    *) printf '  FAIL  %-18s %s   [%s]\n' "$1 $2 $3" "$want" "$(printf '%s' "$out" | tr -d '\n' | tail -c 60)"; FAIL=1;;
  esac
done
[ "$FAIL" -eq 0 ] || { say "arena did NOT build cleanly — do not measure here"; exit 1; }

cat <<EOF

[arena] built: ${RADIUS}-block radius, floor $FLOOR, walls $([ "$WALLS" -eq 1 ] && echo "$WALL ${HEIGHT} high" || echo "none").

[arena] Stations for a camera sweep, on the +X approach:
          $((CX - 2)) $((CY + 1)) $CZ    $((CX - 4)) $((CY + 1)) $CZ
          $((CX - 8)) $((CY + 1)) $CZ    $((CX - 16)) $((CY + 1)) $CZ

[arena] NEXT, IN THIS ORDER — the order is the whole point:
          1. build the portal frame and ignite it
          2. stand at a station and let the projection settle
             (watch destinationChunks stop moving on /state)
          3. THEN freeze:   docker exec -i mc rcon-cli "tick freeze"
          4. run the sweep
          5. thaw:          docker exec -i mc rcon-cli "tick unfreeze"

[arena] Freezing before the projection settles photographs an empty opening,
        because the server builds the projection on its tick.
EOF
