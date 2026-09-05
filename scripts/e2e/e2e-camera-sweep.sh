#!/usr/bin/env bash
# e2e-camera-sweep.sh - Pan the camera from a FIXED eye position and capture every frame.
#
# Purpose: catch view-dependent defects. From one eye position the opening is a
#          window onto a fixed place, so its contents may NOT change when only
#          yaw or pitch changes. Anything that does is a defect by definition,
#          with no judgement call. Also records fps per frame, because panning
#          is what forces the projection to rebuild and a static reading cannot
#          see that cost.
# Context: template-only. Every earlier instrument here reduced ONE camera pose
#          to a scalar, which cannot represent a difference between poses. That
#          is why a whole session of green numbers sat alongside a visibly
#          broken portal.
# Usage:   e2e-camera-sweep.sh --label L --dim DIM --portal "X Y Z" [--facing YAW]
#                              [--distances "2 4 8 16"] [--yaw-span 40] [--yaw-step 10]
#          --facing is the yaw that looks AT the portal from in front of it.
# Gotchas: a server tp's rotation does not stick, so the bridge sets the look.
#          /screenshot starves /state, so each frame reads state THEN shoots.
#          Every wait is capped; nothing here streams.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$REPO/.." && pwd)"
CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
BRIDGE="http://127.0.0.1:${BRIDGE_PORT:-8766}"
PLAYER="${PLAYER:-FLUXXINATED}"

LABEL=""; DIM=""; PORTAL=""; FACING=""
DISTANCES="2 4 8 16"; YAW_SPAN=40; YAW_STEP=10; PITCH_SPAN=20; PITCH_STEP=10
while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --dim) DIM="$2"; shift 2;;
    --portal) PORTAL="$2"; shift 2;;
    --facing) FACING="$2"; shift 2;;
    --distances) DISTANCES="$2"; shift 2;;
    --yaw-span) YAW_SPAN="$2"; shift 2;;
    --yaw-step) YAW_STEP="$2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$LABEL" ] && [ -n "$DIM" ] && [ -n "$PORTAL" ] && [ -n "$FACING" ] || {
  echo "usage: $0 --label L --dim DIM --portal \"X Y Z\" --facing YAW" >&2; exit 2; }

OUT="$REPO/scratchpad/sweeps/$LABEL"
mkdir -p "$OUT"
FRAMES="$OUT/frames.jsonl"
: > "$FRAMES"

say() { printf '[sweep:%s] %s\n' "$LABEL" "$1"; }
rcon() { ( cd "$CONSUMER_DIR" && ./dev rcon "$1" ) >/dev/null 2>&1 || true; }

# shellcheck disable=SC2086  # deliberate split: --portal is "X Y Z"
set -- $PORTAL
PX="$1"; PY="$2"; PZ="$3"

# --- gates: never take a number on a machine that is busy ------------------
QUEUE="$(curl -sf -m 8 http://127.0.0.1:8765/pipeline-status 2>/dev/null || echo '{}')"
PENDING="$(printf '%s' "$QUEUE" | sed -n 's/.*"render_pending": *\([0-9]*\).*/\1/p')"
if [ -n "${PENDING:-}" ] && [ "$PENDING" -gt 0 ] 2>/dev/null; then
  say "render_pending=$PENDING — pausing HIGHRES before measuring"
  curl -sf -m 10 -X POST http://127.0.0.1:8765/render/high/pause >/dev/null 2>&1 || true
fi
MCCPU="$(docker stats mc --no-stream --format '{{.CPUPerc}}' 2>/dev/null || echo unknown)"
say "mc CPU at start: $MCCPU (a busy box invalidates every fps number below)"

if ! curl -sf -m 15 "$BRIDGE/state" >/dev/null 2>&1; then
  echo "sweep: $BRIDGE/state does not answer — no client, no sweep." >&2
  echo "  Recover with ./scratchpad/rig-clean-start.sh, then re-run." >&2
  exit 1
fi

