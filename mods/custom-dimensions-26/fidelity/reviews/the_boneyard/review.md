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

Dossier: `/tmp/claude-0/w10c2/runs/fin/the_boneyard/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/fin/the_boneyard/layers`

## Summary

- Top blocks seen from the sky: soul_sand 29.1%, soul_soil 24.6%, tuff 11.6%, netherrack 10.6%.
- Surface biomes: soul_sand_valley 66.1%, adventure:ash_barrens 14.8%, adventure:gravel_desert 8.3%, adventure:mycotoxic_undergrowth 3.2%, adventure:quartz_flats 3.2%.
- Ground blocks: soul_sand 30%, soul_soil 25.3%, netherrack 14.3%, tuff 11.6%.
- Liquid cover 0% water, 6% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 24.8%; no ground (void) 0%.
- Trees: 6.3 per ha (warped_stem 6.3/ha, mean spacing 3.8 blocks); ground plants other 0.4%, bush 0.1%, grass 0%.
- Surface height p5 19, median 46, p95 111, max 127; median relief per patch 97 blocks; slopes under 15° 46.7%, over 45° 27.1%, cliffs 14.5%; above the clouds 0%, below sea level 6.3%.
- Underground: 15.1% open, 0.9% liquid; cave biomes soul_sand_valley 68.1%, adventure:ash_barrens 13%, adventure:gravel_desert 7.1%, adventure:mycotoxic_undergrowth 4.6%.
- Notable clusters, fewest first: nether_bricks 2 clusters, spacing n/a; sculk_sensor 2 clusters, spacing n/a; calcite 3 clusters, spacing n/a; amethyst_block 3 clusters, spacing n/a; small_amethyst_bud 3 clusters, spacing 1.4; medium_amethyst_bud 3 clusters, spacing 2.
- 6 of 8 placed biomes are expressed; 1 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 82.3%, biome match 97.1%.
- Landforms over the border (noise render): plains 50.5%, hills 36.6%, mountains 6.9%, plateaus 2.4%, basins 0.7%, islands 0%, water 2.8%, void 0%.
- Placeness: biome regions 2.4/km² (10 major, largest 53.4%), landform regions 12.9/km² (largest 7.5%), dominant place 53.4% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 100%.
- Overhangs in 23.2% of columns, stacked layers in 0%, cave pockets in 16.1%.

## Places

- **surface** (279 cells, 7 types, silhouette 0.414):
  - type 1: 32.6% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49 — camera [104.5,52,455.5]
  - type 2: 26.5% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 48; bare rock 8% (layer 25%) — camera [-935.5,61,184.5]
  - type 3: 15.1% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 49; bare rock 88% (layer 25%) — camera [760.5,48,-631.5]
  - type 4: 9.0% of cells; biome soul_sand_valley; ground netherrack; top netherrack; height 73; neighbour step 7.2 (layer 3.6); canopy 0% (layer 0%); bare rock 52% (layer 25%); overhangs 50% (layer 23%); cave pockets 32% (layer 16%) — camera [360.5,47,407.5]
  - type 5: 8.2% of cells; biome adventure:gravel_desert; ground bone_block; top bone_block; height 51; relief over the patch 84.3 (layer 93.5) — camera [888.5,44,88.5]
  - type 6: 5.0% of cells; biome soul_sand_valley; ground netherrack; top lava; height 22; relief over 16 blocks 86.4 (layer 72.9); relief over the patch 104.7 (layer 93.5); liquid cover 79% (layer 6%); bare rock 8% (layer 25%); cave pockets 1% (layer 16%) — camera [424.5,20,568.5]
  - type 7: 3.6% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 53; neighbour step 7.8 (layer 3.6); stacked layers 0% (layer 0%) — camera [680.5,56,440.5]
  - landmark at [-1000,744]: biome adventure:mycotoxic_undergrowth (type soul_sand_valley); ground slime_block (type soul_sand); top slime_block (type soul_sand); trees per cell 2.0 (type 0.0); overhangs 67% (type 14%); notable-block density (log) 2.4 (type 0.7)
  - landmark at [440,-840]: biome adventure:blackstone_basin (type adventure:ash_barrens); ground blackstone (type tuff); top blackstone (type tuff); relief over 16 blocks 9.0 (type 62.0); overhangs 2% (type 44%); liquid cover 22% (type 1%)
  - landmark at [456,-840]: biome adventure:blackstone_basin (type adventure:ash_barrens); ground blackstone (type tuff); top blackstone (type tuff); relief over 16 blocks 11.0 (type 62.0); overhangs 13% (type 44%); liquid cover 26% (type 1%)
  - landmark at [-1000,728]: biome adventure:mycotoxic_undergrowth (type adventure:gravel_desert); ground slime_block (type bone_block); top slime_block (type bone_block); trees per cell 3.0 (type 0.0); overhangs 58% (type 8%); ground plants 4% (type 0%)
  - landmark at [456,-824]: biome adventure:blackstone_basin (type adventure:gravel_desert); ground blackstone (type bone_block); top blackstone (type bone_block); bare rock 62% (type 0%); liquid cover 37% (type 2%); relief over the patch 99.0 (type 86.0)
