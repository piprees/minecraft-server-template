# Review packet: the_blighted_maw

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_blighted_maw.json`

> A toxic little hell that came apart. Piglin scientists built labs and reactors here to study the sculk infection from the boneyard; the blight won, and took the ground with it. What is left is a tight cluster of floating islands with a long fall between them. Fall off and the blight rejects you rather than killing you. Loot escalates with the danger. The blight is not finished; a build beside the portal is just ground it has not taken yet.

Biomes: incendium:toxic_heap, minecraft:soul_sand_valley, incendium:withered_forest, incendium:ash_barrens, minecraft:nether_wastes

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_blighted_maw.json`

> A toxic little hell that came apart. Piglin scientists built labs and reactors here to study the sculk infection from the boneyard; the blight won, and took the ground with it. What is left is a tight cluster of floating islands with a long fall between them. Fall off and the blight rejects you rather than killing you. Loot escalates with the danger. The blight is not finished; a build beside the portal is just ground it has not taken yet.

Biomes: adventure:mycotoxic_undergrowth, minecraft:soul_sand_valley, adventure:withered_forest, adventure:ash_barrens, minecraft:nether_wastes

Terrain: `{"base": "end", "params": {"coverage": 0.12}, "layout": {"type": "radial", "centre": [0, 0], "radius": 200, "falloff": 300, "strength": 0.8}, "scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "end", "endIsland": false}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_blighted_maw/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_blighted_maw/layers`

## Summary

- Top blocks seen from the sky: soul_sand 4.4%, moss_block 3.7%, netherrack 3.5%, slime_block 3.1%.
- Surface biomes: adventure:mycotoxic_undergrowth 7.6%, soul_sand_valley 5.8%, nether_wastes 2.7%, adventure:withered_forest 1.2%, adventure:ash_barrens 0.2%.
- Ground blocks: soul_sand 4.4%, moss_block 3.8%, netherrack 3.6%, slime_block 3.1%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0.1%; bare rock 3.9%; no ground (void) 82.5%.
- Trees: 18.4 per ha (warped_stem 15/ha, mean spacing 3.9 blocks, stripped_crimson_stem 1.8/ha, mean spacing 5.5 blocks, stripped_warped_hyphae 0.9/ha, mean spacing 4.5 blocks, stripped_crimson_hyphae 0.4/ha, mean spacing 6.4 blocks); ground plants other 0.1%, bush 0%, flower 0%.
- Surface height p5 37, median 58, p95 71, max 84; median relief per patch 0 blocks; slopes under 15° 33.5%, over 45° 38.4%, cliffs 11.8%; above the clouds 0%, below sea level 0%.
- Underground: 6% open, 0% liquid; cave biomes adventure:mycotoxic_undergrowth 40.7%, soul_sand_valley 34.8%, nether_wastes 14.7%, adventure:withered_forest 8.1%.
- Notable clusters, fewest first: spawner 2 clusters, spacing n/a; warped_nylium 3 clusters, spacing 8.2; gilded_blackstone 6 clusters, spacing 9; soul_fire 8 clusters, spacing 8.3; nether_bricks 12 clusters, spacing 18.1; glowstone 15 clusters, spacing 13.3.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 449 columns: height within 2 blocks 69.3%, biome match 100%.
- Landforms over the border (noise render): plains 2.8%, hills 2%, mountains 0%, plateaus 0%, basins 0%, islands 3.7%, water 0%, void 91.4%.
- Placeness: biome regions 2.4/km² (2 major, largest 1.1%), landform regions 2.9/km² (largest 91.0%), dominant place 91.0% of the border.

## Layers

- Columns by class: void 82.5%, roofed 0%, stacked 0.1%, overhang 5.6%, cave 0.6%, single 11.2%.
- Columns with each layer: sky 0.1%, surface 17.5%, underground 15.7%, roof 0%.
- Overhangs in 5.7% of columns, stacked layers in 0.1%, cave pockets in 1%.

## Places

