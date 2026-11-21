# Review packet: the_catalyst_maw

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_catalyst_maw.json`

> The sculk that fed on the boneyard war escaped here — corrupted end terrain bleeding into dark overworld, oppressive and chaotic. The catalyst is still feeding, still growing. The reactors and bastions it consumed are unrecognisable, and what it reaches near the portal it takes.

Biomes: minecraft:basalt_deltas, minecraft:soul_sand_valley, incendium:toxic_heap, incendium:withered_forest, minecraft:nether_wastes, incendium:ash_barrens, nullscape:shadowlands, minecraft:deep_dark, terralith:moonlight_valley, minecraft:mangrove_swamp, minecraft:dark_forest, terralith:moonlight_grove, minecraft:end_midlands, minecraft:end_highlands, terralith:volcanic_crater, terralith:caldera

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_catalyst_maw.json`

> The sculk that fed on the boneyard war escaped here — corrupted end terrain bleeding into dark overworld, oppressive and chaotic. The catalyst is still feeding, still growing. The reactors and bastions it consumed are unrecognisable, and what it reaches near the portal it takes.

Biomes: minecraft:deep_dark, adventure:umbral_wastes, minecraft:dark_forest, minecraft:mangrove_swamp, minecraft:soul_sand_valley, adventure:ash_barrens, minecraft:end_highlands, minecraft:end_midlands, minecraft:pale_garden, minecraft:old_growth_birch_forest, adventure:blackstone_basin, adventure:withered_forest, adventure:mycotoxic_undergrowth, minecraft:nether_wastes, minecraft:basalt_deltas, minecraft:stony_peaks

Terrain: `{"base": "end", "params": {"coverage": 0.05}, "scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "end"}`

