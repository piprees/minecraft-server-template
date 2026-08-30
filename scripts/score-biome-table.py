#!/usr/bin/env python3
"""Score a dumped biome table: which listed biomes actually hold ground.

Purpose:  `/customdim facts` answers this from a running server and costs 20-50
          seconds per dimension. This answers it from a `/customdim biome-table`
          dump plus the committed climate samples, in milliseconds, and can
          sweep a band's `offset` without booting anything.

Context:  Vanilla picks a biome by nearest hypercube. `getSquaredDistance` sums
          `square(range.getDistance(axis))` over six axes PLUS `square(offset)`,
          and `getDistance` returns 0 anywhere inside `[min, max]`. So a band
          leaving five axes unconstrained scores exactly 0 wherever its one
          stated axis matches, and nothing can strictly beat it — ties go to the
          incumbent (T59), which ratchets. `offset` is the only term that does
          not depend on the sample, so it is the only one a band cannot avoid
          paying; raising it is what lets a well-fitted native win.

Usage:    scripts/score-biome-table.py <dump.json> <slug>
          scripts/score-biome-table.py <dump.json> <slug> --sweep

Gotchas:  - The table must be dumped from the SAME build you are asking about.
            It already contains whatever the projection did; nothing here
            re-implements it, which is the point.
          - Scores the committed 121-point cloud; `facts` uses 41x41
            disc-clipped. This over-counts, so read gains as RELATIVE. A biome
            winning one cloud point is not a biome a player meets ([K7]).
          - climate-axes.json records the bounding SQUARE. --disc clips to the
            playable circle, which is what the game measures over.
          - Ties resolve first-wins here; the game resolves them by incumbent
            and that depends on generation order (T59). Ties are reported.
"""
import json, sys
from pathlib import Path

AXES = ["temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"]
SAMPLE_KEY = ["temp", "humid", "cont", "eros", None, "weird"]
SCALE = 10000


def fx(v):
    """The game's fixed point: a climate value is stored as v * 10000."""
    return int(v * SCALE)


def axis_distance(lo, hi, noise):
    """ParameterRange.getDistance, from the 1.21.1 bytecode."""
    above = noise - hi
    return above if above > 0 else max(lo - noise, 0)


def distance(cube, point):
    """getSquaredDistance: six axes plus the offset, which is never free."""
    total = 0
    for i, axis in enumerate(AXES):
        lo, hi = cube[axis]
        total += axis_distance(lo, hi, point[i]) ** 2
    return total + cube["offset"] ** 2


def load_table(path):
    """(cube, biome) for every cell, in fixed point."""
    doc = json.loads(Path(path).read_text())
    out = []
    for biome, cells in doc["table"].items():
        for c in cells:
            cube = {a: (fx(c[a][0]), fx(c[a][1])) for a in AXES}
            cube["offset"] = fx(c["offset"])
            out.append((cube, biome))
    return out, doc.get("dimension")


def samples(axes_doc, slug, disc=False):
    """Sample points as fixed-point arrays, optionally clipped to the disc."""
    rec = axes_doc["perDimension"][slug]["samples"]
    n = len(rec["temp"])
    side = round(n ** 0.5)
    out = []
    for i in range(n):
        if disc:
            col = (i % side) / (side - 1) * 2 - 1
            row = (i // side) / (side - 1) * 2 - 1
            if col * col + row * row > 1.0:
                continue
        point = [0] * 6
        for a, key in enumerate(SAMPLE_KEY):
            if key is not None:
                point[a] = fx(rec[key][i])
        out.append(point)
    return out


def score(table, points):
    """(points held per biome, tie count). First-wins on a tie, and counted."""
    held, ties = {}, 0
    for p in points:
        best, who, tied = None, None, False
        for cube, biome in table:
            d = distance(cube, p)
            if best is None or d < best:
                best, who, tied = d, biome, False
            elif d == best and biome != who:
                tied = True
        held[who] = held.get(who, 0) + 1
        ties += 1 if tied else 0
    return held, ties


def with_band_offset(table, band_ids, offset):
    """The same table with every band biome's cells given `offset`."""
    out = []
    for cube, biome in table:
        if biome in band_ids:
            cube = dict(cube)
            cube["offset"] = fx(offset)
        out.append((cube, biome))
    return out


def band_ids_of(config):
    return {e["id"] for e in (config.get("biomes") or [])
            if isinstance(e, dict) and isinstance(e.get("parameters"), dict)}


def listed_of(config):
    out = []
    for e in config.get("biomes") or []:
        out.append(e if isinstance(e, str) else e.get("id"))
    return [x for x in out if x]


def report(label, table, points, listed, bands):
    held, ties = score(table, points)
    present = sorted(b for b in held if b in listed)
    band_pts = sum(v for k, v in held.items() if k in bands)
    print(f"  {label:24s} {len(present):3d} of {len(listed)} biomes, "
          f"bands hold {band_pts:4d}/{len(points)} ({100 * band_pts / len(points):3.0f}%)"
          f"{f', {ties} tied point(s)' if ties else ''}")
    return present


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    dump, slug = argv[1], argv[2]
    disc = "--disc" in argv
    table, _ = load_table(dump)
    axes_doc = json.loads(Path("config/custom-dimensions/climate-axes.json").read_text())
    config = json.loads(
        Path(f"config/custom-dimensions/dimensions/{slug}.json").read_text())
    points = samples(axes_doc, slug, disc)
    listed, bands = listed_of(config), band_ids_of(config)
    print(f"{slug}: {len(table)} cells, {len(listed)} listed, {len(bands)} banded, "
          f"{len(points)} sample points{' (disc-clipped)' if disc else ' (bounding square)'}")
    base = report("as dumped", table, points, listed, bands)
    if "--sweep" in argv:
        for o in (0.005, 0.01, 0.02, 0.05, 0.1, 0.2):
            report(f"band offset {o}", with_band_offset(table, bands, o),
                   points, listed, bands)
    missing = sorted(set(listed) - set(base))
    if missing:
        print(f"  holding nothing: {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
