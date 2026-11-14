# Review packet: the_snowbound_isle

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_snowbound_isle.json`

> A snowbound island adrift in the sky. Fir forests and drifts on floating ground — animal country where the cold keeps everything honest. The towers and vaults here grew naturally, like the trees.

Biomes: paradise_lost:autumnal_tundra, paradise_lost:highlands_shield, minecraft:snowy_taiga, natures_spirit:snowy_fir_forest, terralith:snowy_cherry_grove, terralith:skylands_winter, terralith:frozen_cliffs, minecraft:snowy_plains, minecraft:snowy_slopes

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_snowbound_isle.json`

> A snowbound island adrift in the sky. Fir forests and drifts on floating ground — animal country where the cold keeps everything honest. The towers and vaults here grew naturally, like the trees.

Biomes: minecraft:snowy_taiga, minecraft:snowy_plains, minecraft:snowy_slopes, adventure:windswept_isles, adventure:dying_forest, minecraft:cherry_grove

Terrain: `{"base": "floating_islands", "params": {"size": 4, "coverage": 0.6, "altitude": 120}, "layout": {"type": "radial", "centre": [0, 51], "radius": 260, "falloff": 320, "strength": 1}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fin/full/the_snowbound_isle/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fin/full/the_snowbound_isle/layers`

## Summary

- Top blocks seen from the sky: snow 24.2%, stone 4.6%, powder_snow 1.1%, spruce_leaves 0.6%.
- Surface biomes: snowy_taiga 12.5%, adventure:dying_forest 6.6%, adventure:windswept_isles 6.5%, snowy_slopes 4.4%, snowy_plains 1.6%.
- Ground blocks: grass_block 14.1%, stone 6.7%, podzol 5.4%, snow_block 3.9%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 25.6% (a snowing climate over 31.6%); tree canopy 8%; bare rock 4.7%; no ground (void) 67.6%.
- Trees: 48 per ha (spruce_log 43.3/ha, mean spacing 4.8 blocks, spruce_wood 2.8/ha, mean spacing 11.2 blocks, oak_log 1.4/ha, mean spacing 3.2 blocks, cherry_log 0.5/ha, mean spacing 13.6 blocks); ground plants grass 0.5%, flower 0.3%, mushroom 0%, bush 0%.
- Surface height p5 97, median 142, p95 168, max 177; median relief per patch 0 blocks; slopes under 15° 45.2%, over 45° 6.8%, cliffs 0.3%; above the clouds 0%, below sea level 1.6%.
- Underground: 13.7% open, 0% liquid; cave biomes snowy_taiga 40.4%, adventure:windswept_isles 21.5%, adventure:dying_forest 16.8%, snowy_slopes 15.7%.
- Notable clusters, fewest first: cobweb 2 clusters, spacing n/a; spawner 2 clusters, spacing n/a; lava 3 clusters, spacing 18.6; powder_snow 4 clusters, spacing 23.9; lapis_ore 5 clusters, spacing 18.3; diamond_ore 6 clusters, spacing 13.2.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 373 columns: height within 2 blocks 97.1%, biome match 100%.
- Landforms over the border (noise render): plains 4.7%, hills 4.6%, mountains 1.2%, plateaus 1.9%, basins 1.2%, islands 0%, water 0%, void 86.4%.
- Placeness: biome regions 1.7/km² (6 major, largest 5.0%), landform regions 3.6/km² (largest 86.4%), dominant place 86.4% of the border.

## Layers

- Columns by class: void 67.6%, roofed 0%, stacked 0%, overhang 2.3%, cave 6%, single 24.1%.
- Columns with each layer: sky 0%, surface 32.4%, underground 32.2%, roof 0%.
- Overhangs in 2.3% of columns, stacked layers in 0%, cave pockets in 6.6%.

## Places

