# Seed Rolling Pipeline

Pure-Python seed evaluation for custom dimensions — no Minecraft server, no Docker (after initial warmup). Scores thousands of seeds per second against structure placement, biome layout, and terrain quality, then renders terrain-aware top-down maps of the best candidates.

## Architecture Overview

```
roll-all.sh orchestration
├── Phase 1: Warmup (Docker, one-time)
│   ├── Extract structure sets from mod JARs
│   └── Dump biome params from MC server (/customdim dump-biome-params)
├── Phase 2: Roll (pure Python, fast)
│   ├── fast_roller.py
│   │   ├── Tier 1: structure placement screening (100K+ seeds/sec)
│   │   └── Tier 2: biome + terrain on survivors (~15 seeds/sec)
│   └── Output: candidates in config/custom-dimensions/candidates/
├── Phase 3: Render (pure Python, fast)
│   ├── biome_renderer.py batch mode
│   └── Output: .seedtest/renders/<dim>/<seed>.png
└── Finalise: score + viewer
    ├── score-dimensions.py
    └── Output: .seedtest/viewer.html
```

### Entry points

| Command | What it does |
| --- | --- |
| `./dev seed-roll-all` | Full run: warmup → roll → render → viewer |
| `./dev seed-roll-all --dims the_gauntlet` | Single dimension |
| `./dev seed-roll-all --no-render` | Roll only, skip map rendering |
| `./dev seed-roll-all --render-only` | Re-render existing candidates |

Environment variables `ROLL_POOL`, `ROLL_COUNT`, `ROLL_RENDER_TOP`, `ROLL_RENDER_SIZE` control pool size, candidate count, render count, and render area.

## Data Files

| File | Source | Purpose |
| --- | --- | --- |
| `biome_params.json` | MC server warmup (`/customdim dump-biome-params`) | Multinoise parameter table: 1803 entries, 177 biomes across 4 families. Maps each biome to its 6D climate ranges (temperature, humidity, continentalness, erosion, depth, weirdness) with family tags. |
| `noise_configs.json` | Extracted from mod JARs | Per-family noise parameters: octave configs and xz_scale for each climate parameter (temperature, humidity, continentalness, erosion, weirdness). Overworld, nether, end, and paradise_lost families. |
| `terrain_splines.json` | Extracted from Terralith JAR (`data/minecraft/worldgen/density_function/overworld/offset.json`) | Nested cubic Hermite spline tree for Terralith's terrain offset function. ~11KB of control points driving surface height computation. |
| `.seedtest/.structure_sets/` | Extracted from mod JARs (warmup phase) | Structure placement rules: spacing, separation, salt, spread type, frequency per structure set. Used for tier-1 screening. |

## Module Reference

### `biome_sampler.py` — Multinoise biome sampling

Reimplements vanilla's `MultiNoiseBiomeSource`: Xoroshiro128++ PRNG → DoublePerlinNoise → 6D climate parameters → nearest-neighbour biome lookup. Supports modded biomes via the extracted parameter table. Family-aware: each dimension family uses its own noise config. Key classes: `Xoroshiro128PlusPlus`, `DoublePerlinNoiseSampler`, `BiomeSampler`.

### `terrain_height.py` — Spline-based terrain heights

Evaluates Terralith's nested cubic Hermite offset spline to compute approximate surface Y from climate parameters. The `TerrainEvaluator` class compiles the spline tree into tuples for ~2µs/point evaluation. Only applies to overworld-family dimensions; other families use simpler heuristic height functions.

### `surface_rules.py` — Surface block colours

Maps biome IDs to MC map colours via a two-layer system: explicit lookup table for known biomes, keyword-based fallback for unknowns. Provides surface colour (sand, stone, grass, netherrack, etc.), vegetation density multipliers, and temperature-based grass tinting. 123 biomes mapped explicitly.

### `biome_renderer.py` — Map renderer

Generates terrain-aware PNG images from seeds using the sampler, height evaluator, and surface rules. Blends surface-block colours with biome-identity colours, applies hillshade from computed heights, water depth shading, vegetation density overlay, and weirdness micro-texture. Renders at ~0.5–2s per 1024×1024 image. Supports batch mode for rendering top-N candidates per dimension.

### `fast_roller.py` — Candidate generator

Two-tier screening pipeline. Tier 1 (instant): structure placement check using extracted structure sets — 100K+ seeds/sec. Tier 2 (fast): full biome sampling + terrain proxy on tier-1 survivors — ~15 seeds/sec. Outputs CSV measurements consumed by `score-dimensions.py`. Parallelised across dimensions.

