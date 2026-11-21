# Review packet: the_pale_reach

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_pale_reach.json`

> The pale desolate reach — calcite crags floating in white silence. The skylands' frozen edge, where even the animal kingdoms thin out. Remains and birdcages drift in the whiteness, built by hands that left no other trace.

Biomes: terralith:skylands_winter, terralith:frozen_cliffs, terralith:wintry_lowlands, nullscape:void_barrens, minecraft:ice_spikes, terralith:white_cliffs, paradise_lost:calcite_craglands, minecraft:snowy_slopes, minecraft:frozen_peaks

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_pale_reach.json`

> The pale desolate reach — calcite crags floating in white silence. The skylands' frozen edge, where even the animal kingdoms thin out. Remains and birdcages drift in the whiteness, built by hands that left no other trace.

Biomes: adventure:chalk_cliffs, minecraft:snowy_slopes, minecraft:ice_spikes, minecraft:frozen_peaks, minecraft:snowy_plains, adventure:windswept_isles, minecraft:jagged_peaks, minecraft:end_barrens

Terrain: `{"base": "floating_islands", "params": {"coverage": 0.25, "size": 0.8}, "scales": {"climate": 4}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: ?

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_pale_reach/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_pale_reach/layers`

## Summary

- Top blocks seen from the sky: snow 20.4%, calcite 10.3%, packed_ice 2.5%, stone 2.2%.
- Surface biomes: adventure:chalk_cliffs 10.6%, snowy_slopes 8.7%, snowy_plains 5.2%, frozen_peaks 4.6%, ice_spikes 4.3%.
- Ground blocks: snow_block 15.6%, calcite 10.4%, grass_block 6.6%, stone 3.4%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 24% (a snowing climate over 27.8%); tree canopy 0.6%; bare rock 2.5%; no ground (void) 59.3%.
- Trees: 2.6 per ha (spruce_log 0.9/ha, mean spacing 20.2 blocks, pale_oak_log 0.9/ha, mean spacing 7.5 blocks, oak_log 0.8/ha, mean spacing 2.5 blocks); ground plants grass 0.1%, other 0.1%, flower 0%.
- Surface height p5 119, median 161, p95 214, max 235; median relief per patch 20 blocks; slopes under 15° 25.8%, over 45° 19.8%, cliffs 3%; above the clouds 21.5%, below sea level 0%.
- Underground: 3.5% open, 0% liquid; cave biomes adventure:chalk_cliffs 27.8%, snowy_slopes 19.3%, snowy_plains 13.8%, ice_spikes 10.8%.
- Notable clusters, fewest first: lava 2 clusters, spacing n/a; closed_eyeblossom 4 clusters, spacing 3.1; oak_fence 6 clusters, spacing 2.7; iron_chain 6 clusters, spacing 2.7; pale_moss_block 7 clusters, spacing 13.3; poppy 9 clusters, spacing 3.5.
- 9 of 9 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1077 columns: height within 2 blocks 97.6%, biome match 100%.
- Landforms over the border (noise render): plains 4.3%, hills 5.4%, mountains 1.2%, plateaus 2.3%, basins 2.5%, islands 12.3%, water 0%, void 72.1%.
- Placeness: biome regions 8.0/km² (6 major, largest 2.9%), landform regions 4.8/km² (largest 71.7%), dominant place 71.7% of the border.

## Layers

- Columns by class: void 59.3%, roofed 0%, stacked 1.1%, overhang 4%, cave 3.8%, single 31.8%.
- Columns with each layer: sky 1.1%, surface 40.7%, underground 37%, roof 0%.
- Overhangs in 4.2% of columns, stacked layers in 1.1%, cave pockets in 4.4%.

## Places