- **surface** (95 cells, 6 types, silhouette 0.484):
  - type 1: 28.4% of cells; biome snowy_taiga; ground grass_block; top snow; height 149; trees per cell 6.2 (layer 3.6); canopy 55% (layer 24%); snow or ice 98% (layer 79%); open to the sky 45% (layer 76%) — camera [280.5,163,-116.5]
  - type 2: 21.1% of cells; biome adventure:dying_forest; ground podzol; top snow; height 110; trees per cell 6.1 (layer 3.6) — camera [40.5,112,376.5]
  - type 3: 16.8% of cells; biome adventure:windswept_isles; ground stone; top stone; height 151; trees per cell 0.8 (layer 3.6); canopy 6% (layer 24%); snow or ice 12% (layer 79%); bare rock 78% (layer 14%); open to the sky 94% (layer 76%) — camera [-56.5,167,232.5]
  - type 4: 15.8% of cells; biome snowy_slopes; ground snow_block; top snow; height 158; trees per cell 0.1 (layer 3.6); canopy 1% (layer 24%); snow or ice 98% (layer 79%); open to the sky 99% (layer 76%) — camera [-167.5,164,264.5]
  - type 5: 11.6% of cells; biome snowy_taiga; ground grass_block; top snow; height 131; neighbour step 0.5 (layer 0.2); relief over 16 blocks 15.0 (layer 7.3); relief over the patch 46.0 (layer 21.1); canopy 38% (layer 24%); ground plants 7% (layer 3%); open to the sky 62% (layer 76%); overhangs 22% (layer 7%); cave pockets 57% (layer 20%) — camera [324.5,142,-88.5]
  - type 6: 6.3% of cells; biome snowy_plains; ground grass_block; top snow; height 80; layer present in 71% (layer 98%); relief over 16 blocks 10.7 (layer 7.3); trees per cell 0.2 (layer 3.6); canopy 0% (layer 24%); open to the sky 100% (layer 76%); overhangs 22% (layer 7%) — camera [-108.5,126,-293.5]
  - landmark at [-280,-40]: biome adventure:windswept_isles (type adventure:dying_forest); top podzol (type snow); neighbour step 1.3 (type 0.2); relief over 16 blocks 29.0 (type 3.0); snow or ice 2% (type 100%)
  - landmark at [-152,-24]: biome cherry_grove (type snowy_taiga); top pink_petals (type snow); ground plants 50% (type 1%); snow or ice 9% (type 100%); relief over the patch 28.0 (type 50.0)
  - landmark at [-280,-56]: top podzol (type snow); overhangs 57% (type 0%); snow or ice 3% (type 100%); relief over the patch 35.0 (type 4.0)
  - landmark at [-312,88]: ground stone (type snow_block); top powder_snow (type snow); neighbour step 0.5 (type 0.1); notable-block density (log) 1.2 (type 0.2); relief over the patch 29.0 (type 9.0)
- **sky**: no cells.
- **underground** (95 cells, 5 types, silhouette 0.298):
  - type 1: 43.2% of cells; notableId coal_ore; biome snowy_taiga; height 146; relief over 16 blocks 4.0 (layer 7.1); cave air 3% (layer 11%); cave pockets 3% (layer 20%) — camera [258.5,134.88,-145.5]
  - type 2: 25.3% of cells; notableId coal_ore; biome snowy_taiga; height 125; relief over 16 blocks 10.9 (layer 7.1); trees per cell 0.4 (layer 0.1); cave air 27% (layer 11%); cave pockets 46% (layer 20%) — camera [329.5,14.88,-105.5]
  - type 3: 11.6% of cells; biome adventure:windswept_isles; notableId coal_ore; height 149; notable-block density (log) 0.3 (layer 0.7); cave pockets 41% (layer 20%) — camera [-54.5,134.88,226.5]
  - type 4: 10.5% of cells; notableId coal_ore; biome adventure:dying_forest; height 111; neighbour step 0.4 (layer 0.2) — camera [-190.5,8.88,-14.5]
  - type 5: 9.5% of cells; notableId iron_ore; biome adventure:windswept_isles; height 132; layer present in 82% (layer 98%); relief over 16 blocks 11.1 (layer 7.1); notable-block density (log) 0.2 (layer 0.7); cave air 2% (layer 11%); cave pockets 4% (layer 20%) — camera [-302.5,79.88,-46.5]
  - landmark at [616,88]: notableId tuff_bricks (type coal_ore); biome snowy_plains (type none); notable-block density (log) 3.1 (type 0.8); height -7.0 (type 158.9); layer present in 64% (type 100%)
  - landmark at [616,104]: notableId waxed_copper_block (type coal_ore); biome snowy_plains (type snowy_taiga); height -13.2 (type 146.6); cave pockets 0% (type 46%); layer present in 82% (type 100%)
  - landmark at [-152,-40]: notableId oak_planks (type coal_ore); biome snowy_plains (type snowy_taiga); notable-block density (log) 1.0 (type 0.6); cave air 42% (type 28%); neighbour step 0.5 (type 0.3)
  - landmark at [168,72]: notableId oak_planks (type coal_ore); notable-block density (log) 1.3 (type 0.6); relief over 16 blocks 5.0 (type 12.0); cave air 16% (type 28%)
