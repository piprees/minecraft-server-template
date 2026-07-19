# Fast Spawn & Biome Lookup — Pure-Python Seed Pre-Filter

## Status: research complete, ready to implement

## Problem

The seed roller's biome checks (spawn filter, variety biomes) run via RCON `locate biome` commands that block the server thread for 1-30s each. With 8 namesake + 4 variety = 12 biome locates per candidate, a single seed takes 30-120s just for biomes. Combined with 73 dimensions and 3 workers sharing a Mac, this produces RCON timeouts and worker crashes.

Structure placement was solved in this session by reimplementing vanilla's `RandomSpreadStructurePlacement` algorithm in pure Python (357 structure sets in 22ms, no server). Biomes can be solved the same way.

## How Minecraft biome placement works

Biome selection at any (x, y, z) is a pure function of:

1. **Six noise parameters** sampled at the coordinate:
   - temperature, humidity, continentalness, erosion, depth, weirdness
   - Each is a `DoublePerlinNoiseSampler` — a sum of two Perlin octave samplers
   - Octave count, amplitude, and seed derivation are fixed per parameter

2. **A biome parameter table** that maps 6D noise regions to biome IDs:
   - Vanilla: `MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD`
   - Each entry: `{temperature: [min, max], humidity: [min, max], ...}` → biome
   - The lookup is nearest-neighbour in 6D parameter space (Euclidean distance)

