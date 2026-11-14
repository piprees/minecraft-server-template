# Review packet: the_starwell

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_starwell.json`

> A well of night sky — a few silvered islands adrift around a monolith. The endermen's last gift: a pocket of void where nothing hunts, falling sends you home, and the stars are close enough to touch.

Biomes: terralith:mirage_isles, terralith:skylands_winter, terralith:alpha_islands, terralith:alpha_islands_winter

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_starwell.json`

> A well of night sky — a few silvered islands adrift around a monolith. The endermen's last gift: a pocket of void where nothing hunts, falling sends you home, and the stars are close enough to touch.

Biomes: minecraft:snowy_plains, adventure:alpha_grove, adventure:windswept_isles, adventure:desert_oasis

Terrain: `{"base": "floating_islands", "setting": "gentle", "params": {"coverage": 0.16, "size": 1.0, "spread": 30, "thickness": 8}, "scales": {"climate": 3}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: ?

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_starwell/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_starwell/layers`

## Summary

- Top blocks seen from the sky: snow 6.8%, grass_block 4.7%, oak_leaves 2.4%, oak_planks 0.9%.
- Surface biomes: adventure:alpha_grove 7.1%, snowy_plains 6.5%, adventure:windswept_isles 2.6%, adventure:umbral_wastes 0.4%, adventure:desert_oasis 0.2%.
- Ground blocks: grass_block 12.1%, stone 2.3%, oak_planks 1%, dirt_path 0.3%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 6.8% (a snowing climate over 9.1%); tree canopy 2.7%; bare rock 1.1%; no ground (void) 83.2%.
- Trees: 14 per ha (oak_log 12.6/ha, mean spacing 5.4 blocks, spruce_log 0.9/ha, mean spacing 25.1 blocks, stripped_spruce_log 0.4/ha, mean spacing 10 blocks, stripped_spruce_wood 0.2/ha, mean spacing 15.5 blocks); ground plants other 0.1%, grass 0.1%, flower 0%, crop 0%, bush 0%.
- Surface height p5 15, median 143, p95 161, max 216; median relief per patch 0 blocks; slopes under 15° 34.6%, over 45° 13.7%, cliffs 6.1%; above the clouds 1.4%, below sea level 0%.
- Underground: 1.6% open, 0% liquid; cave biomes adventure:alpha_grove 42%, snowy_plains 36.4%, adventure:windswept_isles 16.8%, adventure:desert_oasis 2.5%.
- Notable clusters, fewest first: blue_bed 2 clusters, spacing n/a; tinted_glass 2 clusters, spacing n/a; packed_ice 3 clusters, spacing 4.7; sandstone 3 clusters, spacing 15.1; chest 4 clusters, spacing 19; furnace 4 clusters, spacing 19.4.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 403 columns: height within 2 blocks 97.5%, biome match 100%.
- Landforms over the border (noise render): plains 3.4%, hills 4.7%, mountains 1.1%, plateaus 1%, basins 1.1%, islands 2.6%, water 0%, void 86%.
- Placeness: biome regions 0.0/km² (5 major, largest 2.6%), landform regions 3.8/km² (largest 85.8%), dominant place 85.8% of the border.

## Layers

- Columns by class: void 83.2%, roofed 0%, stacked 1%, overhang 0.6%, cave 0%, single 15.2%.
- Columns with each layer: sky 1%, surface 16.8%, underground 9.8%, roof 0%.
- Overhangs in 0.7% of columns, stacked layers in 1%, cave pockets in 0%.

## Places

