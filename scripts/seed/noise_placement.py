#!/usr/bin/env python3
"""noise_placement.py — Python mirror of the mod's noise structure placement.

Line-for-line counterpart of the custom-dimensions mod's StructureNoise,
NoiseProfile and NoiseFieldIndex (spike tasks B1/B2, roller task F1). The two
must agree on EVERY position: `/customdim structure-census <dim>` dumps the
Java side and test_noise_placement.py diffs it against this module.

Java/Python translation rules that actually bite:

- Java longs wrap at 64 bits and `>>>` is an unsigned shift; Python integers
  are unbounded and `>>` is arithmetic. Every SplitMix64 step masks to 64
  bits, and every comparison that Java does on a long is done here on an
  explicitly signed or explicitly unsigned value, never on Python's native
  bignum.
- The mod uses `double` everywhere, never `float`. Python floats ARE doubles,
  so the arithmetic matches exactly — but only because the Java side was
  changed from `float[]` to `double[]` for the radial curves (1.3f is
  1.29999995231628418, which would have diverged on the first curve sample).
- `xi & 255` on a negative int gives the same answer in both languages
  (Python's & is two's-complement on negatives), so the lattice wrap needs no
  special handling.

MIRRORED FILES — change together:
  mods/custom-dimensions/src/main/java/com/customdimensions/dimension/
      StructureNoise.java, NoiseProfile.java, NoiseFieldIndex.java,
      DimensionStructures.java (saltOf)
  scripts/gen-structure-groups.py (rarity_for, group_for)
"""

import json
import math
from pathlib import Path

try:
    import numpy as _np
    _np.seterr(over="ignore")  # uint64 SplitMix64 wraparound is the point
    HAVE_NUMPY = True
except ImportError:  # consumers without numpy keep the scalar paths
    _np = None
    HAVE_NUMPY = False

M64 = (1 << 64) - 1
M32 = (1 << 32) - 1

# StructureNoise constants.
NORMALISE = 1.4142135623730951
ORIGIN_X = 0.31830988618379067
ORIGIN_Z = 0.5772156649015329
GOLDEN = 0x9E3779B97F4A7C15

# NoiseFieldIndex.priority constants.
PRIORITY_X = 0x9E3779B97F4A7C15
PRIORITY_Z = 0xC2B2AE3D27D4EB4F

# NoiseProfile.Cluster.FINE_SALT
FINE_SALT = 0xDEAD

# NoiseFieldIndex.MAX_RADIUS_CHUNKS
MAX_RADIUS_CHUNKS = 512

# NoiseFieldIndex.MAX_EXCLUSION_SCALE / MIN_RADIAL_WEIGHT. The radial curve
# scales the exclusion radius, and these bound how far it may push it: a
# weight below MIN_RADIAL_WEIGHT is treated as MIN_RADIAL_WEIGHT, which caps
# the separation at MAX_EXCLUSION_SCALE times the group's base. Reciprocal
# squares of each other; both spelled out as literals so the Java and Python
# sides cannot drift by a rounding step.
MAX_EXCLUSION_SCALE = 4.0
MIN_RADIAL_WEIGHT = 0.0625

# NoiseProfile.REFERENCE_RADIUS_CHUNKS. Frequency scales as
# REFERENCE / radius so every dimension sees the same NUMBER of noise
# features whatever its size — without it, `sparse`'s 67-chunk lattice
# period spans a 1024-block dimension about twice and the whole world
# becomes one blob (measured: zero settlements in the_overgrowth).
REFERENCE_RADIUS_CHUNKS = 512


def frequency_scale(radius_chunks):
    if radius_chunks <= 0:
        return 1.0
    return REFERENCE_RADIUS_CHUNKS / radius_chunks


def _signed64(v):
    """Java long semantics for a masked value."""
    v &= M64
    return v - (1 << 64) if v >= (1 << 63) else v


def mix64(z):
    """StructureNoise.mix64 — SplitMix64 finaliser, unsigned 64-bit result."""
    z &= M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return (z ^ (z >> 31)) & M64


def _fade(t):
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)


def _lerp(t, a, b):
    return a + t * (b - a)


def _grad(h, x, z):
    """Eight gradient vectors, all length sqrt(2). Mirrors StructureNoise.grad."""
    h &= 7
    if h == 0:
        return x + z
    if h == 1:
        return -x + z
    if h == 2:
        return x - z
    if h == 3:
        return -x - z
    if h == 4:
        return x * NORMALISE
    if h == 5:
        return -x * NORMALISE
    if h == 6:
        return z * NORMALISE
    return -z * NORMALISE


# The same eight vectors as coefficient pairs, for the row-scan fast path.
# `gx * x + gz * z` reproduces _grad exactly: the zero coefficients
# contribute an exact +0.0 (or -0.0, which adds identically), and IEEE
# negation is exact, so (-N) * x == -(x * N) bit for bit.
_GRAD_VECTORS = (
    (1.0, 1.0), (-1.0, 1.0), (1.0, -1.0), (-1.0, -1.0),
    (NORMALISE, 0.0), (-NORMALISE, 0.0), (0.0, NORMALISE), (0.0, -NORMALISE),
)


def sample_row(permutation, chunk_xs, chunk_z, frequency):
    """`sample_chunk` for a whole chunk row, bit-identical, ~2x faster.

    The z half of the lattice (zi/zf/fade/gz) depends only on `chunk_z`, so
    it is hoisted out of the loop; everything else is the same arithmetic in
    the same order, with _fade/_lerp/_grad inlined to spare four million
    Python calls per group at radius 512. `chunk_xs` is any iterable of chunk
    x coordinates — a whole row, or just the survivors of a cluster profile's
    coarse gate.

    Returns a list of samples, one per entry in `chunk_xs`.
    """
    z = chunk_z * frequency + ORIGIN_Z
    zi = math.floor(z)
    zf = z - zi
    zf1 = zf - 1.0
    gz = zi & 255
    v = zf * zf * zf * (zf * (zf * 6.0 - 15.0) + 10.0)
    p = permutation
    grads = _GRAD_VECTORS
    floor = math.floor
    out = []
    append = out.append
    for cx in chunk_xs:
        x = cx * frequency + ORIGIN_X
        xi = floor(x)
        xf = x - xi
        xf1 = xf - 1.0
        u = xf * xf * xf * (xf * (xf * 6.0 - 15.0) + 10.0)
        a = p[xi & 255] + gz
        b = p[(xi & 255) + 1] + gz
        g = grads[p[a] & 7]
        n00 = g[0] * xf + g[1] * zf
        g = grads[p[b] & 7]
        n10 = g[0] * xf1 + g[1] * zf
        g = grads[p[a + 1] & 7]
        n01 = g[0] * xf + g[1] * zf1
        g = grads[p[b + 1] & 7]
        n11 = g[0] * xf1 + g[1] * zf1
        l0 = n00 + u * (n10 - n00)
        l1 = n01 + u * (n11 - n01)
        n = l0 + v * (l1 - l0)
        normalised = (n * NORMALISE + 1.0) * 0.5
        if normalised < 0.0:
            normalised = 0.0
        elif normalised > 1.0:
            normalised = 1.0
        append(normalised)
    return out


#: The eight gradient vectors as two float64 coefficient arrays, for the
#: vectorised row scan. Built lazily so importing without numpy is free.
_GRAD_NP = None


def _grad_np():
    global _GRAD_NP
    if _GRAD_NP is None:
        _GRAD_NP = (_np.array([g[0] for g in _GRAD_VECTORS], dtype=_np.float64),
                    _np.array([g[1] for g in _GRAD_VECTORS], dtype=_np.float64))
    return _GRAD_NP


