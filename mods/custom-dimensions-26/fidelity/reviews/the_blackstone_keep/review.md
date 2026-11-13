# Review packet: the_blackstone_keep

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_blackstone_keep.json`

> The piglin military frontier. Bastions and blackstone walls from spawn, fortified against something that came from below. The deeper you dig, the harder it fights back — as if the keep itself remembers the threat.

Biomes: minecraft:soul_sand_valley, regions_unexplored:blackstone_basin, incendium:ash_barrens, minecraft:basalt_deltas, minecraft:nether_wastes, betternether:crimson_glowing_woods, minecraft:crimson_forest, betternether:wart_forest, betternether:wart_forest_edge

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_blackstone_keep.json`

> The piglin military frontier. Bastions and blackstone walls from spawn, fortified against something that came from below. The deeper you dig, the harder it fights back — as if the keep itself remembers the threat.

Biomes: adventure:blackstone_basin, minecraft:basalt_deltas, minecraft:soul_sand_valley, adventure:ash_barrens, adventure:crimson_pinewood, minecraft:crimson_forest

Terrain: `{"scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/r3/full/the_blackstone_keep/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/r3/full/the_blackstone_keep/layers`

## Summary

- Top blocks seen from the sky: blackstone 37.8%, lava 19.6%, tuff 10.5%, netherrack 6.6%.
- Surface biomes: adventure:blackstone_basin 55.4%, adventure:ash_barrens 15.1%, crimson_forest 10.1%, basalt_deltas 8%, soul_sand_valley 7.7%.
- Ground blocks: blackstone 52.2%, tuff 10.5%, netherrack 8.2%, crimson_nylium 7.5%.
- Liquid cover 0% water, 19.6% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 3.7%; bare rock 59.9%; no ground (void) 0%.
- Trees: 32.6 per ha (crimson_stem 32.6/ha, mean spacing 3.4 blocks); ground plants grass 1.2%, mushroom 0.2%, other 0.1%.
- Surface height p5 21, median 65, p95 113, max 127; median relief per patch 101 blocks; slopes under 15° 19.9%, over 45° 53%, cliffs 24.9%; above the clouds 0%, below sea level 15.8%.
- Underground: 22.2% open, 3.6% liquid; cave biomes adventure:blackstone_basin 52%, adventure:ash_barrens 16.2%, crimson_forest 14.4%, soul_sand_valley 10.2%.
- Notable clusters, fewest first: red_mushroom 7 clusters, spacing 3.9; soul_fire 9 clusters, spacing 6; warped_fungus 13 clusters, spacing 15.3; brown_mushroom 22 clusters, spacing 3.6; glowstone 31 clusters, spacing 25.5; fire 32 clusters, spacing 3.8.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 936 columns: height within 2 blocks 65%, biome match 100%.
- Landforms over the border (noise render): plains 37.2%, hills 25.1%, mountains 12.9%, plateaus 6%, basins 9.4%, islands 0.2%, water 9.2%, void 0%.
- Placeness: biome regions 2.1/km² (8 major, largest 54.6%), landform regions 12.2/km² (largest 11.0%), dominant place 54.6% of the border.

## Layers

- Columns by class: void 0%, roofed 97.5%, stacked 0%, overhang 0%, cave 0%, single 2.5%.
- Columns with each layer: sky 4.2%, surface 100%, underground 98.9%, roof 97.5%.
- Overhangs in 30.1% of columns, stacked layers in 4.2%, cave pockets in 39.5%.

## Places

