#!/usr/bin/env python3
"""terrain_height.py -- Evaluate Terralith's terrain height from climate parameters.

Evaluates the nested cubic Hermite spline tree from Terralith's offset.json
to compute approximate surface height. For new terrain (no blending):

    offset = -0.5037500262260437 + spline(continentalness, erosion, ridges)
    surface_Y = int(128 * (1 + offset))

Derived from MC's Y-clamped gradient: depth(Y) = 1.5 - 3*(Y+64)/384.
At the surface, offset + depth = 0, so Y = 128 * (1 + offset).
This gives Y=63 at sea level (offset ~ -0.5037, spline ~ 0).

Where ridges_folded = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)

The spline is a 3+ level nested cubic Hermite spline tree extracted from
Terralith's density function JSON. Some branches go 4 levels deep with
self-referencing coordinates (e.g. a ridges spline whose leaf values
reference continentalness or erosion again).

Usage:
    python3 terrain_height.py --extract              # parse raw JSON, save terrain_splines.json
    python3 terrain_height.py                        # test with synthetic climate grid
    python3 terrain_height.py <seed> [x z]           # compute height at (x, z) for seed
"""

import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SPLINES_PATH = SCRIPT_DIR / "terrain_splines.json"

OFFSET_BIAS = -0.5037500262260437

COORD_MAP = {
    "overworld/continents": "continentalness",
    "overworld/erosion": "erosion",
    "overworld/ridges_folded": "ridges_folded",
    "overworld/ridges": "weirdness",
}

_COORD_IDX = {
    "continentalness": 0,
    "erosion": 1,
    "ridges_folded": 2,
    "weirdness": 3,
}


# ---------------------------------------------------------------------------
# Spline extraction from raw density function JSON
# ---------------------------------------------------------------------------

def _extract_spline(node):
    if isinstance(node, (int, float)):
        return float(node)
    coord = COORD_MAP.get(node["coordinate"], node["coordinate"])
    points = []
    for pt in node["points"]:
        value = _extract_spline(pt["value"])
        points.append([pt["location"], pt["derivative"], value])
    return {"c": coord, "p": points}


def _extract_density_function_spline(df_json):
    """Extract the spline node from a density function JSON tree.

    offset.json: flat_cache > cache_2d > add(mul(blend_offset, ...), mul(add(bias, spline), blend_alpha))
    factor.json: flat_cache > cache_2d > add(mul(10, ...), mul(spline, blend_alpha))
    """
    inner = df_json["argument"]["argument"]["argument2"]["argument1"]
    if inner["type"] == "minecraft:add":
        spline_node = inner["argument2"]["spline"]
    elif inner["type"] == "minecraft:spline":
        spline_node = inner["spline"]
    else:
        raise ValueError(f"Unexpected node type: {inner['type']}")
    return _extract_spline(spline_node)


def extract_splines(offset_json_path, factor_json_path=None):
    with open(offset_json_path) as f:
        offset_df = json.load(f)
    result = {"offset": _extract_density_function_spline(offset_df)}
    if factor_json_path:
        with open(factor_json_path) as f:
            factor_df = json.load(f)
        result["factor"] = _extract_density_function_spline(factor_df)
    return result


def load_splines(path=None):
    path = path or SPLINES_PATH
    with open(path) as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# Compiled spline evaluation -- tuple-based for speed
#
# A compiled node is either a float (leaf) or a tuple:
#   (coord_idx, locations, derivatives, values)
# where locations/derivatives are tuples of floats and values is a tuple
# of compiled nodes.
# ---------------------------------------------------------------------------

def _compile_spline(spline):
    if isinstance(spline, (int, float)):
        return float(spline)
    coord_idx = _COORD_IDX[spline["c"]]
    locations = tuple(pt[0] for pt in spline["p"])
    derivatives = tuple(pt[1] for pt in spline["p"])
    values = tuple(_compile_spline(pt[2]) for pt in spline["p"])
    return (coord_idx, locations, derivatives, values)


def _eval_compiled(node, params):
    """Evaluate a compiled spline node.

    params: (continentalness, erosion, ridges_folded, weirdness)
    """
    if isinstance(node, float):
        return node

    coord_idx, locations, derivatives, values = node
    x = params[coord_idx]
    n = len(locations)

    if x <= locations[0]:
        return _eval_compiled(values[0], params)
    if x >= locations[n - 1]:
        return _eval_compiled(values[n - 1], params)

    lo, hi = 0, n - 1
    while lo < hi - 1:
        mid = (lo + hi) >> 1
        if locations[mid] <= x:
            lo = mid
        else:
            hi = mid
    i = lo

    span = locations[i + 1] - locations[i]
    t = (x - locations[i]) / span

    p0 = _eval_compiled(values[i], params)
    p1 = _eval_compiled(values[i + 1], params)
    m0 = derivatives[i] * span
    m1 = derivatives[i + 1] * span

    t2 = t * t
    t3 = t2 * t
    return ((2 * t3 - 3 * t2 + 1) * p0
            + (t3 - 2 * t2 + t) * m0
            + (-2 * t3 + 3 * t2) * p1
            + (t3 - t2) * m1)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def ridges_folded(weirdness):
    return -(abs(abs(weirdness) - 0.6666667) - 0.3333334)


