# Review packet: the_obsidian_sanctum

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_obsidian_sanctum.json`

> The sanctum is the point. Incendium's cathedral dungeons in a small, dark world — altars and forbidden castles saturating the space. The piglins built their holiest places here, and the obsidian remembers every prayer.

Biomes: minecraft:soul_sand_valley, minecraft:nether_wastes, incendium:withered_forest, minecraft:basalt_deltas

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_obsidian_sanctum.json`

> The sanctum is the point. Incendium's cathedral dungeons in a small, dark world — altars and forbidden castles saturating the space. The piglins built their holiest places here, and the obsidian remembers every prayer.

Biomes: adventure:withered_forest, minecraft:soul_sand_valley, minecraft:nether_wastes, minecraft:basalt_deltas

Terrain: `{}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/c1/full/the_obsidian_sanctum/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c1/full/the_obsidian_sanctum/layers`

## Summary

- Top blocks seen from the sky: soul_soil 47%, obsidian 11.8%, netherrack 10.2%, soul_sand 7.5%.
- Surface biomes: adventure:withered_forest 56%, soul_sand_valley 24%, nether_wastes 11.9%, basalt_deltas 8.1%.
- Ground blocks: soul_soil 50.6%, netherrack 13%, obsidian 12.4%, soul_sand 7.5%.
- Liquid cover 0% water, 5.3% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.7%; bare rock 16.6%; no ground (void) 0%.
- Trees: 239.9 per ha (stripped_crimson_stem 111.6/ha, mean spacing 3.6 blocks, stripped_warped_hyphae 46.7/ha, mean spacing 4.9 blocks, stripped_warped_stem 42.5/ha, mean spacing 6.4 blocks, stripped_crimson_hyphae 39.1/ha, mean spacing 6.2 blocks); ground plants flower 1.4%, bush 1%, other 0.5%, mushroom 0.1%, grass 0%.
- Surface height p5 20, median 73, p95 127, max 127; median relief per patch 112 blocks; slopes under 15° 35.4%, over 45° 38.6%, cliffs 23.8%; above the clouds 0%, below sea level 4.1%.
- Underground: 21% open, 1.1% liquid; cave biomes adventure:withered_forest 57.5%, soul_sand_valley 22.4%, basalt_deltas 13.4%, nether_wastes 6.7%.
- Notable clusters, fewest first: dried_ghast 2 clusters, spacing n/a; nether_bricks 3 clusters, spacing 9.1; brown_mushroom 22 clusters, spacing 3.6; basalt 24 clusters, spacing 8.3; bone_block 25 clusters, spacing 4.7; red_mushroom 29 clusters, spacing 5.1.
- 4 of 4 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 80.2%, biome match 100%.
- Landforms over the border (noise render): plains 39.1%, hills 27.6%, mountains 14.5%, plateaus 7.9%, basins 10.9%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 1.7/km² (5 major, largest 50.4%), landform regions 11.9/km² (largest 13.2%), dominant place 50.4% of the border.

## Layers

- Columns by class: void 0%, roofed 94.6%, stacked 0%, overhang 0%, cave 0%, single 5.4%.
- Columns with each layer: sky 1.2%, surface 100%, underground 98.1%, roof 94.6%.
- Overhangs in 35% of columns, stacked layers in 1.2%, cave pockets in 35.3%.

## Places

