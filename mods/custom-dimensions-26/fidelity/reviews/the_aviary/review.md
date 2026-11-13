# Review packet: the_aviary

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/paradise_lost.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_aviary.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: adventure:sky_highlands, adventure:wisteria_grove, adventure:golden_skygrove, minecraft:cherry_grove, minecraft:meadow, minecraft:flower_forest, minecraft:plains, minecraft:forest, minecraft:birch_forest, adventure:cloud_reef, adventure:windswept_isles, minecraft:snowy_slopes, minecraft:dark_forest, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "setting": "extreme", "params": {"size": 3, "coverage": 0.38, "thickness": 16, "underside": 80, "spread": 20, "flatness": 0.6, "tiers": 2, "tierSpacing": 64, "tierGap": 20, "tierShrink": 0.6}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 512}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1q/final/the_aviary/dossier.json`; layer maps in `/tmp/claude-0/w10c1q/final/the_aviary/layers`

## Summary

- Top blocks seen from the sky: grass_block 34.4%, short_grass 6.1%, pink_petals 5.7%, snow_block 4.4%.
- Surface biomes: cherry_grove 16.3%, plains 14.5%, meadow 12.8%, adventure:golden_skygrove 6.5%, adventure:sky_highlands 5.4%.
- Ground blocks: grass_block 60%, snow_block 4.6%, stone 3.9%, podzol 0.7%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 5.1% (a snowing climate over 8.8%); tree canopy 9.8%; bare rock 3.3%; no ground (void) 29.5%.
- Trees: 26.2 per ha (oak_log 13.6/ha, mean spacing 7.3 blocks, cherry_log 5.9/ha, mean spacing 10.4 blocks, dark_oak_log 4.1/ha, mean spacing 5.5 blocks, poplar_log 1.7/ha, mean spacing 13.2 blocks); ground plants flower 9.2%, grass 7.3%, other 2%, bush 0.1%.
- Surface height p5 125, median 177, p95 241, max 253; median relief per patch 19 blocks; slopes under 15° 37.1%, over 45° 5.5%, cliffs 0.4%; above the clouds 38.8%, below sea level 0%.
- Underground: 4.5% open, 0% liquid; cave biomes adventure:hanging_gardens 96.4%, cherry_grove 1.3%, snowy_slopes 0.7%, plains 0.5%.
- Notable clusters, fewest first: glow_lichen 2 clusters, spacing n/a; shelf_mushroom 3 clusters, spacing 23.3; powder_snow 4 clusters, spacing 5.1; snow_block 5 clusters, spacing 27.4; quartz_block 7 clusters, spacing 15.2; cobblestone 9 clusters, spacing 11.6.
- 9 of 11 placed biomes are expressed; 3 listed biomes were not found.
- Render against world at 786 columns: height within 2 blocks 99.9%, biome match 100%.
- Landforms over the border (noise render): plains 16.1%, hills 16.5%, mountains 3.7%, plateaus 2.8%, basins 4.3%, islands 8.7%, water 0%, void 47.9%.
- Placeness: biome regions 2.9/km² (9 major, largest 5.0%), landform regions 12.1/km² (largest 12.7%), dominant place 12.7% of the border.

## Layers

- Columns by class: void 29.5%, roofed 0%, stacked 12.5%, overhang 2.3%, cave 9%, single 46.7%.
- Columns with each layer: sky 12.5%, surface 70.5%, underground 67.2%, roof 0%.
- Overhangs in 2.4% of columns, stacked layers in 12.5%, cave pockets in 10.8%.

## Places

- **surface** (205 cells, 6 types, silhouette 0.391):
  - type 1: 38.0% of cells; biome meadow; ground grass_block; top grass_block; height 186; open to the sky 95% (layer 69%) — camera [-744.5,175,3560.5]
  - type 2: 20.5% of cells; biome cherry_grove; ground grass_block; top pink_petals; height 182; ground plants 62% (layer 26%); cave pockets 29% (layer 15%) — camera [2760.5,232,2584.5]
  - type 3: 16.6% of cells; biome plains; ground grass_block; top grass_block; height 158; trees per cell 0.0 (layer 0.8); canopy 1% (layer 14%); ground plants 2% (layer 26%); open to the sky 6% (layer 69%); stacked layers 93% (layer 17%) — camera [-1623.5,146,1111.5]
  - type 4: 11.7% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 173; trees per cell 4.3 (layer 0.8); canopy 72% (layer 14%); open to the sky 26% (layer 69%) — camera [-730.5,180,-3960.5]
  - type 5: 7.8% of cells; biome snowy_slopes; ground snow_block; top snow_block; height 205; layer present in 85% (layer 95%); relief over the patch 16.8 (layer 25.2); trees per cell 0.0 (layer 0.8); notable-block density (log) 0.7 (layer 0.4); canopy 0% (layer 14%); ground plants 0% (layer 26%); snow or ice 93% (layer 8%); open to the sky 100% (layer 69%); cave pockets 0% (layer 15%) — camera [3768.5,223,-3863.5]
  - type 6: 5.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 229; relief over the patch 17.4 (layer 25.2); ground plants 0% (layer 26%); bare rock 80% (layer 5%); open to the sky 97% (layer 69%) — camera [2040.5,246,-2440.5]
  - landmark at [1672,536]: biome adventure:wisteria_grove (type meadow); neighbour step 5.3 (type 0.2); relief over 16 blocks 76.0 (type 5.0); relief over the patch 77.0 (type 16.0)
  - landmark at [1672,520]: biome adventure:wisteria_grove (type meadow); neighbour step 4.7 (type 0.2); relief over 16 blocks 69.0 (type 5.0); relief over the patch 77.0 (type 16.0)
  - landmark at [-3624,-1304]: biome dark_forest (type adventure:golden_skygrove); top dark_oak_leaves (type oak_leaves); layer present in 39% (type 100%); trees per cell 2.0 (type 4.0); canopy 97% (type 66%)
  - landmark at [1672,552]: biome adventure:wisteria_grove (type meadow); neighbour step 4.9 (type 0.2); relief over 16 blocks 72.0 (type 5.0); relief over the patch 77.0 (type 16.0)
  - landmark at [-808,328]: biome plains (type meadow); layer present in 65% (type 100%); neighbour step 1.7 (type 0.2); relief over 16 blocks 24.0 (type 5.0)
- **sky** (40 cells, 5 types, silhouette 0.462):
  - type 1: 40.0% of cells; biome plains; ground grass_block; height 215 — camera [-3607.5,212,2584.5]
  - type 2: 27.5% of cells; biome meadow; ground grass_block; height 200; neighbour step 0.7 (layer 0.4); relief over 16 blocks 12.7 (layer 9.1); trees per cell 0.0 (layer 0.8) — camera [-983.5,189,2439.5]
  - type 3: 15.0% of cells; biome adventure:wisteria_grove; ground grass_block; height 217; layer present in 68% (layer 85%); neighbour step 0.1 (layer 0.4); relief over 16 blocks 3.2 (layer 9.1); trees per cell 1.7 (layer 0.8); notable-block density (log) 1.2 (layer 0.6) — camera [1688.5,219,536.5]
  - type 4: 10.0% of cells; biome adventure:golden_skygrove; ground grass_block; height 252; neighbour step 0.2 (layer 0.4); trees per cell 4.3 (layer 0.8); notable-block density (log) 0.8 (layer 0.6) — camera [-295.5,253,-1399.5]
  - type 5: 7.5% of cells; biome meadow; ground grass_block; height 237; layer present in 40% (layer 85%); neighbour step 0.2 (layer 0.4); relief over 16 blocks 6.0 (layer 9.1); notable-block density (log) 0.3 (layer 0.6) — camera [-758.5,235,3580.5]
  - landmark at [-968,2440]: layer present in 33% (type 100%); neighbour step 1.3 (type 0.7); notable-block density (log) 0.8 (type 0.6)
  - landmark at [1672,520]: layer present in 27% (type 100%); notable-block density (log) 0.7 (type 1.2); trees per cell 1.0 (type 2.0)
- **underground** (199 cells, 3 types, silhouette 0.371):
  - type 1: 51.3% of cells; notableId cave_vines_plant; height 181; cave pockets 0% (layer 15%)
  - type 2: 43.7% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 181 — camera [-290.5,171.88,-1386.5]
  - type 3: 5.0% of cells; notableId cave_vines_plant; biome cherry_grove; height 230; notable-block density (log) 1.4 (layer 1.0); cave air 18% (layer 3%); cave pockets 100% (layer 15%) — camera [-94.5,179.88,2273.5]
  - landmark at [1672,552]: notableId rooted_dirt (type cave_vines_plant); neighbour step 6.8 (type 0.2); relief over 16 blocks 71.0 (type 7.0); cave air 28% (type 1%)
  - landmark at [-824,296]: notableId rooted_dirt (type cave_vines_plant); cave air 25% (type 1%); relief over 16 blocks 30.0 (type 7.0); neighbour step 1.4 (type 0.2)
  - landmark at [1672,536]: neighbour step 5.8 (type 0.2); relief over 16 blocks 71.0 (type 7.0); cave air 30% (type 1%)
  - landmark at [1672,520]: relief over 16 blocks 69.0 (type 7.0); neighbour step 5.0 (type 0.2); cave air 17% (type 1%)
  - landmark at [1304,2984]: notableId rooted_dirt (type cave_vines_plant); relief over 16 blocks 30.0 (type 7.0); neighbour step 1.9 (type 0.2); height 114.9 (type 180.5)
- **roof**: no cells.

## Biome expression

9 of 11 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 17380 columns; placement land 1.5%, void 98.5%; snow 0%, canopy 12.1%, trees 0/ha; top grass_block 1.3%, cherry_leaves 0.2%, dark_oak_leaves 0%
- minecraft:cherry_grove: expressed, 14417 columns; placement land 81%, void 19%; snow 0%, canopy 22.6%, trees 36/ha; top pink_petals 28.3%, grass_block 22.6%, cherry_leaves 18.2%
- minecraft:plains: expressed, 14227 columns; placement land 72.7%, sky 27.3%; snow 0%, canopy 0%, trees 1.9/ha; top grass_block 57%, short_grass 9.4%, hanging_roots 4.3%
- minecraft:meadow: placed, not expressed, 12002 columns; placement land 75.9%, sky 24.1%; snow 0%, canopy 0%, trees 0/ha; top grass_block 56.5%, short_grass 6%, hanging_roots 4.2%; **features name trees, none on 0.9 ha of land**
- adventure:golden_skygrove: expressed, 6454 columns; placement land 71.4%, void 10.9%, sky 17.7%; snow 0%, canopy 53.2%, trees 160.6/ha; top oak_leaves 27.8%, grass_block 24.7%, yellow_poplar_leaves 9.9%
- adventure:sky_highlands: expressed, 3847 columns; placement land 100%; snow 0%, canopy 4.2%, trees 15.6/ha; top grass_block 71.6%, short_grass 16.8%, oak_leaves 4.2%
- minecraft:snowy_slopes: expressed, 3538 columns; placement land 100%; snow 94.5%, canopy 0%, trees 0/ha; top snow_block 88.6%, powder_snow 5.9%, stone 3.9%
- adventure:wisteria_grove: expressed, 3518 columns; placement land 70.3%, sky 29.7%; snow 0%, canopy 10.7%, trees 80.8/ha; top grass_block 44.5%, vine 7%, oak_leaves 4.5%
- adventure:windswept_isles: expressed, 2772 columns; placement land 100%; snow 10.1%, canopy 2.8%, trees 10.8/ha; top stone 79.1%, snow 10.1%, spruce_leaves 2.8%
- minecraft:dark_forest: expressed, 1732 columns; placement land 100%; snow 0%, canopy 80.5%, trees 230.9/ha; top dark_oak_leaves 71.6%, grass_block 11.8%, leaf_litter 5.2%
- minecraft:flower_forest: too few columns to judge, 496 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none

Listed in the definition, found in no sampled column: minecraft:forest, minecraft:birch_forest, adventure:cloud_reef.


v2 biomes not found in any sampled column: minecraft:forest, minecraft:birch_forest, adventure:cloud_reef

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:cherry_grove | 16.3 | 14417 | expressed | - |
| V2 | minecraft:plains | 14.5 | 14227 | expressed | - |
| V3 | minecraft:meadow | 12.8 | 12002 | placed, not expressed | features name trees, none on 0.9 ha of land |
| V4 | adventure:golden_skygrove | 6.5 | 6454 | expressed | - |
| V5 | adventure:sky_highlands | 5.4 | 3847 | expressed | - |
| V6 | minecraft:snowy_slopes | 5.0 | 3538 | expressed | - |
| V7 | adventure:windswept_isles | 3.9 | 2772 | expressed | - |
| V8 | adventure:wisteria_grove | 3.5 | 3518 | expressed | - |
| V9 | minecraft:dark_forest | 2.4 | 1732 | expressed | - |
| V10 | adventure:hanging_gardens | 0.4 | 17380 | expressed | - |
| V11 | minecraft:flower_forest | 0.0 | 496 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:cherry_grove 16.3%, minecraft:plains 14.5%, minecraft:meadow 12.8% | type `the_aviary` |
| M:snow | 5.1% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 29.5% (`surface.voidPercent`) | |
| M:canopy | 9.8% (`surface.canopyPercent`) | |
| M:relief | median 19.0 blocks a patch; height {"p5": 125.0, "p25": 155.0, "p50": 177.0, "p75": 217.0, "p95": 241.0, "max": 253.0} (`terrain`) | |
| M:layers | {"sky": 12.5, "surface": 70.5, "underground": 67.2, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 29.5, "roofed": 0.0, "stacked": 12.5, "overhang": 2.3, "cave": 9.0, "single": 46.7} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: surface present (70.5% of columns)

## Shots

### the_aviary-far

Picked for: far: the border ring at bearing 92, facing the centre; 0.0% of rays could reach the border
Census: sky 21.3%; void 33.8%; far: snow_block 23.8%, cherry_leaves 4.1%; biomes: snowy_slopes 61.5%, cherry_grove 20.6%, adventure:hanging_gardens 17.9%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-far.png`

