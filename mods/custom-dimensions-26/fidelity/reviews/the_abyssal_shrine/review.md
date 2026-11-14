# Review packet: the_abyssal_shrine

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: nullscape:shadowlands, minecraft:deep_ocean, minecraft:deep_cold_ocean, paradise_lost:continental_plateau, paradise_lost:highlands_grand_glade, minecraft:deep_dark, minecraft:dripstone_caves, paradise_lost:highlands_thicket, paradise_lost:calcite_craglands

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: minecraft:deep_cold_ocean, minecraft:deep_ocean, adventure:sky_highlands, adventure:chalk_cliffs, adventure:umbral_wastes

Terrain: `{"base": "basin_islands", "setting": "extreme", "layout": {"type": "ring", "centre": [0, 0], "radius": 410, "falloff": 120, "strength": 0.9, "warp": {"blocks": 72, "wavelength": 512}}, "params": {"hollowDepth": 40, "ringLakes": 0, "gaps": 0, "islandShare": 0.02, "hollowApron": 40, "hollowJitter": 40, "coverage": 0.55}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_abyssal_shrine/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_abyssal_shrine/layers`

## Summary

- Top blocks seen from the sky: grass_block 24.7%, water 16.3%, blackstone 13.9%, short_grass 5.4%.
- Surface biomes: adventure:sky_highlands 21.1%, adventure:umbral_wastes 19%, adventure:chalk_cliffs 12.3%, deep_cold_ocean 9.2%, deep_ocean 8.8%.
- Ground blocks: grass_block 32.3%, blackstone 13.9%, gravel 12.6%, soul_soil 2.3%.
- Liquid cover 16.5% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.1%; bare rock 14.2%; no ground (void) 29.6%.
- Trees: 4.2 per ha (oak_log 4.2/ha, mean spacing 18.1 blocks); ground plants grass 5.5%, other 1.3%, flower 1.1%.
- Surface height p5 95, median 154, p95 227, max 256; median relief per patch 34 blocks; slopes under 15° 46.8%, over 45° 28%, cliffs 0.7%; above the clouds 16.7%, below sea level 0%.
- Underground: 6.8% open, 0% liquid; cave biomes adventure:sky_highlands 29.4%, adventure:umbral_wastes 28.1%, adventure:chalk_cliffs 18.8%, deep_cold_ocean 12.7%.
- Notable clusters, fewest first: sandstone 3 clusters, spacing n/a; lava 4 clusters, spacing 15.6; clay 12 clusters, spacing 26; diamond_ore 24 clusters, spacing 10.4; lapis_ore 25 clusters, spacing 3.8; quartz_block 39 clusters, spacing 16.6.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 810 columns: height within 2 blocks 98%, biome match 100%.
- Landforms over the border (noise render): plains 16.1%, hills 10.9%, mountains 3%, plateaus 4.5%, basins 3.7%, islands 0%, water 21.8%, void 40%.
- Placeness: biome regions 5.7/km² (6 major, largest 20.8%), landform regions 10.5/km² (largest 38.7%), dominant place 38.7% of the border.

## Layers

- Columns by class: void 29.6%, roofed 0%, stacked 0.5%, overhang 4.1%, cave 11.3%, single 54.5%.
- Columns with each layer: sky 0.5%, surface 70.4%, underground 69.9%, roof 0%.
- Overhangs in 4.2% of columns, stacked layers in 0.5%, cave pockets in 12.3%.

## Places

- **surface** (206 cells, 3 types, silhouette 0.51):
  - type 1: 49.5% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 156; ground plants 19% (layer 11%); liquid cover 1% (layer 23%); bare rock 1% (layer 20%) — camera [170.5,142,376.5]
  - type 2: 27.7% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 160; liquid cover 0% (layer 23%); bare rock 71% (layer 20%) — camera [136.5,160,-263.5]
  - type 3: 22.8% of cells; biome deep_ocean; ground gravel; top water; height 120; ground plants 0% (layer 11%); liquid cover 98% (layer 23%); bare rock 1% (layer 20%) — camera [8.5,150,56.5]
  - landmark at [-88,-424]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); relief over the patch 117.0 (type 45.0); neighbour step 2.1 (type 0.7); relief over 16 blocks 41.0 (type 13.0)
  - landmark at [440,-56]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); notable-block density (log) 1.7 (type 0.2); neighbour step 1.6 (type 0.7); overhangs 17% (type 0%)
  - landmark at [-88,-408]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); relief over the patch 117.0 (type 45.0); relief over 16 blocks 45.0 (type 13.0); neighbour step 2.0 (type 0.7)
  - landmark at [-120,-424]: biome adventure:chalk_cliffs (type adventure:sky_highlands); ground calcite (type grass_block); top calcite (type grass_block); layer present in 53% (type 100%); relief over the patch 117.0 (type 45.0); ground plants 0% (type 18%)
  - landmark at [-168,-344]: biome adventure:chalk_cliffs (type adventure:sky_highlands); notable-block density (log) 3.4 (type 0.2); canopy 11% (type 0%); trees per cell 1.0 (type 0.0)