### `dimension_profiles.py` — Scoring profiles

Single source of truth for what gets measured and how candidates are judged. Derives per-dimension profiles from the config: mood-driven weights (namesake, variety, terrain, structures), structure placement bands, terrain targets, spawn filters, biome variety probes. Handles the full dimension taxonomy (overworld, nether, end, paradise_lost, void, sky_islands).

Also home of `generation_fingerprint()` — the seed-group-rolling key (see below).

### `structure_placement.py` — Structure distance computation

Computes nearest structure placement from seed + structure set parameters (spacing, separation, salt, spread type). Pure math — no world generation.

### `score-dimensions.py` — Scoring and finalisation

Scores measured candidates against dimension profiles, persists candidate stores, writes config winners, and generates the HTML viewer.

### `candidates.py` — Candidate storage

Manages the per-dimension JSON candidate stores in `config/custom-dimensions/candidates/`.

## Seed-Group Rolling

Many dimensions are "same world, different curated taste" — identical generation settings, differing only by wants/shuns/spawn filters. Those share every seed's measurements, so the roller measures each seed **once per generation fingerprint** and banks the rows for every member.

### The invariant (and its hard edge)

Two dimensions can share a seed's measurements **iff their generation-affecting config is byte-identical**: `type`, `noiseSettings`, the full ordered biome list (one biome's difference re-deals the whole layout), per-biome `parameters`, `structureDensity`, the peaceful overlay (`hostileSpawning: false` drops structure sets), worldgen `environment` fields (minY/height/logicalHeight/coordinateScale), `borders.generation`, `checkerboardScale`/`layers`/`flatBiome`, `settingsOverrides`, `biomePatches`, `exitShrines` (raises the shrine set's frequency), and `structures.spacing` (rescales placements). Everything else — `seedRoll`, `portal`, difficulty multipliers, description, colours — is scoring or runtime and shares freely.

**Hard edge**: measurements never transfer across differing biome lists, even "similar" ones. Same-or-nothing.

### How it works

- `dimension_profiles.generation_fingerprint(dim)` → sha256[:12] of the canonical generation payload (`generation_payload` for debugging). Base-world entries return `None` and never group.
- `fast_roller` groups rollable targets by fingerprint. Each group screens ONE shared tier-1 pool (every member scores every seed), unions the per-member top `--count` survivors, and tier-2-measures each survivor once — a `MemoSampler` (coordinate-cached, exact) serves every member, so per-member rows are **bit-identical to a solo run**. Every member banks rows for every group seed: richer assignment pool, seeds rejected by one member's spawn filter still count for its siblings, and every rejection is banked (never re-rolled).
- Candidates are stamped with the fingerprint they were **measured under** (`candidates/{slug}.json` → `candidates.<seed>.fingerprint`).
- `score-dimensions.py finalise` assigns winners **injectively within a group** — two members with the same fingerprint AND the same seed are literal world clones, so winners must be distinct seeds. Greedy best-fit: pins claim their seed first, then members in best-score order walk down their own ranking past taken seeds.
- **Fingerprint drift**: a generation-config change re-keys the dim to a new fingerprint. Its banked measurements stay (never deleted), but `finalise` and `status` warn when a winner was measured under a different fingerprint — those measurements describe a world the config no longer generates, and only a re-roll fixes that (rescoring can't).

The payoff (measured 2026-07-23, 78 custom dims): 8 groups covering 31 dims — the biggest is the 6-dim nether-default group. For a 5-member group, tier-2 measurement of the whole group costs roughly what one member used to.

## The Biome Sampling Algorithm

The sampler reimplements MC's exact multinoise biome source algorithm:

1. **PRNG**: `Xoroshiro128++` seeded from the world seed via `mixStafford13`. A `RandomDeriver` forks the RNG and derives per-noise-parameter seeds by MD5-hashing the noise ID string.

2. **Noise layers**: Each climate parameter (temperature, humidity, continentalness, erosion, weirdness) gets a `DoublePerlinNoiseSampler` — two `OctavePerlinNoiseSampler` instances combined. Octave counts and amplitudes come from `noise_configs.json`; coordinate scaling (`xz_scale`) controls feature size.

3. **Coordinate shifts**: An offset noise (`minecraft:offset`) shifts the sampling coordinates, decorrelating the climate parameters so they don't align on grid boundaries.

4. **Climate vector**: At each (x, z), the sampler produces a 6D vector: (temperature, humidity, continentalness, erosion, depth=0, weirdness).

5. **Nearest-neighbour lookup**: The climate vector is compared against every entry in `biome_params.json` using squared Euclidean distance in 6D space. The entry with the smallest distance wins — that's the biome at that point.

This matches cubiomes' approach exactly, but supports modded biomes (Terralith, Incendium, Nullscape, Paradise Lost, Nature's Spirit) via the extracted parameter table rather than hardcoded vanilla entries.

## Terrain Height Computation

Surface height is derived from Terralith's offset spline — the density function that controls how high or low the terrain sits.

### The spline system

Terralith replaces vanilla's `overworld/offset` density function with a deeply nested cubic Hermite spline tree. The spline is keyed on three climate parameters:

1. **Continentalness** → top-level (ocean vs inland vs mountain)
2. **Erosion** → second level (flat plains vs carved valleys)
3. **Ridges folded** → leaf level (peaks vs valleys within a region)

Where `ridges_folded = -(|abs(weirdness) - 0.6666667| - 0.3333334)`

### The height formula

The relationship between the offset spline and surface height comes from MC's Y-clamped gradient density function:

```
depth(Y) = 1.5 - 3 * (Y + 64) / 384
```

At the surface, `offset + depth = 0`, solving for Y gives:

```
surface_Y = 128 * (1 + offset)
```

Where `offset = -0.5037500262260437 + spline(continentalness, erosion, ridges_folded)`.

This gives Y ≈ 63 at sea level (offset ≈ −0.5, spline ≈ 0). Accuracy is ±5–15 blocks compared to real worldgen — more than sufficient for hillshade rendering and terrain scoring.

### Cubic Hermite interpolation

Each spline segment uses the standard Hermite basis:

```
H(t) = (2t³ - 3t² + 1)·p₀ + (t³ - 2t² + t)·m₀ + (-2t³ + 3t²)·p₁ + (t³ - t²)·m₁
```

Where `p₀`, `p₁` are endpoint values (which may themselves be sub-splines evaluated recursively) and `m₀`, `m₁` are derivatives scaled by the segment span.

### Non-overworld families

Other dimension families lack Terralith's spline data and use simpler height functions:

| Family | Height function | Domain |
| --- | --- | --- |
| Nether | `64 + erosion×25 + ridges_folded×15`, clamped 8–120 | Compressed cave ceiling/floor |
| End | Void below `continentalness < -0.1`, otherwise `50 + cont×40 + rf×20 - ero×10` | Floating islands with void gaps |
| Paradise Lost | `80 + erosion×25 + ridges_folded×20`, clamped 10–140 | Elevated skylands |
| Fallback | `63 + cont×40 - ero×20 + rf×15`, clamped 0–200 | Generic overworld-like |

## The Rendering Pipeline

Each map image is produced by:

1. **Biome sampling**: A `BiomeSampler` evaluates the noise at a grid of points across the render area (default 128×128 sample resolution, upscaled to the output size).

2. **Colour blending**: Each sample point gets two colours — the surface-block colour from `surface_rules.py` (what MC's map system would show: sand for deserts, stone for mountains, grass for forests) and the biome-identity colour from `BIOME_COLOURS` (a hand-tuned palette for visual distinction). These are blended at 55% surface-block + 45% biome-identity. The blend ratio was chosen for readability: pure surface colours make all grass biomes indistinguishable; pure identity colours lose the terrain character.

3. **Terrain height**: The spline evaluator (overworld) or heuristic function (other families) computes a height at each sample point.

4. **Hillshade**: Finite-difference gradients from the height grid produce a directional light effect (NW illumination). The shade coefficient varies per family to account for different height ranges.

5. **Water depth**: Ocean and river biomes get a depth gradient based on continentalness — deeper water (lower continentalness) renders darker.

6. **Vegetation density**: Dense-canopy biomes (dark forest, jungle, old-growth) are darkened towards a canopy green, making forest coverage visible from above.

7. **End void**: End-family renders show near-black pixels where `height < 1.0` (void between islands).

8. **Weirdness micro-texture**: A subtle brightness variation from the weirdness parameter adds visual noise that breaks up flat-colour regions.

9. **PNG output**: Written with a dependency-free PNG encoder (zlib + struct).

### Why not unmined-cli?

unmined-cli requires full block state data in .mca format — there is no biome-only rendering mode. Generating synthetic .mca files with surface blocks is possible but adds significant complexity (NBT serialisation, block state packing) for marginal quality improvement over the enhanced Python renderer. The Python approach renders 713 candidates in ~14 minutes with 18 workers; unmined-cli at ~2 min/render with server crashes after ~15 renders would take hours.

## Per-Family Rendering Details

| Family | Noise params | Height function | Hillshade `k` | Special handling |
| --- | --- | --- | --- | --- |
| Overworld | 5 params (T, H, C, E, W) with full octave configs | Terralith offset spline | 0.12 | Water depth gradient from continentalness |
| Nether | Nether-specific octave configs | `64 + ero×25 + rf×15` | 0.15 | All surface types map to netherrack/nylium/soul_sand/basalt |
| End | End-specific octave configs | Void below cont < −0.1, then island formula | 0.18 | Void pixels rendered as near-black (8, 5, 15) |
| Paradise Lost | Paradise Lost octave configs | `80 + ero×25 + rf×20` | 0.15 | Grass surface types with elevated terrain |

## Adding a New Dimension

1. **Create the dimension config** at `config/custom-dimensions/dimensions/<slug>.json` with `type`, `biomes`, `seedRoll` (mood, spawnFilter, wants, shuns), and optionally `noiseSettings`, `structureDensity`, `difficulty`.

2. **Check the biome parameter table**: ensure every biome listed in the config exists in `biome_params.json` with the correct family tag. If the dimension uses biomes from a new mod, re-run warmup (`./dev seed-roll-all` with Docker) to regenerate the params.

3. **Check noise configs**: if the dimension's family isn't covered in `noise_configs.json`, add the family's noise parameters (extract from the mod JAR's `worldgen/noise_settings/` data).

4. **Add surface rules**: if the dimension uses biomes not in `surface_rules.py`'s `_BIOME_SURFACE` table, add explicit mappings. The keyword fallback handles most cases, but explicit entries are more reliable.

5. **Add biome colours**: add entries to `BIOME_COLOURS` in `biome_renderer.py` for any new biomes. The keyword fallback produces reasonable defaults but hand-tuned colours are better.

6. **Add a height function** (if needed): if the dimension family doesn't match overworld/nether/end/paradise_lost, add a branch in `biome_renderer.py`'s render loop (the `elif family == "..."` chain starting around line 225).

7. **Test**: run `./dev seed-roll-all --dims <slug>` and check the renders in `.seedtest/renders/<slug>/`.

## Troubleshooting

### Missing candidates

- **Check spawn filter**: if the dimension's `seedRoll.spawnFilter` lists biomes that don't appear in `biome_params.json` for that family, every candidate gets rejected. The `namesake_in_sampler` check in `fast_roller.py` relaxes this for multi_biome dimensions, but single-family dimensions with missing biomes will produce zero candidates.
- **Check `rollable()`**: superflat dimensions are never rolled; void dimensions require a biome list to be rollable.
- **Check family coverage in biome_params.json**: if the warmup didn't capture a family (e.g. nether count < 5), re-run with Docker.

### Flat renders (no terrain variation)

- **Check the height function**: does the dimension's family have a branch in `biome_renderer.py`? Unknown families fall through to the generic formula.
- **Check terrain_splines.json**: if missing, overworld-family renders use the fallback formula instead of the Terralith spline (much less relief).
- **Check hillshade coefficient**: the `shade_k` parameter controls hillshade strength — nether/end use higher values (0.15/0.18) because their height ranges are more compressed.

### Wrong colours

- **Check surface_rules.py**: is the biome mapped to the correct surface type? The keyword fallback can misclassify biomes with unusual names.
- **Check biome_params.json**: does it include the biome with the correct family tag? A biome tagged as "overworld" but used in a nether dimension won't appear in the sampler.

### All green (everything looks like grass)

- **biome_params.json family tags**: if family tags are missing, the sampler falls back to all entries regardless of dimension type, producing overworld biomes everywhere.
- **Noise config mismatch**: if the dimension uses nether biomes but overworld noise, the climate values won't match the nether biome ranges — the sampler returns the closest overworld biome instead.

### Warmup failures

- Docker Desktop must be running.
- The local server must have booted at least once (`./dev up`) to populate `data/mods/`.
- The MC server needs ~90s to boot with 129 mods — if the timeout is too short, the biome param dump fails silently.

## Research Notes

Detailed R&D reports from the spike phase live in `spike/`:

| File | Contents |
| --- | --- |
| `00-spike-plan.md` | Synthesised plan with approach evaluation |
| `01-unmined-cli-research.md` | unmined-cli capabilities and limitations |
| `02-cubiomes-and-mca-research.md` | cubiomes internals and Python sampler analysis |
| `03-mca-format-spec.md` | .mca binary format and NBT structure |
| `04-improved-renderer-research.md` | Surface blocks, colours, water rendering |
| `05-terrain-height-research.md` | Density functions, spline system, Terralith mods |
