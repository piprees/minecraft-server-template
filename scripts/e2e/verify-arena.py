#!/usr/bin/env python3
"""verify-arena.py - Prove an arena is the box it claims to be, block by block.

Purpose: a four-corner probe passes on an arena with a hole in the middle of a
         wall. This samples the floor, all four wall faces, the interior air and
         the absent ceiling, and refuses on the first disagreement.
Context: template-only, local rig. Read-only — it places no blocks.
Usage:   verify-arena.py --dim DIM --centre "X Y Z" [--radius 24] [--height 24]
                         [--floor minecraft:gray_concrete] [--wall BLOCK]
                         [--step 4] [--container mc]
                         [--except-box=x0,y0,z0,x1,y1,z1]   a region the arena does not own
Gotchas: `That position is not loaded` is its own failure, NOT a passing probe
         and NOT `Test failed` — an unloaded chunk answers every question with
         silence and is how an arena gets declared built when nothing was placed.
         Forceload the region before running this.
         --except-box needs an = sign: a bare leading minus reads as an option.
"""
from __future__ import annotations

import argparse
import subprocess
import sys

PASS = "Test passed"
FAIL = "Test failed"
UNLOADED = "That position is not loaded"


def probes(cx, cy, cz, radius, height, floor, wall, step):
    """Yield (label, x, y, z, expected_block) for the whole box."""
    x0, x1 = cx - radius, cx + radius
    z0, z1 = cz - radius, cz + radius
    top = cy + height

    def span(a, b):
        vs = list(range(a, b + 1, step))
        if vs[-1] != b:
            vs.append(b)
        return vs

    for x in span(x0, x1):
        for z in span(z0, z1):
            yield ("floor", x, cy, z, floor)

    # Wall faces, sampled up their full height. The corners belong to two faces;
    # probing them twice is cheaper than excluding them.
    for y in span(cy, top):
        for z in span(z0, z1):
            yield ("wall-xlow", x0, y, z, wall)
            yield ("wall-xhigh", x1, y, z, wall)
        for x in span(x0, x1):
            yield ("wall-zlow", x, y, z0, wall)
            yield ("wall-zhigh", x, y, z1, wall)

    # Interior air, inset one block so a wall never answers for the interior.
    for y in span(cy + 1, top):
        for x in span(x0 + 1, x1 - 1):
            for z in span(z0 + 1, z1 - 1):
                yield ("interior", x, y, z, "minecraft:air")

    # No ceiling: the course above the walls is open to the sky.
    for x in span(x0, x1):
        for z in span(z0, z1):
            yield ("no-ceiling", x, top + 1, z, "minecraft:air")


def run(container, dim, plan):
    script = "".join(
        f"execute in {dim} run execute if block {x} {y} {z} {block}\n"
        for _, x, y, z, block in plan
    )
    out = subprocess.run(
        ["docker", "exec", "-i", container, "rcon-cli"],
        input=script, capture_output=True, text=True, timeout=900,
    )
    lines = [ln.lstrip("> ").strip() for ln in out.stdout.replace("\r", "").splitlines()]
    return [ln for ln in lines if ln]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dim", required=True)
    ap.add_argument("--centre", required=True)
    ap.add_argument("--radius", type=int, default=24)
    ap.add_argument("--height", type=int, default=24)
    ap.add_argument("--floor", default="minecraft:gray_concrete")
    ap.add_argument("--wall", default=None)
    ap.add_argument("--step", type=int, default=4)
    ap.add_argument("--container", default="mc")
    ap.add_argument("--show", type=int, default=15, help="failures to print")
    ap.add_argument("--except-box", action="append", metavar="x0,y0,z0,x1,y1,z1",
                    help="a region the arena does not own, e.g. a portal frame (repeatable)")
    args = ap.parse_args()

    cx, cy, cz = (int(v) for v in args.centre.split())
    wall = args.wall or args.floor
    exempt = []
    for spec in args.except_box or []:
        v = [int(n) for n in spec.split(",")]
        exempt.append((min(v[0], v[3]), min(v[1], v[4]), min(v[2], v[5]),
                       max(v[0], v[3]), max(v[1], v[4]), max(v[2], v[5])))

    def owned(x, y, z):
        return not any(a <= x <= d and b <= y <= e and c <= z <= f
                       for a, b, c, d, e, f in exempt)

    plan = [p for p in probes(cx, cy, cz, args.radius, args.height, args.floor, wall, args.step)
            if owned(p[1], p[2], p[3])]
    answers = run(args.container, args.dim, plan)

    if len(answers) != len(plan):
        print(f"[verify-arena] REFUSED: sent {len(plan)} probes, got {len(answers)} answers.")
        print("  RCON truncates a large reply. Raise --step and run again.")
        return 2

    counts = {}
    bad = []
    for (label, x, y, z, block), answer in zip(plan, answers):
        if answer == PASS:
            counts[label] = counts.get(label, 0) + 1
            continue
        why = "UNLOADED" if answer == UNLOADED else "WRONG BLOCK" if answer == FAIL else answer
        bad.append((label, x, y, z, block, why))

    for label in sorted(counts):
        print(f"[verify-arena] {label:<12} {counts[label]:>5} probes OK")

    if not bad:
        print(f"[verify-arena] {args.dim}: all {len(plan)} probes pass "
              f"(radius {args.radius}, walls {args.height} high, no ceiling).")
        return 0

    unloaded = sum(1 for b in bad if b[5] == "UNLOADED")
    print(f"[verify-arena] REFUSED: {len(bad)} of {len(plan)} probes failed "
          f"({unloaded} unloaded).")
    for label, x, y, z, block, why in bad[: args.show]:
        print(f"  {why:<12} {label:<12} {x} {y} {z}  expected {block}")
    if len(bad) > args.show:
        print(f"  ... and {len(bad) - args.show} more")
    if unloaded:
        print("  Unloaded chunks answer nothing and place nothing. Forceload, then rebuild.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