### the_aviary-surface-t1

Picked for: surface type 1: 38.0% of cells; biome meadow; ground grass_block; top grass_block; height 186; open to the sky 95% (layer 69%)
Census: sky 18.9%; unloaded 9%; near: grass_block 33.1%, short_grass 15.5%; mid: grass_block 1.6%, oak_leaves 1.6%; far: cave_vines_plant 3.3%, stone 1.8%; biomes: meadow 89.2%, adventure:hanging_gardens 10.8%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-surface-t1.png`

### the_aviary-sky-t1

Picked for: sky type 1: 40.0% of cells; biome plains; ground grass_block; height 215
Census: sky 31.9%; unloaded 7.8%; near: grass_block 38.2%, short_grass 14.9%; mid: short_grass 2.4%, grass_block 1%; biomes: plains 99.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-sky-t1.png`

### the_aviary-underground-t2

Picked for: underground type 2: 43.7% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 181
Census: sky 0%; near: stone 62.8%, grass_block 36.2%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-underground-t2.png`

### the_aviary-surface-t2

Picked for: surface type 2: 20.5% of cells; biome cherry_grove; ground grass_block; top pink_petals; height 182; ground plants 62% (layer 26%); cave pockets 29% (layer 15%)
Census: sky 23.1%; unloaded 2.1%; near: grass_block 26.9%, short_grass 26%; mid: cherry_leaves 4%; biomes: cherry_grove 99.8%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-surface-t2.png`

