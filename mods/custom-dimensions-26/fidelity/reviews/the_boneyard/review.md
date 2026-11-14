# Review packet: the_boneyard

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_boneyard.json`

> The battlefield. Piglins met endermen here and neither side won. Bones of giants lie in soul sand, a crashed ship rusts in the distance, and the sculk that crept in wasn't either side's weapon — it fed on the war itself.

Biomes: minecraft:deep_dark, betternether:bone_reef, minecraft:soul_sand_valley, betternether:soul_plain, betternether:sulfuric_bone_reef, incendium:ash_barrens, regions_unexplored:blackstone_basin, minecraft:basalt_deltas, regions_unexplored:ancient_delta, incendium:toxic_heap, incendium:withered_forest

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_boneyard.json`

> The battlefield. Piglins met endermen here and neither side won. Bones of giants lie in soul sand, a crashed ship rusts in the distance, and the sculk that crept in wasn't either side's weapon — it fed on the war itself.

Biomes: minecraft:soul_sand_valley, adventure:ash_barrens, adventure:gravel_desert, adventure:withered_forest, adventure:quartz_flats, minecraft:basalt_deltas, adventure:blackstone_basin, adventure:mycotoxic_undergrowth, minecraft:deep_dark

Terrain: `{"base": "roofed", "params": {"floorHeight": 48, "floorRelief": 14, "ceilingGap": 50, "roughness": 0.35, "chasms": 0.03, "chasmDepth": 30}, "scales": {"climate": 1.6}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/c5/full/the_boneyard/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c5/full/the_boneyard/layers`

## Summary

- Top blocks seen from the sky: soul_sand 27.1%, soul_soil 24.8%, tuff 10.7%, netherrack 10.3%.
- Surface biomes: soul_sand_valley 63.5%, adventure:ash_barrens 13.8%, adventure:gravel_desert 6.6%, adventure:withered_forest 3.8%, adventure:blackstone_basin 3.2%.
- Ground blocks: soul_sand 28.8%, soul_soil 26.8%, netherrack 11.5%, tuff 10.7%.
- Liquid cover 0% water, 7.3% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0.2%; bare rock 24.9%; no ground (void) 0%.
- Trees: 17.9 per ha (stripped_crimson_stem 6.7/ha, mean spacing 4.7 blocks, warped_stem 5/ha, mean spacing 4 blocks, stripped_warped_hyphae 2.4/ha, mean spacing 8.2 blocks, stripped_warped_stem 2.2/ha, mean spacing 8.8 blocks); ground plants other 0.9%, bush 0.2%, flower 0.1%, grass 0%.
- Surface height p5 18, median 46, p95 111, max 127; median relief per patch 97 blocks; slopes under 15° 45%, over 45° 28.6%, cliffs 15.1%; above the clouds 0%, below sea level 6.4%.
- Underground: 15.6% open, 1% liquid; cave biomes soul_sand_valley 59.7%, adventure:ash_barrens 9.6%, deep_dark 9.2%, adventure:gravel_desert 6.9%.
- Notable clusters, fewest first: spawner 2 clusters, spacing n/a; mossy_cobblestone 3 clusters, spacing n/a; cobblestone 4 clusters, spacing 3.1; chest 4 clusters, spacing 3.6; amethyst_cluster 7 clusters, spacing 9.7; large_amethyst_bud 9 clusters, spacing 3.8.
- 9 of 9 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 81.6%, biome match 99.7%.
- Landforms over the border (noise render): plains 50.5%, hills 36.6%, mountains 6.9%, plateaus 2.4%, basins 0.7%, islands 0%, water 2.8%, void 0%.
- Placeness: biome regions 2.6/km² (11 major, largest 52.6%), landform regions 12.9/km² (largest 7.5%), dominant place 52.6% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0%, surface 100%, underground 98.9%, roof 100%.
- Overhangs in 23.8% of columns, stacked layers in 0%, cave pockets in 16.3%.

## Places

