#!/usr/bin/env python3
"""shadow-gradient.py - Is there a shadow across the projection, at any pose?

Purpose: score against "there is no shadow", not "the shadow tracks the world".
         Sweep YAW ONLY, from ONE fixed eye. From a fixed eye the set of rays
         through the aperture is fixed by eye and frame geometry, so rotating the
         camera cannot change what a real window shows — only which pixels it
         lands on. Any ramp that moves with yaw alone is therefore shading tied
         to view direction, with no parallax explanation available.
Context: template-only. Read-only; it takes frames somebody else captured.
Usage:   shadow-gradient.py --box x0,y0,x1,y1 FRAME.png [FRAME.png ...]
                            [--rows N] [--label NAME]
Gotchas: crop the SKY BAND or another single-material region, never the whole
         opening — a real horizon is a vertical step and swamps the ramp you are
         looking for. The vertical figure is printed only as a sanity check that
         you cropped what you think you did.
"""
from __future__ import annotations

import argparse
import statistics
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is missing: pip install -r requirements-dev.txt")


def luma(px):
    return 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]


def ramp(img: Image.Image, box, rows: int):
    """Left-quarter mean minus right-quarter mean, and the vertical equivalent."""
    c = img.crop(box).convert("RGB")
    w, h = c.size
    if w < 8 or h < 8:
        sys.exit(f"box is {w}x{h} — too small to carry a gradient")
    step = max(1, h // rows)
    cols = [statistics.fmean(luma(c.getpixel((x, y))) for y in range(0, h, step))
            for x in range(w)]
    q = max(1, w // 4)
    horiz = statistics.fmean(cols[-q:]) - statistics.fmean(cols[:q])

    stepx = max(1, w // rows)
    rws = [statistics.fmean(luma(c.getpixel((x, y))) for x in range(0, w, stepx))
           for y in range(h)]
    qh = max(1, h // 4)
    vert = statistics.fmean(rws[-qh:]) - statistics.fmean(rws[:qh])
    return horiz, vert, statistics.fmean(cols)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames", nargs="+")
    ap.add_argument("--box", required=True, help="x0,y0,x1,y1 of a single-material region")
    ap.add_argument("--rows", type=int, default=60, help="samples per profile line")
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    box = tuple(int(v) for v in args.box.split(","))
    print(f"[shadow] {args.label or 'gradient'} box={args.box} over {len(args.frames)} pose(s)")

    horiz = []
    for f in args.frames:
        h, v, mean = ramp(Image.open(f), box, args.rows)
        horiz.append(h)
        print(f"    {Path(f).name:<24} L->R {h:+7.2f}   T->B {v:+7.2f}   mean {mean:6.1f}")

    spread = max(horiz) - min(horiz)
    worst = max(abs(h) for h in horiz)
    print(f"\n    worst |L->R| {worst:6.2f}     spread across poses {spread:6.2f}")

    if len(horiz) < 2:
        print("    ONE POSE PROVES NOTHING — the defect is a difference BETWEEN poses.")
        return 2
    if spread <= 3.0 and worst > 3.0:
        pass
    if worst <= 3.0 and spread <= 3.0:
        print("    NO SHADOW. The ramp is flat and does not move with the camera.")
        return 0
    if spread > 3.0:
        print("    SHADOW. The ramp changes with the camera alone, which a real "
              "window cannot do.")
    else:
        print("    RAMP PRESENT but stable across poses — not camera-tracking. "
              "Check it against the destination's own lighting before calling it a defect.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
