# Review packet: the_shattered_skies

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_shattered_skies.json`

> Floating garden islands — sakura and skylands adrift in a void slowly winning. The animals' paradise, where nothing was ever built to last and nothing needs to. Sky houses and castle ruins dot the islands like driftwood.

Biomes: minecraft:plains, minecraft:forest, minecraft:birch_forest, minecraft:meadow, minecraft:flower_forest, minecraft:cherry_grove, terralith:blooming_valley, terralith:sakura_valley, terralith:sakura_grove, terralith:alpine_grove, terralith:lush_valley, terralith:lavender_valley, terralith:blooming_plateau, terralith:shrubland, terralith:skylands_spring, terralith:skylands_autumn, terralith:skylands_summer, terralith:skylands_winter, terralith:mirage_isles, minecraft:sunflower_plains

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_shattered_skies.json`

> Floating garden islands — sakura and skylands adrift in a void slowly winning. The animals' paradise, where nothing was ever built to last and nothing needs to. Sky houses and castle ruins dot the islands like driftwood.

Biomes: minecraft:cherry_grove, adventure:magnolia_woodland, minecraft:flower_forest, minecraft:sunflower_plains, minecraft:meadow, minecraft:plains, adventure:alpine_meadow, adventure:lavender_fields, adventure:sky_highlands, minecraft:forest, minecraft:birch_forest, adventure:golden_skygrove, adventure:windswept_isles, minecraft:snowy_slopes, adventure:desert_oasis, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "params": {"coverage": 0.2, "spread": 40, "size": 2.2, "flatness": 0.3}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_shattered_skies/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_shattered_skies/layers`

## Summary

- Top blocks seen from the sky: grass_block 19.6%, short_grass 2.9%, oak_leaves 2.3%, allium 0.8%.
- Surface biomes: meadow 6.2%, flower_forest 6.2%, plains 4.9%, adventure:lavender_fields 3.6%, adventure:sky_highlands 2.8%.
- Ground blocks: grass_block 28.4%, podzol 0.1%, dirt 0.1%, moss_block 0.1%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 3.2%; bare rock 0.1%; no ground (void) 71%.
- Trees: 12.3 per ha (oak_log 9.1/ha, mean spacing 7 blocks, birch_log 2.2/ha, mean spacing 13.3 blocks, cherry_log 0.8/ha, mean spacing 8.2 blocks, poplar_log 0.2/ha, mean spacing 10.2 blocks); ground plants grass 3.2%, flower 3%, other 0.1%, bush 0%.
- Surface height p5 67, median 168, p95 230, max 254; median relief per patch 0 blocks; slopes under 15° 21.5%, over 45° 21%, cliffs 0.5%; above the clouds 30%, below sea level 0%.
- Underground: 3.8% open, 0% liquid; cave biomes adventure:hanging_gardens 100%.
- Notable clusters, fewest first: diamond_ore 2 clusters, spacing n/a; poppy 4 clusters, spacing 2.3; bee_nest 10 clusters, spacing 23; calcite 11 clusters, spacing 3.8; quartz_block 12 clusters, spacing 11.4; podzol 15 clusters, spacing 6.7.
- 10 of 15 placed biomes are expressed; 1 listed biomes were not found.
- Render against world at 788 columns: height within 2 blocks 100%, biome match 100%.
- Landforms over the border (noise render): plains 0.6%, hills 0.8%, mountains 0.2%, plateaus 0.2%, basins 0.2%, islands 16.9%, water 0%, void 81.2%.
- Placeness: biome regions 4.3/km² (0 major, largest 0.5%), landform regions 3.1/km² (largest 81.0%), dominant place 81.0% of the border.

## Layers

- Columns by class: void 71%, roofed 0%, stacked 0%, overhang 1.7%, cave 2.3%, single 24.9%.
- Columns with each layer: sky 0%, surface 29%, underground 26.6%, roof 0%.
- Overhangs in 1.7% of columns, stacked layers in 0%, cave pockets in 2.6%.

## Places

- **surface** (210 cells, 2 types, silhouette 0.352):
  - type 1: 74.8% of cells; biome meadow; ground grass_block; top grass_block; height 162; canopy 2% (layer 11%); open to the sky 98% (layer 89%) — camera [-438.5,179,2872.5]
  - type 2: 25.2% of cells; biome flower_forest; ground grass_block; top grass_block; height 165; trees per cell 3.4 (layer 1.0); canopy 37% (layer 11%); ground plants 12% (layer 22%); open to the sky 63% (layer 89%) — camera [-3671.5,177,886.5]
  - landmark at [-1064,2568]: biome adventure:hanging_gardens (type meadow); relief over 16 blocks 46.0 (type 8.0); relief over the patch 110.0 (type 20.0); overhangs 22% (type 0%)
  - landmark at [-1464,-3480]: biome adventure:magnolia_woodland (type meadow); overhangs 37% (type 0%); layer present in 41% (type 100%); notable-block density (log) 0.6 (type 0.3)
- **sky**: no cells.
- **underground** (190 cells, 2 types, silhouette 0.378):
  - type 1: 58.9% of cells; notableId cave_vines_plant; height 165 — camera [-2264.5,173.88,-8.5]
  - type 2: 41.1% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 159; cave air 7% (layer 3%); cave pockets 22% (layer 9%) — camera [3281.5,156.88,-3950.5]
  - landmark at [1880,1272]: notableId rooted_dirt (type cave_vines_plant); notable-block density (log) 0.1 (type 1.2); height 56.3 (type 181.0); relief over 16 blocks 31.0 (type 14.0)
  - landmark at [3784,3560]: notableId rooted_dirt (type cave_vines_plant); neighbour step 1.5 (type 0.3); cave air 17% (type 2%); relief over 16 blocks 30.0 (type 14.0)
  - landmark at [-1048,2568]: notableId rooted_dirt (type cave_vines_plant); cave pockets 62% (type 13%); relief over 16 blocks 35.0 (type 14.0); cave air 7% (type 2%)
  - landmark at [-632,3496]: notableId rooted_dirt (type cave_vines_plant); relief over 16 blocks 33.0 (type 12.0); notable-block density (log) 1.9 (type 1.1); neighbour step 1.0 (type 0.4)
  - landmark at [3288,-3864]: notableId rooted_dirt (type cave_vines_plant); cave air 17% (type 2%); cave pockets 37% (type 13%); relief over 16 blocks 4.0 (type 14.0)
- **roof**: no cells.

## Biome expression

10 of 15 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 62380 columns; placement land 2.2%, void 97.8%; snow 0%, canopy 1.7%, trees 0/ha; top grass_block 2.1%, cherry_leaves 0%, short_grass 0%
- minecraft:cherry_grove: expressed, 18523 columns; placement land 20.7%, void 79.3%; snow 0%, canopy 10.6%, trees 18.2/ha; top grass_block 9.9%, pink_petals 6%, short_grass 2.3%
- minecraft:flower_forest: expressed, 18332 columns; placement land 58.5%, void 41.5%; snow 0%, canopy 32.5%, trees 146.4/ha; top grass_block 36.3%, oak_leaves 16%, birch_leaves 3%
- minecraft:meadow: expressed, 15446 columns; placement land 69.6%, void 30.4%; snow 0%, canopy 0%, trees 0/ha; top grass_block 54.9%, short_grass 6.3%, wildflowers 3.4%
- minecraft:plains: expressed, 15029 columns; placement land 56.8%, void 43.2%; snow 0%, canopy 0.5%, trees 2.3/ha; top grass_block 44.3%, short_grass 10.3%, tall_grass 1.1%
- adventure:sky_highlands: expressed, 9514 columns; placement land 51.6%, void 48.4%; snow 0%, canopy 4%, trees 16.3/ha; top grass_block 36.9%, short_grass 8.3%, oak_leaves 2%
- adventure:lavender_fields: expressed, 9283 columns; placement land 66.2%, void 33.8%; snow 0%, canopy 4.7%, trees 19.5/ha; top grass_block 35.8%, allium 14.2%, short_grass 8.7%
- adventure:windswept_isles: too few columns to judge, 6224 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:snowy_slopes: too few columns to judge, 4928 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:sunflower_plains: expressed, 4704 columns; placement land 20.1%, void 79.9%; snow 0%, canopy 1.2%, trees 0/ha; top grass_block 17.1%, short_grass 2.4%, sunflower 0.3%
- adventure:golden_skygrove: expressed, 3758 columns; placement land 44.3%, void 55.7%; snow 0%, canopy 54.1%, trees 120.3/ha; top oak_leaves 19.5%, grass_block 13.7%, yellow_poplar_leaves 4.4%
- adventure:magnolia_woodland: expressed, 2375 columns; placement land 48.8%, void 51.2%; snow 0%, canopy 17%, trees 60.4/ha; top grass_block 30.4%, short_grass 6.3%, pale_oak_leaves 5.3%
- minecraft:birch_forest: too few columns to judge, 1296 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:forest: too few columns to judge, 992 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- adventure:desert_oasis: too few columns to judge, 16 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none

Listed in the definition, found in no sampled column: adventure:alpine_meadow.


v2 biomes not found in any sampled column: adventure:alpine_meadow

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:plains | minecraft:plains | 4.9 | 15029 | expressed | - |
| O2 | minecraft:forest | minecraft:forest | 0.0 | 992 | too few columns to judge | - |
| O3 | minecraft:birch_forest | minecraft:birch_forest | 0.0 | 1296 | too few columns to judge | - |
| O4 | minecraft:meadow | minecraft:meadow | 6.2 | 15446 | expressed | - |
| O5 | minecraft:flower_forest | minecraft:flower_forest | 6.2 | 18332 | expressed | - |
| O6 | minecraft:cherry_grove | minecraft:cherry_grove | 2.2 | 18523 | expressed | - |
| O7 | terralith:blooming_valley | minecraft:flower_forest | 6.2 | 18332 | expressed | - |
| O8 | terralith:sakura_valley | minecraft:cherry_grove | 2.2 | 18523 | expressed | - |
| O9 | terralith:sakura_grove | minecraft:cherry_grove | 2.2 | 18523 | expressed | - |
| O10 | terralith:alpine_grove | minecraft:snowy_slopes | 0.0 | 4928 | too few columns to judge | - |
| O11 | terralith:lush_valley | minecraft:plains | 4.9 | 15029 | expressed | - |
| O12 | terralith:lavender_valley | adventure:lavender_fields | 3.6 | 9283 | expressed | - |
| O13 | terralith:blooming_plateau | adventure:alpine_meadow | 0.0 | 0 | listed, not found | in the v2 definition, in no sampled column |
| O14 | terralith:shrubland | minecraft:plains | 4.9 | 15029 | expressed | - |
| O15 | terralith:skylands_spring | adventure:sky_highlands | 2.8 | 9514 | expressed | - |
| O16 | terralith:skylands_autumn | adventure:golden_skygrove | 1.0 | 3758 | expressed | - |
| O17 | terralith:skylands_summer | adventure:hanging_gardens | 0.8 | 62380 | expressed | - |
| O18 | terralith:skylands_winter | adventure:windswept_isles | 0.0 | 6224 | too few columns to judge | - |
| O19 | terralith:mirage_isles | adventure:desert_oasis | 0.0 | 16 | too few columns to judge | - |
| O20 | minecraft:sunflower_plains | minecraft:sunflower_plains | 0.5 | 4704 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:magnolia_woodland | 0.7 | 2375 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:meadow 6.2%, minecraft:flower_forest 6.2%, minecraft:plains 4.9% | type `sky_islands` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:stone", "defaultFluid": "minecraft:water", "seaLevel": -64, "endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 71.0% (`surface.voidPercent`) | |
| M:canopy | 3.2% (`surface.canopyPercent`) | |
| M:relief | median 0.0 blocks a patch; height {"p5": 67.0, "p25": 126.0, "p50": 168.0, "p75": 201.0, "p95": 230.0, "max": 254.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 29.0, "underground": 26.6, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 71.0, "roofed": 0.0, "stacked": 0.0, "overhang": 1.7, "cave": 2.3, "single": 24.9} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 97.6%
- S:b — 1.21.1 biomes expressed: 70.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (29.0% of columns)

## Shots

### the_shattered_skies-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 6.4%; void 57.9%; mid: cherry_leaves 12.3%, grass_block 11.3%; far: cave_vines_plant 2.2%, grass_block 1.7%; biomes: cherry_grove 76.2%, adventure:hanging_gardens 23.8%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-far.png`

