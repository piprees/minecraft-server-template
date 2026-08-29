#!/usr/bin/env python3
"""Report explicit biome bands that sit outside the climate range their world crosses.

Purpose:  A band the world's climate never reaches is a biome that cannot
          generate. Nothing else catches it — check-biome-bands.py tests that
          bands do not OVERLAP and do not starve natives, which is a different
          question, and both faults are silent in game.

Context:  An equal-width partition of the theoretical -2..2 axis is wrong for
          every real world, because no world crosses -2..2. That shape produced
          17 dead bands of 25 in one dimension and the same generator produced
          10-slice partitions elsewhere.

          Ranges come from config/custom-dimensions/climate-axes.json:
          `perDimension` is measured at that dimension's own borders.player and
          is ground truth; `axes` is a per-(type, noiseSettings) fallback,
          measured on ONE representative, so it is indicative only.

Usage:    scripts/check-band-reach.py            # exits 1 on any dead band
          scripts/check-band-reach.py --warn     # never exits non-zero

Gotchas:  - 11 samples on one diagonal UNDERSTATE the true range, so a band
            marginally outside it may still be live. A band is only reported
            when it misses by more than MARGIN.
          - A dimension with no measurement is skipped, not guessed at.
          - Gaps between bands are NOT a fault: that is where native biomes
            live, and check-biome-bands.py already guards the opposite case.
"""
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DIMS = REPO / "config/custom-dimensions/dimensions"
AXES = REPO / "config/custom-dimensions/climate-axes.json"

# Sampling understates the range; only flag a band that misses by more than this.
MARGIN = 0.05

AXIS_KEY = {
    "temperature": "temp",
    "humidity": "humid",
    "continentalness": "cont",
    "erosion": "eros",
    "weirdness": "weird",
}


def measured_range(table, slug, dim_type, noise_settings, axis):
    """(lo, hi, source) for an axis, or None when nothing measured it."""
    per = table.get("perDimension", {}).get(slug)
    if per and axis in per.get("axes", {}):
        a = per["axes"][axis]
        return a["min"], a["max"], "own border"
    for row in table.get("axes", []):
        if row["type"] == dim_type and row["noiseSettings"] == noise_settings:
            a = row.get("all", {}).get(axis)
            if a and "min" in a:
                return a["min"], a["max"], "representative"
    return None


def main():
    warn_only = "--warn" in sys.argv
    table = json.loads(AXES.read_text())
    dead = 0
    checked = 0
    skipped = []

    for path in sorted(DIMS.glob("*.json")):
        slug = path.stem
        if slug.endswith("_thumb"):
            continue
        cfg = json.loads(path.read_text())
        banded = [
            (b["id"], b["parameters"])
            for b in (cfg.get("biomes") or [])
            if isinstance(b, dict) and b.get("parameters")
        ]
        if not banded:
            continue
        dim_type = cfg.get("type") or "(reserved)"
        noise_settings = cfg.get("noiseSettings") or "-"

        for biome_id, params in banded:
            for name, raw in params.items():
                axis = AXIS_KEY.get(name, name)
                rng = measured_range(table, slug, dim_type, noise_settings, axis)
                if rng is None:
                    skipped.append(f"{slug}/{axis}")
                    continue
                lo, hi, source = rng
                band = raw if isinstance(raw, list) else [raw, raw]
                checked += 1
                if band[1] < lo - MARGIN or band[0] > hi + MARGIN:
                    dead += 1
                    print(
                        f"{slug}: {biome_id} banded {name} "
                        f"[{band[0]:+.3f}, {band[1]:+.3f}] but the world spans "
                        f"[{lo:+.3f}, {hi:+.3f}] ({source}) — it cannot generate"
                    )

    print(f"\nchecked {checked} band(s); {dead} cannot generate")
    if skipped:
        uniq = sorted(set(skipped))
        print(f"no measurement for {len(uniq)} dimension/axis pair(s) — skipped, not guessed")
        for s in uniq[:10]:
            print(f"  {s}")
    if dead and not warn_only:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