def sample_row_np(permutation, chunk_xs, chunk_z, frequency):
    """`sample_row` over a whole row at once. BIT-IDENTICAL, ~8x faster.

    Same expressions in the same order as the scalar form, evaluated in
    float64. numpy elementwise ufuncs neither reassociate nor fuse into FMA,
    so `a * b + c` written as two ufuncs is exactly `fl(fl(a * b) + c)` —
    which is what the scalar loop computes and what the Java side computes.
    That is the whole reason this is allowed to exist: parity with
    StructureNoise.java is the module's contract (test_noise_parity.py diffs
    it against the live calculator with zero tolerance), so a faster sweep
    that is merely close is worthless.

    `permutation` is the int64 array form (see StructureNoise.permutation_np);
    `chunk_xs` is a float64 array of chunk x coordinates. Returns a float64
    array, one sample per entry.
    """
    gx, gz_vec = _grad_np()
    p = permutation
    z = chunk_z * frequency + ORIGIN_Z
    zi = math.floor(z)
    zf = z - zi
    zf1 = zf - 1.0
    gz = zi & 255
    v = zf * zf * zf * (zf * (zf * 6.0 - 15.0) + 10.0)

    x = chunk_xs * frequency + ORIGIN_X
    xi = _np.floor(x)
    xf = x - xi
    xf1 = xf - 1.0
    u = xf * xf * xf * (xf * (xf * 6.0 - 15.0) + 10.0)
    xi255 = xi.astype(_np.int64) & 255
    a = p[xi255] + gz
    b = p[xi255 + 1] + gz
    h00 = p[a] & 7
    h10 = p[b] & 7
    h01 = p[a + 1] & 7
    h11 = p[b + 1] & 7
    n00 = gx[h00] * xf + gz_vec[h00] * zf
    n10 = gx[h10] * xf1 + gz_vec[h10] * zf
    n01 = gx[h01] * xf + gz_vec[h01] * zf1
    n11 = gx[h11] * xf1 + gz_vec[h11] * zf1
    l0 = n00 + u * (n10 - n00)
    l1 = n01 + u * (n11 - n01)
    n = l0 + v * (l1 - l0)
    out = (n * NORMALISE + 1.0) * 0.5
    _np.clip(out, 0.0, 1.0, out=out)
    return out


_U64 = None


def _u64():
    """SplitMix64 constants as uint64 scalars, built once."""
    global _U64
    if _U64 is None:
        u = _np.uint64
        _U64 = (u(PRIORITY_X), u(PRIORITY_Z), u(0xBF58476D1CE4E5B9),
                u(0x94D049BB133111EB), u(30), u(27), u(31))
    return _U64


def _mix64_np(z):
    """SplitMix64 finaliser over an array. uint64 wraps like a Java long."""
    _px, _pz, m1, m2, s30, s27, s31 = _u64()
    z = (z ^ (z >> s30)) * m1
    z = (z ^ (z >> s27)) * m2
    return z ^ (z >> s31)


def priority_np(noise_seed, chunk_xs, chunk_z):
    """`priority` over an array of chunk x coordinates. BIT-IDENTICAL.

    numpy uint64 wraps mod 2^64 exactly as a Java long does, and `>>` on an
    unsigned dtype is the unsigned shift `>>>` requires — so the SplitMix64
    finaliser needs no masking here, unlike the Python-int form.
    """
    px, _pz, _m1, _m2, _s30, _s27, _s31 = _u64()
    cx = chunk_xs.astype(_np.uint64)
    # chunk_z is one value for the whole row, so fold it and the seed into a
    # single exact Python int. Doing it in numpy costs a scalar multiply whose
    # wrap — the point of the operation — numpy reports as an overflow warning.
    row = (noise_seed ^ mix64((chunk_z * PRIORITY_Z) & M64)) & M64
    return _mix64_np(_np.uint64(row) ^ _mix64_np(cx * px))


class StructureNoise:
    """Mirror of StructureNoise.java."""

    __slots__ = ("permutation", "_permutation_np")

    def __init__(self, seed):
        p = list(range(256))
        state = seed & M64
        for i in range(255, 0, -1):
            state = (state + GOLDEN) & M64
            r = mix64(state)
            # Java: Long.remainderUnsigned(r, i + 1). `r` is already unsigned
            # here, so a plain modulo is the same operation.
            j = r % (i + 1)
            p[i], p[j] = p[j], p[i]
        self.permutation = [p[i & 255] for i in range(512)]
        self._permutation_np = None

    @property
    def permutation_np(self):
        """The permutation as an int64 array, for sample_row_np."""
        if self._permutation_np is None:
            self._permutation_np = _np.array(self.permutation, dtype=_np.int64)
        return self._permutation_np

    def sample(self, x, z):
        """Improved 2D Perlin, normalised to [0, 1]."""
        xi = math.floor(x)
        zi = math.floor(z)
        xf = x - xi
        zf = z - zi
        gx = xi & 255
        gz = zi & 255

        u = _fade(xf)
        v = _fade(zf)

        p = self.permutation
        a = p[gx] + gz
        b = p[gx + 1] + gz

        n = _lerp(v,
                  _lerp(u, _grad(p[a], xf, zf), _grad(p[b], xf - 1.0, zf)),
                  _lerp(u, _grad(p[a + 1], xf, zf - 1.0),
                        _grad(p[b + 1], xf - 1.0, zf - 1.0)))

        normalised = (n * NORMALISE + 1.0) * 0.5
        if normalised < 0.0:
            return 0.0
        return 1.0 if normalised > 1.0 else normalised

    def sample_chunk(self, chunk_x, chunk_z, frequency):
        """The only entry point callers should use — owns the lattice offset."""
        return self.sample(chunk_x * frequency + ORIGIN_X,
                           chunk_z * frequency + ORIGIN_Z)


_SAMPLERS = {}


def sampler(seed):
    """NoiseProfile.sampler — per-seed cache."""
    key = seed & M64
    s = _SAMPLERS.get(key)
    if s is None:
        s = StructureNoise(key)
        _SAMPLERS[key] = s
    return s


class NoiseProfile:
    """Mirror of NoiseProfile.java. `none` is represented by None, not an instance."""

    __slots__ = ("id", "frequency", "threshold", "exclusion_multiplier",
                 "coarse_frequency", "coarse_threshold", "cluster")

    def __init__(self, id, frequency, threshold, exclusion_multiplier,
                 coarse_frequency=None, coarse_threshold=None, cluster=False):
        self.id = id
        self.frequency = frequency
        self.threshold = threshold
        self.exclusion_multiplier = exclusion_multiplier
        self.coarse_frequency = coarse_frequency
        self.coarse_threshold = coarse_threshold
        self.cluster = cluster

    def evaluate(self, seed, chunk_x, chunk_z, scale=1.0):
        """Convenience for tests and probes — resolves the sampler per call.

        NoiseFieldIndex binds its samplers once instead; that map lookup in
        the per-chunk loop was most of a 3.4-second world load on the Java
        side, and the mirror keeps the same split so the two stay comparable.
        """
        freq = self.frequency * scale
        if not self.cluster:
            return sampler(seed).sample_chunk(chunk_x, chunk_z, freq)
        coarse = sampler(seed).sample_chunk(
            chunk_x, chunk_z, self.coarse_frequency * scale)
        if coarse <= self.coarse_threshold:
            return 0.0
        return sampler(seed ^ FINE_SALT).sample_chunk(chunk_x, chunk_z, freq)


NATURAL = NoiseProfile("natural", 0.025, 0.68, 2.0)
DENSE = NoiseProfile("dense", 0.040, 0.45, 1.6)
SPARSE = NoiseProfile("sparse", 0.015, 0.85, 2.6)
CLUSTER = NoiseProfile("cluster", 0.05, 0.8, 0.8,
                       coarse_frequency=0.008, coarse_threshold=0.90, cluster=True)

PROFILES = {
    "natural": NATURAL,
    "dense": DENSE,
    "sparse": SPARSE,
    "cluster": CLUSTER,
}