- **surface** (279 cells, 8 types, silhouette 0.388):
  - type 1: 33.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49 — camera [104.5,52,455.5]
  - type 2: 23.7% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 49 — camera [152.5,42,-7.5]
  - type 3: 13.6% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 51; relief over the patch 85.2 (layer 94.3); bare rock 89% (layer 25%) — camera [-983.5,47,-824.5]
  - type 4: 8.2% of cells; biome soul_sand_valley; ground netherrack; top netherrack; height 73; neighbour step 6.9 (layer 3.7); bare rock 51% (layer 25%); overhangs 51% (layer 24%); cave pockets 32% (layer 16%) — camera [360.5,47,407.5]
  - type 5: 7.2% of cells; biome adventure:gravel_desert; ground bone_block; top bone_block; height 53; relief over the patch 82.3 (layer 94.3); overhangs 12% (layer 24%) — camera [888.5,44,88.5]
  - type 6: 5.7% of cells; biome soul_sand_valley; ground soul_sand; top lava; height 22; neighbour step 5.4 (layer 3.7); relief over 16 blocks 100.5 (layer 74.4); relief over the patch 111.7 (layer 94.3); liquid cover 76% (layer 7%); cave pockets 1% (layer 16%) — camera [424.5,20,568.5]
  - type 7: 4.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 46; neighbour step 1.3 (layer 3.7); relief over the patch 78.1 (layer 94.3); trees per cell 7.4 (layer 0.4); canopy 4% (layer 0%); ground plants 7% (layer 1%); bare rock 1% (layer 25%); overhangs 5% (layer 24%); cave pockets 4% (layer 16%) — camera [-935.5,61,184.5]
  - type 8: 4.3% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 51; neighbour step 6.5 (layer 3.7); ground plants 4% (layer 1%); stacked layers 0% (layer 0%) — camera [680.5,56,440.5]
  - landmark at [-1000,728]: biome adventure:mycotoxic_undergrowth (type adventure:withered_forest); ground moss_block (type soul_soil); top moss_block (type soul_soil); canopy 2% (type 5%); stacked layers 0% (type 0%); trees per cell 5.0 (type 8.0)
  - landmark at [-1000,744]: biome adventure:mycotoxic_undergrowth (type soul_sand_valley); ground slime_block (type soul_sand); top slime_block (type soul_sand); overhangs 66% (type 14%); notable-block density (log) 2.2 (type 0.7); relief over the patch 82.0 (type 102.0)
  - landmark at [776,-648]: biome basalt_deltas (type soul_sand_valley); ground basalt (type netherrack); top basalt (type netherrack); cave pockets 51% (type 12%); overhangs 80% (type 47%); height 60.1 (type 79.7)
  - landmark at [-984,744]: biome adventure:mycotoxic_undergrowth (type adventure:gravel_desert); ground moss_block (type bone_block); top moss_block (type bone_block); trees per cell 3.0 (type 0.0); ground plants 2% (type 0%); canopy 1% (type 0%)
  - landmark at [472,-824]: biome adventure:blackstone_basin (type soul_sand_valley); ground blackstone (type soul_sand); neighbour step 17.4 (type 6.5); liquid cover 45% (type 88%); ground plants 6% (type 0%)
- **sky**: no cells.
- **underground** (279 cells, 3 types, silhouette 0.198):
  - type 1: 43.7% of cells; notableId magma_block; biome soul_sand_valley; height 57; neighbour step 5.2 (layer 3.7); cave air 21% (layer 13%) — camera [356.5,45.88,417.5]
  - type 2: 33.7% of cells; notableId magma_block; biome adventure:ash_barrens; height 41; cave air 6% (layer 13%); cave pockets 2% (layer 16%) — camera [889.5,47.88,82.5]
  - type 3: 22.6% of cells; notableId lava; biome deep_dark; height 50; cave liquid 2% (layer 1%); cave pockets 32% (layer 16%) — camera [-552.5,28.88,-59.5]
  - landmark at [472,-824]: notableId gilded_blackstone (type magma_block); biome adventure:blackstone_basin (type none); neighbour step 17.4 (type 2.0); cave air 65% (type 2%); relief over 16 blocks 101.0 (type 74.0)
  - landmark at [456,584]: notableId lava (type magma_block); layer present in 66% (type 100%); cave liquid 11% (type 0%); cave air 63% (type 2%)
  - landmark at [472,-808]: biome adventure:blackstone_basin (type none); cave air 65% (type 2%); neighbour step 7.3 (type 2.0); relief over 16 blocks 103.0 (type 74.0)
  - landmark at [-408,-680]: notableId basalt (type lava); biome adventure:ash_barrens (type deep_dark); notable-block density (log) 3.1 (type 1.1); neighbour step 5.9 (type 1.2); cave liquid 4% (type 1%)
  - landmark at [248,-264]: notableId soul_sand (type magma_block); layer present in 31% (type 100%); neighbour step 13.2 (type 4.4); cave liquid 7% (type 0%)