- **surface** (127 cells, 4 types, silhouette 0.427):
  - type 1: 37.8% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 60; neighbour step 1.8 (layer 1.3); relief over the patch 50.4 (layer 42.1); overhangs 49% (layer 33%) — camera [936.5,72,-983.5]
  - type 2: 34.6% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 55; relief over the patch 35.2 (layer 42.1); trees per cell 0.0 (layer 1.2); ground plants 0% (layer 1%); bare rock 2% (layer 21%) — camera [-167.5,60,72.5]
  - type 3: 21.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 51; bare rock 86% (layer 21%); overhangs 15% (layer 33%) — camera [-1016.5,62,-760.5]
  - type 4: 6.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 52; trees per cell 5.8 (layer 1.2); canopy 4% (layer 0%); ground plants 6% (layer 1%); open to the sky 96% (layer 99%) — camera [-791.5,58,856.5]
  - landmark at [744,-936]: ground slime_block (type moss_block); top slime_block (type moss_block); stacked layers 10% (type 0%); open to the sky 90% (type 100%); cave pockets 25% (type 0%)
  - landmark at [-824,872]: trees per cell 1.0 (type 8.0); canopy 1% (type 5%); ground plants 0% (type 6%)
  - landmark at [-808,872]: biome adventure:withered_forest (type nether_wastes); height 35.0 (type 54.2); notable-block density (log) 2.0 (type 0.1); trees per cell 4.0 (type 0.0)
  - landmark at [-728,952]: layer present in 27% (type 97%); trees per cell 2.0 (type 8.0); ground plants 10% (type 6%)
  - landmark at [824,-872]: ground slime_block (type moss_block); top slime_block (type moss_block); stacked layers 4% (type 0%); open to the sky 96% (type 100%); canopy 2% (type 0%)
- **sky**: no cells.
- **underground** (116 cells, 2 types, silhouette 0.244):
  - type 1: 55.2% of cells; notableId magma_block; biome soul_sand_valley; height 56; trees per cell 0.1 (layer 1.3) — camera [157.5,43.88,-118.5]
  - type 2: 44.8% of cells; notableId magma_block; biome adventure:mycotoxic_undergrowth; height 60; neighbour step 1.8 (layer 1.3); relief over 16 blocks 26.8 (layer 20.7); trees per cell 2.7 (layer 1.3) — camera [725.5,58.88,-931.5]
  - landmark at [40,-232]: notableId soul_soil (type magma_block); biome soul_sand_valley (type none); cave pockets 36% (type 0%); notable-block density (log) 2.9 (type 1.1); layer present in 52% (type 100%)
  - landmark at [712,-856]: notableId slime_block (type magma_block); cave pockets 39% (type 3%); cave air 45% (type 2%); relief over 16 blocks 58.0 (type 24.0)
  - landmark at [-376,-632]: notableId basalt (type magma_block); biome soul_sand_valley (type none); cave air 54% (type 0%); layer present in 50% (type 100%); height 45.4 (type 56.9)
  - landmark at [920,-984]: notableId slime_block (type magma_block); layer present in 36% (type 98%); cave air 31% (type 2%); cave pockets 22% (type 3%)
  - landmark at [184,-168]: notableId soul_sand (type magma_block); biome soul_sand_valley (type none); neighbour step 2.8 (type 0.6); cave pockets 15% (type 0%); cave air 15% (type 0%)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- minecraft:soul_sand_valley: expressed, 70390 columns; placement land 13.6%, void 86.4%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 10.3%, nether_bricks 1.8%, polished_blackstone_bricks 0.5%