- **surface** (117 cells, 6 types, silhouette 0.508):
  - type 1: 38.5% of cells; biome adventure:alpha_grove; ground grass_block; top grass_block; height 141; trees per cell 3.4 (layer 1.7); notable-block density (log) 0.1 (layer 0.4); canopy 32% (layer 15%); snow or ice 9% (layer 42%); open to the sky 68% (layer 79%) — camera [72.5,146,21.5]
  - type 2: 28.2% of cells; biome snowy_plains; ground grass_block; top snow; height 156; trees per cell 0.3 (layer 1.7); canopy 2% (layer 15%); snow or ice 86% (layer 42%); open to the sky 98% (layer 79%) — camera [-151.5,154,-87.5]
  - type 3: 9.4% of cells; biome adventure:windswept_isles; ground stone; top snow; height 132; notable-block density (log) 0.7 (layer 0.4); snow or ice 69% (layer 42%) — camera [-55.5,127,200.5]
  - type 4: 9.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 146; layer present in 64% (layer 82%); trees per cell 0.0 (layer 1.7); canopy 0% (layer 15%); snow or ice 5% (layer 42%); bare rock 70% (layer 8%); open to the sky 100% (layer 79%) — camera [40.5,146,167.5]
  - type 5: 7.7% of cells; biome snowy_plains; ground grass_block; top snow; height 110; neighbour step 10.5 (layer 1.9); relief over 16 blocks 132.7 (layer 25.5); relief over the patch 137.6 (layer 43.1); trees per cell 0.7 (layer 1.7); canopy 2% (layer 15%); ground plants 3% (layer 1%); snow or ice 65% (layer 42%); overhangs 19% (layer 4%); stacked layers 22% (layer 6%); cave pockets 0% (layer 0%) — camera [-215.5,141,-135.5]
  - type 6: 6.8% of cells; biome snowy_plains; ground oak_planks; top oak_planks; height 61; neighbour step 12.9 (layer 1.9); relief over 16 blocks 131.0 (layer 25.5); relief over the patch 137.9 (layer 43.1); notable-block density (log) 0.7 (layer 0.4); canopy 2% (layer 15%); ground plants 8% (layer 1%); open to the sky 38% (layer 79%); stacked layers 60% (layer 6%) — camera [-231.5,12,-119.5]
  - landmark at [216,-136]: biome adventure:umbral_wastes (type adventure:windswept_isles); ground blackstone (type stone); top blackstone (type stone); notable-block density (log) 1.9 (type 0.4); ground plants 8% (type 0%); layer present in 95% (type 56%)
  - landmark at [232,216]: biome adventure:desert_oasis (type snowy_plains); ground sand (type grass_block); top sand (type snow); notable-block density (log) 1.5 (type 0.2); snow or ice 1% (type 99%); height 210.0 (type 152.9)
  - landmark at [-200,-152]: ground dirt_path (type grass_block); top dirt_path (type snow); cave pockets 3% (type 0%); neighbour step 2.3 (type 17.4); notable-block density (log) 1.0 (type 0.3)
  - landmark at [232,232]: biome adventure:desert_oasis (type snowy_plains); ground sand (type grass_block); top sand (type snow); snow or ice 0% (type 99%); height 210.5 (type 152.9); layer present in 43% (type 76%)
  - landmark at [216,216]: biome adventure:desert_oasis (type adventure:windswept_isles); ground sand (type stone); top sand (type stone); bare rock 0% (type 72%); height 200.2 (type 144.2); layer present in 26% (type 56%)
- **sky** (9 cells, 2 types, silhouette 0.304):
  - type 1: 66.7% of cells; biome snowy_plains; ground grass_block; height 136; relief over 16 blocks 8.2 (layer 6.2) — camera [-231.5,139,-119.5]
  - type 2: 33.3% of cells; biome snowy_plains; ground grass_block; height 140; neighbour step 0.1 (layer 0.3); relief over 16 blocks 2.3 (layer 6.2) — camera [-215.5,141,-119.5]
- **underground** (77 cells, 3 types, silhouette 0.318):
  - type 1: 49.4% of cells; notableId deepslate_coal_ore; biome snowy_plains; height 143 — camera [-226.5,120.88,-162.5]
  - type 2: 32.5% of cells; notableId deepslate_iron_ore; biome snowy_plains; height 134 — camera [-227.5,134.88,229.5]
  - type 3: 18.2% of cells; notableId glow_lichen; height 144; layer present in 42% (layer 72%); notable-block density (log) 0.0 (layer 0.1)
  - landmark at [-232,-152]: notableId oak_planks (type deepslate_iron_ore); biome snowy_plains (type none); notable-block density (log) 1.5 (type 0.0); neighbour step 12.5 (type 0.1); cave air 9% (type 0%)
  - landmark at [-232,-168]: biome snowy_plains (type none); cave air 13% (type 0%); layer present in 43% (type 100%); height 129.0 (type 145.1)
  - landmark at [-200,-136]: notableId oak_planks (type deepslate_coal_ore); neighbour step 7.6 (type 0.4); layer present in 63% (type 100%); notable-block density (log) 0.4 (type 0.1)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- minecraft:snowy_plains: expressed, 76131 columns; placement land 13%, void 85%, sky 2%; snow 77.8%, canopy 1.1%, trees 33.2/ha; top snow 10.1%, oak_planks 1.7%, dirt_path 0.6%