- **surface** (234 cells, 7 types, silhouette 0.407):
  - type 1: 47.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 65 — camera [-582.5,60,-534.5]
  - type 2: 12.4% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 72; neighbour step 3.1 (layer 5.2); liquid cover 2% (layer 20%); bare rock 94% (layer 60%) — camera [-356.5,61,-775.5]
  - type 3: 11.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 28; liquid cover 69% (layer 20%); bare rock 27% (layer 60%); overhangs 11% (layer 30%); cave pockets 2% (layer 40%) — camera [-391.5,24,584.5]
  - type 4: 7.7% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 59; relief over 16 blocks 84.9 (layer 67.5); relief over the patch 114.0 (layer 101.7); trees per cell 4.7 (layer 0.4); canopy 39% (layer 4%); ground plants 15% (layer 1%); liquid cover 5% (layer 20%); bare rock 11% (layer 60%) — camera [-691.5,105,711.5]
  - type 5: 7.7% of cells; biome basalt_deltas; ground basalt; top basalt; height 76 — camera [823.5,56,-856.5]
  - type 6: 7.7% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 74; relief over the patch 114.0 (layer 101.7); notable-block density (log) 0.9 (layer 0.4); liquid cover 3% (layer 20%); bare rock 10% (layer 60%); open to the sky 7% (layer 2%); under a roof 93% (layer 98%) — camera [936.5,24,233.5]
  - type 7: 6.0% of cells; biome crimson_forest; ground netherrack; top netherrack; height 77; neighbour step 9.2 (layer 5.2); relief over 16 blocks 97.6 (layer 67.5); relief over the patch 115.1 (layer 101.7); canopy 10% (layer 4%); ground plants 4% (layer 1%); cave pockets 67% (layer 40%) — camera [-727.5,79,328.5]
  - landmark at [744,-120]: ground bedrock (type blackstone); top bedrock (type blackstone); open to the sky 42% (type 0%); under a roof 58% (type 100%); neighbour step 16.8 (type 3.1)
  - landmark at [-808,-728]: biome basalt_deltas (type adventure:blackstone_basin); top bedrock (type blackstone); open to the sky 33% (type 0%); under a roof 67% (type 100%); neighbour step 10.8 (type 3.1)
  - landmark at [-616,-520]: ground bedrock (type blackstone); top bedrock (type blackstone); open to the sky 97% (type 0%); under a roof 3% (type 100%); bare rock 2% (type 84%)
  - landmark at [904,264]: ground soul_soil (type soul_sand); top soul_soil (type soul_sand); open to the sky 23% (type 0%); under a roof 77% (type 100%); height 103.0 (type 58.2)
  - landmark at [-552,968]: biome adventure:crimson_pinewood (type adventure:blackstone_basin); ground netherrack (type blackstone); notable-block density (log) 1.6 (type 0.0); stacked layers 31% (type 0%); canopy 22% (type 0%)
- **sky** (14 cells, 2 types, silhouette 0.596):
  - type 1: 85.7% of cells; biome adventure:blackstone_basin; ground blackstone; height 80 — camera [204.5,91,-102.5]
  - type 2: 14.3% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; height 71; layer present in 31% (layer 51%); neighbour step 5.9 (layer 3.2); relief over 16 blocks 50.0 (layer 38.7); trees per cell 1.0 (layer 0.1); notable-block density (log) 0.7 (layer 0.3); island layers above 1.0 (layer 1.0) — camera [-568.5,60,961.5]
