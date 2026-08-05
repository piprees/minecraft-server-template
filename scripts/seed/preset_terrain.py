#!/usr/bin/env python3
"""preset_terrain.py -- Exact climate evaluation for adventure noise presets.

Purpose:  Interprets the noise_router density-function graphs for all six
          climate axes (temperature, vegetation, continents, erosion, ridges,
          depth) from the adventure noise presets and any family whose router
          graph is extractable from the jar walk.

          The adventure presets route ALL climate axes through Tectonic DF
          graphs (temperature/vegetation use xz_scale=0.15 shifted_noise,
          continents/erosion/ridges are full Tectonic spline graphs, depth
          is the existing y-gradient). Evaluating these graphs exactly —
          instead of approximating with vanilla noise IDs — is the only way
          to achieve zero-divergence biome parity.

          Noise evaluation reuses biome_sampler's vanilla-faithful stack
          (Xoroshiro128++, RandomDeriver.from_hash_of id-MD5 seeding,
          DoublePerlinNoiseSampler), so values are exact, not approximate —
          verified against the live server's `customdim sample-noise` and
          `sample-biome-grid` on elfydd.

Gotchas:  - Blending nodes: fresh chunks have blend_alpha=1, blend_offset=0;
            the interpreter hard-codes that (matches generation-time output).
          - minecraft: noise params are NOT in-repo (vanilla-shipped ids are
            deliberately never copied — see gen-terrain-presets.py). The few
            the graph needs are frozen in _VANILLA_NOISE_PARAMS; extend from
            the vanilla jar if an error asks for one.
          - Unknown node types fail LOUD. The supported set covers the
            Terratonic graphs; a Tectonic pin bump that introduces new
            node types should fail here rather than render nonsense.
          - Pure Python: ~8 noises per column per axis. Six axes cost ~6x
            the single-depth path but the column memo amortises shared
            subgraphs (shift_x/shift_z, island/continent selectors).
"""

import json
from pathlib import Path

from biome_sampler import (
    Xoroshiro128PlusPlus,
    DoublePerlinNoiseSampler,
)

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
RES_DATA = REPO_ROOT / "mods/custom-dimensions/src/main/resources/data"

PRESET_SETTINGS = {
    "adventure:wide": RES_DATA / "adventure/worldgen/noise_settings/wide.json",
    "adventure:compressed": RES_DATA / "adventure/worldgen/noise_settings/compressed.json",
}

# Vanilla-shipped noises referenced by the preset graphs (never copied
# in-repo by design — see gen-terrain-presets.py). Extracted from
# server-1.21.1.jar. Caveat: on a modded server Terralith OVERRIDES
# temperature/vegetation, so subtrees gated on those (depth_additive's
# jungle-pillar band) can diverge slightly from a Terralith-modded world;
# depth itself is exact against pure-vanilla evaluation.
_VANILLA_NOISE_PARAMS = {
    "minecraft:offset": {"firstOctave": -3, "amplitudes": [1.0, 1.0, 1.0, 0.0]},
    "minecraft:temperature": {"firstOctave": -10, "amplitudes": [1.5, 0.0, 1.0, 0.0, 0.0, 0.0]},
    "minecraft:vegetation": {"firstOctave": -8, "amplitudes": [1.0, 1.0, 0.0, 0.0, 0.0, 0.0]},
    "minecraft:continentalness": {"firstOctave": -9, "amplitudes": [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0]},
    "minecraft:erosion": {"firstOctave": -9, "amplitudes": [1.0, 1.0, 0.0, 1.0, 1.0]},
    "minecraft:ridge": {"firstOctave": -7, "amplitudes": [1.0, 2.0, 1.0, 0.0, 0.0, 0.0]},
}

_CACHE_WRAPPERS = {
    "minecraft:flat_cache", "minecraft:cache_2d", "minecraft:cache_once",
    "minecraft:interpolated", "minecraft:cache_all_in_cell",
}

_SHIFT_TYPES = {"minecraft:shift_a", "minecraft:shift_b", "minecraft:shift"}


def supported_presets():
    return sorted(k for k, p in PRESET_SETTINGS.items() if p.is_file())


ROUTER_CLIMATE_FIELDS = {
    "temperature": "temperature",
    "vegetation": "vegetation",
    "continents": "continents",
    "erosion": "erosion",
    "ridges": "ridges",
    "depth": "depth",
}

