"""tb_regions.py — Mirror of TerraBlender's region-based biome selection.

MIRRORS terrablender.worldgen.noise.LayeredNoiseUtil,
        terrablender.worldgen.noise.AreaContext,
        terrablender.worldgen.noise.ZoomLayer,
        terrablender.worldgen.noise.WeightedRandomLayer,
        terrablender.mixin.MixinParameterList (findValuePositional)
        — change together.

TerraBlender wraps vanilla's MultiNoiseBiomeSource for vanilla-typed
dimensions. At each biome lookup it computes a per-position "uniqueness"
from a layered-noise Area seeded from the world seed, and the uniqueness
selects which Region's parameter list answers the query.

Java-semantics rules relied on:
  - All arithmetic is Java signed 64-bit long (overflow wraps at 2^64);
    Python ints are arbitrary-precision, so every intermediate must be
    masked to 64 bits and sign-extended.
  - SeedMixer.mixSeed(seed, value) uses the LCG constants
    6364136223846793005 and 1442695040888963407 (net.minecraft.world.
    biome.source.SeedMixer, Yarn 1.21.1).
  - AreaContext.initRandom uses SeedMixer.mixSeed in a 4-step chain
    (seed, x, z, x, z), and nextRandom uses arithmetic right-shift
    by 24 then Math.floorMod.
  - The zoom layer stack is: initial(salt=1) -> FUZZY(salt=2000) ->
    3x NORMAL(salts=2001..2003) -> regionSize x NORMAL(salts=1001..).
  - FUZZY zoom always picks randomly among 4 parent cells for the (1,1)
    sub-pixel; NORMAL zoom uses a majority-mode then random fallback.
  - getUniqueness(quartX, quartY, quartZ) calls area.get(quartX, quartZ)
    — the Y coordinate is ignored.
  - The WeightedRandomLayer walks entries by cumulative weight; the
    weight and index of each region are provided by the _tbRegions
    sentinel in biome_params.json.
"""

import json
from pathlib import Path

from search_tree import SearchTree

# ---------------------------------------------------------------------------
# Java long helpers — identical semantics to biome_sampler._u64 / _i64
# ---------------------------------------------------------------------------
_MASK64 = 0xFFFFFFFFFFFFFFFF
_SIGN64 = 0x8000000000000000


def _i64(x):
    """Reduce an arbitrary Python int to a signed Java long."""
    x &= _MASK64
    return x - 0x10000000000000000 if x >= _SIGN64 else x


# ---------------------------------------------------------------------------
# SeedMixer.mixSeed — net.minecraft.world.biome.source.SeedMixer (Yarn)
# Bytecode: seed = seed * (seed * 6364136223846793005 + 1442695040888963407)
#           seed = seed + value
# ---------------------------------------------------------------------------
_LCG_MULT = 6364136223846793005
_LCG_ADD = 1442695040888963407


def _seed_mix(seed, value):
    seed = _i64(seed * _i64(seed * _LCG_MULT + _LCG_ADD))
    return _i64(seed + value)


# ---------------------------------------------------------------------------
# AreaContext — terrablender.worldgen.noise.AreaContext
# ---------------------------------------------------------------------------
class _AreaContext:
    """Per-layer RNG context, seeded from the world seed and a salt."""

    def __init__(self, world_seed, salt):
        # mixSeed(worldSeed, salt): 3 rounds on salt, 3 rounds mixing
        temp = _seed_mix(salt, salt)
        temp = _seed_mix(temp, salt)
        temp = _seed_mix(temp, salt)
        s = _seed_mix(world_seed, temp)
        s = _seed_mix(s, temp)
        self.seed = _seed_mix(s, temp)
        self.rval = 0

    def init_random(self, x, z):
        """Prepare the RNG for a specific (x, z) coordinate."""
        r = self.seed
        r = _seed_mix(r, x)
        r = _seed_mix(r, z)
        r = _seed_mix(r, x)
        self.rval = _seed_mix(r, z)

    def next_random(self, bound):
        """Draw one value in [0, bound) and advance the state."""
        # Java: Math.floorMod(rval >> 24, bound)
        # Python's % on positive divisor gives floorMod semantics.
        result = (self.rval >> 24) % bound
        self.rval = _seed_mix(self.rval, self.seed)
        return result

    def random2(self, a, b):
        return a if self.next_random(2) == 0 else b

    def random4(self, a, b, c, d):
        pick = self.next_random(4)
        if pick == 0:
            return a
        if pick == 1:
            return b
        return c if pick == 2 else d


# ---------------------------------------------------------------------------
# Weighted random selection — terrablender.util.WeightedRandomList
# Mirrors MC's WeightedPicker.getAt: walk items by cumulative weight.
# ---------------------------------------------------------------------------
def _weighted_pick(weights, indices, ctx):
    """Pick a region index using the AreaContext's nextRandom.

    weights: list of int weights, one per region, in registration order.
    indices: list of int indices, one per region.
    ctx: _AreaContext positioned for this (x, z).
    Returns the picked region index, or 0 if the list is empty.
    """
    total = sum(weights)
    if total <= 0:
        return 0
    pick = ctx.next_random(total)
    for w, idx in zip(weights, indices):
        pick -= w
        if pick < 0:
            return idx
    return indices[0] if indices else 0


