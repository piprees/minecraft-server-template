# R1 — Where SeedGen's speed comes from, and a whole-world renderer for our 82 dimensions

Analysis of the published jars (DH SeedGen 0.9.1 for 1.21.1 and 26.3, Voxy SeedGen 0.3.1 for 26.2; decompiled outside the repository, nothing quoted), their Modrinth pages and changelogs, the open-source seedlod (MIT, © 2026 Derped, github.com/Derpedyea/seedlod), and our own `mods/custom-dimensions-26` with the w10c1p/w10c1r logs. [I] marks an inference; everything else cites where it was seen or a measurement. Class names are cited only to say where something was seen.

## Answer
SeedGen never generates a world. It asks the noise router only what a distant viewer sees (surface height, the biome there, the top block), once per LOD pixel rather than per block, and writes the answer straight into Distant Horizons' or Voxy's storage. The dominant gain is sampling at the resolution the viewer draws in rings that double outwards, with extra coarse levels added (DH to detail 18; Voxy levels 5–15), so a whole world costs columns in proportion to the log of its radius, not its area. Each column is then cheap: a surface search from the router's preliminary-surface estimate, a cave-free density, shared corner and y-independent caches, octave-progressive base noise (Voxy SeedGen), no chunk system, aquifers, carvers, features or light engine, and trees and structures stamped from templates. All of it is public API on 26.3.

## 1. Architecture
- **DH SeedGen.** Binds on every ServerLevel (dedicated servers included), standing down for: a ceiling other than `minecraft:the_nether`; space-folding mods; a non-`NoiseBasedChunkGenerator`; known custom biome sources; a generator subclass that disagrees with a plain one at 12 test points (24 blocks' tolerance). Otherwise it registers a DH world-generator override, and DH's server generation sends the LODs. Requests are 64×64 sections at stride 2^detail on DH's thread pool, per-thread samplers. Three models chosen **by dimension key**: Nether, End, everything else. Quality levels: preliminary surface only, cave-free density, full density. Real biome resolver; real clay bands. Writes a few vertical runs with sky light computed directly. Surface: the world's material rule re-compiled per biome into a small interpreter; vanilla rules via hand tables. Trees: baked templates or a biome's own features grown once in memory, scattered by a positional hash. Seven mixins extend DH to detail 18. GPU used only for client meshing (a compute shader), never for generation.
- **Voxy SeedGen.** 128-block tiles nearest first; finest level from Voxy's distance rule; coarse levels written straight into Voxy's mip sections; rings out to the world border. Optional real chunks close in (caves). A density compiler (§2.6).
- **Vulkanite.** Absent from all three jars; changelogs mention only "Vulkan API compatibility" (0.7.0) and "Vulkan API and Shader related fixes" (0.9.0). Unknown until the video's transcript is read.
- **seedlod (MIT).** Batch sampling of the full final density with a scout pass every 16 blocks to find the surface band, vanilla's compiled material rule over the top 9 blocks, biomes cached per quart, real trees near the viewer, a benchmark that times tiles and writes a shaded top-down PNG. MIT lets us copy, change and ship it with the notice kept.

## 2. Speed sources, ranked
1. **Display-resolution sampling in geometric rings** — O(log R) columns per world [I]. Published: 13.2 M chunks in 40 s on 28 threads, which is at most ~85 µs per column per thread [I].
2. **No chunk system** — no proto-chunks, status pipeline, heightmaps, light, features, carvers or saving. Published: 10.7× DH's generator (overworld), 2.3× (Nether), 5.6× DH 3.3's Hyperspeed.
3. **Surface search instead of filling the column** — start at the preliminary surface + 16, step down a cell (8 blocks), binary-search the crossing (~3 evaluations), check up to 4 overhangs, full scan as fallback. Ours fills all 384 blocks plus the aquifer per column (`getBaseColumn`).
4. **Cave-free density** — keeps the side of the final density's top-level minimum that excludes vanilla's noodle caves. Cost: no cave mouths; water flat at sea level.
5. **Caches** — interpolation corners shared by a cell's columns; per-column caches for 2D nodes; climate per cell; biomes per quart.
6. **Density compiler (Voxy SeedGen)** — y-independent nodes cached per column, shared subtrees memoised, blending constant, base noise evaluated octave by octave with an interval bound, min/max short-circuited on known ranges, a bit-exact self-check at 128 columns. Gain unpublished.
7. **Threads** — per-thread samplers; no SIMD; DH SeedGen samples point by point, seedlod in batches.
8. **Templates instead of feature placement.**

## 3. Faked or skipped
| Item | How |
| --- | --- |
| Caves | Solid |
| Fluids | Flat water at sea level; Nether lava below 32. Our dry and flooded aquifers and lava levels would be missed [I] |
| Surface | Real material rule; depth grows with stride |
| Snow, icebergs, kelp | Temperature, sites or hashes |
| Trees | Templates, only at stride ≤ 4 |
| Vanilla structures | Spacing and salt hard-coded, the random-spread maths re-done, 41 baked templates; near detail only |
| Mod structures | The level's real structure state and pieces |
| Ores, carvers, block entities, light | Skipped |

## 4. Ceilinged dimensions
Only the Nether works, through its own model keyed to its id (bottom-up march in cell steps, up to 19 runs, Nether palettes, lava below 32, fortress and bastion templates); every other ceilinged dimension is skipped. A proper render needs multi-run columns (about 48 + 3 × crossings evaluations for a 384-block world [I]), the material rule on each run's top and bottom (`stone_depth` with `ceiling` covers roofs), roof-off, cut-at-Y and per-run views, and fluids from the level's own picker at run boundaries.

## 5. Fit with our generators (if SeedGen itself were installed)
- Server side it reads our compiled router: RuntimeRegistry binds at SERVER_STARTING, before any level. On a client, our binding is absent and it would draw vanilla forms or stand down [I].
- Branch by key: all 78 custom dimensions take the single-surface overworld path — sky islands drawn as pillars to the world's floor and lower tiers lost [I: read, not run]; custom End-preset dimensions miss the End model; roofed and caves presets skipped; void dimensions correct [I].
- Zoned biome source fine. Structure control: honoured on the mod-structure path [I], ignored on its vanilla path (our spacing, rejects and forced starts on `minecraft:*` sets). Runtime fluids ignored.
Verdict: right for overworld-like dimensions, wrong for sky, roofed and End ones; our own renderer avoids all of it.

## 6. Design for us
### Today, measured (server logs, w10c1p/w10c1r)
| Tool | Work | Time | Rate |
| --- | --- | --- | --- |
| `/customdim render` (Sampler.sample → getBaseColumn, 2 threads) | overworld 512² | 42–72 s | 3.6–6.2 k col/s |
| | Nether 512² | 4.4–7.4 s | 35–60 k col/s |
| | sky worlds 512² | 15–38 s | 7–17 k col/s |
| `/customdim survey` (proto-chunk patches) | 67–73 k columns | 32–43 s | ~2 k col/s |
| Rig client | 27 frames | 1,601 s | ~1 min per frame |
Our render already runs off-thread without chunks and uses our biome source, zone masks and structure starts (`Sampler.starts` honours our structure control and zone shuns). The gap is per-column cost and threads.

### A renderer of our own (`server/render/fast/`, beside `Sampler`)
- **Core:** per-thread tile sampler from the level's random state, settings and biome source (after RuntimeRegistry), built on seedlod's tile generator with its MIT notice; batch sampling of the **full** final density; a scout pass on cell corners to find occupied bands; **multi-run columns** (cap ~24) so one model covers roofed, cave, tiered-sky, void and End worlds with no branching by key; stride 1–16; a rewrite that caches y-independent nodes per column (our types can declare themselves 2D); optionally octave-progressive sign decisions behind a bit-exact check.
- **Surface:** the level's compiled material rule at each run's top and bottom; biome per quart.
- **Fluids:** the level's own fluid picker at each open run's lowest block.
- **Structures:** `Sampler.starts`; footprints from start bounding boxes first, templates later.
- **Outputs:** border-to-border maps (~2048 px: hillshade, map colour, biome, landform and slope, fluids, structures; roof-off, cut-at-Y and openness for roofed worlds; one image per tier for sky worlds); oblique and far views by a CPU ray-march over the run-list heightfield with the dimension's sky and fog colours, no client (this removes the rig's small-world far-view failure); per-dimension contact sheets with a biome legend and the survey's numbers; one command for all 82.
- **Expected [I]:** 5–10× per column (~85 µs against ~400 µs per thread), 2 → 4 threads here (8 on the production host): overworld 512² in ~2–6 s against 42–72 s; minutes per dimension become seconds, with no client and no chunk written.

