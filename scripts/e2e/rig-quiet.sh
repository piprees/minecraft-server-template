#!/usr/bin/env bash
# rig-quiet.sh - Put the rig into a state where a measurement means something.
#
# Purpose: every confound that has silently changed a reading here, removed and
#          then ASSERTED. A gate that only warns is a gate that gets ignored.
# Context: template-only. Called at the top of a measurement run; safe to run
#          twice. It changes world state (time, weather, gamerules), so it is
#          for the local rig and refuses to touch anything else.
# Usage:   rig-quiet.sh [--dim DIM ...]   (repeatable; defaults to the rig pair)
# Gotchas: `particleType` is NOT the particle switch. A null or absent one falls
#          through to a default dust in all three of PortalHelper.java:1460, :1502
#          and :2230. The switch is `portal.immersive.particleDensity`, which
#          PortalAperture.emittingCells floors at <= 0; an ABSENT `immersive` key
#          means enabled at 0.35, not off. `"immersive": false` is worse still —
#          it falls to PLAIN_DENSITY 1.0 every tick.
#          Two frame-ring emitters ignore density entirely: PortalHelper.java:1587
#          and ImmersiveProjector.spawnEdgeParticles:960. The second is gated only
#          on `projecting`, so the RING CANNOT BE SILENCED while the projection is
#          active. Crop it out and carry its noise floor; do not expect it static.
#          Settings come from the TARGET world's config (PortalHelper.java:841).
set -euo pipefail

CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
DIMS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dim) DIMS="$DIMS $2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$DIMS" ] || DIMS="minecraft:overworld adventure:the_amplified_reaches"

say() { printf '[rig-quiet] %s\n' "$1"; }
rcon() { docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r'; }

FAIL=0

# --- the sun must not move, and it must not rain ---------------------------
rcon "gamerule doDaylightCycle false" >/dev/null
rcon "time set 6000" >/dev/null
rcon "gamerule doWeatherCycle false" >/dev/null
rcon "weather clear" >/dev/null
for d in $DIMS; do
  t="$(rcon "execute in $d run time query daytime" | sed -n 's/.*is \([0-9]*\).*/\1/p')"
  say "$d daytime=$t"
  if [ "${t:-x}" != "6000" ]; then
    say "  REFUSED: $d reads $t, not 6000 — a moving sun changes every luma reading"
    FAIL=1
  fi
done

# --- the render queue must be idle -----------------------------------------
PENDING="$(curl -sf -m 8 http://127.0.0.1:8765/pipeline-status 2>/dev/null \
  | sed -n 's/.*"render_pending": *\([0-9]*\).*/\1/p')"
if [ -n "${PENDING:-}" ] && [ "$PENDING" -gt 0 ] 2>/dev/null; then
  say "render_pending=$PENDING — pausing HIGHRES"
  curl -sf -m 10 -X POST http://127.0.0.1:8765/render/high/pause >/dev/null 2>&1 || true
fi
CPU="$(docker stats mc --no-stream --format '{{.CPUPerc}}' 2>/dev/null || echo unknown)"
say "mc CPU=$CPU  render_pending=${PENDING:-0}"

# --- nothing of ours may be standing in frame ------------------------------
BOTS="$(rcon 'list' | tail -1)"
say "players: $BOTS"
case "$BOTS" in
  *bot*|*Bot*) say "  WARNING: a bot is online and may be in frame";;
esac

# --- every portal effect that alters a frame -------------------------------
# Checked in the DEPLOYED config the server reads, not the overlay source: the
# two differ until `./dev refresh-config` runs, and a whole session was measured
# against configs nobody had deployed.
# A consumer-added dimension lands under overlay/dimensions/, a platform one
# under dimensions/. Look in both or an e2e dim reads as "no config".
for d in $DIMS; do
  slug="${d#*:}"
  f=""
  for cand in \
    "$CONSUMER_DIR/data/config/custom-dimensions/overlay/dimensions/$slug.json" \
    "$CONSUMER_DIR/data/config/custom-dimensions/dimensions/$slug.json"; do
    [ -f "$cand" ] && { f="$cand"; break; }
  done
  if [ -z "$f" ]; then
    say "$d: NO DEPLOYED CONFIG FOUND — it cannot have been refreshed"
    FAIL=1
    continue
  fi
  read -r density light aura <<EOF
$(python3 -c "
import json
d = json.load(open('$f'))
p = (d.get('overrides') or d).get('portal') or {}
if isinstance(p, list):
    p = p[0] if p else {}
a = p.get('aura') or {}
imm = p.get('immersive')
# Absent or true means enabled at the default density; false falls to PLAIN 1.0.
if imm is None or imm is True:
    density = 0.35
elif imm is False:
    density = 1.0
else:
    density = imm.get('particleDensity', 0.35)
print(density, p.get('lightLevel'), a.get('enabled'))" 2>/dev/null)
EOF
  say "$d ($(basename "$(dirname "$(dirname "$f")")")): particleDensity=$density lightLevel=$light aura.enabled=$aura"
  case "$density" in
    0|0.0|0.00) ;;
    *) say "  REFUSED: particleDensity $density emits dust INTO the opening, which is the box every crop uses"
       say "           set portal.immersive.particleDensity to 0 in the TARGET world's config, then ./dev refresh-config"
       FAIL=1;;
  esac
  [ "$light" = "0" ] || { say "  REFUSED: lightLevel $light is a light source beside the thing being measured"; FAIL=1; }
  [ "$aura" = "False" ] || { say "  REFUSED: the aura REWRITES terrain around the portal between frames"; FAIL=1; }
done

if [ "$FAIL" -ne 0 ]; then
  say "NOT QUIET — fix the refusals above before quoting a number."
  exit 1
fi
say "quiet. Time frozen at 6000, weather clear, queue idle, no aperture dust, no portal light, no aura."
say "STILL MOVING, and no config silences it: the frame ring. ImmersiveProjector"
say "  .spawnEdgeParticles:960 dusts every frame block every 10 ticks whenever the"
say "  projection is active. Crop the ring out of any measured box."