### the_shattered_skies-surface-t1

Picked for: surface type 1: 79.0% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 165
Census: sky 18.1%; near: grass_block 45.4%, short_grass 15.5%; mid: grass_block 10.9%, short_grass 2.4%; biomes: meadow 95%, adventure:hanging_gardens 5%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-surface-t1.png`

### the_shattered_skies-underground-t1

Picked for: underground type 1: 62.6% of cells; notableId cave_vines_plant; height 172
Census: sky 0%; void 1.4%; near: stone 39.2%, dirt 24.8%; mid: stone 1.1%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-underground-t1.png`

### the_shattered_skies-surface-t2

Picked for: surface type 2: 21.0% of cells; biome flower_forest; ground grass_block; top grass_block; height 166; trees per cell 3.8 (layer 1.0); canopy 41% (layer 11%); ground plants 12% (layer 22%); open to the sky 59% (layer 89%)
Census: sky 0.5%; unloaded 0.3%; near: grass_block 34.2%, birch_leaves 30.6%; biomes: flower_forest 100%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-surface-t2.png`

### the_shattered_skies-underground-t2

Picked for: underground type 2: 37.4% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 158; cave air 6% (layer 2%); cave pockets 15% (layer 6%)
Census: sky 0%; near: grass_block 48.7%, stone 44.5%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-underground-t2.png`

