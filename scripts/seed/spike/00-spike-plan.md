# Seed Render Pipeline — Spike Plan

> Synthesised from 5 research reports (01–05 in this directory).
> Target: render 713 dimension candidates in <30 minutes on an 18-core Mac with quality significantly better than flat biome maps.

## The Problem

Two existing render implementations, both inadequate:

| | unmined-cli (via MC server) | biome_renderer.py |
|---|---|---|
| Quality | Excellent — real block textures, shadows, vegetation | Poor — flat colour blobs with hillshade |
| Speed | ~2 min/render, crashes after ~15 | ~1s/render, 713 in 14 min |
| Dependencies | Docker + MC server + forceload + save | None (pure Python) |

## What We Learned

### 1. unmined-cli requires full block state data

There is **no biome-only rendering mode**. The cubiomes seed overlay exists only in the Windows GUI, not the CLI. Every pixel's colour comes from `block_state → tag → colour` mapping. A synthetic .mca with only biomes + heightmaps produces a blank image.

**Implication:** To use unmined-cli, synthetic .mca files must contain actual block states — at minimum, the surface block (grass_block, sand, stone, etc.) for each column.

### 2. We already have modded cubiomes

`biome_sampler.py` reimplements the exact same algorithm as cubiomes (Xoroshiro128++ PRNG, DoublePerlinNoiseSampler, nearest-neighbour biome lookup) but against the **modded parameter table** extracted from the server. It handles all 4 dimension families (overworld, nether, end, paradise_lost) with per-family noise configs including Incendium, Nullscape, and Paradise Lost parameters.

Porting to C would give ~100× speedup but the Python version already does 713 renders in 14 minutes. **Speed is not the bottleneck.**

### 3. Surface height is computable from climate parameters

The surface Y can be approximated: `surface_Y ≈ 128 * (offset + 0.5)` where `offset` is a nested cubic Hermite spline of `(continentalness, erosion, ridges_folded)`. Accuracy: ±5-15 blocks — more than sufficient for hillshade.

**Critical caveat:** Terralith completely replaces the offset/factor/jaggedness splines. We must extract and evaluate **Terralith's spline data**, not vanilla's. The spline JSON is ~2000 lines of nested control points, fully extractable from the Terralith JAR.

### 4. Even Chunkbase and AMIDST use flat biome colours

Our biome_renderer.py with hillshade already surpasses both. Applying surface-block colours + water depth shading + vegetation density would produce renders better than any existing seed-evaluation tool.

### 5. No alternative renderer avoids block data

BlueMap (already in our stack), Overviewer (dead), Mapcrafter (dead for modern MC) — all need full block states. The "find a renderer that works with less data" path is a dead end.

## Approaches Evaluated

### A. Enhanced Python Renderer (RECOMMENDED — Phase 1)

**What:** Improve biome_renderer.py with surface-block colouring, computed terrain heights, water depth shading, and vegetation density.

**Why it wins:**
- Zero new dependencies
- Already fast (14 min for 713 with 18 workers)
- Quality improvements are incremental and testable
- Better than Chunkbase/AMIDST after improvements 1-3 below

**Improvements in priority order:**

| # | Improvement | Impact | Effort | Description |
|---|---|---|---|---|
| 1 | Surface-block colours | HIGH | LOW | Replace flat biome colours with MC map colours of the surface block. Desert = sandy yellow, mountains = grey stone. |
| 2 | Spline-based terrain height | HIGH | MEDIUM | Parse Terralith's offset.json, implement cubic Hermite interp, compute real surface Y. Replaces crude continentalness/erosion hillshade. |
| 3 | Water depth shading | HIGH | LOW | Continentalness < -0.19 → water with depth gradient. Bathymetric effect. |
| 4 | Vegetation density | MEDIUM | LOW | Per-biome darkness multiplier. Dark forest = 0.65, plains = 1.0. |
| 5 | River thinning | MEDIUM | LOW | Sample river biomes at higher resolution or use PV < -0.85. |
| 6 | Grass/foliage tinting | MEDIUM | MEDIUM | Temperature+downfall-based tint on grass-surfaced biomes. |

**Estimated timeline:** 1-2 days for improvements 1-4. Improvements 1+2+3 alone transform the visual quality.

### B. Synthetic .mca + unmined-cli (Phase 2, Optional)

**What:** Generate .mca files with surface blocks + biomes + heightmaps, render with unmined-cli natively.

**How:**
1. For each candidate: sample biome grid (existing sampler), compute surface height (spline), determine surface block (biome lookup)
2. Write .mca files with:
   - 24 sections per chunk, most all-air with biome palette
   - Surface section has block_states palette: {stone below, surface_block on top, air above}
   - Heightmaps (MOTION_BLOCKING, WORLD_SURFACE) from computed heights
   - Status: `minecraft:full`
