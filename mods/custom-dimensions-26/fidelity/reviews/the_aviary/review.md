# Review packet: the_aviary

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/paradise_lost.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_aviary.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: adventure:sky_highlands, adventure:wisteria_grove, adventure:golden_skygrove, minecraft:cherry_grove, minecraft:meadow, minecraft:flower_forest, minecraft:plains, minecraft:forest, minecraft:birch_forest, adventure:cloud_reef, adventure:windswept_isles, minecraft:snowy_slopes, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "setting": "extreme", "params": {"size": 2, "coverage": 0.32, "thickness": 16, "underside": 80, "spread": 10, "flatness": 1.0, "tiers": 2, "tierSpacing": 64, "tierGap": 20, "tierShrink": 0.6}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 512}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_aviary/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_aviary/layers`

## Summary

- Top blocks seen from the sky: grass_block 27.5%, oak_leaves 5.2%, stone 4%, short_grass 3.5%.
- Surface biomes: adventure:sky_highlands 10.9%, adventure:wisteria_grove 9.9%, meadow 6.7%, adventure:golden_skygrove 5.7%, cherry_grove 5.5%.
- Ground blocks: grass_block 45.9%, stone 4.3%, snow_block 3.2%, moss_block 1.2%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 3.9% (a snowing climate over 6.3%); tree canopy 10.2%; bare rock 4%; no ground (void) 42.4%.
- Trees: 42.2 per ha (oak_log 26.7/ha, mean spacing 7.1 blocks, birch_log 9.2/ha, mean spacing 6.3 blocks, cherry_log 3.9/ha, mean spacing 9 blocks, poplar_log 1.3/ha, mean spacing 15.7 blocks); ground plants flower 4.4%, grass 4.4%, other 2.2%, bush 0%.
- Surface height p5 148, median 168, p95 227, max 243; median relief per patch 13 blocks; slopes under 15° 56.8%, over 45° 8.1%, cliffs 0.7%; above the clouds 23.9%, below sea level 0%.
- Underground: 5.7% open, 0% liquid; cave biomes adventure:hanging_gardens 96.7%, adventure:sky_highlands 1.2%, adventure:wisteria_grove 0.6%, meadow 0.5%.
- Notable clusters, fewest first: glow_lichen 3 clusters, spacing n/a; azalea 4 clusters, spacing 6.7; white_tulip 7 clusters, spacing 5.9; end_rod 8 clusters, spacing 19.8; dead_bubble_coral 8 clusters, spacing 11; dead_tube_coral 8 clusters, spacing 11.6.
- 13 of 13 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1473 columns: height within 2 blocks 99.8%, biome match 100%.
- Landforms over the border (noise render): plains 8.6%, hills 9.3%, mountains 1.8%, plateaus 1.3%, basins 2.2%, islands 22.2%, water 0%, void 54.7%.
- Placeness: biome regions 4.5/km² (5 major, largest 3.5%), landform regions 9.0/km² (largest 28.7%), dominant place 28.7% of the border.

## Layers

- Columns by class: void 42.4%, roofed 0%, stacked 9.7%, overhang 2.8%, cave 7.3%, single 37.7%.
- Columns with each layer: sky 9.7%, surface 57.6%, underground 55.7%, roof 0%.
- Overhangs in 2.9% of columns, stacked layers in 9.7%, cave pockets in 10%.

## Places

- **surface** (393 cells, 8 types, silhouette 0.391):
  - type 1: 29.3% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 187; trees per cell 0.3 (layer 1.4); canopy 4% (layer 17%); open to the sky 95% (layer 67%) — camera [1576.5,172,-3288.5]
  - type 2: 16.0% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 163; trees per cell 0.2 (layer 1.4); canopy 3% (layer 17%); ground plants 3% (layer 19%); open to the sky 9% (layer 67%); stacked layers 89% (layer 16%) — camera [840.5,160,-311.5]
  - type 3: 13.0% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 185; notable-block density (log) 1.0 (layer 0.4) — camera [136.5,178,2472.5]
  - type 4: 11.2% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 165; trees per cell 4.8 (layer 1.4); canopy 57% (layer 17%); open to the sky 41% (layer 67%) — camera [-812.5,173,-2967.5]
  - type 5: 9.7% of cells; biome flower_forest; ground grass_block; top grass_block; height 158; relief over the patch 46.8 (layer 27.6); trees per cell 3.5 (layer 1.4); canopy 34% (layer 17%) — camera [-3879.5,155,-3865.5]
  - type 6: 8.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 201; canopy 4% (layer 17%); ground plants 0% (layer 19%); bare rock 77% (layer 7%); open to the sky 96% (layer 67%); cave pockets 45% (layer 16%) — camera [2425.5,230,-1336.5]
  - type 7: 6.6% of cells; biome adventure:cloud_reef; ground snow_block; top snow_block; height 171; trees per cell 0.0 (layer 1.4); canopy 0% (layer 17%); ground plants 3% (layer 19%); snow or ice 91% (layer 7%); open to the sky 100% (layer 67%) — camera [-2215.5,146,824.5]
  - type 8: 5.9% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 170; canopy 46% (layer 17%); ground plants 67% (layer 19%) — camera [3720.5,172,2552.5]
  - landmark at [2696,2280]: biome cherry_grove (type flower_forest); neighbour step 7.1 (type 0.3); layer present in 28% (type 100%); relief over 16 blocks 71.0 (type 10.0)
  - landmark at [-2856,2024]: biome meadow (type adventure:wisteria_grove); neighbour step 6.4 (type 0.1); relief over 16 blocks 65.0 (type 2.0); layer present in 38% (type 100%)
  - landmark at [-936,2072]: biome plains (type adventure:wisteria_grove); neighbour step 5.7 (type 0.1); relief over 16 blocks 69.0 (type 2.0); layer present in 45% (type 100%)
  - landmark at [-2840,-3704]: biome adventure:hanging_gardens (type adventure:sky_highlands); overhangs 47% (type 0%); neighbour step 2.1 (type 0.2); cave pockets 66% (type 0%)
  - landmark at [-3240,-984]: biome birch_forest (type adventure:golden_skygrove); top birch_leaves (type oak_leaves); overhangs 30% (type 0%); trees per cell 7.0 (type 4.0); neighbour step 1.1 (type 0.1)
- **sky** (75 cells, 8 types, silhouette 0.33):
  - type 1: 24.0% of cells; biome adventure:wisteria_grove; ground grass_block; height 223; relief over 16 blocks 7.9 (layer 5.6); notable-block density (log) 1.1 (layer 0.7) — camera [248.5,226,3688.5]
  - type 2: 18.7% of cells; biome cherry_grove; ground grass_block; height 212; neighbour step 0.1 (layer 0.3); relief over 16 blocks 2.2 (layer 5.6) — camera [2680.5,216,2264.5]
  - type 3: 14.7% of cells; biome adventure:golden_skygrove; ground grass_block; height 219; layer present in 94% (layer 81%); trees per cell 4.1 (layer 1.7) — camera [-636.5,220,-1895.5]
  - type 4: 12.0% of cells; biome meadow; ground grass_block; height 223; trees per cell 0.0 (layer 1.7); notable-block density (log) 0.4 (layer 0.7) — camera [-3623.5,230,3624.5]
  - type 5: 12.0% of cells; biome plains; ground grass_block; height 221; trees per cell 0.1 (layer 1.7); notable-block density (log) 0.5 (layer 0.7) — camera [-935.5,225,2056.5]
  - type 6: 10.7% of cells; biome adventure:wisteria_grove; ground grass_block; height 216; layer present in 48% (layer 81%); neighbour step 0.8 (layer 0.3); relief over 16 blocks 8.4 (layer 5.6); trees per cell 0.8 (layer 1.7) — camera [2508.5,217,968.5]
  - type 7: 4.0% of cells; biome flower_forest; ground grass_block; height 211; layer present in 56% (layer 81%); neighbour step 0.4 (layer 0.3) — camera [-3895.5,212,-3863.5]
  - type 8: 4.0% of cells; biome birch_forest; ground grass_block; height 222; neighbour step 0.5 (layer 0.3); relief over 16 blocks 7.3 (layer 5.6); trees per cell 4.7 (layer 1.7); notable-block density (log) 0.4 (layer 0.7) — camera [-3223.5,224,-981.5]
  - landmark at [2760,-3176]: biome adventure:sky_highlands (type adventure:wisteria_grove); height 205.4 (type 224.4); layer present in 75% (type 100%); trees per cell 1.0 (type 2.0)
  - landmark at [232,3672]: notable-block density (log) 0.4 (type 1.1); layer present in 55% (type 100%); relief over 16 blocks 13.0 (type 8.0)
  - landmark at [264,3672]: layer present in 40% (type 100%); notable-block density (log) 2.0 (type 1.1); trees per cell 1.0 (type 2.0)
- **underground** (382 cells, 2 types, silhouette 0.384):
  - type 1: 54.2% of cells; notableId cave_vines_plant; biome adventure:windswept_isles; height 180; cave pockets 0% (layer 17%) — camera [2418.5,170.88,-1329.5]
  - type 2: 45.8% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 173; cave air 8% (layer 4%); cave pockets 36% (layer 17%) — camera [2516.5,122.88,948.5]
  - landmark at [3176,-2824]: notableId rooted_dirt (type cave_vines_plant); biome adventure:windswept_isles (type adventure:hanging_gardens); cave pockets 100% (type 28%); height 209.7 (type 161.1); cave air 17% (type 4%)
  - landmark at [-3288,-2872]: cave air 51% (type 4%); relief over 16 blocks 49.0 (type 4.0); notable-block density (log) 1.9 (type 1.1)
  - landmark at [-3272,-2872]: cave air 42% (type 4%); neighbour step 2.1 (type 0.2); relief over 16 blocks 46.0 (type 4.0)
  - landmark at [2408,-1320]: biome adventure:windswept_isles (type adventure:hanging_gardens); cave air 33% (type 4%); height 225.3 (type 161.1); cave pockets 89% (type 28%)
  - landmark at [2408,-1336]: biome adventure:windswept_isles (type adventure:hanging_gardens); cave air 30% (type 4%); height 226.6 (type 161.1); cave pockets 99% (type 28%)
- **roof**: no cells.

## Biome expression

13 of 13 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 64691 columns; placement land 3%, void 96.8%, sky 0.2%; snow 0.1%, canopy 5.5%, trees 0/ha; top grass_block 2.4%, hanging_roots 0.2%, oak_leaves 0.1%
- adventure:sky_highlands: expressed, 21802 columns; placement land 81.6%, void 13.3%, sky 5.1%; snow 0%, canopy 4.7%, trees 21.4/ha; top grass_block 54.6%, short_grass 14.7%, oak_leaves 3.8%
- adventure:wisteria_grove: expressed, 20535 columns; placement land 78.8%, lava 0.3%, sky 20.9%; snow 0%, canopy 17.3%, trees 90.3/ha; top grass_block 41%, vine 12.6%, oak_leaves 8.3%
- minecraft:meadow: expressed, 14771 columns; placement land 74.3%, void 11.3%, sky 14.5%; snow 0%, canopy 0%, trees 0/ha; top grass_block 54.3%, short_grass 7.1%, wildflowers 3.7%
- adventure:golden_skygrove: expressed, 13663 columns; placement land 68.5%, void 10.8%, sky 20.7%; snow 0%, canopy 43.4%, trees 156/ha; top grass_block 29.8%, oak_leaves 23.8%, yellow_poplar_leaves 5.9%
- minecraft:cherry_grove: expressed, 11977 columns; placement land 74.7%, void 3.5%, sky 21.8%; snow 0%, canopy 33.5%, trees 70.4/ha; top grass_block 26.5%, cherry_leaves 24.8%, pink_petals 13.4%
- adventure:windswept_isles: expressed, 8109 columns; placement land 100%; snow 10.5%, canopy 4.6%, trees 22.2/ha; top stone 78.2%, snow 10.4%, spruce_leaves 4.3%
- minecraft:flower_forest: expressed, 6284 columns; placement land 95.7%, sky 4.3%; snow 0%, canopy 36.4%, trees 173/ha; top grass_block 53.1%, oak_leaves 29.4%, birch_leaves 5.5%
- minecraft:birch_forest: expressed, 5155 columns; placement land 87.3%, sky 12.7%; snow 0%, canopy 47.3%, trees 266.6/ha; top birch_leaves 41.2%, grass_block 37.6%, wildflowers 4.3%
- adventure:cloud_reef: expressed, 3977 columns; placement land 94%, void 6%; snow 90.6%, canopy 0%, trees 0/ha; top snow_block 76.3%, snow 5.8%, calcite 1.6%
- minecraft:plains: expressed, 3491 columns; placement land 50.2%, sky 49.8%; snow 0%, canopy 0%, trees 5.7/ha; top grass_block 35.5%, hanging_roots 12.8%, cave_vines_plant 1.5%
- minecraft:forest: expressed, 2881 columns; placement land 94.3%, sky 5.7%; snow 0%, canopy 42.2%, trees 198.8/ha; top grass_block 39.3%, oak_leaves 31.4%, leaf_litter 11.8%
- minecraft:snowy_slopes: expressed, 2195 columns; placement land 100%; snow 97.4%, canopy 0%, trees 0/ha; top snow_block 90.1%, powder_snow 7.4%, grass_block 2.6%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:sky_highlands | 10.9 | 21802 | expressed | - |
| V2 | adventure:wisteria_grove | 9.9 | 20535 | expressed | - |
| V3 | minecraft:meadow | 6.7 | 14771 | expressed | - |
| V4 | adventure:golden_skygrove | 5.7 | 13663 | expressed | - |
| V5 | minecraft:cherry_grove | 5.5 | 11977 | expressed | - |
| V6 | adventure:windswept_isles | 5.0 | 8109 | expressed | - |
| V7 | minecraft:flower_forest | 3.7 | 6284 | expressed | - |
| V8 | minecraft:birch_forest | 2.8 | 5155 | expressed | - |
| V9 | adventure:cloud_reef | 2.3 | 3977 | expressed | - |
| V10 | minecraft:forest | 1.7 | 2881 | expressed | - |
| V11 | minecraft:snowy_slopes | 1.3 | 2195 | expressed | - |
| V12 | adventure:hanging_gardens | 1.2 | 64691 | expressed | - |
| V13 | minecraft:plains | 1.1 | 3491 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 10.9%, adventure:wisteria_grove 9.9%, minecraft:meadow 6.7% | type `the_aviary` |
| M:snow | 3.9% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 42.4% (`surface.voidPercent`) | |
| M:canopy | 10.2% (`surface.canopyPercent`) | |
| M:relief | median 13.0 blocks a patch; height {"p5": 148.0, "p25": 158.0, "p50": 168.0, "p75": 180.0, "p95": 227.0, "max": 243.0} (`terrain`) | |
| M:layers | {"sky": 9.7, "surface": 57.6, "underground": 55.7, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 42.4, "roofed": 0.0, "stacked": 9.7, "overhang": 2.8, "cave": 7.3, "single": 37.7} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (57.6% of columns)

## Shots

### the_aviary-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 2.4%; void 14%; mid: birch_leaves 30.1%, grass_block 14%; far: birch_leaves 10.3%, grass_block 8.7%; biomes: birch_forest 81.9%, adventure:hanging_gardens 18.1%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-far.png`