Zones: ?

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_catalyst_maw/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_catalyst_maw/layers`

## Summary

- Top blocks seen from the sky: end_stone 9.2%, blackstone 5.2%, soul_sand 4.2%, soul_soil 4%.
- Surface biomes: adventure:umbral_wastes 6.3%, deep_dark 5.8%, dark_forest 4.6%, pale_garden 4.6%, adventure:withered_forest 4.4%.
- Ground blocks: end_stone 10.2%, grass_block 6.4%, blackstone 6.1%, soul_soil 4.9%.
- Liquid cover 0% water, 0.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 9.6%; bare rock 18%; no ground (void) 50.5%.
- Trees: 47.3 per ha (pale_oak_log 8.9/ha, mean spacing 6.6 blocks, warped_stem 8.5/ha, mean spacing 4.3 blocks, dark_oak_log 7/ha, mean spacing 6.4 blocks, stripped_crimson_stem 6.4/ha, mean spacing 4.8 blocks); ground plants other 3.6%, grass 1.2%, flower 0.3%, bush 0.2%, mushroom 0%.
- Surface height p5 41, median 59, p95 65, max 80; median relief per patch 19 blocks; slopes under 15° 47.5%, over 45° 21.9%, cliffs 3.9%; above the clouds 0%, below sea level 0%.
- Underground: 5.2% open, 0% liquid; cave biomes adventure:umbral_wastes 11.9%, dark_forest 9.7%, deep_dark 9.6%, adventure:withered_forest 9.6%.
- Notable clusters, fewest first: calcite 2 clusters, spacing n/a; creaking_heart 2 clusters, spacing n/a; dried_ghast 2 clusters, spacing n/a; spawner 3 clusters, spacing 22.5; brown_mushroom_block 4 clusters, spacing n/a; red_mushroom_block 4 clusters, spacing 22.2.
- 15 of 16 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1257 columns: height within 2 blocks 89.3%, biome match 100%.
- Landforms over the border (noise render): plains 7.1%, hills 4.5%, mountains 0%, plateaus 0%, basins 0%, islands 33.8%, water 0%, void 54.6%.
- Placeness: biome regions 7.8/km² (2 major, largest 1.2%), landform regions 7.2/km² (largest 50.0%), dominant place 50.0% of the border.

## Layers

- Columns by class: void 50.5%, roofed 0%, stacked 0.1%, overhang 7.5%, cave 4.7%, single 37.3%.
- Columns with each layer: sky 0.1%, surface 49.5%, underground 46%, roof 0%.
- Overhangs in 7.5% of columns, stacked layers in 0.1%, cave pockets in 5.8%.

## Places

- **surface** (344 cells, 8 types, silhouette 0.373):
  - type 1: 18.3% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 56; trees per cell 0.0 (layer 1.9); notable-block density (log) 1.0 (layer 0.6); canopy 0% (layer 19%); liquid cover 6% (layer 2%); bare rock 72% (layer 37%); open to the sky 100% (layer 81%); overhangs 7% (layer 16%); cave pockets 2% (layer 11%) — camera [-1272.5,71,2265.5]
  - type 2: 17.2% of cells; biome end_highlands; ground end_stone; top end_stone; height 53; neighbour step 1.3 (layer 0.8); relief over 16 blocks 24.4 (layer 14.2); relief over the patch 40.7 (layer 28.5); trees per cell 0.1 (layer 1.9); notable-block density (log) 0.2 (layer 0.6); canopy 0% (layer 19%); ground plants 0% (layer 10%); bare rock 92% (layer 37%); open to the sky 100% (layer 81%) — camera [-2215.5,59,-3335.5]
  - type 3: 11.9% of cells; biome deep_dark; ground end_stone; top end_stone; height 62; neighbour step 0.2 (layer 0.8); relief over 16 blocks 4.8 (layer 14.2); relief over the patch 10.1 (layer 28.5); trees per cell 0.2 (layer 1.9); canopy 2% (layer 19%); open to the sky 97% (layer 81%); cave pockets 22% (layer 11%) — camera [1240.5,64,-903.5]
  - type 4: 11.6% of cells; biome pale_garden; ground pale_moss_block; top pale_oak_leaves; height 58; trees per cell 4.4 (layer 1.9); canopy 78% (layer 19%); ground plants 18% (layer 10%); bare rock 6% (layer 37%); open to the sky 22% (layer 81%); cave pockets 32% (layer 11%) — camera [-3991.5,62,4008.5]
  - type 5: 11.3% of cells; biome dark_forest; ground grass_block; top dark_oak_leaves; height 58; neighbour step 0.3 (layer 0.8); relief over 16 blocks 6.5 (layer 14.2); relief over the patch 17.2 (layer 28.5); trees per cell 5.1 (layer 1.9); canopy 73% (layer 19%); ground plants 42% (layer 10%); bare rock 1% (layer 37%); open to the sky 27% (layer 81%) — camera [1112.5,59,664.5]
  - type 6: 10.5% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 58; neighbour step 1.5 (layer 0.8); relief over 16 blocks 22.7 (layer 14.2); relief over the patch 44.7 (layer 28.5); notable-block density (log) 1.1 (layer 0.6); ground plants 3% (layer 10%); bare rock 3% (layer 37%); overhangs 42% (layer 16%) — camera [3720.5,59,-3079.5]
  - type 7: 9.9% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 57; neighbour step 0.3 (layer 0.8); relief over 16 blocks 6.5 (layer 14.2); trees per cell 0.0 (layer 1.9); canopy 1% (layer 19%); ground plants 0% (layer 10%); bare rock 13% (layer 37%); open to the sky 99% (layer 81%) — camera [-3063.5,59,2296.5]
  - type 8: 9.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 56; trees per cell 6.7 (layer 1.9); bare rock 3% (layer 37%) — camera [4008.5,58,-2488.5]
  - landmark at [1272,-3976]: biome basalt_deltas (type end_highlands); ground basalt (type end_stone); top basalt (type end_stone); liquid cover 26% (type 0%); overhangs 65% (type 3%); neighbour step 3.0 (type 0.7)
  - landmark at [-3960,-3992]: biome mangrove_swamp (type adventure:mycotoxic_undergrowth); ground mud (type moss_block); top mangrove_leaves (type moss_block); canopy 62% (type 0%); open to the sky 38% (type 100%); relief over the patch 19.0 (type 46.0)
  - landmark at [-2232,872]: biome mangrove_swamp (type pale_garden); ground mud (type pale_moss_block); top mangrove_leaves (type pale_oak_leaves); overhangs 60% (type 13%); cave pockets 55% (type 17%); neighbour step 1.5 (type 0.4)
  - landmark at [-3992,4024]: ground end_stone (type pale_moss_block); neighbour step 2.7 (type 0.4); height 46.5 (type 61.3); relief over 16 blocks 35.0 (type 6.0)
  - landmark at [824,1176]: biome dark_forest (type adventure:mycotoxic_undergrowth); ground grass_block (type moss_block); top grass_block (type moss_block); height 35.3 (type 62.0); layer present in 25% (type 100%); relief over 16 blocks 28.0 (type 14.0)
- **sky**: no cells.
- **underground** (324 cells, 2 types, silhouette 0.366):
  - type 1: 88.6% of cells; notableId sculk_vein; biome deep_dark; height 58 — camera [-1986.5,64.88,-2966.5]
  - type 2: 11.4% of cells; notableId cave_vines_plant; biome adventure:mycotoxic_undergrowth; height 59; neighbour step 1.9 (layer 0.8); relief over 16 blocks 23.4 (layer 12.0); trees per cell 2.4 (layer 0.3); notable-block density (log) 0.8 (layer 0.3); cave air 11% (layer 6%) — camera [2758.5,59.88,-2270.5]
  - landmark at [1240,-3928]: notableId basalt (type cave_vines_plant); biome basalt_deltas (type adventure:mycotoxic_undergrowth); cave liquid 0% (type 0%); cave air 29% (type 1%); layer present in 39% (type 100%)
  - landmark at [-3992,4024]: notableId stone_bricks (type cave_vines_plant); biome pale_garden (type adventure:mycotoxic_undergrowth); notable-block density (log) 2.8 (type 0.3); height 46.5 (type 60.7); trees per cell 0.0 (type 2.0)
  - landmark at [-4008,4040]: notableId cracked_stone_bricks (type cave_vines_plant); biome pale_garden (type adventure:mycotoxic_undergrowth); cave pockets 55% (type 0%); height 49.1 (type 60.7); trees per cell 0.0 (type 2.0)
  - landmark at [1304,-4008]: notableId oak_planks (type none); biome stony_peaks (type none); cave air 43% (type 2%); trees per cell 4.0 (type 0.0); notable-block density (log) 1.5 (type 0.0)
  - landmark at [1288,-4008]: notableId iron_chain (type none); biome stony_peaks (type none); cave air 48% (type 2%); cave pockets 59% (type 3%); height 49.3 (type 58.7)
- **roof**: no cells.

## Biome expression

15 of 16 placed biomes are expressed.

- minecraft:deep_dark: expressed, 23042 columns; placement land 41.1%, void 58.9%; snow 0%, canopy 0%, trees 0/ha; top sculk 18.8%, end_stone 16.7%, sculk_vein 5.1%
- adventure:umbral_wastes: expressed, 22995 columns; placement land 44.8%, void 55.2%; snow 0%, canopy 0%, trees 0/ha; top blackstone 32.2%, obsidian 4.9%, soul_fire 3.3%
- minecraft:end_highlands: expressed, 15484 columns; placement land 41.1%, void 58.8%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top end_stone 39.2%, chorus_plant 1.1%, chorus_flower 0.8%
- minecraft:dark_forest: expressed, 14000 columns; placement land 54.3%, void 45.7%; snow 0%, canopy 79.7%, trees 208/ha; top dark_oak_leaves 38.3%, grass_block 6.6%, oak_leaves 3.5%
- minecraft:pale_garden: expressed, 13360 columns; placement land 56.8%, void 43.2%; snow 0%, canopy 79.2%, trees 185.8/ha; top pale_oak_leaves 44.5%, grass_block 4.8%, pale_moss_block 3.4%
- minecraft:mangrove_swamp: expressed, 12208 columns; placement land 28.7%, void 71.3%; snow 0%, canopy 62.6%, trees 168.4/ha; top mangrove_leaves 14.9%, mud 7.2%, vine 4.8%
- adventure:withered_forest: expressed, 10376 columns; placement land 69.7%, void 30.3%, sky 0.1%; snow 0%, canopy 4.9%, trees 300.2/ha; top soul_soil 57.6%, wither_rose 2.7%, end_stone 2.5%
- minecraft:soul_sand_valley: expressed, 9681 columns; placement land 72.6%, void 27.4%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 70.3%, soul_soil 1%, end_stone 0.9%
- minecraft:basalt_deltas: expressed, 8576 columns; placement land 50.6%, lava 15.8%, void 33.6%; snow 0%, canopy 0%, trees 0/ha; top basalt 42.7%, lava 15.8%, magma_block 4.1%
- adventure:mycotoxic_undergrowth: expressed, 7745 columns; placement land 91.4%, void 7.6%, sky 1%; snow 0%, canopy 0.2%, trees 74.9/ha; top moss_block 47.6%, slime_block 37.3%, end_stone 3.2%
- minecraft:end_midlands: expressed, 6192 columns; placement land 30.9%, void 69.1%; snow 0%, canopy 12.5%, trees 20.9/ha; top end_stone 26.3%, pale_oak_leaves 3.4%, mangrove_leaves 0.4%
- minecraft:stony_peaks: expressed, 5347 columns; placement land 47%, lava 0.3%, void 52.4%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top end_stone 46.3%, basalt 0.4%, lava 0.3%
- adventure:ash_barrens: too few columns to judge, 5120 columns; placement land 1%, void 99%; snow 0%, canopy 0%, trees 0/ha; top tuff 0.9%, soul_soil 0%, bone_block 0%
- minecraft:nether_wastes: expressed, 4981 columns; placement land 37.3%, lava 0.3%, void 62.3%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top netherrack 32.6%, end_stone 3.2%, nether_quartz_ore 0.5%
- adventure:blackstone_basin: expressed, 2304 columns; placement land 44.4%, lava 3.8%, void 51.7%; snow 0%, canopy 0%, trees 0/ha; top blackstone 40.3%, polished_blackstone 4%, lava 3.8%
- minecraft:old_growth_birch_forest: expressed, 2304 columns; placement land 75%, void 25%; snow 0%, canopy 47.7%, trees 225.7/ha; top birch_leaves 35.8%, grass_block 32.1%, wildflowers 3.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:basalt_deltas | minecraft:basalt_deltas | 3.5 | 8576 | expressed | - |
| O2 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 4.3 | 9681 | expressed | - |
| O3 | incendium:toxic_heap | adventure:mycotoxic_undergrowth | 4.3 | 7745 | expressed | - |
| O4 | incendium:withered_forest | adventure:withered_forest | 4.4 | 10376 | expressed | - |
| O5 | minecraft:nether_wastes | minecraft:nether_wastes | 1.1 | 4981 | expressed | - |
| O6 | incendium:ash_barrens | adventure:ash_barrens | 0.0 | 5120 | too few columns to judge | - |
| O7 | nullscape:shadowlands | adventure:umbral_wastes | 6.3 | 22995 | expressed | - |
| O8 | minecraft:deep_dark | minecraft:deep_dark | 5.8 | 23042 | expressed | - |
| O9 | terralith:moonlight_valley | minecraft:old_growth_birch_forest | 1.1 | 2304 | expressed | - |
| O10 | minecraft:mangrove_swamp | minecraft:mangrove_swamp | 2.1 | 12208 | expressed | - |
| O11 | minecraft:dark_forest | minecraft:dark_forest | 4.6 | 14000 | expressed | - |
| O12 | terralith:moonlight_grove | minecraft:pale_garden | 4.6 | 13360 | expressed | - |
| O13 | minecraft:end_midlands | minecraft:end_midlands | 1.2 | 6192 | expressed | - |
| O14 | minecraft:end_highlands | minecraft:end_highlands | 3.9 | 15484 | expressed | - |
| O15 | terralith:volcanic_crater | adventure:blackstone_basin | 0.7 | 2304 | expressed | - |
| O16 | terralith:caldera | minecraft:stony_peaks | 1.5 | 5347 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:umbral_wastes 6.3%, minecraft:deep_dark 5.8%, minecraft:dark_forest 4.6% | type `nether_islands` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `adventure:compressed` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 50.5% (`surface.voidPercent`) | |
| M:canopy | 9.6% (`surface.canopyPercent`) | |
| M:relief | median 19.0 blocks a patch; height {"p5": 41.0, "p25": 56.0, "p50": 59.0, "p75": 61.0, "p95": 65.0, "max": 80.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 49.5, "underground": 46.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#0B2B3B", "ambientLight": 0.06, "effects": "minecraft:the_end", "hasCeiling": false, "monsterSpawnLightLevel": 15}` |
| M:classes | {"void": 50.5, "roofed": 0.0, "stacked": 0.1, "overhang": 7.5, "cave": 4.7, "single": 37.3} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 93.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (49.5% of columns)

