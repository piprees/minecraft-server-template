# Review packet: the_abyssal_shrine

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: nullscape:shadowlands, minecraft:deep_ocean, minecraft:deep_cold_ocean, paradise_lost:continental_plateau, paradise_lost:highlands_grand_glade, minecraft:deep_dark, minecraft:dripstone_caves, paradise_lost:highlands_thicket, paradise_lost:calcite_craglands

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: minecraft:deep_cold_ocean, minecraft:deep_ocean, adventure:sky_highlands, adventure:chalk_cliffs, adventure:umbral_wastes

Terrain: `{"base": "basin_islands", "setting": "extreme", "layout": {"type": "ring", "centre": [0, 0], "radius": 300, "falloff": 160, "strength": 0.9}, "params": {"hollowDepth": 40, "ringLakes": 0, "gaps": 0.15, "islandShare": 0.02}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `runs/fin/full/the_abyssal_shrine/dossier.json`; layer maps in `runs/fin/full/the_abyssal_shrine/layers`

## Summary

- Top blocks seen from the sky: grass_block 21.7%, blackstone 8.3%, water 6.9%, short_grass 3.3%.
- Surface biomes: adventure:sky_highlands 18.3%, adventure:umbral_wastes 11.1%, adventure:chalk_cliffs 9.4%, deep_cold_ocean 4.5%, deep_ocean 3.2%.
- Ground blocks: grass_block 27%, blackstone 8.3%, gravel 6.1%, soul_soil 1.3%.
- Liquid cover 7% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.1%; bare rock 8.4%; no ground (void) 53.5%.
- Trees: 5.1 per ha (oak_log 5.1/ha, mean spacing 17.2 blocks); ground plants grass 3.4%, flower 0.9%, other 0.8%, bush 0%.
- Surface height p5 22, median 139, p95 252, max 264; median relief per patch 0 blocks; slopes under 15° 22.5%, over 45° 39.1%, cliffs 1.5%; above the clouds 24.9%, below sea level 0%.
- Underground: 8% open, 0% liquid; cave biomes adventure:sky_highlands 34%, adventure:umbral_wastes 33.1%, adventure:chalk_cliffs 14.9%, deep_cold_ocean 10.3%.
- Notable clusters, fewest first: deepslate_lapis_ore 2 clusters, spacing n/a; deepslate_copper_ore 2 clusters, spacing n/a; deepslate_coal_ore 2 clusters, spacing n/a; clay 3 clusters, spacing 15.6; deepslate_diamond_ore 3 clusters, spacing n/a; gold_ore 8 clusters, spacing 13.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 502 columns: height within 2 blocks 97.4%, biome match 100%.
- Landforms over the border (noise render): plains 10.2%, hills 7.5%, mountains 1.9%, plateaus 2.9%, basins 2.5%, islands 0%, water 11.9%, void 63.1%.
- Placeness: biome regions 5.7/km² (6 major, largest 12.4%), landform regions 8.6/km² (largest 61.3%), dominant place 61.3% of the border.

## Layers

- Columns by class: void 53.5%, roofed 0%, stacked 0.5%, overhang 2.4%, cave 11.5%, single 32%.
- Columns with each layer: sky 0.5%, surface 46.5%, underground 44.9%, roof 0%.
- Overhangs in 2.4% of columns, stacked layers in 0.5%, cave pockets in 12.2%.

## Places

- **surface** (129 cells, 3 types, silhouette 0.513):
  - type 1: 58.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 124; ground plants 16% (layer 11%); bare rock 2% (layer 19%) — camera [-24.5,105,280.5]
  - type 2: 24.8% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 196; layer present in 90% (layer 97%); trees per cell 0.0 (layer 0.3); notable-block density (log) 0.9 (layer 0.5); canopy 0% (layer 2%); bare rock 70% (layer 19%) — camera [19.5,178,-248.5]
  - type 3: 17.1% of cells; biome deep_cold_ocean; ground gravel; top water; height 111; trees per cell 0.0 (layer 0.3); canopy 0% (layer 2%); ground plants 1% (layer 11%); liquid cover 85% (layer 15%); bare rock 3% (layer 19%); stacked layers 5% (layer 1%); cave pockets 9% (layer 26%) — camera [72.5,150,-71.5]
  - landmark at [24,-200]: ground oak_planks (type gravel); top oak_planks (type water); neighbour step 13.0 (type 0.0); stacked layers 36% (type 0%); open to the sky 64% (type 100%)
  - landmark at [8,-200]: ground sand (type gravel); neighbour step 17.9 (type 0.0); relief over 16 blocks 156.0 (type 0.0); relief over the patch 209.0 (type 1.0)
  - landmark at [8,-216]: stacked layers 33% (type 0%); open to the sky 67% (type 100%); layer present in 63% (type 100%)
  - landmark at [-8,-200]: ground grass_block (type gravel); relief over 16 blocks 160.0 (type 0.0); relief over the patch 209.0 (type 1.0); liquid cover 31% (type 100%)
  - landmark at [-312,-136]: canopy 13% (type 0%); cave pockets 90% (type 16%); overhangs 23% (type 0%)
- **sky** (2 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome deep_cold_ocean; ground grass_block; height 165 — camera [24.5,192,152.5]
- **underground** (127 cells, 8 types, silhouette 0.255):
  - type 1: 26.8% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 142; cave air 0% (layer 8%); cave pockets 1% (layer 26%) — camera [-178.5,101.88,197.5]
  - type 2: 16.5% of cells; notableId iron_ore; biome adventure:sky_highlands; height 117; notable-block density (log) 0.1 (layer 0.5) — camera [-34.5,82.88,301.5]
  - type 3: 15.0% of cells; notableId coal_ore; biome adventure:sky_highlands; height 190; notable-block density (log) 1.0 (layer 0.5); cave pockets 53% (layer 26%) — camera [-169.5,103.88,193.5]
  - type 4: 11.8% of cells; notableId calcite; biome adventure:chalk_cliffs; height 89; relief over 16 blocks 27.9 (layer 18.5); notable-block density (log) 0.8 (layer 0.5); cave air 16% (layer 8%); cave liquid 0% (layer 0%) — camera [258.5,13.88,62.5]
  - type 5: 9.4% of cells; biome adventure:umbral_wastes; notableId blackstone; height 196; relief over 16 blocks 25.9 (layer 18.5); notable-block density (log) 0.1 (layer 0.5) — camera [45.5,134.88,-245.5]
  - type 6: 8.7% of cells; notableId redstone_ore; biome adventure:sky_highlands; height 90; layer present in 87% (layer 95%); notable-block density (log) 0.0 (layer 0.5); cave air 0% (layer 8%); cave pockets 1% (layer 26%) — camera [243.5,5.88,115.5]
  - type 7: 6.3% of cells; notableId blackstone; biome adventure:umbral_wastes; height 223; notable-block density (log) 1.5 (layer 0.5); cave pockets 59% (layer 26%) — camera [226.5,209.88,-180.5]
  - type 8: 5.5% of cells; notableId coal_ore; biome deep_cold_ocean; height 121; relief over 16 blocks 7.0 (layer 18.5); cave air 31% (layer 8%) — camera [68.5,10.88,-59.5]
  - landmark at [24,-200]: notableId oak_planks (type coal_ore); neighbour step 11.5 (type 0.0); layer present in 64% (type 100%); cave air 2% (type 30%)
  - landmark at [-232,120]: biome adventure:sky_highlands (type adventure:chalk_cliffs); cave air 60% (type 6%); cave pockets 86% (type 18%); height 152.1 (type 35.7)
  - landmark at [-216,120]: biome adventure:sky_highlands (type adventure:chalk_cliffs); cave air 35% (type 6%); height 154.1 (type 35.7); relief over 16 blocks 44.0 (type 23.0)
  - landmark at [8,-216]: notableId oak_planks (type coal_ore); neighbour step 11.4 (type 0.7); layer present in 42% (type 100%); relief over 16 blocks 1.0 (type 18.0)
  - landmark at [184,-152]: notableId iron_ore (type coal_ore); layer present in 36% (type 100%); height 241.6 (type 153.9); neighbour step 2.1 (type 0.7)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:sky_highlands: expressed, 31172 columns; placement land 40.2%, water 0.3%, void 59.3%, sky 0.3%; snow 0%, canopy 4.9%, trees 22.4/ha; top grass_block 29.7%, short_grass 5.5%, oak_leaves 2%
- adventure:umbral_wastes: expressed, 21358 columns; placement land 36%, void 63.4%, sky 0.6%; snow 0%, canopy 0%, trees 0/ha; top blackstone 26.4%, obsidian 3.5%, soul_fire 2.4%
- adventure:chalk_cliffs: expressed, 11416 columns; placement land 56.7%, void 43.2%, sky 0.1%; snow 0%, canopy 2.3%, trees 10.8/ha; top grass_block 46.5%, short_grass 5%, calcite 1.4%
- minecraft:deep_cold_ocean: expressed, 3271 columns; placement land 17.1%, water 77.6%, void 1.3%, sky 4.1%; snow 0%, canopy 0%, trees 0/ha; top water 76.9%, grass_block 9.3%, oak_planks 3.8%
- minecraft:deep_ocean: expressed, 2276 columns; placement land 1.7%, water 97%, void 0.4%, sky 0.9%; snow 0%, canopy 0%, trees 0/ha; top water 96.2%, grass_block 1.5%, tall_seagrass 0.8%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 11.1 | 21358 | expressed | - |
| O2 | minecraft:deep_ocean | minecraft:deep_ocean | 3.2 | 2276 | expressed | - |
| O3 | minecraft:deep_cold_ocean | minecraft:deep_cold_ocean | 4.5 | 3271 | expressed | - |
| O4 | paradise_lost:continental_plateau | adventure:sky_highlands | 18.3 | 31172 | expressed | - |
| O5 | paradise_lost:highlands_grand_glade | adventure:golden_skygrove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | minecraft:deep_dark | minecraft:deep_dark | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O7 | minecraft:dripstone_caves | minecraft:dripstone_caves | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O9 | paradise_lost:calcite_craglands | adventure:windswept_isles | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:chalk_cliffs | 9.4 | 11416 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 18.3%, adventure:umbral_wastes 11.1%, adventure:chalk_cliffs 9.4% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 7.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 53.5% (`surface.voidPercent`) | |
| M:canopy | 1.1% (`surface.canopyPercent`) | |
| M:relief | median 0.0 blocks a patch; height {"p5": 22.0, "p25": 108.0, "p50": 139.0, "p75": 191.0, "p95": 252.0, "max": 264.0} (`terrain`) | |
| M:layers | {"sky": 0.5, "surface": 46.5, "underground": 44.9, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B3B6B", "fogColor": "#0E2A44", "ambientLight": 0.15}` |
| M:classes | {"void": 53.5, "roofed": 0.0, "stacked": 0.5, "overhang": 2.4, "cave": 11.5, "single": 32.0} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 79.8%
- S:b — 1.21.1 biomes expressed: 44.4%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (46.5% of columns)

