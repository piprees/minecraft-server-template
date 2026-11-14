# Review packet: the_glacial_drift

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_glacial_drift.json`

> A glacial drift among the skylands — frozen paradise islands calving into the void. The oldest animals shelter here in tundra that predates every civilisation. Nothing was ever built; nothing needs to be.

Biomes: terralith:glacial_chasm, terralith:skylands_winter, nullscape:void_barrens, natures_spirit:tundra, terralith:frozen_cliffs, paradise_lost:autumnal_tundra, terralith:snowy_shield, minecraft:ice_spikes, paradise_lost:calcite_craglands, minecraft:frozen_peaks, natures_spirit:sleeted_slopes, minecraft:snowy_slopes

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_glacial_drift.json`

> A glacial drift among the skylands — frozen paradise islands calving into the void. The oldest animals shelter here in tundra that predates every civilisation. Nothing was ever built; nothing needs to be.

Biomes: minecraft:ice_spikes, minecraft:frozen_peaks, minecraft:snowy_slopes, adventure:windswept_isles, minecraft:snowy_taiga, adventure:highland_fells, minecraft:jagged_peaks, minecraft:end_barrens, adventure:golden_skygrove

Terrain: `{"base": "floating_islands", "params": {"coverage": 0.3, "spread": 40, "size": 1.8, "flatness": 0.4}, "scales": {"climate": 3}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_glacial_drift/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_glacial_drift/layers`

## Summary

- Top blocks seen from the sky: snow 22.6%, packed_ice 5.2%, stone 2.8%, grass_block 1.6%.
- Surface biomes: ice_spikes 9.4%, snowy_taiga 6.7%, frozen_peaks 4.5%, adventure:highland_fells 3.7%, adventure:windswept_isles 3.3%.
- Ground blocks: snow_block 12.9%, grass_block 11.6%, packed_ice 5.3%, stone 3.3%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 28% (a snowing climate over 31.9%); tree canopy 3.9%; bare rock 2.8%; no ground (void) 65.6%.
- Trees: 16.1 per ha (spruce_log 14.1/ha, mean spacing 4.8 blocks, oak_log 2/ha, mean spacing 6.1 blocks, poplar_log 0.1/ha, mean spacing n/a blocks); ground plants grass 0.6%, flower 0.3%, other 0.1%, bush 0%, mushroom 0%.
- Surface height p5 84, median 177, p95 251, max 266; median relief per patch 10 blocks; slopes under 15° 18.9%, over 45° 30.1%, cliffs 1%; above the clouds 31.5%, below sea level 0%.
- Underground: 7.7% open, 0% liquid; cave biomes ice_spikes 29.8%, snowy_taiga 21.2%, frozen_peaks 11.8%, adventure:highland_fells 11.2%.
- Notable clusters, fewest first: lava 4 clusters, spacing 8.1; powder_snow 5 clusters, spacing 6.3; dandelion 8 clusters, spacing 7.2; cobblestone 12 clusters, spacing 17; poppy 13 clusters, spacing 2.8; podzol 16 clusters, spacing 8.6.
- 8 of 9 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 937 columns: height within 2 blocks 95%, biome match 100%.
- Landforms over the border (noise render): plains 0.7%, hills 0.9%, mountains 0.2%, plateaus 0.3%, basins 0.3%, islands 24%, water 0%, void 73.7%.
- Placeness: biome regions 5.8/km² (0 major, largest 0.3%), landform regions 5.2/km² (largest 73.4%), dominant place 73.4% of the border.

## Layers

- Columns by class: void 65.6%, roofed 0%, stacked 0.1%, overhang 2.7%, cave 3.9%, single 27.7%.
- Columns with each layer: sky 0.1%, surface 34.4%, underground 31.6%, roof 0%.
- Overhangs in 2.7% of columns, stacked layers in 0.1%, cave pockets in 4.2%.

## Places