## Shots

### the_catalyst_maw-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 8.6%; void 11.4%; mid: soul_soil 21.2%, blackstone 19.4%; far: blackstone 11.5%, soul_soil 5.6%; biomes: adventure:withered_forest 56.3%, adventure:blackstone_basin 43.7%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-far.png`

### the_catalyst_maw-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 8.6%; void 11.4%; mid: soul_soil 21.2%, blackstone 19.4%; far: blackstone 11.5%, soul_soil 5.6%; biomes: adventure:withered_forest 56.3%, adventure:blackstone_basin 43.7%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-far-nv.png`

### the_catalyst_maw-surface-t2

Picked for: surface type 2: 18.0% of cells; biome end_highlands; ground end_stone; top end_stone; height 53; neighbour step 1.2 (layer 0.8); relief over 16 blocks 23.4 (layer 14.2); relief over the patch 39.4 (layer 28.5); trees per cell 0.1 (layer 1.9); notable-block density (log) 0.2 (layer 0.6); canopy 0% (layer 19%); ground plants 0% (layer 10%); bare rock 92% (layer 37%); open to the sky 100% (layer 81%)
Census: sky 25.5%; unloaded 1.6%; near: end_stone 50.9%, chorus_plant 9.5%; mid: chorus_plant 4.4%, end_stone 2.5%; far: end_stone 1.3%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t2.png`

### the_catalyst_maw-surface-t2-nv

Picked for: surface type 2: 18.0% of cells; biome end_highlands; ground end_stone; top end_stone; height 53; neighbour step 1.2 (layer 0.8); relief over 16 blocks 23.4 (layer 14.2); relief over the patch 39.4 (layer 28.5); trees per cell 0.1 (layer 1.9); notable-block density (log) 0.2 (layer 0.6); canopy 0% (layer 19%); ground plants 0% (layer 10%); bare rock 92% (layer 37%); open to the sky 100% (layer 81%)
Census: sky 25.5%; unloaded 1.6%; near: end_stone 50.9%, chorus_plant 9.5%; mid: chorus_plant 4.4%, end_stone 2.5%; far: end_stone 1.3%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t2-nv.png`