- **sky**: no cells.
- **underground** (279 cells, 8 types, silhouette 0.229):
  - type 1: 19.0% of cells; notableId magma_block; biome soul_sand_valley; height 50; neighbour step 2.0 (layer 3.6); cave air 6% (layer 13%) — camera [709.5,48.88,-312.5]
  - type 2: 18.6% of cells; notableId magma_block; biome adventure:ash_barrens; height 46; neighbour step 1.8 (layer 3.6); cave air 2% (layer 13%); cave pockets 2% (layer 16%) — camera [-714.5,46.88,571.5]
  - type 3: 16.8% of cells; notableId magma_block; biome soul_sand_valley; height 67; neighbour step 6.2 (layer 3.6); notable-block density (log) 1.5 (layer 1.1); cave air 25% (layer 13%); cave pockets 37% (layer 16%) — camera [562.5,56.88,152.5]
  - type 4: 13.6% of cells; notableId magma_block; biome adventure:ash_barrens; height 54 — camera [-971.5,51.88,-819.5]
  - type 5: 11.1% of cells; notableId blackstone; biome soul_sand_valley; height 41; notable-block density (log) 0.8 (layer 1.1) — camera [41.5,78.88,683.5]
  - type 6: 7.5% of cells; notableId lava; biome soul_sand_valley; height 20; neighbour step 2.2 (layer 3.6); relief over 16 blocks 86.8 (layer 72.7); notable-block density (log) 0.0 (layer 1.1); cave pockets 0% (layer 16%) — camera [237.5,19.88,-226.5]
  - type 7: 7.2% of cells; notableId lava; biome soul_sand_valley; height 50; relief over 16 blocks 82.5 (layer 72.7); notable-block density (log) 1.9 (layer 1.1); cave liquid 4% (layer 1%); cave pockets 40% (layer 16%) — camera [126.5,46.88,449.5]
  - type 8: 6.1% of cells; notableId lava; biome soul_sand_valley; height 61; layer present in 100% (layer 100%); neighbour step 7.8 (layer 3.6); relief over 16 blocks 87.7 (layer 72.7); cave air 44% (layer 13%) — camera [-163.5,30.88,440.5]
  - landmark at [776,-648]: biome adventure:ash_barrens (type soul_sand_valley); cave air 38% (type 5%); neighbour step 6.6 (type 2.3); relief over 16 blocks 99.0 (type 73.0)
  - landmark at [456,-840]: notableId gilded_blackstone (type blackstone); biome adventure:blackstone_basin (type soul_sand_valley); relief over 16 blocks 11.0 (type 75.0); notable-block density (log) 0.1 (type 0.5); cave air 0% (type 4%)
  - landmark at [-440,-680]: biome adventure:ash_barrens (type soul_sand_valley); cave air 39% (type 5%); neighbour step 8.0 (type 2.3); cave liquid 8% (type 5%)
  - landmark at [344,424]: notableId soul_sand (type lava); notable-block density (log) 3.8 (type 0.7); height 103.1 (type 56.9); cave air 53% (type 48%)
  - landmark at [-808,-424]: notableId soul_soil (type magma_block); cave pockets 79% (type 34%); notable-block density (log) 2.6 (type 1.4); cave liquid 3% (type 1%)