- **surface** (253 cells, 3 types, silhouette 0.44):
  - type 1: 66.0% of cells; biome ice_spikes; ground snow_block; top snow; height 165; trees per cell 0.0 (layer 1.1); canopy 0% (layer 11%); open to the sky 100% (layer 89%) — camera [3607.5,187,-3383.5]
  - type 2: 22.1% of cells; biome snowy_taiga; ground grass_block; top snow; height 187; neighbour step 0.4 (layer 0.8); relief over the patch 27.4 (layer 48.0); trees per cell 4.8 (layer 1.1); canopy 48% (layer 11%); open to the sky 52% (layer 89%) — camera [3320.5,213,1448.5]
  - type 3: 11.9% of cells; biome adventure:windswept_isles; ground stone; top stone; height 144; notable-block density (log) 0.2 (layer 0.8); snow or ice 6% (layer 82%); bare rock 68% (layer 8%) — camera [-519.5,173,248.5]
  - landmark at [-2824,568]: biome adventure:highland_fells (type ice_spikes); ground packed_ice (type snow_block); top packed_ice (type snow); notable-block density (log) 2.0 (type 0.6); neighbour step 2.0 (type 0.8); ground plants 9% (type 0%)
  - landmark at [1784,1096]: biome end_barrens (type ice_spikes); ground grass_block (type snow_block); top grass_block (type snow); snow or ice 13% (type 100%); overhangs 34% (type 5%); relief over the patch 28.0 (type 60.0)
  - landmark at [1816,1096]: biome end_barrens (type adventure:windswept_isles); ground grass_block (type stone); top grass_block (type stone); bare rock 0% (type 88%); cave pockets 33% (type 6%); overhangs 15% (type 0%)
  - landmark at [2712,1512]: biome adventure:highland_fells (type ice_spikes); ground grass_block (type snow_block); cave pockets 63% (type 0%); ground plants 14% (type 0%); notable-block density (log) 2.1 (type 0.6)
  - landmark at [3448,696]: biome adventure:highland_fells (type ice_spikes); ground grass_block (type snow_block); ground plants 14% (type 0%); height 98.2 (type 186.5); overhangs 23% (type 5%)