# ---------------------------------------------------------------------------
# Zoom layers — terrablender.worldgen.noise.ZoomLayer (NORMAL + FUZZY)
# ---------------------------------------------------------------------------
def _mode_or_random_normal(ctx, a, b, c, d):
    """NORMAL zoom: majority-mode among 4 values, random fallback.

    Checks all 3-of-4 and 2-of-4-with-distinct-remainder combinations
    in the exact order from the bytecode. With two 2-of-4 pairs (e.g.
    a==b and c==d), falls through to random — no deterministic pick.
    """
    # 3-of-4 or 4-of-4
    if b == c and c == d:
        return b
    if a == b and a == c:
        return a
    if a == b and a == d:
        return a
    if a == c and a == d:
        return a
    # 2-of-4 where the other two differ
    if a == b and c != d:
        return a
    if a == c and b != d:
        return a
    if a == d and b != c:
        return a
    if b == c and a != d:
        return b
    if b == d and a != c:
        return b
    if c == d and a != b:
        return c
    # No majority
    return ctx.random4(a, b, c, d)


def _zoom_apply(ctx, parent_fn, x, z, fuzzy):
    """One ZoomLayer.apply call.

    parent_fn: callable (x, z) -> int, the parent layer's evaluator.
    fuzzy: if True, the (1,1) corner always picks randomly.
    """
    px, pz = x >> 1, z >> 1
    val = parent_fn(px, pz)
    # initRandom receives (x>>1)<<1 and (z>>1)<<1 as longs
    ctx.init_random(_i64((x >> 1) << 1), _i64((z >> 1) << 1))
    odd_x = x & 1
    odd_z = z & 1
    if odd_x == 0 and odd_z == 0:
        return val
    # Fetch neighbours as needed
    bottom = parent_fn(px, (z + 1) >> 1)
    choose_xz = ctx.random2(val, bottom)
    if odd_x == 0 and odd_z == 1:
        return choose_xz
    right = parent_fn((x + 1) >> 1, pz)
    choose_xr = ctx.random2(val, right)
    if odd_x == 1 and odd_z == 0:
        return choose_xr
    # (1, 1): all four parents
    bottom_right = parent_fn((x + 1) >> 1, (z + 1) >> 1)
    if fuzzy:
        return ctx.random4(val, right, bottom, bottom_right)
    return _mode_or_random_normal(ctx, val, right, bottom, bottom_right)


# ---------------------------------------------------------------------------
# Cached area — transparent hash-map cache over a PixelTransformer
# ---------------------------------------------------------------------------
class _CachedArea:
    """Python-side memoisation replacing the Java Area's StampedLock cache."""

    def __init__(self, fn):
        self._fn = fn
        self._cache = {}

    def get(self, x, z):
        key = (x, z)
        if key not in self._cache:
            self._cache[key] = self._fn(x, z)
        return self._cache[key]


# ---------------------------------------------------------------------------
# Uniqueness area construction
# ---------------------------------------------------------------------------
def _build_uniqueness(world_seed, region_weights, region_indices,
                      region_size=3):
    """Build the layered-noise uniqueness Area from the region table.

    The layer stack mirrors LayeredNoiseUtil.createZoomedArea exactly:
      1. Initial weighted-random layer (salt=1)
      2. FUZZY zoom (salt=2000)
      3. 3x NORMAL zoom (salts=2001, 2002, 2003)
      4. regionSize x NORMAL zoom (salts=1001, 1002, ..., 1000+regionSize)

    Returns a _CachedArea whose .get(quartX, quartZ) returns a region index.
    """
    # 1. Initial layer
    init_ctx = _AreaContext(world_seed, 1)

    def _initial(x, z, _ctx=init_ctx, _w=region_weights, _i=region_indices):
        _ctx.init_random(_i64(x), _i64(z))
        return _weighted_pick(_w, _i, _ctx)

    current = _CachedArea(_initial)

    # 2. FUZZY zoom (salt=2000)
    fuzzy_ctx = _AreaContext(world_seed, 2000)

    def _make_zoom(parent, ctx, is_fuzzy):
        def _zoom(x, z, _p=parent, _c=ctx, _f=is_fuzzy):
            return _zoom_apply(_c, _p.get, x, z, _f)
        return _CachedArea(_zoom)

    current = _make_zoom(current, fuzzy_ctx, True)

    # 3. Three NORMAL zoom layers (salts=2001, 2002, 2003)
    for i in range(3):
        ctx = _AreaContext(world_seed, 2001 + i)
        current = _make_zoom(current, ctx, False)

    # 4. regionSize NORMAL zoom layers (salts=1001 .. 1000+regionSize)
    for i in range(region_size):
        ctx = _AreaContext(world_seed, 1001 + i)
        current = _make_zoom(current, ctx, False)

    return current