### Prototype and its measurement
`/customdim fastrender <dim> [stride] [threads]` (existing PNG and JSON plus a run-list dump) on the_aviary, one roofed dimension and overworld at the logged seeds. Measure: (1) columns/s against `/customdim render` on the same grid, target ≥ 5×; (2) height and biome against `Sampler.sample` (`Mismatch`) and top block against the survey's proto-chunk patches, target ≥ 98% of heights within ±1 block away from features; (3) run-lists against real chunks in 3 patches per dimension. Pass: each rendered border to border at stride 4 in under 10 s.

### Distant Horizons LODs on the server
The same core can register a DH world-generator override on level load and answer each request with a 64×64 tile at stride 2^detail; DH's server generation sends the LODs to players, so no client mod is needed. Needs DH 3.3+ (26.3 pins 3.3.4; 1.21.1's 3.2.0-b has no N-sized requests [I]); stay within DH's maximum detail of 8; the cave-free path is acceptable for LODs, maps use the full density.

## Open questions
1. Vulkanite (needs the transcript or the creator's description).
2. Crossings per column on our sky terrains (sets their cost).
3. Whether the material rule on run tops matches real chunks for `above_preliminary_surface`, `ore_vein` and `steep` rules (prototype measurement 2).
4. Whether the aquifer can be queried cheaply at isolated points on 26.3.
5. Whether our own density types implement 26.3's batch sampling efficiently (affects today's render too).
6. The octave-refinement gain (measure our own).