def profile_from_string(value):
    """NoiseProfile.fromString — None for `none`, absent, or unrecognised."""
    if not value:
        return None
    return PROFILES.get(value.lower())


def chunk_pos_to_long(x, z):
    """net.minecraft.util.math.ChunkPos.toLong, as a SIGNED long."""
    return _signed64((x & M32) | ((z & M32) << 32))


def priority(noise_seed, chunk_x, chunk_z):
    """NoiseFieldIndex.priority — unsigned 64-bit rank.

    Each coordinate is passed through the SplitMix64 finaliser before the two
    are combined. XOR-ing the raw multiples collided antipodally: negating a
    two's-complement value leaves every bit at and below its lowest set bit
    alone and flips the rest, so `(x, z)` and `(-x, -z)` XOR to the same value
    whenever the two coordinates share a trailing-zero count — about a third
    of pairs, measured 43,692 duplicates over a full radius-256 grid. Mixing
    first destroys that structure (measured zero).

    The outer mix64 is inlined: this runs once per eligible chunk, which is
    millions of times for a 512-radius group.
    """
    z = (noise_seed
         ^ mix64((chunk_x * PRIORITY_X) & M64)
         ^ mix64((chunk_z * PRIORITY_Z) & M64)) & M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return (z ^ (z >> 31)) & M64


def radial_weight(radial, dist_chunks, radius_chunks):
    """NoiseFieldIndex.radialWeight — linear interpolation, clamped at both ends."""
    if not radial:
        return 1.0
    if radius_chunks <= 0:
        return radial[0]
    fraction = dist_chunks / radius_chunks
    if fraction < 0.0:
        fraction = 0.0
    if fraction >= 1.0:
        return radial[-1]
    scaled = fraction * (len(radial) - 1)
    lo = int(scaled)
    hi = min(lo + 1, len(radial) - 1)
    t = scaled - lo
    return radial[lo] + t * (radial[hi] - radial[lo])


def exclusion_for(base_exclusion, weight):
    """NoiseFieldIndex.exclusionFor — the minimum separation at a radial weight.

    A Poisson-disc set with minimum separation d has density proportional to
    1/d^2, so d = base / sqrt(weight) makes the placement density directly
    proportional to `weight`. That is the whole point of the change: the curve
    value IS the relative density multiplier, 1.0 being the profile's own
    density, and the shape of the curve is the shape of the density profile.

    Before this, the curve multiplied into the noise before the threshold test,
    which made it a binary eligibility gate rather than a dial. Measured on the
    banked candidates: raising `inner`'s peak from 1.6 to 3.0 changed density
    per unit area not at all (0.85 0.57 0.85 1.10 ... vs 0.85 0.85 1.03 1.34
    ...), because `_outranks` used a FIXED exclusion radius and the packing
    saturated at it. All the curve could do was decide where a group existed —
    so `inner` gave 33 dimensions no village past a third of the radius, and
    `outer` gave 54 no dungeon inside half of it.

    Weight 0.0 still suppresses outright (returns 0, which the caller reads as
    ineligible), so an author who genuinely wants a hard edge can still write
    one. It is now opt-in rather than the accidental consequence of a taper.
    """
    if weight <= 0.0:
        return 0
    w = weight if weight > MIN_RADIAL_WEIGHT else MIN_RADIAL_WEIGHT
    return max(1, _java_round(base_exclusion / math.sqrt(w)))


def region_key(region_x, region_z):
    """NoiseFieldIndex.regionKey."""
    return _signed64(((region_x << 32) & M64) ^ (region_z & M32))


#: Geometry memo. Every entry is derived from (radial curve, radius, base
#: exclusion) alone — the seed touches none of it — so one entry serves every
#: candidate of a dimension. Bounded because a worker walks many dimensions
#: over a full run; at radius 512 an entry is ~7 MB, so the cap is a memory
#: ceiling, not a correctness one.
#: Sized so one dimension's whole group set fits alongside the previous
#: dimension's — a full overworld resolves seven distinct triples, and a cap
#: below that evicts every entry before it is reused even once.
#:
#: A radius-512 entry is ~24 MB (two lookup tables, the row lists, and their
#: array views), so this is also a per-worker memory ceiling of a few hundred
#: MB. With census tasks sorted by dimension a worker holds the current
#: dimension's triples and little else, so the ceiling is not normally
#: approached.
_GEOMETRY = {}
_GEOMETRY_ORDER = []
_GEOMETRY_MAX = 16


def geometry(radial, radius_chunks, exclusion):
    """Everything about a group's layout that does not depend on the seed.

    Returns a dict carrying, for one (radial, radius, exclusion) triple:

      rows      [(dz, live_dx)] — the chunk offsets in each row that are
                inside the disc AND not suppressed by a zero radial weight.
      excl_of   dist_sq -> the exclusion radius a candidate at that distance
                is judged against (0 for suppressed).
      offsets   exclusion radius -> its disc offsets.

    Rebuilding this per candidate was ~2.5s of a 20.2s overworld census —
    5.8M loop iterations and 7.4M dict lookups to re-derive constants. It is
    stored as flat lookup tables rather than dicts because at radius 512 the
    dict forms cost ~20 MB each against ~2 MB for the table.
    """
    key = (tuple(radial) if radial else None, radius_chunks, exclusion)
    hit = _GEOMETRY.get(key)
    if hit is not None:
        return hit

    r = radius_chunks
    side = r * 2 + 1
    r_squared = float(r) * r
    max_dist_sq = r * r
    from array import array
    weight_of = array("d", bytes(8 * (max_dist_sq + 1)))
    excl_of = array("i", bytes(4 * (max_dist_sq + 1)))
    known = bytearray(max_dist_sq + 1)
    sqrt = math.sqrt
    rows = []
    distinct = set()
    for dz in range(-r, r + 1):
        dz_sq = float(dz) * dz
        span = int(sqrt(max(r_squared - dz_sq, 0.0)))
        live_dx = []
        for dx in range(-span, span + 1):
            dist_sq = float(dx) * dx + dz_sq
            if dist_sq > r_squared:
                continue
            k = int(dist_sq)
            if not known[k]:
                known[k] = 1
                w = radial_weight(radial, sqrt(dist_sq), r)
                weight_of[k] = w
                excl_of[k] = exclusion_for(exclusion, w) if w > 0.0 else 0
            if weight_of[k] <= 0.0:
                continue
            live_dx.append(dx)
            distinct.add(excl_of[k])
        if live_dx:
            rows.append((dz, live_dx))

    offsets = {}
    for e in distinct:
        offsets[e] = _disc_offsets(e)

    entry = {
        "side": side,
        "radius": r,
        "rows": rows,
        "weight_of": weight_of,
        "excl_of": excl_of,
        "offsets": offsets,
        "distinct_excls": sorted(distinct),
        "rows_np": None,
        "flat_offsets": {},
    }
    _GEOMETRY[key] = entry
    _GEOMETRY_ORDER.append(key)
    while len(_GEOMETRY_ORDER) > _GEOMETRY_MAX:
        _GEOMETRY.pop(_GEOMETRY_ORDER.pop(0), None)
    return entry


def _geometry_np(geom):
    """Add the array views the vectorised path needs, once per memo entry.

    Built on demand rather than inside `geometry`, so an entry created while
    the scalar path was selected is still usable by the vectorised one. Doing
    it eagerly keyed the cache on a global flag without saying so: a scalar
    build poisoned the entry for every later vectorised caller, which is
    invisible in production (the flag never changes) and wrong the moment
    anything toggles it — a test, or a future per-dimension fallback.
    """
    if geom["rows_np"] is None:
        geom["rows_np"] = [(dz, _np.array(live, dtype=_np.float64),
                            _np.array(live, dtype=_np.int64))
                           for dz, live in geom["rows"]]
        geom["excl_np"] = _np.frombuffer(geom["excl_of"], dtype=_np.int32)
    return geom


