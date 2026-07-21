#!/usr/bin/env python3
"""terrain_height.py -- Evaluate terrain height from climate parameters.

Evaluates nested cubic Hermite spline trees extracted from modded density
function JSON to compute approximate surface height per dimension family:

  - overworld (Terralith): offset + factor splines
  - nether (Incendium): offset + factor splines
  - end (Nullscape): offset + factor splines
  - paradise_lost: delegates to overworld spline (no custom splines)

For all families the surface height formula is the same:

    offset = -0.5037500262260437 + spline(continentalness, erosion, weirdness)
    surface_Y = int(128 * (1 + offset))

The overworld spline uses ridges_folded (a transform of weirdness) as a
coordinate; nether and end splines use raw weirdness directly.

Usage:
    python3 terrain_height.py --extract              # extract from mod JARs, save terrain_splines.json
    python3 terrain_height.py                        # test with synthetic climate grid
    python3 terrain_height.py <seed> [x z]           # compute height at (x, z) for seed
"""

import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SPLINES_PATH = SCRIPT_DIR / "terrain_splines.json"

OFFSET_BIAS = -0.5037500262260437

COORD_MAPS = {
    "overworld": {
        "overworld/continents": "continentalness",
        "overworld/erosion": "erosion",
        "overworld/ridges_folded": "ridges_folded",
        "overworld/ridges": "weirdness",
    },
    "nether": {
        "incendium:climate/continentalness": "continentalness",
        "incendium:climate/erosion": "erosion",
        "incendium:climate/weirdness": "weirdness",
        "incendium:climate/purity": "purity",
    },
    "end": {
        "nullscape:base/continents": "continentalness",
        "minecraft:overworld/continents": "continentalness",
        "minecraft:overworld/erosion": "erosion",
        "minecraft:overworld/ridges_folded": "ridges_folded",
        "minecraft:overworld/ridges": "weirdness",
    },
}
COORD_MAP = COORD_MAPS["overworld"]

_COORD_IDX = {
    "continentalness": 0,
    "erosion": 1,
    "ridges_folded": 2,
    "weirdness": 3,
}

_FAMILIES_WITH_SPLINES = ("overworld", "nether", "end")

# Nether/end splines produce extreme negatives for void/lava columns (offset
# -22 → height -2784). Clamping the final Y to the dimension's physical range
# prevents 2800-block gradients while preserving all real terrain detail.
_FAMILY_HEIGHT_CLAMP = {
    "nether": (0, 128),
    "end": (0, 200),
}


# ---------------------------------------------------------------------------
# Spline extraction from raw density function JSON
# ---------------------------------------------------------------------------

def _extract_spline(node, coord_map=None):
    if coord_map is None:
        coord_map = COORD_MAP
    if isinstance(node, (int, float)):
        return float(node)
    coord = coord_map.get(node["coordinate"], node["coordinate"])
    points = []
    for pt in node["points"]:
        value = _extract_spline(pt["value"], coord_map)
        points.append([pt["location"], pt["derivative"], value])
    return {"c": coord, "p": points}


def _find_spline_node(obj):
    """Recursively find the first minecraft:spline node in a density function tree."""
    if isinstance(obj, (int, float, str, bool)) or obj is None:
        return None
    if isinstance(obj, list):
        for x in obj:
            r = _find_spline_node(x)
            if r is not None:
                return r
        return None
    if isinstance(obj, dict):
        if obj.get("type") == "minecraft:spline":
            return obj["spline"]
        for v in obj.values():
            r = _find_spline_node(v)
            if r is not None:
                return r
    return None


def _extract_density_function_spline(df_json, coord_map=None):
    """Extract the spline node from a density function JSON tree."""
    spline_node = _find_spline_node(df_json)
    if spline_node is None:
        raise ValueError("No minecraft:spline node found in density function")
    return _extract_spline(spline_node, coord_map)