- **roof** (279 cells, 5 types, silhouette 0.308):
  - type 1: 32.6% of cells; notableId nether_quartz_ore; height 99; neighbour step 0.4 (layer 0.8); notable-block density (log) 0.3 (layer 0.7) — camera [-711.5,94.88,568.5]
  - type 2: 21.1% of cells; notableId soul_soil; height 99; notable-block density (log) 1.3 (layer 0.7) — camera [104.5,93.88,456.5]
  - type 3: 17.9% of cells; notableId nether_quartz_ore; height 103; neighbour step 1.9 (layer 0.8); relief over 16 blocks 28.9 (layer 20.7); notable-block density (log) 0.3 (layer 0.7) — camera [-183.5,91.88,456.5]
  - type 4: 16.1% of cells; notableId soul_sand; height 98; notable-block density (log) 2.0 (layer 0.7) — camera [-807.5,90.88,-439.5]
  - type 5: 12.2% of cells; notableId nether_gold_ore; height 99; notable-block density (log) 0.2 (layer 0.7) — camera [-183.5,89.88,424.5]
  - landmark at [-408,-424]: notableId basalt (type nether_quartz_ore); relief over 16 blocks 97.0 (type 27.0); height 95.7 (type 102.7); notable-block density (log) 0.6 (type 0.2)
  - landmark at [-824,-440]: notableId basalt (type nether_quartz_ore); relief over 16 blocks 102.0 (type 27.0); height 96.7 (type 102.7); neighbour step 1.9 (type 1.7)
  - landmark at [-376,-440]: notableId basalt (type soul_sand); relief over 16 blocks 70.0 (type 19.0); height 102.4 (type 97.0); notable-block density (log) 2.0 (type 2.1)
  - landmark at [472,-824]: notableId gilded_blackstone (type nether_quartz_ore); height 117.9 (type 102.7); neighbour step 1.3 (type 1.7); notable-block density (log) 0.0 (type 0.2)
  - landmark at [-232,552]: notableId basalt (type nether_gold_ore); layer present in 100% (type 100%); relief over 16 blocks 65.0 (type 22.0); notable-block density (log) 0.4 (type 0.1)

## Biome expression

9 of 9 placed biomes are expressed.

