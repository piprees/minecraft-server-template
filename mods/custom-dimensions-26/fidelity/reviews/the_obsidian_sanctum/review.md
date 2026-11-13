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

Dossier: `/tmp/claude-0/w10c2/runs/r3/full/the_obsidian_sanctum/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/r3/full/the_obsidian_sanctum/layers`

## Summary

- Top blocks seen from the sky: soul_soil 52.4%, soul_sand 10.8%, netherrack 9.1%, obsidian 7.3%.
- Surface biomes: adventure:withered_forest 57%, soul_sand_valley 28%, nether_wastes 8.6%, basalt_deltas 6.4%.
- Ground blocks: soul_soil 56.6%, netherrack 12%, soul_sand 10.8%, obsidian 7.9%.
- Liquid cover 0% water, 6.1% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 2.1%; bare rock 13%; no ground (void) 0%.
- Trees: 256.4 per ha (stripped_crimson_stem 122.4/ha, mean spacing 3.6 blocks, stripped_warped_hyphae 47.2/ha, mean spacing 5.5 blocks, stripped_warped_stem 47/ha, mean spacing 5.8 blocks, stripped_crimson_hyphae 39.8/ha, mean spacing 6.1 blocks); ground plants flower 1.7%, bush 1.5%, other 0.5%, mushroom 0%, grass 0%.
- Surface height p5 21, median 72, p95 111, max 127; median relief per patch 107 blocks; slopes under 15° 39.9%, over 45° 34.9%, cliffs 21.9%; above the clouds 0%, below sea level 3.6%.
- Underground: 29.4% open, 1% liquid; cave biomes adventure:withered_forest 51.7%, soul_sand_valley 27.4%, nether_wastes 12.6%, basalt_deltas 8.4%.
- Notable clusters, fewest first: dried_ghast 7 clusters, spacing 36.1; basalt 12 clusters, spacing 11.7; red_mushroom 19 clusters, spacing 5.6; glowstone 37 clusters, spacing 15; bone_block 42 clusters, spacing 5.4; fire 72 clusters, spacing 5.8.
- 4 of 4 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 80.9%, biome match 100%.
- Landforms over the border (noise render): plains 39.1%, hills 27.6%, mountains 14.5%, plateaus 7.9%, basins 10.9%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 1.7/km² (5 major, largest 50.4%), landform regions 11.9/km² (largest 13.2%), dominant place 50.4% of the border.

## Layers

- Columns by class: void 0%, roofed 96.9%, stacked 0%, overhang 0%, cave 0%, single 3.1%.
- Columns with each layer: sky 3.8%, surface 100%, underground 98.1%, roof 96.9%.
- Overhangs in 34.4% of columns, stacked layers in 3.8%, cave pockets in 39.7%.

## Places

- **surface** (225 cells, 2 types, silhouette 0.335):
  - type 1: 52.0% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 59; trees per cell 5.9 (layer 3.1); canopy 4% (layer 2%); ground plants 7% (layer 4%) — camera [632.5,81,600.5]
  - type 2: 48.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 72; trees per cell 0.1 (layer 3.1); canopy 0% (layer 2%); ground plants 0% (layer 4%) — camera [712.5,95,-935.5]
  - landmark at [-360,-40]: biome adventure:withered_forest (type soul_sand_valley); ground netherrack (type soul_sand); top netherrack (type soul_sand); liquid cover 34% (type 0%); neighbour step 12.2 (type 4.7); open to the sky 29% (type 0%)
  - landmark at [328,648]: biome nether_wastes (type soul_sand_valley); ground netherrack (type soul_sand); top netherrack (type soul_sand); neighbour step 15.0 (type 4.7); bare rock 63% (type 0%); relief over the patch 125.0 (type 104.0)
  - landmark at [-360,-24]: biome adventure:withered_forest (type soul_sand_valley); ground netherrack (type soul_sand); top netherrack (type soul_sand); liquid cover 42% (type 0%); neighbour step 15.7 (type 4.7); notable-block density (log) 2.9 (type 0.1)
  - landmark at [-648,760]: biome basalt_deltas (type soul_sand_valley); ground basalt (type soul_sand); top basalt (type soul_sand); stacked layers 48% (type 0%); bare rock 61% (type 0%); height 24.4 (type 82.5)
  - landmark at [152,-328]: ground bedrock (type soul_soil); top lava (type soul_soil); liquid cover 70% (type 0%); notable-block density (log) 3.3 (type 0.3); ground plants 2% (type 11%)
