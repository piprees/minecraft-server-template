## F4 report: the Distant Horizons LOD generator on the fast-render core

**Progress line:** The generator is built, wired, tested headless and committed in 4 commits. It works without DH installed (DH is compileOnly and the integration is guarded). I added the lead's roofed/sky shortcut, measured it and changed it where the measurement showed it was worse. The one in-boot proof (pregen, then LOD columns compared against real chunks) is in a lane script for you to run. I have not booted a server.

### DH API facts
- **Version:** DH 3.3.4-26.3 (`distanthorizons:TGgEbP9A`, file `DistantHorizons-3.3.4-26.3-fabric-neoforge.jar`).
  - The sha512 prefix `0b90b1b0…` matches Modrinth. The mrpack holds only the version id, no hash.
  - Licence: LGPL-3. I read the source at the core commit `65cbb1ac` and the common commit `01b9370b`.
- **Registration:** `DhApi.worldGenOverrides.registerWorldGeneratorOverride(IDhApiLevelWrapper, IDhApiWorldGenerator)`.
  - It must happen inside a `DhApiLevelLoadEvent` handler (`DhApi.events.bind`). DH creates its queue lazily on its tick, so binding at load is early enough.
  - Binding again at the same priority replaces the earlier generator.
- **Request shape:**
  - `getReturnType()=API_DATA_SOURCES` leads to `generateLod(chunkMinX, chunkMinZ, lodX, lodZ, detail, IDhApiFullDataSource, mode, pool, consumer)`.
  - Each call is a data source of 64 × 64 columns, one column every `2^detail` blocks. Detail runs 0 to `getLargestDataDetailLevel()`; I return 12, as DH's own generator does.
  - It runs on DH's world-gen pool, which has `numberOfThreads` threads (2 in the dev config).
  - `/dh pregen` always asks for detail 0 (`PregenManager`).
- **What a column can hold:** `setApiDataPointColumn(relX, relZ, step, List<DhApiTerrainDataPoint>)`.
  - Each data point has detail 0, a top-exclusive [bottom, top) range relative to the level's min Y, a block wrapper, a biome wrapper, and block and sky light.
  - Points must be contiguous from top to bottom with no gaps (air must be explicit). Several solid runs per column are allowed.
  - The light stored is that of the block above the point.
  - I mark columns `LIGHT`, so DH treats them as finished and does not ask again.
- **Chunk ingestion:** DH ingests real chunks on save (`MixinChunkMapCommon` → `serverChunkSaveEvent`). So inside the Chunky radius the LODs come from real chunks; ours only fill the sections that are missing.

### Mapping
Runs (`ColumnRuns`), dressed by `RunDress`, become segments topmost first (`LodColumn.segments`, `LodColumn.java:41`):
- air over each run, then the run's fluid up to its level;
- the run's top block (from the material rule), 1 block thick;
- the body in the settings' default block;
- the underside block (always dressed).

Light:
- Sky light is 15 over the topmost run, and in an open band of 16 or more blocks under a run of an unroofed column (sky tiers, the void).
- It is 0 in a shorter band (a cave), under a roof, and in a level without sky light.
- Fluid takes 1 off per block. Block light is the emission of the block above (lava gives 15).

Biome: each run's biome is the noise biome at its top, and its open space and fluid take the same biome.

### What I built (all in `mods/custom-dimensions-26/server/src/main/java/com/customdimensions/server/`)
- **`render/fast/LodSource.java`** (additive to the core, so it merges cleanly with F3)
  - Constructor at :101, `columns()` :155, `segments()` :203, `coarse()` :210, `prescan()` :252, `compare()` :341.
  - Per-thread `TileSampler`s built from the level's own `RandomState`, settings, biome source and fluid picker.
  - Samples at the request's spacing in 16 × 16 tiles.