- **underground** (234 cells, 6 types, silhouette 0.271):
  - type 1: 29.1% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 71; notable-block density (log) 0.4 (layer 1.0) — camera [-590.5,71.88,-539.5]
  - type 2: 18.8% of cells; notableId blackstone; biome basalt_deltas; height 70; notable-block density (log) 1.5 (layer 1.0) — camera [835.5,26.88,-878.5]
  - type 3: 15.8% of cells; notableId lava; biome adventure:blackstone_basin; height 55; cave air 31% (layer 21%) — camera [1016.5,43.88,-439.5]
  - type 4: 15.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 32; neighbour step 1.5 (layer 5.2); relief over 16 blocks 35.6 (layer 65.3); notable-block density (log) 0.3 (layer 1.0); cave air 2% (layer 21%); cave pockets 1% (layer 40%) — camera [-113.5,23.88,-402.5]
  - type 5: 13.2% of cells; notableId magma_block; biome adventure:ash_barrens; height 75; notable-block density (log) 1.6 (layer 1.0) — camera [-340.5,61.88,-798.5]
  - type 6: 8.1% of cells; notableId magma_block; biome crimson_forest; height 83; relief over 16 blocks 83.2 (layer 65.3); trees per cell 3.3 (layer 0.3); notable-block density (log) 1.6 (layer 1.0); cave liquid 13% (layer 3%); cave pockets 75% (layer 40%) — camera [-775.5,51.88,-2.5]
  - landmark at [728,-120]: layer present in 76% (type 100%); notable-block density (log) 4.1 (type 0.4); cave liquid 20% (type 0%)
  - landmark at [-680,696]: notableId lava (type magma_block); layer present in 55% (type 100%); notable-block density (log) 5.3 (type 1.2); neighbour step 22.6 (type 7.6)
  - landmark at [824,664]: notableId soul_soil (type blackstone); biome soul_sand_valley (type basalt_deltas); notable-block density (log) 3.0 (type 1.1); neighbour step 0.2 (type 7.6); relief over 16 blocks 11.0 (type 54.0)
  - landmark at [-8,-824]: notableId lava (type magma_block); notable-block density (log) 4.3 (type 1.4); cave liquid 16% (type 0%); cave pockets 100% (type 30%)
  - landmark at [-728,344]: biome crimson_forest (type basalt_deltas); layer present in 71% (type 100%); neighbour step 15.6 (type 7.6); cave liquid 9% (type 1%)
- **roof** (233 cells, 8 types, silhouette 0.343):
  - type 1: 32.2% of cells; notableId gilded_blackstone; height 107; neighbour step 0.7 (layer 1.8); relief over 16 blocks 16.0 (layer 32.6); notable-block density (log) 0.1 (layer 0.4) — camera [-615.5,101.88,-551.5]
  - type 2: 13.3% of cells; notableId gilded_blackstone; height 75; neighbour step 3.8 (layer 1.8); relief over 16 blocks 51.4 (layer 32.6) — camera [728.5,87.88,-105.5]
  - type 3: 12.9% of cells; notableId nether_quartz_ore; height 90; neighbour step 4.2 (layer 1.8); relief over 16 blocks 71.0 (layer 32.6); trees per cell 0.5 (layer 0.1) — camera [857.5,64.88,680.5]
  - type 4: 11.6% of cells; notableId nether_gold_ore; height 107; relief over 16 blocks 17.4 (layer 32.6); notable-block density (log) 0.1 (layer 0.4) — camera [8.5,106.88,-807.5]
  - type 5: 9.9% of cells; notableId nether_quartz_ore; height 103 — camera [-359.5,101.88,-775.5]
  - type 6: 9.0% of cells; notableId smooth_basalt; height 109; neighbour step 0.7 (layer 1.8); relief over 16 blocks 16.2 (layer 32.6); notable-block density (log) 0.9 (layer 0.4) — camera [312.5,105.88,-759.5]
  - type 7: 6.9% of cells; notableId blackstone; height 101; notable-block density (log) 1.1 (layer 0.4) — camera [824.5,96.88,-839.5]
  - type 8: 4.3% of cells; notableId blackstone; height 81; layer present in 80% (layer 98%); neighbour step 4.3 (layer 1.8); relief over 16 blocks 89.8 (layer 32.6); notable-block density (log) 2.0 (layer 0.4) — camera [-824.5,86.88,-729.5]
  - landmark at [-744,328]: notableId magma_block (type nether_quartz_ore); neighbour step 10.1 (type 2.7); trees per cell 1.0 (type 0.0); notable-block density (log) 1.0 (type 0.3)
  - landmark at [904,248]: notableId soul_sand (type blackstone); layer present in 60% (type 83%); height 51.4 (type 88.7); neighbour step 4.4 (type 5.4)
  - landmark at [-680,696]: neighbour step 11.1 (type 2.7); trees per cell 1.0 (type 0.0); height 67.2 (type 91.1)
  - landmark at [936,264]: notableId soul_soil (type blackstone); layer present in 70% (type 83%); height 64.2 (type 88.7); relief over 16 blocks 79.0 (type 108.0)
  - landmark at [904,264]: notableId soul_soil (type blackstone); neighbour step 0.4 (type 5.4); relief over 16 blocks 66.0 (type 108.0); height 103.5 (type 88.7)

