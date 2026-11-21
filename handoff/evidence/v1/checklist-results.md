# V1 checklist results (K3 server-checklist.md order)

Evidence: self-test reports /tmp/claude-0/v1/st-*.txt; survey runs /tmp/claude-0/v1/runs/batch*/ (driver.log, server.log, dossiers/, full/, renders/).

## 0. The pack decodes
- FIXED compiled timelines decode: the pack was refused at load (st-full1): `adventure:day_length/day_54000_18000` repeated `minecraft:wake_up_from_sleep` on clock `minecraft:overworld` (26.3 `Timeline.validateRegistry`: a (clock, time marker) pair is unique across the whole timeline registry). A day length runs on its own world clock `<ns>:day_length/<day>_<night>`, the dimension type's `default_clock`. Test `DayLengthTest.noClockCarriesATimeMarkerTwice`. After: 15/15 timelines, 198/198 dimension types, 159/159 noise settings, 198/198 level stems decode (st-full2).

## 1. Load order
1. Generators
   - PASS generator void (the_red_monument): 0 non-air blocks; 8 of 8 listed biomes within 1024 blocks. FIXED the check: a 128-block chunk sample held one climate region (umbral_wastes only); it now reads the biome source every 64 blocks out to 1024.
   - PASS generator void biomes (selftest_void_biomes): 0 blocks; ice_spikes and snowy_plains (same fix).
   - FIXED generator checkerboard voidBiomes: every void-cell column was water (the sea and aquifers flooded the cells the density emptied). A checkerboard with void cells has no aquifers, `default_fluid` air and its sea at min_y; `liquids.seaLevel` is reported unsupported there. Test `VoidWorldsTest.checkerboardVoidCellsHoldTheFinalDensityBelowZero`; golden `checkerboard_void_cells` regenerated. In-server after: 312 of 20480 void-cell columns hold blocks, all features or structures (the_end biome's own obsidian spikes, village pieces reaching over from neighbour cells), none terrain; the line counts terrain (stone, deepslate, bedrock, fluid) and lists the feature blocks.
2. Land share — PASS. Log lines: 0.8 fixture shift 0.2439 (0.8000 measured), seed 1 shift 0.6256 (0.7999); 0.25 fixture shift -0.2656 (0.2500), seed 1 shift 0.0412 (0.2500); each printed twice per level with the same shift. landforms.sharesPercent.water: 0.8 fixture 16.5 and 17.1 (seed 1) (want ~20 ±10); 0.25 fixture 83.0 and 80.9 (want ~75 ±10).
3. Day length — see clock section below.
4. Dry sea — PASS. the_dustbowl placeness.biomesPercent has no ocean biome (water share 0.1%); terrain_overworld_dry_sea water 0.1%, fluidCoverPercent.water 0.
5. Roofed terrain — PASS. New self-test line `terrain roofed (terrain_roofed_default)`: 100.0% of columns under a roof at y 127, 94.2% with 8+ blocks of open air to the floor, floor y 35..89 (p10..p90), median gap 53. Renders show the floor (lava lakes on it), not the roof (`/customdim render` reads under a roof already); the self-test gallery's height maps now read the floor under a roof too (TerrainSelfTest.floorHeight). Goldens `terrain_roofed_*`, `terrain_nether_climate` pass in the build.
6. End and sky kit — PASS for the End family line (`terrain end (terrain_end_default)`: 11.1% land, 88.9% empty to the bottom, 99.5% of land ground end stone). Islands survey: terrain_end_default 194 islands (p50 0.9 ha, max 45.0), dominantBiomePercent 47.5, voidBiomesPercent lists 5; terrain_end_cluster 346 islands. `biome regions ... (land N)` for End regions over land: no shipped definition or fixture prints one (golden `end_regions_land` only). FAIL (gallery): terrain_end_cluster shows no cluster at the centre (see renders).
7. Materials — PASS. the_underdark: grass_block 0.0% of ground (was 27.5), moss_block 14.9%. the_teal_corruption crimson_forest: tops crimson_nylium 29.7%, nether_wart_block 46.3%, 268.9 trees/ha. the_blighted_maw end_stone 11.5% -> 0.0%, netherrack and soul ground 15.8 -> 27.1%. the_tiled_expanse grass 34.4 -> 26.1%, basalt group 18.6 -> 26.2%.
8. Library biomes (st-full2, then st-a-biomes)
   - FIXED ashen_savanna, cedar_forest: 0 land columns (the showcase's 96x96 round spawn was ocean); both showcases stand on continents with landShare 0.95 and a radial layout. PASS after.
   - FIXED mirage_isles: grass 43.4% (want 50), calcite 56.5%: calcite_pavements 3 -> 2 disks a chunk. PASS after.
   - FIXED bayou cypress_knees (0 placed): a block_column tests the blocks after its origin, never the origin, so a water-only allowed_placement truncated every knee to nothing; knees may now rise into air. Result in the final self-test below.
   - FAIL rock_pools rim_moss (0 placed; seagrass 2, sea pickles 7): widening the water check to one or two below changed nothing; the showcase's water (39.1% of tops) is the sea, and the pools the rim moss looks for are rare there. Needs a block-level probe of the showcase.
   - The other twelve load and meet L1's numbers (st-a-biomes: 106 PASS lines).
## 2. Survey metrics
- PASS structures.census (nested in `structures`): the_forged_depths fortress 5 (byRing [2,0,0,2,1]), the_underdark 7 kinds in 115 ms. the_red_monument census is empty because its mes structures are not installed on the dev server ("forced structure mes:monolith is not in the registry"), not a census fault.
- PASS placeness.at.centre/spawn.heightAboveMedianBlocks: the_gilded_pit centre -39, spawn 23.
- PASS surface.groundBlocksPercentByBand: the_forged_depths bounds [41, 83] with low, middle, high.
- PASS skyLuminance/fogLuminance: the_red_monument 0.027/0.002 (want ~0.03), the_starwell 0.006/0.002.
- PASS placeness.islands and voidBiomesPercent on End fixtures; the_red_monument voidBiomesPercent lists 5 biomes (umbral_wastes 85.1).
## 3. Day length and the clock
- PASS (after FIX): overworld and the_greenreach read clock adventure:day_length/54000_18000; `time set 1000` in the overworld moved the_greenreach and not minecraft:overworld; `time set noon` -> 27000, `night` -> 55500 (+60 ticks of running). Self-test `day length` line (final run) reads the sun at 0, 27000, 54000, 63000, 72000.
## Beams (W10a's root_hollows bug)
- FIXED: the_crucible full survey 0 "unsafe terrain read" lines (W10a's full surveys: 15340 to 37777); root_hollows tops still 11.6% mangrove_roots. BiomeLibrary refuses a horizontal block_column over 16 (test aHorizontalColumnPastTheWriteRadiusIsRefused).
## Final fresh-world self-test (level-seed 20261022, st-final.txt)
- PASS 249, FAIL 2, SKIP 1 (baseline 232/0/1; 17 new lines). The two FAILs: checkerboard voidBiomes (3 terrain columns of 20480; the line takes 0.1% since, PASS in st-c-generators) and rock_pools rim_moss (open). bayou cypress_knees 414 mangrove_wood after the fix.
- New lines: day length; terrain roofed; terrain end; plus K1/K2/K3/L1 lines already in the branch.
## Counts
- PASS 12 items, FIXED 10, FAIL 2 (rock_pools rim_moss; terrain_end_cluster gallery promise).
## Round 2
- Void cells: the 3 "terrain" columns (-34,-25; -42,-1; -13,-40) were End spikes (obsidian from y -64, a bedrock crown and fire at y 103/91/88), the_end biome's own feature; no terrain crosses a cell edge. The line counts terrain by VoidCellBlocks (stone, deepslate, water, lava, bedrock floor) and wants 0; feature columns 312 (want at most 5%). PASS.
- rock_pools: the showcase had no pools (all water was the sea at y 62, land at y 79-87, tide pools place only on ground at y 62-68); rims and floors were gravel/sand/clay, which rim moss refused; pool life asked for 2-deep water. FIXED: showcase on flats at y 65; rim moss on stone, andesite, gravel, sand, clay; seagrass in 1-deep pools. rim_moss 38, seagrass 51. PASS.
- terrain_end_cluster: FIXED (EndTerrain.laidOut). r512 and r2048 renders show a cluster round the centre inside the empty ring; self-test `terrain end layout`: 38.6% island 150-450 blocks out, tops y 103.
- Final fresh-world self-test: PASS 252, FAIL 0, SKIP 1.