def _disc_max(ranks, e):
    """Max rank inside a radius-`e` disc centred on every cell.

    Grey dilation by a disc, decomposed into horizontal runs: for each row
    offset dz the disc spans a window of half-width isqrt(e^2 - dz^2), so one
    horizontal sliding max per distinct width and a max-reduce over the
    2e+1 shifted rows. The sliding maxima come from a sparse table (doubling),
    so a width costs one pairwise max rather than a scan.

    Padding is zero, which is exactly right: an ineligible cell already carries
    rank 0 and a real rank is positive, so padding can never win a max. (A
    genuine rank of 0 is guarded by the caller — see `_select_np`.)

    The disc may be LARGER than the grid (a small dimension with a big
    exclusion: endgame's base 20 at sparse x2.6 is 52, and a 256-block border
    is a 33-cell side). Rows shifted clear off the grid contribute nothing and
    must be skipped explicitly — `row[:h + dz]` with `h + dz` negative is a
    from-the-end slice in Python, so it silently yields a non-empty array of
    the wrong height instead of an empty one (T28).
    """
    h, w = ranks.shape
    pad = _np.zeros((h, w + 2 * e), dtype=_np.uint64)
    pad[:, e:e + w] = ranks

    levels = [pad]
    k = 1
    while (1 << k) <= 2 * e + 1:
        prev = levels[k - 1]
        step = 1 << (k - 1)
        levels.append(_np.maximum(prev[:, :prev.shape[1] - step],
                                  prev[:, step:]))
        k += 1

    def window(length, start):
        """Max over [start, start+length-1] for every output column."""
        kk = length.bit_length() - 1
        tab = levels[kk]
        second = start + length - (1 << kk)
        return _np.maximum(tab[:, start:start + w], tab[:, second:second + w])

    out = _np.zeros((h, w), dtype=_np.uint64)
    for dz in range(-e, e + 1):
        if dz <= -h or dz >= h:
            continue                     # shifted clear off the grid
        hw = math.isqrt(e * e - dz * dz)
        row = window(2 * hw + 1, e - hw)
        if dz < 0:
            _np.maximum(out[-dz:], row[:h + dz], out=out[-dz:])
        elif dz > 0:
            _np.maximum(out[:h - dz], row[dz:], out=out[:h - dz])
        else:
            _np.maximum(out, row, out=out)
    return out


#: Survivors are checked in blocks so one gather never allocates more than
#: roughly this many rank reads (block x disc cells).
_GATHER_BUDGET = 4_000_000


def _flat_offsets(geom, e, width):
    """Disc offsets as flat index deltas into a padded rank array."""
    cached = geom["flat_offsets"].get((e, width))
    if cached is None:
        offs = geom["offsets"][e]
        cached = _np.array([oz * width + ox for ox, oz in offs],
                           dtype=_np.int64)
        geom["flat_offsets"][(e, width)] = cached
    return cached


def _chunk_keys_np(chunk_xs, chunk_zs):
    """`chunk_pos_to_long` over arrays — the SIGNED long ChunkPos.toLong makes."""
    m32 = _np.uint64(M32)
    v = (((chunk_zs.astype(_np.uint64) & m32) << _np.uint64(32))
         | (chunk_xs.astype(_np.uint64) & m32))
    return _np.ascontiguousarray(v).view(_np.int64)