## Biome expression

6 of 6 placed biomes are expressed.

- adventure:blackstone_basin: expressed, 34961 columns; placement land 70.2%, lava 24.7%, roofed 92.6%, sky 5.1%; snow 0%, canopy 0%, trees 0/ha; top blackstone 63.5%, lava 24.7%, polished_blackstone 3.5%
- adventure:ash_barrens: expressed, 9520 columns; placement land 82.3%, lava 12.5%, roofed 94.8%, sky 5.2%; snow 0%, canopy 0%, trees 0/ha; top tuff 66%, lava 12.5%, netherrack 11.5%
- minecraft:crimson_forest: expressed, 6101 columns; placement land 91.3%, lava 8.1%, roofed 98.7%, sky 0.6%; snow 0%, canopy 25%, trees 125.7/ha; top netherrack 37.4%, crimson_nylium 23.8%, nether_wart_block 22.3%
- minecraft:basalt_deltas: expressed, 4800 columns; placement land 79.6%, lava 20.4%, roofed 93.6%; snow 0%, canopy 0%, trees 0/ha; top basalt 55.8%, lava 20.4%, blackstone 8%
- minecraft:soul_sand_valley: expressed, 4615 columns; placement land 96.9%, lava 2.9%, roofed 92.5%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 44.4%, soul_soil 34.3%, netherrack 8.6%
- adventure:crimson_pinewood: expressed, 2401 columns; placement land 81.6%, lava 11.7%, roofed 93.3%, sky 6.7%; snow 0%, canopy 42.6%, trees 163.3/ha; top nether_wart_block 34.7%, crimson_nylium 31.1%, lava 11.7%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 7.7 | 4615 | expressed | - |
| O2 | regions_unexplored:blackstone_basin | adventure:blackstone_basin | 55.4 | 34961 | expressed | - |
| O3 | incendium:ash_barrens | adventure:ash_barrens | 15.1 | 9520 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 8.0 | 4800 | expressed | - |
| O5 | minecraft:nether_wastes | minecraft:nether_wastes | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | betternether:crimson_glowing_woods | minecraft:crimson_forest | 10.1 | 6101 | expressed | - |
| O7 | minecraft:crimson_forest | minecraft:crimson_forest | 10.1 | 6101 | expressed | - |
| O8 | betternether:wart_forest | adventure:upside_down_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O9 | betternether:wart_forest_edge | adventure:crimson_pinewood | 3.7 | 2401 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:blackstone_basin 55.4%, adventure:ash_barrens 15.1%, minecraft:crimson_forest 10.1% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 19.6% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 3.7% (`surface.canopyPercent`) | |
| M:relief | median 101.0 blocks a patch; height {"p5": 21.0, "p25": 30.0, "p50": 65.0, "p75": 89.0, "p95": 113.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 4.2, "surface": 100.0, "underground": 98.9, "roof": 97.5} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#1B1B2B", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 97.5, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 2.5} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 77.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (97.5% of columns)

## Shots

### the_blackstone_keep-surface-t1

Picked for: surface type 1: 47.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 65
Census: sky 0%; near: blackstone 87%, polished_blackstone 1%; mid: blackstone 10.5%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t1.png`

### the_blackstone_keep-surface-t1-nv

Picked for: surface type 1: 47.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 65
Census: sky 0%; near: blackstone 87%, polished_blackstone 1%; mid: blackstone 10.5%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t1-nv.png`

### the_blackstone_keep-sky-t1

Picked for: sky type 1: 85.7% of cells; biome adventure:blackstone_basin; ground blackstone; height 80
Census: sky 0%; near: blackstone 68.3%, lava 1.1%; mid: blackstone 28.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-sky-t1.png`

### the_blackstone_keep-sky-t1-nv

Picked for: sky type 1: 85.7% of cells; biome adventure:blackstone_basin; ground blackstone; height 80
Census: sky 0%; near: blackstone 68.3%, lava 1.1%; mid: blackstone 28.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-sky-t1-nv.png`