- **roof** (279 cells, 7 types, silhouette 0.342):
  - type 1: 28.7% of cells; notableId nether_quartz_ore; height 98; notable-block density (log) 0.3 (layer 0.7) — camera [264.5,92.88,-231.5]
  - type 2: 19.4% of cells; notableId soul_soil; height 99; notable-block density (log) 1.4 (layer 0.7) — camera [104.5,93.88,456.5]
  - type 3: 16.5% of cells; notableId nether_quartz_ore; height 102; neighbour step 1.9 (layer 0.8); relief over 16 blocks 28.7 (layer 20.1); notable-block density (log) 0.2 (layer 0.7) — camera [-183.5,91.88,456.5]
  - type 4: 15.4% of cells; notableId soul_sand; height 98; notable-block density (log) 2.0 (layer 0.7) — camera [-807.5,90.88,-439.5]
  - type 5: 9.0% of cells; notableId nether_gold_ore; height 102; notable-block density (log) 0.1 (layer 0.7) — camera [-823.5,93.88,-423.5]
  - type 6: 6.8% of cells; notableId bone_block; height 97; neighbour step 0.4 (layer 0.8) — camera [936.5,89.88,952.5]
  - type 7: 4.3% of cells; notableId cave_vines_plant; height 102 — camera [-983.5,95.88,744.5]
  - landmark at [-408,-424]: notableId basalt (type nether_quartz_ore); relief over 16 blocks 97.0 (type 27.0); height 95.7 (type 102.7); notable-block density (log) 0.5 (type 0.2)
  - landmark at [-824,-440]: notableId basalt (type nether_quartz_ore); relief over 16 blocks 102.0 (type 27.0); height 96.7 (type 102.7); neighbour step 1.9 (type 1.7)
  - landmark at [376,632]: neighbour step 3.1 (type 0.4); height 108.5 (type 97.0); notable-block density (log) 3.1 (type 2.2)
  - landmark at [-760,-232]: neighbour step 2.3 (type 0.6); notable-block density (log) 2.8 (type 0.8); height 106.1 (type 98.3)
  - landmark at [344,424]: height 118.3 (type 99.8); neighbour step 1.2 (type 0.6); notable-block density (log) 0.0 (type 0.1)

## Biome expression

6 of 8 placed biomes are expressed.

- minecraft:soul_sand_valley: expressed, 47247 columns; placement land 96.6%, lava 3.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 43.9%, soul_soil 36.7%, netherrack 11.5%
- adventure:ash_barrens: expressed, 10604 columns; placement land 98.6%, lava 1.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top tuff 77.9%, netherrack 10.5%, smooth_basalt 4.6%
- adventure:gravel_desert: expressed, 5894 columns; placement land 85.8%, lava 14.2%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top bone_block 75.1%, lava 14.2%, netherrack 7.3%
- adventure:mycotoxic_undergrowth: expressed, 2304 columns; placement land 99%, lava 1%, roofed 100%; snow 0%, canopy 0%, trees 61.4/ha; top moss_block 33.7%, slime_block 33.4%, netherrack 22.8%
- adventure:quartz_flats: expressed, 2280 columns; placement land 99.1%, lava 0.5%, roofed 99.6%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 80.1%, glowstone 11.1%, smooth_quartz_slab 5.1%
- adventure:blackstone_basin: expressed, 2136 columns; placement land 65.6%, lava 34.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top blackstone 60.8%, lava 34.4%, polished_blackstone 3.7%
- minecraft:deep_dark: placed, not expressed, 962 columns; placement land 0.4%, lava 99.6%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top lava 99.6%, sculk_vein 0.4%; **mostly lava**
- adventure:withered_forest: too few columns to judge, 16 columns; placement land 100%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top soul_soil 93.8%, soul_fire 6.3%