- **sky** (1 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome jagged_peaks; ground snow_block; height 183 — camera [2520.5,185,710.5]
- **underground** (234 cells, 7 types, silhouette 0.254):
  - type 1: 32.9% of cells; notableId coal_ore; biome frozen_peaks; height 184 — camera [3939.5,84.88,1348.5]
  - type 2: 20.5% of cells; notableId coal_ore; biome ice_spikes; height 189; relief over 16 blocks 20.9 (layer 15.4); notable-block density (log) 0.9 (layer 0.6); cave pockets 25% (layer 12%) — camera [3710.5,166.88,-3531.5]
  - type 3: 15.0% of cells; notableId iron_ore; biome end_barrens; height 144; layer present in 55% (layer 90%); notable-block density (log) 0.2 (layer 0.6); cave pockets 1% (layer 12%) — camera [1672.5,60.88,-1365.5]
  - type 4: 11.5% of cells; notableId coal_ore; biome snowy_taiga; height 175; neighbour step 0.3 (layer 0.8); relief over 16 blocks 8.7 (layer 15.4) — camera [3297.5,198.88,1425.5]
  - type 5: 11.5% of cells; notableId coal_ore; biome adventure:highland_fells; height 156; neighbour step 1.5 (layer 0.8); relief over 16 blocks 27.9 (layer 15.4); cave liquid 0% (layer 0%) — camera [3482.5,134.88,690.5]
  - type 6: 5.1% of cells; biome adventure:windswept_isles; notableId iron_ore; height 137; notable-block density (log) 0.1 (layer 0.6); cave air 12% (layer 6%); cave pockets 40% (layer 12%) — camera [116.5,94.88,2273.5]
  - type 7: 3.4% of cells; notableId iron_chain; biome jagged_peaks; height 92; trees per cell 0.1 (layer 0.0); notable-block density (log) 1.0 (layer 0.6); cave air 21% (layer 6%); cave liquid 0% (layer 0%); cave pockets 34% (layer 12%) — camera [-1895.5,7.88,2699.5]
  - landmark at [88,2280]: notableId calcite (type coal_ore); biome adventure:windswept_isles (type none); cave air 45% (type 0%); layer present in 52% (type 98%); height 102.9 (type 167.4)
  - landmark at [3720,-3512]: notableId snow_block (type coal_ore); cave pockets 75% (type 15%); notable-block density (log) 2.0 (type 0.9); cave air 27% (type 3%)
  - landmark at [2520,680]: notableId snow_block (type iron_chain); trees per cell 0.0 (type 1.0); relief over 16 blocks 36.0 (type 11.0); height 189.0 (type 83.1)
  - landmark at [-1160,-4056]: notableId packed_ice (type none); notable-block density (log) 2.0 (type 0.0); cave air 41% (type 0%); height 255.3 (type 123.0)
  - landmark at [2712,1512]: biome adventure:highland_fells (type ice_spikes); relief over 16 blocks 40.0 (type 15.0); cave pockets 63% (type 15%); cave air 25% (type 3%)
- **roof**: no cells.

## Biome expression

8 of 9 placed biomes are expressed.

- minecraft:ice_spikes: placed, not expressed, 41496 columns; placement land 39.2%, lava 0.1%, void 60.6%, sky 0.1%; snow 99.6%, canopy 0%, trees 0/ha; top snow 26.4%, packed_ice 12.8%, lava 0.1%; **features name trees, none on 1.6 ha of land**
- minecraft:frozen_peaks: expressed, 29424 columns; placement land 26.5%, void 73.5%; snow 100%, canopy 0%, trees 0/ha; top snow 17.3%, packed_ice 8.6%, ice 0.6%
- minecraft:snowy_taiga: expressed, 27280 columns; placement land 42.7%, void 57.3%; snow 98%, canopy 43.8%, trees 200.7/ha; top snow 40.2%, packed_ice 1%, spruce_leaves 0.7%
- minecraft:snowy_slopes: expressed, 21264 columns; placement land 12.9%, void 87.1%; snow 100%, canopy 0%, trees 0/ha; top snow 11.4%, packed_ice 1.3%, powder_snow 0.2%
- adventure:windswept_isles: expressed, 18800 columns; placement land 30.5%, void 69.5%; snow 7.9%, canopy 3.7%, trees 15.7/ha; top stone 25.6%, snow 2.3%, spruce_leaves 1.1%
- adventure:highland_fells: expressed, 16128 columns; placement land 40%, void 60%; snow 83%, canopy 0%, trees 0/ha; top snow 30.6%, short_grass 3.3%, packed_ice 2.6%
- minecraft:jagged_peaks: expressed, 9345 columns; placement land 46.9%, void 51.7%, sky 1.4%; snow 97.1%, canopy 0%, trees 0/ha; top snow 44.8%, oak_planks 1.3%, packed_ice 0.7%
- minecraft:end_barrens: expressed, 6800 columns; placement land 32.2%, void 67.8%; snow 3.9%, canopy 0.3%, trees 0/ha; top grass_block 30.7%, packed_ice 1.3%, podzol 0.2%
- adventure:golden_skygrove: expressed, 2416 columns; placement land 90.1%, void 9.9%; snow 0%, canopy 61.6%, trees 160.7/ha; top oak_leaves 52.6%, grass_block 27.6%, podzol 4.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:glacial_chasm | minecraft:frozen_peaks | 4.5 | 29424 | expressed | - |
| O2 | terralith:skylands_winter | adventure:windswept_isles | 3.3 | 18800 | expressed | - |
| O3 | nullscape:void_barrens | minecraft:end_barrens | 1.3 | 6800 | expressed | - |
| O4 | natures_spirit:tundra | adventure:highland_fells | 3.7 | 16128 | expressed | - |
| O5 | terralith:frozen_cliffs | minecraft:jagged_peaks | 2.5 | 9345 | expressed | - |
| O6 | paradise_lost:autumnal_tundra | adventure:golden_skygrove | 1.3 | 2416 | expressed | - |
| O7 | terralith:snowy_shield | minecraft:snowy_taiga | 6.7 | 27280 | expressed | - |
| O8 | minecraft:ice_spikes | minecraft:ice_spikes | 9.4 | 41496 | placed, not expressed | features name trees, none on 1.6 ha of land |
| O9 | paradise_lost:calcite_craglands | adventure:windswept_isles | 3.3 | 18800 | expressed | - |
| O10 | minecraft:frozen_peaks | minecraft:frozen_peaks | 4.5 | 29424 | expressed | - |
| O11 | natures_spirit:sleeted_slopes | minecraft:snowy_slopes | 1.6 | 21264 | expressed | - |
| O12 | minecraft:snowy_slopes | minecraft:snowy_slopes | 1.6 | 21264 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:ice_spikes 9.4%, minecraft:snowy_taiga 6.7%, minecraft:frozen_peaks 4.5% | type `sky_islands` |
| M:snow | 28.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:stone", "defaultFluid": "minecraft:water", "seaLevel": -64, "endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 65.6% (`surface.voidPercent`) | |
| M:canopy | 3.9% (`surface.canopyPercent`) | |
| M:relief | median 10.0 blocks a patch; height {"p5": 84.0, "p25": 129.0, "p50": 177.0, "p75": 199.0, "p95": 251.0, "max": 266.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 34.4, "underground": 31.6, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#BFD8E8", "fogColor": "#D8E8F0", "ambientLight": 0.3}` |
| M:classes | {"void": 65.6, "roofed": 0.0, "stacked": 0.1, "overhang": 2.7, "cave": 3.9, "single": 27.7} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 91.7%
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: sky present (34.4% of columns)

## Shots

### the_glacial_drift-surface-t1

Picked for: surface type 1: 66.0% of cells; biome ice_spikes; ground snow_block; top snow; height 165; trees per cell 0.0 (layer 1.1); canopy 0% (layer 11%); open to the sky 100% (layer 89%)
Census: sky 14.9%; unloaded 3.2%; near: packed_ice 30.5%, snow_block 24.5%; mid: packed_ice 3.6%; biomes: ice_spikes 100%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-surface-t1.png`

### the_glacial_drift-sky-t1

Picked for: sky type 1: 100.0% of cells; biome jagged_peaks; ground snow_block; height 183
Census: sky 15.8%; unloaded 3.6%; near: snow_block 43.2%, snow 27.3%; far: snow_block 1.9%; biomes: jagged_peaks 99.6%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-sky-t1.png`

### the_glacial_drift-underground-t1

Picked for: underground type 1: 32.9% of cells; notableId coal_ore; biome frozen_peaks; height 184
Census: sky 0.9%; void 12.7%; unloaded 32.9%; near: stone 46.8%; far: stone 5.9%; biomes: adventure:windswept_isles 100%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-underground-t1.png`

### the_glacial_drift-surface-t2

Picked for: surface type 2: 22.1% of cells; biome snowy_taiga; ground grass_block; top snow; height 187; neighbour step 0.4 (layer 0.8); relief over the patch 27.4 (layer 48.0); trees per cell 4.8 (layer 1.1); canopy 48% (layer 11%); open to the sky 52% (layer 89%)
Census: sky 9.2%; unloaded 1.8%; near: spruce_leaves 41.1%, snow 24%; biomes: snowy_taiga 100%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-surface-t2.png`

### the_glacial_drift-surface-t3

Picked for: surface type 3: 11.9% of cells; biome adventure:windswept_isles; ground stone; top stone; height 144; notable-block density (log) 0.2 (layer 0.8); snow or ice 6% (layer 82%); bare rock 68% (layer 8%)
Census: sky 19.9%; unloaded 7%; near: cobblestone 35.5%, stone 23.5%; far: stone 3.9%; biomes: adventure:windswept_isles 100%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-surface-t3.png`

### the_glacial_drift-underground-t3

Picked for: underground type 3: 15.0% of cells; notableId iron_ore; biome end_barrens; height 144; layer present in 55% (layer 90%); notable-block density (log) 0.2 (layer 0.6); cave pockets 1% (layer 12%)
Census: sky 0%; near: stone 70.4%, iron_ore 12.5%; mid: stone 13.6%; biomes: adventure:windswept_isles 100%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-underground-t3.png`

### the_glacial_drift-surface-lm2

Picked for: surface landmark: biome end_barrens (type ice_spikes); ground grass_block (type snow_block); top grass_block (type snow); snow or ice 13% (type 100%); overhangs 34% (type 5%); relief over the patch 28.0 (type 60.0)
Census: sky 23.7%; unloaded 20.9%; near: grass_block 43.4%, dirt 6.5%; mid: grass_block 1.3%; biomes: end_barrens 98%
Image: `/tmp/claude-0/w10c3/reviews/the_glacial_drift/screens/the_glacial_drift-surface-lm2.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
