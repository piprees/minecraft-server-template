# Review packet: the_abyssal_shrine

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: nullscape:shadowlands, minecraft:deep_ocean, minecraft:deep_cold_ocean, paradise_lost:continental_plateau, paradise_lost:highlands_grand_glade, minecraft:deep_dark, minecraft:dripstone_caves, paradise_lost:highlands_thicket, paradise_lost:calcite_craglands

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_abyssal_shrine.json`

> A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent.

Biomes: minecraft:deep_cold_ocean, minecraft:deep_ocean, adventure:sky_highlands, adventure:chalk_cliffs, adventure:umbral_wastes

Terrain: `{"base": "basin_islands", "setting": "extreme", "layout": {"type": "ring", "centre": [0, 0], "radius": 360, "falloff": 140, "strength": 0.9}, "params": {"hollowDepth": 40, "ringLakes": 0, "gaps": 0.15, "islandShare": 0.02}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1q/final/the_abyssal_shrine/dossier.json`; layer maps in `/tmp/claude-0/w10c1q/final/the_abyssal_shrine/layers`

## Summary

- Top blocks seen from the sky: water 22%, grass_block 21.4%, blackstone 5%, short_grass 3.9%.
- Surface biomes: adventure:sky_highlands 22.6%, deep_cold_ocean 14%, deep_ocean 9.6%, adventure:umbral_wastes 7%, adventure:chalk_cliffs 6.1%.
- Ground blocks: grass_block 27.2%, gravel 18.4%, blackstone 5%, dirt 1.6%.
- Liquid cover 22.1% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1%; bare rock 5.3%; no ground (void) 40.7%.
- Trees: 5.7 per ha (oak_log 5.7/ha, mean spacing 12.7 blocks); ground plants grass 4%, flower 1%, other 0.6%.
- Surface height p5 27, median 109, p95 252, max 298; median relief per patch 29 blocks; slopes under 15° 35.9%, over 45° 25.8%, cliffs 2.6%; above the clouds 6.2%, below sea level 0%.
- Underground: 5.6% open, 0% liquid; cave biomes deep_cold_ocean 27.8%, adventure:sky_highlands 24.4%, adventure:umbral_wastes 19.1%, deep_ocean 17.4%.
- Notable clusters, fewest first: tuff_bricks 2 clusters, spacing n/a; gold_ore 3 clusters, spacing 26; spawner 3 clusters, spacing 18; chiseled_tuff_bricks 4 clusters, spacing 1.3; deepslate_copper_ore 4 clusters, spacing 16.6; deepslate_iron_ore 5 clusters, spacing 7.9.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 686 columns: height within 2 blocks 97.5%, biome match 100%.
- Landforms over the border (noise render): plains 10.2%, hills 7.3%, mountains 2.3%, plateaus 3.6%, basins 2.8%, islands 0%, water 21.8%, void 52%.
- Placeness: biome regions 5.7/km² (6 major, largest 13.7%), landform regions 10.5/km² (largest 48.2%), dominant place 48.2% of the border.

## Layers

- Columns by class: void 40.7%, roofed 0%, stacked 1.8%, overhang 3.3%, cave 8.5%, single 45.8%.
- Columns with each layer: sky 1.8%, surface 59.3%, underground 57.5%, roof 0%.
- Overhangs in 3.3% of columns, stacked layers in 1.8%, cave pockets in 9.2%.

## Places

- **surface** (175 cells, 3 types, silhouette 0.564):
  - type 1: 49.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 93; ground plants 17% (layer 9%); liquid cover 1% (layer 36%) — camera [-343.5,99,73.5]
  - type 2: 37.7% of cells; biome deep_cold_ocean; ground gravel; top water; height 115; ground plants 0% (layer 9%); liquid cover 96% (layer 36%) — camera [-39.5,150,-215.5]
  - type 3: 13.1% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 209; layer present in 89% (layer 97%); notable-block density (log) 0.9 (layer 0.4); liquid cover 0% (layer 36%); bare rock 72% (layer 10%) — camera [280.5,253,-231.5]
  - landmark at [168,200]: biome deep_ocean (type adventure:sky_highlands); ground oak_planks (type grass_block); top oak_planks (type grass_block); stacked layers 77% (type 0%); open to the sky 23% (type 100%); trees per cell 2.0 (type 0.0)
  - landmark at [152,200]: biome deep_ocean (type deep_cold_ocean); ground oak_planks (type gravel); top oak_planks (type water); stacked layers 63% (type 0%); open to the sky 37% (type 100%); trees per cell 2.0 (type 0.0)
  - landmark at [184,216]: top oak_planks (type grass_block); neighbour step 19.0 (type 0.4); relief over 16 blocks 153.0 (type 12.0); open to the sky 52% (type 100%)
  - landmark at [152,216]: biome deep_ocean (type deep_cold_ocean); ground dirt (type gravel); relief over 16 blocks 146.0 (type 1.0); stacked layers 40% (type 0%); open to the sky 60% (type 100%)
  - landmark at [168,216]: biome deep_ocean (type adventure:sky_highlands); neighbour step 25.8 (type 0.4); relief over 16 blocks 153.0 (type 12.0); open to the sky 55% (type 100%)
- **sky** (9 cells, 2 types, silhouette 0.485):
  - type 1: 66.7% of cells; biome deep_ocean; ground grass_block; height 149; neighbour step 0.5 (layer 2.3); relief over 16 blocks 13.7 (layer 23.4); notable-block density (log) 0.7 (layer 0.8) — camera [184.5,150,196.5]
  - type 2: 33.3% of cells; biome adventure:sky_highlands; ground grass_block; height 177; neighbour step 5.9 (layer 2.3); relief over 16 blocks 43.0 (layer 23.4); notable-block density (log) 1.2 (layer 0.8) — camera [24.5,193,136.5]
- **underground** (173 cells, 8 types, silhouette 0.348):
  - type 1: 24.9% of cells; notableId coal_ore; height 130; cave pockets 1% (layer 16%) — camera [-3.5,9.88,-233.5]
  - type 2: 17.3% of cells; biome adventure:sky_highlands; notableId calcite; height 67; notable-block density (log) 0.0 (layer 0.4) — camera [-395.5,31.88,12.5]
  - type 3: 15.6% of cells; notableId copper_ore; biome adventure:umbral_wastes; height 124; notable-block density (log) 0.0 (layer 0.4); cave pockets 1% (layer 16%)
  - type 4: 10.4% of cells; notableId coal_ore; biome deep_cold_ocean; height 119; relief over 16 blocks 4.4 (layer 14.6); trees per cell 0.3 (layer 0.0); notable-block density (log) 0.6 (layer 0.4); cave air 17% (layer 5%); cave liquid 0% (layer 0%) — camera [19.5,14.88,-59.5]
  - type 5: 10.4% of cells; notableId iron_ore; biome adventure:sky_highlands; height 117; notable-block density (log) 0.1 (layer 0.4) — camera [75.5,62.88,349.5]
  - type 6: 9.2% of cells; notableId coal_ore; biome deep_ocean; height 114 — camera [27.5,86.88,221.5]
  - type 7: 6.9% of cells; notableId coal_ore; biome adventure:chalk_cliffs; height 164; relief over 16 blocks 34.3 (layer 14.6); notable-block density (log) 1.3 (layer 0.4); cave air 12% (layer 5%); cave pockets 52% (layer 16%) — camera [-141.5,161.88,-321.5]
  - type 8: 5.2% of cells; notableId blackstone; biome adventure:umbral_wastes; height 168; layer present in 67% (layer 95%); relief over 16 blocks 24.4 (layer 14.6) — camera [185.5,150.88,-281.5]
  - landmark at [-120,-344]: notableId calcite (type coal_ore); relief over 16 blocks 95.0 (type 19.0); cave air 40% (type 5%); notable-block density (log) 2.2 (type 0.9)
  - landmark at [8,-296]: notableId calcite (type coal_ore); relief over 16 blocks 65.0 (type 19.0); cave air 18% (type 5%); layer present in 82% (type 100%)
  - landmark at [24,136]: biome adventure:sky_highlands (type deep_ocean); cave liquid 1% (type 0%); relief over 16 blocks 84.0 (type 1.0); neighbour step 2.8 (type 0.1)
  - landmark at [56,-40]: notableId tuff_bricks (type coal_ore); cave air 56% (type 3%); notable-block density (log) 2.0 (type 0.3); cave pockets 75% (type 11%)
  - landmark at [296,-200]: notableId obsidian (type coal_ore); height 256.0 (type 109.0); relief over 16 blocks 45.0 (type 5.0); neighbour step 2.2 (type 0.2)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:sky_highlands: expressed, 30615 columns; placement land 53.4%, water 0.9%, void 44.5%, sky 1.2%; snow 0%, canopy 3.9%, trees 18.4/ha; top grass_block 38.9%, short_grass 7.4%, oak_leaves 2.1%
- adventure:chalk_cliffs: expressed, 14089 columns; placement land 31.4%, water 0.7%, void 67.2%, sky 0.7%; snow 0%, canopy 1.6%, trees 4.5/ha; top grass_block 22.9%, short_grass 4.1%, calcite 3.4%
- adventure:umbral_wastes: expressed, 12003 columns; placement land 43.2%, void 56.7%; snow 0%, canopy 0%, trees 0/ha; top blackstone 30.8%, obsidian 5.1%, soul_fire 3%
- minecraft:deep_cold_ocean: expressed, 10405 columns; placement land 3.7%, water 95.4%, void 0.1%, sky 0.8%; snow 0%, canopy 0%, trees 0/ha; top water 94.6%, grass_block 2.8%, tall_seagrass 0.6%
- minecraft:deep_ocean: expressed, 7942 columns; placement land 13.4%, water 75.8%, void 1%, sky 9.7%; snow 0%, canopy 0%, trees 37.5/ha; top water 75.5%, oak_planks 7.4%, grass_block 4%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 7.0 | 12003 | expressed | - |
| O2 | minecraft:deep_ocean | minecraft:deep_ocean | 9.6 | 7942 | expressed | - |
| O3 | minecraft:deep_cold_ocean | minecraft:deep_cold_ocean | 14.0 | 10405 | expressed | - |
| O4 | paradise_lost:continental_plateau | adventure:sky_highlands | 22.6 | 30615 | expressed | - |
| O5 | paradise_lost:highlands_grand_glade | adventure:golden_skygrove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | minecraft:deep_dark | minecraft:deep_dark | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O7 | minecraft:dripstone_caves | minecraft:dripstone_caves | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O9 | paradise_lost:calcite_craglands | adventure:windswept_isles | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:chalk_cliffs | 6.1 | 14089 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 22.6%, minecraft:deep_cold_ocean 14.0%, minecraft:deep_ocean 9.6% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 22.1% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 40.7% (`surface.voidPercent`) | |
| M:canopy | 1.0% (`surface.canopyPercent`) | |
| M:relief | median 29.0 blocks a patch; height {"p5": 27.0, "p25": 87.0, "p50": 109.0, "p75": 145.0, "p95": 252.0, "max": 298.0} (`terrain`) | |
| M:layers | {"sky": 1.8, "surface": 59.3, "underground": 57.5, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B3B6B", "fogColor": "#0E2A44", "ambientLight": 0.15}` |
| M:classes | {"void": 40.7, "roofed": 0.0, "stacked": 1.8, "overhang": 3.3, "cave": 8.5, "single": 45.8} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 89.7%
- S:b — 1.21.1 biomes expressed: 44.4%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (40.7% of columns)

## Shots

### the_abyssal_shrine-far

Picked for: far: the border ring at bearing 184, facing the centre; 0.0% of rays could reach the border
Census: sky 42.2%; void 11.5%; far: grass_block 22.7%, water 7.2%; biomes: adventure:sky_highlands 73.9%, deep_cold_ocean 12.3%, deep_ocean 7.2%
Image: `/tmp/claude-0/w10c1p/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-far.png`

### the_abyssal_shrine-underground-t1

Picked for: underground type 1: 24.9% of cells; notableId coal_ore; height 130; cave pockets 1% (layer 16%)
Census: sky 18.5%; void 32.6%; unloaded 0.5%; near: oak_planks 18.3%, rail 6.6%; mid: oak_planks 1.4%; far: stone 15.1%, oak_planks 1.9%; biomes: deep_cold_ocean 84.7%, adventure:sky_highlands 6.6%, adventure:chalk_cliffs 6.4%
Image: `/tmp/claude-0/w10c1p/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-underground-t1.png`

### the_abyssal_shrine-surface-t2

Picked for: surface type 2: 37.7% of cells; biome deep_cold_ocean; ground gravel; top water; height 115; ground plants 0% (layer 9%); liquid cover 96% (layer 36%)
Census: sky 31.7%; near: water 51.6%; mid: water 3.9%; far: grass_block 6.5%, calcite 2.5%; biomes: deep_cold_ocean 82.7%, adventure:chalk_cliffs 12.8%
Image: `/tmp/claude-0/w10c1p/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t2.png`

### the_abyssal_shrine-surface-t3

Picked for: surface type 3: 13.1% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 209; layer present in 89% (layer 97%); notable-block density (log) 0.9 (layer 0.4); liquid cover 0% (layer 36%); bare rock 72% (layer 10%)
Census: sky 34.7%; near: blackstone 30.2%, soul_fire 12.1%; mid: obsidian 2.9%, blackstone 2%; biomes: adventure:umbral_wastes 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-surface-t3.png`

### the_abyssal_shrine-sky-t2

Picked for: sky type 2: 33.3% of cells; biome adventure:sky_highlands; ground grass_block; height 177; neighbour step 5.9 (layer 2.3); relief over 16 blocks 43.0 (layer 23.4); notable-block density (log) 1.2 (layer 0.8)
Census: sky 38.3%; void 0.1%; near: grass_block 9.3%, calcite 3.9%; mid: water 13.4%; far: water 18.7%, grass_block 5%; biomes: deep_cold_ocean 47.9%, adventure:sky_highlands 20.8%, adventure:umbral_wastes 12.5%
Image: `/tmp/claude-0/w10c1p/reviews/the_abyssal_shrine/screens/the_abyssal_shrine-sky-t2.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
