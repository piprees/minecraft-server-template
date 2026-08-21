#!/usr/bin/env python3
"""Report biome bands that overlap, or that starve a dimension's native entries.

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

Usage:    scripts/check-biome-bands.py            # exits 1 on either fault

Gotchas:  - Hypercubes intersect only if they intersect on EVERY axis, so two
            entries sharing a weirdness band but sitting in different `depth`
            bands do not overlap. Comparing one axis alone over-reports.
          - An unconstrained axis spans the whole -2..2 range, which is why an
            entry carrying a single axis overlaps almost everything.
"""
import json, glob, sys
from itertools import combinations

AXES = ["temperature","humidity","continentalness","erosion","depth","weirdness"]
FULL = (-2.0, 2.0)

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

# A sliver narrower than this is not a real home for a biome.
FREE_EPSILON = 0.05

total_files = total_pairs = total_starved = 0
for pat in ("config/custom-dimensions/dimensions/*.json",
            "/Users/pip/Projects/elfydd/overlay/config/custom-dimensions/dimensions/*.json"):
    for f in sorted(glob.glob(pat)):
        doc = json.load(open(f)); cfg = doc.get("overrides", doc)
        arr = cfg.get("biomes") or []
        ex = [(e["id"], e["parameters"]) for e in arr
              if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
        natives = [e for e in arr if isinstance(e, str)]
        where = "overlay" if "elfydd" in f else "platform"
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

print(f"\n{total_files} files, {total_pairs} overlapping pairs, {total_starved} starved natives")
sys.exit(1 if (total_pairs or total_starved) else 0)