- **roof**: no cells.

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:snowy_taiga: expressed, 26656 columns; placement land 34.6%, void 65.4%; snow 98%, canopy 51.3%, trees 223.5/ha; top snow 33.3%, spruce_leaves 0.6%, fern 0.3%
- minecraft:snowy_plains: expressed, 19776 columns; placement land 6%, void 94%; snow 96.9%, canopy 3.1%, trees 0/ha; top snow 5.8%, cherry_leaves 0.1%, short_grass 0%
- adventure:dying_forest: expressed, 11680 columns; placement land 41.6%, void 58.4%; snow 90.1%, canopy 14%, trees 252.9/ha; top snow 37.4%, podzol 2.4%, stone 0.3%
- adventure:windswept_isles: expressed, 7168 columns; placement land 66.5%, void 33.5%; snow 22.1%, canopy 5.7%, trees 23.1/ha; top stone 45.9%, snow 14.4%, spruce_leaves 3%
- minecraft:snowy_slopes: expressed, 5568 columns; placement land 58.6%, void 41.4%; snow 98.6%, canopy 0%, trees 0/ha; top snow 42.4%, powder_snow 13.8%, snow_block 1.6%
- minecraft:cherry_grove: expressed, 2880 columns; placement land 20%, void 80%; snow 5.9%, canopy 29.9%, trees 69.4/ha; top grass_block 6%, pink_petals 5.6%, cherry_leaves 5.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | paradise_lost:autumnal_tundra | adventure:golden_skygrove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O2 | paradise_lost:highlands_shield | adventure:windswept_isles | 6.5 | 7168 | expressed | - |
| O3 | minecraft:snowy_taiga | minecraft:snowy_taiga | 12.5 | 26656 | expressed | - |
| O4 | natures_spirit:snowy_fir_forest | minecraft:snowy_taiga | 12.5 | 26656 | expressed | - |
| O5 | terralith:snowy_cherry_grove | minecraft:cherry_grove | 0.8 | 2880 | expressed | - |
| O6 | terralith:skylands_winter | adventure:windswept_isles | 6.5 | 7168 | expressed | - |
| O7 | terralith:frozen_cliffs | minecraft:jagged_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | minecraft:snowy_plains | minecraft:snowy_plains | 1.6 | 19776 | expressed | - |
| O9 | minecraft:snowy_slopes | minecraft:snowy_slopes | 4.4 | 5568 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:dying_forest | 6.6 | 11680 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:snowy_taiga 12.5%, adventure:dying_forest 6.6%, adventure:windswept_isles 6.5% | type `paradise_lost:paradise_lost` |
| M:snow | 25.6% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 67.6% (`surface.voidPercent`) | |
| M:canopy | 8.0% (`surface.canopyPercent`) | |
| M:relief | median 0.0 blocks a patch; height {"p5": 97.0, "p25": 115.0, "p50": 142.0, "p75": 163.0, "p95": 168.0, "max": 177.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 32.4, "underground": 32.2, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 67.6, "roofed": 0.0, "stacked": 0.0, "overhang": 2.3, "cave": 6.0, "single": 24.1} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 79.6%
- S:b — 1.21.1 biomes expressed: 77.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (32.4% of columns)

## Shots