- **sky** (14 cells, 2 types, silhouette 0.318):
  - type 1: 64.3% of cells; biome adventure:withered_forest; ground soul_soil; height 83 — camera [199.5,73,233.5]
  - type 2: 35.7% of cells; biome basalt_deltas; ground basalt; height 77; layer present in 40% (layer 53%); neighbour step 3.5 (layer 2.8); relief over 16 blocks 33.6 (layer 43.0); trees per cell 0.2 (layer 2.4) — camera [613.5,76,632.5]
- **underground** (225 cells, 5 types, silhouette 0.254):
  - type 1: 24.4% of cells; notableId blackstone; biome adventure:withered_forest; height 85; trees per cell 9.2 (layer 2.9); cave pockets 85% (layer 40%) — camera [-631.5,27.88,-215.5]
  - type 2: 21.8% of cells; notableId blackstone; biome adventure:withered_forest; height 30; trees per cell 0.5 (layer 2.9); notable-block density (log) 0.5 (layer 1.3); cave air 6% (layer 26%); cave pockets 1% (layer 40%) — camera [862.5,22.88,210.5]
  - type 3: 20.0% of cells; notableId blackstone; biome adventure:withered_forest; height 61; neighbour step 7.0 (layer 4.1); relief over 16 blocks 90.0 (layer 70.2); notable-block density (log) 0.8 (layer 1.3); cave air 46% (layer 26%) — camera [889.5,34.88,-266.5]
  - type 4: 18.2% of cells; notableId soul_sand; biome soul_sand_valley; height 76; trees per cell 0.0 (layer 2.9); notable-block density (log) 2.1 (layer 1.3) — camera [-887.5,64.88,-663.5]
  - type 5: 15.6% of cells; notableId magma_block; biome adventure:withered_forest; height 80; cave air 12% (layer 26%); cave pockets 21% (layer 40%) — camera [858.5,71.88,-263.5]
  - landmark at [-360,-24]: notableId nether_quartz_ore (type blackstone); layer present in 66% (type 100%); cave liquid 10% (type 0%); neighbour step 14.8 (type 7.6)
  - landmark at [344,648]: notableId lava (type magma_block); biome nether_wastes (type adventure:withered_forest); cave liquid 6% (type 0%); relief over 16 blocks 116.0 (type 51.0); neighbour step 9.7 (type 4.5)
  - landmark at [-600,-232]: notableId soul_soil (type blackstone); layer present in 73% (type 100%); cave liquid 6% (type 0%); notable-block density (log) 2.3 (type 1.3)
  - landmark at [-584,-232]: notableId obsidian (type blackstone); cave liquid 17% (type 0%); layer present in 72% (type 100%); height 88.0 (type 49.7)
  - landmark at [152,-328]: notableId soul_fire (type blackstone); layer present in 31% (type 100%); cave liquid 13% (type 0%); neighbour step 9.6 (type 1.5)
