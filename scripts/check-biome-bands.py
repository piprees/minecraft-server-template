#!/usr/bin/env python3
"""Report explicit biome entries whose hypercubes overlap within a dimension.

Purpose:  A dimension's explicit bands are meant to partition its climate axis.
          Two entries claiming the same region both generate there and the split
          between them is no longer what the config says — which is how a
          hand-tuned layout quietly stops meaning anything.

Context:  The shipped configs had zero overlapping pairs. Two editing passes
          appending bands to the same files produced 393, none of which lint or
          the boot log flags: overlap is legal worldgen, just not intended.

Usage:    scripts/check-biome-bands.py            # exits 1 if any pair overlaps

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

total_files = total_pairs = 0
for pat in ("config/custom-dimensions/dimensions/*.json",
            "/Users/pip/Projects/elfydd/overlay/config/custom-dimensions/dimensions/*.json"):
    for f in sorted(glob.glob(pat)):
        doc = json.load(open(f)); cfg = doc.get("overrides", doc)
        arr = cfg.get("biomes") or []
        ex = [(e["id"], e["parameters"]) for e in arr
              if isinstance(e, dict) and isinstance(e.get("parameters"), dict)]
        bad = [(a[0], b[0]) for a, b in combinations(ex, 2) if overlaps(a[1], b[1])]
        if bad:
            total_files += 1; total_pairs += len(bad)
            where = "overlay" if "elfydd" in f else "platform"
            print(f"  {f.split('/')[-1][:-5]:26s} [{where:8s}] {len(bad):2d} overlapping pair(s) of {len(ex)} explicit")
print(f"\n{total_files} files, {total_pairs} overlapping pairs")
sys.exit(1 if total_pairs else 0)