3. unmined-cli renders in ~1s per file natively

**Advantages over Phase 1:**
- unmined's block→colour pipeline is battle-tested and handles edge cases
- Proper shadow/lighting from unmined's stylesheet
- Modded block texture support via `--java-client-jar`

**Disadvantages:**
- Significantly more code (NBT serialisation, .mca writer, block state packing)
- New dependency (amulet-nbt or hand-rolled NBT)
- Surface blocks are an approximation anyway — no vegetation placement, no features
- The result would look like "Minecraft terrain rendered at the lowest detail level" rather than a proper render

**Estimated timeline:** 3-5 days. Only worth it if Phase 1 results aren't good enough.

### C. Parallel MC Servers (NOT RECOMMENDED)

**What:** Run 4-6 Docker containers simultaneously, each processing batches of candidates.

**Why not:**
- Still ~30s per candidate per container = 713 / 6 × 30s = ~60 min minimum
- Memory pressure: 10GB Docker limit means frequent restarts
- Complexity: container lifecycle management, VirtioFS lag, RCON health monitoring
- The existing seed_worker.py already handles this and it already crashes after ~15 renders
- Could potentially get to 30 min with 12 containers but hardware-limited

**Only consider** as a fallback if both A and B produce unsatisfactory results.

### D. Direct Java Worldgen (NOT RECOMMENDED)

**What:** Write a small Java program using MC's own NoiseChunkGenerator to generate chunks.

**Why not:**
- Requires setting up the full mod stack (129 JARs) in a headless Java environment
- Effectively building a headless MC server, which is what the Docker approach already does
- Complex classloading, mod initialisation, registry bootstrapping
- The accuracy gain over the spline approach (B) is marginal for top-down rendering

## Recommended Plan

### Phase 1: Enhanced Python Renderer (THIS SPRINT)

**Goal:** 713 renders in <15 min, quality recognisably terrain-like.

1. **Extract Terralith's spline data** from the JAR (`data/minecraft/worldgen/density_function/overworld/offset.json`)
   - Parse nested cubic Hermite spline tree into Python data structure
   - Store as `scripts/seed/terrain_splines.json` (cached, like `noise_configs.json`)

2. **Implement spline evaluation** in `biome_sampler.py` or a new `terrain_height.py`
   - Cubic Hermite interpolation
   - Nested spline evaluation: continentalness → erosion → ridges_folded → scalar
   - `ridges_folded = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)`

3. **Upgrade biome_renderer.py** with:
   - Surface-block colour table (biome → MC map colour)
   - Spline-based surface height for hillshade
   - Water depth gradient from continentalness
   - Vegetation density multiplier per biome
   - Higher-resolution river sampling

4. **Test** against the existing 713 candidates; visually compare old vs new renders.

### Phase 2: Synthetic .mca (IF NEEDED)

Only if Phase 1 renders aren't good enough for seed evaluation. The .mca writer would reuse the spline-based height computation from Phase 1.

### Phase 3: Regenerate biome_params.json (BLOCKING for visual quality)

The current `biome_params.json` only contains 10 Paradise Lost biomes — no overworld, nether, or end biomes at all. This means the renderer produces the same green/grey palette for every dimension regardless of type.

**Fix:** Re-run the warmup step to regenerate biome_params.json with all 4 dimension families:
```bash
./dev seed-roll --warmup-only
```

This requires Docker Desktop and the full mod stack. See `spike/08-biome-params-data-gap.md` for full details.

**No code changes needed** — the renderer, surface_rules, and terrain_height modules are complete and tested. Only the input data needs regeneration.

## Implementation Status

| Component | Status | Notes |
|---|---|---|
| `terrain_height.py` | DONE | Spline evaluator, 2µs/eval, ±5-15 block accuracy |
| `terrain_splines.json` | DONE | 11KB, extracted from Terralith JAR |
| `surface_rules.py` | DONE | 123 biomes mapped, MC map colours, vegetation density |
| `biome_renderer.py` upgrade | DONE | Blended colours, spline heights, water depth, vegetation |
| `biome_sampler.py` optimisation | DONE | 2.4× faster nearest-neighbour with early-exit pruning |
| `biome_params.json` | DONE | Recovered from stack bundle (1803 entries, 177 biomes, 4 families) |
| Visual verification | DONE | Overworld + nether renders tested with full biome data |
| Per-family terrain heights | DONE | Nether, end, paradise_lost height functions with ridges_folded |
| BiomeSampler filter fix | DONE | biome_filter overrides family filter for multi_biome dims |
| fast_roller spawn gate fix | DONE | Lenient gate when namesake biomes aren't in sampler |
| 5 missing dimension candidates | DONE | 27 candidates generated, 13 renders produced |

