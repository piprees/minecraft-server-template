#!/usr/bin/env bash
#
# portal-aperture.sh — does an immersive portal read as an OPENING?
#
# Purpose:  End-to-end check of the portal aperture effects against a real
#           client. Builds a scratch portal, lights it through Pip's own
#           client, and asserts the three properties the effects exist for:
#           the opening is never filled, the frame is lit, and the light and
#           colour reaching it come from the dimension on the other side.
#           Then walks through it, because a gateway you cannot use is a
#           picture.
#
# Context:  Local dev stack only (~/Projects/elfydd), linked to a platform
#           checkout via `./dev link`. Needs Pip's client CONNECTED — every
#           assertion that involves seeing something is driven through it.
#           No Carpet bots: this is tested by hand, through the real client.
#
# Usage:    ./scripts/e2e/portal-aperture.sh [--keep]
#             --keep   leave the scratch portal standing for a look
#
# Gotchas:  - Screenshots land in $E2E_OUT (default /tmp/c0-e2e/aperture)
#             and are the point;
#             read them, the script can only prove they were taken.
#           - The scratch site is deliberately far from Pip's own portals
#             and at POSITIVE coordinates: a block's centre is block + 0.5,
#             and getting that wrong on a negative coordinate has walked
#             people through portals they meant to stand beside.
#           - Fill share is read from the mod's own `aperture:` heartbeat,
#             which is DEBUG. A silent run means log level, not a dead pass.
#
set -euo pipefail

CONSUMER="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
OUT="${E2E_OUT:-/tmp/c0-e2e/aperture}"
PLAYER="${PLAYER:-}"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

# Scratch site: positive coordinates, far from anything hand-built.
SX=2048; SY=90; SZ=2048
# the_basalt_spires: basalt frame, flint and steel, standard shape.
FRAME="minecraft:basalt"
IGNITER="minecraft:flint_and_steel"

PASSES=0
FAILURES=0

pass() { PASSES=$((PASSES + 1)); printf 'PASS  %s\n' "$1"; }
fail() { FAILURES=$((FAILURES + 1)); printf 'FAIL  %s\n' "$1"; }
info() { printf '      %s\n' "$1"; }
check() { if [ "$1" = "0" ]; then pass "$2"; else fail "$2"; fi; }

rcon() { docker exec -i mc rcon-cli "$*" 2>&1; }

mkdir -p "$OUT"
cd "$CONSUMER"

printf '\n== preconditions ==\n'

health="$(docker inspect mc --format '{{.State.Health.Status}}' 2>/dev/null || echo missing)"
restarts="$(docker inspect mc --format '{{.RestartCount}}' 2>/dev/null || echo -1)"
if [ "$health" = "healthy" ] && [ "$restarts" = "0" ]; then
    pass "mc is healthy with no restarts"
else
    fail "mc health=$health restarts=$restarts"
fi

if docker exec mc sh -c 'unzip -l /data/mods/customdimensions-*.jar' 2>/dev/null \
        | grep -q 'PortalAperture.class'; then
    pass "the served jar carries the aperture code under test"
else
    fail "the served jar has no PortalAperture — run ./dev up first"
fi

if [ -z "$PLAYER" ]; then
    PLAYER="$(rcon list | sed -n 's/.*players online: //p' | tr -d '\r' | cut -d, -f1 | tr -d ' ')"
fi
if [ -n "$PLAYER" ]; then
    pass "client connected as $PLAYER"
else
    fail "no player online — launch the client (./dev launch) and join before running this"
    printf '\n%s passed, %s failed\n' "$PASSES" "$FAILURES"
    exit 1
fi

# Every log assertion below reads only lines written after this point.
MARK="$(docker logs mc 2>&1 | wc -l | tr -d ' ')"
since() { docker logs mc 2>&1 | tail -n "+$((MARK + 1))"; }

printf '\n== build and light a scratch portal ==\n'

rcon "execute in minecraft:overworld run forceload add $SX $SZ" >/dev/null
# A standard frame: 4 wide, 5 tall, hollow 2x3 interior, plane along X.
rcon "execute in minecraft:overworld run fill $((SX-1)) $SY $SZ $((SX+2)) $((SY+4)) $SZ $FRAME" >/dev/null
rcon "execute in minecraft:overworld run fill $SX $((SY+1)) $SZ $((SX+1)) $((SY+3)) $SZ minecraft:air" >/dev/null
rcon "execute in minecraft:overworld run fill $((SX-2)) $((SY-1)) $((SZ-4)) $((SX+3)) $((SY-1)) $((SZ+1)) minecraft:smooth_stone" >/dev/null
interior="$(rcon "execute in minecraft:overworld if block $SX $((SY+1)) $SZ minecraft:air")"
case "$interior" in
    *"Test passed"*) pass "frame built with an empty interior" ;;
    *) fail "frame interior is not air: $interior" ;;
