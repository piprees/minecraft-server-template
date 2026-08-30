#!/usr/bin/env python3
"""Report biome bands that overlap, starve a dimension's natives, or slice the schema's axis.

Purpose:  A dimension's explicit bands are meant to partition its climate axis.
          Two entries claiming the same region both generate there and the split
          between them is no longer what the config says — which is how a
          hand-tuned layout quietly stops meaning anything.

Context:  The shipped configs had zero overlapping pairs. Two editing passes
          appending bands to the same files produced 393, none of which lint or
          the boot log flags: overlap is legal worldgen, just not intended.

          A bare-string biome keeps its point from the underlying source, but an
          explicit entry withdraws from native placement and wins wherever its
          stated axis matches — an unstated axis spans the whole range. So
          explicit bands covering an axis end to end leave the natives
          unreachable, and the dimension silently stops containing them.

          -2..2 is the range the SCHEMA allows a band to declare, not the range
          a world crosses: a real dimension crosses a fraction of it, centred
          wherever its own noise sits. Cutting -2..2 into equal slices therefore
          puts most of the slices outside the world — 17 dead bands of 25 in one
          dimension. check-band-reach.py measures that consequence and needs
          climate-axes.json to do it, so it can only report indicatively on a
          dimension nobody has measured yet. This check needs no measurement and
          catches the shape itself, which is what a new dimension ships with.

Usage:    scripts/check-biome-bands.py            # exits 1 on any of the three

Gotchas:  - Hypercubes intersect only if they intersect on EVERY axis, so two
            entries sharing a weirdness band but sitting in different `depth`
            bands do not overlap. Comparing one axis alone over-reports.
          - An unconstrained axis spans the whole -2..2 range, which is why an
            entry carrying a single axis overlaps almost everything.
          - Equal widths are judged over the WHOLE chain, ends included. A
            correct fit clamps its outermost bands to +/-2.0 so nothing falls
            off the axis, and those two are then far wider than the interior
            ones — an equal-area fit is unequal-width by construction.
          - `depth` is excluded from the slice check: it is a surface/underground
            split at a fixed boundary, not a partition fitted to a range.
"""
import json, glob, sys
from itertools import combinations
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from consumer_path import ENV_VAR, optional_consumer_dir  # noqa: E402

AXES = ["temperature","humidity","continentalness","erosion","depth","weirdness"]
FULL = (-2.0, 2.0)

# A sliver narrower than this is not a real home for a biome.
FREE_EPSILON = 0.05
# Float slack for "starts at -2.0" and "these bands touch".
EDGE = 1e-6
# Band widths within this fraction of their mean count as equal.
WIDTH_TOL = 0.02
# Two bands can split an axis at its midpoint deliberately; three cannot.
MIN_SLICES = 3
# A surface/underground split, not a partition fitted to a measured range.
NOT_SLICED = {"depth"}

def rng(params, axis):
    v = params.get(axis)
    if v is None: return FULL
    if isinstance(v, (int, float)): return (float(v), float(v))
    return (float(v[0]), float(v[1]))

def overlaps(a, b):
    # Hypercubes intersect only if they intersect on EVERY axis.
    for ax in AXES:
        lo1, hi1 = rng(a, ax); lo2, hi2 = rng(b, ax)
        if hi1 <= lo2 or hi2 <= lo1:
            return False
    return True

def covered(spans):
    """Total width the given [lo,hi] spans cover, overlaps counted once."""
    out = 0.0; end = None
    for lo, hi in sorted(spans):
        if end is None or lo > end:
            out += hi - lo; end = hi
        elif hi > end:
            out += hi - end; end = hi
    return out

def signature(params, axis):
    """A band's constraints on every axis but this one.

    Bands separated by another axis are separate partitions: surface and cave
    entries each span their axis end to end and are not one chain.
    """
    return tuple(sorted((a, tuple(v) if isinstance(v, list) else v)
                        for a, v in params.items() if a != axis))