### the_snowbound_isle-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 0.8%; void 21.5%; mid: snow 44.5%, spruce_leaves 24.5%; far: spruce_leaves 1.6%, snow 1.3%; biomes: snowy_taiga 100%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-far.png`

### the_snowbound_isle-surface-t1

Picked for: surface type 1: 28.4% of cells; biome snowy_taiga; ground grass_block; top snow; height 149; trees per cell 6.2 (layer 3.6); canopy 55% (layer 24%); snow or ice 98% (layer 79%); open to the sky 45% (layer 76%)
Census: sky 9.3%; near: spruce_leaves 28.3%, snow 20%; mid: spruce_leaves 1.4%; biomes: snowy_taiga 100%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-t1.png`

### the_snowbound_isle-underground-t1

Picked for: underground type 1: 43.2% of cells; notableId coal_ore; biome snowy_taiga; height 146; relief over 16 blocks 4.0 (layer 7.1); cave air 3% (layer 11%); cave pockets 3% (layer 20%)
Census: sky 16.3%; void 42.5%; unloaded 5.8%; near: oak_planks 8.2%; mid: stone 8.7%; far: stone 13.4%; biomes: snowy_slopes 23%, adventure:dying_forest 22.8%, adventure:windswept_isles 21.9%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-underground-t1.png`

### the_snowbound_isle-surface-t3

Picked for: surface type 3: 16.8% of cells; biome adventure:windswept_isles; ground stone; top stone; height 151; trees per cell 0.8 (layer 3.6); canopy 6% (layer 24%); snow or ice 12% (layer 79%); bare rock 78% (layer 14%); open to the sky 94% (layer 76%)
Census: sky 34.3%; void 2.6%; near: stone 35.5%, spruce_leaves 12.6%; mid: snow 1.3%; biomes: adventure:windswept_isles 94.9%, snowy_slopes 5.1%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-t3.png`

### the_snowbound_isle-surface-t2

Picked for: surface type 2: 21.1% of cells; biome adventure:dying_forest; ground podzol; top snow; height 110; trees per cell 6.1 (layer 3.6)
Census: sky 12.9%; near: snow 28.6%, spruce_log 16.8%; mid: cherry_leaves 1.7%, spruce_log 1%; biomes: adventure:dying_forest 97.8%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-t2.png`

### the_snowbound_isle-surface-t5

Picked for: surface type 5: 11.6% of cells; biome snowy_taiga; ground grass_block; top snow; height 131; neighbour step 0.5 (layer 0.2); relief over 16 blocks 15.0 (layer 7.3); relief over the patch 46.0 (layer 21.1); canopy 38% (layer 24%); ground plants 7% (layer 3%); open to the sky 62% (layer 76%); overhangs 22% (layer 7%); cave pockets 57% (layer 20%)
Census: sky 18.7%; void 0.3%; near: spruce_log 29.6%, spruce_leaves 20.2%; mid: spruce_leaves 1.5%; far: spruce_leaves 1.4%; biomes: snowy_taiga 98.6%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-t5.png`

### the_snowbound_isle-surface-t4

Picked for: surface type 4: 15.8% of cells; biome snowy_slopes; ground snow_block; top snow; height 158; trees per cell 0.1 (layer 3.6); canopy 1% (layer 24%); snow or ice 98% (layer 79%); open to the sky 99% (layer 76%)
Census: sky 38.1%; void 3.4%; near: snow 38.1%, snow_block 15.1%; mid: snow_block 1.6%; far: snow 1.3%; biomes: snowy_slopes 99.9%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-t4.png`

### the_snowbound_isle-surface-lm1

Picked for: surface landmark: biome adventure:windswept_isles (type adventure:dying_forest); top podzol (type snow); neighbour step 1.3 (type 0.2); relief over 16 blocks 29.0 (type 3.0); snow or ice 2% (type 100%)
Census: sky 1.9%; near: spruce_log 37.6%, podzol 33%; biomes: adventure:dying_forest 99.3%
Image: `/tmp/claude-0/w10c1/reviews/the_snowbound_isle/screens/the_snowbound_isle-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