- **surface** (225 cells, 6 types, silhouette 0.397):
  - type 1: 48.4% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 67; trees per cell 5.4 (layer 2.6); canopy 3% (layer 2%); ground plants 6% (layer 3%) — camera [375.5,35,-270.5]
  - type 2: 16.0% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 74; relief over the patch 97.5 (layer 107.8); trees per cell 0.0 (layer 2.6); notable-block density (log) 1.1 (layer 0.6); canopy 0% (layer 2%); ground plants 1% (layer 3%) — camera [153.5,83,-695.5]
  - type 3: 13.8% of cells; biome nether_wastes; ground obsidian; top obsidian; height 45; relief over the patch 116.1 (layer 107.8); trees per cell 0.1 (layer 2.6); canopy 0% (layer 2%); ground plants 0% (layer 3%) — camera [962.5,23,644.5]
  - type 4: 8.4% of cells; biome basalt_deltas; ground basalt; top basalt; height 58; relief over the patch 97.1 (layer 107.8); trees per cell 0.0 (layer 2.6); canopy 0% (layer 2%); ground plants 0% (layer 3%); liquid cover 16% (layer 5%); bare rock 70% (layer 17%) — camera [-612.5,31,743.5]
  - type 5: 8.4% of cells; biome adventure:withered_forest; ground netherrack; top netherrack; height 77; neighbour step 6.8 (layer 4.1); relief over the patch 116.3 (layer 107.8); trees per cell 0.5 (layer 2.6); canopy 1% (layer 2%); ground plants 1% (layer 3%); liquid cover 12% (layer 5%); bare rock 47% (layer 17%); overhangs 18% (layer 35%) — camera [743.5,40,-280.5]
  - type 6: 4.9% of cells; biome soul_sand_valley; ground bedrock; top bedrock; height 114; neighbour step 8.1 (layer 4.1); relief over 16 blocks 111.6 (layer 71.5); relief over the patch 117.0 (layer 107.8); trees per cell 0.1 (layer 2.6); notable-block density (log) 0.0 (layer 0.6); canopy 0% (layer 2%); ground plants 0% (layer 3%); open to the sky 72% (layer 5%); overhangs 3% (layer 35%); cave pockets 1% (layer 35%); under a roof 28% (layer 95%) — camera [-711.5,101,-775.5]
  - landmark at [680,8]: biome adventure:withered_forest (type nether_wastes); ground bedrock (type obsidian); top lava (type obsidian); liquid cover 74% (type 4%); notable-block density (log) 3.0 (type 0.3); overhangs 30% (type 2%)
  - landmark at [-856,-376]: top lava (type netherrack); liquid cover 83% (type 3%); notable-block density (log) 3.4 (type 0.1); height 8.0 (type 79.8)
  - landmark at [24,-520]: biome soul_sand_valley (type nether_wastes); ground bedrock (type obsidian); top lava (type obsidian); liquid cover 65% (type 4%); notable-block density (log) 2.8 (type 0.3); overhangs 31% (type 2%)
  - landmark at [-856,-360]: top lava (type netherrack); liquid cover 91% (type 3%); height 8.1 (type 79.8); relief over 16 blocks 22.0 (type 88.0)
