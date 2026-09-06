#!/usr/bin/env python3
"""Per-frame change in a named box across a flicker-record.sh run.

The bridge's /screenshot is a ~1s round trip, so a burst of it is a 1 Hz series
and cannot see a per-frame defect. This reads consecutive screen frames instead
and answers the question that separates two different defects:

  (a) the opening is EMPTY in some frames -- the projection vanishes and the
      backdrop or source world shows through
  (b) the opening is FULL in every frame but alternating between two contents

Both give "it flickers". They need different fixes. The discriminator is
Rec.709 luma standard deviation per state: a flat backdrop carries far less
structure than a drawn destination, so (a) has one low-std state and (b) has
two comparable ones. Threshold and delta rule are kdiff.py's, luma weights are
lumastd.py's, so a box measured by one is the same box in the other.

Usage:
  flicker-check.py <run-dir> [--threshold 12] [--state-share 1.0]
                   [--reference N] [--rows N] [--client-fps F]
"""

import argparse
import json
import statistics
import sys
from pathlib import Path

import numpy as np
from PIL import Image

THRESHOLD = 12
LUMA = np.array([0.2126, 0.7152, 0.0722])

# A state whose luma spread is this many times the other's is the flat one.
FLAT_RATIO = 1.6
# Below this the two states carry comparable structure and neither is empty.
SAME_RATIO = 1.25


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def crop(image, rect):
    x0, y0, x1, y1 = rect
    return image[y0:y1, x0:x1]


def changed_share(a, b, threshold):
    """Percent of pixels whose largest per-channel difference clears threshold."""
    if a.shape != b.shape:
        return None
    delta = np.abs(a - b).max(axis=2)
    return 100.0 * float((delta >= threshold).mean())


def luma_facts(patch):
    flat = patch.reshape(-1, 3).astype(np.float64)
    values = flat @ LUMA
    return float(values.std()), float(values.mean())


def runs(series):
    """[(value, length), ...] for a sequence, in order."""
    out = []
    for value in series:
        if out and out[-1][0] == value:
            out[-1][1] += 1
        else:
            out.append([value, 1])
    return [(v, n) for v, n in out]


def histogram(lengths):
    counts = {}
    for n in lengths:
        counts[n] = counts.get(n, 0) + 1
    return "  ".join(f"{n}x{counts[n]}" for n in sorted(counts))