- **`render/fast/LodColumn.java`:** the game-free mapping.
- **`render/fast/ColumnRuns.java`:** additive `withFloor`, `clip`, `within`, `dropTop`, `sameAs`.
- **`render/fast/LodBench.java`**, plus a hook in `FastBench`: `runFastBench -PfastBenchLod=<details>`.
- **`lod/DistantHorizonsLods.java`:** the guard. It touches no DH class and uses `isModLoaded`.
- **`lod/DhLodGenerators.java`:**
  - `register` :37 and `bind` :57 bind every server level DH loads, without naming any dimension.
  - `reach` :74 is read lazily, because base worlds load before `LevelManager` exists.
- **`lod/DhLodGenerator.java`:** `generateLod` :88 and `fill` :106.
- **`lod/DhLodSelfTest.java`:** runs with `-Pdh -PselfTestOnly=lod [-PselfTestLod=…]`. Without DH it writes no lines, so the self-test baseline is unchanged.
- **`level/LevelMaths.java:65` `lodReach`:** max(player, generation) border, each falling back to the defaults.
- **Build:**
  - `server/build.gradle` adds `compileOnly` DH (not nested; I checked the jar) and `-Dcustomdimensions.dh.validate=true` under `-Pdh`.
  - `gradle.properties` comment updated.
- **README:** new section "Distant Horizons LODs", which states that structures, trees, other features, carvers' caves and aquifer water tables are not drawn.
- **`cutover/files/scripts/idle-tasks.sh`** (the 26.3 copy; the 1.21.1 `scripts/idle-tasks.sh` is unchanged):
  - `lod_reach_for` (:263) gives the DH stage max(player, generation) with defaults; :369 turns that into the chunk radius.
  - Chunky stays on `borders.generation`. The header is updated.
  - ShellCheck is clean, and a fixture test gives the same results as `lodReach`.
  - The repo file's base hash is unchanged, so no mkshas re-record was needed. I did not run a clean-clone `apply.sh`.

### Coarse shortcut (lead's addition), measured headless
Seed 20261022, 16,384 columns per level per detail level 2–8 (`handoff/evidence/f4/lodbench.txt`):
- **Overworld (surface window -64..178):** heights and runs match whole columns 100%; about 1.4× cheaper.
- **The End (tiers window 0..83):** heights 100%, runs kept 100%; about 2.3× cheaper.
- **The Aviary (tiers window 0..255):** heights 99.87–100%, runs kept 100%; about 2.8× cheaper.
- **Roofed levels (Nether, `the_underdark`):** the fixed start does not pay.
  - The best fixed height is block 101, and it is open in only 8,523 of 15,520 floored pre-scan columns (55%).
  - Cut there and clipped, heights match about 55–63%. Made exact by rescanning, it cost 1.7× whole columns.
  - So the code now uses a fixed start only when that height is open in at least 90% of floored columns (`ROOF_START_SHARE`). Otherwise it scans whole columns and drops the roof, which gives 100% heights at whole-column cost.
- **Metric correction:** my first metric compared against min(floor, H+1). That hid misses and scored the useless H=35 cut at 67%. It now compares against the true floor under the roof.
- **Detail levels 0–1:** keep every run, roof included.
- **DH-shape check:** 0 malformed columns across all request levels (no gaps, no empty segments, every column down to the floor).

### Tests
- After: core 719, compiler 559, server 174 = 1,452 tests, 0 failed.
- I added 14: `LodColumnTest` (13: single surface, fluid over ground, lava light, roof, three tiers over a void, cave gap, no-skylight level, empty column, one-block run, `clip`/`within`/`withFloor`, the check) and a `lodReach` test.
- Before: 1,438, derived as 1,452 − 14. I stopped my baseline run because it would have compiled my new files.
- Full `./gradlew --max-workers=1 --offline build` is green. The quick gate exits 0.

### Lane script (server lane only)
- **Script:** `handoff/evidence/f4/lane-f4.sh [mod dir] [out dir]`
  - Run it from the main checkout once F4's commits are merged, or pass the main checkout's `mods/custom-dimensions-26`.
