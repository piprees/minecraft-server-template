# Review packet: overworld

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/overworld.json`

> The world where it all begins. Villagers settled the plains and humans arrived through the stone gate. Safe at spawn, but the ancient cities near the border predate everyone — and something stirs in them.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/overworld.json`

> The world where it all begins. Villagers settled the plains and humans arrived through the stone gate. Safe at spawn, but the ancient cities near the border predate everyone — and something stirs in them.

Biomes: none listed

Terrain: `{"scales": {"continents": 1.3, "erosion": 2.08, "ridges": 1.39, "climate": 1.67}, "landShare": 0.65}`; generator `{"type": "noise", "preset": "overworld"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fin/full/overworld/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fin/full/overworld/layers`

## Summary

- Top blocks seen from the sky: water 36.6%, snow 24.6%, grass_block 13.6%, oak_leaves 5.9%.
- Surface biomes: snowy_taiga 15.8%, ocean 13.9%, forest 12.7%, plains 7.8%, deep_ocean 6.5%.
- Ground blocks: grass_block 46.3%, gravel 26.4%, sand 9.8%, snow_block 6.9%.
- Liquid cover 36.6% water, 0% lava, 0% other; snow or ice 28.2% (a snowing climate over 28.2%); tree canopy 21.1%; bare rock 0.9%; no ground (void) 0%.
- Trees: 87.9 per ha (spruce_log 50.5/ha, mean spacing 4.6 blocks, oak_log 26.2/ha, mean spacing 5.2 blocks, birch_log 4.6/ha, mean spacing 9.8 blocks, mangrove_log 4.1/ha, mean spacing 6.3 blocks); ground plants other 7%, grass 3%, bush 0.1%, mushroom 0.1%, flower 0%.
- Surface height p5 38, median 67, p95 121, max 168; median relief per patch 18 blocks; slopes under 15° 54.8%, over 45° 11%, cliffs 0.8%; above the clouds 0%, below sea level 38.7%.
- Underground: 9.5% open, 1.9% liquid; cave biomes snowy_taiga 15.5%, forest 13.3%, taiga 11.7%, plains 9.4%.
- Notable clusters, fewest first: dirt_path 2 clusters, spacing n/a; poppy 3 clusters, spacing n/a; ice 5 clusters, spacing 11.9; spawner 7 clusters, spacing n/a; chest 7 clusters, spacing 6.3; amethyst_cluster 8 clusters, spacing 22.3.
- 17 of 20 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 97%, biome match 100%.
- Landforms over the border (noise render): plains 22.3%, hills 15.4%, mountains 4.1%, plateaus 1%, basins 1.7%, islands 6%, water 49.5%, void 0%.
- Placeness: biome regions 8.1/km² (19 major, largest 4.4%), landform regions 7.8/km² (largest 19.0%), dominant place 19.0% of the border.

## Layers

- Columns by class: void 0%, roofed 0%, stacked 0%, overhang 11.6%, cave 59.3%, single 29%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 0%.
- Overhangs in 11.6% of columns, stacked layers in 0%, cave pockets in 67.7%.

## Places

