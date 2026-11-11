# Review packet: the_aviary

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/paradise_lost.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_aviary.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: adventure:sky_highlands, adventure:wisteria_grove, adventure:golden_skygrove, minecraft:cherry_grove, minecraft:meadow, minecraft:flower_forest, minecraft:plains, minecraft:forest, minecraft:birch_forest, adventure:cloud_reef, adventure:windswept_isles, minecraft:snowy_slopes, minecraft:dark_forest, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "setting": "extreme", "params": {"size": 3, "coverage": 0.45, "thickness": 16, "underside": 80}, "scales": {"climate": 4}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fav/full/the_aviary/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fav/full/the_aviary/layers`

## Summary

- Top blocks seen from the sky: grass_block 34.9%, oak_leaves 6.6%, pink_petals 3.3%, short_grass 3.3%.
- Surface biomes: adventure:sky_highlands 12.6%, cherry_grove 12.4%, adventure:wisteria_grove 6.7%, meadow 5.8%, adventure:hanging_gardens 5.6%.
- Ground blocks: grass_block 55.3%, snow_block 3.1%, stone 2.9%, moss_block 0.9%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 3.3% (a snowing climate over 6.2%); tree canopy 11.6%; bare rock 2.7%; no ground (void) 35.5%.
- Trees: 35.7 per ha (oak_log 25.5/ha, mean spacing 7.1 blocks, birch_log 3.9/ha, mean spacing 10.2 blocks, dark_oak_log 2.9/ha, mean spacing 7.2 blocks, cherry_log 2.4/ha, mean spacing 11.1 blocks); ground plants flower 5.8%, other 4.3%, grass 4%, mushroom 0%, bush 0%.
- Surface height p5 58, median 195, p95 250, max 257; median relief per patch 29 blocks; slopes under 15° 16.4%, over 45° 34.5%, cliffs 0.2%; above the clouds 51.7%, below sea level 0%.
- Underground: 2.7% open, 0% liquid; cave biomes adventure:hanging_gardens 100%.
- Notable clusters, fewest first: diamond_ore 2 clusters, spacing n/a; snow_block 3 clusters, spacing 8.6; lapis_ore 3 clusters, spacing 27.1; bee_nest 4 clusters, spacing 32.9; azalea 6 clusters, spacing 19.4; flowering_azalea 7 clusters, spacing 21.1.
- 10 of 14 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 727 columns: height within 2 blocks 99.6%, biome match 99.9%.
- Landforms over the border (noise render): plains 12.2%, hills 13.2%, mountains 3.1%, plateaus 4.6%, basins 4.3%, islands 6.9%, water 0%, void 55.8%.
- Placeness: biome regions 2.8/km² (11 major, largest 5.6%), landform regions 11.4/km² (largest 43.4%), dominant place 43.4% of the border.

## Layers

- Columns by class: void 35.5%, roofed 0%, stacked 0%, overhang 1.7%, cave 6.4%, single 56.4%.
- Columns with each layer: sky 0%, surface 64.5%, underground 62.1%, roof 0%.
- Overhangs in 1.7% of columns, stacked layers in 0%, cave pockets in 6.7%.

## Places

- **surface** (186 cells, 8 types, silhouette 0.315):
  - type 1: 23.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 177; neighbour step 0.3 (layer 0.6); trees per cell 0.2 (layer 1.3); canopy 3% (layer 18%); open to the sky 97% (layer 82%) — camera [-3609.5,141,2598.5]
  - type 2: 16.1% of cells; biome meadow; ground grass_block; top grass_block; height 160; neighbour step 1.4 (layer 0.6); relief over 16 blocks 35.6 (layer 17.4); relief over the patch 109.8 (layer 53.9); trees per cell 0.1 (layer 1.3); canopy 3% (layer 18%); ground plants 8% (layer 22%); open to the sky 97% (layer 82%) — camera [1080.5,174,-1687.5]
  - type 3: 15.6% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 199; trees per cell 2.9 (layer 1.3); canopy 52% (layer 18%); open to the sky 48% (layer 82%) — camera [-676.5,204,663.5]
  - type 4: 14.5% of cells; biome cherry_grove; ground grass_block; top grass_block; height 204; trees per cell 0.3 (layer 1.3); canopy 6% (layer 18%); ground plants 40% (layer 22%); open to the sky 94% (layer 82%) — camera [-82.5,220,2274.5]
  - type 5: 12.4% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 192; relief over 16 blocks 9.0 (layer 17.4); relief over the patch 24.4 (layer 53.9); notable-block density (log) 1.0 (layer 0.4) — camera [-3607.5,170,-1303.5]
  - type 6: 8.6% of cells; biome forest; ground grass_block; top oak_leaves; height 99; relief over 16 blocks 10.6 (layer 17.4); trees per cell 6.0 (layer 1.3); canopy 55% (layer 18%); ground plants 50% (layer 22%); open to the sky 45% (layer 82%) — camera [2201.5,98,2648.5]
  - type 7: 4.8% of cells; biome snowy_slopes; ground snow_block; top snow_block; height 56; relief over the patch 30.0 (layer 53.9); trees per cell 0.0 (layer 1.3); canopy 0% (layer 18%); ground plants 0% (layer 22%); snow or ice 95% (layer 5%); open to the sky 100% (layer 82%); overhangs 11% (layer 3%); cave pockets 26% (layer 10%) — camera [2680.5,56,-1751.5]
  - type 8: 4.8% of cells; biome adventure:windswept_isles; ground stone; top stone; height 218; neighbour step 0.3 (layer 0.6); trees per cell 0.3 (layer 1.3); canopy 3% (layer 18%); ground plants 0% (layer 22%); bare rock 85% (layer 4%); open to the sky 97% (layer 82%); cave pockets 0% (layer 10%) — camera [3704.5,236,-662.5]
  - landmark at [632,-1032]: biome adventure:hanging_gardens (type meadow); neighbour step 3.8 (type 1.4); layer present in 48% (type 100%); relief over the patch 184.0 (type 66.0)
  - landmark at [2136,-2568]: biome dark_forest (type adventure:golden_skygrove); top dark_oak_leaves (type oak_leaves); layer present in 69% (type 100%); canopy 82% (type 48%); open to the sky 18% (type 52%)
  - landmark at [2120,-2552]: biome dark_forest (type meadow); layer present in 52% (type 100%); overhangs 13% (type 0%); canopy 39% (type 0%)
  - landmark at [2136,-2584]: biome dark_forest (type adventure:golden_skygrove); top dark_oak_leaves (type oak_leaves); overhangs 14% (type 0%); neighbour step 1.0 (type 0.3); canopy 73% (type 48%)
  - landmark at [1688,536]: biome flower_forest (type adventure:golden_skygrove); top grass_block (type oak_leaves); overhangs 39% (type 0%); cave pockets 54% (type 0%); trees per cell 5.0 (type 3.0)
- **sky**: no cells.
- **underground** (182 cells, 2 types, silhouette 0.377):
  - type 1: 58.8% of cells; notableId cave_vines_plant; height 183
  - type 2: 41.2% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 162; cave air 5% (layer 2%); cave pockets 24% (layer 10%) — camera [-290.5,171.88,-1386.5]
  - landmark at [632,-1032]: neighbour step 4.3 (type 0.3); layer present in 36% (type 100%); relief over 16 blocks 55.0 (type 18.0)
  - landmark at [632,-1016]: notableId rooted_dirt (type cave_vines_plant); neighbour step 2.0 (type 0.3); relief over 16 blocks 45.0 (type 18.0); height 99.9 (type 205.2)
  - landmark at [2696,-1752]: height 52.6 (type 205.2); notable-block density (log) 0.0 (type 0.9); cave pockets 46% (type 18%)
  - landmark at [2712,-1736]: notableId rooted_dirt (type cave_vines_plant); height 61.9 (type 205.2); cave pockets 49% (type 18%); notable-block density (log) 0.3 (type 0.9)
  - landmark at [2712,-1752]: notableId emerald_ore (type cave_vines_plant); height 60.9 (type 205.2); notable-block density (log) 0.0 (type 0.9); cave pockets 45% (type 18%)
- **roof**: no cells.

## Biome expression

10 of 14 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 21683 columns; placement land 18.5%, void 81.5%; snow 0%, canopy 3.5%, trees 5/ha; top grass_block 16.1%, oak_leaves 0.3%, azure_bluet 0.3%
- minecraft:cherry_grove: expressed, 10913 columns; placement land 80.9%, void 19.1%; snow 0%, canopy 14.8%, trees 18.1/ha; top grass_block 39.1%, pink_petals 21.4%, cherry_leaves 12%
- adventure:sky_highlands: expressed, 9016 columns; placement land 100%; snow 0%, canopy 3.1%, trees 12.2/ha; top grass_block 77.4%, short_grass 11.2%, oak_leaves 3.1%
- adventure:wisteria_grove: expressed, 6645 columns; placement land 72.2%, void 27.8%; snow 0%, canopy 24%, trees 87.6/ha; top grass_block 30.5%, vine 16.2%, oak_leaves 10.8%
- minecraft:forest: expressed, 4320 columns; placement land 92.3%, void 7.7%; snow 0%, canopy 47.7%, trees 215.6/ha; top oak_leaves 36.6%, grass_block 36%, leaf_litter 10.8%
- minecraft:meadow: placed, not expressed, 4319 columns; placement land 96.4%, void 3.6%; snow 0%, canopy 0%, trees 0/ha; top grass_block 87.8%, short_grass 3.6%, wildflowers 2.1%; **features name trees, none on 0.4 ha of land**
- adventure:golden_skygrove: expressed, 3747 columns; placement land 100%; snow 0%, canopy 46.9%, trees 112.1/ha; top grass_block 44.6%, oak_leaves 41.7%, podzol 5.2%
- minecraft:birch_forest: too few columns to judge, 2304 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:snowy_slopes: expressed, 2249 columns; placement land 100%; snow 97.5%, canopy 0%, trees 0/ha; top snow_block 93.6%, powder_snow 3.9%, grass_block 1.1%
- adventure:windswept_isles: expressed, 2162 columns; placement land 100%; snow 6.1%, canopy 3.2%, trees 13.9/ha; top stone 85%, snow 6.1%, spruce_leaves 3%
- minecraft:flower_forest: expressed, 1794 columns; placement land 82.3%, void 17.7%; snow 0%, canopy 45.4%, trees 169.3/ha; top grass_block 40.9%, oak_leaves 26.4%, birch_leaves 11%
- minecraft:dark_forest: expressed, 1563 columns; placement land 100%; snow 0%, canopy 66.4%, trees 179.1/ha; top dark_oak_leaves 59.3%, grass_block 22.7%, leaf_litter 4.5%
- minecraft:plains: too few columns to judge, 672 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- adventure:cloud_reef: too few columns to judge, 37 columns; placement land 100%; snow 51.4%, canopy 0%, trees 0/ha; top snow_block 51.4%, calcite 43.2%, grass_block 5.4%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:sky_highlands | 12.6 | 9016 | expressed | - |
| V2 | minecraft:cherry_grove | 12.4 | 10913 | expressed | - |
| V3 | adventure:wisteria_grove | 6.7 | 6645 | expressed | - |
| V4 | minecraft:meadow | 5.8 | 4319 | placed, not expressed | features name trees, none on 0.4 ha of land |
| V5 | adventure:hanging_gardens | 5.6 | 21683 | expressed | - |
| V6 | minecraft:forest | 5.6 | 4320 | expressed | - |
| V7 | adventure:golden_skygrove | 5.2 | 3747 | expressed | - |
| V8 | minecraft:snowy_slopes | 3.1 | 2249 | expressed | - |
| V9 | adventure:windswept_isles | 3.0 | 2162 | expressed | - |
| V10 | minecraft:dark_forest | 2.2 | 1563 | expressed | - |
| V11 | minecraft:flower_forest | 2.1 | 1794 | expressed | - |
| V12 | adventure:cloud_reef | 0.1 | 37 | too few columns to judge | - |
| V13 | minecraft:birch_forest | 0.0 | 2304 | too few columns to judge | - |
| V14 | minecraft:plains | 0.0 | 672 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 12.6%, minecraft:cherry_grove 12.4%, adventure:wisteria_grove 6.7% | type `the_aviary` |
| M:snow | 3.3% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 35.5% (`surface.voidPercent`) | |
| M:canopy | 11.6% (`surface.canopyPercent`) | |
| M:relief | median 29.0 blocks a patch; height {"p5": 58.0, "p25": 135.0, "p50": 195.0, "p75": 217.0, "p95": 250.0, "max": 257.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 64.5, "underground": 62.1, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 35.5, "roofed": 0.0, "stacked": 0.0, "overhang": 1.7, "cave": 6.4, "single": 56.4} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: surface present (64.5% of columns)

## Shots

### the_aviary-surface-t1

Picked for: surface type 1: 23.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 177; neighbour step 0.3 (layer 0.6); trees per cell 0.2 (layer 1.3); canopy 3% (layer 18%); open to the sky 97% (layer 82%)
Census: sky 12.6%; unloaded 0.1%; near: grass_block 50.9%, short_grass 17.8%; mid: short_grass 5.5%, grass_block 4.3%; biomes: adventure:sky_highlands 100%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t1.png`