esac

rcon "give $PLAYER $IGNITER" >/dev/null
rcon "item replace entity $PLAYER hotbar.0 with $IGNITER" >/dev/null
# Stand back from the plane, looking at the bottom-left interior cell.
rcon "tp $PLAYER $((SX)).5 $SY $((SZ-3)).5 0 25" >/dev/null
sleep 2

./dev screenshot --out "$OUT/1-unlit.png" >/dev/null 2>&1 || true
[ -s "$OUT/1-unlit.png" ] && pass "screenshot of the unlit frame" || fail "no unlit screenshot"

./dev input focus >/dev/null 2>&1 || true
./dev input rightclick 900 500 >/dev/null 2>&1 || true
sleep 3

lit=1
for _ in 1 2 3 4 5; do
    if [ "$(rcon "execute in minecraft:overworld if block $SX $((SY+1)) $SZ minecraft:air" \
            | grep -c 'Test passed')" != "0" ] \
        && since | grep -q "aperture: emitted"; then
        lit=0
        break
    fi
    sleep 4
done
check "$lit" "portal lit through the client, and emitting"
if [ "$lit" != "0" ]; then
    info "no 'aperture:' heartbeat — the ignition click may have missed the frame"
fi

printf '\n== the opening reads as an opening ==\n'

# Rule: NOTHING in the frame. The effects are the whole visible portal.
empty="$(rcon "execute in minecraft:overworld if block $SX $((SY+1)) $SZ minecraft:nether_portal")"
case "$empty" in
    *"Test failed"*) pass "no portal blocks in the frame — the frame is the thing" ;;
    *) fail "a portal block stands in the interior: $empty" ;;
esac

fillline="$(since | grep 'aperture: emitted' | tail -1 || true)"
if [ -n "$fillline" ]; then
    info "$(printf '%s' "$fillline" | sed 's/.*aperture: /aperture: /')"
    share="$(printf '%s' "$fillline" | sed -n 's/.*(\([0-9]*\)%).*/\1/p')"
    if [ -n "$share" ] && [ "$share" -lt 50 ]; then
        pass "fill share ${share}% — under half the plane, so it reads through"
    else
        fail "fill share ${share:-unknown}% — an opening this full reads as a surface"
    fi
else
    fail "no aperture fill heartbeat (is the mod's DEBUG logging on?)"
fi

if since | grep -q "immersive: edge particles"; then
    pass "the frame ring is lit"
    info "$(since | grep 'immersive: edge particles' | tail -1 | sed 's/.*immersive: /immersive: /')"
else
    fail "no edge particles — the frame is not being drawn"
fi

printf '\n== the light comes from the far side ==\n'

glow="$(since | grep 'immersive: destination glow' | tail -1 || true)"
if [ -n "$glow" ]; then
    pass "the destination's light and colour reached the opening"
    info "$(printf '%s' "$glow" | sed 's/.*immersive: /immersive: /')"
else
    fail "no destination glow sampled — the opening is showing its configured colour only"
fi

./dev screenshot --out "$OUT/2-lit.png" >/dev/null 2>&1 || true
[ -s "$OUT/2-lit.png" ] && pass "screenshot of the lit opening" || fail "no lit screenshot"

printf '\n== it is a gateway, not a picture ==\n'

before="$(rcon "execute as $PLAYER run data get entity $PLAYER Dimension")"
./dev input focus >/dev/null 2>&1 || true
./dev input hold w 3 >/dev/null 2>&1 || true
sleep 4
after="$(rcon "execute as $PLAYER run data get entity $PLAYER Dimension")"
if [ "$before" != "$after" ]; then
    pass "walked through into $(printf '%s' "$after" | sed -n 's/.*: *//p')"
else
    fail "still in the same dimension after walking forward: $after"
fi

./dev screenshot --out "$OUT/3-arrival.png" >/dev/null 2>&1 || true
[ -s "$OUT/3-arrival.png" ] && pass "screenshot of the arrival" || fail "no arrival screenshot"

if [ "$KEEP" = "0" ]; then
    printf '\n== clean up ==\n'
    rcon "execute in minecraft:overworld run fill $((SX-1)) $SY $SZ $((SX+2)) $((SY+4)) $SZ minecraft:air" >/dev/null
    rcon "execute in minecraft:overworld run forceload remove $SX $SZ" >/dev/null
    info "scratch frame removed"
fi

printf '\nScreenshots: %s\n' "$OUT"
printf '%s passed, %s failed\n' "$PASSES" "$FAILURES"
[ "$FAILURES" = "0" ]
