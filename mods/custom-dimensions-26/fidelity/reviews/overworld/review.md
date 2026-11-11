# Review packet: overworld

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/overworld.json`

> The world where it all begins. Villagers settled the plains and humans arrived through the stone gate. Safe at spawn, but the ancient cities near the border predate everyone — and something stirs in them.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/overworld.json`

> The world where it all begins. Villagers settled the plains and humans arrived through the stone gate. Safe at spawn, but the ancient cities near the border predate everyone — and something stirs in them.

Biomes: none listed

Terrain: `{"scales": {"continents": 1.3, "erosion": 2.08, "ridges": 1.39, "climate": 4}, "landShare": 0.65, "climateShift": {"temperature": 0.3}}`; generator `{"type": "noise", "preset": "overworld"}`

Zones: none

## Survey

Dossier: `runs/fin/full/overworld/dossier.json`; layer maps in `runs/fin/full/overworld/layers`

## Summary

- Top blocks seen from the sky: water 36.4%, grass_block 26.9%, spruce_leaves 8.6%, oak_leaves 6.1%.
- Surface biomes: plains 18.5%, ocean 17.5%, taiga 12.5%, forest 12.3%, dappled_forest 6.5%.
- Ground blocks: grass_block 51%, gravel 29.8%, sand 6.5%, dirt 3%.
- Liquid cover 36.4% water, 0% lava, 0% other; snow or ice 6% (a snowing climate over 6%); tree canopy 20.2%; bare rock 1.9%; no ground (void) 0%.
- Trees: 82 per ha (spruce_log 44.4/ha, mean spacing 4.4 blocks, oak_log 25.5/ha, mean spacing 5.2 blocks, poplar_log 9.5/ha, mean spacing 5.8 blocks, birch_log 2.7/ha, mean spacing 11.9 blocks); ground plants other 7.2%, grass 5.4%, mushroom 0.3%, flower 0.2%, bush 0.2%.
- Surface height p5 38, median 67, p95 121, max 168; median relief per patch 15 blocks; slopes under 15° 57.2%, over 45° 8.4%, cliffs 0.6%; above the clouds 0%, below sea level 39%.
- Underground: 9.5% open, 1.9% liquid; cave biomes forest 25.8%, dappled_forest 13%, plains 9.8%, grove 9.5%.
- Notable clusters, fewest first: dirt_path 2 clusters, spacing n/a; poppy 6 clusters, spacing 29.3; spawner 7 clusters, spacing n/a; chest 7 clusters, spacing 6.3; amethyst_cluster 8 clusters, spacing 22.3; sandstone 8 clusters, spacing 9.7.
- 16 of 19 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 98.7%, biome match 100%.
- Landforms over the border (noise render): plains 22.3%, hills 15.4%, mountains 4.1%, plateaus 1%, basins 1.7%, islands 6%, water 49.5%, void 0%.
- Placeness: biome regions 6.6/km² (18 major, largest 8.3%), landform regions 7.8/km² (largest 19.0%), dominant place 19.0% of the border.

## Layers

- Columns by class: void 0%, roofed 0%, stacked 0%, overhang 11.2%, cave 59.6%, single 29.2%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 0%.
- Overhangs in 11.2% of columns, stacked layers in 0%, cave pockets in 67.7%.

## Places

