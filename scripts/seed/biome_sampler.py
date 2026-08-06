#!/usr/bin/env python3
"""biome_sampler.py — Pure-Python biome sampling for seed rolling.

Reimplements vanilla's MultiNoiseBiomeSource: Perlin noise → 6D climate
parameters → nearest-neighbour biome lookup. No server, no RCON, no chunk
generation — runs in <1ms per point.

The biome parameter table is dumped from the modded server via
`/customdim dump-biome-params` (captures TerraBlender + all mod biomes).
Noise configs are vanilla 1.21.1 (mods don't modify climate noise).

Algorithm matches net.minecraft.util.math.noise and
net.minecraft.world.biome.source.util.MultiNoiseUtil in 1.21.1 (Yarn).
"""

import json
import math
import hashlib
import struct
from pathlib import Path


# ---------------------------------------------------------------------------
# Unsigned / signed 64-bit helpers (match Java long semantics)
# ---------------------------------------------------------------------------
def _u64(x):
    return x & 0xFFFFFFFFFFFFFFFF

def _i64(x):
    x = _u64(x)
    return x - 0x10000000000000000 if x >= 0x8000000000000000 else x

def _rotl64(x, k):
    x = _u64(x)
    return _u64((x << k) | (x >> (64 - k)))


# ---------------------------------------------------------------------------
# Xoroshiro128++ PRNG — MC's random for noise generation
# Matches net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom
# ---------------------------------------------------------------------------
_GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15
_SILVER_RATIO_64 = 0x6A09E667F3BCC909