- **roof** (222 cells, 3 types, silhouette 0.362):
  - type 1: 62.2% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 15.7 (layer 31.0) — camera [168.5,103.88,232.5]
  - type 2: 20.3% of cells; notableId soul_soil; height 93; notable-block density (log) 1.4 (layer 0.5) — camera [-7.5,86.88,-695.5]
  - type 3: 17.6% of cells; notableId nether_quartz_ore; height 80; layer present in 92% (layer 98%); neighbour step 4.8 (layer 1.6); relief over 16 blocks 72.8 (layer 31.0) — camera [39.5,50.88,-39.5]
  - landmark at [40,-8]: notableId lava (type nether_quartz_ore); neighbour step 9.6 (type 4.3); trees per cell 2.0 (type 0.0); height 53.3 (type 78.0)
  - landmark at [808,-872]: notableId soul_sand (type nether_quartz_ore); layer present in 62% (type 100%); height 37.2 (type 78.0); notable-block density (log) 1.6 (type 0.3)
  - landmark at [824,-872]: notableId soul_sand (type soul_soil); height 36.2 (type 96.9); layer present in 78% (type 100%); relief over 16 blocks 91.0 (type 39.0)
  - landmark at [328,648]: notableId magma_block (type nether_quartz_ore); height 50.9 (type 78.0); neighbour step 7.3 (type 4.3); layer present in 87% (type 100%)
  - landmark at [808,-856]: layer present in 40% (type 100%); height 40.5 (type 96.9); relief over 16 blocks 86.0 (type 39.0)

## Biome expression

4 of 4 placed biomes are expressed.

- adventure:withered_forest: expressed, 34388 columns; placement land 90.3%, lava 5.1%, roofed 93.8%, sky 4.5%; snow 0%, canopy 3.8%, trees 237.9/ha; top soul_soil 67.7%, netherrack 10.4%, lava 5.1%
- minecraft:soul_sand_valley: expressed, 16270 columns; placement land 97.4%, lava 1.8%, roofed 92.5%, sky 0.9%; snow 0%, canopy 0%, trees 0/ha; top soul_soil 42.2%, soul_sand 38.3%, bedrock 6.6%
- minecraft:nether_wastes: expressed, 5037 columns; placement land 93.4%, lava 4.7%, roofed 95.1%, sky 1.8%; snow 0%, canopy 0%, trees 0/ha; top obsidian 68.2%, netherrack 20.2%, lava 4.7%
- minecraft:basalt_deltas: expressed, 4113 columns; placement land 59.7%, lava 30.1%, roofed 89.9%, sky 10.1%; snow 0%, canopy 0%, trees 4.1/ha; top basalt 44.3%, lava 30.1%, blackstone 6.4%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 28.0 | 16270 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 8.6 | 5037 | expressed | - |
| O3 | incendium:withered_forest | adventure:withered_forest | 57.0 | 34388 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 6.4 | 4113 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:withered_forest 57.0%, minecraft:soul_sand_valley 28.0%, minecraft:nether_wastes 8.6% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 6.1% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 2.1% (`surface.canopyPercent`) | |
| M:relief | median 107.0 blocks a patch; height {"p5": 21.0, "p25": 26.0, "p50": 72.0, "p75": 93.0, "p95": 111.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 3.8, "surface": 100.0, "underground": 98.1, "roof": 96.9} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#1B0B2B", "fogColor": "#1B0B2B", "ambientLight": 0.05}` |
| M:classes | {"void": 0.0, "roofed": 96.9, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 3.1} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (96.9% of columns)

## Shots

### the_obsidian_sanctum-surface-t1

Picked for: surface type 1: 52.0% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 59; trees per cell 5.9 (layer 3.1); canopy 4% (layer 2%); ground plants 7% (layer 4%)
Census: sky 0%; near: soul_soil 30.4%, stripped_crimson_stem 12.2%; mid: netherrack 16.8%, soul_soil 6.1%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t1.png`

### the_obsidian_sanctum-surface-t1-nv

Picked for: surface type 1: 52.0% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 59; trees per cell 5.9 (layer 3.1); canopy 4% (layer 2%); ground plants 7% (layer 4%)
Census: sky 0%; near: soul_soil 30.4%, stripped_crimson_stem 12.2%; mid: netherrack 16.8%, soul_soil 6.1%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t1-nv.png`

### the_obsidian_sanctum-sky-t1

Picked for: sky type 1: 64.3% of cells; biome adventure:withered_forest; ground soul_soil; height 83
Census: sky 0%; near: netherrack 38.4%, soul_soil 37.4%; mid: netherrack 11.5%, soul_soil 6.6%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t1.png`

### the_obsidian_sanctum-sky-t1-nv

Picked for: sky type 1: 64.3% of cells; biome adventure:withered_forest; ground soul_soil; height 83
Census: sky 0%; near: netherrack 38.4%, soul_soil 37.4%; mid: netherrack 11.5%, soul_soil 6.4%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t1-nv.png`