- **sky** (4 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:withered_forest; ground soul_soil; height 69 — camera [698.5,103,-27.5]
- **underground** (224 cells, 2 types, silhouette 0.215):
  - type 1: 61.6% of cells; notableId magma_block; biome adventure:withered_forest; height 79 — camera [306.5,42.88,270.5]
  - type 2: 38.4% of cells; notableId blackstone; biome basalt_deltas; height 50; trees per cell 0.1 (layer 3.0); cave air 10% (layer 20%); cave pockets 10% (layer 36%) — camera [1002.5,23.88,665.5]
- **roof** (219 cells, 2 types, silhouette 0.422):
  - type 1: 74.0% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 16.1 (layer 31.8) — camera [792.5,102.88,216.5]
  - type 2: 26.0% of cells; notableId nether_quartz_ore; height 79; layer present in 92% (layer 97%); neighbour step 4.4 (layer 1.6); relief over 16 blocks 76.4 (layer 31.8) — camera [39.5,50.88,-39.5]
  - landmark at [40,-8]: notableId lava (type nether_quartz_ore); neighbour step 9.6 (type 4.3); trees per cell 2.0 (type 0.0); height 53.3 (type 78.0)
  - landmark at [984,680]: notableId magma_block (type nether_quartz_ore); layer present in 68% (type 100%); height 35.3 (type 78.0); notable-block density (log) 1.1 (type 0.3)
  - landmark at [-680,-808]: notableId lava (type nether_quartz_ore); layer present in 51% (type 100%); relief over 16 blocks 103.0 (type 10.0); neighbour step 2.0 (type 0.3)
  - landmark at [-712,-776]: notableId lava (type nether_quartz_ore); layer present in 41% (type 100%); height 112.0 (type 78.0); relief over 16 blocks 110.0 (type 71.0)
  - landmark at [-712,-792]: notableId nether_gold_ore (type nether_quartz_ore); layer present in 39% (type 100%); relief over 16 blocks 92.0 (type 10.0); neighbour step 1.9 (type 0.3)

## Biome expression

4 of 4 placed biomes are expressed.

- adventure:withered_forest: expressed, 32886 columns; placement land 92.9%, lava 5.2%, roofed 95.4%, sky 1.9%; snow 0%, canopy 3.3%, trees 202.7/ha; top soul_soil 69.8%, netherrack 11.3%, lava 5.2%
- minecraft:soul_sand_valley: expressed, 13849 columns; placement land 97%, lava 2.8%, roofed 86.9%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 31.1%, soul_soil 29.6%, bedrock 12.9%
- minecraft:nether_wastes: expressed, 6836 columns; placement land 97.4%, lava 2.5%, roofed 94%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top obsidian 68.4%, netherrack 19%, bedrock 6%
- minecraft:basalt_deltas: expressed, 4694 columns; placement land 83%, lava 16.9%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top basalt 66.8%, lava 16.9%, blackstone 6.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 24.0 | 13849 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 11.9 | 6836 | expressed | - |
| O3 | incendium:withered_forest | adventure:withered_forest | 56.0 | 32886 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 8.1 | 4694 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:withered_forest 56.0%, minecraft:soul_sand_valley 24.0%, minecraft:nether_wastes 11.9% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 5.3% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 1.7% (`surface.canopyPercent`) | |
| M:relief | median 112.0 blocks a patch; height {"p5": 20.0, "p25": 33.0, "p50": 73.0, "p75": 94.0, "p95": 127.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 1.2, "surface": 100.0, "underground": 98.1, "roof": 94.6} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B0B2B", "fogColor": "#1B0B2B", "ambientLight": 0.05}` |
| M:classes | {"void": 0.0, "roofed": 94.6, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 5.4} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (94.6% of columns)

## Shots

### the_obsidian_sanctum-surface-t1

Picked for: surface type 1: 48.4% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 67; trees per cell 5.4 (layer 2.6); canopy 3% (layer 2%); ground plants 6% (layer 3%)
Census: sky 0%; near: soul_soil 51.6%, stripped_crimson_stem 16.3%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t1.png`

### the_obsidian_sanctum-surface-t1-nv

Picked for: surface type 1: 48.4% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 67; trees per cell 5.4 (layer 2.6); canopy 3% (layer 2%); ground plants 6% (layer 3%)
Census: sky 0%; near: soul_soil 51.6%, stripped_crimson_stem 16.3%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t1-nv.png`

### the_obsidian_sanctum-sky-t1

Picked for: sky type 1: 100.0% of cells; biome adventure:withered_forest; ground soul_soil; height 69
Census: sky 0%; near: soul_soil 38.7%, stripped_crimson_stem 30.2%; mid: netherrack 16.1%, soul_soil 3.1%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t1.png`

### the_obsidian_sanctum-sky-t1-nv

Picked for: sky type 1: 100.0% of cells; biome adventure:withered_forest; ground soul_soil; height 69
Census: sky 0%; near: soul_soil 38.7%, stripped_crimson_stem 30.2%; mid: netherrack 16.1%, soul_soil 3.1%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t1-nv.png`

### the_obsidian_sanctum-underground-t1

Picked for: underground type 1: 61.6% of cells; notableId magma_block; biome adventure:withered_forest; height 79
Census: sky 0%; near: netherrack 28.6%, soul_soil 23%; mid: netherrack 25.6%, soul_soil 3.1%; far: netherrack 5.5%, soul_soil 1.6%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-underground-t1.png`

### the_obsidian_sanctum-underground-t1-nv

Picked for: underground type 1: 61.6% of cells; notableId magma_block; biome adventure:withered_forest; height 79
Census: sky 0%; near: netherrack 28.6%, soul_soil 23%; mid: netherrack 25.6%, soul_soil 3.1%; far: netherrack 5.4%, soul_soil 1.6%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-underground-t1-nv.png`

### the_obsidian_sanctum-roof-t1

Picked for: roof type 1: 74.0% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 16.1 (layer 31.8)
Census: sky 0%; near: netherrack 71.5%, nether_quartz_ore 1.4%; mid: netherrack 14.8%, glowstone 2.6%; far: netherrack 4.4%, soul_soil 1.4%; biomes: adventure:withered_forest 99.9%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-roof-t1.png`

### the_obsidian_sanctum-roof-t1-nv

Picked for: roof type 1: 74.0% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 16.1 (layer 31.8)
Census: sky 0%; near: netherrack 71.5%, nether_quartz_ore 1.4%; mid: netherrack 14.8%, glowstone 2.6%; far: netherrack 4.4%, soul_soil 1.4%; biomes: adventure:withered_forest 99.9%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-roof-t1-nv.png`

### the_obsidian_sanctum-surface-t2

Picked for: surface type 2: 16.0% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 74; relief over the patch 97.5 (layer 107.8); trees per cell 0.0 (layer 2.6); notable-block density (log) 1.1 (layer 0.6); canopy 0% (layer 2%); ground plants 1% (layer 3%)
Census: sky 0.8%; near: soul_soil 47.8%, soul_sand 11.2%; mid: soul_sand 19.1%, soul_soil 12.7%; biomes: soul_sand_valley 99%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t2.png`

### the_obsidian_sanctum-surface-t2-nv

Picked for: surface type 2: 16.0% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 74; relief over the patch 97.5 (layer 107.8); trees per cell 0.0 (layer 2.6); notable-block density (log) 1.1 (layer 0.6); canopy 0% (layer 2%); ground plants 1% (layer 3%)
Census: sky 0.8%; near: soul_soil 47.8%, soul_sand 11.2%; mid: soul_sand 19.1%, soul_soil 12.7%; biomes: soul_sand_valley 99%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t2-nv.png`

### the_obsidian_sanctum-surface-t3

Picked for: surface type 3: 13.8% of cells; biome nether_wastes; ground obsidian; top obsidian; height 45; relief over the patch 116.1 (layer 107.8); trees per cell 0.1 (layer 2.6); canopy 0% (layer 2%); ground plants 0% (layer 3%)
Census: sky 0%; near: obsidian 52%, netherrack 10.7%; mid: netherrack 24.5%, obsidian 7.2%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t3.png`

### the_obsidian_sanctum-surface-t3-nv

Picked for: surface type 3: 13.8% of cells; biome nether_wastes; ground obsidian; top obsidian; height 45; relief over the patch 116.1 (layer 107.8); trees per cell 0.1 (layer 2.6); canopy 0% (layer 2%); ground plants 0% (layer 3%)
Census: sky 0%; near: obsidian 52%, netherrack 10.7%; mid: netherrack 24.4%, obsidian 7%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t3-nv.png`

### the_obsidian_sanctum-surface-lm1

Picked for: surface landmark: biome adventure:withered_forest (type nether_wastes); ground bedrock (type obsidian); top lava (type obsidian); liquid cover 74% (type 4%); notable-block density (log) 3.0 (type 0.3); overhangs 30% (type 2%)
Census: sky 0%; near: lava 39.9%, netherrack 32.6%; mid: netherrack 3.1%, soul_soil 1.3%; far: netherrack 4.6%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-lm1.png`

### the_obsidian_sanctum-surface-lm1-nv

Picked for: surface landmark: biome adventure:withered_forest (type nether_wastes); ground bedrock (type obsidian); top lava (type obsidian); liquid cover 74% (type 4%); notable-block density (log) 3.0 (type 0.3); overhangs 30% (type 2%)
Census: sky 0%; near: lava 39.9%, netherrack 32.6%; mid: netherrack 3.1%, soul_soil 1.3%; far: netherrack 4.6%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