- **sky** (2 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:sky_highlands; ground grass_block; height 180 — camera [24.5,193,136.5]
- **underground** (205 cells, 2 types, silhouette 0.361):
  - type 1: 76.6% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 142 — camera [318.5,136.88,106.5]
  - type 2: 23.4% of cells; notableId blackstone; biome adventure:umbral_wastes; height 170; neighbour step 0.9 (layer 0.6); cave air 13% (layer 4%); cave pockets 55% (layer 17%) — camera [218.5,149.88,-391.5]
  - landmark at [-104,-408]: notableId calcite (type blackstone); biome adventure:chalk_cliffs (type adventure:umbral_wastes); notable-block density (log) 1.8 (type 0.0); neighbour step 2.0 (type 0.7); height 122.2 (type 199.2)
  - landmark at [-120,-408]: notableId calcite (type blackstone); biome adventure:chalk_cliffs (type adventure:umbral_wastes); cave air 54% (type 6%); notable-block density (log) 2.0 (type 0.0); cave pockets 85% (type 45%)
  - landmark at [-104,-424]: notableId calcite (type blackstone); biome adventure:chalk_cliffs (type adventure:umbral_wastes); height 104.4 (type 199.2); notable-block density (log) 1.5 (type 0.0); relief over 16 blocks 43.0 (type 15.0)
  - landmark at [-88,-440]: notableId copper_ore (type coal_ore); biome adventure:chalk_cliffs (type none); height 62.6 (type 144.7); neighbour step 1.4 (type 0.1); relief over 16 blocks 36.0 (type 14.0)
  - landmark at [-120,-424]: notableId copper_ore (type blackstone); biome adventure:chalk_cliffs (type adventure:umbral_wastes); layer present in 46% (type 100%); height 120.3 (type 199.2); relief over 16 blocks 35.0 (type 15.0)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:sky_highlands: expressed, 29978 columns; placement land 51%, water 0.9%, void 47.2%, sky 0.8%; snow 0%, canopy 3.4%, trees 13.7/ha; top grass_block 35.7%, short_grass 9.3%, oak_leaves 1.7%
- adventure:umbral_wastes: expressed, 17639 columns; placement land 79.5%, void 20.5%; snow 0%, canopy 0%, trees 0/ha; top blackstone 57.7%, obsidian 8.2%, soul_fire 5.4%
- adventure:chalk_cliffs: expressed, 13072 columns; placement land 69.6%, void 30.4%; snow 0%, canopy 3%, trees 9.9/ha; top grass_block 49.1%, short_grass 9.2%, calcite 8.1%
- minecraft:deep_cold_ocean: expressed, 6784 columns; placement land 13.6%, water 86.1%, void 0.4%; snow 0%, canopy 2.5%, trees 10.9/ha; top water 84.9%, grass_block 10%, blackstone 1.1%
- minecraft:deep_ocean: expressed, 6593 columns; placement land 6.6%, water 91.3%, void 0.8%, sky 1.2%; snow 0%, canopy 0%, trees 0/ha; top water 90.9%, grass_block 5.4%, sand 0.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 19.0 | 17639 | expressed | - |
| O2 | minecraft:deep_ocean | minecraft:deep_ocean | 8.8 | 6593 | expressed | - |
| O3 | minecraft:deep_cold_ocean | minecraft:deep_cold_ocean | 9.2 | 6784 | expressed | - |
| O4 | paradise_lost:continental_plateau | adventure:sky_highlands | 21.1 | 29978 | expressed | - |
| O5 | paradise_lost:highlands_grand_glade | adventure:golden_skygrove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | minecraft:deep_dark | minecraft:deep_dark | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O7 | minecraft:dripstone_caves | minecraft:dripstone_caves | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O9 | paradise_lost:calcite_craglands | adventure:windswept_isles | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:chalk_cliffs | 12.3 | 13072 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 21.1%, adventure:umbral_wastes 19.0%, adventure:chalk_cliffs 12.3% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 16.5% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 29.6% (`surface.voidPercent`) | |
| M:canopy | 1.1% (`surface.canopyPercent`) | |
| M:relief | median 34.0 blocks a patch; height {"p5": 95.0, "p25": 112.0, "p50": 154.0, "p75": 159.0, "p95": 227.0, "max": 256.0} (`terrain`) | |
| M:layers | {"sky": 0.5, "surface": 70.4, "underground": 69.9, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B3B6B", "fogColor": "#0E2A44", "ambientLight": 0.15}` |
| M:classes | {"void": 29.6, "roofed": 0.0, "stacked": 0.5, "overhang": 4.1, "cave": 11.3, "single": 54.5} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 82.5%
- S:b — 1.21.1 biomes expressed: 44.4%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (29.6% of columns)

## Shots

### the_abyssal_shrine-far

Picked for: far: the border ring at bearing 96, facing the centre; 0.0% of rays could reach the border
Census: sky 48.5%; void 4.9%; far: grass_block 25.4%, short_grass 7.7%; biomes: adventure:sky_highlands 89.9%, deep_ocean 8.9%
Image: `/tmp/claude-0/w10r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-far.png`

### the_abyssal_shrine-sky-t1

Picked for: sky type 1: 100.0% of cells; biome adventure:sky_highlands; ground grass_block; height 180
Census: sky 40.4%; near: grass_block 9.3%, calcite 3.9%; mid: water 13.4%; far: water 19.5%, grass_block 3.9%; biomes: deep_cold_ocean 37.9%, deep_ocean 22.8%, adventure:sky_highlands 21.4%
Image: `/tmp/claude-0/w10r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-sky-t1.png`

### the_abyssal_shrine-underground-t1

Picked for: underground type 1: 76.6% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 142
Census: sky 0%; near: stone 96.7%, coal_ore 3.1%; biomes: deep_ocean 95.4%
Image: `/tmp/claude-0/w10r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-underground-t1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