- **surface** (279 cells, 7 types, silhouette 0.465):
  - type 1: 35.5% of cells; biome ocean; ground gravel; top water; height 47; trees per cell 0.0 (layer 2.1); canopy 0% (layer 20%); ground plants 0% (layer 13%); liquid cover 99% (layer 36%); open to the sky 100% (layer 80%) — camera [-359.5,63,-4007.5]
  - type 2: 24.7% of cells; biome plains; ground grass_block; top grass_block; height 77; trees per cell 0.5 (layer 2.1); canopy 5% (layer 20%); liquid cover 4% (layer 36%); open to the sky 95% (layer 80%) — camera [2504.5,74,-3112.5]
  - type 3: 14.7% of cells; biome taiga; ground grass_block; top spruce_leaves; height 90; trees per cell 6.5 (layer 2.1); canopy 55% (layer 20%); liquid cover 0% (layer 36%); open to the sky 45% (layer 80%) — camera [-533.5,72,-568.5]
  - type 4: 12.2% of cells; biome forest; ground grass_block; top oak_leaves; height 75; relief over the patch 31.2 (layer 20.1); trees per cell 5.4 (layer 2.1); canopy 50% (layer 20%); ground plants 47% (layer 13%); liquid cover 1% (layer 36%); open to the sky 50% (layer 80%); cave pockets 90% (layer 68%) — camera [1483.5,70,-872.5]
  - type 5: 6.1% of cells; biome dappled_forest; ground grass_block; top orange_poplar_leaves; height 85; trees per cell 3.8 (layer 2.1); canopy 60% (layer 20%); ground plants 31% (layer 13%); liquid cover 0% (layer 36%); open to the sky 40% (layer 80%) — camera [-231.5,95,2151.5]
  - type 6: 3.6% of cells; biome snowy_beach; ground sand; top snow; height 63; relief over the patch 7.0 (layer 20.1); trees per cell 0.2 (layer 2.1); notable-block density (log) 0.7 (layer 0.4); canopy 2% (layer 20%); ground plants 1% (layer 13%); liquid cover 3% (layer 36%); snow or ice 79% (layer 6%); open to the sky 98% (layer 80%); overhangs 25% (layer 11%); cave pockets 51% (layer 68%) — camera [-4023.5,64,3784.5]
  - type 7: 3.2% of cells; biome grove; ground snow_block; top snow; height 143; neighbour step 0.7 (layer 0.3); relief over 16 blocks 24.3 (layer 6.9); relief over the patch 106.0 (layer 20.1); trees per cell 3.8 (layer 2.1); notable-block density (log) 0.8 (layer 0.4); canopy 34% (layer 20%); ground plants 0% (layer 13%); liquid cover 0% (layer 36%); snow or ice 86% (layer 6%); open to the sky 66% (layer 80%); overhangs 23% (layer 11%); cave pockets 100% (layer 68%) — camera [24.5,145,648.5]
  - landmark at [3256,-440]: biome windswept_hills (type plains); ground stone (type grass_block); top stone (type grass_block); bare rock 94% (type 0%); neighbour step 1.9 (type 0.3); relief over 16 blocks 32.0 (type 8.0)
  - landmark at [56,632]: ground stone (type snow_block); top powder_snow (type snow); relief over 16 blocks 91.0 (type 19.0); overhangs 49% (type 4%); notable-block density (log) 1.3 (type 0.3)
  - landmark at [3784,2232]: biome swamp (type plains); top water (type grass_block); stacked layers 2% (type 0%); overhangs 49% (type 1%); neighbour step 1.0 (type 0.3)
  - landmark at [3800,2248]: biome swamp (type plains); top water (type grass_block); neighbour step 1.2 (type 0.3); relief over 16 blocks 24.0 (type 8.0); overhangs 29% (type 1%)
  - landmark at [3784,2248]: biome swamp (type plains); top water (type grass_block); relief over 16 blocks 23.0 (type 8.0); neighbour step 0.8 (type 0.3); notable-block density (log) 0.6 (type 0.1)
