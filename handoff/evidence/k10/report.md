K10 report: all four gaps are done. Each has its schema, docs, unit tests, a compile check on scratch copies of its users, and the vanilla boot check. Nothing was booted on the mod; that is left to the lane script below.

**Where it is**
- Worktree: `/home/user/minecraft-server-template/.claude/worktrees/agent-a8c0e12f15f720581`, branch `worktree-agent-a8c0e12f15f720581`, on top of 2b5f8540. Not pushed.
- Commits:
  - d6e0824c: gaps 1, 3 and 4 (compiler, core, schema, docs).
  - 0991ab78: gap 2 (survey and server), plus the self-test fixture and its line.
- Paths below are relative to `mods/custom-dimensions-26/`.

**Gap 1 — sea and shore roles under a lava sea**
- Where:
  - `compiler/.../terrain/RoofedTerrain.java:129` writes `<prefix>/coast` = clamp((floor − seaLevel)/64, ±1) as the router's continentalness. `coast()` is at :240 and `lowestFloor()` at :227.
  - `compiler/.../datapack/RegionLayout.java`:
    - `ground()` :162 and `roofedCoast()` :216 detect a roofed floor with `liquids.seaLevel` over part of it.
    - `seaRoles()` :222 and `coast(…, roles)` :260 build the coastline (line 0, shore ±3/64, no river band).
    - :330 refuses the `river` role on a roofed floor.
    - :390 `over: land` ranks over the floor above the sea.
- Fields: none new. It switches on when an `ocean`/`shore` role is present (own or via `biomeLayout.roles`) or with `over: land`. Without either, the layout is unchanged.
- Example: `"liquids": {"seaLevel": 74}, "biomeLayout": {"mode": "regions", "roles": {"minecraft:basalt_deltas": "ocean"}}`.

**Gap 2 — vistas and landmarks under the roof**
- Root cause: the Nether type is 256 high, so above the bedrock roof is open air. A column solid from floor to roof had its "surface" at 128 with a clear eye over it.
- Where:
  - `server/.../render/Sampler.java:303`: `Surface` and `Column` now carry the roof's lowest block.
  - `core/.../render/ColumnSamples.java`: `setRoof`, `roof`, `NO_ROOF`.
  - `server/.../survey/SurveyJob.java:200`: under a ceiling it passes the roofs to `Landforms.withRoofs` (:229).
  - `Landforms.vista()` :488 takes the highest floor with `OPEN_UNDER_ROOF` (16) or more blocks open under its roof. A closed target is looked at at the lowest floor on its ray.
  - `core/.../survey/PatchAnalysis.java:781`: under a ceiling a surface camera needs a roof over it, with 16+ blocks open preferred.
  - `Survey.java`: the vista note says when it is measured under the roof.
- `pick-shots.py` already puts a ceiling world's far camera at summit + 12, so it is unchanged and T248 does not apply.

**Gap 3 — atmosphere over biomes**
- Field: `atmosphere.overBiomes: true`. Example: `"atmosphere": {"fogColor": "#1B0B2B", "overBiomes": true}`.
- Mechanism (`DatapackCompiler.java:483` `applyOverBiomes`): the type's sky and fog colours are written as `<ns>:atmosphere/<slug>`, one constant `override` track each, placed first in the dimension type's `timelines`.
  - I checked the order in the 26.3 bytecode: dimension attributes, then biome, then timelines in list order. So the colours win over every biome, and the day's `multiply` tracks still darken them.
- The brief assumed the compiler already writes per-dimension biome copies. It doesn't, so I used this timeline layer instead. Copied biomes would also lose their ids' tags, structure biome checks and palettes.
- With no colour to hold, the compile refuses. The `Atmosphere` record (core) and `DefinitionReader` take the new field.

**Gap 4 — a biome bound below the floor**
- Field: `biomeLayout.belowFloor`, biome → whole blocks from 4 to 256, each biome a different number. Example: `"belowFloor": {"minecraft:deep_dark": 8}`.
- Where:
  - `RoofedTerrain.java:123`: router depth = lerp(step at N blocks under the actual floor, the existing (floorHeight − y)/128, 1.5005 + 0.5/256 per block deeper).
  - `RegionLayout` `belowFloor()` :512, `bands()` :552, `checkBelowFloor()` :539.
- Several biomes stack in bands; the deepest runs to 2. Existing `climate.depth` boxes keep their meaning above the floor.
- Refused when the terrain is not roofed, when the biome also has a climate box, for duplicate depths, for depths outside 4–256, and for unlisted biomes.