- minecraft:soul_sand_valley: expressed, 45345 columns; placement land 93.1%, lava 6.9%, roofed 100%; snow 0%, canopy 0%, trees 0.5/ha; top soul_sand 42.5%, soul_soil 33.5%, netherrack 11.3%
- adventure:ash_barrens: expressed, 9873 columns; placement land 97.2%, lava 2.8%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top tuff 77.1%, netherrack 9.6%, smooth_basalt 3.3%
- adventure:gravel_desert: expressed, 4721 columns; placement land 97.1%, lava 2.9%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top bone_block 83.8%, netherrack 9.5%, lava 2.9%
- adventure:withered_forest: expressed, 2729 columns; placement land 99.2%, lava 0.8%, roofed 100%; snow 0%, canopy 5.1%, trees 328.7/ha; top soul_soil 84.8%, dead_bush 3.6%, wither_rose 2.9%
- adventure:blackstone_basin: expressed, 2257 columns; placement land 56.8%, lava 43.2%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top blackstone 49.7%, lava 43.2%, sculk_vein 3.2%
- adventure:quartz_flats: expressed, 2250 columns; placement land 99.1%, lava 0.6%, roofed 99.7%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 77.5%, glowstone 8.7%, smooth_quartz_slab 5.6%
- adventure:mycotoxic_undergrowth: expressed, 1985 columns; placement land 98.9%, lava 1%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0.2%, trees 61.1/ha; top moss_block 32.2%, slime_block 30.4%, netherrack 26.1%
- minecraft:basalt_deltas: expressed, 1679 columns; placement land 70.8%, lava 29.2%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top basalt 53.8%, lava 29.2%, magma_block 8.6%
- minecraft:deep_dark: expressed, 600 columns; placement land 79.5%, lava 20.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 32.7%, sculk_vein 27.7%, lava 20.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:deep_dark | minecraft:deep_dark | 0.8 | 600 | expressed | - |
| O2 | betternether:bone_reef | adventure:gravel_desert | 6.6 | 4721 | expressed | - |
| O3 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 63.5 | 45345 | expressed | - |
| O4 | betternether:soul_plain | minecraft:soul_sand_valley | 63.5 | 45345 | expressed | - |
| O5 | betternether:sulfuric_bone_reef | adventure:quartz_flats | 3.1 | 2250 | expressed | - |
| O6 | incendium:ash_barrens | adventure:ash_barrens | 13.8 | 9873 | expressed | - |
| O7 | regions_unexplored:blackstone_basin | adventure:blackstone_basin | 3.2 | 2257 | expressed | - |
| O8 | minecraft:basalt_deltas | minecraft:basalt_deltas | 2.3 | 1679 | expressed | - |
| O9 | regions_unexplored:ancient_delta | adventure:ash_barrens | 13.8 | 9873 | expressed | - |
| O10 | incendium:toxic_heap | adventure:mycotoxic_undergrowth | 2.8 | 1985 | expressed | - |
| O11 | incendium:withered_forest | adventure:withered_forest | 3.8 | 2729 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:soul_sand_valley 63.5%, adventure:ash_barrens 13.8%, adventure:gravel_desert 6.6% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 7.3% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.2% (`surface.canopyPercent`) | |
| M:relief | median 97.0 blocks a patch; height {"p5": 18.0, "p25": 41.0, "p50": 46.0, "p75": 53.0, "p95": 111.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 98.9, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#D8C8A0", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_boneyard-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 5.8%; near: bone_block 8.6%, nether_bricks 5.2%; mid: nether_bricks 23.5%, netherrack 21.1%; far: soul_soil 3.8%, soul_sand 3.4%; biomes: adventure:gravel_desert 89.5%, soul_sand_valley 9.4%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-far.png`

### the_boneyard-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 5.8%; near: bone_block 8.6%, nether_bricks 5.2%; mid: nether_bricks 23.5%, netherrack 21.1%; far: soul_soil 3.8%, soul_sand 3.4%; biomes: adventure:gravel_desert 89.5%, soul_sand_valley 9.4%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-far-nv.png`

### the_boneyard-surface-t1

Picked for: surface type 1: 33.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49
Census: sky 1.6%; unloaded 4%; near: soul_sand 35.2%, soul_soil 8.1%; mid: soul_sand 14.9%, soul_soil 9.4%; far: soul_soil 9.6%, soul_sand 8.7%; biomes: soul_sand_valley 99.9%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t1.png`

### the_boneyard-surface-t1-nv

Picked for: surface type 1: 33.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49
Census: sky 1.6%; unloaded 2.8%; near: soul_sand 35.2%, soul_soil 8.1%; mid: soul_sand 14.9%, soul_soil 9.4%; far: soul_soil 10.2%, soul_sand 9%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t1-nv.png`

### the_boneyard-underground-t1

Picked for: underground type 1: 43.7% of cells; notableId magma_block; biome soul_sand_valley; height 57; neighbour step 5.2 (layer 3.7); cave air 21% (layer 13%)
Census: sky 4.2%; near: soul_sand 48.5%, bone_block 3.7%; mid: soul_sand 5%, soul_soil 4.5%; far: soul_sand 14.1%, soul_soil 13.9%; biomes: soul_sand_valley 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-underground-t1.png`

### the_boneyard-underground-t1-nv

Picked for: underground type 1: 43.7% of cells; notableId magma_block; biome soul_sand_valley; height 57; neighbour step 5.2 (layer 3.7); cave air 21% (layer 13%)
Census: sky 4.2%; near: soul_sand 48.5%, bone_block 3.7%; mid: soul_sand 4.9%, soul_soil 4.5%; far: soul_sand 14.1%, soul_soil 13.9%; biomes: soul_sand_valley 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-underground-t1-nv.png`

### the_boneyard-roof-t1

Picked for: roof type 1: 32.6% of cells; notableId nether_quartz_ore; height 99; neighbour step 0.4 (layer 0.8); notable-block density (log) 0.3 (layer 0.7)
Census: sky 0%; unloaded 3.7%; near: netherrack 74.5%, basalt 2.2%; mid: netherrack 8.3%, basalt 6%; far: basalt 1.5%; biomes: adventure:ash_barrens 89.8%, basalt_deltas 10%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-roof-t1.png`

### the_boneyard-roof-t1-nv

Picked for: roof type 1: 32.6% of cells; notableId nether_quartz_ore; height 99; neighbour step 0.4 (layer 0.8); notable-block density (log) 0.3 (layer 0.7)
Census: sky 0%; unloaded 3.6%; near: netherrack 74.5%, basalt 2.2%; mid: netherrack 8.3%, basalt 6%; far: basalt 1.5%; biomes: adventure:ash_barrens 89.8%, basalt_deltas 10%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-roof-t1-nv.png`

### the_boneyard-surface-t2

Picked for: surface type 2: 23.7% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 49
Census: sky 0.3%; unloaded 1.4%; near: soul_soil 43.5%, nether_bricks 19.9%; mid: soul_soil 6%, nether_bricks 4.3%; far: soul_sand 8.3%, soul_soil 6%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t2.png`

### the_boneyard-surface-t2-nv

Picked for: surface type 2: 23.7% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 49
Census: sky 0.5%; unloaded 1%; near: soul_soil 43.5%, nether_bricks 19.9%; mid: soul_soil 6%, nether_bricks 4.3%; far: soul_sand 8.4%, soul_soil 6.1%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t2-nv.png`

### the_boneyard-surface-t7

Picked for: surface type 7: 4.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 46; neighbour step 1.3 (layer 3.7); relief over the patch 78.1 (layer 94.3); trees per cell 7.4 (layer 0.4); canopy 4% (layer 0%); ground plants 7% (layer 1%); bare rock 1% (layer 25%); overhangs 5% (layer 24%); cave pockets 4% (layer 16%)
Census: sky 0%; unloaded 6.7%; near: soul_soil 32.3%, stripped_warped_hyphae 9.2%; mid: netherrack 7%, soul_soil 5.8%; far: netherrack 7.2%, soul_soil 4.7%; biomes: adventure:withered_forest 78.1%, soul_sand_valley 21.9%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t7.png`

### the_boneyard-surface-t7-nv

Picked for: surface type 7: 4.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 46; neighbour step 1.3 (layer 3.7); relief over the patch 78.1 (layer 94.3); trees per cell 7.4 (layer 0.4); canopy 4% (layer 0%); ground plants 7% (layer 1%); bare rock 1% (layer 25%); overhangs 5% (layer 24%); cave pockets 4% (layer 16%)
Census: sky 0%; unloaded 6.5%; near: soul_soil 32.3%, stripped_warped_hyphae 9.2%; mid: netherrack 7%, soul_soil 5.8%; far: netherrack 7.3%, soul_soil 4.8%; biomes: adventure:withered_forest 78%, soul_sand_valley 22%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t7-nv.png`

### the_boneyard-surface-lm1

Picked for: surface landmark: biome adventure:mycotoxic_undergrowth (type adventure:withered_forest); ground moss_block (type soul_soil); top moss_block (type soul_soil); canopy 2% (type 5%); stacked layers 0% (type 0%); trees per cell 5.0 (type 8.0)
Census: sky 0%; unloaded 0.5%; near: moss_block 37.6%, soul_soil 12.4%; mid: netherrack 3.8%; far: netherrack 12.8%, cave_vines_plant 1.1%; biomes: adventure:mycotoxic_undergrowth 53.1%, adventure:withered_forest 46.6%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-lm1.png`

### the_boneyard-surface-lm1-nv

Picked for: surface landmark: biome adventure:mycotoxic_undergrowth (type adventure:withered_forest); ground moss_block (type soul_soil); top moss_block (type soul_soil); canopy 2% (type 5%); stacked layers 0% (type 0%); trees per cell 5.0 (type 8.0)
Census: sky 0%; unloaded 0.4%; near: moss_block 37.6%, soul_soil 12.4%; mid: netherrack 3.8%; far: netherrack 12.7%, cave_vines_plant 1.1%; biomes: adventure:mycotoxic_undergrowth 53%, adventure:withered_forest 46.6%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