- adventure:ash_barrens: expressed, 41584 columns; placement land 0.9%, void 99.1%; snow 0%, canopy 0%, trees 0/ha; top tuff 0.8%, bone_block 0%, basalt 0%
- adventure:mycotoxic_undergrowth: expressed, 31886 columns; placement land 39.6%, void 60.1%, sky 0.3%; snow 0%, canopy 0.1%, trees 75.2/ha; top moss_block 19.1%, slime_block 16%, netherrack 3.1%
- adventure:withered_forest: expressed, 15232 columns; placement land 13.4%, void 86.6%; snow 0%, canopy 3.6%, trees 273.4/ha; top soul_soil 9.7%, netherrack 2.5%, dead_bush 0.4%
- minecraft:nether_wastes: expressed, 6920 columns; placement land 64.4%, void 35.5%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top netherrack 62.2%, fire 0.7%, nether_quartz_ore 0.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | incendium:toxic_heap | adventure:mycotoxic_undergrowth | 7.6 | 31886 | expressed | - |
| O2 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 5.8 | 70390 | expressed | - |
| O3 | incendium:withered_forest | adventure:withered_forest | 1.2 | 15232 | expressed | - |
| O4 | incendium:ash_barrens | adventure:ash_barrens | 0.2 | 41584 | expressed | - |
| O5 | minecraft:nether_wastes | minecraft:nether_wastes | 2.7 | 6920 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:mycotoxic_undergrowth 7.6%, minecraft:soul_sand_valley 5.8%, minecraft:nether_wastes 2.7% | type `nether_islands` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:netherrack", "defaultFluid": "minecraft:lava", "seaLevel": -60}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 82.5% (`surface.voidPercent`) | |
| M:canopy | 0.1% (`surface.canopyPercent`) | |
| M:relief | median 0.0 blocks a patch; height {"p5": 37.0, "p25": 55.0, "p50": 58.0, "p75": 62.0, "p95": 71.0, "max": 84.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 17.5, "underground": 15.7, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#3B1B1B", "ambientLight": 0.08}` |
| M:classes | {"void": 82.5, "roofed": 0.0, "stacked": 0.1, "overhang": 5.6, "cave": 0.6, "single": 11.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (17.5% of columns)

## Shots

### the_blighted_maw-surface-t1

Picked for: surface type 1: 28.8% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 53; neighbour step 0.7 (layer 1.2); trees per cell 0.1 (layer 1.2); ground plants 0% (layer 1%); bare rock 1% (layer 21%); overhangs 16% (layer 31%)
Census: sky 35.1%; near: soul_sand 46.9%; mid: slime_block 6.6%, soul_sand 3.9%; far: nether_bricks 2.3%; biomes: soul_sand_valley 85%, adventure:mycotoxic_undergrowth 15%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t1.png`

### the_blighted_maw-surface-t1-nv

Picked for: surface type 1: 28.8% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 53; neighbour step 0.7 (layer 1.2); trees per cell 0.1 (layer 1.2); ground plants 0% (layer 1%); bare rock 1% (layer 21%); overhangs 16% (layer 31%)
Census: sky 35.1%; near: soul_sand 46.9%; mid: slime_block 6.6%, soul_sand 3.9%; far: nether_bricks 2.3%; biomes: soul_sand_valley 85%, adventure:mycotoxic_undergrowth 15%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t1-nv.png`

### the_blighted_maw-underground-t1

Picked for: underground type 1: 43.1% of cells; notableId magma_block; biome adventure:mycotoxic_undergrowth; height 60; neighbour step 1.9 (layer 1.2); relief over 16 blocks 26.6 (layer 20.3); trees per cell 2.8 (layer 1.3)
Census: sky 0.6%; void 0.3%; near: moss_block 51.7%, slime_block 27%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-underground-t1.png`

### the_blighted_maw-underground-t1-nv

Picked for: underground type 1: 43.1% of cells; notableId magma_block; biome adventure:mycotoxic_undergrowth; height 60; neighbour step 1.9 (layer 1.2); relief over 16 blocks 26.6 (layer 20.3); trees per cell 2.8 (layer 1.3)
Census: sky 0.6%; void 0.3%; near: moss_block 51.7%, slime_block 27%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-underground-t1-nv.png`

### the_blighted_maw-surface-t2

Picked for: surface type 2: 25.6% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 59; neighbour step 1.7 (layer 1.2); relief over the patch 50.5 (layer 42.4); overhangs 46% (layer 31%)
Census: sky 43.1%; void 12.6%; near: slime_block 18.1%; mid: slime_block 6.4%, netherrack 5%; far: slime_block 4.6%, netherrack 1.8%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t2.png`

### the_blighted_maw-surface-t2-nv

Picked for: surface type 2: 25.6% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 59; neighbour step 1.7 (layer 1.2); relief over the patch 50.5 (layer 42.4); overhangs 46% (layer 31%)
Census: sky 43.1%; void 12.6%; near: slime_block 18.1%; mid: slime_block 6.4%, netherrack 5%; far: slime_block 4.6%, netherrack 1.8%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t2-nv.png`

### the_blighted_maw-surface-t4

Picked for: surface type 4: 11.2% of cells; biome adventure:mycotoxic_undergrowth; ground slime_block; top slime_block; height 61; neighbour step 1.9 (layer 1.2); relief over the patch 49.0 (layer 42.4); trees per cell 2.5 (layer 1.2); bare rock 0% (layer 21%); open to the sky 98% (layer 99%); overhangs 56% (layer 31%); stacked layers 1% (layer 0%)
Census: sky 2.2%; near: moss_block 39.4%, slime_block 31.9%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t4.png`

### the_blighted_maw-surface-t4-nv

Picked for: surface type 4: 11.2% of cells; biome adventure:mycotoxic_undergrowth; ground slime_block; top slime_block; height 61; neighbour step 1.9 (layer 1.2); relief over the patch 49.0 (layer 42.4); trees per cell 2.5 (layer 1.2); bare rock 0% (layer 21%); open to the sky 98% (layer 99%); overhangs 56% (layer 31%); stacked layers 1% (layer 0%)
Census: sky 2.2%; near: moss_block 39.4%, slime_block 31.9%; biomes: adventure:mycotoxic_undergrowth 100%
Image: `/tmp/claude-0/w10c3/reviews/the_blighted_maw/screens/the_blighted_maw-surface-t4-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