3. **Modded biome injection** (TerraBlender, Terralith, Nature's Spirit):
   - TerraBlender replaces the vanilla parameter list with a merged one at runtime
   - Terralith adds ~90 biomes with custom parameter ranges
   - Nature's Spirit adds ~15 biomes
   - The MERGED parameter table is what determines biome placement

## Implementation plan

### Phase 1: Extract the biome parameter table (one-time, from a running server)

Add a `/customdim dump-biome-params <dimension>` command to the mod that serialises the active `MultiNoiseBiomeSource`'s parameter entries to JSON:

```json
[
  {
    "biome": "minecraft:plains",
    "temperature": [-0.45, 0.2],
    "humidity": [-1.0, -0.35],
    "continentalness": [-0.19, 1.0],
    "erosion": [-1.0, 1.0],
    "depth": [0.0, 0.0],
    "weirdness": [-1.0, -0.5333]
  },
  ...
]
```

This captures the FINAL merged table (vanilla + TerraBlender + all mods) as the server sees it. Run once per mod-set change, cache the output.

**Access point**: `MultiNoiseBiomeSource` → the `MultiNoiseBiomeSourceAccessor` mixin already exposes `invokeGetBiomeEntries()` which returns `MultiNoiseUtil.Entries<RegistryEntry<Biome>>`. Each entry has a `NoiseHypercube` with the 6 parameter ranges.

### Phase 2: Implement Perlin noise sampling in Python

The noise sampler chain:

```
WorldSeed
  → hash to get per-parameter seed
    → DoublePerlinNoiseSampler (two ImprovedNoiseSampler arrays)
      → ImprovedNoiseSampler (Perlin gradient noise)
        → permutation table (256 entries, seeded)
        → gradient interpolation (standard Perlin)
```

Key functions to reimplement:
- `ImprovedNoiseSampler.sample(x, y, z)` — standard Perlin with permutation table
- `OctavePerlinNoiseSampler.sample(x, y, z)` — sum of N octaves with scaling
- `DoublePerlinNoiseSampler.sample(x, y, z)` — two octave samplers combined
- `MultiNoiseUtil.createNoiseValuePoint(...)` — sample all 6 parameters at a point

Reference: `net.minecraft.util.math.noise` package in Yarn mappings. The Perlin implementation is standard (Ken Perlin's improved noise, 2002) — many Python implementations exist.

### Phase 3: Biome lookup at a point

```python
def biome_at(x, z, seed, param_table):
    """Returns the biome ID at (x, z) for the given seed."""
    # 1. Sample 6 noise parameters at (x/4, 0, z/4)
    #    (biomes use quarter-resolution coordinates)
    qx, qz = x // 4, z // 4
    temp = temperature_sampler.sample(qx, 0, qz)
    humid = humidity_sampler.sample(qx, 0, qz)
    cont = continentalness_sampler.sample(qx, 0, qz)
    eros = erosion_sampler.sample(qx, 0, qz)
    depth = 0.0  # surface
    weird = weirdness_sampler.sample(qx, 0, qz)

    # 2. Find nearest biome in parameter table
    point = (temp, humid, cont, eros, depth, weird)
    best_biome, best_dist = None, float('inf')
    for entry in param_table:
        dist = sum((clamp(p, lo, hi) - p) ** 2
                   for p, (lo, hi) in zip(point, entry['ranges']))
        if dist < best_dist:
            best_dist = dist
            best_biome = entry['biome']
    return best_biome
```

### Phase 4: Fast spawn filter

```python
def spawn_filter(seed, namesake_biomes, radius=768):
    """Check if any namesake biome exists within radius of origin."""
    # Sample biome at a grid of points
    step = 64  # 64-block grid = reasonable resolution
    for x in range(-radius, radius + 1, step):
        for z in range(-radius, radius + 1, step):
            biome = biome_at(x, z, seed, param_table)
            if biome in namesake_biomes:
                dist = int(math.sqrt(x*x + z*z))
                return biome, dist
    return None, -1
```

At 64-block step over a 768-block radius: `(1536/64)² = 576 sample points × 6 noise lookups = 3,456 Perlin samples`. Each Perlin sample is ~1μs in Python → **~3.5ms per seed**.

### Phase 5: Locate biome (nearest instance)

Same as spawn filter but searching outward in concentric rings until found, or returning -1 after the cap radius. Used for variety biome scoring.

## Performance estimates

| Operation | Current (RCON) | Pure Python |
|-----------|---------------|-------------|
| Structure placement (all 357 sets) | disabled (kills RCON) | 22ms |
| Spawn biome at 0,0 | ~2s | <0.1ms |
| Spawn filter (8 biomes × 768 radius) | ~30s | ~5ms |
| Variety biomes (4 × locate) | ~20s | ~10ms |
| **Total per seed (no terrain)** | **~50s + crashes** | **~37ms** |

At 37ms per seed: **27 seeds/second → 1,620/minute → 97,000/hour**.

## What STILL needs the server

- **Terrain heights** (3×3 grid): requires actual chunk generation via forceload + block probes
- **Water detection**: same — needs generated blocks
- **BlueMap renders**: needs generated chunks + BlueMap renderer
- **Spawn biome identification** (the `if biome 0 Y 0 <id>` probes): could be replaced by the Python sampler

## Verification strategy

1. Boot one container, create a test dimension with known seed
2. Sample biome at 20 points via RCON (`execute if biome X Y Z <biome>`)
3. Sample the same 20 points via the Python implementation
4. All 20 must match — any mismatch means the noise implementation or parameter table is wrong

## Dependencies

- The `MultiNoiseBiomeSourceAccessor` mixin (already exists in the mod)
- A new `/customdim dump-biome-params` command (small addition)
- Python `struct` module for seed hashing (already used)
- No external libraries needed — Perlin noise is ~80 lines

## Risks

- **TerraBlender's region system**: TerraBlender doesn't just add biomes to the parameter table — it uses a region-based system where different areas of the world use different parameter tables. The dump command must capture the FULL merged view, not just the vanilla subset.
- **Modded noise modifications**: some mods (Tectonic, Terralith) modify the noise router itself, not just the biome table. If they change how the 6 parameters are computed, the Python sampler would diverge. Verify with the 20-point check.
- **Version sensitivity**: the Perlin implementation and parameter table format are stable within 1.21.x but change across major versions. Pin to 1.21.1.

## Files

- `scripts/seed/structure_placement.py` — already done, working
- `scripts/seed/biome_sampler.py` — new: Perlin noise + biome lookup
- `scripts/seed/noise_params.json` — cached: extracted biome parameter table
- `mods/custom-dimensions/.../DimensionCommands.java` — add `dump-biome-params`