**Tests (before: 1438 green at a0442313; after: 1459, 0 failed)**
- Full `./gradlew --max-workers=1 --offline build`: 1459 tests (compiler 578, core 721, server 160).
- Quick gate exit 0. Goldens are unchanged; no shipped definition uses the new fields.
- New tests, which fail without the change (the old reader rejects the new fields; the new APIs and the camera rule don't exist before it):
  - `compiler/.../datapack/RoofedBiomesTest.java`: 9 tests, gaps 1 and 4 plus all refusals.
  - `RoofedTerrainTest`: 6 tests, evaluating the coast cue and the bands numerically.
  - `DatapackCompilerTest`: 4 tests, gap 3, including a layered fog read over `withered_forest`, whose own fog is #55606c.
  - `core/.../survey/SurveyTest`: 2 tests. One checks a roofed fixture's vista lies under its roof (it would pick y 128 without the roofs). The other checks a surface camera stands under the roof, not on top of it.
- I did not run the new tests against the old code; "fail without" is by reasoning. The lane reader's dry run on W10c2's r6 dossiers does fail on the old behaviour:
  - buried age land biomes 46–90% under lava;
  - blackstone vista y 128, far camera at 140;
  - 2 of 21 surface cameras on the roof;
  - boneyard deep_dark 1.3% of the surface and 0.1% of the caves.

**Users compile check**
- Scratch definitions only; `definitions/` is untouched. Built by `/tmp/claude-0/k10/make_users.py` into `/tmp/claude-0/k10/defs-users`.
- Users: buried_age, molten_flats, gilded_pit and forged_depths with sea roles (the last three given a roofed terrain); boneyard and ember_fields with belowFloor; forged_depths with magma_land bound 12 blocks under the floor; sanctum, boneyard, ember and buried with overBiomes.
- All 82 definitions compile. `/tmp/claude-0/k10/check_users.py` reports all PASS.
- Vanilla boot check on that pack: PASS, all base worlds entered, 0 errors in the log.

**Lane script (T217; not run)**
- `/tmp/claude-0/k10/lane.sh`, read by `/tmp/claude-0/k10/lane-read.py`. Outputs go to `/tmp/claude-0/k10/lane/`; read `verdicts.txt`, `selftest.txt` and `driver.log`. Needs no other server running and 5 GB free.
- It is one script with two boots: the self-test boot stops the server itself, so the surveys need a second boot.
- Boot 1: `-PselfTestOnly=terrain` over the scratch users plus the fixtures, including the new `selftest_roofed_biomes` (TerrainSelfTest `roofedBiomes()` :955). The line must PASS:
  - basalt on 90% or more of floors 4+ blocks under the lava, and on 5% or less of floors as far above it;
  - deep_dark on 95% or more of points 16 blocks under the floor, and on 1% or less of the floor;
  - fog #1b0b2b at every floor, read through the running game's environment attributes;
  - 40+ columns in each class.
  - This adds one line to the self-test baseline (232 → 233 PASS).
- Boot 2 surveys one user per gap, with these bars:
  - the_buried_age: basalt 50%+ under lava; every other biome 25% or less under lava.
  - the_blackstone_keep: far camera under the roof; no surface camera at or over y 127.
  - the_obsidian_sanctum: the registered type's timelines start with its atmosphere timeline.
  - the_boneyard: deep_dark on 1% or less of the surface and 5%+ of the cave samples.
- The rig's fog-luma check on the sanctum is not part of it.

**Trap drafts** (full text in `/tmp/claude-0/k10/traps.md`; nothing staged in TROUBLESHOOTING/AGENTS/HANDOFF)
- **T269:** a biome's own fog and sky replace the dimension's (layer order); `overBiomes` holds them. Never use per-dimension biome copies for this.
- **T270:** above the Nether-type bedrock roof is open air (256 high, 128 logical), so survey vistas and cameras landed on the roof. The coarse sample keeps each column's roof; a vista needs 16+ blocks open; a surface camera needs a roof over it.
- **T271:** a roofed world's router depth is measured from floorHeight, not the floor, so no `climate.depth` box separates "under the floor" from "deep floor". Use `belowFloor`. Band values stay `BAND_EDGE` off box edges to avoid nearest-distance ties.

The log is `/tmp/claude-0/k10/progress.log` and the state file is `/tmp/claude-0/k10/done.txt`.
