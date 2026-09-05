#!/usr/bin/env python3
"""Verdicts from a camera sweep: view-stability and fps under motion.

Two questions the old instruments could not ask, because each reduced ONE
camera pose to a scalar and every defect here is a difference BETWEEN poses.

1. Where the opening reconstructs to. A pack recovers a fragment's position
   from window depth alone, so a projection compressed into a slice at the
   portal surface is shaded as though it stood in the doorway. This is the
   verdict: it needs no shader pack, no image and no floor to compare against.
   The aperture luma either side of it is corroboration, not the test.

2. Framerate under motion. Panning is what forces the projection to rebuild.
   A static hold cannot see that cost, so min and 5th-percentile fps across a
   sweep are the numbers that matter, not the mean of a stationary camera.

Usage: camera-sweep-check.py <sweep-dir> [--box W]
"""
import json
import statistics as st
import sys
from pathlib import Path


def load(d: Path):
    rows = []
    f = d / "frames.jsonl"
    if not f.exists():
        sys.exit(f"no frames.jsonl in {d}")
    for line in f.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
    return rows


def luma_stats(png: Path, ndc=None, frac=0.05):
    """Mean Rec.709 luma over a box on the opening, and the blown-pixel fraction.

    The box follows the aperture. A centred crop reads the source world either
    side of the frame at wide yaw, which swings for reasons that have nothing to
    do with the projection and buries the one thing being asked about.
    """
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        im = Image.open(png).convert("RGB")
    except Exception:
        return None
    w, h = im.size
    if ndc is None:
        return None
    cx = (ndc[0] + 1.0) / 2.0 * w
    cy = (1.0 - ndc[1]) / 2.0 * h
    half = min(w, h) * frac / 2.0
    box = (int(cx - half), int(cy - half), int(cx + half), int(cy + half))
    if box[0] < 0 or box[1] < 0 or box[2] > w or box[3] > h:
        return None
    px = im.crop(box).getdata()
    if not px:
        return None
    lum = [0.2126 * r + 0.7152 * g + 0.0722 * b for r, g, b in px]
    bright = sum(1 for v in lum if v >= 254) / len(lum)
    return {"mean": st.fmean(lum), "std": st.pstdev(lum), "bright": bright * 100.0}


