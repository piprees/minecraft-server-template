#!/usr/bin/env python3
"""Report how much of its own dimension each explicit biome band holds.

Purpose:  check-band-reach.py asks whether a band is reachable at all and
          check-biome-bands.py asks whether bands overlap or slice the schema's
          axis. Neither reports SIZE. This runs vanilla's own nearest-hypercube
          lookup over a dimension's measured climate and prints the share each
          band takes, so a band reduced to a sliver is visible.

Context:  A small share is NOT a fault and this script does not gate. It reads
          the 41x41 = 1681-point grids under config/custom-dimensions/grids-41/,
          which is the density FactsEngine measures at. Thinning those same
          grids to the 121-point cloud in climate-axes.json calls 40 bands empty
          against these 2 — 38 of the 40 are resolution artefacts (K7).

          The target is that no listed biome is IGNORED, never that the parts
          are equal. A dimension where one biome dominates and the others each
          hold real ground is a good world; equal parts would be a quadrant
          world, and the pack has a checkerboard generator for anyone who wants
          one.

Usage:    scripts/check-band-share.py            # shares per dimension
          scripts/check-band-share.py --empty    # only bands taking no cell

          Share is reported and never gated.

Gotchas:  - A biome may carry several bands and holds each region. A repeat
            stating nothing new is a config fault `scripts/check-biome-bands.py`
            gates; nothing about it is measured here.
          - **The grid is the full square, and so is the world.** `WorldBorder`
            is `setCenter(0,0)` + `setSize(radius * 2)`, which in vanilla is a
            SQUARE, so every cell of `[-B,+B]^2` is ground a player can stand
            on. FactsEngine disc-clips its own sample, so it under-reports a
            band living only near the corners; that is a limit of `facts`, not
            of the world. Nothing here clips. Measured: `the_whitestone_ford`
            `minecraft:lush_caves` holds 0 cells inside the disc and 46 outside
            it, so `facts` reports the band empty and this script does not.
          - **Depth is measured, at one height.** The grids carry depth at y=0;
            the gradient is -1/128 per block, so block y 64 — the height
            `customdim facts` reads — is the sampled value minus 0.5. Every
            column is scored once, there. A band placed deeper than that height
            reaches only what the surface columns happen to expose, so a thin
            share is expected and is not a fault.
          - A dimension whose depth column is off the climate scale (an end or
            cave router reads 0, or 40..80) never has a band constraining depth,
            and an axis no band constrains adds the same distance to every band.
            Depth is dropped from the lookup there; the argmin cannot move.
          - Exits 0 always. "Holds no cell" at this density is worth
            investigating and is still not proof of absence — only
            `customdim locate biome` searches rather than samples.
          - An unstated axis spans -2..2 and costs nothing in the lookup, which
            is why a band constraining one axis beats a native point almost
            everywhere.
          - A dimension with plain-string natives is NOT JUDGED: their
            hypercubes come from the base source and this script cannot read
            them, and they compete for the same points.
          - A dimension with a band but no grid is skipped and named, never
            guessed at. Regenerate one with scripts/sample-climate-grid.sh.
"""
import gzip
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DIMS = REPO / "config/custom-dimensions/dimensions"
GRIDS = REPO / "config/custom-dimensions/grids-41"
# Column order in a sample-climate-grid.sh TSV: x z temp humid cont eros depth weird
COL = {"temp": 2, "humid": 3, "cont": 4, "eros": 5, "depth": 6, "weird": 7}

AXES = ["temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"]
KEY = {"temperature": "temp", "humidity": "humid", "continentalness": "cont",
       "erosion": "eros", "depth": "depth", "weirdness": "weird"}
FULL = (-2.0, 2.0)
# Blocks between the grid's sampled height and the one facts reads, over the
# depth gradient's -1/128 per block.
DEPTH_AT_Y64 = -0.5


def grid(slug):
    """Every sampled point for one dimension, or None if it has no grid."""
    path = GRIDS / f"{slug}.tsv.gz"
    if not path.exists():
        return None
    with gzip.open(path, "rt") as fh:
        rows = [l.split("\t") for l in fh.read().splitlines() if not l.startswith("#")][1:]
    cols = {k: [float(r[c]) for r in rows] for k, c in COL.items()}
    cols["depth"] = [d + DEPTH_AT_Y64 for d in cols["depth"]]
    return cols


def rng(params, axis):
    v = params.get(axis)
    if v is None:
        return FULL
    if isinstance(v, (int, float)):
        return (float(v), float(v))
    return (float(v[0]), float(v[1]))


def squared_distance(cube, point, axes):
    total = 0.0
    for axis in axes:
        lo, hi = cube[axis]
        v = point[axis]
        d = lo - v if v < lo else (v - hi if v > hi else 0.0)
        total += d * d
    return total


def wins(bands, samples, axes):
    got = {bid: 0 for bid, _ in bands}
    n = len(next(iter(samples.values())))
    for i in range(n):
        point = {a: samples[KEY[a]][i] for a in axes}
        best, bestd = None, None
        for bid, cube in bands:
            d = squared_distance(cube, point, axes)
            if bestd is None or d < bestd:
                best, bestd = bid, d
        got[best] += 1
    return got, n


def main():
    empty_only = "--empty" in sys.argv
    empty = judged = points = 0
    natives_skipped = []
    unmeasured = []

    for path in sorted(DIMS.glob("*.json")):
        slug = path.stem
        if slug.endswith("_thumb"):
            continue
        cfg = json.loads(path.read_text())
        cfg = cfg.get("overrides", cfg)
        entries = cfg.get("biomes") or []
        bands = [(e["id"], {a: rng(e["parameters"], a) for a in AXES}) for e in entries
                 if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
        if not bands:
            continue
        samples = grid(slug)
        if samples is None:
            unmeasured.append(slug)
            continue
        points = len(samples["temp"])
        if any(isinstance(e, str) for e in entries):
            natives_skipped.append(slug)
            continue


        judged += len(bands)
        # Depth off-scale (an end or cave router reads 0, or 40..80) only ever
        # coincides with no band constraining it, and an axis no band constrains
        # adds the same distance to every band, so dropping it cannot move the
        # argmin.
        axes = AXES if any(c["depth"] != FULL for _, c in bands) else \
            [a for a in AXES if a != "depth"]
        held, n = wins(bands, samples, axes)
        top = max(((c / n, bid) for bid, c in held.items()), default=(0.0, None))

        blank = [bid for bid, _ in bands if held[bid] == 0]
        empty += len(blank)
        if blank or not empty_only:
            print(f"{slug}: {len(bands) - len(blank)}/{len(bands)} bands hold a cell; "
                  f"biggest {top[0] * 100:.0f}% ({top[1]})")
            for bid in blank:
                print(f"    holds no cell of {points}: {bid}")

    print(f"\njudged {judged} band(s) across measured dimensions with no native biomes")
    print(f"  {empty} hold no cell at the density the game measures with. Still not proof "
          f"of absence: only `customdim locate biome` searches rather than samples. See K7.")
    if natives_skipped:
        print(f"  {len(natives_skipped)} dimension(s) NOT JUDGED (plain-string natives): "
              f"{', '.join(natives_skipped[:6])}{'...' if len(natives_skipped) > 6 else ''}")
    if unmeasured:
        print(f"  {len(unmeasured)} dimension(s) have bands but no grid — skipped, "
              f"not guessed: {', '.join(unmeasured[:6])}{'...' if len(unmeasured) > 6 else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
