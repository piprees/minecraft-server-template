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


class StructureNoise:
    """Mirror of StructureNoise.java."""

    __slots__ = ("permutation",)

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


NATURAL = NoiseProfile("natural", 0.025, 0.68, 1.0)
DENSE = NoiseProfile("dense", 0.040, 0.45, 0.6)
SPARSE = NoiseProfile("sparse", 0.015, 0.85, 1.5)
CLUSTER = NoiseProfile("cluster", 0.05, 0.40, 0.4,
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

    mix64 is inlined: this runs once per eligible chunk, which is millions of
    times for a 512-radius group.
    """
    z = (noise_seed
         ^ ((chunk_x * PRIORITY_X) & M64)
         ^ ((chunk_z * PRIORITY_Z) & M64)) & M64
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


def region_key(region_x, region_z):
    """NoiseFieldIndex.regionKey."""
    return _signed64(((region_x << 32) & M64) ^ (region_z & M32))


class NoiseFieldIndex:
    """Mirror of NoiseFieldIndex.java.

    Same two-pass shape as the Java side: mark eligibility over the bounding
    box, then keep the chunks that outrank every eligible neighbour inside the
    exclusion disc. Order-free by construction, so the ring walk below only
    fixes the ORDER of `positions`, never its contents.
    """

    def __init__(self, noise_seed, profile, exclusion, radial, radius_chunks,
                 spawn_chunk_x=0, spawn_chunk_z=0):
        noise_seed &= M64
        r = max(0, min(radius_chunks, MAX_RADIUS_CHUNKS))
        excl = max(1, exclusion)
        self.spacing = max(2, excl * 2)
        self.radius_chunks = r

        # Samplers bound ONCE, mirroring the Java constructor.
        scale = frequency_scale(r)
        frequency = profile.frequency * scale
        coarse_frequency = (profile.coarse_frequency or 0.0) * scale
        coarse_threshold = profile.coarse_threshold or 0.0
        is_cluster = profile.cluster
        primary = StructureNoise(noise_seed)
        fine = StructureNoise(_signed64(noise_seed ^ FINE_SALT) & M64) if is_cluster else None

        side = r * 2 + 1
        eligible = bytearray(side * side)
        # Ranks cached alongside eligibility, mirroring Java: every eligible
        # chunk is read once as a candidate and many times as a neighbour.
        ranks = [0] * (side * side)
        r_squared = float(r) * r
        threshold = profile.threshold

        # Row-at-a-time eligibility. Same arithmetic as the per-chunk form
        # this replaced, in the same order, and pinned bit-for-bit by the
        # parity fixtures — but a 512-radius group costs four million fewer
        # Python calls. Two exactness-preserving filters run before any
        # Perlin work:
        #   1. outside the disc — as before;
        #   2. radial weight <= threshold — `sample` is clamped to [0, 1], so
        #      such a chunk can never satisfy `noise * weight > threshold`
        #      however the noise falls. Whole deciles of most curves are 0.
        weight_cache = {}
        primary_perm = primary.permutation
        fine_perm = fine.permutation if is_cluster else None
        sqrt = math.sqrt
        for dz in range(-r, r + 1):
            row = (dz + r) * side
            cz = spawn_chunk_z + dz
            dz_sq = float(dz) * dz
            span = int(sqrt(max(r_squared - dz_sq, 0.0)))
            live_dx = []
            live_weights = []
            for dx in range(-span, span + 1):
                dist_sq = float(dx) * dx + dz_sq
                if dist_sq > r_squared:
                    continue
                key = int(dist_sq)
                weight = weight_cache.get(key)
                if weight is None:
                    weight = radial_weight(radial, sqrt(dist_sq), r)
                    weight_cache[key] = weight
                if weight <= threshold:
                    continue
                live_dx.append(dx)
                live_weights.append(weight)
            if not live_dx:
                continue
            live_cx = [spawn_chunk_x + dx for dx in live_dx]
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
                if noise * live_weights[i] > threshold:
                    dx = live_dx[i]
                    idx = row + (dx + r)
                    eligible[idx] = 1
                    ranks[idx] = priority(noise_seed, spawn_chunk_x + dx, cz)

        # Precompute the exclusion disc offsets once.
        offsets = []
        excl_sq = excl * excl
        for oz in range(-excl, excl + 1):
            for ox in range(-excl, excl + 1):
                if ox == 0 and oz == 0:
                    continue
                if ox * ox + oz * oz > excl_sq:
                    continue
                offsets.append((ox, oz))

        # Single O(r^2) pass, mirroring the Java constructor. Walking outward
        # ring by ring and skipping each ring's interior is O(r^3) — 1.8e8
        # iterations at radius 512, and the whole reason a large dimension
        # took 3.4 seconds to load. Order is restored by the sort below.
        ordered = []
        for dz in range(-r, r + 1):
            row = (dz + r) * side
            for dx in range(-r, r + 1):
                if not eligible[row + (dx + r)]:
                    continue
                cx = spawn_chunk_x + dx
                cz = spawn_chunk_z + dz
                if not self._outranks(eligible, ranks, side, r, dx, dz, offsets,
                                      cx, cz, spawn_chunk_x, spawn_chunk_z):
                    continue
                ordered.append((cx, cz))

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


# ---------------------------------------------------------------------------
# Classification — mirrors scripts/gen-structure-groups.py and
# StructureGroupRegistry.rarityForSpacing.
# ---------------------------------------------------------------------------

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
    if not type_entry or not type_entry.get("groups"):
        return {}

    group_defaults = type_defaults["groupDefaults"]
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
    for group in type_entry["groups"]:
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
                   bins=CENSUS_BINS):
    """A scoring-sized view of `noise_census` — counts and a radial histogram.

    Returns
      {"radiusChunks": r, "groups": {group: {"count": n, "hist": [n0..n9]}}}

    A full census of the largest shipped dimension is 62k positions; scoring
    only ever asks how many there are and how they spread, so the positions
    are histogrammed as they are produced rather than banked per candidate.
    Bins are equal-WIDTH in radius (not equal-area): bin i covers
    [i/bins, (i+1)/bins] of the playable radius, which is what a 10-point
    radial curve is indexed by.

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
    dim_salt = salt_of(dim_name)
    scale = float(radius_chunks) if radius_chunks > 0 else 1.0
    for group, settings in groups.items():
        noise_seed = _signed64(world_seed ^ dim_salt ^ salt_of(group))
        index = NoiseFieldIndex(noise_seed, settings["profile"], settings["exclusion"],
                                settings["radial"], radius_chunks,
                                spawn_chunk_x, spawn_chunk_z)
        hist = [0] * bins
        for cx, cz in index.positions():
            dx = cx - spawn_chunk_x
            dz = cz - spawn_chunk_z
            b = int(math.sqrt(float(dx) * dx + float(dz) * dz) / scale * bins)
            if b < 0:
                b = 0
            elif b >= bins:
                b = bins - 1
            hist[b] += 1
        out["groups"][group] = {"count": len(index), "hist": hist}
    return out
