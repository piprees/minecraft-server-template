# Review packet: the_abyssal_shrine

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: nullscape:shadowlands, minecraft:deep_ocean, minecraft:deep_cold_ocean, paradise_lost:continental_plateau, paradise_lost:highlands_grand_glade, minecraft:deep_dark, minecraft:dripstone_caves, paradise_lost:highlands_thicket, paradise_lost:calcite_craglands

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: minecraft:deep_cold_ocean, minecraft:deep_ocean, adventure:sky_highlands, adventure:chalk_cliffs, adventure:umbral_wastes

Terrain: `{"base": "basin_islands", "setting": "extreme", "layout": {"type": "ring", "centre": [0, 0], "radius": 300, "falloff": 160, "strength": 0.9}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fin/full/the_abyssal_shrine/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fin/full/the_abyssal_shrine/layers`

## Summary

- Top blocks seen from the sky: grass_block 21.7%, water 8.7%, blackstone 4.9%, short_grass 4.1%.
- Surface biomes: adventure:sky_highlands 20.7%, adventure:chalk_cliffs 9%, deep_ocean 8.1%, adventure:umbral_wastes 6.8%, deep_cold_ocean 2.4%.
- Ground blocks: grass_block 27.5%, gravel 7.1%, blackstone 5%, calcite 3.9%.
- Liquid cover 8.7% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0.8%; bare rock 5.1%; no ground (void) 53%.
- Trees: 3.3 per ha (oak_log 3.3/ha, mean spacing 14.8 blocks); ground plants grass 4.1%, flower 1%, other 0.5%.
- Surface height p5 100, median 185, p95 201, max 242; median relief per patch 40 blocks; slopes under 15° 40.9%, over 45° 36.3%, cliffs 7%; above the clouds 47.3%, below sea level 0%.
- Underground: 1.8% open, 1.7% liquid; cave biomes adventure:sky_highlands 43.6%, adventure:chalk_cliffs 17.4%, deep_ocean 17.2%, adventure:umbral_wastes 16.1%.
- Notable clusters, fewest first: clay 3 clusters, spacing n/a; magma_block 4 clusters, spacing 2.3; dandelion 4 clusters, spacing 4.3; poppy 6 clusters, spacing 4.2; lava 16 clusters, spacing 7.7; diamond_ore 26 clusters, spacing 4.2.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 543 columns: height within 2 blocks 98.5%, biome match 100%.
- Landforms over the border (noise render): plains 7.3%, hills 5.1%, mountains 1.7%, plateaus 1.5%, basins 1%, islands 0.6%, water 10.2%, void 72.5%.
- Placeness: biome regions 10.5/km² (11 major, largest 4.0%), landform regions 9.5/km² (largest 57.4%), dominant place 57.4% of the border.

## Layers

- Columns by class: void 53%, roofed 0%, stacked 0%, overhang 2.9%, cave 7.7%, single 36.4%.
- Columns with each layer: sky 0%, surface 47%, underground 46.1%, roof 0%.
- Overhangs in 2.9% of columns, stacked layers in 0%, cave pockets in 8.4%.

## Places

- **surface** (147 cells, 4 types, silhouette 0.434):
  - type 1: 51.7% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 160 — camera [-103.5,150,196.5]
  - type 2: 17.0% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 186; trees per cell 1.0 (layer 0.2); canopy 9% (layer 2%); ground plants 21% (layer 12%); open to the sky 91% (layer 98%) — camera [-279.5,196,200.5]
  - type 3: 16.3% of cells; biome deep_ocean; ground gravel; top water; height 134; ground plants 1% (layer 12%); liquid cover 85% (layer 17%); cave pockets 4% (layer 17%) — camera [40.5,150,360.5]
  - type 4: 15.0% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 199; liquid cover 1% (layer 17%); bare rock 68% (layer 11%); cave pockets 30% (layer 17%) — camera [200.5,196,-263.5]
  - landmark at [-248,-120]: biome deep_cold_ocean (type deep_ocean); ground calcite (type gravel); neighbour step 4.1 (type 0.3); overhangs 28% (type 2%); canopy 5% (type 0%)
  - landmark at [-8,-376]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); height 18.1 (type 148.5); relief over the patch 187.0 (type 83.0); ground plants 1% (type 13%)
  - landmark at [-24,-376]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); relief over the patch 187.0 (type 83.0); height 29.3 (type 148.5); ground plants 3% (type 13%)
  - landmark at [-168,-168]: biome adventure:chalk_cliffs (type adventure:sky_highlands); canopy 14% (type 8%); open to the sky 86% (type 92%); relief over 16 blocks 49.0 (type 5.0)
  - landmark at [-40,-376]: biome adventure:chalk_cliffs (type adventure:sky_highlands); top calcite (type grass_block); relief over the patch 187.0 (type 83.0); height 53.7 (type 148.5); cave pockets 25% (type 3%)
