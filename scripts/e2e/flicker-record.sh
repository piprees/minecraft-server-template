#!/usr/bin/env bash
# flicker-record.sh - Capture the client as consecutive screen frames, fast
#                     enough that a per-frame portal flicker becomes a number.
#
# Purpose: the dev bridge's /screenshot is a ~1s round trip, so a burst of it
#          is a 1 Hz series and can only rule out a slow vanish. This drives
#          screencapture over a cropped screen region instead and reaches
#          ~11 frames/s, which is the client's own frame rate. Analysis is
#          flicker-check.py: run lengths, and whether the opening goes EMPTY
#          or merely changes CONTENT.
# Context: template-only, macOS only. Reads the client window's live bounds, so
#          the window may sit anywhere. Boxes are given in GAME IMAGE pixels
#          (the retina framebuffer, twice the screen points) to match the boxes
#          kdiff.py and lumastd.py take. It never touches the server, the
#          containers or the client -- it only photographs the screen.
# Usage:   flicker-record.sh --label L --box name=x0,y0,x1,y1 [--box ...]
#                            [--seconds 10] [--mode burst|video]
#                            [--aperture NAME] [--origin X,Y] [--scale 2]
#                            [--out DIR] [--allow-background]
#          flicker-record.sh --self-test [--self-test-defect a|b]
#
#          The FIRST --box is the aperture unless --aperture names another;
#          every other box is a control and must cover something that cannot
#          move, so a moving control convicts the capture rather than the game.
#          --self-test runs the whole pipeline against a synthetic window with
#          a known defect and no Minecraft at all.
# Gotchas: screencapture -R takes a SCREEN region, not a window, so anything
#          stacked over the game lands in the frames. The frontmost process is
#          asserted before and after the run and recorded in meta.json; use
#          --allow-background only when you know what is in front.
#          -R is given in points and writes at 2x, so image pixels map 1:1
#          into the captured PNG; the union rect is rounded to even image
#          pixels or every box shifts by half a pixel.
#          Video mode needs ffmpeg to become frames and there is none on this
#          machine -- it records the .mov and says so rather than pretending.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PRISM_MAIN="org.prismlauncher.EntryPoint"

LABEL=""; SECONDS_TOTAL=10; MODE="burst"; APERTURE=""; ORIGIN=""; SCALE=2
OUT_ROOT="$REPO/scratchpad/flicker"; SELF_TEST=0; DEFECT="a"; ALLOW_BG=0
PERIOD_MS=250
BOX_SPECS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2;;
    --box) BOX_SPECS+=("$2"); shift 2;;
    --seconds) SECONDS_TOTAL="$2"; shift 2;;
    --mode) MODE="$2"; shift 2;;
    --aperture) APERTURE="$2"; shift 2;;
    --origin) ORIGIN="$2"; shift 2;;
    --scale) SCALE="$2"; shift 2;;
    --out) OUT_ROOT="$2"; shift 2;;
    --allow-background) ALLOW_BG=1; shift;;
    --self-test) SELF_TEST=1; shift;;
    --self-test-defect) DEFECT="$2"; shift 2;;
    --self-test-period-ms) PERIOD_MS="$2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done

say() { printf '[flicker:%s] %s\n' "${LABEL:-selftest}" "$1"; }
die() { printf '[flicker] %s\n' "$1" >&2; exit 1; }

frontmost() {
  osascript -e 'tell application "System Events" to get name of first process whose frontmost is true' 2>/dev/null || echo unknown
}

# ---------------------------------------------------------------- self-test --
# A tkinter window with a known defect: the left half alternates on a fixed
# period, the right half never changes. Defect a flips detail against a flat
# fill (the projection vanishing); defect b flips two detailed patterns (the
# projection present and showing something else).
if [ "$SELF_TEST" = 1 ]; then
  LABEL="selftest-$DEFECT"
  ORIGIN="120,120"
  BOX_SPECS=("opening=20,20,380,580" "still=420,20,780,580")
  APERTURE="opening"
  [ "$SECONDS_TOTAL" = 10 ] && SECONDS_TOTAL=8
fi