## Files to Create/Modify

| File | Action | Purpose |
|---|---|---|
| `scripts/seed/terrain_splines.json` | Create | Parsed spline data from Terralith's offset.json |
| `scripts/seed/terrain_height.py` | Create | Spline evaluator + surface height computation |
| `scripts/seed/biome_renderer.py` | Modify | Surface-block colours, computed heights, water depth, vegetation |
| `scripts/seed/surface_rules.py` | Create | Biome → surface block → colour mapping |

## Research Reports

| File | Contents |
|---|---|
| `spike/01-unmined-cli-research.md` | unmined-cli capabilities, what it reads, alternative renderers |
| `spike/02-cubiomes-and-mca-research.md` | cubiomes internals, mod support, existing Python sampler analysis |
| `spike/03-mca-format-spec.md` | .mca binary format, NBT structure, packing rules |
| `spike/04-improved-renderer-research.md` | Surface blocks, colours, vegetation density, water rendering |
| `spike/05-terrain-height-research.md` | Density functions, spline system, Terralith modifications |

### Phase 6: Integration & E2E Tests (CI-verified)

**Goal:** Test the full pipeline (sampling → height → surface rules → render → batch) in CI, covering every dimension family and edge case, without Docker.

**Tests to add** (`scripts/seed/test_biome_pipeline.py`):

1. **BiomeSampler filter behaviour**
   - biome_filter overrides family filter (the multi_biome fix)
   - Family-only filtering works (overworld, nether, end, paradise_lost)
   - Empty biome_filter → all entries for the family
   - Sampler initialises with each noise config family

2. **TerrainEvaluator**
   - Spline loads from terrain_splines.json
   - Height at sea level range (continentalness ~0 → Y ~63)
   - Height at ocean (continentalness < -0.455 → Y < 55)
   - Height at mountain (continentalness > 0.5 → Y > 140)
   - ridges_folded computation matches expected values

3. **Surface rules**
   - Every biome in BIOME_COLOURS maps to a valid surface type
   - Grass biomes get tinted (R, G, B differ from base)
   - Water biomes map to water surface types
   - Nether biomes map to nether surface types
   - Vegetation density in expected ranges

4. **Renderer integration**
   - Single render produces valid PNG (header check, size > 1KB)
   - Per-family renders produce non-identical output (different colour distributions)
   - End family produces void pixels (near-black) where expected
   - Hillshade produces non-uniform brightness (not flat)

5. **Fast roller spawn filter**
   - multi_biome dim with nether biomes → candidates accepted (not 100% rejected)
   - Overworld dim → normal spawn filter works
   - End/paradise_lost dims → candidates generated

6. **Snapshot tests** (golden-file comparison)
   - Render specific seed at specific coordinates → compare against saved reference PNG
   - One per family: overworld, nether, end, paradise_lost
   - Tolerance: pixel-level exact (deterministic noise → deterministic output)

### Phase 7: Documentation & Code Organisation

**Goal:** Document the R&D, annotate the code so future agents understand the pipeline, and ensure everything is referenced from the mod documentation.

**Tasks:**

1. **Code documentation** — add module-level docstrings and key function docstrings to:
   - `terrain_height.py` — explain the spline evaluation algorithm, Terralith extraction
   - `surface_rules.py` — explain the colour system, MC map colours, vegetation density
   - `biome_renderer.py` — explain the blended-colour approach, per-family height functions
   - `biome_sampler.py` — document the early-exit optimisation, filter precedence

2. **Seed-rolling documentation** — create/update `scripts/seed/README.md`:
   - Architecture overview (warmup → fast_roller → biome_renderer → score → viewer)
   - Per-module purpose and data flow
   - How biome_params.json is generated (warmup phase, mod JARs, family tags)
   - How terrain_splines.json is extracted (Terralith JAR, offset.json)
   - Per-family rendering differences and why
   - How to add a new dimension type
   - Troubleshooting (missing candidates, flat renders, family mismatches)

3. **Link from mod docs** — update `mods/AGENTS.md` and `mods/custom-dimensions/README.md` to reference the seed-rolling pipeline

4. **Spike summary** — final `spike/12-final-summary.md` with lessons learned

## Success Criteria

- 713 renders in <30 minutes (ideally <15 with 18 workers)
- Every image shows recognisable terrain — not flat colours, not void
- Rivers visible as distinct features
- Mountains/hills distinguishable from plains
- Ocean depth visible
- Biome boundaries clear with surface-appropriate colours
- Works for all 4 dimension families (overworld, nether, end, paradise_lost)