### the_shattered_skies-surface-lm1

Picked for: surface landmark: biome cherry_grove (type meadow); ground dark_oak_planks (type grass_block); top dark_oak_planks (type grass_block); trees per cell 9.0 (type 0.0); overhangs 65% (type 0%); neighbour step 2.4 (type 0.4)
Census: sky 8.4%; unloaded 40.9%; near: dark_oak_planks 31.9%; mid: grass_block 4.3%, pink_petals 1%; far: grass_block 6.3%, cherry_leaves 1.8%; biomes: cherry_grove 98%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-surface-lm1.png`

### the_shattered_skies-feature-snow_block

Picked for: snow_block: 8 clusters in 2 patches, median 18.0 blocks, surface layer
Census: sky 0%; near: snow_block 28.3%, grass_block 25%; mid: stone 3%, calcite 2.4%; biomes: snowy_slopes 50%, adventure:hanging_gardens 40.1%, adventure:windswept_isles 9.9%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-feature-snow_block.png`

### the_shattered_skies-feature-vine

Picked for: vine: 10 clusters in 2 patches, median 1.0 blocks, surface layer
Census: sky 45.3%; near: grass_block 21.1%, dirt 12.8%; biomes: plains 51.1%, adventure:hanging_gardens 48.9%
Image: `/tmp/claude-0/w10c3/reviews/the_shattered_skies/screens/the_shattered_skies-feature-vine.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
