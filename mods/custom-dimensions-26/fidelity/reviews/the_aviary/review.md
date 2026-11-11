# Review packet: the_aviary

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/paradise_lost.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_aviary.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: adventure:sky_highlands, adventure:wisteria_grove, adventure:golden_skygrove, minecraft:cherry_grove, minecraft:meadow, minecraft:flower_forest, minecraft:plains, minecraft:forest, minecraft:birch_forest, adventure:cloud_reef, adventure:windswept_isles, minecraft:snowy_slopes, minecraft:dark_forest, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "setting": "extreme", "params": {"size": 3, "coverage": 0.45, "thickness": 16, "underside": 80, "spread": 20, "flatness": 0.6, "tiers": 2, "tierSpacing": 64, "tierGap": 20, "tierShrink": 0.6}, "scales": {"climate": 4}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/sel/the_aviary/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/sel/the_aviary/layers`

## Summary

- Top blocks seen from the sky: grass_block 24.7%, oak_leaves 6.6%, cherry_leaves 5.5%, pink_petals 3.9%.
- Surface biomes: cherry_grove 14.5%, adventure:golden_skygrove 9.4%, adventure:sky_highlands 9.2%, meadow 5.8%, birch_forest 5.4%.
- Ground blocks: grass_block 50.5%, stone 3.4%, podzol 1.6%, dirt 0.5%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0.5% (a snowing climate over 3.3%); tree canopy 16.8%; bare rock 3%; no ground (void) 42.4%.
- Trees: 53.1 per ha (oak_log 26.6/ha, mean spacing 6.8 blocks, birch_log 13/ha, mean spacing 4.6 blocks, cherry_log 9/ha, mean spacing 9.7 blocks, dark_oak_log 2.1/ha, mean spacing 8.1 blocks); ground plants flower 7.7%, grass 4.6%, other 2.1%, bush 0%.
- Surface height p5 138, median 186, p95 234, max 241; median relief per patch 18 blocks; slopes under 15° 41%, over 45° 4.6%, cliffs 0.6%; above the clouds 47%, below sea level 0%.
- Underground: 8.4% open, 0% liquid; cave biomes adventure:hanging_gardens 95.3%, cherry_grove 1.2%, birch_forest 1.1%, meadow 1.1%.
- Notable clusters, fewest first: pink_tulip 2 clusters, spacing n/a; dandelion 4 clusters, spacing 15.9; cobblestone 5 clusters, spacing 17.9; shelf_mushroom 7 clusters, spacing 11.2; bush 8 clusters, spacing 3.9; bee_nest 12 clusters, spacing 12.8.
- 11 of 12 placed biomes are expressed; 2 listed biomes were not found.
- Render against world at 603 columns: height within 2 blocks 100%, biome match 100%.
- Landforms over the border (noise render): plains 20.3%, hills 20.1%, mountains 4.5%, plateaus 3.4%, basins 5.4%, islands 5.2%, water 0%, void 41.1%.
- Placeness: biome regions 2.6/km² (12 major, largest 7.8%), landform regions 14.2/km² (largest 6.7%), dominant place 7.8% of the border.

## Layers

- Columns by class: void 42.4%, roofed 0%, stacked 10%, overhang 1.3%, cave 12.1%, single 34.2%.
- Columns with each layer: sky 10%, surface 57.6%, underground 54.3%, roof 0%.
- Overhangs in 1.4% of columns, stacked layers in 10%, cave pockets in 13.9%.

## Places

- **surface** (158 cells, 8 types, silhouette 0.398):
  - type 1: 19.0% of cells; biome meadow; ground grass_block; top grass_block; height 210; trees per cell 0.5 (layer 2.0); canopy 8% (layer 29%); open to the sky 92% (layer 55%) — camera [1288.5,214,-3543.5]
  - type 2: 17.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 161; relief over the patch 59.4 (layer 33.6); trees per cell 0.1 (layer 2.0); notable-block density (log) 0.6 (layer 0.4); canopy 1% (layer 29%); ground plants 1% (layer 25%); open to the sky 6% (layer 55%); stacked layers 92% (layer 17%) — camera [-2791.5,141,3672.5]
  - type 3: 16.5% of cells; biome birch_forest; ground grass_block; top grass_block; height 187; relief over the patch 20.5 (layer 33.6); trees per cell 5.0 (layer 2.0); notable-block density (log) 0.2 (layer 0.4); canopy 45% (layer 29%); ground plants 10% (layer 25%) — camera [3671.5,206,2232.5]
  - type 4: 14.6% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 181; trees per cell 4.4 (layer 2.0); canopy 66% (layer 29%); open to the sky 31% (layer 55%); overhangs 6% (layer 2%) — camera [1084.5,198,3177.5]
  - type 5: 13.3% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 200; relief over the patch 19.8 (layer 33.6); canopy 51% (layer 29%); ground plants 60% (layer 25%); cave pockets 45% (layer 23%) — camera [-7.5,155,2760.5]
  - type 6: 8.9% of cells; biome cherry_grove; ground grass_block; top pink_petals; height 184; relief over the patch 20.9 (layer 33.6); trees per cell 0.9 (layer 2.0); ground plants 62% (layer 25%); open to the sky 81% (layer 55%) — camera [-199.5,167,1879.5]
  - type 7: 5.7% of cells; biome adventure:windswept_isles; ground stone; top stone; height 180; trees per cell 0.6 (layer 2.0); canopy 3% (layer 29%); ground plants 0% (layer 25%); snow or ice 9% (layer 1%); bare rock 80% (layer 5%); open to the sky 97% (layer 55%) — camera [3576.5,182,-3255.5]
  - type 8: 5.1% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 200; layer present in 81% (layer 95%); neighbour step 1.0 (layer 0.4); relief over 16 blocks 32.1 (layer 10.4); relief over the patch 71.3 (layer 33.6); notable-block density (log) 1.0 (layer 0.4); open to the sky 71% (layer 55%); cave pockets 2% (layer 23%) — camera [-3591.5,173,-3192.5]
  - landmark at [3208,-1096]: biome adventure:cloud_reef (type meadow); ground snow_block (type grass_block); top snow_block (type grass_block); snow or ice 69% (type 0%); notable-block density (log) 1.6 (type 0.2); layer present in 76% (type 100%)
  - landmark at [-2760,3640]: biome adventure:sky_highlands (type meadow); overhangs 36% (type 0%); neighbour step 3.1 (type 0.2); relief over 16 blocks 63.0 (type 6.0)
  - landmark at [-616,-3112]: biome adventure:golden_skygrove (type adventure:wisteria_grove); relief over 16 blocks 91.0 (type 6.0); notable-block density (log) 0.3 (type 1.2); neighbour step 1.4 (type 0.2)
  - landmark at [-2776,3640]: biome adventure:hanging_gardens (type adventure:sky_highlands); relief over 16 blocks 65.0 (type 4.0); neighbour step 1.8 (type 0.2); notable-block density (log) 0.2 (type 0.8)
  - landmark at [-3928,-600]: biome adventure:wisteria_grove (type adventure:sky_highlands); neighbour step 3.3 (type 0.2); relief over 16 blocks 76.0 (type 4.0); height 171.2 (type 139.3)
- **sky** (29 cells, 6 types, silhouette 0.348):
  - type 1: 31.0% of cells; biome adventure:sky_highlands; ground grass_block; height 232; layer present in 99% (layer 88%); neighbour step 0.2 (layer 0.4); relief over 16 blocks 14.2 (layer 10.8) — camera [-1734.5,234,1160.5]
  - type 2: 24.1% of cells; biome adventure:wisteria_grove; ground grass_block; height 215; relief over 16 blocks 8.1 (layer 10.8); trees per cell 2.1 (layer 1.0); notable-block density (log) 1.1 (layer 0.7) — camera [-3943.5,223,-600.5]
  - type 3: 13.8% of cells; biome adventure:sky_highlands; ground grass_block; height 200; layer present in 100% (layer 88%); neighbour step 0.2 (layer 0.4); relief over 16 blocks 6.5 (layer 10.8) — camera [-2791.5,201,3656.5]
  - type 4: 13.8% of cells; biome adventure:golden_skygrove; ground grass_block; height 220; layer present in 55% (layer 88%); neighbour step 0.6 (layer 0.4); notable-block density (log) 0.5 (layer 0.7) — camera [-635.5,246,-3112.5]
  - type 5: 10.3% of cells; biome adventure:sky_highlands; ground grass_block; height 194; layer present in 76% (layer 88%); neighbour step 0.8 (layer 0.4); relief over 16 blocks 13.3 (layer 10.8); trees per cell 0.0 (layer 1.0) — camera [-2758.5,195,3673.5]
  - type 6: 6.9% of cells; biome cherry_grove; ground grass_block; height 243; neighbour step 0.6 (layer 0.4); relief over 16 blocks 13.0 (layer 10.8) — camera [-632.5,244,-3143.5]
  - landmark at [-984,776]: biome adventure:golden_skygrove (type adventure:wisteria_grove); trees per cell 4.0 (type 2.0); notable-block density (log) 0.9 (type 1.2); height 211.6 (type 221.6)
- **underground** (148 cells, 2 types, silhouette 0.362):
  - type 1: 52.7% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 186; cave air 11% (layer 6%); cave pockets 46% (layer 24%) — camera [3614.5,166.88,-3258.5]
  - type 2: 47.3% of cells; notableId cave_vines_plant; height 187; cave air 0% (layer 6%); cave pockets 0% (layer 24%)
  - landmark at [2392,1800]: notableId rooted_dirt (type cave_vines_plant); biome birch_forest (type adventure:hanging_gardens); notable-block density (log) 2.3 (type 1.1); cave air 23% (type 8%); cave pockets 100% (type 57%)
  - landmark at [-2776,3640]: neighbour step 1.8 (type 0.2); height 127.5 (type 187.7); relief over 16 blocks 25.0 (type 5.0)
  - landmark at [216,1240]: notableId rooted_dirt (type cave_vines_plant); biome cherry_grove (type adventure:hanging_gardens); height 231.0 (type 187.7); cave pockets 100% (type 57%); notable-block density (log) 1.5 (type 1.1)
  - landmark at [-2760,3640]: neighbour step 2.8 (type 0.2); relief over 16 blocks 63.0 (type 5.0); height 117.5 (type 187.7)
  - landmark at [-184,1912]: notableId rooted_dirt (type cave_vines_plant); neighbour step 0.7 (type 0.2); cave pockets 16% (type 57%); relief over 16 blocks 18.0 (type 5.0)
- **roof**: no cells.

## Biome expression

11 of 12 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 21979 columns; placement land 1.9%, void 98%; snow 0%, canopy 10.7%, trees 0/ha; top grass_block 1.6%, oak_leaves 0.1%, cherry_leaves 0%
- adventure:sky_highlands: expressed, 12529 columns; placement land 49%, void 19.9%, sky 31.1%; snow 0%, canopy 2.4%, trees 16.3/ha; top grass_block 36.5%, hanging_roots 4.7%, short_grass 2.5%
- minecraft:cherry_grove: expressed, 10526 columns; placement land 91.9%, void 2.7%, sky 5.4%; snow 0%, canopy 36.5%, trees 62/ha; top cherry_leaves 33.4%, pink_petals 24.1%, grass_block 22.8%
- adventure:golden_skygrove: expressed, 8101 columns; placement land 77.5%, void 12.4%, sky 10%; snow 0%, canopy 57.7%, trees 167.2/ha; top oak_leaves 38.3%, grass_block 22.7%, yellow_poplar_leaves 6.4%
- adventure:wisteria_grove: expressed, 4485 columns; placement land 68.7%, sky 31.3%; snow 0%, canopy 11.7%, trees 87.6/ha; top grass_block 39.4%, vine 7.9%, hanging_roots 7.2%
- minecraft:birch_forest: expressed, 4105 columns; placement land 87.9%, void 12.1%; snow 0%, canopy 45.7%, trees 216.2/ha; top grass_block 42.3%, birch_leaves 40.1%, wildflowers 3%
- minecraft:meadow: expressed, 3996 columns; placement land 96.5%, void 3.5%; snow 0%, canopy 7.1%, trees 7.8/ha; top grass_block 63.3%, short_grass 9.3%, wildflowers 6%
- minecraft:flower_forest: expressed, 2304 columns; placement land 100%; snow 0%, canopy 46.1%, trees 221.4/ha; top grass_block 47.9%, oak_leaves 38.6%, birch_leaves 7.5%
- adventure:windswept_isles: expressed, 2226 columns; placement land 100%; snow 9.4%, canopy 2.9%, trees 22.5/ha; top stone 80.6%, snow 9%, spruce_leaves 2.3%
- adventure:cloud_reef: expressed, 2067 columns; placement land 9.4%, void 90.6%; snow 75.9%, canopy 0%, trees 0/ha; top snow_block 6.6%, cobblestone 0.3%, grass_block 0.3%
- minecraft:dark_forest: expressed, 684 columns; placement land 100%; snow 0%, canopy 72.4%, trees 233.9/ha; top dark_oak_leaves 62.6%, grass_block 18.7%, oak_leaves 7.7%
- minecraft:plains: too few columns to judge, 496 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none

Listed in the definition, found in no sampled column: minecraft:forest, minecraft:snowy_slopes.


v2 biomes not found in any sampled column: minecraft:forest, minecraft:snowy_slopes

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:cherry_grove | 14.5 | 10526 | expressed | - |
| V2 | adventure:golden_skygrove | 9.4 | 8101 | expressed | - |
| V3 | adventure:sky_highlands | 9.2 | 12529 | expressed | - |
| V4 | minecraft:meadow | 5.8 | 3996 | expressed | - |
| V5 | minecraft:birch_forest | 5.4 | 4105 | expressed | - |
| V6 | adventure:wisteria_grove | 4.6 | 4485 | expressed | - |
| V7 | minecraft:flower_forest | 3.4 | 2304 | expressed | - |
| V8 | adventure:windswept_isles | 3.3 | 2226 | expressed | - |
| V9 | minecraft:dark_forest | 1.0 | 684 | expressed | - |
| V10 | adventure:hanging_gardens | 0.6 | 21979 | expressed | - |
| V11 | adventure:cloud_reef | 0.3 | 2067 | expressed | - |
| V12 | minecraft:plains | 0.0 | 496 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:cherry_grove 14.5%, adventure:golden_skygrove 9.4%, adventure:sky_highlands 9.2% | type `the_aviary` |
| M:snow | 0.5% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 42.4% (`surface.voidPercent`) | |
| M:canopy | 16.8% (`surface.canopyPercent`) | |
| M:relief | median 18.0 blocks a patch; height {"p5": 138.0, "p25": 157.0, "p50": 186.0, "p75": 213.0, "p95": 234.0, "max": 241.0} (`terrain`) | |
| M:layers | {"sky": 10.0, "surface": 57.6, "underground": 54.3, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 42.4, "roofed": 0.0, "stacked": 10.0, "overhang": 1.3, "cave": 12.1, "single": 34.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (57.6% of columns)

## Shots

### the_aviary-surface-t2

Picked for: surface type 2: 17.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 161; relief over the patch 59.4 (layer 33.6); trees per cell 0.1 (layer 2.0); notable-block density (log) 0.6 (layer 0.4); canopy 1% (layer 29%); ground plants 1% (layer 25%); open to the sky 6% (layer 55%); stacked layers 92% (layer 17%)
Census: sky 11.6%; unloaded 1.2%; near: grass_block 34.7%, cave_vines 17.5%; mid: short_grass 3.3%, grass_block 2.9%; far: cave_vines_plant 4.4%, grass_block 3.7%; biomes: adventure:sky_highlands 88.2%, adventure:hanging_gardens 11.8%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t2.png`