- **sky**: no cells.
- **underground** (144 cells, 7 types, silhouette 0.268):
  - type 1: 22.2% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 133; cave pockets 1% (layer 17%) — camera [-35.5,101.88,-345.5]
  - type 2: 21.5% of cells; notableId coal_ore; biome adventure:sky_highlands; height 195; neighbour step 0.2 (layer 1.1); relief over 16 blocks 8.4 (layer 33.6); notable-block density (log) 1.0 (layer 0.7) — camera [-254.5,148.88,228.5]
  - type 3: 14.6% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 163; neighbour step 2.6 (layer 1.1); relief over 16 blocks 66.3 (layer 33.6); cave liquid 5% (layer 1%) — camera [-257.5,148.88,228.5]
  - type 4: 11.1% of cells; notableId coal_ore; biome adventure:sky_highlands; height 162; neighbour step 1.9 (layer 1.1) — camera [46.5,124.88,218.5]
  - type 5: 11.1% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 167; notable-block density (log) 1.2 (layer 0.7); cave air 6% (layer 2%); cave pockets 33% (layer 17%) — camera [-216.5,75.88,-142.5]
  - type 6: 10.4% of cells; notableId iron_ore; biome adventure:sky_highlands; height 157; layer present in 52% (layer 91%); relief over 16 blocks 17.7 (layer 33.6); notable-block density (log) 0.3 (layer 0.7); cave liquid 0% (layer 1%); cave pockets 3% (layer 17%)
  - type 7: 9.0% of cells; notableId blackstone; biome adventure:umbral_wastes; height 201; relief over 16 blocks 18.3 (layer 33.6); notable-block density (log) 0.3 (layer 0.7); cave air 5% (layer 2%); cave pockets 43% (layer 17%) — camera [205.5,167.88,-250.5]
  - landmark at [-184,-136]: notableId calcite (type coal_ore); cave air 19% (type 2%); cave pockets 86% (type 27%); notable-block density (log) 1.9 (type 1.4)
  - landmark at [72,-200]: notableId obsidian (type iron_ore); cave air 19% (type 0%); notable-block density (log) 1.8 (type 0.2); height 235.3 (type 155.6)
  - landmark at [216,-248]: notableId blackstone (type none); notable-block density (log) 3.1 (type 0.0); cave pockets 89% (type 21%); cave air 11% (type 2%)
  - landmark at [72,-232]: notableId blackstone (type coal_ore); biome adventure:umbral_wastes (type adventure:chalk_cliffs); layer present in 74% (type 100%); relief over 16 blocks 82.0 (type 49.0); notable-block density (log) 1.7 (type 1.4)
  - landmark at [232,-264]: notableId blackstone (type none); cave pockets 73% (type 21%); relief over 16 blocks 59.0 (type 10.0); neighbour step 2.0 (type 0.5)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:sky_highlands: expressed, 31936 columns; placement land 47.1%, water 0.7%, void 52.2%; snow 0%, canopy 2.5%, trees 11.3/ha; top grass_block 32.5%, short_grass 6.8%, calcite 3.8%