### the_catalyst_maw-underground-t1

Picked for: underground type 1: 88.6% of cells; notableId sculk_vein; biome deep_dark; height 58
Census: sky 20.8%; unloaded 2.8%; near: end_stone 54.4%, chorus_plant 17.4%; mid: chorus_plant 2%; biomes: end_highlands 95.5%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-underground-t1.png`

### the_catalyst_maw-underground-t1-nv

Picked for: underground type 1: 88.6% of cells; notableId sculk_vein; biome deep_dark; height 58
Census: sky 21.1%; unloaded 2.4%; near: end_stone 54.4%, chorus_plant 17.4%; mid: chorus_plant 2%; biomes: end_highlands 95.5%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-underground-t1-nv.png`

### the_catalyst_maw-surface-t3

Picked for: surface type 3: 12.2% of cells; biome dark_forest; ground grass_block; top dark_oak_leaves; height 58; neighbour step 0.3 (layer 0.8); relief over 16 blocks 7.0 (layer 14.2); relief over the patch 17.5 (layer 28.5); trees per cell 5.0 (layer 1.9); canopy 70% (layer 19%); ground plants 39% (layer 10%); bare rock 2% (layer 37%); open to the sky 30% (layer 81%)
Census: sky 0.4%; unloaded 0.2%; near: dark_oak_log 28%, grass_block 26.9%; biomes: dark_forest 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t3.png`

### the_catalyst_maw-surface-t3-nv

Picked for: surface type 3: 12.2% of cells; biome dark_forest; ground grass_block; top dark_oak_leaves; height 58; neighbour step 0.3 (layer 0.8); relief over 16 blocks 7.0 (layer 14.2); relief over the patch 17.5 (layer 28.5); trees per cell 5.0 (layer 1.9); canopy 70% (layer 19%); ground plants 39% (layer 10%); bare rock 2% (layer 37%); open to the sky 30% (layer 81%)
Census: sky 0.4%; unloaded 0.1%; near: dark_oak_log 28%, grass_block 26.9%; biomes: dark_forest 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t3-nv.png`