def main():
    d = Path(sys.argv[1]) if len(sys.argv) > 1 else sys.exit(__doc__)
    rows = load(d)
    if not rows:
        sys.exit("no frames")

    stations = {}
    for r in rows:
        stations.setdefault(r.get("station", "?"), []).append(r)

    print(f"{len(rows)} frames, {len(stations)} stations, from {d}\n")

    # ---- framerate under motion -------------------------------------------
    fps = [r["fps"] for r in rows if isinstance(r.get("fps"), (int, float))]
    if fps:
        fps_sorted = sorted(fps)
        p5 = fps_sorted[max(0, int(len(fps_sorted) * 0.05) - 1)]
        print("FRAMERATE WHILE PANNING (the number a static hold cannot see)")
        print(f"  n={len(fps)}  mean={st.fmean(fps):.2f}  median={st.median(fps):.1f}"
              f"  min={min(fps)}  p5={p5}  max={max(fps)}")
        stalls = sum(1 for v in fps if v <= 2)
        print(f"  frames at <=2 fps: {stalls} ({100.0*stalls/len(fps):.1f}%)"
              f"   <- a stall is felt, a mean is not\n")
    else:
        print("FRAMERATE: no fps field in any frame — the bridge did not report it\n")

    ru = [r["renderUs"] for r in rows if isinstance(r.get("renderUs"), (int, float))]
    if ru:
        print(f"  mod render pass: mean {st.fmean(ru):.0f}us  max {max(ru):.0f}us\n")

    # ---- where a screen-space pass puts the opening -------------------------
    # No image processing and no shader pack: the mod reconstructs its own
    # fragment the way a pack does, from window depth alone.
    print("RECONSTRUCTED DEPTH OF THE OPENING (what a pack reads, in blocks)")
    print("  The station distance is how far the doorway is. The projection")
    print("  behind it is tens of blocks further, and reads as neither.\n")
    print(f"  {'station':<20} {'n':>3} {'dist':>6} {'recon':>7} {'swing':>7} {'slice':>7}"
          f" {'far':>9}")
    for name, group in sorted(stations.items()):
        vals = [r["reconDistance"] for r in group
                if isinstance(r.get("reconDistance"), (int, float))]
        if not vals:
            print(f"  {name:<20}   -  (no apertureSample — no opening drawn)")
            continue
        far = [r["farDistance"] for r in group
               if isinstance(r.get("farDistance"), (int, float))]
        # What the same pixel reports when the destination is drawn inside the
        # compressed range instead. Absent from a sweep taken before the field
        # existed, and then simply blank.
        sl = [r["sliceDistance"] for r in group
              if isinstance(r.get("sliceDistance"), (int, float))]
        print(f"  {name:<20} {len(vals):>3} {group[0].get('dist', 0):>6}"
              f" {st.fmean(vals):>7.2f} {max(vals) - min(vals):>7.2f}"
              f" {(f'{st.fmean(sl):.2f}' if sl else '-'):>7}"
              f" {(st.fmean(far) if far else float('nan')):>9.1f}")
    print()

    # ---- view stability ----------------------------------------------------
    print("VIEW STABILITY — aperture luma against yaw, per station")
    print("  A window onto a fixed place moves only by parallax. Yaw and pitch")
    print("  are reported side by side; neither is a floor for the other.\n")
    print(f"  {'station':<20} {'n':>3} {'mean':>7} {'min':>7} {'max':>7} {'swing':>7}  bright")
    swings = {}
    for name, group in sorted(stations.items()):
        vals, bright_max = [], 0.0
        for r in group:
            png = d / f"{r['tag']}.png"
            if not png.exists():
                continue
            if not isinstance(r.get("ndcX"), (int, float)):
                continue
            s = luma_stats(png, (r["ndcX"], r["ndcY"]))
            if s is None:
                continue
            vals.append(s["mean"])
            bright_max = max(bright_max, s["bright"])
        if not vals:
            print(f"  {name:<20}   -  (no frame carried an aperture; Pillow installed?)")
            continue
        swing = max(vals) - min(vals)
        swings[name] = swing
        flag = "  <- bright, discard" if bright_max > 0.0 else ""
        print(f"  {name:<20} {len(vals):>3} {st.fmean(vals):>7.2f} {min(vals):>7.2f}"
              f" {max(vals):>7.2f} {swing:>7.2f}  {bright_max:.2f}%{flag}")

    # Both swings are reported and neither is a floor for the other. A pitch
    # sweep moves the aperture across the screen exactly as a yaw sweep does, so
    # a defect present in both axes divides out and reads as stable.
    yaw_swings = {k: v for k, v in swings.items() if k.endswith("-yaw")}
    pitch_swings = {k: v for k, v in swings.items() if k.endswith("-pitch")}
    for yk, yv in sorted(yaw_swings.items()):
        pk = yk[: -len("-yaw")] + "-pitch"
        pv = pitch_swings.get(pk)
        if pv is not None:
            print(f"  {yk:<20} yaw {yv:6.2f}   pitch {pv:6.2f}"
                  "   both are swings a fixed eye should not see")

    # The verdict is the reconstruction, not the picture: it needs no floor, no
    # shader pack and no judgement. A window shows a place BEHIND the doorway.
    print("\n  VERDICT — is the projection pinned to the doorway?")
    for name, group in sorted(stations.items()):
        vals = [r["reconDistance"] for r in group
                if isinstance(r.get("reconDistance"), (int, float))]
        dist = group[0].get("dist")
        if not vals or not isinstance(dist, (int, float)):
            continue
        recon = st.fmean(vals)
        pinned = recon < dist + 2.0
        print(f"  {name:<20} doorway {dist:>5.1f}  reconstructs {recon:6.2f}  "
              + ("PINNED — every screen-space pass shades it at the doorway"
                 if pinned else "BEHIND THE DOORWAY"))

    print("\n  Frames are in", d, "- LOOK at them. A number that disagrees with")
    print("  the picture means the number is measuring the wrong thing.")


if __name__ == "__main__":
    main()