### the_aviary-surface-t1

Picked for: surface type 1: 29.5% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 187; trees per cell 0.3 (layer 1.4); canopy 4% (layer 17%); open to the sky 95% (layer 67%)
Census: sky 16.4%; unloaded 3.7%; near: quartz_block 22.5%, short_grass 21.1%; mid: cave_vines_plant 1.3%, short_grass 1%; far: stone 6.2%, cave_vines_plant 1.8%; biomes: adventure:sky_highlands 86.5%, adventure:hanging_gardens 13.5%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-surface-t1.png`

### the_aviary-sky-t1

Picked for: sky type 1: 17.3% of cells; biome adventure:wisteria_grove; ground grass_block; height 218; layer present in 61% (layer 81%); neighbour step 0.6 (layer 0.3); relief over 16 blocks 9.7 (layer 5.6)
Census: sky 21.8%; unloaded 0.5%; near: grass_block 22.2%, vine 18.4%; far: vine 1.3%; biomes: adventure:wisteria_grove 98.9%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-sky-t1.png`

### the_aviary-underground-t1

Picked for: underground type 1: 54.2% of cells; notableId cave_vines_plant; biome adventure:windswept_isles; height 180; cave pockets 0% (layer 17%)
Census: sky 0%; unloaded 7.8%; near: stone 39.1%, cave_vines_plant 23.9%; mid: stone 12%, cave_vines_plant 6.5%; biomes: adventure:windswept_isles 74.7%, adventure:hanging_gardens 25.3%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-underground-t1.png`

### the_aviary-surface-t6

Picked for: surface type 6: 8.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 201; canopy 4% (layer 17%); ground plants 0% (layer 19%); bare rock 77% (layer 7%); open to the sky 96% (layer 67%); cave pockets 45% (layer 16%)
Census: sky 34.9%; near: stone 32.5%, snow 22%; mid: stone 1.7%, spruce_leaves 1.5%; biomes: adventure:windswept_isles 100%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-surface-t6.png`