[ -n "$LABEL" ] || die "usage: $0 --label L --box name=x0,y0,x1,y1 [...]"
[ ${#BOX_SPECS[@]} -gt 0 ] || die "at least one --box is required"
case "$MODE" in burst|video) ;; *) die "--mode is burst or video";; esac

RUN="$OUT_ROOT/$LABEL"
rm -rf "$RUN"; mkdir -p "$RUN/frames"

PATTERN="$RUN/pattern.py"
if [ "$SELF_TEST" = 1 ]; then
  cat > "$PATTERN" <<'PY'
import random, sys, tkinter

defect, period, seconds, x, y = (sys.argv[1], int(sys.argv[2]),
                                 float(sys.argv[3]), int(sys.argv[4]),
                                 int(sys.argv[5]))
W, H = 400, 300
root = tkinter.Tk()
root.geometry(f"{W}x{H}+{x}+{y}")
root.attributes("-topmost", True)
canvas = tkinter.Canvas(root, width=W, height=H, highlightthickness=0)
canvas.pack()


def blocks(seed, x0, x1, palette):
    rng = random.Random(seed)
    for _ in range(400):
        bx, by = rng.randrange(x0, x1 - 12), rng.randrange(0, H - 12)
        canvas.create_rectangle(bx, by, bx + 12, by + 12,
                                fill=rng.choice(palette), outline="")


state = {"on": True}


def draw():
    canvas.delete("all")
    canvas.create_rectangle(0, 0, W, H, fill="#101014", outline="")
    # The right half is the control: identical in every frame.
    blocks(99, 205, W, ["#3a6ea5", "#8fbf5f", "#c9a227", "#7d4f9c"])
    if state["on"]:
        blocks(7, 0, 195, ["#4f8fd0", "#6fbf7f", "#d9b237", "#a05fbc"])
    elif defect == "a":
        canvas.create_rectangle(0, 0, 195, H, fill="#23303c", outline="")
    else:
        blocks(31, 0, 195, ["#d05f4f", "#bf7f6f", "#37b2d9", "#5fbca0"])
    state["on"] = not state["on"]
    root.after(period, draw)


draw()
root.after(int(seconds * 1000), root.destroy)
root.mainloop()
PY
  IFS=, read -r SX SY <<EOF
$ORIGIN
EOF
  python3 "$PATTERN" "$DEFECT" "$PERIOD_MS" "$(( SECONDS_TOTAL + 4 ))" "$SX" "$SY" &
  PATTERN_PID=$!
  trap 'kill "$PATTERN_PID" 2>/dev/null || true' EXIT
  sleep 2
  say "synthetic window up (defect $DEFECT, flip every ${PERIOD_MS}ms)"
fi

# ------------------------------------------------------------ window bounds --
if [ -z "$ORIGIN" ]; then
  PID="$(pgrep -f "$PRISM_MAIN" | head -1 || true)"
  [ -n "$PID" ] || die "no $PRISM_MAIN process — launch the client, or pass --origin X,Y"
  BOUNDS="$(osascript -e "tell application \"System Events\" to tell (first process whose unix id is $PID) to get position of window 1" 2>/dev/null || true)"
  [ -n "$BOUNDS" ] || die "could not read window 1 of pid $PID — grant Accessibility, or pass --origin X,Y"
  ORIGIN="$(printf '%s' "$BOUNDS" | tr -d ' ')"
  say "client pid $PID window origin $ORIGIN"
fi

# -------------------------------------------------- geometry, in image pixels --
# The union of every box is what gets captured, so all boxes come from one
# instant; the analyser crops them back out at the offsets written here.
GEOM="$(python3 - "$ORIGIN" "$SCALE" "$APERTURE" "${BOX_SPECS[@]}" <<'PY'
import json, sys

origin, scale, aperture, specs = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4:]
ox, oy = (int(float(v)) for v in origin.split(","))
boxes = []
for spec in specs:
    name, _, rect = spec.partition("=")
    parts = [int(v) for v in rect.split(",")]
    if not name or len(parts) != 4:
        sys.exit(f"bad box {spec!r}: want name=x0,y0,x1,y1")
    boxes.append((name, parts))
if not aperture:
    aperture = boxes[0][0]
if aperture not in [n for n, _ in boxes]:
    sys.exit(f"--aperture {aperture} is not one of the boxes")