### the_obsidian_sanctum-underground-t2

Picked for: underground type 2: 21.8% of cells; notableId blackstone; biome adventure:withered_forest; height 30; trees per cell 0.5 (layer 2.9); notable-block density (log) 0.5 (layer 1.3); cave air 6% (layer 26%); cave pockets 1% (layer 40%)
Census: sky 0%; near: soul_soil 34.3%, stripped_crimson_stem 16.3%; mid: netherrack 4.6%, soul_soil 3%; far: netherrack 8.7%, soul_soil 1.5%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-underground-t2.png`

### the_obsidian_sanctum-underground-t2-nv

Picked for: underground type 2: 21.8% of cells; notableId blackstone; biome adventure:withered_forest; height 30; trees per cell 0.5 (layer 2.9); notable-block density (log) 0.5 (layer 1.3); cave air 6% (layer 26%); cave pockets 1% (layer 40%)
Census: sky 0%; near: soul_soil 34.3%, stripped_crimson_stem 16.3%; mid: netherrack 4.6%, soul_soil 3%; far: netherrack 8.7%, soul_soil 1.5%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-underground-t2-nv.png`

### the_obsidian_sanctum-roof-t1

Picked for: roof type 1: 62.2% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 15.7 (layer 31.0)
Census: sky 0%; near: netherrack 74.5%, glowstone 19.7%; mid: netherrack 2.7%; far: netherrack 1.7%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-roof-t1.png`

### the_obsidian_sanctum-roof-t1-nv

Picked for: roof type 1: 62.2% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 15.7 (layer 31.0)
Census: sky 0%; near: netherrack 74.5%, glowstone 19.7%; mid: netherrack 2.7%; far: netherrack 1.7%; biomes: adventure:withered_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-roof-t1-nv.png`

### the_obsidian_sanctum-surface-t2

Picked for: surface type 2: 48.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 72; trees per cell 0.1 (layer 3.1); canopy 0% (layer 2%); ground plants 0% (layer 4%)
Census: sky 0%; near: soul_sand 68.9%, soul_soil 17.7%; mid: soul_sand 4.6%, netherrack 4.3%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t2.png`

### the_obsidian_sanctum-surface-t2-nv

Picked for: surface type 2: 48.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 72; trees per cell 0.1 (layer 3.1); canopy 0% (layer 2%); ground plants 0% (layer 4%)
Census: sky 0%; near: soul_sand 68.9%, soul_soil 17.7%; mid: soul_sand 4.6%, netherrack 4.3%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-surface-t2-nv.png`

### the_obsidian_sanctum-sky-t2

Picked for: sky type 2: 35.7% of cells; biome basalt_deltas; ground basalt; height 77; layer present in 40% (layer 53%); neighbour step 3.5 (layer 2.8); relief over 16 blocks 33.6 (layer 43.0); trees per cell 0.2 (layer 2.4)
Census: sky 0%; near: basalt 66.6%, lava 1.3%; mid: basalt 22.1%, blackstone 1%; far: netherrack 4.6%, obsidian 2.5%; biomes: basalt_deltas 92.4%, nether_wastes 7.6%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t2.png`

### the_obsidian_sanctum-sky-t2-nv

Picked for: sky type 2: 35.7% of cells; biome basalt_deltas; ground basalt; height 77; layer present in 40% (layer 53%); neighbour step 3.5 (layer 2.8); relief over 16 blocks 33.6 (layer 43.0); trees per cell 0.2 (layer 2.4)
Census: sky 0%; near: basalt 66.6%, lava 1.3%; mid: basalt 22.1%, blackstone 1%; far: netherrack 4.6%, obsidian 2.5%; biomes: basalt_deltas 92.4%, nether_wastes 7.6%
Image: `/tmp/claude-0/w10c2/reviews/the_obsidian_sanctum/screens/the_obsidian_sanctum-sky-t2-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