Listed in the definition, found in no sampled column: minecraft:basalt_deltas.


v2 biomes not found in any sampled column: minecraft:basalt_deltas

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:deep_dark | minecraft:deep_dark | 1.3 | 962 | placed, not expressed | mostly lava |
| O2 | betternether:bone_reef | adventure:gravel_desert | 8.3 | 5894 | expressed | - |
| O3 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 66.1 | 47247 | expressed | - |
| O4 | betternether:soul_plain | minecraft:soul_sand_valley | 66.1 | 47247 | expressed | - |
| O5 | betternether:sulfuric_bone_reef | adventure:quartz_flats | 3.2 | 2280 | expressed | - |
| O6 | incendium:ash_barrens | adventure:ash_barrens | 14.8 | 10604 | expressed | - |
| O7 | regions_unexplored:blackstone_basin | adventure:blackstone_basin | 3.0 | 2136 | expressed | - |
| O8 | minecraft:basalt_deltas | minecraft:basalt_deltas | 0.0 | 0 | listed, not found | in the v2 definition, in no sampled column |
| O9 | regions_unexplored:ancient_delta | adventure:ash_barrens | 14.8 | 10604 | expressed | - |
| O10 | incendium:toxic_heap | adventure:mycotoxic_undergrowth | 3.2 | 2304 | expressed | - |
| O11 | incendium:withered_forest | adventure:withered_forest | 0.0 | 16 | too few columns to judge | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:soul_sand_valley 66.1%, adventure:ash_barrens 14.8%, adventure:gravel_desert 8.3% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 6.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 97.0 blocks a patch; height {"p5": 19.0, "p25": 41.0, "p50": 46.0, "p75": 53.0, "p95": 111.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 100.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#D8C8A0", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 72.7%
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_boneyard-surface-t1

Picked for: surface type 1: 32.6% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49
Census: sky 2%; unloaded 2.5%; near: soul_sand 35.2%, soul_soil 8.1%; mid: soul_sand 14.9%, soul_soil 9.4%; far: soul_soil 10.5%, soul_sand 8.8%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t1.png`

### the_boneyard-surface-t1-nv

Picked for: surface type 1: 32.6% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 49
Census: sky 2%; unloaded 2.1%; near: soul_sand 35.2%, soul_soil 8.1%; mid: soul_sand 14.9%, soul_soil 9.4%; far: soul_soil 10.6%, soul_sand 9%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t1-nv.png`

### the_boneyard-underground-t3

Picked for: underground type 3: 16.8% of cells; notableId magma_block; biome soul_sand_valley; height 67; neighbour step 6.2 (layer 3.6); notable-block density (log) 1.5 (layer 1.1); cave air 25% (layer 13%); cave pockets 37% (layer 16%)
Census: sky 1.8%; unloaded 3.5%; near: soul_sand 40.1%, soul_soil 7.2%; mid: soul_sand 10.4%, soul_soil 5%; far: soul_sand 8.3%, soul_soil 6.9%; biomes: soul_sand_valley 96.3%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-underground-t3.png`

### the_boneyard-underground-t3-nv

Picked for: underground type 3: 16.8% of cells; notableId magma_block; biome soul_sand_valley; height 67; neighbour step 6.2 (layer 3.6); notable-block density (log) 1.5 (layer 1.1); cave air 25% (layer 13%); cave pockets 37% (layer 16%)
Census: sky 1.8%; unloaded 3%; near: soul_sand 40.1%, soul_soil 7.2%; mid: soul_sand 10.4%, soul_soil 5%; far: soul_sand 8.4%, soul_soil 6.9%; biomes: soul_sand_valley 95.8%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-underground-t3-nv.png`

### the_boneyard-roof-t1

Picked for: roof type 1: 28.7% of cells; notableId nether_quartz_ore; height 98; notable-block density (log) 0.3 (layer 0.7)
Census: sky 5.1%; unloaded 0.7%; near: soul_sand 64.1%, soul_soil 13.2%; mid: soul_soil 7%, soul_sand 6.9%; far: basalt 1.1%, soul_soil 1%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-roof-t1.png`

### the_boneyard-roof-t1-nv

Picked for: roof type 1: 28.7% of cells; notableId nether_quartz_ore; height 98; notable-block density (log) 0.3 (layer 0.7)
Census: sky 5.2%; unloaded 0.6%; near: soul_sand 64.1%, soul_soil 13.2%; mid: soul_soil 7%, soul_sand 6.9%; far: basalt 1.1%, soul_soil 1%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-roof-t1-nv.png`

### the_boneyard-surface-t2

Picked for: surface type 2: 26.5% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 48; bare rock 8% (layer 25%)
Census: sky 0%; unloaded 11.4%; near: soul_soil 32.9%, soul_sand 11.8%; mid: soul_soil 9%, soul_sand 8.9%; far: soul_soil 6%, netherrack 5.3%; biomes: soul_sand_valley 86.6%, adventure:withered_forest 13.4%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t2.png`

### the_boneyard-surface-t2-nv

Picked for: surface type 2: 26.5% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 48; bare rock 8% (layer 25%)
Census: sky 0%; unloaded 9.7%; near: soul_soil 32.9%, soul_sand 11.8%; mid: soul_soil 9%, soul_sand 8.9%; far: soul_soil 6.3%, netherrack 6.1%; biomes: soul_sand_valley 85.9%, adventure:withered_forest 14.1%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t2-nv.png`

### the_boneyard-surface-t4

Picked for: surface type 4: 9.0% of cells; biome soul_sand_valley; ground netherrack; top netherrack; height 73; neighbour step 7.2 (layer 3.6); canopy 0% (layer 0%); bare rock 52% (layer 25%); overhangs 50% (layer 23%); cave pockets 32% (layer 16%)
Census: sky 0.7%; unloaded 4.7%; near: soul_soil 46.9%, basalt 2.9%; mid: soul_sand 5.2%, soul_soil 4.4%; far: soul_soil 15.5%, soul_sand 13%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t4.png`

### the_boneyard-surface-t4-nv

Picked for: surface type 4: 9.0% of cells; biome soul_sand_valley; ground netherrack; top netherrack; height 73; neighbour step 7.2 (layer 3.6); canopy 0% (layer 0%); bare rock 52% (layer 25%); overhangs 50% (layer 23%); cave pockets 32% (layer 16%)
Census: sky 1.1%; unloaded 4.3%; near: soul_soil 46.9%, basalt 2.9%; mid: soul_sand 5.2%, soul_soil 4.4%; far: soul_soil 15.5%, soul_sand 13%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-t4-nv.png`

### the_boneyard-surface-lm1

Picked for: surface landmark: biome adventure:mycotoxic_undergrowth (type soul_sand_valley); ground slime_block (type soul_sand); top slime_block (type soul_sand); trees per cell 2.0 (type 0.0); overhangs 67% (type 14%); notable-block density (log) 2.4 (type 0.7)
Census: sky 0%; near: moss_block 37.7%, slime_block 36.2%; mid: slime_block 1%; biomes: adventure:mycotoxic_undergrowth 99.1%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-lm1.png`

### the_boneyard-surface-lm1-nv

Picked for: surface landmark: biome adventure:mycotoxic_undergrowth (type soul_sand_valley); ground slime_block (type soul_sand); top slime_block (type soul_sand); trees per cell 2.0 (type 0.0); overhangs 67% (type 14%); notable-block density (log) 2.4 (type 0.7)
Census: sky 0%; near: moss_block 37.7%, slime_block 36.2%; mid: slime_block 1%; biomes: adventure:mycotoxic_undergrowth 99.1%
Image: `/tmp/claude-0/w10c2/reviews/the_boneyard/screens/the_boneyard-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