### the_aviary-surface-t2

Picked for: surface type 2: 15.8% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 163; trees per cell 0.2 (layer 1.4); canopy 3% (layer 17%); ground plants 3% (layer 19%); open to the sky 8% (layer 67%); stacked layers 90% (layer 16%)
Census: sky 8.6%; unloaded 7%; near: grass_block 53.5%, cave_vines_plant 1.1%; mid: vine 9.2%, cave_vines_plant 4.3%; far: stone 2.1%, cave_vines_plant 1.8%; biomes: adventure:wisteria_grove 88.1%, adventure:hanging_gardens 11.9%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-surface-t2.png`

### the_aviary-surface-t7

Picked for: surface type 7: 6.6% of cells; biome adventure:cloud_reef; ground snow_block; top snow_block; height 171; trees per cell 0.0 (layer 1.4); canopy 0% (layer 17%); ground plants 3% (layer 19%); snow or ice 91% (layer 7%); open to the sky 100% (layer 67%)
Census: sky 11.3%; unloaded 15.5%; near: snow_block 33.7%, snow 4.1%; far: cave_vines_plant 5.5%, stone 3.9%; biomes: adventure:cloud_reef 82.3%, adventure:hanging_gardens 17.6%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-surface-t7.png`

### the_aviary-surface-lm2

Picked for: surface landmark: biome meadow (type adventure:wisteria_grove); neighbour step 6.4 (type 0.1); relief over 16 blocks 65.0 (type 5.0); layer present in 38% (type 100%)
Census: sky 36.8%; unloaded 5.2%; near: grass_block 40.1%, short_grass 9.4%; biomes: meadow 98.4%
Image: `/tmp/claude-0/w10c3/reviews/the_aviary/screens/the_aviary-surface-lm2.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