def coherent(patches, indices, threshold, share):
    """Is every frame in this state the same picture? Returns (bool, worst)."""
    if len(indices) < 2:
        return True, 0.0
    middle = indices[len(indices) // 2]
    worst = max(changed_share(patches[i], patches[middle], threshold)
                for i in indices)
    return worst < share, worst


def report_states(name, letters, patches, stds, share, threshold):
    groups = {}
    for i, letter in enumerate(letters):
        groups.setdefault(letter, []).append(i)
    print(f"\n  {name}: {len(groups)} state(s) at {share}% of the box")
    print(f"    pattern  {''.join(letters)}")
    for letter in sorted(groups):
        indices = groups[letter]
        lengths = [n for v, n in runs(letters) if v == letter]
        ok, worst = coherent(patches, indices, threshold, share)
        mean_std = statistics.fmean(stds[i] for i in indices)
        print(f"    {letter}: {len(indices):4d} frames  lumaStd={mean_std:7.2f}"
              f"  runs {histogram(lengths)}"
              f"  {'one picture' if ok else f'NOT one picture (worst {worst:.2f}%)'}")
    return {letter: statistics.fmean(stds[i] for i in idx)
            for letter, idx in groups.items()}, groups


def verdict(state_std, groups):
    if len(state_std) < 2:
        print("\n  VERDICT: a single state — no flicker at this threshold.")
        return
    if len(state_std) > 2:
        print("\n  VERDICT: more than two states — this is not a two-way"
              " flicker; read the per-frame table.")
        return
    (la, sa), (lb, sb) = sorted(state_std.items(), key=lambda kv: kv[1])
    ratio = sb / sa if sa > 0 else float("inf")
    print(f"\n  VERDICT: two states. lumaStd {la}={sa:.2f}  {lb}={sb:.2f}"
          f"  ratio={ratio:.2f}")
    if ratio >= FLAT_RATIO:
        print(f"    (a) THE PROJECTION VANISHES. State {la} carries"
              f" {ratio:.1f}x less structure than {lb} in"
              f" {len(groups[la])} of {sum(len(g) for g in groups.values())}"
              " frames — a flat backdrop, not a second destination.")
    elif ratio <= SAME_RATIO:
        print(f"    (b) THE PROJECTION IS PRESENT THROUGHOUT. Both states carry"
              f" comparable structure, so the opening is drawing something in"
              f" every frame and alternating between two contents.")
    else:
        print(f"    UNDECIDED between (a) and (b): {ratio:.2f} falls between"
              f" {SAME_RATIO} and {FLAT_RATIO}. Both numbers are above;"
              " a shot of the opening with no portal settles it.")


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("run")
    ap.add_argument("--threshold", type=int, default=THRESHOLD)
    ap.add_argument("--state-share", type=float, default=1.0)
    ap.add_argument("--reference", type=int, default=0)
    ap.add_argument("--rows", type=int, default=200)
    ap.add_argument("--client-fps", type=float)
    args = ap.parse_args()

    run = Path(args.run)
    meta = json.loads((run / "meta.json").read_text())
    frames = meta["frames"]
    if len(frames) < 2:
        sys.exit(f"{len(frames)} frames — nothing to difference.")

    boxes = meta["boxes"]
    aperture = next((b for b in boxes if b.get("role") == "aperture"), boxes[0])
    controls = [b for b in boxes if b is not aperture]

    print(f"flicker-check {meta['label']}  mode={meta['mode']}"
          f"  {len(frames)} frames")
    if meta.get("frontmostBefore") != meta.get("frontmostAfter"):
        print(f"  OCCLUSION RISK: frontmost app changed mid-run,"
              f" {meta['frontmostBefore']} -> {meta['frontmostAfter']}."
              " -R captures a screen region, so anything stacked over the"
              " game is in these frames.")

    images = [load(run / f["file"]) for f in frames]
    walls = [f["wall"] for f in frames]
    span = walls[-1] - walls[0]
    fps = (len(frames) - 1) / span if span > 0 else 0.0
    gaps = [b - a for a, b in zip(walls, walls[1:])]
    print(f"  span {span:.2f}s  effective {fps:.2f} frames/s"
          f"  gap median={statistics.median(gaps)*1000:.0f}ms"
          f" max={max(gaps)*1000:.0f}ms")
    if args.client_fps:
        print(f"  client runs at {args.client_fps:.1f} fps; a sampler at"
              f" {fps:.2f}/s beats against it, so a strict 2-frame alternation"
              " arrives as uneven runs rather than a clean ABAB.")

    columns = {}
    for box in boxes:
        patches = [crop(img, box["rect"]) for img in images]
        ref = patches[min(max(args.reference, 0), len(patches) - 1)]
        d_prev = [None] + [changed_share(patches[i - 1], patches[i], args.threshold)
                           for i in range(1, len(patches))]
        d_ref = [changed_share(ref, p, args.threshold) for p in patches]
        stds = [luma_facts(p)[0] for p in patches]
        means = [luma_facts(p)[1] for p in patches]
        columns[box["name"]] = dict(box=box, patches=patches, d_prev=d_prev,
                                    d_ref=d_ref, stds=stds, means=means)

    for name, col in columns.items():
        moving = [d for d in col["d_prev"][1:] if d is not None]
        hot = sum(1 for d in moving if d >= args.state_share)
        role = "APERTURE" if col["box"] is aperture else "control"
        print(f"\n  {name} [{role}] {col['box']['rect']}"
              f"  changed vs previous frame: {hot}/{len(moving)} frames"
              f" over {args.state_share}%"
              f"  median={statistics.median(moving):.2f}%"
              f" max={max(moving):.2f}%")
        print(f"    lumaStd {min(col['stds']):.2f}..{max(col['stds']):.2f}"
              f"   lumaMean {min(col['means']):.1f}..{max(col['means']):.1f}")
        flags = [d >= args.state_share for d in moving]
        changed_runs = [n for v, n in runs(flags) if v]
        still_runs = [n for v, n in runs(flags) if not v]
        print(f"    run lengths — changed: {histogram(changed_runs) or 'none'}"
              f"   still: {histogram(still_runs) or 'none'}")

    hot_controls = [name for name, col in columns.items()
                    if col["box"] is not aperture
                    and statistics.median(d for d in col["d_prev"][1:]) >= args.state_share]
    if hot_controls:
        print(f"\n  CONTROL MOVED: {', '.join(hot_controls)} changes as much as"
              " the aperture. The whole capture is moving — the camera, the"
              " window, or something stacked over it — so nothing below is"
              " about the portal.")

    col = columns[aperture["name"]]
    letters = ["A" if d < args.state_share else "B" for d in col["d_ref"]]
    state_std, groups = report_states(aperture["name"], letters, col["patches"],
                                      col["stds"], args.state_share,
                                      args.threshold)
    verdict(state_std, groups)

    print(f"\n  per-frame (first {min(args.rows, len(frames))} of {len(frames)}),"
          f" reference=frame {args.reference}")
    header = f"    {'i':>4} {'t/ms':>7} {'dt':>5} {'dPrev%':>7} {'dRef%':>7} {'lumaStd':>8} S"
    for name in columns:
        if columns[name]["box"] is not aperture:
            header += f" {name[:10]:>10}"
    print(header)
    for i in range(min(args.rows, len(frames))):
        prev = col["d_prev"][i]
        dt = (walls[i] - walls[i - 1]) * 1000 if i else 0
        line = (f"    {i:>4} {(walls[i]-walls[0])*1000:>7.0f} {dt:>5.0f}"
                f" {('-' if prev is None else f'{prev:.2f}'):>7}"
                f" {col['d_ref'][i]:>7.2f} {col['stds'][i]:>8.2f} {letters[i]}")
        for name in columns:
            other = columns[name]
            if other["box"] is not aperture:
                value = other["d_prev"][i]
                line += f" {('-' if value is None else f'{value:.2f}'):>10}"
        print(line)


if __name__ == "__main__":
    main()
