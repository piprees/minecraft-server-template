#!/usr/bin/env bash
# fps-motion.sh - Sample framerate DURING continuous camera motion, not between poses.
#
# Purpose: the camera sweep jumps the look then settles before reading, so it
#          measures many stationary poses. A player pans continuously, which
#          never lets the projection finish rebuilding. That is the condition
#          the maintainer plays in and the one no instrument here has sampled.
# Context: template-only. A mean fps is not the complaint — a stall is, and
#          `client.getCurrentFps()` is ALREADY a one-second mean, so it smooths
#          away exactly what is felt. This differences the monotonic frame
#          counter against the wall clock instead, and reports the worst
#          interval and the seconds spent under 10 fps.
# Usage:   fps-motion.sh --label L [--seconds 60] [--degrees-per-step 3]
#          Run it where the portal fills a good part of the view; it does not
#          move the player, only the look.
# Gotchas: /state is the sampler AND the clock, so the sample rate is itself a
#          symptom — a run that yields few rows over many seconds is evidence,
#          not a broken script. Every wait is capped; nothing streams.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BRIDGE="http://127.0.0.1:${BRIDGE_PORT:-8766}"

LABEL=""; SECONDS_TOTAL=60; STEP_DEG=3
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --seconds) SECONDS_TOTAL="$2"; shift 2;;
    --degrees-per-step) STEP_DEG="$2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$LABEL" ] || { echo "usage: $0 --label L [--seconds N]" >&2; exit 2; }

OUT="$REPO/scratchpad/fps-motion"
mkdir -p "$OUT"
ROWS="$OUT/$LABEL.jsonl"
: > "$ROWS"

say() { printf '[fps-motion:%s] %s\n' "$LABEL" "$1"; }

QUEUE="$(curl -sf -m 8 http://127.0.0.1:8765/pipeline-status 2>/dev/null || echo '{}')"
PENDING="$(printf '%s' "$QUEUE" | sed -n 's/.*"render_pending": *\([0-9]*\).*/\1/p')"
if [ -n "${PENDING:-}" ] && [ "$PENDING" -gt 0 ] 2>/dev/null; then
  say "render_pending=$PENDING — pausing HIGHRES; a busy box invalidates every number here"
  curl -sf -m 10 -X POST http://127.0.0.1:8765/render/high/pause >/dev/null 2>&1 || true
fi
say "mc CPU: $(docker stats mc --no-stream --format '{{.CPUPerc}}' 2>/dev/null || echo unknown)"

BASE="$(curl -sf -m 25 "$BRIDGE/state" 2>/dev/null || echo '{}')"
START_YAW="$(printf '%s' "$BASE" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
p=d.get('player') or {}
print(p.get('yaw', 0))" 2>/dev/null || echo 0)"
START_PITCH="$(printf '%s' "$BASE" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
p=d.get('player') or {}
print(p.get('pitch', 0))" 2>/dev/null || echo 0)"
say "panning from yaw $START_YAW pitch $START_PITCH for ${SECONDS_TOTAL}s at ${STEP_DEG} deg/step"

DEADLINE=$(( $(date +%s) + SECONDS_TOTAL ))
i=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  i=$(( i + 1 ))
  yaw="$(python3 -c "print((($START_YAW) + ($i * $STEP_DEG) + 180) % 360 - 180)")"
  # No settle: the whole point is to read while the rebuild is still in flight.
  curl -sf -m 10 -X POST "$BRIDGE/input" \
    -d "{\"look\":{\"yaw\":$yaw,\"pitch\":$START_PITCH}}" >/dev/null 2>&1 || true
  st="$(curl -sf -m 15 "$BRIDGE/state" 2>/dev/null || echo '{}')"
  printf '%s\n' "$st" | python3 -c "
import sys,json,time
try: d=json.load(sys.stdin)
except Exception: d={}
c=d.get('client') or {}
r=d.get('realtime') or {}
print(json.dumps({'i':$i,'wall':time.time(),'yaw':$yaw,
  'fps':c.get('fps'),'frames':c.get('frames'),'tick':d.get('tick'),
  'renderUs':r.get('apertureRenderUs'),'destinationChunks':r.get('destinationChunks')}))" \
    >> "$ROWS" 2>/dev/null || true
done

say "$(wc -l < "$ROWS" | tr -d ' ') samples over ${SECONDS_TOTAL}s -> $ROWS"
python3 - "$ROWS" <<'PY'
import json, statistics as st, sys
rows = []
for line in open(sys.argv[1]):
    line = line.strip()
    if line:
        try:
            rows.append(json.loads(line))
        except ValueError:
            pass
# client.getCurrentFps() is ALREADY a one-second mean, so it smooths away the
# stalls that are actually felt. The frames counter is monotonic: differencing
# it against the wall clock gives the true rate over each interval.
pairs = [(r["wall"], r["frames"]) for r in rows
         if isinstance(r.get("wall"), (int, float))
         and isinstance(r.get("frames"), (int, float))]
if len(pairs) < 2:
    print("  fewer than two frame-counter samples — nothing to difference.")
    fps = [r["fps"] for r in rows if isinstance(r.get("fps"), (int, float))]
    if fps:
        print(f"  reported fps only: n={len(fps)} mean={st.fmean(fps):.2f} min={min(fps)}")
    raise SystemExit
pairs.sort()
rates, worst = [], None
for (w0, f0), (w1, f1) in zip(pairs, pairs[1:]):
    dt, df = w1 - w0, f1 - f0
    if dt <= 0 or df < 0:
        continue
    r = df / dt
    rates.append(r)
    if worst is None or r < worst[0]:
        worst = (r, dt, w0)
if not rates:
    print("  no usable intervals")
    raise SystemExit
srt = sorted(rates)
p5 = srt[max(0, int(len(srt) * 0.05) - 1)]
span = pairs[-1][0] - pairs[0][0]
frames_total = pairs[-1][1] - pairs[0][1]
print(f"  TRUE rate from the frame counter over {len(rates)} intervals, {span:.1f}s:")
print(f"    mean={frames_total/span:6.2f}  median={st.median(rates):6.2f}"
      f"  p5={p5:6.2f}  worst={min(rates):6.2f}")
bad = sum(1 for r in rates if r < 10)
lost = sum(dt for (w0, f0), (w1, f1) in zip(pairs, pairs[1:])
           for dt in [w1 - w0] if dt > 0 and (f1 - f0) / dt < 10)
print(f"    intervals under 10 fps: {bad} ({100.0*bad/len(rates):.1f}%), "
      f"{lost:.1f}s of {span:.1f}s spent there   <- the felt number")
print(f"    worst interval {worst[0]:.2f} fps over {worst[1]:.2f}s")
reported = [r["fps"] for r in rows if isinstance(r.get("fps"), (int, float))]
if reported:
    print(f"  client.getCurrentFps() said: mean={st.fmean(reported):.2f} "
          f"min={min(reported)}  <- a 1s mean; it cannot show a short freeze")
PY