## Shots

### the_abyssal_shrine-far

Picked for: far: the border ring at bearing 180, facing the centre; 0.0% of rays could reach the border
Census: sky 36.4%; void 29.6%; far: grass_block 18.3%, stone 5.5%; biomes: adventure:sky_highlands 73.1%, adventure:chalk_cliffs 14.9%, deep_cold_ocean 6%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-far.png`

### the_abyssal_shrine-surface-t1

Picked for: surface type 1: 58.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 124; ground plants 16% (layer 11%); bare rock 2% (layer 19%)
Census: sky 0%; near: grass_block 51.8%, short_grass 22.9%; mid: grass_block 5%, stone 1.5%; biomes: adventure:sky_highlands 100%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t1.png`

### the_abyssal_shrine-underground-t1

Picked for: underground type 1: 26.8% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 142; cave air 0% (layer 8%); cave pockets 1% (layer 26%)
Census: sky 0.1%; void 14.1%; near: stone 49%, grass_block 1.6%; mid: stone 29.3%, grass_block 3%; biomes: adventure:sky_highlands 100%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-underground-t1.png`

### the_abyssal_shrine-surface-t3

Picked for: surface type 3: 17.1% of cells; biome deep_cold_ocean; ground gravel; top water; height 111; trees per cell 0.0 (layer 0.3); canopy 0% (layer 2%); ground plants 1% (layer 11%); liquid cover 85% (layer 15%); bare rock 3% (layer 19%); stacked layers 5% (layer 1%); cave pockets 9% (layer 26%)
Census: sky 35.2%; near: water 51.6%; mid: water 3.9%; far: grass_block 3.4%, stone 3%; biomes: deep_cold_ocean 85.7%, adventure:sky_highlands 8.5%, deep_ocean 5.6%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t3.png`