- **surface** (279 cells, 7 types, silhouette 0.472):
  - type 1: 37.3% of cells; biome ocean; ground gravel; top water; height 48; trees per cell 0.0 (layer 2.3); canopy 0% (layer 21%); ground plants 0% (layer 10%); liquid cover 96% (layer 37%); snow or ice 0% (layer 28%); open to the sky 100% (layer 79%) — camera [-359.5,63,-4007.5]
  - type 2: 16.5% of cells; biome snowy_taiga; ground grass_block; top snow; height 89; relief over the patch 11.0 (layer 23.3); trees per cell 5.9 (layer 2.3); canopy 52% (layer 21%); liquid cover 0% (layer 37%); snow or ice 98% (layer 28%); open to the sky 48% (layer 79%) — camera [-533.5,72,-568.5]
  - type 3: 14.3% of cells; biome forest; ground grass_block; top oak_leaves; height 77; trees per cell 5.6 (layer 2.3); canopy 51% (layer 21%); ground plants 48% (layer 10%); liquid cover 1% (layer 37%); snow or ice 0% (layer 28%); open to the sky 49% (layer 79%) — camera [2488.5,75,-3112.5]
  - type 4: 11.5% of cells; biome ice_spikes; ground snow_block; top snow; height 111; neighbour step 0.8 (layer 0.3); relief over 16 blocks 19.0 (layer 8.1); relief over the patch 53.7 (layer 23.3); trees per cell 0.2 (layer 2.3); notable-block density (log) 0.9 (layer 0.5); canopy 1% (layer 21%); ground plants 0% (layer 10%); liquid cover 1% (layer 37%); snow or ice 99% (layer 28%); open to the sky 99% (layer 79%) — camera [-566.5,127,1719.5]
  - type 5: 11.1% of cells; biome plains; ground grass_block; top grass_block; height 70; trees per cell 0.3 (layer 2.3); notable-block density (log) 0.2 (layer 0.5); canopy 1% (layer 21%); liquid cover 0% (layer 37%); snow or ice 5% (layer 28%); bare rock 7% (layer 1%); open to the sky 99% (layer 79%) — camera [712.5,72,1848.5]
  - type 6: 5.7% of cells; biome taiga; ground grass_block; top spruce_leaves; height 69; trees per cell 5.3 (layer 2.3); notable-block density (log) 0.1 (layer 0.5); canopy 52% (layer 21%); liquid cover 1% (layer 37%); snow or ice 0% (layer 28%); open to the sky 47% (layer 79%); stacked layers 0% (layer 0%) — camera [-1942.5,78,-904.5]
  - type 7: 3.6% of cells; biome mangrove_swamp; ground mud; top mangrove_leaves; height 63; neighbour step 1.2 (layer 0.3); relief over 16 blocks 22.0 (layer 8.1); relief over the patch 46.4 (layer 23.3); notable-block density (log) 1.1 (layer 0.5); canopy 51% (layer 21%); snow or ice 0% (layer 28%); open to the sky 49% (layer 79%); overhangs 32% (layer 12%); stacked layers 0% (layer 0%) — camera [3784.5,63,2244.5]
  - landmark at [-648,-1128]: biome frozen_ocean (type ice_spikes); ground packed_ice (type snow_block); top packed_ice (type snow); relief over 16 blocks 45.0 (type 14.0); overhangs 55% (type 8%); neighbour step 1.9 (type 0.7)
  - landmark at [56,632]: biome grove (type ice_spikes); ground stone (type snow_block); top powder_snow (type snow); relief over 16 blocks 91.0 (type 14.0); relief over the patch 106.0 (type 23.0); overhangs 49% (type 8%)
  - landmark at [24,632]: biome grove (type ice_spikes); ground stone (type snow_block); top powder_snow (type snow); relief over the patch 106.0 (type 23.0); overhangs 57% (type 8%); notable-block density (log) 1.8 (type 0.6)
  - landmark at [-3048,-1544]: biome beach (type ocean); ground sand (type gravel); top sand (type water); cave pockets 5% (type 67%); liquid cover 21% (type 100%); relief over 16 blocks 19.0 (type 3.0)
  - landmark at [-4056,3800]: biome beach (type ocean); ground sand (type gravel); top sand (type water); overhangs 61% (type 0%); liquid cover 40% (type 100%); cave pockets 43% (type 67%)
