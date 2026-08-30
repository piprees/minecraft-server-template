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

          Two instruments, and they disagree in opposite directions. Plain
          output models THE WORLD: a Minecraft world border is a square
          (`WorldBorderManager` passes a side length to `setSize`), so every
          corner of [-B,+B]^2 is reachable ground. `--disc` models what
          `customdim facts` REPORTS: `FactsEngine` samples "a square grid
          clipped to the playable disc", leaving ~21% of reachable ground
          unsampled. Use --disc to predict a facts figure, plain to ask what
          the world contains.

Gotchas:  - The table must be dumped from the SAME build you are asking about.
            It already contains whatever the projection did; nothing here
            re-implements it, which is the point.
          - Scores the committed 121-point cloud where `facts` walks 41x41, so
            a biome winning one cloud point is not a biome a player meets
            ([K7]). Against a facts figure it over-counts: measured on
            the_lantern_pools, --disc matched facts exactly at 4 of 13 and then
            over-read it by 2 at 10 of 13 on the same dimension. One agreement
            is not calibration. Read absolutes as an upper bound, never as
            facts numbers.
          - The likely source of that gap is the tie-break. This scores by
            plain argmin; the game hands a tie to the incumbent, which is
            whatever the previous lookup returned (T59), so a biome that wins
            here on merit can lose there to a ratchet. That biases this tool
            HIGH on distinct counts, which is the direction observed.
          - `facts` itself UNDER-counts against the world, because its disc
            never samples the corners. The two biases pull opposite ways.
          - Ties resolve first-wins here; the game resolves them by incumbent
            and that depends on generation order (T59). Ties are reported.
"""
import gzip
import json, sys
from pathlib import Path

AXES = ["temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"]
SAMPLE_KEY = ["temp", "humid", "cont", "eros", "depth", "weird"]
# The samples carry depth at y=0; FactsEngine reads block y64, which is that
# value minus 0.5 (K7). Scoring depth as 0.0 hands every native pinned at
# depth [-0.005, 0.000] a free 0.2014 of squared distance — measured on
# the_frozen_strait, 3.7x the entire offset term being swept.
DEPTH_AT_Y64 = -0.5
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
    """Sample points as fixed-point arrays.

    `disc` reproduces FactsEngine's own sampling, which clips a square grid to
    a circle. It is NOT the shape of the world: the border is a square and the
    corners are reachable. Clip to compare against a facts figure; leave it off
    to ask what the world holds.
    """
    rec = grid_samples(slug) or axes_doc["perDimension"][slug]["samples"]
    if "depth" not in rec:
        print("  WARNING: no depth column in this sample source, so depth is scored"
              " as 0.0 and every native pinned near depth 0 is handed a free"
              " 0.2 of squared distance. Regenerate config/custom-dimensions/"
              "grids-41/%s.tsv.gz to score it properly." % slug, file=sys.stderr)
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
            if key is not None and key in rec:
                v = rec[key][i]
                point[a] = fx(v + DEPTH_AT_Y64 if key == "depth" else v)
        out.append(point)
    return out


def grid_samples(slug):
    """All six axes from the committed 41x41 grid, or None when there is none.

    The 121-point cloud in climate-axes.json carries no depth column at all,
    which is why depth went unscored. These grids do, and they are the density
    FactsEngine itself reads.
    """
    path = Path("config/custom-dimensions/grids-41/%s.tsv.gz" % slug)
    if not path.is_file():
        return None
    cols, rec = None, {}
    with gzip.open(path, "rt") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("#"):
                continue
            if cols is None:
                cols = line.split("\t")
                rec = {c: [] for c in cols}
                continue
            parts = line.split("\t")
            if len(parts) != len(cols):
                continue
            for c, v in zip(cols, parts):
                rec[c].append(float(v))
    return rec or None


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
          f"{len(points)} sample points{" (facts' disc sampling)" if disc else " (the square world)"}")
    base = report("as dumped", table, points, listed, bands)
    if "--sweep" in argv:
        # Spans the range a band offset is actually written in. The old grid
        # topped out at 0.2, where the_frozen_strait measures inert at three
        # separate live points — a sweep that cannot reach the answer.
        for o in (0.05, 0.1, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5, 0.6):
            report(f"band offset {o}", with_band_offset(table, bands, o),
                   points, listed, bands)
    missing = sorted(set(listed) - set(base))
    if missing:
        print(f"  holding nothing: {', '.join(missing)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