### the_abyssal_shrine-underground-t4

Picked for: underground type 4: 11.8% of cells; notableId calcite; biome adventure:chalk_cliffs; height 89; relief over 16 blocks 27.9 (layer 18.5); notable-block density (log) 0.8 (layer 0.5); cave air 16% (layer 8%); cave liquid 0% (layer 0%)
Census: sky 0%; void 0.4%; near: stone 44.3%, grass_block 24.3%; mid: stone 5.4%, grass_block 1.6%; biomes: adventure:chalk_cliffs 99.1%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-underground-t4.png`

### the_abyssal_shrine-surface-t2-r

Picked for: surface type 2: 24.8% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 196; layer present in 90% (layer 97%); trees per cell 0.0 (layer 0.3); notable-block density (log) 0.9 (layer 0.5); canopy 0% (layer 2%); bare rock 70% (layer 19%)
Census: sky 17.8%; void 0.9%; near: blackstone 62.3%, soul_soil 1.7%; mid: water 2.3%, blackstone 1.8%; far: water 4.6%, grass_block 1%; biomes: adventure:umbral_wastes 83.4%, deep_cold_ocean 9.9%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t2-r.png`

### the_abyssal_shrine-surface-t2-r-nv

Picked for: surface type 2: 24.8% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 196; layer present in 90% (layer 97%); trees per cell 0.0 (layer 0.3); notable-block density (log) 0.9 (layer 0.5); canopy 0% (layer 2%); bare rock 70% (layer 19%)
Census: sky 17.8%; void 0.9%; near: blackstone 62.3%, soul_soil 1.7%; mid: water 2.3%, blackstone 1.8%; far: water 4.6%, grass_block 1%; biomes: adventure:umbral_wastes 83.4%, deep_cold_ocean 9.9%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t2-r-nv.png`

### the_abyssal_shrine-surface-lm1-r

Picked for: surface landmark: ground oak_planks (type gravel); top oak_planks (type water); neighbour step 13.0 (type 0.0); stacked layers 36% (type 0%); open to the sky 64% (type 100%)
Census: sky 7.6%; void 30.4%; near: oak_planks 21.4%; mid: oak_planks 3.4%, stone 3.2%; far: stone 28.2%; biomes: deep_cold_ocean 69%, adventure:chalk_cliffs 14.5%, adventure:sky_highlands 13.2%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-lm1-r.png`

### the_abyssal_shrine-surface-lm1-r-nv

Picked for: surface landmark: ground oak_planks (type gravel); top oak_planks (type water); neighbour step 13.0 (type 0.0); stacked layers 36% (type 0%); open to the sky 64% (type 100%)
Census: sky 7.6%; void 30.4%; near: oak_planks 21.4%; mid: oak_planks 3.4%, stone 3.2%; far: stone 28.2%; biomes: deep_cold_ocean 69%, adventure:chalk_cliffs 14.5%, adventure:sky_highlands 13.2%
Image: `/tmp/claude-0/w10c1r/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-lm1-r-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