def _select_np(geom, ranks, cells_dx, cells_dz, spawn_chunk_x, spawn_chunk_z):
    """The outranks pass, vectorised. Returns kept (dx, dz) arrays, or None
    when an exact answer cannot be guaranteed and the caller must fall back.

    Two stages, both exact:

      A. One disc-max at the SMALLEST exclusion in the group. A survivor at
         exclusion e is the maximum inside its own disc, which contains the
         smallest disc, so `rank >= discmax(e_min)` is a valid superset — it
         throws away ~99% of eligible chunks for the cost of one dilation.
      B. The exact per-cell test on that superset, batched by exclusion value
         so each batch is a single gather rather than a Python loop.

    RANK TIES ARE COMMON — measured 25,699 among 836,056 eligible chunks
    across one overworld census, ~3%, not the 2^-64 a hash collision would
    suggest. They are ANTIPODAL: `priority` mixes `seed ^ (cx*PX) ^ (cz*PZ)`
    through a bijection, and negating a value leaves every bit at and below
    its lowest set bit alone while flipping the rest — so when cx and cz have
    the same number of trailing zeros, (cx, cz) and (-cx, -cz) produce the
    identical XOR and therefore the identical rank. About a third of
    coordinate pairs qualify.

    That puts every tied pair 2*sqrt(cx^2 + cz^2) apart, so a tie only lands
    inside one exclusion disc near the origin — rare, but real, and the
    chunk-key rule is what decides it. Resolved here exactly as `_outranks`
    resolves it (lower chunk key loses), and only for chunks nothing
    outranked outright, which keeps the hot path to a single gather.

    Still returns None for a zero rank: `_disc_max` pads with zero, and a
    real rank of 0 would make padding indistinguishable from a tied
    neighbour. That one IS a 2^-64 event, so the scalar fallback never runs
    in practice and the answer stays exact if it ever does.
    """
    geom = _geometry_np(geom)
    r = geom["radius"]
    side = geom["side"]
    excl_np = geom["excl_np"]
    distinct = geom["distinct_excls"]
    if not distinct:
        return []

    flat = (cells_dz + r) * side + (cells_dx + r)
    ranks_flat = ranks.reshape(-1)
    cell_ranks = ranks_flat[flat]
    if cell_ranks.size == 0:
        return []
    if not cell_ranks.all():
        return None                      # a genuine zero rank

    e_min = distinct[0]
    dm = _disc_max(ranks, e_min).reshape(-1)
    keep = cell_ranks >= dm[flat]
    cand_dx = cells_dx[keep]
    cand_dz = cells_dz[keep]
    if cand_dx.size == 0:
        return []

    e_max = distinct[-1]
    width = side + 2 * e_max
    padded = _np.zeros((side + 2 * e_max, width), dtype=_np.uint64)
    padded[e_max:e_max + side, e_max:e_max + side] = ranks
    padded_flat = padded.reshape(-1)

    cand_sq = cand_dx.astype(_np.int64) ** 2 + cand_dz.astype(_np.int64) ** 2
    cand_excl = excl_np[cand_sq]
    base = ((cand_dz + r + e_max).astype(_np.int64) * width
            + (cand_dx + r + e_max).astype(_np.int64))
    cand_ranks = padded_flat[base]

    keys = None                          # built only if a tie survives stage A

    kept_dx, kept_dz = [], []
    for e in distinct:
        sel = _np.nonzero(cand_excl == e)[0]
        if sel.size == 0:
            continue
        offs = _flat_offsets(geom, e, width)
        if offs.size == 0:
            kept_dx.append(cand_dx[sel])
            kept_dz.append(cand_dz[sel])
            continue
        block = max(1, _GATHER_BUDGET // offs.size)
        for start in range(0, sel.size, block):
            part = sel[start:start + block]
            part_base = base[part]
            part_rank = cand_ranks[part][:, None]
            nb = padded_flat[part_base[:, None] + offs[None, :]]
            alive = ~(nb > part_rank).any(axis=1)
            if not alive.any():
                continue
            # Ties only matter for chunks nothing outranked outright.
            live = _np.nonzero(alive)[0]
            tied = (nb[live] == part_rank[live]).any(axis=1)
            if tied.any():
                fix = live[tied]
                if keys is None:
                    padded_keys = _np.zeros((side + 2 * e_max, width),
                                            dtype=_np.int64)
                    dzs = _np.arange(side, dtype=_np.int64) - r + spawn_chunk_z
                    dxs = _np.arange(side, dtype=_np.int64) - r + spawn_chunk_x
                    padded_keys[e_max:e_max + side, e_max:e_max + side] = \
                        _chunk_keys_np(_np.broadcast_to(dxs, (side, side)),
                                       dzs[:, None] * _np.ones(side, _np.int64))
                    keys = padded_keys.reshape(-1)
                nk = keys[part_base[fix][:, None] + offs[None, :]]
                loses = ((nb[fix] == part_rank[fix])
                         & (nk < keys[part_base[fix]][:, None])).any(axis=1)
                alive[fix] = ~loses
            if alive.any():
                kept_dx.append(cand_dx[part][alive])
                kept_dz.append(cand_dz[part][alive])
    if not kept_dx:
        return []
    return (_np.concatenate(kept_dx), _np.concatenate(kept_dz))


class NoiseFieldIndex:
    """Mirror of NoiseFieldIndex.java.

    Same two-pass shape as the Java side: mark eligibility over the bounding
    box, then keep the chunks that outrank every eligible neighbour inside the
    exclusion disc. Order-free by construction, so the ring walk below only
    fixes the ORDER of `positions`, never its contents.

    The radial curve scales the EXCLUSION RADIUS (see `exclusion_for`), it does
    not gate eligibility. Eligibility is `noise > threshold`, uniform across the
    dimension, so the profile owns "what fraction of the world is candidate
    material" and the curve owns "how densely the candidates pack". One knob,
    one job, and no dead zones.

    Each candidate is judged against its OWN exclusion radius, which makes the
    relation asymmetric: a chunk in a low-weight band has to beat competitors a
    high-weight chunk never looks at. That is exactly what "denser where the
    weight is high" means, and it stays order-free — every decision reads only
    the eligibility and rank arrays, never another decision — so parity with
    the Java side is still a set comparison.
    """

    def __init__(self, noise_seed, profile, exclusion, radial, radius_chunks,
                 spawn_chunk_x=0, spawn_chunk_z=0):
        noise_seed &= M64
        r = max(0, min(radius_chunks, MAX_RADIUS_CHUNKS))
        excl = max(1, exclusion)
        # The locate cell has to be sized from the SMALLEST separation the
        # curve can ask for, not the base: a cell built for the base would hold
        # two placements wherever the weight peaks, and `by_region` keeps only
        # the first, so locate would silently stop finding the rest. A uniform
        # curve peaks at 1.0, which reproduces the old `excl * 2` exactly.
        min_excl = max(1, exclusion_for(excl, max(radial) if radial else 1.0))
        self.spacing = max(2, min_excl * 2)
        self.radius_chunks = r

        # Samplers bound ONCE, mirroring the Java constructor.
        scale = frequency_scale(r)
        frequency = profile.frequency * scale
        coarse_frequency = (profile.coarse_frequency or 0.0) * scale
        coarse_threshold = profile.coarse_threshold or 0.0
        is_cluster = profile.cluster
        primary = StructureNoise(noise_seed)
        fine = StructureNoise(_signed64(noise_seed ^ FINE_SALT) & M64) if is_cluster else None

        geom = geometry(radial, r, excl)
        args = (geom, noise_seed, primary, fine, is_cluster, frequency,
                coarse_frequency, coarse_threshold, profile.threshold,
                spawn_chunk_x, spawn_chunk_z)
        ordered = self._build_np(*args) if HAVE_NUMPY else self._build(*args)

        # Nearest-first, ties on the chunk key — a total order, so the Java
        # and Python lists agree element for element.
        ordered.sort(key=lambda p: (
            (p[0] - spawn_chunk_x) ** 2 + (p[1] - spawn_chunk_z) ** 2,
            chunk_pos_to_long(p[0], p[1])))

        placements = set(ordered)
        by_region = {}
        for cx, cz in ordered:
            key = region_key(_floor_div(cx, self.spacing),
                             _floor_div(cz, self.spacing))
            by_region.setdefault(key, (cx, cz))

        self.placements = placements
        self.ordered = ordered
        self.by_region = by_region

    @staticmethod
    def _build(geom, noise_seed, primary, fine, is_cluster, frequency,
               coarse_frequency, coarse_threshold, threshold,
               spawn_chunk_x, spawn_chunk_z):
        """Scalar eligibility + selection. The reference behaviour.

        Row-at-a-time over the geometry's pre-filtered rows: the two
        exactness-preserving filters (outside the disc; radial weight 0.0, an
        author's explicit hard suppression) are what `geometry` bakes into
        `rows`, so they cost nothing after the first candidate of a dimension.

        Eligible cells are collected as they are found rather than
        rediscovered by a second full (2r+1)^2 sweep, which visited 1,050,625
        cells per group to find ~120,000 of them.
        """
        r = geom["radius"]
        side = geom["side"]
        eligible = bytearray(side * side)
        # Ranks cached alongside eligibility, mirroring Java: every eligible
        # chunk is read once as a candidate and many times as a neighbour.
        ranks = [0] * (side * side)
        primary_perm = primary.permutation
        fine_perm = fine.permutation if is_cluster else None
        live_cells = []
        for dz, live_dx in geom["rows"]:
            row = (dz + r) * side
            cz = spawn_chunk_z + dz
            live_cx = live_dx if spawn_chunk_x == 0 else \
                [spawn_chunk_x + dx for dx in live_dx]
            if is_cluster:
                coarse_row = sample_row(primary_perm, live_cx, cz, coarse_frequency)
                keep = [i for i, c in enumerate(coarse_row) if c > coarse_threshold]
                if not keep:
                    continue
                fine_row = sample_row(fine_perm, [live_cx[i] for i in keep],
                                      cz, frequency)
                noises = [0.0] * len(live_cx)
                for j, i in enumerate(keep):
                    noises[i] = fine_row[j]
            else:
                noises = sample_row(primary_perm, live_cx, cz, frequency)
            for i, noise in enumerate(noises):
                if noise > threshold:
                    dx = live_dx[i]
                    idx = row + (dx + r)
                    eligible[idx] = 1
                    ranks[idx] = priority(noise_seed, spawn_chunk_x + dx, cz)
                    live_cells.append((dx, dz))
        return NoiseFieldIndex._select_scalar(geom, eligible, ranks, live_cells,
                                              spawn_chunk_x, spawn_chunk_z)

    @staticmethod
    def _select_scalar(geom, eligible, ranks, live_cells,
                       spawn_chunk_x, spawn_chunk_z):
        """Keep the chunks that outrank every eligible neighbour in their disc.

        Order-free — every decision reads only the eligibility and rank
        arrays, never another decision — so the caller's sort fixes the ORDER
        of `positions`, never its contents.
        """
        r = geom["radius"]
        side = geom["side"]
        excl_of = geom["excl_of"]
        offsets_by_excl = geom["offsets"]
        ordered = []
        for dx, dz in live_cells:
            offsets = offsets_by_excl[excl_of[dx * dx + dz * dz]]
            cx = spawn_chunk_x + dx
            cz = spawn_chunk_z + dz
            if NoiseFieldIndex._outranks(eligible, ranks, side, r, dx, dz,
                                         offsets, cx, cz,
                                         spawn_chunk_x, spawn_chunk_z):
                ordered.append((cx, cz))
        return ordered

    @staticmethod
    def _build_np(geom, noise_seed, primary, fine, is_cluster, frequency,
                  coarse_frequency, coarse_threshold, threshold,
                  spawn_chunk_x, spawn_chunk_z):
        """Vectorised eligibility + selection. Bit-identical to `_build`.

        The rank field is assembled straight into a uint64 array by one
        scatter — never via a million-element Python list, which cost more to
        convert than the Perlin sweep cost to compute.
        """
        geom = _geometry_np(geom)
        r = geom["radius"]
        side = geom["side"]
        dx_parts, rank_parts, dz_vals, counts = [], [], [], []
        perm = primary.permutation_np
        fine_perm = fine.permutation_np if is_cluster else None
        for dz, live_f, live_i in geom["rows_np"]:
            cz = spawn_chunk_z + dz
            cx_f = live_f if spawn_chunk_x == 0 else live_f + spawn_chunk_x
            if is_cluster:
                coarse_row = sample_row_np(perm, cx_f, cz, coarse_frequency)
                keep = coarse_row > coarse_threshold
                if not keep.any():
                    continue
                fine_row = sample_row_np(fine_perm, cx_f[keep], cz, frequency)
                hits = _np.nonzero(keep)[0][fine_row > threshold]
            else:
                noises = sample_row_np(perm, cx_f, cz, frequency)
                hits = _np.nonzero(noises > threshold)[0]
            if hits.size == 0:
                continue
            hit_dx = live_i[hits]
            dx_parts.append(hit_dx)
            rank_parts.append(priority_np(noise_seed, hit_dx + spawn_chunk_x, cz))
            dz_vals.append(dz)
            counts.append(hit_dx.size)
        if not dx_parts:
            return []

        dxs = _np.concatenate(dx_parts)
        dzs = _np.repeat(_np.array(dz_vals, dtype=_np.int64), counts)
        ranks = _np.zeros(side * side, dtype=_np.uint64)
        ranks[(dzs + r) * side + (dxs + r)] = _np.concatenate(rank_parts)
        got = _select_np(geom, ranks.reshape(side, side), dxs, dzs,
                         spawn_chunk_x, spawn_chunk_z)
        if got is not None:
            if not len(got):
                return []
            kdx, kdz = got
            return [(int(dx) + spawn_chunk_x, int(dz) + spawn_chunk_z)
                    for dx, dz in zip(kdx.tolist(), kdz.tolist())]

        # Degenerate rank field (a zero rank, or a tie needing the chunk-key
        # rule). Rebuild the scalar views and answer exactly.
        eligible = bytearray(side * side)
        flat = ((dzs + r) * side + (dxs + r)).tolist()
        for idx in flat:
            eligible[idx] = 1
        rank_list = ranks.tolist()
        return NoiseFieldIndex._select_scalar(
            geom, eligible, rank_list, list(zip(dxs.tolist(), dzs.tolist())),
            spawn_chunk_x, spawn_chunk_z)

    @staticmethod
    def _outranks(eligible, ranks, side, r, dx, dz, offsets,
                  cx, cz, spawn_chunk_x, spawn_chunk_z):
        rank = ranks[(dz + r) * side + (dx + r)]
        for ox, oz in offsets:
            nx = dx + ox
            nz = dz + oz
            if nx < -r or nx > r or nz < -r or nz > r:
                continue
            n_idx = (nz + r) * side + (nx + r)
            if not eligible[n_idx]:
                continue
            other = ranks[n_idx]
            # Java Long.compareUnsigned — both values are already unsigned.
            if other > rank:
                return False
            # A 64-bit tie is vanishingly rare, so the chunk key is derived
            # only when one happens rather than once per candidate.
            if other == rank and chunk_pos_to_long(
                    spawn_chunk_x + nx, spawn_chunk_z + nz) \
                    < chunk_pos_to_long(cx, cz):
                return False
        return True

    def is_placement(self, chunk_x, chunk_z):
        return (chunk_x, chunk_z) in self.placements

    def start_for(self, chunk_x, chunk_z):
        """NoiseFieldIndex.startFor."""
        region_x = _floor_div(chunk_x, self.spacing)
        region_z = _floor_div(chunk_z, self.spacing)
        hit = self.by_region.get(region_key(region_x, region_z))
        if hit is not None:
            return hit
        return (region_x * self.spacing, region_z * self.spacing)

    def positions(self):
        return self.ordered

    def __len__(self):
        return len(self.ordered)


def _floor_div(a, b):
    return a // b if b > 0 else math.floor(a / b)


def _disc_offsets(exclusion):
    """Chunk offsets inside an exclusion disc, self excluded. Mirrors the
    bounds of NoiseFieldIndex.outranksNeighbours' double loop."""
    excl_sq = exclusion * exclusion
    return [(ox, oz)
            for oz in range(-exclusion, exclusion + 1)
            for ox in range(-exclusion, exclusion + 1)
            if not (ox == 0 and oz == 0) and ox * ox + oz * oz <= excl_sq]


# ---------------------------------------------------------------------------
# Classification — mirrors scripts/gen-structure-groups.py and
# StructureGroupRegistry.rarityForSpacing.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Structure pick — mirrors StructurePick.java (§2.1 / §3.1).
# MIRRORED FILES — change together, re-run test_noise_parity.py:
#   mods/custom-dimensions/.../dimension/StructurePick.java
#   scripts/seed/noise_placement.py (this section)
# ---------------------------------------------------------------------------

#: Schema version stamped on every census artefact and sidecar. A cache entry
#: with a lower version is a miss, never a fallback.
NOISE_CENSUS_SCHEMA_VERSION = 2

#: FNV-1a + SplitMix64 of "structure_pick", matching DimensionStructures.saltOf.
PICK_SALT = None  # set lazily below to avoid circular init


def _ensure_pick_salt():
    global PICK_SALT
    if PICK_SALT is None:
        PICK_SALT = salt_of("structure_pick")
    return PICK_SALT


def pick_seed(noise_seed):
    """pickSeed = noiseSeed XOR saltOf("structure_pick"), masked to 64 bits.

    MIRRORS StructurePick.pickSeed — change together, re-run test_noise_parity.py.
    """
    return (noise_seed ^ _ensure_pick_salt()) & M64


def resolve_structure(sorted_pool, pick_value):
    """Assign a structure to a noise site via weighted cumulative walk.

    MIRRORS StructurePick.resolveWeighted — change together, re-run
    test_noise_parity.py.

    sorted_pool: [(structure_id, int_weight), ...] sorted by structure_id
                 (plain string sort, stable). Both sides sort before walking.
    pick_value:  unsigned 64-bit value from priority(). Python ints are
                 unsigned after the mix64 output, so `%` is equivalent to
                 Java's Long.remainderUnsigned.
    Returns the assigned structure_id, or None iff totalWeight <= 0
    (confirmed-empty pool — no structure can generate here on any seed).
    """
    total = sum(w for _, w in sorted_pool)
    if total <= 0:
        return None
    target = pick_value % total
    cumulative = 0
    for sid, weight in sorted_pool:
        cumulative += weight
        if cumulative > target:
            return sid
    # Reachable only on a rounding impossibility; the last entry wins.
    return sorted_pool[-1][0] if sorted_pool else None


def pool_hash(pools_for_dim):
    """md5 over sorted (group, structure_id, weight) tuples.

    Stamps every sidecar and summary so a mod update that changes pool
    composition (biome-filter outcomes, weight rebalance) invalidates the
    cache. An empty-pool dimension hashes too — {} is a valid state.
    """
    import hashlib
    tuples = []
    for group in sorted((pools_for_dim or {}).keys()):
        pool = pools_for_dim[group]
        for sid in sorted(pool.keys()):
            tuples.append((group, sid, pool[sid]))
    return hashlib.md5(
        json.dumps(tuples, sort_keys=True).encode()).hexdigest()[:12]


def rarity_for_spacing(spacing):
    """Spacing -> rarity tier. -1 means "not a random_spread placement"."""
    if spacing is None or spacing < 0:
        return "uncommon"
    if spacing > 80:
        return "endgame"
    if spacing > 45:
        return "rare"
    if spacing > 24:
        return "uncommon"
    return "common"


def salt_of(name):
    """DimensionStructures.saltOf — FNV-1a 64 then SplitMix64, SIGNED result.

    A plain hash would give neighbouring names neighbouring salts, so two
    similarly-named dimensions would have correlated noise fields.
    """
    if not name:
        return 0
    h = 0xCBF29CE484222325
    for ch in name:
        h ^= ord(ch) & 0xFFFF
        # Java chars are 16-bit and the XOR is on an int-widened char; the
        # multiply then wraps as a long.
        h = (h * 0x100000001B3) & M64
    return _signed64(mix64(h))


# ---------------------------------------------------------------------------
# Type defaults
# ---------------------------------------------------------------------------

_TYPE_DEFAULTS = {}


def load_type_defaults(config_dir):
    """Reads structure-type-defaults.json (the roller's copy of the jar data).

    Comment keys are stripped so the shape matches the jar resource.
    """
    key = str(config_dir)
    cached = _TYPE_DEFAULTS.get(key)
    if cached is not None:
        return cached
    path = Path(config_dir) / "structure-type-defaults.json"
    if not path.exists():
        _TYPE_DEFAULTS[key] = None
        return None
    doc = _strip_comments(json.loads(path.read_text()))
    _TYPE_DEFAULTS[key] = doc
    return doc


def load_structure_groups(config_dir):
    """Reads structure-groups.json -> {set_id: {group, rarity, theme, spacing}}."""
    path = Path(config_dir) / "structure-groups.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text()).get("sets", {})


def _strip_comments(node):
    if isinstance(node, dict):
        return {k: _strip_comments(v) for k, v in node.items()
                if not k.startswith("_")}
    if isinstance(node, list):
        return [_strip_comments(v) for v in node]
    return node


def resolve_groups(dim_config, type_defaults):
    """Mirror of NoiseGroupPlan.resolve.

    Returns {group: {"profile": NoiseProfile, "radial": [...], "exclusion": n}}
    or {} when noise is suppressed for this dimension.
    """
    if not type_defaults:
        return {}
    structures = dim_config.get("structures") or {}
    density = (dim_config.get("structureDensity") or "normal").lower()
    if density not in ("dense", "normal", "sparse", "none"):
        density = "normal"
    if density == "none":
        return {}
    mode = structures.get("mode")
    if isinstance(mode, str) and mode.strip().lower() == "none":
        return {}
    noise_field = structures.get("noise")
    if noise_field is False:
        return {}

    world_type = dim_config.get("type")
    type_entry = (type_defaults.get("types") or {}).get(world_type)
    if not type_entry:
        return {}

    group_defaults = type_defaults["groupDefaults"]

    # A group named explicitly under `structures.noise` is ADDED, not merely
    # re-profiled — the world type's list is a DEFAULT and an explicit entry is
    # the author saying what they want. MIRRORS NoiseGroupPlan.resolve; change
    # both together.
    enabled = list(type_entry.get("groups") or [])
    noise_cfg = structures.get("noise")
    if isinstance(noise_cfg, dict):
        for name, value in noise_cfg.items():
            if isinstance(value, str) and name not in enabled and name in group_defaults:
                enabled.append(name)
    if not enabled:
        return {}
    curves = type_defaults["curves"]
    shifts = type_defaults["difficultyShifts"]
    difficulty = dim_config.get("difficulty") or {}
    mob = difficulty.get("mobMultiplier")
    mob = 1.0 if mob is None else float(mob)

    global_profile = None
    if isinstance(noise_field, str):
        global_profile = noise_field
    elif density != "normal":
        global_profile = density

    per_group = noise_field if isinstance(noise_field, dict) else {}
    radial_overrides = structures.get("radial") or {}

    resolved = {}
    for group in enabled:
        gd = group_defaults.get(group)
        if not gd:
            continue
        profile_name = gd["profile"]
        if group in (type_entry.get("profiles") or {}):
            profile_name = type_entry["profiles"][group]
        if global_profile is not None:
            profile_name = global_profile
        # The peaceful shift sits ABOVE structureDensity, not below it as the
        # spike's precedence list has it — a coarse density dial must not
        # resurrect a group the dimension's difficulty says is not there.
        if mob <= shifts["peaceful"]["maxMobMultiplier"] \
                and group in shifts["peaceful"].get("profiles", {}):
            profile_name = shifts["peaceful"]["profiles"][group]
        if group in per_group and isinstance(per_group[group], str):
            profile_name = per_group[group]

        profile = profile_from_string(profile_name)
        if profile is None:
            continue

        radial = _resolve_radial(group, radial_overrides, type_entry, gd,
                                 curves, shifts, mob)
        exclusion = max(1, _java_round(gd["exclusion"] * profile.exclusion_multiplier))
        resolved[group] = {
            "profile": profile,
            "radial": radial,
            "exclusion": exclusion,
        }
    return resolved


def _resolve_radial(group, overrides, type_entry, group_default, curves, shifts, mob):
    explicit = overrides.get(group)
    if isinstance(explicit, list) and len(explicit) == 10 \
            and all(isinstance(v, (int, float)) and 0.0 <= v <= 3.0 for v in explicit):
        return [float(v) for v in explicit]
    curve_name = group_default["radial"]
    if group in (type_entry.get("radial") or {}):
        curve_name = type_entry["radial"][group]
    if mob >= shifts["hostile"]["minMobMultiplier"] \
            and group in shifts["hostile"].get("radial", {}):
        curve_name = shifts["hostile"]["radial"][group]
    curve = curves.get(curve_name)
    return [float(v) for v in curve] if curve else None


def _java_round(x):
    """Java's Math.round: floor(x + 0.5), which differs from Python's banker's
    rounding at exact halves (0.5 -> 1 in Java, 0 in Python)."""
    return math.floor(x + 0.5)


def noise_census(world_seed, dim_name, dim_config, type_defaults,
                 radius_chunks=None, spawn_chunk_x=0, spawn_chunk_z=0):
    """Every noise position in a dimension, grouped.

    Returns {group: [(chunk_x, chunk_z), ...]}. Mirrors what
    DimensionStructures.transformedNoise builds and what
    `/customdim structure-census` dumps.
    """
    groups = resolve_groups(dim_config, type_defaults)
    if not groups:
        return {}
    radius_chunks = census_radius_chunks(dim_config, radius_chunks)
    dim_salt = salt_of(dim_name)
    out = {}
    for group, settings in groups.items():
        noise_seed = _signed64(world_seed ^ dim_salt ^ salt_of(group))
        index = NoiseFieldIndex(noise_seed, settings["profile"], settings["exclusion"],
                                settings["radial"], radius_chunks,
                                spawn_chunk_x, spawn_chunk_z)
        out[group] = index.positions()
    return out


def census_radius_chunks(dim_config, radius_chunks=None):
    """The scanned radius the mod uses: borders.player / 16, capped.

    The cap matters — a dimension configured beyond MAX_RADIUS_CHUNKS is
    scanned to the cap on both sides, and the frequency scale is derived from
    the capped value, so the roller must cap identically or every position
    moves.
    """
    if radius_chunks is None:
        borders = dim_config.get("borders") or {}
        player = borders.get("player")
        if not isinstance(player, (int, float)) or isinstance(player, bool) \
                or player <= 0:
            player = 8192
        radius_chunks = int(player) // 16
    return max(0, min(int(radius_chunks), MAX_RADIUS_CHUNKS))


CENSUS_BINS = 10


def census_summary(world_seed, dim_name, dim_config, type_defaults,
                   radius_chunks=None, spawn_chunk_x=0, spawn_chunk_z=0,
                   bins=CENSUS_BINS, bin_origin_x=None, bin_origin_z=None):
    """A scoring-sized view of `noise_census` — counts and a radial histogram.

    Returns
      {"radiusChunks": r, "groups": {group: {"count": n, "hist": [n0..n9]}}}

    A full census of the largest shipped dimension is 62k positions; scoring
    only ever asks how many there are and how they spread, so the positions
    are histogrammed as they are produced rather than banked per candidate.
    Bins are equal-WIDTH in radius (not equal-area): bin i covers
    [i/bins, (i+1)/bins] of the playable radius, which is what a 10-point
    radial curve is indexed by.

    `bin_origin_x`/`bin_origin_z` (CHUNK coordinates) anchor the histogram
    only — the point the player actually stands on, i.e. the candidate's
    chosen spawn. They default to `spawn_chunk_x`/`spawn_chunk_z`, which
    remain the PLACEMENT anchor handed to NoiseFieldIndex and must stay 0
    for census parity: DimensionStructures passes (0, 0), so a non-zero
    placement anchor here would describe a world the mod never generates.

    Deliberately carries NO per-dimension config (profile, exclusion, radial
    curve): those are identical for every candidate of a dimension and the
    summary is banked per candidate. Storing them cost 2.5 MB per large
    dimension to say the same thing two hundred times — the scorer resolves
    them from the config instead (see score-dimensions.ensure_censuses).
    """
    groups = resolve_groups(dim_config, type_defaults)
    radius_chunks = census_radius_chunks(dim_config, radius_chunks)
    out = {"radiusChunks": radius_chunks, "groups": {}}
    if not groups:
        return out
    if bin_origin_x is None:
        bin_origin_x = spawn_chunk_x
    if bin_origin_z is None:
        bin_origin_z = spawn_chunk_z
    dim_salt = salt_of(dim_name)
    scale = float(radius_chunks) if radius_chunks > 0 else 1.0
    for group, settings in groups.items():
        noise_seed = _signed64(world_seed ^ dim_salt ^ salt_of(group))
        index = NoiseFieldIndex(noise_seed, settings["profile"], settings["exclusion"],
                                settings["radial"], radius_chunks,
                                spawn_chunk_x, spawn_chunk_z)
        from census_scoring import radial_hist
        hist = radial_hist(index.positions(), bin_origin_x, bin_origin_z,
                           radius_chunks, bins)
        out["groups"][group] = {"count": len(index), "hist": hist}
    return out


def _write_census_sidecar(seedtest, dim_name, seed, noise_fp, ph, group_data):
    """Atomic write of the exact-position sidecar.

    group_data: {group: {"ids": [sid, ...], "positions": [(cx, cz, id_index), ...]}}
    """
    import gzip
    import os
    import tempfile

    out_dir = Path(seedtest) / "census-positions" / dim_name
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / "{}.json.gz".format(seed)
    doc = {
        "schemaVersion": NOISE_CENSUS_SCHEMA_VERSION,
        "fp": noise_fp,
        "poolHash": ph,
        "groups": group_data,
    }
    raw = json.dumps(doc, separators=(",", ":")).encode()
    fd, tmp = tempfile.mkstemp(dir=str(out_dir), suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as fh:
            with gzip.GzipFile(fileobj=fh, mode="wb") as gz:
                gz.write(raw)
        os.replace(tmp, str(dest))
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def load_census_positions(seedtest, dim_name, seed):
    """Load exact positions from the sidecar cache.

    Returns {group: [(cx, cz, "ns:id"), ...]} or {} on miss.
    """
    import gzip
    p = Path(seedtest) / "census-positions" / dim_name / "{}.json.gz".format(seed)
    if not p.exists():
        return {}
    try:
        with gzip.open(str(p), "rb") as gz:
            doc = json.loads(gz.read())
    except (OSError, ValueError):
        return {}
    if doc.get("schemaVersion") != NOISE_CENSUS_SCHEMA_VERSION:
        return {}
    result = {}
    for group, gdata in (doc.get("groups") or {}).items():
        ids = gdata.get("ids") or []
        positions = []
        for pos in gdata.get("positions") or []:
            if len(pos) >= 3:
                cx, cz, id_idx = int(pos[0]), int(pos[1]), int(pos[2])
                sid = ids[id_idx] if 0 <= id_idx < len(ids) else None
                if sid is not None:
                    positions.append((cx, cz, sid))
        result[group] = positions
    return result


def census_sidecar_exists(seedtest, dim_name, seed):
    """True iff the sidecar file for this candidate exists."""
    return (Path(seedtest) / "census-positions" / dim_name
            / "{}.json.gz".format(seed)).exists()


def census_task(task):
    """Pool worker: one candidate's census summary with exact structure
    identity per position, plus a sidecar file of every position.

    Lives here, not beside its caller in score-dimensions.py, because
    pickling a function pickles a (module, qualname) pair and the child
    re-imports that module BY NAME. `score-dimensions.py` is hyphenated, so
    it is only ever loaded through importlib under the synthetic name
    `score_dimensions` — which no child can import, and multiprocessing on
    macOS spawns rather than forks. Registering the module in the parent's
    sys.modules fixes the pickling half and leaves the unpickling half
    failing in the worker with "No module named 'score_dimensions'".
    Any function handed to a Pool must therefore live in a module whose
    filename is a legal identifier.

    Tasks are 11-tuples:
      (name, seed, dim_config, type_defaults, radius_chunks,
       pools_for_dim, spawn_cx, spawn_cz, seedtest, noise_fp, pool_hash_val)
    where pools_for_dim is {group: {structure_id: weight}} from
    structure_pools.json for this dimension.
    """
    name, seed, dim_config, type_defaults, radius_chunks = task[:5]
    dim_pools = task[5] if len(task) > 5 else {}
    spawn_cx = task[6] if len(task) > 6 else 0
    spawn_cz = task[7] if len(task) > 7 else 0
    seedtest = task[8] if len(task) > 8 else None
    noise_fp = task[9] if len(task) > 9 else ""
    pool_hash_val = task[10] if len(task) > 10 else ""

    groups = resolve_groups(dim_config, type_defaults)
    radius_chunks = census_radius_chunks(dim_config, radius_chunks)
    dim_salt = salt_of(name)
    bins = CENSUS_BINS
    scale = float(radius_chunks) if radius_chunks > 0 else 1.0
    out = {"radiusChunks": radius_chunks, "groups": {}}

    sidecar_groups = {}

    for group, settings in groups.items():
        noise_seed = _signed64(int(seed) ^ dim_salt ^ salt_of(group))
        index = NoiseFieldIndex(noise_seed, settings["profile"],
                                settings["exclusion"], settings["radial"],
                                radius_chunks, 0, 0)
        positions = index.positions()

        gpool = (dim_pools or {}).get(group) or {}
        sorted_pool = sorted(gpool.items())
        ps = pick_seed(noise_seed)

        by_struct = {}
        from census_scoring import radial_hist
        hist = radial_hist(positions, spawn_cx, spawn_cz, radius_chunks, bins)
        # Build the id table and position list for the sidecar.
        id_to_index = {}
        id_list = []
        sidecar_positions = []
        for cx, cz in positions:
            if sorted_pool:
                pv = priority(ps, cx, cz)
                sid = resolve_structure(sorted_pool, pv)
                if sid is not None:
                    dist_blocks = math.sqrt(
                        float((cx - spawn_cx) * 16) ** 2
                        + float((cz - spawn_cz) * 16) ** 2)
                    entry = by_struct.get(sid)
                    if entry is None:
                        entry = {"count": 0, "nearest": float("inf")}
                        by_struct[sid] = entry
                    entry["count"] += 1
                    if dist_blocks < entry["nearest"]:
                        entry["nearest"] = dist_blocks
                    if sid not in id_to_index:
                        id_to_index[sid] = len(id_list)
                        id_list.append(sid)
                    sidecar_positions.append([cx, cz, id_to_index[sid]])

        for entry in by_struct.values():
            if entry["nearest"] == float("inf"):
                entry["nearest"] = -1
            else:
                entry["nearest"] = round(entry["nearest"], 1)

        out["groups"][group] = {
            "count": len(index),
            "hist": hist,
            "byStructure": by_struct,
        }
        sidecar_groups[group] = {"ids": id_list, "positions": sidecar_positions}

    if seedtest:
        try:
            _write_census_sidecar(seedtest, name, seed, noise_fp,
                                  pool_hash_val, sidecar_groups)
        except OSError:
            pass

    return (name, seed, out)