# ---------------------------------------------------------------------------
# Region table loader
# ---------------------------------------------------------------------------
_DEFERRED_PLACEHOLDER = "terrablender:deferred_placeholder"


def load_tb_regions(biome_params_path):
    """Load all _tbRegions sentinels from a biome_params.json file.

    Returns a dict {type_str: sentinel_data} for every sentinel found,
    e.g. {"overworld": {...}, "nether": {...}}. Empty dict when no
    sentinels are present.
    """
    data = json.loads(Path(biome_params_path).read_text())
    result = {}
    for entry in data:
        if "_tbRegions" in entry:
            sentinel = entry["_tbRegions"]
            region_type = sentinel.get("type", "overworld")
            result[region_type] = sentinel
    return result


def _build_region_trees(tb_regions):
    """Build a SearchTree for each region from the sentinel data.

    Returns a list of (region_name, SearchTree) indexed by region index.
    """
    from biome_sampler import _to_long

    max_idx = max(r["index"] for r in tb_regions["regions"])
    trees = [None] * (max_idx + 1)

    for region in tb_regions["regions"]:
        entries = []
        for biome_entry in region["biomes"]:
            biome_id = biome_entry["biome"]
            flat = []
            for param in ("temperature", "humidity", "continentalness",
                          "erosion", "depth", "weirdness"):
                lo, hi = biome_entry[param]
                flat.append(_to_long(lo))
                flat.append(_to_long(hi))
            offset_long = _to_long(biome_entry.get("offset", 0.0))
            tree_params = tuple(flat) + (offset_long, offset_long)
            entries.append((tree_params, biome_id))

        tree = SearchTree(entries) if entries else None
        trees[region["index"]] = (region["name"], tree)

    return trees


# ---------------------------------------------------------------------------
# TB-aware biome source
# ---------------------------------------------------------------------------
class TBBiomeSource:
    """Biome source wrapping TerraBlender's region selection.

    Given a world seed and the _tbRegions sentinel data, this evaluates
    the exact same layered-noise uniqueness + per-region SearchTree
    lookup that the Java MixinParameterList.findValuePositional performs.

    Climate sampling is delegated to a vanilla BiomeSampler (passed as
    climate_sampler) which provides the 6-axis noise values. This class
    only replaces the biome-from-climate step.
    """

    def __init__(self, world_seed, tb_regions, climate_sampler,
                 region_size=3, region0_entries=None):
        """Build the TB biome source.

        Args:
            world_seed: the world seed (long)
            tb_regions: the _tbRegions sentinel dict from biome_params.json
            climate_sampler: a BiomeSampler (or compatible) providing
                sample_climate(x, z) -> dict with the 6 climate axes
            region_size: overworld=3 (default), nether=2
            region0_entries: optional list of (params_14, biome_id) pairs
                to use for region 0's tree instead of the sentinel's. Use
                this for custom dimensions whose parameter list differs
                from the full overworld.
        """
        self.seed = world_seed
        self._climate = climate_sampler

        weights = [r["weight"] for r in tb_regions["regions"]]
        indices = [r["index"] for r in tb_regions["regions"]]
        self._uniqueness = _build_uniqueness(
            world_seed, weights, indices, region_size)

        self._trees = _build_region_trees(tb_regions)
        if region0_entries is not None:
            self._trees[0] = ("custom", SearchTree(region0_entries))
        self.climate_exact = getattr(climate_sampler, 'climate_exact', {})
        self.depth_exact = getattr(climate_sampler, 'depth_exact', False)

    def uniqueness_at(self, block_x, block_z):
        """Return the region index at block coordinates."""
        return self._uniqueness.get(block_x >> 2, block_z >> 2)

    def biome_at(self, x, z):
        """Return the biome ID at block coordinates (x, z)."""
        return self.biome_and_climate(x, z)[0]

    def biome_and_climate(self, x, z):
        """Return (biome_id, climate_dict) at block coordinates."""
        from biome_sampler import _to_long

        climate = self._climate.sample_climate(x, z)
        tl = _to_long(climate["temperature"])
        hl = _to_long(climate["humidity"])
        cl = _to_long(climate["continentalness"])
        el = _to_long(climate["erosion"])
        dl = _to_long(climate["depth"])
        wl = _to_long(climate["weirdness"])
        point = (tl, hl, cl, el, dl, wl, 0)

        region_idx = self._uniqueness.get(x >> 2, z >> 2)

        entry = self._trees[region_idx] if region_idx < len(self._trees) else None
        if entry is None or entry[1] is None:
            entry = self._trees[0]

        biome = entry[1].get(point)

        if biome == _DEFERRED_PLACEHOLDER:
            fallback = self._trees[0]
            if fallback is not None and fallback[1] is not None:
                biome = fallback[1].get(point)

        return biome, climate

    def sample_climate(self, x, z):
        """Delegate climate sampling to the underlying sampler."""
        return self._climate.sample_climate(x, z)
