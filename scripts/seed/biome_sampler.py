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
# Biome Sampler — ties noise sampling + parameter table lookup together.
# Family-aware: accepts a noise_config dict per dimension family so
# nether/end/paradise_lost dimensions use their own noise parameters.
# ---------------------------------------------------------------------------
class BiomeSampler:
    def __init__(self, seed, biome_params_path, noise_config=None,
                 biome_filter=None, family=None, param_overrides=None):
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

        self._entries = []
        for entry in (source_entries if source_entries is not None else self.biome_table):
            if source_entries is None:
                if family and entry.get("family") and entry["family"] != family:
                    continue
            flat = []
            for param in ("temperature", "humidity", "continentalness",
                          "erosion", "depth", "weirdness"):
                lo, hi = entry[param]
                flat.append(lo)
                flat.append(hi)
            offset = entry.get("offset", 0.0)
            self._entries.append((entry["biome"], tuple(flat), offset * offset))

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

    def _shifts(self, qx, qz):
        """Compute coordinate shifts from the offset noise.
        MC's ShiftA = offset(qx*0.25, 0, qz*0.25)
        MC's ShiftB = offset(qz*0.25, 0, qx*0.25)"""
        sx = self._offset_noise.sample(qx * 0.25, 0, qz * 0.25)
        sz = self._offset_noise.sample(qz * 0.25, 0, qx * 0.25)
        return sx, sz

    def sample_climate(self, x, z):
        """Sample all 6 climate parameters at (x, z). Returns dict."""
        qx = x / 4.0
        qz = z / 4.0
        shift_x, shift_z = self._shifts(qx, qz)
        climate = {"depth": 0.0}
        for param_name in ("temperature", "humidity", "continentalness",
                           "erosion", "weirdness"):
            if param_name in self._climate_params:
                sampler, xz_scale = self._climate_params[param_name]
                sx = qx * xz_scale + shift_x
                sz = qz * xz_scale + shift_z
                climate[param_name] = sampler.sample(sx, 0, sz)
            else:
                climate[param_name] = 0.0
        return climate

    def biome_and_climate(self, x, z):
        """Return (biome_id, climate_dict) in one pass — no double computation."""
        climate = self.sample_climate(x, z)
        t = climate["temperature"]
        h = climate["humidity"]
        c = climate["continentalness"]
        e = climate["erosion"]
        dp = climate["depth"]
        w = climate["weirdness"]
        best_biome = "unknown"
        best_dist = float('inf')
        for biome_id, flat, off_sq in self._entries:
            # Unrolled 6D distance with early exit — skip entries that
            # can't beat the current best after checking 2 parameters.
            d = off_sq
            v = t
            if v < flat[0]:
                v = flat[0] - v
                d += v * v
            elif v > flat[1]:
                v = v - flat[1]
                d += v * v
            v = h
            if v < flat[2]:
                v = flat[2] - v
                d += v * v
            elif v > flat[3]:
                v = v - flat[3]
                d += v * v
            # Prune: 2 params already exceed best → skip remaining 4
            if d >= best_dist:
                continue
            v = c
            if v < flat[4]:
                v = flat[4] - v
                d += v * v
            elif v > flat[5]:
                v = v - flat[5]
                d += v * v
            v = e
            if v < flat[6]:
                v = flat[6] - v
                d += v * v
            elif v > flat[7]:
                v = v - flat[7]
                d += v * v
            if d >= best_dist:
                continue
            v = dp
            if v < flat[8]:
                v = flat[8] - v
                d += v * v
            elif v > flat[9]:
                v = v - flat[9]
                d += v * v
            v = w
            if v < flat[10]:
                v = flat[10] - v
                d += v * v
            elif v > flat[11]:
                v = v - flat[11]
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