- **What it does:**
  - Deletes only `DistantHorizons*.sqlite*` under `server/run/world`, so pregen asks the generator for every section.
  - Runs `timeout 3600 ./gradlew --offline --max-workers=3 :server:runServer -Pdh -PselfTest=<out>/st-lod.txt -PselfTestOnly=lod` in the foreground.
  - Copies `lod-check-<slug>.tsv` and prints a snapshot.
- **What to read:**
  - the `lod` lines in `st-lod.txt`;
  - the log lines "LOD generator bound to", "come from customdimensions" and "LOD generation for every";
  - the DH error count.
- **Pass bars:**
  - no FAIL line, and at least 12 `lod` lines;
  - generator bound for overworld, the_nether and the_aviary;
  - `lod pregen <slug>` served more than 0 full-detail requests with 0 failed;
  - `lod vs chunks <slug>` has 0 columns without data, heights within 1 ≥90%, block under the surface ≥60%, biome ≥90% (trees are excluded from the real surface);
  - `lod shortcut` lines PASS (≥95% heights, ≥90% runs kept);
  - 0 log lines matching "Error generating data", "World generator disabled", "has a gap" or "is upside down".
- **Expected time:** roughly 2–5 minutes per dimension, at 2 DH threads and about 4,096 columns in 0.34 s per request headless.
- **Caveats:**
  - The main run dir's `level-seed` is currently 4705305050088502262. The script only warns about that, because the LOD section does not depend on the seed.
  - The self-test's DH calls are written against the API source but have not run in a boot.

### What DH shows that our data cannot, and what would add it
- **Missing from our data:** structures, trees and other features, carver caves, aquifer local water, and snow or other surface decoration placed after the noise stage.
- **What would add it later:**
  - trees: stamping per-biome tree templates from the placed-feature config at sampled positions;
  - structures: `StructureStarts` bounding boxes plus their templates, with `Sampler.starts` already giving the starts;
  - carvers: sampling the carver noise.
- Inside the Chunky radius, LODs built from real chunks already include all of this.

### Commits
On worktree branch `worktree-agent-a668b7cfa8ba0e114` (worktree `/home/user/minecraft-server-template/.claude/worktrees/agent-a668b7cfa8ba0e114`), fast-forwarded from `327418f4`, not pushed:
- `0a16d800` feat(cd26): Distant Horizons LOD generator from the fast-render core
- `a7d16bef` feat(cutover): DH pre-generates to the larger of the player and generation borders
- `99c163f7` docs(cd26): README section, lane script, LOD bench evidence
- `af040528` fix(cd26): an LOD column whose roof the shortcut left out stays dark under it

The log is at `/tmp/claude-0/f4/progress.log`.

### Trap drafts (T-F4)
- **T-F4a — LODs marked `LIGHT` are final to DH.**
  - Symptom: after a worldgen or definition change, distant terrain still shows the old shape.
  - Cause: DH never asks again for a section it holds as `LIGHT`.
  - Fix: delete the level's `DistantHorizons*.sqlite*` together with the world wipe (D2), and remove the `.dhpregen-<id>-complete` marker.
- **T-F4b — A base world's override must be bound in `DhApiLevelLoadEvent`, which fires before `SERVER_STARTED`.**
  - Symptom: base worlds' LODs come from DH's own generator, or the reach is wrong.
  - Fix: bind every level at load and read the reach lazily from `LevelManager` on the first coarse request (as built).
- **T-F4c — A fixed roofed start height is not exact, and a min()-capped metric hides it.**
  - In the Nether the best single height is open in only 55% of floored columns.
  - Compare against the floor under the roof, and use the fixed start only at 90% coverage or more.
- **T-F4d — The LOD self-test needs empty DH databases.**
  - Symptom: `lod pregen` shows 0 served requests.
  - Cause: sections already held from earlier boots or real chunks are skipped.
  - Fix: the lane script deletes the databases first.