- **surface** (300 cells, 5 types, silhouette 0.44):
  - type 1: 40.7% of cells; biome snowy_slopes; ground snow_block; top snow; height 146; snow or ice 97% (layer 58%) — camera [-7.5,146,-647.5]
  - type 2: 32.3% of cells; biome adventure:chalk_cliffs; ground calcite; top calcite; height 166; snow or ice 1% (layer 58%) — camera [264.5,162,-151.5]
  - type 3: 12.0% of cells; biome snowy_plains; ground grass_block; top snow; height 182; snow or ice 93% (layer 58%) — camera [-455.5,206,-296.5]
  - type 4: 8.3% of cells; biome frozen_peaks; ground snow_block; top snow; height 144; neighbour step 14.6 (layer 2.0); relief over 16 blocks 187.8 (layer 29.7); relief over the patch 195.0 (layer 47.6); notable-block density (log) 1.1 (layer 0.6); ground plants 2% (layer 1%); open to the sky 75% (layer 96%); stacked layers 25% (layer 3%) — camera [-295.5,156,-152.5]
  - type 5: 6.7% of cells; biome adventure:windswept_isles; ground stone; top stone; height 188; trees per cell 0.5 (layer 0.1); snow or ice 9% (layer 58%); bare rock 76% (layer 6%) — camera [-647.5,221,-535.5]
  - landmark at [-552,-312]: ground oak_planks (type grass_block); top oak_planks (type snow); stacked layers 83% (type 0%); trees per cell 4.0 (type 0.0); open to the sky 17% (type 100%)
  - landmark at [-296,-120]: ground oak_planks (type snow_block); top oak_planks (type snow); trees per cell 3.0 (type 0.0); stacked layers 57% (type 10%); ground plants 9% (type 0%)
  - landmark at [72,-472]: ground oak_planks (type snow_block); top oak_planks (type snow); trees per cell 4.0 (type 0.0); stacked layers 71% (type 0%); open to the sky 29% (type 100%)
  - landmark at [72,-456]: ground oak_planks (type snow_block); top oak_planks (type snow); stacked layers 65% (type 0%); open to the sky 35% (type 100%); layer present in 33% (type 100%)
  - landmark at [-520,-296]: neighbour step 25.1 (type 0.2); relief over 16 blocks 207.0 (type 12.0); relief over the patch 213.0 (type 38.0)
- **sky** (11 cells, 3 types, silhouette 0.471):
  - type 1: 36.4% of cells; biome snowy_plains; ground grass_block; height 201; neighbour step 0.5 (layer 0.8); notable-block density (log) 0.6 (layer 1.1) — camera [-535.5,199,-317.5]
  - type 2: 36.4% of cells; biome frozen_peaks; ground snow_block; height 146; notable-block density (log) 1.6 (layer 1.1) — camera [-311.5,154,-118.5]
  - type 3: 27.3% of cells; biome ice_spikes; ground snow_block; height 209; layer present in 30% (layer 41%); neighbour step 1.5 (layer 0.8); relief over 16 blocks 19.7 (layer 15.1) — camera [-199.5,209,-231.5]
- **underground** (279 cells, 3 types, silhouette 0.241):
  - type 1: 50.9% of cells; notableId coal_ore; biome frozen_peaks; height 167; cave pockets 1% (layer 11%) — camera [406.5,173.88,-309.5]
  - type 2: 26.2% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 183 — camera [419.5,162.88,-316.5]
  - type 3: 22.9% of cells; notableId coal_ore; biome snowy_slopes; height 140; cave air 7% (layer 3%); cave pockets 22% (layer 11%) — camera [77.5,97.88,466.5]
  - landmark at [72,-472]: notableId oak_planks (type coal_ore); cave air 43% (type 6%); relief over 16 blocks 99.0 (type 9.0); layer present in 36% (type 100%)
  - landmark at [-408,88]: notableId packed_ice (type coal_ore); biome frozen_peaks (type snowy_slopes); notable-block density (log) 3.5 (type 0.5); cave pockets 72% (type 19%); cave air 16% (type 6%)
  - landmark at [-296,-120]: notableId iron_ore (type coal_ore); biome frozen_peaks (type none); relief over 16 blocks 145.0 (type 12.0); neighbour step 23.6 (type 0.5); layer present in 48% (type 84%)
  - landmark at [232,424]: notableId snow_block (type coal_ore); cave liquid 0% (type 0%); cave air 28% (type 6%); notable-block density (log) 1.7 (type 0.5)
  - landmark at [-168,-232]: notableId packed_ice (type coal_ore); biome ice_spikes (type adventure:chalk_cliffs); notable-block density (log) 1.8 (type 0.7); layer present in 62% (type 100%); cave air 9% (type 3%)