# Even image pixels only: -R takes points, so an odd edge lands half a pixel out.
x0 = min(b[1][0] for b in boxes) // 2 * 2
y0 = min(b[1][1] for b in boxes) // 2 * 2
x1 = -(-max(b[1][2] for b in boxes) // 2) * 2
y1 = -(-max(b[1][3] for b in boxes) // 2) * 2
print(json.dumps({
    "rect": f"{ox + x0 // scale},{oy + y0 // scale},{(x1 - x0) // scale},{(y1 - y0) // scale}",
    "union": [x0, y0, x1, y1],
    "origin": [ox, oy],
    "boxes": [{"name": n, "role": "aperture" if n == aperture else "control",
               "rect": [p[0] - x0, p[1] - y0, p[2] - x0, p[3] - y0]}
              for n, p in boxes],
}))
PY
)" || die "box geometry rejected"
RECT="$(printf '%s' "$GEOM" | python3 -c 'import json,sys; print(json.load(sys.stdin)["rect"])')"
say "capturing screen rect $RECT (x,y,w,h in points) for ${SECONDS_TOTAL}s"

FRONT_BEFORE="$(frontmost)"
if [ "$SELF_TEST" = 0 ] && [ "$ALLOW_BG" = 0 ]; then
  case "$FRONT_BEFORE" in
    *java*|*Prism*|*Minecraft*|*prism*) ;;
    *) die "frontmost app is '$FRONT_BEFORE', not the game — -R photographs whatever is on top. Focus the client, or pass --allow-background.";;
  esac
fi

# ------------------------------------------------------------------ capture --
if [ "$MODE" = video ]; then
  /usr/sbin/screencapture -v -V "$SECONDS_TOTAL" -x -R "$RECT" "$RUN/capture.mov"
  say "recorded $RUN/capture.mov"
  if command -v ffmpeg >/dev/null 2>&1; then
    ffmpeg -nostdin -loglevel error -i "$RUN/capture.mov" \
      -vsync 0 "$RUN/frames/%06d.png"
    say "extracted $(find "$RUN/frames" -name '*.png' | wc -l | tr -d ' ') frames"
    die "video frame timestamps are not wired through to meta.json yet — use --mode burst"
  fi
  die "ffmpeg is NOT installed, so this .mov cannot become frames. Install it (brew install ffmpeg) or use --mode burst, which needs nothing and reaches ~11 frames/s."
fi

STAMPS="$RUN/frames.tsv"
: > "$STAMPS"
DEADLINE="$(python3 -c "import time; print(time.time() + $SECONDS_TOTAL)")"
i=0
while [ "$(python3 -c "import time; print(1 if time.time() < $DEADLINE else 0)")" = 1 ]; do
  i=$(( i + 1 ))
  FILE="$(printf 'frames/%06d.png' "$i")"
  /usr/sbin/screencapture -x -o -R "$RECT" -t png "$RUN/$FILE" 2>/dev/null || break
  printf '%s\t%s\t%s\n' "$i" "$(python3 -c 'import time; print(repr(time.time()))')" "$FILE" >> "$STAMPS"
done
FRONT_AFTER="$(frontmost)"

python3 - "$RUN" "$LABEL" "$MODE" "$SECONDS_TOTAL" "$SCALE" \
  "$FRONT_BEFORE" "$FRONT_AFTER" "$GEOM" <<'PY'
import json, sys
from pathlib import Path

run, label, mode, seconds, scale, before, after, geom = sys.argv[1:9]
run = Path(run)
geom = json.loads(geom)
frames = []
for line in (run / "frames.tsv").read_text().splitlines():
    index, wall, file = line.split("\t")
    if (run / file).exists():
        frames.append({"i": int(index), "wall": float(wall), "file": file})
(run / "meta.json").write_text(json.dumps({
    "label": label, "mode": mode, "seconds": float(seconds), "scale": int(scale),
    "origin": geom["origin"], "union": geom["union"], "rect": geom["rect"],
    "frontmostBefore": before, "frontmostAfter": after,
    "boxes": geom["boxes"], "frames": frames,
}, indent=2))
print(f"[flicker:{label}] {len(frames)} frames -> {run}/meta.json")
PY

python3 "$REPO/scripts/e2e/flicker-check.py" "$RUN"