- adventure:chalk_cliffs: expressed, 23696 columns; placement land 27.6%, water 0.4%, void 72%; snow 0%, canopy 2.7%, trees 9.2/ha; top grass_block 17.3%, calcite 5.5%, short_grass 3.3%
- adventure:umbral_wastes: expressed, 10144 columns; placement land 49.2%, water 0.2%, void 50.5%; snow 0%, canopy 0%, trees 0/ha; top blackstone 35.5%, obsidian 4.4%, soul_fire 3.6%
- minecraft:deep_ocean: expressed, 6005 columns; placement land 16.8%, water 82.3%, void 0.8%, sky 0.1%; snow 0%, canopy 1.9%, trees 9.9/ha; top water 82.2%, grass_block 14.5%, calcite 1.1%
- minecraft:deep_cold_ocean: expressed, 1952 columns; placement land 34.6%, water 57.9%, void 7.5%; snow 0%, canopy 0.7%, trees 0/ha; top water 57.5%, grass_block 29.6%, calcite 2.2%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 6.8 | 10144 | expressed | - |
| O2 | minecraft:deep_ocean | minecraft:deep_ocean | 8.1 | 6005 | expressed | - |
| O3 | minecraft:deep_cold_ocean | minecraft:deep_cold_ocean | 2.4 | 1952 | expressed | - |
| O4 | paradise_lost:continental_plateau | adventure:sky_highlands | 20.7 | 31936 | expressed | - |
| O5 | paradise_lost:highlands_grand_glade | adventure:golden_skygrove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | minecraft:deep_dark | minecraft:deep_dark | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O7 | minecraft:dripstone_caves | minecraft:dripstone_caves | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O9 | paradise_lost:calcite_craglands | adventure:windswept_isles | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:chalk_cliffs | 9.0 | 23696 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 20.7%, adventure:chalk_cliffs 9.0%, minecraft:deep_ocean 8.1% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 8.7% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 53.0% (`surface.voidPercent`) | |
| M:canopy | 0.8% (`surface.canopyPercent`) | |
| M:relief | median 40.0 blocks a patch; height {"p5": 100.0, "p25": 141.0, "p50": 185.0, "p75": 196.0, "p95": 201.0, "max": 242.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 47.0, "underground": 46.1, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B3B6B", "fogColor": "#0E2A44", "ambientLight": 0.15}` |
| M:classes | {"void": 53.0, "roofed": 0.0, "stacked": 0.0, "overhang": 2.9, "cave": 7.7, "single": 36.4} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 80.9%
- S:b — 1.21.1 biomes expressed: 44.4%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (47.0% of columns)

## Shots

### the_abyssal_shrine-surface-t1

Picked for: surface type 1: 51.7% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 160
Census: sky 29.5%; void 4.3%; near: grass_block 43.2%, short_grass 8.1%; far: stone 8.6%, water 2.5%; biomes: adventure:sky_highlands 77.5%, adventure:umbral_wastes 14.3%, adventure:chalk_cliffs 5.4%
Image: `/tmp/claude-0/w10c1/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t1.png`

### the_abyssal_shrine-underground-t1

Picked for: underground type 1: 22.2% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 133; cave pockets 1% (layer 17%)
Census: sky 3.3%; void 12.9%; near: stone 54.4%, diorite 5.6%; mid: stone 10.4%, water 3.2%; far: stone 3%, water 2.6%; biomes: adventure:chalk_cliffs 70%, deep_cold_ocean 16.4%, adventure:umbral_wastes 13.6%
Image: `/tmp/claude-0/w10c1/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-underground-t1.png`

### the_abyssal_shrine-surface-t2

Picked for: surface type 2: 17.0% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 186; trees per cell 1.0 (layer 0.2); canopy 9% (layer 2%); ground plants 21% (layer 12%); open to the sky 91% (layer 98%)
Census: sky 23.7%; near: oak_leaves 26.9%, grass_block 25.7%; biomes: adventure:sky_highlands 99.2%
Image: `/tmp/claude-0/w10c1/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t2.png`

### the_abyssal_shrine-surface-t3

Picked for: surface type 3: 16.3% of cells; biome deep_ocean; ground gravel; top water; height 134; ground plants 1% (layer 12%); liquid cover 85% (layer 17%); cave pockets 4% (layer 17%)
Census: sky 0.1%; near: water 51.6%; mid: stone 30.8%, dirt 5.2%; biomes: deep_ocean 58.4%, adventure:sky_highlands 41.6%
Image: `/tmp/claude-0/w10c1/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t3.png`

### the_abyssal_shrine-surface-lm1

Picked for: surface landmark: biome deep_cold_ocean (type deep_ocean); ground calcite (type gravel); neighbour step 4.1 (type 0.3); overhangs 28% (type 2%); canopy 5% (type 0%)
Census: sky 21.7%; near: water 48.7%, calcite 10.9%; mid: water 1.3%, stone 1.2%; far: stone 6.7%, calcite 1.8%; biomes: deep_cold_ocean 63.7%, adventure:chalk_cliffs 18.5%, adventure:sky_highlands 17.1%
Image: `/tmp/claude-0/w10c1/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