# --- the eye positions ------------------------------------------------------
# Stations sit on the portal's facing axis, both sides. FACING is the yaw that
# looks at the portal from the FRONT, so the front station offset is the
# opposite bearing. Minecraft: yaw 0 = +Z, -90 = +X.
rad() { python3 -c "import math;print(math.radians($1))"; }
station_xz() { # distance signed -> "x z"
  python3 -c "
import math
yaw=math.radians($FACING); d=$1
# unit vector the camera looks along at this yaw
lx=-math.sin(yaw); lz=math.cos(yaw)
print(f'{$PX - lx*d:.2f} {$PZ - lz*d:.2f}')"
}

frame_no=0
capture() { # station_label eye_x eye_z yaw pitch dist side
  frame_no=$((frame_no + 1))
  local tag; tag="$(printf '%s-%03d' "$1" "$frame_no")"
  curl -sf -m 20 -X POST "$BRIDGE/input" \
    -d "{\"look\":{\"yaw\":$4,\"pitch\":$5}}" >/dev/null 2>&1 || true
  # Longer than ProjectionRenderer.SAMPLE_INTERVAL_MS, or apertureSample — and
  # with it every ndc and reconstruction below — is the previous pose's.
  sleep 2.5
  local st; st="$(curl -sf -m 25 "$BRIDGE/state" 2>/dev/null || echo '{}')"
  printf '%s\n' "$st" | python3 -c "
import sys,json,time
try: d=json.load(sys.stdin)
except Exception: d={}
c=d.get('client') or {}
r=d.get('realtime') or {}
s=r.get('apertureSample') or {}
row={'tag':'$tag','station':'$1','eyeX':$2,'eyeZ':$3,'yaw':$4,'pitch':$5,
     'dist':$6,'side':'$7','wall':time.time(),
     'tick':d.get('tick'),'fps':c.get('fps'),'renderUs':r.get('renderUs'),
     'destinationChunks':r.get('destinationChunks'),
     'apertureEntities':r.get('apertureEntities'),
     'projections':len(d.get('projections') or []),
     'frames':c.get('frames'),
     'ndcX':s.get('ndcX'),'ndcY':s.get('ndcY'),
     'windowZ':s.get('windowZ'),'reconDistance':s.get('distance'),
     'reconAt':s.get('at'),'farDistance':s.get('farDistance')}
print(json.dumps(row))" >> "$FRAMES" 2>/dev/null || true
  curl -sf -m 45 -X POST "$BRIDGE/screenshot" \
    -d "{\"path\":\"$OUT/$tag.png\"}" >/dev/null 2>&1 || say "shot failed: $tag"
}

for d in $DISTANCES; do
  for side in front back; do
    if [ "$side" = "front" ]; then sd="$d"; look="$FACING"; else sd="-$d"; look="$(python3 -c "print((($FACING)+360)%360-180)")"; fi
    # shellcheck disable=SC2046  # deliberate split: station_xz prints "x z"
    set -- $(station_xz "$sd")
    ex="$1"; ez="$2"
    say "station ${side} ${d}m at $ex, $PY, $ez looking $look"
    rcon "say [sweep] camera test: moving you to $ex $PY $ez, back afterwards."
    rcon "execute in $DIM run tp $PLAYER $ex $PY $ez $look 0"
    sleep 3
    # yaw sweep at fixed eye position — the decisive one
    y=$(( -YAW_SPAN ))
    while [ "$y" -le "$YAW_SPAN" ]; do
      capture "${side}${d}-yaw" "$ex" "$ez" "$(python3 -c "print((($look)+($y)+180)%360-180)")" 0 "$d" "$side"
      y=$(( y + YAW_STEP ))
    done
    # pitch sweep at the same eye position
    p=$(( -PITCH_SPAN ))
    while [ "$p" -le "$PITCH_SPAN" ]; do
      capture "${side}${d}-pitch" "$ex" "$ez" "$look" "$p" "$d" "$side"
      p=$(( p + PITCH_STEP ))
    done
  done
done

say "captured $frame_no frames -> $OUT"
say "now run: python3 $REPO/scripts/e2e/camera-sweep-check.py $OUT"