ROUTER_TO_BIOME_AXIS = {
    "temperature": "temperature",
    "vegetation": "humidity",
    "continents": "continentalness",
    "erosion": "erosion",
    "ridges": "weirdness",
    "depth": "depth",
}


class PresetTerrainEvaluator:
    """Evaluate noise_router density functions for one world seed.

    Supports all six climate router fields (temperature, vegetation,
    continents, erosion, ridges, depth). Accepts either a preset id
    string (resolved from PRESET_SETTINGS) or a pre-loaded settings dict.
    When data_roots is provided, density functions and noises are resolved
    from those directories (in order) alongside the in-repo RES_DATA.
    """

    def __init__(self, preset_id_or_settings, seed, data_roots=None):
        if isinstance(preset_id_or_settings, dict):
            settings = preset_id_or_settings
        else:
            settings_path = PRESET_SETTINGS.get(preset_id_or_settings)
            if settings_path is None or not settings_path.is_file():
                raise ValueError(f"unknown preset {preset_id_or_settings!r}")
            settings = json.loads(settings_path.read_text())
        router = settings["noise_router"]
        self._root_ref = router["depth"]
        self._router_refs = {}
        for field in ROUTER_CLIMATE_FIELDS:
            if field in router:
                self._router_refs[field] = router[field]
        self._data_roots = list(data_roots or []) + [RES_DATA]

        deriver = Xoroshiro128PlusPlus(int(seed)).fork()
        self._deriver = deriver
        self._noises = {}      # noise id -> DoublePerlinNoiseSampler
        self._df_cache = {}    # df id -> parsed JSON
        self._column_memo = {}  # per-(x,z) df-ref value memo (reset per column)

    # -- resolution ---------------------------------------------------------

    def _noise(self, noise_id):
        sampler = self._noises.get(noise_id)
        if sampler is None:
            ns, _, path = noise_id.partition(":")
            params = None
            for root in self._data_roots:
                f = root / ns / "worldgen/noise" / (path + ".json")
                if f.is_file():
                    params = json.loads(f.read_text())
                    break
            if params is None:
                if noise_id in _VANILLA_NOISE_PARAMS:
                    params = _VANILLA_NOISE_PARAMS[noise_id]
                else:
                    raise ValueError(
                        f"noise {noise_id} not found in any data root "
                        f"and not in _VANILLA_NOISE_PARAMS")
            rng = self._deriver.from_hash_of(noise_id)
            sampler = DoublePerlinNoiseSampler(
                rng, params["firstOctave"], params["amplitudes"])
            self._noises[noise_id] = sampler
        return sampler

    def _df_body(self, df_id):
        body = self._df_cache.get(df_id)
        if body is None:
            ns, _, path = df_id.partition(":")
            for root in self._data_roots:
                f = root / ns / "worldgen/density_function" / (path + ".json")
                if f.is_file():
                    body = json.loads(f.read_text())
                    break
            if body is None:
                raise ValueError(
                    f"density function {df_id} not found in any data root")
            self._df_cache[df_id] = body
        return body

    # -- evaluation ---------------------------------------------------------

    def _shift_x(self, x, z):
        # vanilla shift_x = flat_cache(cache_2d(shift_a(minecraft:offset)));
        # shift_a samples (x*0.25, 0, z*0.25) * 4
        return self._noise("minecraft:offset").sample(x * 0.25, 0.0, z * 0.25) * 4.0

    def _shift_z(self, x, z):
        # shift_b samples (z*0.25, x*0.25, 0) * 4
        return self._noise("minecraft:offset").sample(z * 0.25, x * 0.25, 0.0) * 4.0

    def _eval(self, node, x, y, z):
        if isinstance(node, (int, float)):
            return float(node)
        if isinstance(node, str):
            if node == "minecraft:shift_x":
                return self._shift_x(x, z)
            if node == "minecraft:shift_z":
                return self._shift_z(x, z)
            if node == "minecraft:y":
                return float(y)
            if node == "minecraft:zero":
                return 0.0
            memo = self._column_memo
            v = memo.get(node)
            if v is None:
                v = self._eval(self._df_body(node), x, y, z)
                memo[node] = v
            return v

        t = node["type"] if "type" in node else node.get("type", "")
        if t in _CACHE_WRAPPERS:
            return self._eval(node["argument"], x, y, z)
        if t == "minecraft:constant":
            return float(node["argument"])
        if t == "minecraft:add":
            return (self._eval(node["argument1"], x, y, z)
                    + self._eval(node["argument2"], x, y, z))
        if t == "minecraft:mul":
            a = self._eval(node["argument1"], x, y, z)
            if a == 0.0:
                return 0.0
            return a * self._eval(node["argument2"], x, y, z)
        if t == "minecraft:min":
            return min(self._eval(node["argument1"], x, y, z),
                       self._eval(node["argument2"], x, y, z))
        if t == "minecraft:max":
            return max(self._eval(node["argument1"], x, y, z),
                       self._eval(node["argument2"], x, y, z))
        if t == "minecraft:clamp":
            v = self._eval(node["input"], x, y, z)
            return max(float(node["min"]), min(float(node["max"]), v))
        if t == "minecraft:abs":
            return abs(self._eval(node["argument"], x, y, z))
        if t == "minecraft:square":
            v = self._eval(node["argument"], x, y, z)
            return v * v
        if t == "minecraft:cube":
            v = self._eval(node["argument"], x, y, z)
            return v * v * v
        if t == "minecraft:half_negative":
            v = self._eval(node["argument"], x, y, z)
            return v if v > 0 else v * 0.5
        if t == "minecraft:quarter_negative":
            v = self._eval(node["argument"], x, y, z)
            return v if v > 0 else v * 0.25
        if t == "minecraft:squeeze":
            v = self._eval(node["argument"], x, y, z)
            c = max(-1.0, min(1.0, v))
            return c / 2.0 - c * c * c / 24.0
        if t == "minecraft:y_clamped_gradient":
            from_y, to_y = float(node["from_y"]), float(node["to_y"])
            from_v, to_v = float(node["from_value"]), float(node["to_value"])
            cy = max(from_y, min(to_y, float(y)))
            tt = (cy - from_y) / (to_y - from_y)
            return from_v + tt * (to_v - from_v)
        if t == "minecraft:range_choice":
            v = self._eval(node["input"], x, y, z)
            if float(node["min_inclusive"]) <= v < float(node["max_exclusive"]):
                return self._eval(node["when_in_range"], x, y, z)
            return self._eval(node["when_out_of_range"], x, y, z)
        if t == "minecraft:blend_alpha":
            return 1.0   # fresh chunks: no blending
        if t == "minecraft:blend_offset":
            return 0.0
        if t == "minecraft:blend_density":
            return self._eval(node["argument"], x, y, z)
        if t == "minecraft:shifted_noise":
            sx = self._eval(node.get("shift_x", 0.0), x, y, z)
            sy = self._eval(node.get("shift_y", 0.0), x, y, z)
            sz = self._eval(node.get("shift_z", 0.0), x, y, z)
            xz = float(node.get("xz_scale", 1.0))
            ys = float(node.get("y_scale", 1.0))
            return self._noise(node["noise"]).sample(
                x * xz + sx, y * ys + sy, z * xz + sz)
        if t == "minecraft:noise":
            xz = float(node.get("xz_scale", 1.0))
            ys = float(node.get("y_scale", 1.0))
            return self._noise(node["noise"]).sample(x * xz, y * ys, z * xz)
        if t in _SHIFT_TYPES:
            n = self._noise(node["argument"])
            if t == "minecraft:shift_a":
                return n.sample(x * 0.25, 0.0, z * 0.25) * 4.0
            if t == "minecraft:shift_b":
                return n.sample(z * 0.25, x * 0.25, 0.0) * 4.0
            return n.sample(x * 0.25, y * 0.25, z * 0.25) * 4.0
        if t == "minecraft:spline":
            return self._eval_spline(node["spline"], x, y, z)

        raise ValueError(f"unsupported density-function node type: {t!r}")

    def _eval_spline(self, spline, x, y, z):
        if isinstance(spline, (int, float)):
            return float(spline)
        cx = self._eval(spline["coordinate"], x, y, z)
        pts = spline["points"]
        n = len(pts)
        locs = [p["location"] for p in pts]

        # Vanilla CubicSpline EXTRAPOLATES LINEARLY beyond the endpoints
        # using the endpoint derivative — clamping instead flattens any
        # spline with non-zero edge derivatives (tectonic's full_continents
        # is `derivative: 1` at both ends: effectively an identity band).
        if cx < locs[0]:
            return (self._eval_spline(pts[0]["value"], x, y, z)
                    + pts[0]["derivative"] * (cx - locs[0]))
        if cx > locs[n - 1]:
            return (self._eval_spline(pts[n - 1]["value"], x, y, z)
                    + pts[n - 1]["derivative"] * (cx - locs[n - 1]))
        if cx == locs[0]:
            return self._eval_spline(pts[0]["value"], x, y, z)

        lo, hi = 0, n - 1
        while lo < hi - 1:
            mid = (lo + hi) >> 1
            if locs[mid] <= cx:
                lo = mid
            else:
                hi = mid
        i = lo
        span = locs[i + 1] - locs[i]
        tt = (cx - locs[i]) / span
        p0 = self._eval_spline(pts[i]["value"], x, y, z)
        p1 = self._eval_spline(pts[i + 1]["value"], x, y, z)
        m0 = pts[i]["derivative"] * span
        m1 = pts[i + 1]["derivative"] * span
        t2 = tt * tt
        t3 = t2 * tt
        return ((2 * t3 - 3 * t2 + 1) * p0
                + (t3 - 2 * t2 + tt) * m0
                + (-2 * t3 + 3 * t2) * p1
                + (t3 - t2) * m1)

    # -- public API ---------------------------------------------------------

    # The oracle (sample-biome-grid, DimensionCommands.java line 946) calls
    # biomeSource.getBiome(qx, 16, qz, sampler) — quart y=16. The
    # MultiNoiseSampler passes quart coords through UnblendedNoisePos
    # WITHOUT converting to block, so the DF nodes see quart y=16.
    # The block-space constant (64) is kept for backward compatibility
    # with depth() and depth_for_biome() which operate in block space.
    BIOME_SAMPLE_BLOCK_Y = 64
    BIOME_SAMPLE_QUART_Y = 16

    def router_fields(self):
        """The router fields this evaluator can compute."""
        return set(self._router_refs.keys())

    def evaluate_router(self, field, x, z, y=None):
        """Evaluate one router field at (x, y, z).

        When y is None, uses BIOME_SAMPLE_BLOCK_Y (the biome oracle's y).
        Resets the column memo so shared subgraphs are recomputed per call;
        callers evaluating multiple fields at the same (x, z) should use
        evaluate_climate instead.
        """
        ref = self._router_refs.get(field)
        if ref is None:
            raise ValueError(f"router field {field!r} not available")
        if y is None:
            y = self.BIOME_SAMPLE_BLOCK_Y
        self._column_memo = {}
        return self._eval(ref, float(x), float(y), float(z))

    def evaluate_climate(self, x, z, y=None):
        """Evaluate all available router climate fields at (x, z) in one pass.

        Called with QUART coordinates (the BiomeSampler batch path converts
        block to quart before calling). The default y is quart 16, matching
        getBiome(qx, 16, qz, sampler) in the Java oracle.

        Returns {biome_axis_name: value} using ROUTER_TO_BIOME_AXIS naming
        (temperature, humidity, continentalness, erosion, weirdness, depth).
        The column memo is shared across all fields so overlapping DF
        subgraphs (shift noises, selectors) are evaluated once.
        """
        if y is None:
            y = self.BIOME_SAMPLE_BLOCK_Y
        self._column_memo = {}
        result = {}
        for field, ref in self._router_refs.items():
            axis = ROUTER_TO_BIOME_AXIS[field]
            result[axis] = self._eval(ref, float(x), float(y), float(z))
        return result

    def depth(self, x, z, y=0):
        """Router depth at (x, y, z) — matches `customdim sample-noise`'s
        depth field when sampled at quarter-aligned coordinates and y=0."""
        self._column_memo = {}
        return self._eval(self._root_ref, float(x), float(y), float(z))

    def depth_for_biome(self, x, z):
        """Router depth at the oracle's biome-sampling y (block y=64).

        sample-biome-grid passes quart y=16 to getBiome; vanilla converts
        to block y=64 via QuartPos.toBlock before evaluating the density
        function. The evaluator operates in block space.
        """
        return self.depth(x, z, y=self.BIOME_SAMPLE_BLOCK_Y)

    def surface_height(self, x, z):
        """Approximate surface Y: 128 * depth(y=0) = 128 * (1 + offset)."""
        return int(128.0 * self.depth(x, z))


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 3:
        print("Usage: preset_terrain.py <preset_id> <seed> [x z]", file=sys.stderr)
        print(f"  presets: {supported_presets()}", file=sys.stderr)
        sys.exit(1)
    ev = PresetTerrainEvaluator(sys.argv[1], int(sys.argv[2]))
    px = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    pz = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    d = ev.depth(px, pz)
    print(f"depth(y=0) = {d:.6f}   offset = {d - 1.0:.6f}   surface_Y = {ev.surface_height(px, pz)}")