### the_aviary-underground-t2

Picked for: underground type 2: 41.2% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 162; cave air 5% (layer 2%); cave pockets 24% (layer 10%)
Census: sky 0%; near: stone 62.8%, grass_block 36.2%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-underground-t2.png`

### the_aviary-surface-t6

Picked for: surface type 6: 8.6% of cells; biome forest; ground grass_block; top oak_leaves; height 99; relief over 16 blocks 10.6 (layer 17.4); trees per cell 6.0 (layer 1.3); canopy 55% (layer 18%); ground plants 50% (layer 22%); open to the sky 45% (layer 82%)
Census: sky 0%; near: oak_leaves 35.4%, oak_log 27.2%; biomes: forest 100%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t6.png`

### the_aviary-surface-t2

Picked for: surface type 2: 16.1% of cells; biome meadow; ground grass_block; top grass_block; height 160; neighbour step 1.4 (layer 0.6); relief over 16 blocks 35.6 (layer 17.4); relief over the patch 109.8 (layer 53.9); trees per cell 0.1 (layer 1.3); canopy 3% (layer 18%); ground plants 8% (layer 22%); open to the sky 97% (layer 82%)
Census: sky 15.8%; unloaded 21%; near: grass_block 39%, short_grass 7.9%; far: grass_block 11%, dirt 1.6%; biomes: meadow 96.4%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t2.png`

### the_aviary-surface-t7

Picked for: surface type 7: 4.8% of cells; biome snowy_slopes; ground snow_block; top snow_block; height 56; relief over the patch 30.0 (layer 53.9); trees per cell 0.0 (layer 1.3); canopy 0% (layer 18%); ground plants 0% (layer 22%); snow or ice 95% (layer 5%); open to the sky 100% (layer 82%); overhangs 11% (layer 3%); cave pockets 26% (layer 10%)
Census: sky 0%; unloaded 16.1%; near: snow_block 62.5%; mid: snow_block 17.8%; far: snow_block 1.6%; biomes: snowy_slopes 99.2%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-t7.png`

### the_aviary-surface-lm1

Picked for: surface landmark: biome adventure:hanging_gardens (type meadow); neighbour step 3.8 (type 1.4); layer present in 48% (type 100%); relief over the patch 184.0 (type 66.0)
Census: sky 1.8%; void 2.1%; unloaded 30.3%; near: grass_block 31.9%, dirt 17.4%; mid: grass_block 1.3%; far: grass_block 5%, dirt 1.3%; biomes: adventure:hanging_gardens 76.2%, meadow 23.8%
Image: `/tmp/claude-0/w10c1/reviews/the_aviary/screens/the_aviary-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