- **roof**: no cells.

## Biome expression

9 of 9 placed biomes are expressed.

- adventure:chalk_cliffs: expressed, 42560 columns; placement land 42%, void 58%; snow 0.3%, canopy 0.4%, trees 0.6/ha; top calcite 40.5%, diorite 1.1%, pale_oak_leaves 0.2%
- minecraft:snowy_slopes: expressed, 35932 columns; placement land 40.8%, void 58.5%, sky 0.7%; snow 97.9%, canopy 0%, trees 2.7/ha; top snow 36.7%, powder_snow 2.5%, snow_block 0.8%
- minecraft:frozen_peaks: expressed, 25960 columns; placement land 29.5%, void 68.1%, sky 2.4%; snow 91.7%, canopy 0%, trees 6.5/ha; top snow 18.3%, packed_ice 8.3%, oak_planks 2.2%
- minecraft:ice_spikes: expressed, 21361 columns; placement land 34%, void 64.4%, sky 1.7%; snow 95%, canopy 0.1%, trees 0/ha; top snow 22.7%, packed_ice 9.5%, oak_planks 1.5%
- minecraft:snowy_plains: expressed, 12756 columns; placement land 68.8%, void 26.7%, sky 4.4%; snow 87.8%, canopy 0.8%, trees 9.1/ha; top snow 60.2%, oak_planks 4.3%, grass_block 2.8%
- minecraft:end_barrens: expressed, 10992 columns; placement land 25%, void 75%; snow 1.9%, canopy 0%, trees 0/ha; top grass_block 24.6%, snow 0.4%, snow_block 0.1%
- adventure:windswept_isles: expressed, 8929 columns; placement land 59.1%, void 40.7%, sky 0.2%; snow 20.7%, canopy 4%, trees 20.8/ha; top stone 39.9%, snow 11.8%, spruce_leaves 2.3%
- minecraft:jagged_peaks: expressed, 8864 columns; placement land 36.3%, void 63.7%; snow 98.2%, canopy 0%, trees 0/ha; top snow 31.9%, snow_block 3.8%, stone 0.5%
- minecraft:pale_garden: expressed, 2656 columns; placement land 34.2%, void 65.8%; snow 0%, canopy 63.7%, trees 154/ha; top pale_oak_leaves 21.8%, grass_block 6.9%, pale_moss_block 2.8%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:skylands_winter | adventure:windswept_isles | 3.1 | 8929 | expressed | - |
| O2 | terralith:frozen_cliffs | minecraft:jagged_peaks | 1.9 | 8864 | expressed | - |
| O3 | terralith:wintry_lowlands | minecraft:snowy_plains | 5.2 | 12756 | expressed | - |
| O4 | nullscape:void_barrens | minecraft:end_barrens | 1.6 | 10992 | expressed | - |
| O5 | minecraft:ice_spikes | minecraft:ice_spikes | 4.3 | 21361 | expressed | - |
| O6 | terralith:white_cliffs | adventure:chalk_cliffs | 10.6 | 42560 | expressed | - |
| O7 | paradise_lost:calcite_craglands | adventure:windswept_isles | 3.1 | 8929 | expressed | - |
| O8 | minecraft:snowy_slopes | minecraft:snowy_slopes | 8.7 | 35932 | expressed | - |
| O9 | minecraft:frozen_peaks | minecraft:frozen_peaks | 4.6 | 25960 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:pale_garden | 0.5 | 2656 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:chalk_cliffs 10.6%, minecraft:snowy_slopes 8.7%, minecraft:snowy_plains 5.2% | type `sky_islands` |
| M:snow | 24.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:stone", "defaultFluid": "minecraft:water", "seaLevel": -64, "endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 59.3% (`surface.voidPercent`) | |
| M:canopy | 0.6% (`surface.canopyPercent`) | |
| M:relief | median 20.0 blocks a patch; height {"p5": 119.0, "p25": 139.0, "p50": 161.0, "p75": 185.0, "p95": 214.0, "max": 235.0} (`terrain`) | |
| M:layers | {"sky": 1.1, "surface": 40.7, "underground": 37.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#E8F0F5", "fogColor": "#E8F0F5", "ambientLight": 0.3, "fixedTime": 6000}` |
| M:classes | {"void": 59.3, "roofed": 0.0, "stacked": 1.1, "overhang": 4.0, "cave": 3.8, "single": 31.8} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 98.8%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (40.7% of columns)

## Shots

### the_pale_reach-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 10%; void 55.5%; mid: packed_ice 5.6%, snow 2.4%; far: packed_ice 10.5%, snow 4%; biomes: ice_spikes 82.3%, adventure:chalk_cliffs 13.7%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-far.png`

### the_pale_reach-surface-t1

Picked for: surface type 1: 64.3% of cells; biome snowy_slopes; ground snow_block; top snow; height 149; snow or ice 94% (layer 62%)
Census: sky 36%; void 1.3%; near: snow 31.6%, snow_block 20%; far: stone 3.9%, snow_block 3.1%; biomes: snowy_slopes 88.8%, ice_spikes 7.8%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-surface-t1.png`

### the_pale_reach-sky-t1

Picked for: sky type 1: 34.2% of cells; biome snowy_slopes; ground snow_block; height 137
Census: sky 21.8%; void 1.3%; near: snow 28.6%, snow_block 21.1%; mid: packed_ice 5.7%, snow_block 1.4%; far: packed_ice 1.5%; biomes: snowy_slopes 64.6%, ice_spikes 35.4%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-sky-t1.png`

### the_pale_reach-surface-t2

Picked for: surface type 2: 24.7% of cells; biome adventure:chalk_cliffs; ground calcite; top calcite; height 163; relief over the patch 40.2 (layer 91.6); snow or ice 3% (layer 62%)
Census: sky 33.5%; void 0.4%; unloaded 19.4%; near: calcite 29%; mid: calcite 1.1%; far: stone 8.2%, calcite 4.1%; biomes: adventure:chalk_cliffs 86.1%, snowy_slopes 9.6%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-surface-t2.png`

### the_pale_reach-surface-t3

Picked for: surface type 3: 11.0% of cells; biome adventure:windswept_isles; ground stone; top stone; height 180; snow or ice 8% (layer 62%); bare rock 79% (layer 10%)
Census: sky 34.5%; void 1.7%; near: stone 22.7%, spruce_leaves 19.8%; far: stone 3.9%, grass_block 2.6%; biomes: adventure:windswept_isles 87.8%, snowy_plains 7.1%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-surface-t3.png`

### the_pale_reach-sky-t2

Picked for: sky type 2: 18.4% of cells; biome snowy_plains; ground grass_block; height 169; layer present in 31% (layer 43%); neighbour step 0.6 (layer 0.9)
Census: sky 35.1%; void 11.5%; near: grass_block 19.1%, snow 18.9%; mid: grass_block 3.9%, snow 3.7%; far: stone 2.8%, snow_block 1.2%; biomes: snowy_plains 88.2%, snowy_slopes 8%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-sky-t2.png`

### the_pale_reach-surface-lm1

Picked for: surface landmark: biome frozen_peaks (type snowy_slopes); ground oak_planks (type snow_block); top oak_planks (type snow); trees per cell 3.0 (type 0.0); stacked layers 57% (type 0%); neighbour step 24.1 (type 0.3)
Census: sky 12.1%; void 9.9%; near: oak_planks 56.7%, oak_log 9.7%; mid: oak_planks 1.5%; far: stone 3.4%, tuff_bricks 1%; biomes: frozen_peaks 95%
Image: `/tmp/claude-0/w10c3/reviews/the_pale_reach/screens/the_pale_reach-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