def chains(entries, axis):
    """Maximal runs of touching bands on one axis, grouped by signature."""
    groups = {}
    for _, params in entries:
        if params.get(axis) is None:
            continue
        groups.setdefault(signature(params, axis), []).append(rng(params, axis))
    out = []
    for bands in groups.values():
        run = []
        for lo, hi in sorted(bands):
            if run and abs(lo - run[-1][1]) > EDGE:
                out.append(run); run = []
            run.append((lo, hi))
        out.append(run)
    return out

def slices_the_schema(chain):
    """True when a chain cuts the whole -2..2 axis into equal-width bands."""
    if len(chain) < MIN_SLICES:
        return False
    if chain[0][0] > FULL[0] + EDGE or chain[-1][1] < FULL[1] - EDGE:
        return False
    widths = [hi - lo for lo, hi in chain]
    mean = sum(widths) / len(widths)
    return mean > 0 and (max(widths) - min(widths)) <= WIDTH_TOL * mean

def main():
    total_files = total_pairs = total_starved = total_sliced = 0
    consumer = optional_consumer_dir()
    sources = [("platform", "config/custom-dimensions/dimensions/*.json")]
    if consumer:
        sources.append(("overlay",
                        str(consumer / "overlay/config/custom-dimensions/dimensions/*.json")))

    scanned = 0
    for where, pat in sources:
        for f in sorted(glob.glob(pat)):
            scanned += 1
            doc = json.load(open(f)); cfg = doc.get("overrides", doc)
            arr = cfg.get("biomes") or []
            ex = [(e["id"], e["parameters"]) for e in arr
                  if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
            natives = [e for e in arr if isinstance(e, str)]
            name = f.split('/')[-1][:-5]

            bad = [(a[0], b[0]) for a, b in combinations(ex, 2) if overlaps(a[1], b[1])]
            if bad:
                total_files += 1; total_pairs += len(bad)
                print(f"  {name:26s} [{where:8s}] {len(bad):2d} overlapping pair(s) of {len(ex)} explicit")

            # Natives are only reachable where no explicit band claims the axis.
            for ax in AXES:
                stated = [rng(p, ax) for _, p in ex if p.get(ax) is not None]
                if not natives or not stated:
                    continue
                free = 4.0 - covered(stated)
                if free <= FREE_EPSILON:
                    total_files += 1; total_starved += len(natives)
                    print(f"  {name:26s} [{where:8s}] {len(natives):2d} native biome(s) unreachable"
                          f" — explicit bands cover {ax} end to end")
                    for n in natives[:4]:
                        print(f"      {n}")
                    break

            # -2..2 is the schema's range, not the world's.
            for ax in AXES:
                if ax in NOT_SLICED:
                    continue
                for chain in chains(ex, ax):
                    if not slices_the_schema(chain):
                        continue
                    total_files += 1; total_sliced += len(chain)
                    print(f"  {name:26s} [{where:8s}] {len(chain):2d} band(s) cut {ax} into equal"
                          f" slices of {FULL[0]:+.1f}..{FULL[1]:+.1f}, which is the range the schema"
                          f" allows, not the range this world crosses")
                    print(f"      every slice is {(chain[0][1] - chain[0][0]):.4f} wide;"
                          f" fit the boundaries to the dimension's own measured range in"
                          f" config/custom-dimensions/climate-axes.json instead")

    print(f"\nscanned {scanned} dimension file(s) "
          f"({'platform + overlay' if consumer else 'platform only'})")
    if not consumer:
        print(f"  overlay not checked - set {ENV_VAR} to include a consumer repo")
    print(f"{total_files} files, {total_pairs} overlapping pairs, {total_starved} starved natives, "
          f"{total_sliced} schema-sliced bands")
    if scanned == 0:
        sys.exit("nothing scanned - run this from the platform repo root")
    return 1 if (total_pairs or total_starved or total_sliced) else 0

if __name__ == "__main__":
    sys.exit(main())