- **sky**: no cells.
- **underground** (279 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; notableId coal_ore; biome ocean; height 70 — camera [-817.5,-42.12,3477.5]
  - landmark at [56,632]: notableId powder_snow (type coal_ore); biome grove (type plains); relief over 16 blocks 91.0 (type 6.0); cave air 50% (type 6%); neighbour step 1.4 (type 0.3)
  - landmark at [40,632]: notableId clay (type coal_ore); biome deep_dark (type plains); cave air 54% (type 6%); notable-block density (log) 2.5 (type 0.9); height 137.0 (type 76.0)
  - landmark at [-3240,1256]: notableId lava (type coal_ore); biome forest (type plains); notable-block density (log) 3.4 (type 0.9); cave liquid 17% (type 0%); cave air 36% (type 6%)
- **roof**: no cells.

## Biome expression

16 of 19 placed biomes are expressed.

- minecraft:plains: expressed, 13232 columns; placement land 98.5%, water 1.5%; snow 0%, canopy 0.9%, trees 6.1/ha; top grass_block 75%, short_grass 18.8%, water 1.5%
- minecraft:ocean: expressed, 12464 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%, seagrass 0%, grass_block 0%
- minecraft:taiga: expressed, 8944 columns; placement land 99.5%, water 0.5%; snow 0.2%, canopy 55.5%, trees 262.9/ha; top spruce_leaves 55.2%, grass_block 42%, fern 0.8%
- minecraft:forest: expressed, 8816 columns; placement land 91.9%, water 8.1%; snow 0%, canopy 50.7%, trees 219.7/ha; top oak_leaves 41.9%, grass_block 31%, leaf_litter 11.7%
- minecraft:deep_ocean: expressed, 4608 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:dappled_forest: expressed, 4608 columns; placement land 100%; snow 0%, canopy 57.9%, trees 149.7/ha; top orange_poplar_leaves 32.1%, grass_block 31.8%, red_poplar_leaves 13.8%
- minecraft:cold_ocean: expressed, 4400 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:beach: placed, not expressed, 2960 columns; placement land 29.9%, water 70.1%; snow 0%, canopy 0%, trees 0/ha; top water 70.1%, sand 29.9%; **mostly water**
- minecraft:swamp: expressed, 2309 columns; placement land 74.7%, water 25.1%, sky 0.2%; snow 0%, canopy 37.2%, trees 87/ha; top grass_block 32.4%, water 24.7%, oak_leaves 22.5%
- minecraft:old_growth_pine_taiga: expressed, 2304 columns; placement land 100%; snow 0%, canopy 46.7%, trees 186.6/ha; top spruce_leaves 46.7%, podzol 26.7%, grass_block 14.8%
- minecraft:grove: expressed, 1969 columns; placement land 99.9%, sky 0.1%; snow 99.1%, canopy 34%, trees 152.4/ha; top snow 73.6%, powder_snow 23.7%, spruce_leaves 1.7%
- minecraft:snowy_beach: expressed, 1024 columns; placement land 100%; snow 100%, canopy 0.1%, trees 0/ha; top snow 94.6%, ice 5.3%, spruce_leaves 0.1%
- minecraft:windswept_hills: placed, not expressed, 808 columns; placement land 99%, sky 1%; snow 0%, canopy 0%, trees 0/ha; top stone 70.4%, diorite 13%, grass_block 12.4%; **mostly bare rock**
- minecraft:stony_shore: expressed, 800 columns; placement land 74%, water 26%; snow 0%, canopy 7.9%, trees 0/ha; top stone 59.5%, water 26%, gravel 6.6%
- minecraft:frozen_river: expressed, 704 columns; placement land 100%; snow 99%, canopy 0.4%, trees 0/ha; top ice 78.8%, snow 20.2%, sweet_berry_bush 0.6%
- minecraft:river: expressed, 704 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:snowy_taiga: expressed, 576 columns; placement land 100%; snow 97.2%, canopy 31.6%, trees 156.3/ha; top snow 93.8%, spruce_leaves 2.8%, large_fern 1.2%
- minecraft:meadow: expressed, 192 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top grass_block 68.8%, short_grass 15.6%, cornflower 6.3%
- minecraft:frozen_peaks: too few columns to judge, 16 columns; placement land 100%; snow 100%, canopy 0%, trees 0/ha; top snow 81.3%, packed_ice 18.8%
- minecraft:lush_caves: underground only (647 cave samples)
- minecraft:dripstone_caves: underground only (214 cave samples)
- minecraft:sulfur_caves: underground only (93 cave samples)
- minecraft:deep_dark: underground only (1262 cave samples)


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:plains | 18.5 | 13232 | expressed | - |
| V2 | minecraft:ocean | 17.5 | 12464 | expressed | - |
| V3 | minecraft:taiga | 12.5 | 8944 | expressed | - |
| V4 | minecraft:forest | 12.3 | 8816 | expressed | - |
| V5 | minecraft:deep_ocean | 6.5 | 4608 | expressed | - |
| V6 | minecraft:dappled_forest | 6.5 | 4608 | expressed | - |
| V7 | minecraft:cold_ocean | 6.2 | 4400 | expressed | - |
| V8 | minecraft:beach | 4.1 | 2960 | placed, not expressed | mostly water |
| V9 | minecraft:swamp | 3.2 | 2309 | expressed | - |
| V10 | minecraft:old_growth_pine_taiga | 3.2 | 2304 | expressed | - |
| V11 | minecraft:grove | 2.8 | 1969 | expressed | - |
| V12 | minecraft:snowy_beach | 1.4 | 1024 | expressed | - |
| V13 | minecraft:windswept_hills | 1.1 | 808 | placed, not expressed | mostly bare rock |
| V14 | minecraft:stony_shore | 1.1 | 800 | expressed | - |
| V15 | minecraft:frozen_river | 1.0 | 704 | expressed | - |
| V16 | minecraft:river | 1.0 | 704 | expressed | - |
| V17 | minecraft:snowy_taiga | 0.8 | 576 | expressed | - |
| V18 | minecraft:meadow | 0.3 | 192 | expressed | - |
| V19 | minecraft:frozen_peaks | 0.0 | 16 | too few columns to judge | - |
| V20 | minecraft:lush_caves | 0.0 | 0 | underground only | - |
| V21 | minecraft:dripstone_caves | 0.0 | 0 | underground only | - |
| V22 | minecraft:sulfur_caves | 0.0 | 0 | underground only | - |
| V23 | minecraft:deep_dark | 0.0 | 0 | underground only | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:plains 18.5%, minecraft:ocean 17.5%, minecraft:taiga 12.5% | type `overworld` |
| M:snow | 6.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 36.4% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 20.2% (`surface.canopyPercent`) | |
| M:relief | median 15.0 blocks a patch; height {"p5": 38.0, "p25": 50.0, "p50": 67.0, "p75": 77.0, "p95": 121.0, "max": 168.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 100.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 0.0, "stacked": 0.0, "overhang": 11.2, "cave": 59.6, "single": 29.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), underground present (100.0% of columns)

## Shots

### overworld-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 19%; mid: grass_block 3%, snow 1.8%; far: water 28.7%, grass_block 27.2%; biomes: plains 60.2%, windswept_hills 16.9%, river 14.5%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-far.png`

### overworld-surface-t1

Picked for: surface type 1: 35.5% of cells; biome ocean; ground gravel; top water; height 47; trees per cell 0.0 (layer 2.1); canopy 0% (layer 20%); ground plants 0% (layer 13%); liquid cover 99% (layer 36%); open to the sky 100% (layer 80%)
Census: sky 30.3%; unloaded 13.7%; near: water 51.6%; mid: water 3.9%; biomes: ocean 99.1%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-t1.png`

### overworld-underground-t1

Picked for: underground type 1: 100.0% of cells; notableId coal_ore; biome ocean; height 70
Census: sky 0%; near: deepslate 93.4%, tuff 3.3%; biomes: plains 100%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-underground-t1.png`

### overworld-surface-t2

Picked for: surface type 2: 24.7% of cells; biome plains; ground grass_block; top grass_block; height 77; trees per cell 0.5 (layer 2.1); canopy 5% (layer 20%); liquid cover 4% (layer 36%); open to the sky 95% (layer 80%)
Census: sky 27.7%; unloaded 6.5%; near: grass_block 37.3%, short_grass 13.2%; mid: short_grass 2.6%, water 1.7%; far: short_grass 1.2%, grass_block 1.1%; biomes: plains 96.8%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-t2.png`

### overworld-surface-t7

Picked for: surface type 7: 3.2% of cells; biome grove; ground snow_block; top snow; height 143; neighbour step 0.7 (layer 0.3); relief over 16 blocks 24.3 (layer 6.9); relief over the patch 106.0 (layer 20.1); trees per cell 3.8 (layer 2.1); notable-block density (log) 0.8 (layer 0.4); canopy 34% (layer 20%); ground plants 0% (layer 13%); liquid cover 0% (layer 36%); snow or ice 86% (layer 6%); open to the sky 66% (layer 80%); overhangs 23% (layer 11%); cave pockets 100% (layer 68%)
Census: sky 3.3%; unloaded 13.3%; near: spruce_leaves 36.9%, snow_block 22.5%; mid: snow_block 1.7%; far: spruce_leaves 1%; biomes: grove 82%, taiga 16.8%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-t7.png`

### overworld-surface-t3

Picked for: surface type 3: 14.7% of cells; biome taiga; ground grass_block; top spruce_leaves; height 90; trees per cell 6.5 (layer 2.1); canopy 55% (layer 20%); liquid cover 0% (layer 36%); open to the sky 45% (layer 80%)
Census: sky 0.7%; near: grass_block 56.1%, spruce_leaves 37.3%; biomes: taiga 100%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-t3.png`

### overworld-surface-t4

Picked for: surface type 4: 12.2% of cells; biome forest; ground grass_block; top oak_leaves; height 75; relief over the patch 31.2 (layer 20.1); trees per cell 5.4 (layer 2.1); canopy 50% (layer 20%); ground plants 47% (layer 13%); liquid cover 1% (layer 36%); open to the sky 50% (layer 80%); cave pockets 90% (layer 68%)
Census: sky 10.5%; unloaded 0.3%; near: oak_leaves 34.5%, grass_block 32.7%; mid: oak_leaves 1.7%; biomes: forest 100%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-t4.png`

### overworld-surface-lm1

Picked for: surface landmark: biome windswept_hills (type plains); ground stone (type grass_block); top stone (type grass_block); bare rock 94% (type 0%); neighbour step 1.9 (type 0.3); relief over 16 blocks 32.0 (type 8.0)
Census: sky 16.2%; near: stone 64.8%; mid: grass_block 10%, dirt 2.8%; biomes: windswept_hills 79.3%, plains 20.7%
Image: `/tmp/claude-0/w10c1r/reviews/overworld/screens/overworld-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
