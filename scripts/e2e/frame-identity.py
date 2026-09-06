#!/usr/bin/env python3
"""frame-identity.py - Is this scene actually static? Zero differing pixels, or it is not.

Purpose: the acceptance test for the benchmark itself. Two frames of an untouched
         scene must be BIT-IDENTICAL; anything else means a number taken here
         measures the scene moving, not the thing under test.
Context: template-only, local rig. Read-only.
Usage:   frame-identity.py A.png B.png [C.png ...] [--box name=x0,y0,x1,y1 ...]
                           [--threshold 0] [--map DIR]
Gotchas: threshold 0 is the point — a "small" difference is still a difference and
         a shader's temporal accumulation shows up here as a wash of 1-level noise.
         --threshold 12 answers a different question (is it VISIBLY different) and
         is for triage only, never for accepting a benchmark.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError:
    sys.exit("Pillow is missing: pip install -r requirements-dev.txt")


def boxes_from(args):
    out = []
    for spec in args or []:
        name, _, coords = spec.partition("=")
        x0, y0, x1, y1 = (int(v) for v in coords.split(","))
        out.append((name, (x0, y0, x1, y1)))
    return out


def compare(a: Image.Image, b: Image.Image, box, threshold):
    ra = a.crop(box).convert("RGB") if box else a.convert("RGB")
    rb = b.crop(box).convert("RGB") if box else b.convert("RGB")
    diff = ImageChops.difference(ra, rb)
    total = ra.width * ra.height
    # Per-pixel max channel delta, so a 1-level shift in one channel still counts.
    worst = max_channel(diff)
    hist = worst.histogram()
    differing = sum(hist[1:])
    over = sum(hist[threshold + 1:]) if threshold >= 0 else 0
    peak = max((i for i, n in enumerate(hist) if n), default=0)
    return total, differing, over, peak


def max_channel(diff: Image.Image) -> Image.Image:
    r, g, b = diff.split()
    return ImageChops.lighter(ImageChops.lighter(r, g), b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frames", nargs="+")
    ap.add_argument("--box", action="append", help="name=x0,y0,x1,y1 (repeatable)")
    ap.add_argument("--threshold", type=int, default=0)
    ap.add_argument("--map", help="directory to write difference maps into")
    args = ap.parse_args()

    if len(args.frames) < 2:
        sys.exit("need at least two frames")

    imgs = [(Path(f).name, Image.open(f)) for f in args.frames]
    sizes = {im.size for _, im in imgs}
    if len(sizes) != 1:
        sys.exit(f"frames differ in size: {sizes} — the window moved or was resized")

    regions = boxes_from(args.box) or [("whole-frame", None)]
    print(f"[identity] {len(imgs)} frames, {imgs[0][1].size[0]}x{imgs[0][1].size[1]}, "
          f"threshold {args.threshold}")

    verdict = 0
    for i in range(len(imgs) - 1):
        (na, a), (nb, b) = imgs[i], imgs[i + 1]
        print(f"\n  {na} -> {nb}")
        for name, box in regions:
            total, differing, over, peak = compare(a, b, box, args.threshold)
            pct = 100.0 * differing / total if total else 0.0
            flag = "STATIC" if differing == 0 else "MOVED "
            if differing:
                verdict = 1
            print(f"    {flag} {name:<12} {differing:>8}/{total:<8} px differ "
                  f"({pct:6.2f}%)  peak delta {peak:>3}  over-threshold {over}")
            if args.map and differing:
                d = Path(args.map)
                d.mkdir(parents=True, exist_ok=True)
                ra = a.crop(box).convert("RGB") if box else a.convert("RGB")
                rb = b.crop(box).convert("RGB") if box else b.convert("RGB")
                out = d / f"{i}-{name}.png"
                amplify = [min(255, v * 16) for v in range(256)]
                max_channel(ImageChops.difference(ra, rb)).point(amplify).save(out)
                print(f"           map -> {out}")

    print()
    if verdict:
        print("[identity] NOT A BENCHMARK. Frames of an untouched scene differ; "
              "every number taken here measures the scene moving.")
    else:
        print("[identity] BIT-IDENTICAL across every frame and region. "
              "This scene is static and a reading in it means something.")
    return verdict


if __name__ == "__main__":
    sys.exit(main())
