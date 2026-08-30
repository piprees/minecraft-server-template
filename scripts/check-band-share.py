#!/usr/bin/env python3
"""Report how much of its own dimension each explicit biome band holds.

Purpose:  check-band-reach.py asks whether a band is reachable at all and
          check-biome-bands.py asks whether bands overlap or slice the schema's
          axis. Neither reports SIZE. This runs vanilla's own nearest-hypercube
          lookup over a dimension's measured climate and prints the share each
          band takes, so a band reduced to a sliver is visible.

Context:  A small share is NOT a fault and this script does not gate. Measured
          against the game: `the_claymarsh`'s `minecraft:swamp` holds a band
          0.026 wide of a 2.000 span, takes none of the 121-point grid, and
          `customdim locate biome` finds it at (340, 64, -224) — a few hundred
          blocks from spawn. `customdim facts` on the same seed reports 14 of
          its 15 biomes present and the dimension scores 83.4%. A band this
          grid calls empty is a biome a player meets.

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
          - **This grid is 11x11 = 121 points at two synthetic depths. The game
            samples 41x41 disc-clipped, about 1300 cells, at block y 64**
            (`FactsEngine.GRID`, `SpikeSampler`). It is roughly 11x denser, so
            this script calls bands empty that the game finds. Do not read
            "holds no cell" as "cannot generate" — that inference was made once
            and a locate probe disproved it.
          - Exits 0 always. Calibrating a threshold needs a grid measured at
            the game's own geometry; until then a gate here would block on a
            number nobody has validated.
          - An unstated axis spans -2..2 and costs nothing in the lookup, which
            is why a band constraining one axis beats a native point almost
            everywhere.
          - A dimension with plain-string natives is NOT JUDGED: their
            hypercubes come from the base source and this script cannot read
            them, and they compete for the same points.
          - Samples come from climate-axes.json. A measured dimension with no
            samples block is an error, not a skip.
"""
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DIMS = REPO / "config/custom-dimensions/dimensions"
AXES_FILE = REPO / "config/custom-dimensions/climate-axes.json"

AXES = ["temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"]
KEY = {"temperature": "temp", "humidity": "humid", "continentalness": "cont",
       "erosion": "eros", "weirdness": "weird"}
FULL = (-2.0, 2.0)
# One representative depth per layer: below and above every shipped split.
LAYERS = {"surface": -0.5, "cave": 0.5}


def rng(params, axis):
    v = params.get(axis)
    if v is None:
        return FULL
    if isinstance(v, (int, float)):
        return (float(v), float(v))
    return (float(v[0]), float(v[1]))


def squared_distance(cube, point):
    total = 0.0
    for axis in AXES:
        lo, hi = cube[axis]
        v = point[axis]
        d = lo - v if v < lo else (v - hi if v > hi else 0.0)
        total += d * d
    return total


def wins(bands, samples, depth):
    got = {bid: 0 for bid, _ in bands}
    n = len(next(iter(samples.values())))
    for i in range(n):
        point = {a: (depth if a == "depth" else samples[KEY[a]][i]) for a in AXES}
        best, bestd = None, None
        for bid, cube in bands:
            d = squared_distance(cube, point)
            if bestd is None or d < bestd:
                best, bestd = bid, d
        got[best] += 1
    return got, n


def main():
    empty_only = "--empty" in sys.argv
    table = json.loads(AXES_FILE.read_text())["perDimension"]
    empty = judged = 0
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
        if slug not in table:
            unmeasured.append(slug)
            continue
        samples = table[slug].get("samples")
        if not samples:
            print(f"{slug}: measured at its own border but climate-axes.json carries no "
                  f"samples block — this script cannot run and will not pretend to")
            return 1
        if any(isinstance(e, str) for e in entries):
            natives_skipped.append(slug)
            continue


        judged += len(bands)
        held = {bid: 0 for bid, _ in bands}
        top = (0.0, None, 0)
        for depth in LAYERS.values():
            members = [b for b in bands if b[1]["depth"][0] <= depth <= b[1]["depth"][1]]
            if not members:
                continue
            got, n = wins(bands, samples, depth)
            for bid, c in got.items():
                held[bid] += c
            best = max(((got[bid], bid) for bid, _ in members), default=(0, None))
            if best[0] / n > top[0]:
                top = (best[0] / n, best[1], len(members))

        blank = [bid for bid, _ in bands if held[bid] == 0]
        empty += len(blank)
        if blank or not empty_only:
            print(f"{slug}: {len(bands) - len(blank)}/{len(bands)} bands hold a cell; "
                  f"biggest {top[0] * 100:.0f}% ({top[1]})")
            for bid in blank:
                print(f"    holds no cell of 121: {bid}")

    print(f"\njudged {judged} band(s) across measured dimensions with no native biomes")
    print(f"  {empty} hold no cell of this 121-point grid. Measured, that overstates "
          f"absence by 3.8x against the game's own geometry — re-measure with "
          f"scripts/sample-climate-grid.sh before acting on one. See K7.")
    if natives_skipped:
        print(f"  {len(natives_skipped)} dimension(s) NOT JUDGED (plain-string natives): "
              f"{', '.join(natives_skipped[:6])}{'...' if len(natives_skipped) > 6 else ''}")
    if unmeasured:
        print(f"  {len(unmeasured)} dimension(s) have bands but no measurement — skipped, "
              f"not guessed: {', '.join(unmeasured[:6])}{'...' if len(unmeasured) > 6 else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
