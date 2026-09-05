#!/usr/bin/env bash
# shimmer-trace.sh - Does the portal draw in every frame, or only in some of them?
#
# Purpose: the projection is skipped wholesale by four early returns in
#          ProjectionRenderer.drawOne (past the plane, frustum-culled, no clip
#          cone, no mesh). A skipped portal draws NOTHING, backdrop included,
#          so the source world shows through the opening for that frame. This
#          differences the monotonic stamp counter against the wall clock to
#          give the portal's own draw rate, beside the client's frame rate.
# Context: template-only, run against a linked local consumer with the dev
#          bridge up. `realtime.apertureStamps` reads "STAGE=calls/corners"
#          per stage; the calls figure rises once per portal per frame that
#          reaches the end of drawOne, so a gap in it IS a vanished frame.
# Usage:   shimmer-trace.sh --label L [--seconds 45] [--degrees-per-step 2]
#                          [--centre-yaw Y --amplitude D]
#          Stand where the portal fills a good part of the view. Only the look
#          is moved; the player is not. Without --amplitude the pan is a full
#          circle, which spends most of its time with the opening off screen
#          and off-screen frames are correctly not drawn -- give a centre and
#          an amplitude to keep the opening in view for the whole run.
# Gotchas: `clip` and `apertureRenderUs` refresh on the renderer's own 2s
#          sample cadence, so they lag the yaw; `meshReady`, `slabProjections`
#          and the stamp counts are live. There is no monotonic client frame
#          counter on /state, so client.fps is a one-second mean here and
#          cannot resolve a freeze shorter than that -- the stamp rate can.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BRIDGE="http://127.0.0.1:${BRIDGE_PORT:-8766}"

LABEL=""; SECONDS_TOTAL=45; STEP_DEG=2; CENTRE_YAW=""; AMPLITUDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --seconds) SECONDS_TOTAL="$2"; shift 2;;
    --degrees-per-step) STEP_DEG="$2"; shift 2;;
    --centre-yaw) CENTRE_YAW="$2"; shift 2;;
    --amplitude) AMPLITUDE="$2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$LABEL" ] || { echo "usage: $0 --label L [--seconds N]" >&2; exit 2; }

OUT="$REPO/scratchpad/shimmer"
mkdir -p "$OUT"
ROWS="$OUT/$LABEL.jsonl"
: > "$ROWS"

say() { printf '[shimmer:%s] %s\n' "$LABEL" "$1"; }

BASE="$(curl -sf -m 25 "$BRIDGE/state" 2>/dev/null || echo '{}')"
START_YAW="$(printf '%s' "$BASE" | python3 -c "
import sys, json
try: d = json.load(sys.stdin)
except Exception: d = {}
print((d.get('player') or {}).get('yaw', 0))" 2>/dev/null || echo 0)"
START_PITCH="$(printf '%s' "$BASE" | python3 -c "
import sys, json
try: d = json.load(sys.stdin)
except Exception: d = {}
print((d.get('player') or {}).get('pitch', 0))" 2>/dev/null || echo 0)"
say "panning from yaw $START_YAW pitch $START_PITCH for ${SECONDS_TOTAL}s at ${STEP_DEG} deg/step"

SAMPLE="$OUT/.sample.py"
cat > "$SAMPLE" <<'PY'
import json, sys, time

i, yaw = int(sys.argv[1]), float(sys.argv[2])
try:
    d = json.load(sys.stdin)
except Exception:
    d = {}
client = d.get('client') or {}
rt = d.get('realtime') or {}
projections = d.get('projections') or []
first = projections[0] if projections else {}
clip = first.get('clip') or {}
emitted = sum((layer.get('emitted') or 0) for layer in (clip.get('layers') or []))
quads_in = sum((layer.get('quadsIn') or 0) for layer in (clip.get('layers') or []))
stamps = {}
for part in (rt.get('apertureStamps') or '').split():
    if '=' in part and '/' in part:
        stage, value = part.split('=', 1)
        try:
            stamps[stage] = int(value.split('/')[0])
        except ValueError:
            pass
print(json.dumps({
    'i': i, 'wall': time.time(), 'yaw': yaw, 'tick': d.get('tick'),
    'fps': client.get('fps'),
    'projections': len(projections),
    'meshReady': first.get('meshReady'),
    'quads': first.get('quads'),
    'planes': clip.get('planes'),
    'camToPlane': clip.get('camToPlane'),
    'emitted': emitted, 'quadsIn': quads_in,
    'slabProjections': rt.get('slabProjections'),
    'frames': rt.get('frames'),
    'destinationChunks': rt.get('destinationChunks'),
    'renderUs': rt.get('apertureRenderUs'),
    'stampFar': stamps.get('DESTINATION_FAR'),
    'stampNear': stamps.get('NEAR_DEPTH'),
    'stampFarDepth': stamps.get('FAR_DEPTH'),
}))
PY

DEADLINE=$(( $(date +%s) + SECONDS_TOTAL ))
i=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  i=$(( i + 1 ))
  if [ -n "$AMPLITUDE" ]; then
    # Triangle wave about the centre: the opening stays in view for every
    # sample, so a frame with no draw in it is a defect and not a cull.
    yaw="$(python3 -c "
c, a, s = ${CENTRE_YAW:-$START_YAW}, $AMPLITUDE, $i * $STEP_DEG
t = s % (4 * a)
print(c + (t if t <= a else (2 * a - t if t <= 3 * a else t - 4 * a)))")"
  else
    yaw="$(python3 -c "print((($START_YAW) + ($i * $STEP_DEG) + 180) % 360 - 180)")"
  fi
  curl -sf -m 10 -X POST "$BRIDGE/input" \
    -d "{\"look\":{\"yaw\":$yaw,\"pitch\":$START_PITCH}}" >/dev/null 2>&1 || true
  curl -sf -m 15 "$BRIDGE/state" 2>/dev/null \
    | python3 "$SAMPLE" "$i" "$yaw" >> "$ROWS" 2>/dev/null || true
done

say "$(wc -l < "$ROWS" | tr -d ' ') samples over ${SECONDS_TOTAL}s -> $ROWS"
python3 "$REPO/scripts/e2e/shimmer-check.py" "$ROWS"