### the_blackstone_keep-underground-t4

Picked for: underground type 4: 15.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 32; neighbour step 1.5 (layer 5.2); relief over 16 blocks 35.6 (layer 65.3); notable-block density (log) 0.3 (layer 1.0); cave air 2% (layer 21%); cave pockets 1% (layer 40%)
Census: sky 0%; unloaded 1.7%; near: lava 31.2%, blackstone 27.6%; mid: blackstone 26.7%; far: blackstone 10.4%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-underground-t4.png`

### the_blackstone_keep-underground-t4-nv

Picked for: underground type 4: 15.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 32; neighbour step 1.5 (layer 5.2); relief over 16 blocks 35.6 (layer 65.3); notable-block density (log) 0.3 (layer 1.0); cave air 2% (layer 21%); cave pockets 1% (layer 40%)
Census: sky 0%; unloaded 1.7%; near: lava 31.2%, blackstone 27.6%; mid: blackstone 26.7%; far: blackstone 10.4%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-underground-t4-nv.png`

### the_blackstone_keep-roof-t8

Picked for: roof type 8: 4.3% of cells; notableId blackstone; height 81; layer present in 80% (layer 98%); neighbour step 4.3 (layer 1.8); relief over 16 blocks 89.8 (layer 32.6); notable-block density (log) 2.0 (layer 0.4)
Census: sky 0%; near: basalt 91.5%, netherrack 3.6%; mid: basalt 3.9%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-roof-t8.png`

### the_blackstone_keep-roof-t8-nv

Picked for: roof type 8: 4.3% of cells; notableId blackstone; height 81; layer present in 80% (layer 98%); neighbour step 4.3 (layer 1.8); relief over 16 blocks 89.8 (layer 32.6); notable-block density (log) 2.0 (layer 0.4)
Census: sky 0%; near: basalt 91.5%, netherrack 3.6%; mid: basalt 3.9%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-roof-t8-nv.png`

### the_blackstone_keep-surface-t4

Picked for: surface type 4: 7.7% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 59; relief over 16 blocks 84.9 (layer 67.5); relief over the patch 114.0 (layer 101.7); trees per cell 4.7 (layer 0.4); canopy 39% (layer 4%); ground plants 15% (layer 1%); liquid cover 5% (layer 20%); bare rock 11% (layer 60%)
Census: sky 0%; near: netherrack 45.3%, nether_wart_block 32.2%; mid: netherrack 7.2%, nether_wart_block 5.8%; biomes: crimson_forest 93.8%, adventure:crimson_pinewood 6.2%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t4.png`

### the_blackstone_keep-surface-t4-nv

Picked for: surface type 4: 7.7% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 59; relief over 16 blocks 84.9 (layer 67.5); relief over the patch 114.0 (layer 101.7); trees per cell 4.7 (layer 0.4); canopy 39% (layer 4%); ground plants 15% (layer 1%); liquid cover 5% (layer 20%); bare rock 11% (layer 60%)
Census: sky 0%; near: netherrack 45.3%, nether_wart_block 32.2%; mid: netherrack 7.2%, nether_wart_block 5.8%; biomes: crimson_forest 93.8%, adventure:crimson_pinewood 6.2%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t4-nv.png`

### the_blackstone_keep-surface-t3

Picked for: surface type 3: 11.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 28; liquid cover 69% (layer 20%); bare rock 27% (layer 60%); overhangs 11% (layer 30%); cave pockets 2% (layer 40%)
Census: sky 0%; near: lava 44.7%, blackstone 18.4%; mid: blackstone 27.7%; far: blackstone 7.7%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t3.png`

### the_blackstone_keep-surface-t3-nv

Picked for: surface type 3: 11.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 28; liquid cover 69% (layer 20%); bare rock 27% (layer 60%); overhangs 11% (layer 30%); cave pockets 2% (layer 40%)
Census: sky 0%; near: lava 44.7%, blackstone 18.4%; mid: blackstone 27.7%; far: blackstone 7.7%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t3-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