def _extract_family(offset_path, factor_path, coord_map):
    """Extract offset and factor splines for one dimension family."""
    with open(offset_path) as f:
        offset_df = json.load(f)
    result = {"offset": _extract_density_function_spline(offset_df, coord_map)}
    if factor_path and Path(factor_path).exists():
        with open(factor_path) as f:
            factor_df = json.load(f)
        result["factor"] = _extract_density_function_spline(factor_df, coord_map)
    return result


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
    """Fast terrain height evaluator using compiled spline trees.

    Loads per-family splines (overworld, nether, end) and evaluates terrain
    height using the correct coordinate transform for each family.
    """

    def __init__(self, spline_data=None, splines_path=None):
        if spline_data is None:
            spline_data = load_splines(splines_path)
        # Support both legacy flat format and new per-family format
        if "overworld" in spline_data:
            self._families = {}
            for fam in _FAMILIES_WITH_SPLINES:
                if fam in spline_data:
                    fam_data = spline_data[fam]
                    self._families[fam] = (
                        _compile_spline(fam_data["offset"]),
                        _compile_spline(fam_data.get("factor", 0.0)),
                    )
        else:
            self._families = {
                "overworld": (
                    _compile_spline(spline_data["offset"]),
                    _compile_spline(spline_data.get("factor", 0.0)),
                ),
            }

    def has_family(self, family):
        if family == "paradise_lost":
            return "overworld" in self._families
        return family in self._families

    def _params(self, continentalness, erosion, weirdness):
        rf = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)
        return (continentalness, erosion, rf, weirdness)

    def surface_height(self, continentalness, erosion, weirdness, family="overworld"):
        """Compute approximate surface Y from climate parameters."""
        if family == "paradise_lost":
            family = "overworld"
        offset_tree, _ = self._families[family]
        params = self._params(continentalness, erosion, weirdness)
        spline_value = _eval_compiled(offset_tree, params)
        offset = OFFSET_BIAS + spline_value
        h = int(128 * (1 + offset))
        clamp = _FAMILY_HEIGHT_CLAMP.get(family)
        if clamp:
            if h < clamp[0]:
                h = clamp[0]
            elif h > clamp[1]:
                h = clamp[1]
        return h

    def factor(self, continentalness, erosion, weirdness, family="overworld"):
        """Compute the factor (vertical stretch) from climate parameters."""
        if family == "paradise_lost":
            family = "overworld"
        _, factor_tree = self._families[family]
        params = self._params(continentalness, erosion, weirdness)
        return _eval_compiled(factor_tree, params)

    def offset_raw(self, continentalness, erosion, weirdness, family="overworld"):
        """Return the raw offset value (before Y conversion)."""
        if family == "paradise_lost":
            family = "overworld"
        offset_tree, _ = self._families[family]
        params = self._params(continentalness, erosion, weirdness)
        spline_value = _eval_compiled(offset_tree, params)
        return OFFSET_BIAS + spline_value


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def extract_all_from_jars(mods_dir):
    """Extract splines from all mod JARs into per-family structure.

    Expected JARs:
      - Terralith: overworld offset/factor in data/minecraft/worldgen/density_function/overworld/
      - Incendium: nether depth/factor in data/incendium/worldgen/density_function/climate/
      - Nullscape: end depth in data/nullscape/worldgen/density_function/depth.json,
                   end factor in data/nullscape/worldgen/density_function/base/factor.json
    """
    import tempfile
    import zipfile

    mods_dir = Path(mods_dir)
    result = {}

    # Terralith (overworld)
    terralith_jars = list(mods_dir.glob("Terralith*.jar"))
    if terralith_jars:
        jar = terralith_jars[0]
        with tempfile.TemporaryDirectory() as tmp:
            with zipfile.ZipFile(jar) as zf:
                for name in ("data/minecraft/worldgen/density_function/overworld/offset.json",
                             "data/minecraft/worldgen/density_function/overworld/factor.json"):
                    if name in zf.namelist():
                        zf.extract(name, tmp)
            offset_path = Path(tmp) / "data/minecraft/worldgen/density_function/overworld/offset.json"
            factor_path = Path(tmp) / "data/minecraft/worldgen/density_function/overworld/factor.json"
            if offset_path.exists():
                result["overworld"] = _extract_family(
                    offset_path, factor_path, COORD_MAPS["overworld"])
                print(f"  overworld: extracted from {jar.name}")

    # Incendium (nether)
    incendium_jars = list(mods_dir.glob("Incendium*.jar"))
    if incendium_jars:
        jar = incendium_jars[0]
        with tempfile.TemporaryDirectory() as tmp:
            with zipfile.ZipFile(jar) as zf:
                for name in ("data/incendium/worldgen/density_function/climate/depth.json",
                             "data/incendium/worldgen/density_function/climate/factor.json"):
                    if name in zf.namelist():
                        zf.extract(name, tmp)
            depth_path = Path(tmp) / "data/incendium/worldgen/density_function/climate/depth.json"
            factor_path = Path(tmp) / "data/incendium/worldgen/density_function/climate/factor.json"
            if depth_path.exists():
                result["nether"] = _extract_family(
                    depth_path, factor_path, COORD_MAPS["nether"])
                print(f"  nether: extracted from {jar.name}")

    # Nullscape (end)
    nullscape_jars = list(mods_dir.glob("Nullscape*.jar"))
    if nullscape_jars:
        jar = nullscape_jars[0]
        with tempfile.TemporaryDirectory() as tmp:
            with zipfile.ZipFile(jar) as zf:
                for name in ("data/nullscape/worldgen/density_function/depth.json",
                             "data/nullscape/worldgen/density_function/base/factor.json"):
                    if name in zf.namelist():
                        zf.extract(name, tmp)
            depth_path = Path(tmp) / "data/nullscape/worldgen/density_function/depth.json"
            factor_path = Path(tmp) / "data/nullscape/worldgen/density_function/base/factor.json"
            if depth_path.exists():
                result["end"] = _extract_family(
                    depth_path, factor_path, COORD_MAPS["end"])
                print(f"  end: extracted from {jar.name}")

    return result