### the_catalyst_maw-surface-t4

Picked for: surface type 4: 11.6% of cells; biome pale_garden; ground pale_moss_block; top pale_oak_leaves; height 58; trees per cell 4.5 (layer 1.9); canopy 79% (layer 19%); ground plants 18% (layer 10%); bare rock 8% (layer 37%); open to the sky 21% (layer 81%); cave pockets 34% (layer 11%)
Census: sky 0.6%; near: tall_grass 21.8%, pale_oak_log 16%; biomes: pale_garden 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t4.png`

### the_catalyst_maw-surface-t4-nv

Picked for: surface type 4: 11.6% of cells; biome pale_garden; ground pale_moss_block; top pale_oak_leaves; height 58; trees per cell 4.5 (layer 1.9); canopy 79% (layer 19%); ground plants 18% (layer 10%); bare rock 8% (layer 37%); open to the sky 21% (layer 81%); cave pockets 34% (layer 11%)
Census: sky 0.6%; near: tall_grass 21.8%, pale_oak_log 16%; biomes: pale_garden 100%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t4-nv.png`

### the_catalyst_maw-surface-t7

Picked for: surface type 7: 9.9% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 58; neighbour step 1.5 (layer 0.8); relief over 16 blocks 23.1 (layer 14.2); relief over the patch 45.6 (layer 28.5); notable-block density (log) 1.0 (layer 0.6); ground plants 3% (layer 10%); bare rock 2% (layer 37%); overhangs 43% (layer 16%); stacked layers 1% (layer 0%)
Census: sky 5.2%; unloaded 0.7%; near: slime_block 39.1%, moss_block 34.7%; biomes: adventure:mycotoxic_undergrowth 99.8%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t7.png`

### the_catalyst_maw-surface-t7-nv

Picked for: surface type 7: 9.9% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 58; neighbour step 1.5 (layer 0.8); relief over 16 blocks 23.1 (layer 14.2); relief over the patch 45.6 (layer 28.5); notable-block density (log) 1.0 (layer 0.6); ground plants 3% (layer 10%); bare rock 2% (layer 37%); overhangs 43% (layer 16%); stacked layers 1% (layer 0%)
Census: sky 5.2%; unloaded 0.7%; near: slime_block 39.1%, moss_block 34.7%; biomes: adventure:mycotoxic_undergrowth 99.8%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-t7-nv.png`

### the_catalyst_maw-surface-lm1

Picked for: surface landmark: biome basalt_deltas (type end_highlands); ground basalt (type end_stone); top basalt (type end_stone); liquid cover 26% (type 0%); overhangs 65% (type 3%); neighbour step 3.0 (type 0.7)
Census: sky 0%; void 4.4%; near: end_stone 41.4%, basalt 33.6%; biomes: basalt_deltas 98.5%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-lm1.png`

### the_catalyst_maw-surface-lm1-nv

Picked for: surface landmark: biome basalt_deltas (type end_highlands); ground basalt (type end_stone); top basalt (type end_stone); liquid cover 26% (type 0%); overhangs 65% (type 3%); neighbour step 3.0 (type 0.7)
Census: sky 0%; void 4.4%; near: end_stone 41.4%, basalt 33.6%; biomes: basalt_deltas 98.5%
Image: `/tmp/claude-0/w10c3/reviews/the_catalyst_maw/screens/the_catalyst_maw-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