### the_aviary-sky-t1

Picked for: sky type 1: 31.0% of cells; biome adventure:sky_highlands; ground grass_block; height 232; layer present in 99% (layer 88%); neighbour step 0.2 (layer 0.4); relief over 16 blocks 14.2 (layer 10.8)
Census: sky 22.5%; unloaded 11.5%; near: grass_block 36.3%, short_grass 18.7%; mid: grass_block 1.2%, oak_leaves 1%; far: grass_block 2.6%, short_grass 1%; biomes: adventure:sky_highlands 99.9%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-sky-t1.png`

### the_aviary-underground-t1

Picked for: underground type 1: 52.7% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 186; cave air 11% (layer 6%); cave pockets 46% (layer 24%)
Census: sky 0%; near: stone 50.4%, grass_block 46.9%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-underground-t1.png`

### the_aviary-surface-t7

Picked for: surface type 7: 5.7% of cells; biome adventure:windswept_isles; ground stone; top stone; height 180; trees per cell 0.6 (layer 2.0); canopy 3% (layer 29%); ground plants 0% (layer 25%); snow or ice 9% (layer 1%); bare rock 80% (layer 5%); open to the sky 97% (layer 55%)
Census: sky 23.5%; void 0.4%; unloaded 8.3%; near: stone 41.3%, spruce_leaves 10.5%; mid: stone 2.8%, cave_vines_plant 2.6%; far: snow_block 1.7%; biomes: adventure:windswept_isles 84%, adventure:hanging_gardens 10.8%, snowy_slopes 5.2%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t7.png`

### the_aviary-surface-t1

Picked for: surface type 1: 19.0% of cells; biome meadow; ground grass_block; top grass_block; height 210; trees per cell 0.5 (layer 2.0); canopy 8% (layer 29%); open to the sky 92% (layer 55%)
Census: sky 30.7%; unloaded 10.3%; near: grass_block 30%, short_grass 12.5%; biomes: meadow 98.9%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t1.png`