if __name__ == "__main__":
    import time

    if len(sys.argv) >= 2 and sys.argv[1] == "--extract":
        mods_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None
        if mods_dir and mods_dir.is_dir():
            print("Extracting splines from mod JARs...")
            splines = extract_all_from_jars(mods_dir)
        else:
            print("Usage: terrain_height.py --extract <mods_dir>", file=sys.stderr)
            print("  mods_dir: directory containing Terralith, Incendium, Nullscape JARs",
                  file=sys.stderr)
            sys.exit(1)

        if not splines:
            print("Error: no splines extracted", file=sys.stderr)
            sys.exit(1)

        with open(SPLINES_PATH, 'w') as f:
            json.dump(splines, f, separators=(',', ':'))

        size = SPLINES_PATH.stat().st_size
        print(f"Wrote {SPLINES_PATH} ({size:,} bytes)")
        for fam, data in splines.items():
            print(f"  {fam}: offset + {'factor' if 'factor' in data else 'no factor'}")
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

        for fam in ("overworld", "nether", "end"):
            if not evaluator.has_family(fam):
                continue
            t0 = time.time()
            heights = []
            for ci in range(-10, 11):
                c = ci / 10.0
                for ei in range(-10, 11):
                    e = ei / 10.0
                    for wi in range(-10, 11):
                        w = wi / 10.0
                        h = evaluator.surface_height(c, e, w, family=fam)
                        heights.append(h)
            elapsed = time.time() - t0

            print(f"[{fam}] {len(heights):,} points in {elapsed * 1000:.1f}ms "
                  f"({elapsed / len(heights) * 1e6:.1f} us/point)")
            print(f"  Height range: {min(heights)} - {max(heights)}")
            print(f"  Mean: {sum(heights) / len(heights):.1f}")
            print()