- adventure:windswept_isles: expressed, 34736 columns; placement land 11.2%, void 88.8%; snow 60%, canopy 8.1%, trees 20.5/ha; top snow 6.7%, stone 3.4%, grass_block 0.5%
- adventure:alpha_grove: expressed, 31280 columns; placement land 34.6%, void 65.4%; snow 2.6%, canopy 33.5%, trees 158.7/ha; top grass_block 22.1%, oak_leaves 11.4%, snow 0.9%
- adventure:umbral_wastes: expressed, 7888 columns; placement land 7%, void 93%; snow 0.4%, canopy 0%, trees 0/ha; top blackstone 4.7%, obsidian 1.3%, soul_fire 0.4%
- adventure:desert_oasis: expressed, 3520 columns; placement land 10.3%, void 89.7%; snow 0.3%, canopy 0%, trees 0/ha; top sand 10%, blackstone 0.1%, dead_bush 0.1%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:mirage_isles | adventure:desert_oasis | 0.2 | 3520 | expressed | - |
| O2 | terralith:skylands_winter | adventure:windswept_isles | 2.6 | 34736 | expressed | - |
| O3 | terralith:alpha_islands | adventure:alpha_grove | 7.1 | 31280 | expressed | - |
| O4 | terralith:alpha_islands_winter | minecraft:snowy_plains | 6.5 | 76131 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:umbral_wastes | 0.4 | 7888 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:alpha_grove 7.1%, minecraft:snowy_plains 6.5%, adventure:windswept_isles 2.6% | type `sky_islands` |
| M:snow | 6.8% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:deepslate", "seaLevel": -64}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 83.2% (`surface.voidPercent`) | |
| M:canopy | 2.7% (`surface.canopyPercent`) | |
| M:relief | median 0.0 blocks a patch; height {"p5": 15.0, "p25": 128.0, "p50": 143.0, "p75": 152.0, "p95": 161.0, "max": 216.0} (`terrain`) | |
| M:layers | {"sky": 1.0, "surface": 16.8, "underground": 9.8, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#0B0E2E", "fogColor": "#05060F", "ambientLight": 0.15, "fixedTime": 18000, "effects": "minecraft:the_end"}` |
| M:classes | {"void": 83.2, "roofed": 0.0, "stacked": 1.0, "overhang": 0.6, "cave": 0.0, "single": 15.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 97.6%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (16.8% of columns)

## Shots

### the_starwell-surface-t1

Picked for: surface type 1: 38.5% of cells; biome adventure:alpha_grove; ground grass_block; top grass_block; height 141; trees per cell 3.4 (layer 1.7); notable-block density (log) 0.1 (layer 0.4); canopy 32% (layer 15%); snow or ice 9% (layer 42%); open to the sky 68% (layer 79%)
Census: sky 24.2%; void 1.9%; near: oak_leaves 31.7%, grass_block 28.3%; biomes: adventure:alpha_grove 99.9%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t1.png`

### the_starwell-surface-t1-nv

Picked for: surface type 1: 38.5% of cells; biome adventure:alpha_grove; ground grass_block; top grass_block; height 141; trees per cell 3.4 (layer 1.7); notable-block density (log) 0.1 (layer 0.4); canopy 32% (layer 15%); snow or ice 9% (layer 42%); open to the sky 68% (layer 79%)
Census: sky 24.2%; void 1.9%; near: oak_leaves 31.7%, grass_block 28.3%; biomes: adventure:alpha_grove 99.9%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t1-nv.png`

### the_starwell-sky-t1

Picked for: sky type 1: 66.7% of cells; biome snowy_plains; ground grass_block; height 136; relief over 16 blocks 8.2 (layer 6.2)
Census: sky 37.4%; void 2.6%; near: snow 37.4%, grass_block 14.1%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-sky-t1.png`

### the_starwell-sky-t1-nv

Picked for: sky type 1: 66.7% of cells; biome snowy_plains; ground grass_block; height 136; relief over 16 blocks 8.2 (layer 6.2)
Census: sky 37.4%; void 2.6%; near: snow 37.4%, grass_block 14.1%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-sky-t1-nv.png`

### the_starwell-underground-t1

Picked for: underground type 1: 49.4% of cells; notableId deepslate_coal_ore; biome snowy_plains; height 143
Census: sky 0.3%; near: deepslate 83.2%, deepslate_coal_ore 13.5%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-underground-t1.png`

### the_starwell-underground-t1-nv

Picked for: underground type 1: 49.4% of cells; notableId deepslate_coal_ore; biome snowy_plains; height 143
Census: sky 0.3%; near: deepslate 83.2%, deepslate_coal_ore 13.5%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-underground-t1-nv.png`

### the_starwell-surface-t2

Picked for: surface type 2: 28.2% of cells; biome snowy_plains; ground grass_block; top snow; height 156; trees per cell 0.3 (layer 1.7); canopy 2% (layer 15%); snow or ice 86% (layer 42%); open to the sky 98% (layer 79%)
Census: sky 36.3%; void 0.1%; near: grass_block 35.9%, snow 23.5%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t2.png`

### the_starwell-surface-t2-nv

Picked for: surface type 2: 28.2% of cells; biome snowy_plains; ground grass_block; top snow; height 156; trees per cell 0.3 (layer 1.7); canopy 2% (layer 15%); snow or ice 86% (layer 42%); open to the sky 98% (layer 79%)
Census: sky 36.3%; void 0.1%; near: grass_block 35.9%, snow 23.5%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t2-nv.png`

### the_starwell-surface-t6

Picked for: surface type 6: 6.8% of cells; biome snowy_plains; ground oak_planks; top oak_planks; height 61; neighbour step 12.9 (layer 1.9); relief over 16 blocks 131.0 (layer 25.5); relief over the patch 137.9 (layer 43.1); notable-block density (log) 0.7 (layer 0.4); canopy 2% (layer 15%); ground plants 8% (layer 1%); open to the sky 38% (layer 79%); stacked layers 60% (layer 6%)
Census: sky 27.8%; void 6.6%; near: oak_planks 52%, oak_log 10.5%; mid: oak_planks 1.6%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t6.png`

### the_starwell-surface-t6-nv

Picked for: surface type 6: 6.8% of cells; biome snowy_plains; ground oak_planks; top oak_planks; height 61; neighbour step 12.9 (layer 1.9); relief over 16 blocks 131.0 (layer 25.5); relief over the patch 137.9 (layer 43.1); notable-block density (log) 0.7 (layer 0.4); canopy 2% (layer 15%); ground plants 8% (layer 1%); open to the sky 38% (layer 79%); stacked layers 60% (layer 6%)
Census: sky 27.8%; void 6.6%; near: oak_planks 52%, oak_log 10.5%; mid: oak_planks 1.6%; biomes: snowy_plains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t6-nv.png`

### the_starwell-surface-t4

Picked for: surface type 4: 9.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 146; layer present in 64% (layer 82%); trees per cell 0.0 (layer 1.7); canopy 0% (layer 15%); snow or ice 5% (layer 42%); bare rock 70% (layer 8%); open to the sky 100% (layer 79%)
Census: sky 35.7%; void 11.2%; near: cobblestone 21.5%, stone 15.8%; mid: deepslate 3.2%; biomes: adventure:windswept_isles 91.3%, adventure:alpha_grove 8.7%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t4.png`

### the_starwell-surface-t4-nv

Picked for: surface type 4: 9.4% of cells; biome adventure:windswept_isles; ground stone; top stone; height 146; layer present in 64% (layer 82%); trees per cell 0.0 (layer 1.7); canopy 0% (layer 15%); snow or ice 5% (layer 42%); bare rock 70% (layer 8%); open to the sky 100% (layer 79%)
Census: sky 35.7%; void 11.2%; near: cobblestone 21.5%, stone 15.8%; mid: deepslate 3.2%; biomes: adventure:windswept_isles 91.3%, adventure:alpha_grove 8.7%
Image: `/tmp/claude-0/w10c3/reviews/the_starwell/screens/the_starwell-surface-t4-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