class TerrainEvaluator:
    """Fast terrain height evaluator using compiled spline trees."""

    def __init__(self, spline_data=None, splines_path=None):
        if spline_data is None:
            spline_data = load_splines(splines_path)
        self._offset = _compile_spline(spline_data["offset"])
        self._factor = _compile_spline(spline_data.get("factor", 0.0))

    def _params(self, continentalness, erosion, weirdness):
        rf = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)
        return (continentalness, erosion, rf, weirdness)

    def surface_height(self, continentalness, erosion, weirdness):
        """Compute approximate surface Y from climate parameters."""
        params = self._params(continentalness, erosion, weirdness)
        spline_value = _eval_compiled(self._offset, params)
        offset = OFFSET_BIAS + spline_value
        # Derived from depth(Y) = 1.5 - 3*(Y+64)/384; at surface offset+depth=0
        return int(128 * (1 + offset))

    def factor(self, continentalness, erosion, weirdness):
        """Compute the factor (vertical stretch) from climate parameters."""
        params = self._params(continentalness, erosion, weirdness)
        return _eval_compiled(self._factor, params)

    def offset_raw(self, continentalness, erosion, weirdness):
        """Return the raw offset value (before Y conversion)."""
        params = self._params(continentalness, erosion, weirdness)
        spline_value = _eval_compiled(self._offset, params)
        return OFFSET_BIAS + spline_value


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import time

    if len(sys.argv) >= 2 and sys.argv[1] == "--extract":
        scratchpad = Path(__file__).resolve().parent / ".." / ".."
        raw_dir = Path("/private/tmp/claude-501/-Users-pip-Projects-minecraft-server-template"
                       "/4df14a25-f10e-44dc-b6e3-634f06f857f9/scratchpad"
                       "/terralith_splines/data/minecraft/worldgen/density_function/overworld")
        offset_path = raw_dir / "offset.json"
        factor_path = raw_dir / "factor.json"

        if not offset_path.exists():
            print(f"Error: {offset_path} not found", file=sys.stderr)
            sys.exit(1)

        splines = extract_splines(
            offset_path,
            factor_path if factor_path.exists() else None)

        with open(SPLINES_PATH, 'w') as f:
            json.dump(splines, f, separators=(',', ':'))

        size = SPLINES_PATH.stat().st_size
        print(f"Extracted splines to {SPLINES_PATH} ({size:,} bytes)")
        sys.exit(0)

    if not SPLINES_PATH.exists():
        print(f"Error: {SPLINES_PATH} not found. Run with --extract first.",
              file=sys.stderr)
        sys.exit(1)

    evaluator = TerrainEvaluator()

    if len(sys.argv) >= 2:
        seed = int(sys.argv[1])
        x = int(sys.argv[2]) if len(sys.argv) > 2 else 0
        z = int(sys.argv[3]) if len(sys.argv) > 3 else 0

        from biome_sampler import BiomeSampler

        params_path = SCRIPT_DIR / "biome_params.json"
        if not params_path.exists():
            print(f"Error: {params_path} not found (generated by server warmup)",
                  file=sys.stderr)
            sys.exit(1)
        sampler = BiomeSampler(seed, params_path)
        biome, climate = sampler.biome_and_climate(x, z)

        c = climate["continentalness"]
        e = climate["erosion"]
        w = climate["weirdness"]

        y = evaluator.surface_height(c, e, w)
        fac = evaluator.factor(c, e, w)
        rf = ridges_folded(w)

        print(f"Seed: {seed}  Position: ({x}, {z})")
        print(f"Biome: {biome}")
        print(f"Climate: C={c:.4f} E={e:.4f} W={w:.4f} "
              f"T={climate['temperature']:.4f} H={climate['humidity']:.4f}")
        print(f"Ridges folded: {rf:.4f}")
        print(f"Surface Y: {y}  Factor: {fac:.4f}")
    else:
        print("Testing terrain height evaluator (synthetic climate grid)")
        print()

        t0 = time.time()
        heights = []
        for ci in range(-10, 11):
            c = ci / 10.0
            for ei in range(-10, 11):
                e = ei / 10.0
                for wi in range(-10, 11):
                    w = wi / 10.0
                    h = evaluator.surface_height(c, e, w)
                    heights.append(h)
        elapsed = time.time() - t0

        print(f"Evaluated {len(heights):,} points in {elapsed * 1000:.1f}ms "
              f"({elapsed / len(heights) * 1e6:.1f} us/point)")
        print(f"Height range: {min(heights)} - {max(heights)}")
        print(f"Mean height: {sum(heights) / len(heights):.1f}")

        buckets = {}
        for h in heights:
            bucket = (h // 10) * 10
            buckets[bucket] = buckets.get(bucket, 0) + 1
        print("\nHeight distribution:")
        for bucket in sorted(buckets):
            bar = '#' * (buckets[bucket] // 5)
            print(f"  {bucket:4d}-{bucket + 9:4d}: {buckets[bucket]:4d} {bar}")