### the_aviary-surface-t3

Picked for: surface type 3: 16.6% of cells; biome plains; ground grass_block; top grass_block; height 158; trees per cell 0.0 (layer 0.8); canopy 1% (layer 14%); ground plants 2% (layer 26%); open to the sky 6% (layer 69%); stacked layers 93% (layer 17%)
Census: sky 0.1%; unloaded 6.3%; near: grass_block 70.1%, short_grass 1.3%; mid: grass_block 5.2%, cave_vines_plant 2.8%; far: cave_vines_plant 3%, stone 1.4%; biomes: plains 89.7%, adventure:hanging_gardens 8.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-surface-t3.png`

### the_aviary-surface-t4

Picked for: surface type 4: 11.7% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 173; trees per cell 4.3 (layer 0.8); canopy 72% (layer 14%); open to the sky 26% (layer 69%)
Census: sky 3.3%; unloaded 14.5%; near: oak_leaves 28.3%, grass_block 24.6%; biomes: adventure:golden_skygrove 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-surface-t4.png`

### the_aviary-surface-lm1

Picked for: surface landmark: biome adventure:wisteria_grove (type meadow); neighbour step 5.3 (type 0.2); relief over 16 blocks 76.0 (type 5.0); relief over the patch 77.0 (type 16.0)
Census: sky 15.8%; unloaded 1.2%; near: vine 24.8%, short_grass 15%; biomes: adventure:wisteria_grove 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_aviary/screens/the_aviary-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