- **sky**: no cells.
- **underground** (279 cells, 8 types, silhouette 0.184):
  - type 1: 19.7% of cells; notableId coal_ore; biome forest; height 74 — camera [-817.5,-42.12,3477.5]
  - type 2: 14.3% of cells; notableId clay; biome lush_caves; height 53; notable-block density (log) 1.7 (layer 1.1); cave liquid 6% (layer 2%) — camera [-4002.5,-26.12,-250.5]
  - type 3: 14.3% of cells; notableId copper_ore; biome plains; height 66; notable-block density (log) 0.9 (layer 1.1); cave pockets 85% (layer 68%) — camera [708.5,-24.12,1841.5]
  - type 4: 12.5% of cells; notableId iron_ore; biome ocean; height 49; notable-block density (log) 0.8 (layer 1.1) — camera [-337.5,-34.12,-3989.5]
  - type 5: 12.2% of cells; notableId coal_ore; biome snowy_taiga; height 98 — camera [-2157.5,-0.12,-273.5]
  - type 6: 11.5% of cells; notableId copper_ore; biome deep_ocean; height 51; cave air 1% (layer 9%); cave pockets 17% (layer 68%) — camera [-4030.5,-35.12,3815.5]
  - type 7: 9.7% of cells; notableId coal_ore; biome ice_spikes; height 115; neighbour step 0.8 (layer 0.3); relief over 16 blocks 19.9 (layer 8.1); cave air 16% (layer 9%) — camera [-563.5,28.88,1720.5]
  - type 8: 5.7% of cells; notableId copper_ore; biome mangrove_swamp; height 63; neighbour step 1.1 (layer 0.3); relief over 16 blocks 22.9 (layer 8.1); cave liquid 6% (layer 2%); cave pockets 89% (layer 68%) — camera [3777.5,-10.12,2228.5]
  - landmark at [56,632]: notableId powder_snow (type coal_ore); biome grove (type ice_spikes); relief over 16 blocks 91.0 (type 14.0); cave air 50% (type 4%); neighbour step 1.4 (type 0.7)
  - landmark at [-3240,1256]: notableId lava (type copper_ore); biome taiga (type mangrove_swamp); notable-block density (log) 3.4 (type 0.8); cave liquid 17% (type 2%); cave air 36% (type 15%)
  - landmark at [-648,-1128]: notableId packed_ice (type copper_ore); biome frozen_ocean (type none); relief over 16 blocks 45.0 (type 2.0); neighbour step 1.9 (type 0.1); notable-block density (log) 2.8 (type 0.7)
  - landmark at [40,648]: notableId sculk (type coal_ore); biome deep_dark (type ice_spikes); cave air 46% (type 4%); notable-block density (log) 3.0 (type 1.2); height 145.8 (type 115.7)
  - landmark at [40,632]: notableId sculk_vein (type coal_ore); biome deep_dark (type ice_spikes); cave air 55% (type 4%); neighbour step 1.2 (type 0.7); cave pockets 100% (type 71%)
- **roof**: no cells.

## Biome expression

17 of 20 placed biomes are expressed.