def _mix_stafford_13(z):
    z = _u64(z)
    z = _u64((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9)
    z = _u64((z ^ (z >> 27)) * 0x94D049BB133111EB)
    return z ^ (z >> 31)


class Xoroshiro128PlusPlus:
    def __init__(self, seed_lo, seed_hi=None):
        if seed_hi is None:
            # MC: createUnmixedXoroshiroSeed(seed).mix()
            unmixed_lo = _u64(seed_lo) ^ _SILVER_RATIO_64
            unmixed_hi = _u64(unmixed_lo + _GOLDEN_RATIO_64)
            lo = _mix_stafford_13(unmixed_lo)
            hi = _mix_stafford_13(unmixed_hi)
        else:
            lo = _u64(seed_lo)
            hi = _u64(seed_hi)
        if lo == 0 and hi == 0:
            lo = _GOLDEN_RATIO_64
            hi = _SILVER_RATIO_64
        self.lo = lo
        self.hi = hi

    def next_long(self):
        lo, hi = self.lo, self.hi
        result = _u64(_rotl64(_u64(lo + hi), 17) + lo)
        hi = _u64(hi ^ lo)
        self.lo = _u64(_rotl64(lo, 49) ^ hi ^ _u64(hi << 21))
        self.hi = _rotl64(hi, 28)
        return _i64(result)

    def next_int(self, bound):
        # MC bytecode: nextInt() = (int)implementation.next() — l2i takes
        # the BOTTOM 32 bits, not the top. Integer.toUnsignedLong converts
        # the signed int to unsigned for the multiply-shift.
        if bound <= 0:
            return 0
        r = _u64(self.next_long()) & 0xFFFFFFFF
        m = r * bound
        low = m & 0xFFFFFFFF
        if low < bound:
            threshold = (0x100000000 - bound) % bound
            while low < threshold:
                r = _u64(self.next_long()) & 0xFFFFFFFF
                m = r * bound
                low = m & 0xFFFFFFFF
        return int(m >> 32)

    def next_double(self):
        return (_u64(self.next_long()) >> 11) * 1.1102230246251565e-16

    def fork(self):
        return RandomDeriver(self.next_long(), self.next_long())

    def skip(self, count):
        for _ in range(count):
            self.next_long()


class RandomDeriver:
    def __init__(self, lo, hi):
        self.lo = _u64(lo)
        self.hi = _u64(hi)

    def from_hash_of(self, string):
        # MC: RandomSeed.createXoroshiroSeed(String) → XoroshiroSeed
        # MD5 all 16 bytes → lo from bytes 0-7, hi from bytes 8-15
        # Then XOR each with the deriver's seeds
        hash_lo, hash_hi = _string_to_seed(string)
        return Xoroshiro128PlusPlus(_u64(hash_lo) ^ self.lo,
                                    _u64(hash_hi) ^ self.hi)


def _string_to_seed(s):
    """MD5 → two big-endian longs (lo from bytes 0-7, hi from bytes 8-15).
    Matches RandomSeed.createXoroshiroSeed(String) in MC 1.21.1."""
    md5 = hashlib.md5(s.encode('utf-8')).digest()
    lo = struct.unpack('>q', md5[:8])[0]
    hi = struct.unpack('>q', md5[8:16])[0]
    return lo, hi


# ---------------------------------------------------------------------------
# Improved Noise Sampler — Ken Perlin's improved noise (2002)
# Matches net.minecraft.util.math.noise.SimplexNoiseSampler is NOT used;
# this matches net.minecraft.util.math.noise.PerlinNoiseSampler.
# ---------------------------------------------------------------------------
def _perlin_fade(t):
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

def _lerp(t, a, b):
    return a + t * (b - a)

def _grad(hash_val, x, y, z):
    h = hash_val & 15
    u = x if h < 8 else y
    v = y if h < 4 else (x if h == 12 or h == 14 else z)
    return (u if (h & 1) == 0 else -u) + (v if (h & 2) == 0 else -v)


class ImprovedNoiseSampler:
    def __init__(self, random):
        self.origin_x = random.next_double() * 256.0
        self.origin_y = random.next_double() * 256.0
        self.origin_z = random.next_double() * 256.0
        p = list(range(256))
        for i in range(256):
            j = random.next_int(256 - i)
            p[i], p[i + j] = p[i + j], p[i]
        self.permutations = p

    def _p(self, idx):
        return self.permutations[idx & 255]

    def sample(self, x, y, z):
        xs = x + self.origin_x
        ys = y + self.origin_y
        zs = z + self.origin_z

        xi = int(math.floor(xs))
        yi = int(math.floor(ys))
        zi = int(math.floor(zs))

        xf = xs - xi
        yf = ys - yi
        zf = zs - zi

        u = _perlin_fade(xf)
        v = _perlin_fade(yf)
        w = _perlin_fade(zf)

        a  = self._p(xi) + yi
        aa = self._p(a) + zi
        ab = self._p(a + 1) + zi
        b  = self._p(xi + 1) + yi
        ba = self._p(b) + zi
        bb = self._p(b + 1) + zi

        return _lerp(w,
            _lerp(v,
                _lerp(u, _grad(self._p(aa),     xf,     yf,     zf),
                         _grad(self._p(ba),     xf - 1, yf,     zf)),
                _lerp(u, _grad(self._p(ab),     xf,     yf - 1, zf),
                         _grad(self._p(bb),     xf - 1, yf - 1, zf))),
            _lerp(v,
                _lerp(u, _grad(self._p(aa + 1), xf,     yf,     zf - 1),
                         _grad(self._p(ba + 1), xf - 1, yf,     zf - 1)),
                _lerp(u, _grad(self._p(ab + 1), xf,     yf - 1, zf - 1),
                         _grad(self._p(bb + 1), xf - 1, yf - 1, zf - 1))))


# ---------------------------------------------------------------------------
# Octave Perlin Noise Sampler — sum of N scaled octaves
# Matches net.minecraft.util.math.noise.OctavePerlinNoiseSampler
# ---------------------------------------------------------------------------
_WRAP_PERIOD = 3.3554432e7  # 2^25


def _wrap(value):
    return value - math.floor(value / _WRAP_PERIOD + 0.5) * _WRAP_PERIOD


class OctavePerlinNoiseSampler:
    def __init__(self, random, first_octave, amplitudes):
        self.first_octave = first_octave
        self.amplitudes = amplitudes
        n = len(amplitudes)

        # MC bytecode: persistence = 2^(n-1) / (2^n - 1)
        self.lacunarity = 2.0 ** first_octave
        self.persistence = (2.0 ** (n - 1)) / (2.0 ** n - 1.0)

        # MC uses splitter-based seeding (new path, not legacy):
        # splitter = random.nextSplitter(); each octave gets
        # splitter.split("octave_" + (firstOctave + i))
        deriver = random.fork()
        self.samplers = []
        for i in range(n):
            if amplitudes[i] != 0.0:
                octave_rng = deriver.from_hash_of(f"octave_{first_octave + i}")
                self.samplers.append(ImprovedNoiseSampler(octave_rng))
            else:
                self.samplers.append(None)

        # MC bytecode: maxValue = getTotalAmplitude(2.0)
        max_val = 0.0
        p = self.persistence
        for i in range(n):
            if self.samplers[i] is not None:
                max_val += amplitudes[i] * 2.0 * p
            p /= 2.0
        self.max_value = max_val

    def sample(self, x, y, z):
        value = 0.0
        lacunarity = self.lacunarity
        persistence = self.persistence
        for i, amp in enumerate(self.amplitudes):
            if amp != 0.0 and self.samplers[i] is not None:
                v = self.samplers[i].sample(
                    _wrap(x * lacunarity),
                    _wrap(y * lacunarity),
                    _wrap(z * lacunarity))
                value += amp * v * persistence
            lacunarity *= 2.0
            persistence /= 2.0
        return value


# ---------------------------------------------------------------------------
# Double Perlin Noise Sampler — two octave samplers combined
# Matches net.minecraft.util.math.noise.DoublePerlinNoiseSampler
# ---------------------------------------------------------------------------
_DOUBLE_PERLIN_SHIFT = 337.0 / 331.0  # ≈ 1.0181268882175227


def _create_amplitude(n):
    """MC bytecode: 0.1 * (1.0 + 1.0 / (n + 1))"""
    return 0.1 * (1.0 + 1.0 / (n + 1))


class DoublePerlinNoiseSampler:
    def __init__(self, random, first_octave, amplitudes):
        self.first = OctavePerlinNoiseSampler(random, first_octave, amplitudes)
        self.second = OctavePerlinNoiseSampler(random, first_octave, amplitudes)

        # MC bytecode: amplitude = (1/6) / createAmplitude(maxIdx - minIdx)
        # where min/maxIdx are the first/last non-zero amplitude indices
        min_idx = len(amplitudes)
        max_idx = -1
        for i, a in enumerate(amplitudes):
            if a != 0.0:
                min_idx = min(min_idx, i)
                max_idx = max(max_idx, i)
        span = max_idx - min_idx if max_idx >= min_idx else 0
        self.amplitude = (1.0 / 6.0) / _create_amplitude(span)

    def sample(self, x, y, z):
        d = x * _DOUBLE_PERLIN_SHIFT
        e = y * _DOUBLE_PERLIN_SHIFT
        f = z * _DOUBLE_PERLIN_SHIFT
        return (self.first.sample(x, y, z) + self.second.sample(d, e, f)) * self.amplitude


# ---------------------------------------------------------------------------
# Noise configs — per-family noise parameter definitions.
# Loaded from noise_configs.json (extracted from mod JARs), with a
# hardcoded overworld fallback so the sampler works standalone.
# ---------------------------------------------------------------------------
_NOISE_CONFIGS_CACHE = None
_NOISE_CONFIGS_PATH = Path(__file__).resolve().parent / "noise_configs.json"

_OVERWORLD_FALLBACK = {
    "temperature":     {"noise_id": "minecraft:temperature",     "first_octave": -10, "amplitudes": [1.5, 0.0, 1.0, 0.0, 0.0, 0.0], "xz_scale": 0.25},
    "humidity":        {"noise_id": "minecraft:vegetation",      "first_octave": -8,  "amplitudes": [1.0, 1.0, 0.0, 0.0, 0.0, 0.0], "xz_scale": 0.25},
    "continentalness": {"noise_id": "minecraft:continentalness", "first_octave": -9,  "amplitudes": [1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0], "xz_scale": 0.25},
    "erosion":         {"noise_id": "minecraft:erosion",         "first_octave": -9,  "amplitudes": [1.0, 1.0, 0.0, 1.0, 1.0], "xz_scale": 0.25},
    "weirdness":       {"noise_id": "minecraft:ridge",           "first_octave": -7,  "amplitudes": [1.0, 2.0, 1.0, 0.0, 0.0, 0.0], "xz_scale": 0.25},
}


def load_noise_configs():
    """Load per-family noise configs. Returns {family: {param: {...}}}."""
    global _NOISE_CONFIGS_CACHE
    if _NOISE_CONFIGS_CACHE is not None:
        return _NOISE_CONFIGS_CACHE
    if _NOISE_CONFIGS_PATH.exists():
        _NOISE_CONFIGS_CACHE = json.loads(_NOISE_CONFIGS_PATH.read_text())
    else:
        _NOISE_CONFIGS_CACHE = {"overworld": _OVERWORLD_FALLBACK}
    return _NOISE_CONFIGS_CACHE


# ---------------------------------------------------------------------------
# Vanilla quantisation: MultiNoiseUtil.toLong / ParameterRange.getDistance
# ---------------------------------------------------------------------------
_F32 = struct.Struct('f')


def _f32(value):
    """Round a Python float (double) to IEEE 754 float32 (binary32)."""
    return _F32.unpack(_F32.pack(value))[0]


def _to_long(value):
    """Mirror of MultiNoiseUtil.toLong: fload → ldc 10000.0f → fmul → f2l.

    Java bytecode uses float32 multiplication (fmul), not double.  The
    input float parameter is multiplied by the float constant 10000.0f,
    producing a float result truncated to long (f2l).  Python's float is
    double (64-bit); we round to float32 at each step to match fmul.
    """
    return int(_f32(_f32(float(value)) * 10000.0))


def _param_distance(noise_long, range_min, range_max):
    """Mirror of ParameterRange.getDistance(long noise)."""
    above = noise_long - range_max
    below = range_min - noise_long
    return above if above > 0 else max(below, 0)


# ---------------------------------------------------------------------------
# Biome Sampler — ties noise sampling + parameter table lookup together.
# Family-aware: accepts a noise_config dict per dimension family so
# nether/end/paradise_lost dimensions use their own noise parameters.
# ---------------------------------------------------------------------------
class BiomeSampler:
    def __init__(self, seed, biome_params_path, noise_config=None,
                 biome_filter=None, family=None, param_overrides=None,
                 suppress=None, depth_evaluator=None,
                 climate_evaluators=None):
        """Create a biome sampler for one seed.

        Args:
            seed: world/dimension seed (long)
            biome_params_path: path to the biome parameter table JSON
            noise_config: dict of {param_name: {noise_id, first_octave,
                          amplitudes, xz_scale}} — defaults to overworld
            biome_filter: optional biome ID list to restrict the lookup
                          table (matches the mod's per-dimension biome list).
                          Pass an ORDERED list when foreign biomes are in
                          play — round-robin assignment follows list order.
            family: optional family tag to filter entries by source dimension
                    family (e.g. "nether", "end", "paradise_lost")
            param_overrides: optional {biome_id: raw "parameters" dict}
                    (Tier 3 object-form biomes entries) — forwarded to
                    build_mixed_entries for explicit placement intervals
            depth_evaluator: optional callable (x, z) -> float that returns
                    the exact depth climate value for biome selection.
                    When set, depth_exact is True. When None, depth defaults
                    to 0.0 and depth_exact reflects whether that is provably
                    correct (set by the caller via depth_exact).
            climate_evaluators: optional dict of
                    {axis_name: callable(x, z) -> float} for any of the
                    five non-depth axes (temperature, humidity,
                    continentalness, erosion, weirdness). When an evaluator
                    is present for an axis, the DF-graph value replaces the
                    hardcoded noise sampler for that axis. Per-axis
                    exactness is recorded in climate_exact.
        """
        self.seed = seed
        self.biome_table = json.loads(Path(biome_params_path).read_text())

        if noise_config is None:
            noise_config = load_noise_configs().get("overworld", _OVERWORLD_FALLBACK)

        # Pre-parse ranges into flat tuples for fast lookup.
        # _entries: list of (biome_id, lo0,hi0,lo1,hi1,...,lo5,hi5, offset_sq)
        # Flat layout avoids per-iteration tuple/list indexing overhead.
        #
        # When biome_filter is provided (dimension has an explicit biome list),
        # use the mod's exact mixed-source semantics: native biomes keep their
        # climate regions, foreign biomes are assigned unclaimed regions
        # round-robin, and biomes not in the list are dropped entirely.
        if biome_filter:
            from biome_source_mixing import build_mixed_entries
            biome_list = list(biome_filter) if not isinstance(biome_filter, list) else biome_filter
            source_entries = build_mixed_entries(
                self.biome_table, biome_list, family_filter=family or "overworld",
                param_overrides=param_overrides)
        else:
            source_entries = None

        if source_entries is not None:
            iter_entries = source_entries
        else:
            # The exact table carries non-biome rows (the _noiseAliases
            # metadata sentinel) — skip them like every other consumer.
            iter_entries = [e for e in self.biome_table
                            if e.get("biome")
                            and not (family and e.get("family")
                                     and e["family"] != family)]
        # Global suppress list (BiomeSuppression.filter mirror): drop
        # suppressed biomes' entries; refuse to empty a source — the Java
        # side WARNs and keeps the source unfiltered, so must we.
        if suppress:
            suppressed = {str(s).lower() for s in suppress}
            kept = [e for e in iter_entries
                    if e["biome"].lower() not in suppressed]
            if kept and len(kept) != len(iter_entries):
                iter_entries = kept

        self._entries = []
        tree_entries = []
        for entry in iter_entries:
            if entry.get("unresolved") or not entry.get("biome"):
                continue
            flat = []
            for param in ("temperature", "humidity", "continentalness",
                          "erosion", "depth", "weirdness"):
                lo, hi = entry[param]
                flat.append(_to_long(lo))
                flat.append(_to_long(hi))
            offset_long = _to_long(entry.get("offset", 0.0))
            self._entries.append((entry["biome"], tuple(flat), offset_long * offset_long))
            tree_params = tuple(flat) + (offset_long, offset_long)
            tree_entries.append((tree_params, entry["biome"]))

        from search_tree import SearchTree
        self._search_tree = SearchTree(tree_entries) if tree_entries else None

        # Create noise samplers from the world seed
        rng = Xoroshiro128PlusPlus(seed)
        deriver = rng.fork()

        # Coordinate shift noise (minecraft:offset) — shared across families.
        # ShiftA samples (qx*0.25, 0, qz*0.25), ShiftB samples (qz*0.25, 0, qx*0.25).
        offset_rng = deriver.from_hash_of("minecraft:offset")
        self._offset_noise = DoublePerlinNoiseSampler(offset_rng, -3, [1.0, 1.0, 1.0, 0.0])

        # Climate parameters present in this family's noise config
        self._climate_params = {}  # param_name -> (sampler, xz_scale)
        for param_name in ("temperature", "humidity", "continentalness",
                           "erosion", "weirdness"):
            if param_name in noise_config:
                cfg = noise_config[param_name]
                noise_id = cfg["noise_id"]
                param_rng = deriver.from_hash_of(noise_id)
                sampler = DoublePerlinNoiseSampler(
                    param_rng, cfg["first_octave"], cfg["amplitudes"])
                xz_scale = cfg.get("xz_scale", 0.25)
                self._climate_params[param_name] = (sampler, xz_scale)

        # Depth axis: when a depth_evaluator is provided, depth is computed
        # exactly from the noise router's density-function graph at the
        # oracle's biome-sampling y (block y=64 = QuartPos.toBlock(16)).
        # Without one, depth=0.0. depth_exact records whether depth was
        # evaluated from the router (True) or defaulted (False).
        self._depth_evaluator = depth_evaluator
        self.depth_exact = depth_evaluator is not None

        # Per-axis climate evaluators from the noise router's DF graph.
        # When present, the evaluator replaces the hardcoded noise sampler
        # for that axis. climate_exact tracks exactness per axis.
        #
        # _climate_batch_evaluator: optional callable (x, z) -> dict of
        # {axis_name: float} that evaluates ALL router axes in one pass
        # (sharing the column memo). Takes precedence over per-axis
        # evaluators. Set by build_from_spec when a PresetTerrainEvaluator
        # covers all axes.
        self._climate_evaluators = dict(climate_evaluators or {})
        self._climate_batch_evaluator = None
        self.climate_exact = {
            "temperature": False,
            "humidity": False,
            "continentalness": False,
            "erosion": False,
            "weirdness": False,
            "depth": self.depth_exact,
        }
        for axis in self._climate_evaluators:
            self.climate_exact[axis] = True
        if self.depth_exact:
            self.climate_exact["depth"] = True

    def _shifts(self, qx, qz):
        """Coordinate shifts matching the vanilla router's shift_a/shift_b.
        shift_a(offset) = offset.sample(x*0.25, 0, z*0.25) * 4
        shift_b(offset) = offset.sample(z*0.25, x*0.25, 0) * 4"""
        sx = self._offset_noise.sample(qx * 0.25, 0, qz * 0.25) * 4.0
        sz = self._offset_noise.sample(qz * 0.25, qx * 0.25, 0) * 4.0
        return sx, sz

    def sample_climate(self, x, z):
        """Sample all 6 climate parameters at (x, z). Returns dict.

        When a batch evaluator is available (PresetTerrainEvaluator covering
        all axes), it evaluates all router fields in one pass with a shared
        column memo — ~6x faster than calling per-axis evaluators.
        Individual evaluators and noise-config samplers fill remaining axes.
        """
        # Batch evaluation: the PresetTerrainEvaluator computes all router
        # fields in one pass, sharing the column memo across subgraphs.
        if self._climate_batch_evaluator is not None:
            return self._climate_batch_evaluator(x, z)

        qx = x / 4.0
        qz = z / 4.0
        if self._depth_evaluator is not None:
            climate = {"depth": self._depth_evaluator(x, z)}
        else:
            climate = {"depth": 0.0}
        _shifts_computed = False
        shift_x = shift_z = 0.0
        for param_name in ("temperature", "humidity", "continentalness",
                           "erosion", "weirdness"):
            if param_name in self._climate_evaluators:
                climate[param_name] = self._climate_evaluators[param_name](x, z)
            elif param_name in self._climate_params:
                if not _shifts_computed:
                    shift_x, shift_z = self._shifts(qx, qz)
                    _shifts_computed = True
                sampler, xz_scale = self._climate_params[param_name]
                sx = qx * xz_scale + shift_x
                sz = qz * xz_scale + shift_z
                climate[param_name] = sampler.sample(sx, 0, sz)
            else:
                climate[param_name] = 0.0
        return climate

    def biome_and_climate(self, x, z):
        """Return (biome_id, climate_dict) in one pass — no double computation.

        Uses the SearchTree (mirrors MultiNoiseUtil$SearchTree) for the
        nearest-neighbour lookup.  The tree's build-order sort determines
        tie-breaking identically to vanilla: the traversal comparison is
        strict '<' (equal distance does NOT replace), so the first-visited
        entry wins, and visit order is fixed by the tree construction.
        """
        climate = self.sample_climate(x, z)
        tl = _to_long(climate["temperature"])
        hl = _to_long(climate["humidity"])
        cl = _to_long(climate["continentalness"])
        el = _to_long(climate["erosion"])
        dl = _to_long(climate["depth"])
        wl = _to_long(climate["weirdness"])
        if self._search_tree is not None:
            best_biome = self._search_tree.get((tl, hl, cl, el, dl, wl, 0))
        else:
            best_biome = "unknown"
        return best_biome, climate

    def _linear_biome_search(self, x, z):
        """Linear-scan biome lookup (the pre-SearchTree path).

        Kept for cross-checking: returns the same result as vanilla's
        Entries.getValueSimple, which scans the list in order and replaces
        on strict '<'.  Differs from the tree at tie points.
        """
        climate = self.sample_climate(x, z)
        tl = _to_long(climate["temperature"])
        hl = _to_long(climate["humidity"])
        cl = _to_long(climate["continentalness"])
        el = _to_long(climate["erosion"])
        dl = _to_long(climate["depth"])
        wl = _to_long(climate["weirdness"])
        best_biome = "unknown"
        best_dist = 0x7FFFFFFFFFFFFFFF
        for biome_id, flat, off_sq in self._entries:
            d = off_sq
            v = _param_distance(tl, flat[0], flat[1])
            d += v * v
            v = _param_distance(hl, flat[2], flat[3])
            d += v * v
            if d >= best_dist:
                continue
            v = _param_distance(cl, flat[4], flat[5])
            d += v * v
            v = _param_distance(el, flat[6], flat[7])
            d += v * v
            if d >= best_dist:
                continue
            v = _param_distance(dl, flat[8], flat[9])
            d += v * v
            v = _param_distance(wl, flat[10], flat[11])
            d += v * v
            if d < best_dist:
                best_dist = d
                best_biome = biome_id
        return best_biome, climate

    def biome_at(self, x, z):
        """Return the biome ID at world coordinates (x, z)."""
        return self.biome_and_climate(x, z)[0]

    def locate_biome(self, biome_id, radius=6400, step=64, origin_x=0, origin_z=0):
        """Find the nearest instance of biome_id within radius of origin.
        Searches in expanding rings. Returns (distance, x, z) or None."""
        max_rings = radius // step
        for ring in range(max_rings + 1):
            if ring == 0:
                coords = [(0, 0)]
            else:
                coords = []
                for i in range(-ring, ring + 1):
                    coords.append((i, -ring))
                    coords.append((i, ring))
                for i in range(-ring + 1, ring):
                    coords.append((-ring, i))
                    coords.append((ring, i))

            for dx, dz in coords:
                x = origin_x + dx * step
                z = origin_z + dz * step
                if self.biome_at(x, z) == biome_id:
                    dist = int(math.sqrt(
                        (x - origin_x) ** 2 + (z - origin_z) ** 2))
                    return dist, x, z

        return None

    def spawn_filter(self, namesake_biomes, radius=768, step=64):
        """Check if any namesake biome exists within radius of origin.
        Returns (biome_id, distance, x, z) or (None, -1, 0, 0)."""
        best = None
        best_dist = float('inf')
        half = radius
        for x in range(-half, half + 1, step):
            for z in range(-half, half + 1, step):
                biome = self.biome_at(x, z)
                if biome in namesake_biomes:
                    dist_sq = x * x + z * z
                    if dist_sq < best_dist:
                        best_dist = dist_sq
                        best = (biome, int(math.sqrt(dist_sq)), x, z)
        if best:
            return best
        return None, -1, 0, 0


class CheckerboardBiomeSampler(BiomeSampler):
    """Deterministic checkerboard biome layout over seeded climate noise.

    Mirrors the mod's "checkerboard" generator case (DimensionManager:
    vanilla CheckerboardBiomeSource wrapped in a NoiseChunkGenerator with
    overworld settings). The biome at a point is pure geometry — seed-
    independent — while climate sampling (the terrain proxy) still runs the
    family noise, because terrain shape and structures DO vary with seed.

    Vanilla formula (1.21.1 CheckerboardBiomeSource, quart coords = block
    >> 2): index = floorMod((qx >> scale+2) + (qz >> scale+2), len(biomes)).
    Python's >> and % on negative ints are arithmetic-shift and floor-mod,
    matching Java's >> and Math.floorMod. Keep in sync with the Java case.

    Biome order matters: it must match the config's biomes list exactly
    (the mod builds its RegistryEntryList in config order)."""

    def __init__(self, seed, biome_params_path, biomes, scale=None,
                 noise_config=None, family=None):
        super().__init__(seed, biome_params_path, noise_config=noise_config,
                         biome_filter=None, family=family)
        self.biomes = [b.strip() for b in biomes if b and b.strip()]
        eff_scale = scale if isinstance(scale, int) and 0 <= scale <= 62 else 2
        self.grid_shift = eff_scale + 2
        # tier2's "namesake representable" check reads {e[0] for e in _entries}.
        self._entries = [(b, (), 0.0) for b in self.biomes]

    def biome_and_climate(self, x, z):
        climate = self.sample_climate(x, z)
        if not self.biomes:
            return "unknown", climate
        idx = (((x >> 2) >> self.grid_shift) + ((z >> 2) >> self.grid_shift)) % len(self.biomes)
        return self.biomes[idx], climate


_PATCH_DEFAULT_BLEND = 8
_PATCH_SELECTOR_CAP = 256  # blocks — selector sampling sweep cap


def _patch_hash_unit(x, z, salt):
    """Splitmix-style hash -> [-1, 1). Bit-for-bit mirror of
    PatchedBiomeSource.hashUnit — change BOTH or neither."""
    h = _u64(_u64(x * 0x9E3779B97F4A7C15) ^ _u64(z * 0xC2B2AE3D27D4EB4F)
             ^ _u64(salt * 0x100000001B3))
    h ^= h >> 33
    h = _u64(h * 0xFF51AFD7ED558CCD)
    h ^= h >> 33
    h = _u64(h * 0xC4CEB9FE1A85EC53)
    h ^= h >> 33
    return (h >> 11) * (2.0 ** -53) * 2.0 - 1.0


def _patch_jitter(qx, qz, salt):
    """Smooth value noise in [-1, 1]: 4-quart lattice, smoothstep
    interpolation. Mirror of PatchedBiomeSource.jitterNoise."""
    lx, lz = qx >> 2, qz >> 2
    fx, fz = (qx & 3) / 4.0, (qz & 3) / 4.0
    v00 = _patch_hash_unit(lx, lz, salt)
    v10 = _patch_hash_unit(lx + 1, lz, salt)
    v01 = _patch_hash_unit(lx, lz + 1, salt)
    v11 = _patch_hash_unit(lx + 1, lz + 1, salt)
    sx = fx * fx * (3.0 - 2.0 * fx)
    sz = fz * fz * (3.0 - 2.0 * fz)
    a = v00 + (v10 - v00) * sx
    b = v01 + (v11 - v01) * sx
    return a + (b - a) * sz


class PatchedBiomeSampler:
    """Mirror of the mod's PatchedBiomeSource (biomePatches config).

    Per-patch modes: STAMP (no "replace" — the area claims every column),
    CLIPPED SWAP ("replace": id — within the area, only columns the
    delegate resolves to that id are substituted; "*" = any biome), and
    GLOBAL SWAP ("scope": "global" — dimension-wide: explicit "replace"
    swaps that id everywhere; no replace/"*" makes the area a SELECTOR
    whose touching biomes all swap globally). "shape": "circle" (default,
    Euclidean) or "square" (Chebyshev). "blend" (blocks, default 8, 0 =
    razor) jitters local edges with _patch_jitter, salted by patch index.
    Precedence: local patches in config order (non-matching swaps fall
    through), then the global map on the delegate's answer. All geometry
    in QUART space (block >> 2), containment inclusive — keep everything
    in sync with the Java source."""

    def __init__(self, delegate, patches):
        self.delegate = delegate
        self._geom = []       # local: (biome, qx, qz, qr, replace, blend_q, salt, square)
        self._global = {}     # target id -> replacement biome
        selectors = []
        entries = []
        local_index = 0
        for p in patches:
            entries.append((p["biome"], (), 0.0))
            replace = (p.get("replace") or "").strip().lower() or None
            if (p.get("scope") or "").strip().lower() == "global":
                if replace and replace != "*":
                    self._global[replace] = p["biome"]
                else:
                    selectors.append(p)
                continue
            qr = max(1, int(p["radius"]) >> 2)
            blend = p.get("blend", _PATCH_DEFAULT_BLEND) or 0
            blend_q = max(1, int(blend) >> 2) if blend > 0 else 0
            square = (p.get("shape") or "").strip().lower() == "square"
            self._geom.append((p["biome"], int(p["x"]) >> 2, int(p["z"]) >> 2,
                               qr, replace, blend_q, local_index, square))
            local_index += 1
        for p in selectors:
            self._resolve_selector(p)
        # tier2's representability check unions patches with the delegate.
        self._entries = entries + list(delegate._entries)

    def _resolve_selector(self, p):
        """Sample the delegate across the selector area; every distinct
        biome touching it swaps globally to the patch biome. Mirrors
        resolveGlobalSelectors (2D sampling; the Java samples at y=64 —
        region-level parity, same as the rest of the pipeline)."""
        cqx, cqz = int(p["x"]) >> 2, int(p["z"]) >> 2
        qr = max(1, min(int(p["radius"]), _PATCH_SELECTOR_CAP) >> 2)
        square = (p.get("shape") or "").strip().lower() == "square"
        own = p["biome"]
        for dz in range(-qr, qr + 1):
            for dx in range(-qr, qr + 1):
                if not square and dx * dx + dz * dz > qr * qr:
                    continue
                found = self.delegate.biome_at((cqx + dx) << 2, (cqz + dz) << 2)
                if found and found != own:
                    self._global.setdefault(found, own)

    def biome_and_climate(self, x, z):
        qx, qz = x >> 2, z >> 2
        resolved = None  # delegate (biome, climate), computed at most once
        for biome, cqx, cqz, qr, replace, blend_q, salt, square in self._geom:
            dx, dz = qx - cqx, qz - cqz
            eff = qr if blend_q == 0 else qr + _patch_jitter(qx, qz, salt) * blend_q
            inside = (max(abs(dx), abs(dz)) <= eff) if square \
                else (dx * dx + dz * dz <= eff * eff)
            if not inside:
                continue
            if replace is None or replace == "*":
                return biome, self.delegate.sample_climate(x, z)
            if resolved is None:
                resolved = self.delegate.biome_and_climate(x, z)
            if resolved[0] == replace:
                return biome, resolved[1]
        if self._global:
            if resolved is None:
                resolved = self.delegate.biome_and_climate(x, z)
            replacement = self._global.get(resolved[0])
            if replacement is not None:
                return replacement, resolved[1]
        return resolved if resolved is not None else self.delegate.biome_and_climate(x, z)

    def biome_at(self, x, z):
        return self.biome_and_climate(x, z)[0]

    def sample_climate(self, x, z):
        return self.delegate.sample_climate(x, z)

    # Ring search + spawn scan only need biome_at — borrow the base
    # implementations unbound so behaviour stays identical.
    locate_biome = BiomeSampler.locate_biome
    spawn_filter = BiomeSampler.spawn_filter


# ---------------------------------------------------------------------------
# The ONE way to build a dimension's sampler.
#
# Five inputs decide a dimension's biome layout: noise family, ordered biome
# list, Tier-3 per-biome hypercubes, biome patches, checkerboard scale. They are
# derived once by sampler_spec() and consumed only by build_from_spec(); a
# caller that assembles them by hand samples a different world from the one the
# roller scores (see T20). A field not in sampler_spec() does not change the
# layout; one that does belongs here and nowhere else.
# ---------------------------------------------------------------------------

#: Family -> noise config key.
FAMILY_NOISE = {"overworld": "overworld", "nether": "nether", "end": "end",
                "paradise_lost": "paradise_lost", None: "overworld"}

#: Dimension TYPE -> noise config key. The type wins over the profile family:
#: a paradise_lost:paradise_lost dimension resolves family "overworld", which is
#: right for scoring and wrong for sampling.
TYPE_NOISE_OVERRIDE = {
    "paradise_lost:paradise_lost": "paradise_lost",
    "sky_islands": "overworld",
    "nether_islands": "nether",
}


def resolve_noise_family(dim_type, family):
    """The noise family a dimension actually generates with."""
    return TYPE_NOISE_OVERRIDE.get(dim_type, FAMILY_NOISE.get(family, "overworld"))


def sampler_spec(profile):
    """Every input that changes a dimension's biome layout, as plain data.

    Primitives only — terrain_survey hands this to a multiprocessing child.
    """
    dim_type = profile.get("type") or ""
    biomes = profile.get("create_args", {}).get("biome") or ""
    noise_settings = (profile.get("create_args", {}).get("noiseSettings")
                      or profile.get("noiseSettings"))
    return {
        "noise_family": resolve_noise_family(dim_type, profile.get("family")),
        "dim_type": dim_type,
        # Ordered, not a set: foreign-biome round-robin follows config order.
        "biomes": [b.strip() for b in biomes.split(",") if b.strip()],
        "parameters": dict(profile.get("biome_parameters") or {}),
        "patches": list(profile.get("biome_patches") or []),
        "checkerboard_scale": profile.get("checkerboard_scale"),
        # Global suppress list (settings.json suppress.biomes) — changes
        # the layout of every noise-sourced world; see BiomeSuppression.
        "suppressed_biomes": list(profile.get("suppressed_biomes") or []),
        # The noise_settings id decides the noise router graph. When it
        # names an adventure preset whose graph is in-repo, depth can be
        # evaluated exactly; otherwise depth is unavailable.
        "noise_settings": noise_settings,
    }


def _apply_legacy_climate(settings, seed, climate_evals):
    """legacy_random_source: vanilla REPLACES the router's temperature and
    vegetation noises (NoiseConfig$LegacyNoiseDensityFunctionVisitor) —
    CheckedRandom(seed)/(seed+1), hardcoded (-7, [1,1]) params, zero shift.
    Registry params are ignored, so the DF-graph and noise-config paths are
    both wrong for these two axes on a legacy settings. Evaluated at quart
    coordinates per this module's convention."""
    if not settings.get("legacy_random_source"):
        return
    from legacy_noise import legacy_climate_samplers
    temperature, vegetation = legacy_climate_samplers(int(seed))

    def _legacy(sampler):
        def _eval(x, z, _s=sampler):
            return _s.sample(int(math.floor(x)) >> 2, 0.0,
                             int(math.floor(z)) >> 2)
        return _eval

    climate_evals["temperature"] = _legacy(temperature)
    climate_evals["humidity"] = _legacy(vegetation)


def _make_climate_evaluators(noise_settings, seed, noise_family,
                             extracted_root=None, noise_aliases=None,
                             dim_type=None):
    """Build climate evaluators for as many router axes as possible.

    Returns (depth_eval, depth_exact, climate_evals) where:
      depth_eval: callable (x, z) -> float or None (backward compat)
      depth_exact: bool
      climate_evals: dict {axis_name: callable (x, z) -> float} for the
                     five non-depth axes where an exact evaluator exists

    When extracted_root is provided (the .noise_settings/ directory from
    the jar walk), the noise_settings graph and its referenced density
    functions and noises are resolved from there.

    noise_aliases: optional {lookup_id: canonical_id} from the live
    registry's _noiseAliases dump. The server seeds each noise by the
    canonical key, which may differ from the lookup key the DF JSON names.

    dim_type: the dimension's type string (e.g. "cave", "nether",
    "multi_biome"). Used to infer the default noise_settings when none is
    configured — mirrors DimensionManager.createDimensionOptions.
    """
    from preset_terrain import (PresetTerrainEvaluator, supported_presets,
                                ROUTER_TO_BIOME_AXIS)

    climate_evals = {}

    # DimensionManager defaults: dims with no explicit noiseSettings inherit
    # the generator from their type's base dimension. Mirrors
    # createDimensionOptions' type switch.
    #
    # "cave" uses minecraft:caves whose router has constants for
    # continents/erosion/ridges/depth (all 0.0) and vanilla shifted_noise
    # for temperature/vegetation. The constants are injected directly;
    # temperature/humidity use the noise_config path (build_from_spec
    # patches the params from the extracted data).
    if noise_settings is None and dim_type == "cave" and extracted_root:
        ns_path = Path(extracted_root) / "minecraft" / "caves.json"
        if ns_path.is_file():
            import json as _json
            _settings = _json.loads(ns_path.read_text())
            router = _settings.get("noise_router", {})
            for field_name, axis_name in ROUTER_TO_BIOME_AXIS.items():
                node = router.get(field_name)
                if isinstance(node, (int, float)):
                    val = float(node)
                    def _const(x, z, _v=val):
                        return _v
                    if axis_name != "depth":
                        climate_evals[axis_name] = _const
            _apply_legacy_climate(_settings, seed, climate_evals)
            if climate_evals:
                return None, True, climate_evals

    inferred_overworld_default = False
    if noise_settings is None and extracted_root:
        _FAMILY_DEFAULT_SETTINGS = {
            "overworld": "adventure:wide",
            "nether": "minecraft:nether",
            "end": "minecraft:end",
        }
        noise_settings = _FAMILY_DEFAULT_SETTINGS.get(noise_family)
        # A dim with no explicit noiseSettings inherits its TYPE's base
        # generator (DimensionManager.createDimensionOptions): multi_biome
        # and the base overworld template off overworldOpts, so the live
        # climate is the OVERWORLD router. adventure:wide is byte-derived
        # from it and agrees on continentalness/erosion/weirdness/depth,
        # but carries its own temperature/vegetation by design
        # (eval-df-verified at the 1e-4 floor on the_greywoods:
        # minecraft:overworld/noise_router/temperature matches the live
        # MultiNoiseSampler; adventure:wide's does not). The full raw
        # overworld graph is not evaluable here (un-inlined
        # tectonic:config_noise nodes), so the wide evaluator stands in
        # and the two climate axes are overridden below from the resolved
        # registry-DF chains — or honestly marked not exact.
        inferred_overworld_default = noise_settings == "adventure:wide"

    # Adventure presets have their full router graph in-repo — build
    # evaluators for ALL six climate axes, not just depth. The evaluator
    # object is returned so build_from_spec can wire the batch path.
    # The extracted data root provides Terralith's noise parameter
    # overrides (e.g. minecraft:erosion, minecraft:vegetation) — without
    # it the evaluator falls back to vanilla params and diverges.
    if noise_settings in supported_presets():
        data_roots = [Path(extracted_root)] if extracted_root else None
        ev = PresetTerrainEvaluator(noise_settings, int(seed),
                                    data_roots=data_roots,
                                    noise_aliases=noise_aliases)
        available = ev.router_fields()
        for field in available:
            axis = ROUTER_TO_BIOME_AXIS.get(field)
            if axis and axis != "depth":
                def _make_fn(f):
                    return lambda x, z: ev.evaluate_router(f, x, z)
                climate_evals[axis] = _make_fn(field)
        if inferred_overworld_default:
            # Replace wide's temperature/vegetation with the live overworld
            # chains. Terratonic routes both through tectonic:config_noise,
            # whose verified semantics are
            #   flat_cache(shifted_noise(noise, xz_scale=cfg_scale, y=0))
            #   * cfg_multiplier + cfg_offset
            # (gen-terrain-presets.py, bytecode-verified) with the constants
            # from the LIVE config/tectonic.json (biomes.temperature_* /
            # vegetation_*) — wide bakes 0.15 where the live config runs
            # 0.25, which is the whole divergence. On success the batch
            # evaluator must not install (it would bypass the overrides —
            # same rule as the legacy climate path); on any failure the two
            # axes are removed so they read as not exactly measurable
            # rather than exact-but-wrong.
            try:
                cfg_path = (Path(extracted_root).parent
                            / "base/config/tectonic.json")
                import re as _re
                cfg_text = _re.sub(r"^\s*//.*$", "", cfg_path.read_text(),
                                   flags=_re.MULTILINE)
                biomes_cfg = json.loads(cfg_text)["biomes"]

                def _axis_node(noise_id, prefix):
                    node = {"type": "minecraft:shifted_noise",
                            "noise": noise_id,
                            "xz_scale": biomes_cfg[prefix + "_scale"],
                            "y_scale": 0.0,
                            "shift_x": "minecraft:shift_x",
                            "shift_y": 0.0,
                            "shift_z": "minecraft:shift_z"}
                    mult = biomes_cfg[prefix + "_multiplier"]
                    off = biomes_cfg[prefix + "_offset"]
                    node = {"type": "minecraft:flat_cache", "argument": node}
                    if mult != 1.0:
                        node = {"type": "minecraft:mul",
                                "argument1": node, "argument2": mult}
                    if off != 0.0:
                        node = {"type": "minecraft:add",
                                "argument1": node, "argument2": off}
                    return node

                ow = PresetTerrainEvaluator(
                    {"noise_router": {
                        "depth": 0,
                        "temperature": _axis_node("minecraft:temperature",
                                                  "temperature"),
                        "vegetation": _axis_node("minecraft:vegetation",
                                                 "vegetation"),
                    }},
                    int(seed), data_roots=data_roots,
                    noise_aliases=noise_aliases)
                ow.evaluate_router("temperature", 0, 0)
                ow.evaluate_router("vegetation", 0, 0)
                climate_evals["temperature"] = (
                    lambda x, z: ow.evaluate_router("temperature", x, z))
                climate_evals["humidity"] = (
                    lambda x, z: ow.evaluate_router("vegetation", x, z))
            except (OSError, ValueError, KeyError):
                climate_evals.pop("temperature", None)
                climate_evals.pop("humidity", None)
            return ev.depth_for_biome, True, climate_evals
        climate_evals["_evaluator"] = ev
        return ev.depth_for_biome, True, climate_evals

    # Paradise Lost: every biome entry has depth=(0,0), so depth=0.0 is
    # provably correct — the biome source ignores depth entirely.
    if noise_family == "paradise_lost":
        return None, True, {}

    # Extracted noise_settings: load the graph from the jar walk output
    # and build evaluators for every router field that is resolvable.
    # Same block-coord evaluator as the adventure presets: eval-df confirms
    # the DFs match at block coords (the NoiseConfig creates the sampler
    # from the same DF graph the evaluator walks).
    if extracted_root and noise_settings:
        ns, _, name = noise_settings.partition(":")
        ns_path = Path(extracted_root) / ns / (name + ".json")
        if ns_path.is_file():
            import json as _json
            settings = _json.loads(ns_path.read_text())
            router = settings.get("noise_router", {})
            try:
                ev = PresetTerrainEvaluator(
                    settings, int(seed),
                    data_roots=[Path(extracted_root)],
                    noise_aliases=noise_aliases)
                ev.depth_for_biome(0, 0)
                for field in ev.router_fields():
                    axis = ROUTER_TO_BIOME_AXIS.get(field)
                    if axis and axis != "depth":
                        try:
                            ev.evaluate_router(field, 0, 0)
                            def _make_fn(f):
                                return lambda x, z: ev.evaluate_router(f, x, z)
                            climate_evals[axis] = _make_fn(field)
                        except (ValueError, KeyError):
                            pass
                if settings.get("legacy_random_source"):
                    # The legacy override replaces two axes, so the batch
                    # evaluator (which would bypass per-axis evaluators)
                    # must not be installed for legacy settings.
                    _apply_legacy_climate(settings, seed, climate_evals)
                else:
                    climate_evals["_evaluator"] = ev
                return ev.depth_for_biome, True, climate_evals
            except (ValueError, KeyError):
                pass  # DF chain incomplete — fall through
            # Fallback: depth-only extraction for settings whose full
            # graph is not resolvable but depth is a constant.
            depth_node = router.get("depth")
            if isinstance(depth_node, (int, float)):
                val = float(depth_node)
                if val == 0.0:
                    return None, True, {}
                return (lambda x, z, _v=val: _v), True, {}

    return None, False, {}


def _make_depth_evaluator(noise_settings, seed, noise_family,
                          extracted_root=None, dim_type=None):
    """Backward-compatible wrapper: returns (depth_eval, depth_exact)."""
    depth_eval, depth_exact, _climate = _make_climate_evaluators(
        noise_settings, seed, noise_family, extracted_root,
        dim_type=dim_type)
    return depth_eval, depth_exact


def default_extraction_root(biome_params_path):
    """The .noise_settings/ jar-walk extraction beside a biome table, or None.

    Warmup writes both into the same seedtest directory, so the table's
    location IS the discovery rule (see build_from_spec).
    """
    root = Path(biome_params_path).resolve().parent / ".noise_settings"
    return str(root) if root.is_dir() else None


def build_from_spec(seed, spec, biome_params_path, noise_configs=None,
                    extracted_data_root=None):
    """One dimension's sampler, from sampler_spec() and nothing else.

    extracted_data_root: path to the .noise_settings/ directory from the
    jar walk. When provided, noise_settings graphs and their referenced
    density functions and noises are resolved from there, enabling exact
    depth evaluation for families whose graphs are not in-repo.

    When the caller passes None, the root is resolved from the biome
    table's own directory (<seedtest>/.noise_settings — warmup writes
    both). Load-bearing: the extraction carries Terralith's
    temperature/vegetation parameter overrides and the legacy_random_source
    climate replacement, so a rootless sampler describes a different world
    from the one the parity gate certifies.
    """
    if noise_configs is None:
        noise_configs = load_noise_configs()
    if extracted_data_root is None:
        extracted_data_root = default_extraction_root(biome_params_path)
    noise_family = spec.get("noise_family") or "overworld"
    noise_config = noise_configs.get(noise_family, noise_configs.get("overworld"))

    # Terralith overrides vanilla noise params (minecraft:temperature,
    # minecraft:vegetation) in the worldgen registry. The overrides live
    # in the extracted .noise_settings/ directory. When available, patch
    # the noise_config so the BiomeSampler's default shifted_noise path
    # uses the correct amplitudes and octaves.
    if extracted_data_root and noise_config:
        _NOISE_TO_CONFIG = {
            "minecraft:temperature": "temperature",
            "minecraft:vegetation": "humidity",
            "minecraft:continentalness": "continentalness",
            "minecraft:erosion": "erosion",
            "minecraft:ridge": "weirdness",
        }
        patched = False
        for noise_id, config_key in _NOISE_TO_CONFIG.items():
            if config_key not in noise_config:
                continue
            ns, _, path = noise_id.partition(":")
            params_path = Path(extracted_data_root) / ns / "worldgen/noise" / (path + ".json")
            if params_path.is_file():
                import json as _json
                extracted_params = _json.loads(params_path.read_text())
                current = noise_config[config_key]
                if (extracted_params.get("firstOctave") != current.get("first_octave")
                        or extracted_params.get("amplitudes") != current.get("amplitudes")):
                    if not patched:
                        noise_config = dict(noise_config)
                        patched = True
                    noise_config[config_key] = {
                        "noise_id": current["noise_id"],
                        "first_octave": extracted_params["firstOctave"],
                        "amplitudes": extracted_params["amplitudes"],
                        "xz_scale": current.get("xz_scale", 0.25),
                    }

    biomes = spec.get("biomes") or None
    suppress = spec.get("suppressed_biomes") or None

    # Mirror of the Java allowed-list strip in buildMixedSource: a listed
    # dim loses suppressed entries from its list; a list emptied entirely
    # falls back to the (entry-filtered) family source, exactly as the mod
    # keeps the base source when no usable biomes remain. Checkerboard
    # lists are explicit per-dim stamps and are NOT filtered — specific
    # beats general, matching the Java no-op for non-noise sources.
    if biomes and suppress and spec.get("dim_type") != "checkerboard":
        suppressed_set = {str(s).lower() for s in suppress}
        biomes = [b for b in biomes if b.lower() not in suppressed_set] or None

    # Resolve climate evaluators from the noise_settings id.
    # Load the noise alias table from biome_params.json when present.
    from preset_terrain import load_noise_aliases
    noise_aliases = load_noise_aliases(biome_params_path)
    noise_settings = spec.get("noise_settings")
    dim_type = spec.get("dim_type")
    depth_eval, depth_exact, climate_evals = _make_climate_evaluators(
        noise_settings, seed, noise_family,
        extracted_root=extracted_data_root, noise_aliases=noise_aliases,
        dim_type=dim_type)

    # Separate the evaluator object from the per-axis callables.
    terrain_ev = climate_evals.pop("_evaluator", None)
    # Strip any non-axis keys from climate_evals.
    axis_evals = {k: v for k, v in climate_evals.items()
                  if k in ("temperature", "humidity", "continentalness",
                           "erosion", "weirdness")}

    if spec.get("dim_type") == "checkerboard" and biomes:
        sampler = CheckerboardBiomeSampler(
            seed, biome_params_path, biomes=biomes,
            scale=spec.get("checkerboard_scale"),
            noise_config=noise_config, family=noise_family)
        # Checkerboard ignores climate for biome selection — depth is moot.
        sampler.depth_exact = True
        sampler.climate_exact = {k: True for k in sampler.climate_exact}
    else:
        sampler = BiomeSampler(
            seed, biome_params_path, noise_config=noise_config,
            biome_filter=biomes, family=noise_family,
            param_overrides=spec.get("parameters") or None,
            suppress=suppress, depth_evaluator=depth_eval,
            climate_evaluators=axis_evals)
        if not depth_eval and depth_exact:
            # depth=0.0 is provably correct (e.g. paradise_lost).
            sampler.depth_exact = True
            sampler.climate_exact["depth"] = True

        # Wire the batch evaluator when a PresetTerrainEvaluator covers all
        # axes — evaluate_climate returns all router fields in one pass,
        # sharing the column memo across subgraph evaluations.
        if terrain_ev is not None:
            sampler._climate_batch_evaluator = terrain_ev.evaluate_climate
            for axis in axis_evals:
                sampler.climate_exact[axis] = True
    patches = spec.get("patches") or []
    if patches:
        sampler = PatchedBiomeSampler(sampler, patches)
    return sampler


def build_for_dimension(seed, profile, biome_params_path, noise_configs=None):
    """Convenience: sampler_spec() + build_from_spec() for callers holding a
    profile and no reason to keep the spec around."""
    return build_from_spec(seed, sampler_spec(profile), biome_params_path,
                           noise_configs)


# ---------------------------------------------------------------------------
# CLI mode
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import sys
    import time

    if len(sys.argv) < 3:
        print("Usage: biome_sampler.py <biome_params.json> <seed> [--family NAME] [x z]")
        sys.exit(1)

    params_path = sys.argv[1]
    seed = int(sys.argv[2])
    family = "overworld"
    args = sys.argv[3:]
    if "--family" in args:
        idx = args.index("--family")
        family = args[idx + 1]
        args = args[:idx] + args[idx + 2:]
    x = int(args[0]) if len(args) > 0 else 0
    z = int(args[1]) if len(args) > 1 else 0

    configs = load_noise_configs()
    noise_config = configs.get(family)
    if not noise_config:
        print(f"Unknown family '{family}'. Available: {', '.join(configs.keys())}")
        sys.exit(1)

    t0 = time.time()
    sampler = BiomeSampler(seed, params_path, noise_config=noise_config)
    init_ms = (time.time() - t0) * 1000

    print(f"BiomeSampler({family}) initialized in {init_ms:.0f}ms (seed {seed})")
    print(f"  {len(sampler._entries)} biome entries, "
          f"{len(sampler._climate_params)} noise params: "
          f"{', '.join(sampler._climate_params.keys())}")

    t0 = time.time()
    biome = sampler.biome_at(x, z)
    sample_ms = (time.time() - t0) * 1000
    print(f"Biome at ({x}, {z}): {biome} ({sample_ms:.2f}ms)")

    climate = sampler.sample_climate(x, z)
    parts = [f"{k}={v:.4f}" for k, v in climate.items() if k != "depth"]
    print(f"Climate: {' '.join(parts)}")

    # Grid sample
    print(f"\nBiome grid (step=128, ±512 blocks):")
    t0 = time.time()
    biome_counts = {}
    for gx in range(-512, 513, 128):
        for gz in range(-512, 513, 128):
            b = sampler.biome_at(gx, gz)
            biome_counts[b] = biome_counts.get(b, 0) + 1
    grid_ms = (time.time() - t0) * 1000
    for b, c in sorted(biome_counts.items(), key=lambda x: -x[1])[:15]:
        print(f"  {b}: {c}")
    total = sum(biome_counts.values())
    print(f"  ({len(biome_counts)} unique biomes, {total} points in {grid_ms:.0f}ms)")