### the_aviary-surface-t4

Picked for: surface type 4: 14.6% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 181; trees per cell 4.4 (layer 2.0); canopy 66% (layer 29%); open to the sky 31% (layer 55%); overhangs 6% (layer 2%)
Census: sky 7.9%; near: oak_leaves 38.3%, grass_block 36.7%; far: stone 1.3%; biomes: adventure:golden_skygrove 99.3%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t4.png`

### the_aviary-surface-lm1

Picked for: surface landmark: biome adventure:cloud_reef (type meadow); ground snow_block (type grass_block); top snow_block (type grass_block); snow or ice 69% (type 0%); notable-block density (log) 1.6 (type 0.2); layer present in 76% (type 100%)
Census: sky 21.3%; unloaded 19.1%; near: snow_block 37.2%, grass_block 2.5%; far: stone 6%, cave_vines_plant 5.5%; biomes: adventure:cloud_reef 69.8%, adventure:hanging_gardens 26.9%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-lm1.png`

### the_aviary-far2

Picked for: vista: from above the spawn island, across the open sky to the next masses
Census: sky 33.5%; void 0.4%; unloaded 1.7%; mid: cherry_leaves 21.7%, grass_block 3.6%; far: grass_block 7.2%, stone 6.1%; biomes: cherry_grove 66.4%, meadow 17.3%, adventure:hanging_gardens 16.4%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-far2.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