- minecraft:snowy_taiga: expressed, 11312 columns; placement land 100%; snow 98.5%, canopy 53.4%, trees 238.7/ha; top snow 96.3%, spruce_leaves 2.2%, fern 0.7%
- minecraft:ocean: expressed, 9952 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 99.9%, seagrass 0%, grass_block 0%
- minecraft:forest: expressed, 9088 columns; placement land 99.6%, water 0.4%; snow 0%, canopy 50%, trees 224.2/ha; top oak_leaves 42.3%, grass_block 32.8%, leaf_litter 14.4%
- minecraft:plains: expressed, 5568 columns; placement land 99.9%, water 0.1%; snow 0%, canopy 1.3%, trees 12.6/ha; top grass_block 71.3%, short_grass 21.1%, tall_grass 1.9%
- minecraft:deep_ocean: expressed, 4608 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:ice_spikes: placed, not expressed, 4608 columns; placement land 100%; snow 100%, canopy 0%, trees 0/ha; top snow 69.7%, packed_ice 30.3%; **features name trees, none on 0.5 ha of land**
- minecraft:taiga: expressed, 4352 columns; placement land 84.4%, water 15.6%; snow 0%, canopy 53.9%, trees 223.4/ha; top spruce_leaves 44.9%, grass_block 37.1%, water 15.6%
- minecraft:cold_ocean: expressed, 4000 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%, ice 0%
- minecraft:beach: placed, not expressed, 3984 columns; placement land 46.5%, water 53.5%; snow 0%, canopy 0%, trees 0/ha; top water 53.5%, sand 46.3%, grass_block 0.3%; **mostly water**
- minecraft:mangrove_swamp: expressed, 2308 columns; placement land 80.2%, water 19.6%, sky 0.2%; snow 0%, canopy 65.3%, trees 156.7/ha; top mangrove_leaves 42.9%, water 19.6%, vine 17.2%
- minecraft:lukewarm_ocean: expressed, 2304 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:snowy_plains: expressed, 1920 columns; placement land 100%; snow 96.9%, canopy 3.2%, trees 10.4/ha; top snow 94.6%, ice 2.3%, stone 1.7%
- minecraft:snowy_slopes: expressed, 1792 columns; placement land 100%; snow 100%, canopy 0.6%, trees 5.6/ha; top snow 82.2%, powder_snow 17.8%
- minecraft:dark_forest: expressed, 1504 columns; placement land 91.2%, water 8.8%; snow 0%, canopy 66.4%, trees 175.1/ha; top dark_oak_leaves 43.8%, grass_block 21.1%, oak_leaves 14.5%
- minecraft:river: expressed, 1408 columns; placement land 10.4%, water 89.6%; snow 0%, canopy 0%, trees 0/ha; top water 87.9%, grass_block 7.8%, sand 2%
- minecraft:windswept_forest: expressed, 808 columns; placement land 99%, sky 1%; snow 0%, canopy 22.1%, trees 75/ha; top grass_block 73.4%, dark_oak_leaves 11.1%, oak_leaves 5.8%
- minecraft:stony_shore: expressed, 800 columns; placement land 74%, water 26%; snow 0.9%, canopy 0%, trees 0/ha; top stone 64.3%, water 26%, gravel 6.6%
- minecraft:frozen_ocean: expressed, 608 columns; placement land 34.9%, water 65.1%; snow 34.9%, canopy 0%, trees 0/ha; top water 65.1%, packed_ice 31.3%, snow_block 2.3%
- minecraft:grove: expressed, 497 columns; placement land 99.8%, sky 0.2%; snow 99.8%, canopy 15.1%, trees 80.6/ha; top powder_snow 66.6%, snow 32.6%, spruce_leaves 0.4%
- minecraft:frozen_peaks: too few columns to judge, 16 columns; placement land 100%; snow 100%, canopy 0%, trees 0/ha; top snow 81.3%, packed_ice 18.8%
- minecraft:lush_caves: underground only (594 cave samples)
- minecraft:dripstone_caves: underground only (216 cave samples)
- minecraft:sulfur_caves: underground only (93 cave samples)
- minecraft:deep_dark: underground only (1262 cave samples)


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:snowy_taiga | 15.8 | 11312 | expressed | - |
| V2 | minecraft:ocean | 13.9 | 9952 | expressed | - |
| V3 | minecraft:forest | 12.7 | 9088 | expressed | - |
| V4 | minecraft:plains | 7.8 | 5568 | expressed | - |
| V5 | minecraft:deep_ocean | 6.5 | 4608 | expressed | - |
| V6 | minecraft:ice_spikes | 6.5 | 4608 | placed, not expressed | features name trees, none on 0.5 ha of land |
| V7 | minecraft:taiga | 6.1 | 4352 | expressed | - |
| V8 | minecraft:cold_ocean | 5.6 | 4000 | expressed | - |
| V9 | minecraft:beach | 5.6 | 3984 | placed, not expressed | mostly water |
| V10 | minecraft:mangrove_swamp | 3.2 | 2308 | expressed | - |
| V11 | minecraft:lukewarm_ocean | 3.2 | 2304 | expressed | - |
| V12 | minecraft:snowy_plains | 2.7 | 1920 | expressed | - |
| V13 | minecraft:snowy_slopes | 2.5 | 1792 | expressed | - |
| V14 | minecraft:dark_forest | 2.1 | 1504 | expressed | - |
| V15 | minecraft:river | 2.0 | 1408 | expressed | - |
| V16 | minecraft:windswept_forest | 1.1 | 808 | expressed | - |
| V17 | minecraft:stony_shore | 1.1 | 800 | expressed | - |
| V18 | minecraft:frozen_ocean | 0.9 | 608 | expressed | - |
| V19 | minecraft:grove | 0.7 | 497 | expressed | - |
| V20 | minecraft:frozen_peaks | 0.0 | 16 | too few columns to judge | - |
| V21 | minecraft:lush_caves | 0.0 | 0 | underground only | - |
| V22 | minecraft:dripstone_caves | 0.0 | 0 | underground only | - |
| V23 | minecraft:sulfur_caves | 0.0 | 0 | underground only | - |
| V24 | minecraft:deep_dark | 0.0 | 0 | underground only | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:snowy_taiga 15.8%, minecraft:ocean 13.9%, minecraft:forest 12.7% | type `overworld` |
| M:snow | 28.2% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 36.6% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 21.1% (`surface.canopyPercent`) | |
| M:relief | median 18.0 blocks a patch; height {"p5": 38.0, "p25": 50.0, "p50": 67.0, "p75": 77.0, "p95": 121.0, "max": 168.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 100.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 0.0, "stacked": 0.0, "overhang": 11.6, "cave": 59.3, "single": 29.0} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), underground present (100.0% of columns)

