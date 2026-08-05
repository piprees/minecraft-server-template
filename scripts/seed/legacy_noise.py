"""legacy_noise.py — climate noises for legacy_random_source settings.

MIRRORS NoiseConfig$LegacyNoiseDensityFunctionVisitor (1.21.1, bytecode):
when a noise_settings carries legacy_random_source=true, vanilla REPLACES
the router's climate noise holders instead of seeding them from the
registry —

  temperature  DoublePerlinNoiseSampler.createLegacy(
                   new CheckedRandom(seed), NoiseParameters(-7, [1.0, 1.0]))
  vegetation   same, with new CheckedRandom(seed + 1)
  offset       zero-amplitude constant, so every shift DF evaluates 0.0

Registry params (Terralith's overrides included) are IGNORED for these
three in legacy mode; every other noise keeps the normal splitter path.

The legacy octave chain (OctavePerlinNoiseSampler ctor, legacy branch) is
sequential consumption of ONE Java LCG: a PerlinNoiseSampler is always
built first (stored only when index -firstOctave falls inside the
amplitude list), then octaves -firstOctave-1 .. 0 descending each either
build a sampler (non-zero amplitude, index < len) or skip exactly 262
LCG draws. DoublePerlin builds its two octave samplers back to back from
the same LCG.

MIRRORS CheckedRandom/BaseRandom/Random (bytecode): setSeed
(seed ^ 25214903917) & 2^48-1; next(bits) advances
seed*25214903917 + 11 and returns the SIGNED top bits;
nextDouble = (next(26) << 27 | + next(27)) * 2^-53; nextInt(bound) has
the power-of-two shortcut and the rejection loop; skip(n) = n draws of
next(32).

Change together with the sampler wiring in biome_sampler; re-run
test_biome_parity.py (the_luminous_caverns is the covering fixture).
"""

from biome_sampler import (
    DoublePerlinNoiseSampler,
    ImprovedNoiseSampler,
    OctavePerlinNoiseSampler,
)

_LCG_MULT = 25214903917
_LCG_INC = 11
_LCG_MASK = (1 << 48) - 1
_DOUBLE_MULT = 1.1102230246251565e-16  # 2^-53


class JavaRandom:
    """CheckedRandom — the java.util.Random LCG."""

    def __init__(self, seed):
        self.seed = (seed ^ _LCG_MULT) & _LCG_MASK

    def next(self, bits):
        # Java: (int)(seed >>> (48 - bits)) — an int CAST, not a signed
        # shift: non-negative for bits <= 31, wraps only at bits == 32.
        self.seed = (self.seed * _LCG_MULT + _LCG_INC) & _LCG_MASK
        value = self.seed >> (48 - bits)
        if bits == 32 and value >= (1 << 31):
            value -= 1 << 32
        return value

    def next_int(self, bound):
        if bound <= 0:
            raise ValueError("Bound must be positive")
        if (bound & (bound - 1)) == 0:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            value = bits % bound
            if bits - value + (bound - 1) >= 0:
                return value

    def next_double(self):
        hi = self.next(26)
        lo = self.next(27)
        return ((hi << 27) + lo) * _DOUBLE_MULT

    def skip(self, count):
        for _ in range(count):
            self.next(32)


def _legacy_octave(random, first_octave, amplitudes):
    """OctavePerlinNoiseSampler legacy branch — sequential LCG consumption."""
    inst = OctavePerlinNoiseSampler.__new__(OctavePerlinNoiseSampler)
    inst.first_octave = first_octave
    inst.amplitudes = list(amplitudes)
    n = len(inst.amplitudes)
    j = -first_octave

    inst.lacunarity = 2.0 ** first_octave
    inst.persistence = (2.0 ** (n - 1)) / (2.0 ** n - 1.0)

    samplers = [None] * n
    first = ImprovedNoiseSampler(random)
    if 0 <= j < n and inst.amplitudes[j] != 0.0:
        samplers[j] = first
    for k in range(j - 1, -1, -1):
        if k < n and inst.amplitudes[k] != 0.0:
            samplers[k] = ImprovedNoiseSampler(random)
        else:
            random.skip(262)
    inst.samplers = samplers

    max_val = 0.0
    p = inst.persistence
    for i in range(n):
        if samplers[i] is not None:
            max_val += inst.amplitudes[i] * 2.0 * p
        p /= 2.0
    inst.max_value = max_val
    return inst


def create_legacy_double_perlin(random, first_octave, amplitudes):
    """DoublePerlinNoiseSampler.createLegacy — both octave samplers from
    the SAME LCG, then the modern amplitude formula."""
    inst = DoublePerlinNoiseSampler.__new__(DoublePerlinNoiseSampler)
    inst.first = _legacy_octave(random, first_octave, amplitudes)
    inst.second = _legacy_octave(random, first_octave, amplitudes)

    min_idx = len(amplitudes)
    max_idx = -1
    for i, a in enumerate(amplitudes):
        if a != 0.0:
            min_idx = min(min_idx, i)
            max_idx = max(max_idx, i)
    span = max_idx - min_idx if max_idx >= min_idx else 0
    inst.amplitude = (1.0 / 6.0) / (0.1 * (1.0 + 1.0 / (span + 1)))
    return inst


def legacy_climate_samplers(seed):
    """The two legacy climate noises for a legacy_random_source world.

    Returns (temperature, vegetation) DoublePerlin samplers. The shift
    noise is a zero-amplitude constant in legacy mode — callers model it
    as shift = 0.0 rather than constructing a sampler.
    """
    temperature = create_legacy_double_perlin(JavaRandom(seed), -7, [1.0, 1.0])
    vegetation = create_legacy_double_perlin(JavaRandom(seed + 1), -7, [1.0, 1.0])
    return temperature, vegetation