## Shots

### overworld-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 18.6%; mid: dark_oak_leaves 3.4%, snow 1%; far: water 25.4%, grass_block 15.4%; biomes: dark_forest 35.9%, windswept_forest 16%, river 14%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-far.png`

### overworld-surface-t1

Picked for: surface type 1: 37.3% of cells; biome ocean; ground gravel; top water; height 48; trees per cell 0.0 (layer 2.3); canopy 0% (layer 21%); ground plants 0% (layer 10%); liquid cover 96% (layer 37%); snow or ice 0% (layer 28%); open to the sky 100% (layer 79%)
Census: sky 27.6%; unloaded 15.6%; near: water 51.6%; mid: water 3.9%; biomes: ocean 97.8%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-t1.png`

### overworld-surface-t4

Picked for: surface type 4: 11.5% of cells; biome ice_spikes; ground snow_block; top snow; height 111; neighbour step 0.8 (layer 0.3); relief over 16 blocks 19.0 (layer 8.1); relief over the patch 53.7 (layer 23.3); trees per cell 0.2 (layer 2.3); notable-block density (log) 0.9 (layer 0.5); canopy 1% (layer 21%); ground plants 0% (layer 10%); liquid cover 1% (layer 37%); snow or ice 99% (layer 28%); open to the sky 99% (layer 79%)
Census: sky 29.2%; unloaded 18.7%; near: packed_ice 31.1%, snow 4.7%; mid: packed_ice 7.5%, snow 1.1%; far: packed_ice 2.4%, snow_block 1%; biomes: ice_spikes 97.6%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-t4.png`

### overworld-surface-t2

Picked for: surface type 2: 16.5% of cells; biome snowy_taiga; ground grass_block; top snow; height 89; relief over the patch 11.0 (layer 23.3); trees per cell 5.9 (layer 2.3); canopy 52% (layer 21%); liquid cover 0% (layer 37%); snow or ice 98% (layer 28%); open to the sky 48% (layer 79%)
Census: sky 0.5%; near: spruce_leaves 36.6%, snow 31.2%; biomes: snowy_taiga 100%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-t2.png`

### overworld-surface-t3

Picked for: surface type 3: 14.3% of cells; biome forest; ground grass_block; top oak_leaves; height 77; trees per cell 5.6 (layer 2.3); canopy 51% (layer 21%); ground plants 48% (layer 10%); liquid cover 1% (layer 37%); snow or ice 0% (layer 28%); open to the sky 49% (layer 79%)
Census: sky 7.1%; unloaded 4.5%; near: grass_block 50%, oak_leaves 19.5%; biomes: forest 99.6%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-t3.png`

### overworld-surface-t7

Picked for: surface type 7: 3.6% of cells; biome mangrove_swamp; ground mud; top mangrove_leaves; height 63; neighbour step 1.2 (layer 0.3); relief over 16 blocks 22.0 (layer 8.1); relief over the patch 46.4 (layer 23.3); notable-block density (log) 1.1 (layer 0.5); canopy 51% (layer 21%); snow or ice 0% (layer 28%); open to the sky 49% (layer 79%); overhangs 32% (layer 12%); stacked layers 0% (layer 0%)
Census: sky 0%; near: water 36.7%, mangrove_leaves 31%; biomes: mangrove_swamp 100%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-t7.png`

### overworld-surface-lm1

Picked for: surface landmark: biome frozen_ocean (type ice_spikes); ground packed_ice (type snow_block); top packed_ice (type snow); relief over 16 blocks 45.0 (type 14.0); overhangs 55% (type 8%); neighbour step 1.9 (type 0.7)
Census: sky 18.7%; unloaded 4.3%; near: packed_ice 58.2%, water 6.8%; mid: packed_ice 5.2%, water 3.3%; biomes: frozen_ocean 97.6%
Image: `/tmp/claude-0/w10c1/reviews/overworld/screens/overworld-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
