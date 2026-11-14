# Review packet: the_furnace_halls

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_furnace_halls.json`

> Classic nether halls — fortress corridors that defined piglin military power. A mega-fortress anchors the far end, built when the piglin empire was at its height. Brick and fire all the way down.

Biomes: minecraft:nether_wastes, minecraft:basalt_deltas, minecraft:crimson_forest, incendium:volcanic_deltas, minecraft:soul_sand_valley

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_furnace_halls.json`

> Classic nether halls — fortress corridors that defined piglin military power. A mega-fortress anchors the far end, built when the piglin empire was at its height. Brick and fire all the way down.

Biomes: minecraft:soul_sand_valley, minecraft:nether_wastes, minecraft:crimson_forest, adventure:magma_land, minecraft:basalt_deltas

Terrain: `{}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/c1/full/the_furnace_halls/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c1/full/the_furnace_halls/layers`

## Summary

- Top blocks seen from the sky: lava 24.6%, netherrack 23.9%, basalt 20.6%, soul_soil 6.1%.
- Surface biomes: basalt_deltas 37%, nether_wastes 29.6%, soul_sand_valley 18.5%, crimson_forest 7.4%, adventure:magma_land 7.4%.
- Ground blocks: netherrack 36.6%, basalt 25.9%, soul_soil 7.4%, soul_sand 7.2%.
- Liquid cover 0% water, 24.6% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.9%; bare rock 48.5%; no ground (void) 0%.
- Trees: 15.4 per ha (crimson_stem 15.4/ha, mean spacing 2.9 blocks); ground plants grass 0.7%, other 0.4%, mushroom 0.2%.
- Surface height p5 20, median 65, p95 116, max 127; median relief per patch 110 blocks; slopes under 15° 27.7%, over 45° 47.6%, cliffs 25.9%; above the clouds 0%, below sea level 21.9%.
- Underground: 20.3% open, 2.4% liquid; cave biomes basalt_deltas 40.9%, soul_sand_valley 24.6%, nether_wastes 15.9%, adventure:magma_land 9.3%.
- Notable clusters, fewest first: dried_ghast 2 clusters, spacing n/a; nether_bricks 6 clusters, spacing 28.5; basalt 11 clusters, spacing 8.1; bone_block 14 clusters, spacing 2.2; brown_mushroom 33 clusters, spacing 9.1; soul_fire 36 clusters, spacing 4.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 68%, biome match 100%.
- Landforms over the border (noise render): plains 37%, hills 25.9%, mountains 8.4%, plateaus 7%, basins 9.4%, islands 0.3%, water 12%, void 0%.
- Placeness: biome regions 1.2/km² (5 major, largest 28.1%), landform regions 16.7/km² (largest 9.5%), dominant place 28.1% of the border.

## Layers

- Columns by class: void 0%, roofed 96.1%, stacked 0%, overhang 0%, cave 0%, single 3.9%.
- Columns with each layer: sky 4.8%, surface 100%, underground 99%, roof 96.1%.
- Overhangs in 27.3% of columns, stacked layers in 4.8%, cave pockets in 31.5%.

## Places

- **surface** (243 cells, 4 types, silhouette 0.319):
  - type 1: 35.4% of cells; biome basalt_deltas; ground basalt; top basalt; height 70; bare rock 67% (layer 48%) — camera [-806.5,84,393.5]
  - type 2: 26.7% of cells; biome nether_wastes; ground netherrack; top netherrack; height 76; neighbour step 7.6 (layer 5.3); liquid cover 6% (layer 25%); bare rock 74% (layer 48%); open to the sky 11% (layer 4%); under a roof 89% (layer 96%) — camera [648.5,64,-797.5]
  - type 3: 22.2% of cells; biome nether_wastes; ground netherrack; top lava; height 28; liquid cover 68% (layer 25%); bare rock 17% (layer 48%); overhangs 13% (layer 27%); cave pockets 3% (layer 32%) — camera [984.5,24,-615.5]
  - type 4: 15.6% of cells; biome soul_sand_valley; ground soul_soil; top soul_sand; height 65; trees per cell 0.8 (layer 0.2); canopy 7% (layer 2%); ground plants 4% (layer 1%); liquid cover 8% (layer 25%); bare rock 10% (layer 48%); overhangs 43% (layer 27%) — camera [-487.5,61,-920.5]
  - landmark at [-968,-72]: ground bedrock (type basalt); top bedrock (type basalt); open to the sky 96% (type 0%); under a roof 4% (type 100%); relief over 16 blocks 118.0 (type 42.0)
  - landmark at [248,24]: biome crimson_forest (type nether_wastes); ground crimson_nylium (type netherrack); top crimson_nylium (type lava); stacked layers 65% (type 0%); liquid cover 1% (type 96%); canopy 27% (type 0%)
  - landmark at [360,-984]: ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 52% (type 3%); under a roof 48% (type 97%); bare rock 11% (type 95%)
  - landmark at [680,-24]: ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 79% (type 3%); under a roof 21% (type 97%); bare rock 16% (type 95%)
  - landmark at [248,936]: biome crimson_forest (type basalt_deltas); ground crimson_nylium (type basalt); top nether_wart_block (type basalt); canopy 64% (type 0%); trees per cell 7.0 (type 0.0); ground plants 27% (type 0%)
- **sky** (17 cells, 3 types, silhouette 0.462):
  - type 1: 47.1% of cells; biome basalt_deltas; ground basalt; height 90 — camera [-169.5,95,839.5]
  - type 2: 35.3% of cells; biome nether_wastes; ground netherrack; height 89 — camera [296.5,90,-408.5]
  - type 3: 17.6% of cells; biome soul_sand_valley; ground soul_soil; height 89; neighbour step 0.6 (layer 2.1); relief over 16 blocks 10.0 (layer 25.4); notable-block density (log) 1.1 (layer 0.5) — camera [-488.5,93,-935.5]
- **underground** (243 cells, 5 types, silhouette 0.265):
  - type 1: 28.0% of cells; notableId blackstone; biome basalt_deltas; height 79; cave pockets 64% (layer 32%) — camera [-414.5,86.88,980.5]
  - type 2: 22.6% of cells; notableId blackstone; biome basalt_deltas; height 31; neighbour step 2.5 (layer 5.2); notable-block density (log) 0.7 (layer 1.3); cave air 5% (layer 19%); cave pockets 1% (layer 32%) — camera [978.5,22.88,-609.5]
  - type 3: 20.2% of cells; notableId magma_block; biome nether_wastes; height 82; cave air 8% (layer 19%) — camera [674.5,84.88,-760.5]
  - type 4: 17.7% of cells; notableId blackstone; biome nether_wastes; height 47; neighbour step 7.5 (layer 5.2); relief over 16 blocks 89.8 (layer 72.3); notable-block density (log) 0.6 (layer 1.3); cave air 32% (layer 19%) — camera [665.5,57.88,-532.5]
  - type 5: 11.5% of cells; notableId soul_soil; biome soul_sand_valley; height 68; notable-block density (log) 2.1 (layer 1.3); cave air 33% (layer 19%); cave liquid 5% (layer 2%); cave pockets 58% (layer 32%) — camera [-813.5,42.88,-647.5]
  - landmark at [-360,-328]: notableId soul_sand (type soul_soil); cave liquid 22% (type 0%); cave pockets 100% (type 50%); relief over 16 blocks 30.0 (type 69.0)
  - landmark at [232,920]: biome crimson_forest (type nether_wastes); cave liquid 17% (type 3%); relief over 16 blocks 33.0 (type 98.0); cave air 66% (type 31%)
  - landmark at [344,-984]: layer present in 72% (type 100%); cave liquid 16% (type 3%); neighbour step 18.1 (type 6.5)
  - landmark at [248,920]: notableId lava (type blackstone); biome crimson_forest (type basalt_deltas); notable-block density (log) 4.0 (type 1.5); cave liquid 12% (type 0%); trees per cell 1.0 (type 0.0)
  - landmark at [248,936]: notableId nether_quartz_ore (type blackstone); biome crimson_forest (type basalt_deltas); cave liquid 20% (type 0%); relief over 16 blocks 27.0 (type 74.0); trees per cell 1.0 (type 0.0)
- **roof** (241 cells, 8 types, silhouette 0.327):
  - type 1: 23.7% of cells; notableId blackstone; height 105; neighbour step 0.7 (layer 2.0); relief over 16 blocks 17.6 (layer 36.9) — camera [-872.5,101.88,824.5]
  - type 2: 20.7% of cells; notableId nether_quartz_ore; height 87; relief over 16 blocks 54.7 (layer 36.9); notable-block density (log) 0.4 (layer 0.7) — camera [664.5,72.88,808.5]
  - type 3: 15.8% of cells; notableId nether_quartz_ore; height 110; neighbour step 0.6 (layer 2.0); relief over 16 blocks 14.9 (layer 36.9); notable-block density (log) 0.1 (layer 0.7) — camera [984.5,107.88,-632.5]
  - type 4: 9.5% of cells; notableId nether_gold_ore; height 107; neighbour step 0.4 (layer 2.0); relief over 16 blocks 15.3 (layer 36.9); notable-block density (log) 0.1 (layer 0.7) — camera [968.5,103.88,-615.5]
  - type 5: 9.5% of cells; notableId blackstone; height 69; neighbour step 5.6 (layer 2.0); relief over 16 blocks 75.3 (layer 36.9); notable-block density (log) 2.0 (layer 0.7) — camera [-487.5,54.88,408.5]
  - type 6: 8.3% of cells; notableId nether_quartz_ore; height 88; layer present in 76% (layer 97%); neighbour step 4.0 (layer 2.0); relief over 16 blocks 88.9 (layer 36.9) — camera [696.5,86.88,-7.5]
  - type 7: 6.2% of cells; notableId soul_sand; height 108; notable-block density (log) 1.3 (layer 0.7) — camera [-359.5,99.88,-327.5]
  - type 8: 6.2% of cells; notableId soul_soil; height 106; notable-block density (log) 1.4 (layer 0.7) — camera [-759.5,104.88,-583.5]
  - landmark at [360,-984]: notableId magma_block (type blackstone); layer present in 48% (type 100%); height 39.1 (type 63.4); neighbour step 8.2 (type 4.8)
  - landmark at [376,-968]: notableId magma_block (type blackstone); layer present in 42% (type 100%); notable-block density (log) 0.8 (type 2.4); relief over 16 blocks 90.0 (type 66.0)
  - landmark at [344,-984]: notableId magma_block (type nether_quartz_ore); neighbour step 13.1 (type 2.4); relief over 16 blocks 96.0 (type 52.0); height 70.7 (type 85.4)
  - landmark at [-856,840]: notable-block density (log) 4.2 (type 2.4); layer present in 79% (type 100%); height 83.4 (type 63.4)
  - landmark at [376,-952]: notableId nether_bricks (type nether_quartz_ore); height 53.3 (type 89.9); notable-block density (log) 1.8 (type 0.3); neighbour step 2.5 (type 2.0)

## Biome expression

5 of 5 placed biomes are expressed.

- minecraft:basalt_deltas: expressed, 24631 columns; placement land 72.2%, lava 21.4%, roofed 90.9%, sky 6.5%; snow 0%, canopy 0%, trees 0/ha; top basalt 51.7%, lava 21.4%, blackstone 9.9%
- minecraft:nether_wastes: expressed, 19124 columns; placement land 65.5%, lava 30.9%, roofed 87.9%, sky 3.6%; snow 0%, canopy 0%, trees 0/ha; top netherrack 53.8%, lava 30.9%, bedrock 8.4%
- minecraft:soul_sand_valley: expressed, 11949 columns; placement land 81.3%, lava 15.1%, roofed 96.4%, sky 3.6%; snow 0%, canopy 0%, trees 0/ha; top soul_soil 31.8%, soul_sand 29.9%, lava 15.1%
- minecraft:crimson_forest: expressed, 4852 columns; placement land 84%, lava 10.9%, roofed 95%, sky 5%; snow 0%, canopy 28.7%, trees 125.1/ha; top netherrack 29.6%, nether_wart_block 23.7%, crimson_nylium 20.1%
- adventure:magma_land: expressed, 4658 columns; placement land 60.6%, lava 38.3%, roofed 95.6%, sky 1.1%; snow 0%, canopy 0%, trees 0/ha; top lava 38.3%, netherrack 28.1%, magma_block 19%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:nether_wastes | minecraft:nether_wastes | 29.6 | 19124 | expressed | - |
| O2 | minecraft:basalt_deltas | minecraft:basalt_deltas | 37.0 | 24631 | expressed | - |
| O3 | minecraft:crimson_forest | minecraft:crimson_forest | 7.4 | 4852 | expressed | - |
| O4 | incendium:volcanic_deltas | adventure:magma_land | 7.4 | 4658 | expressed | - |
| O5 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 18.5 | 11949 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:basalt_deltas 37.0%, minecraft:nether_wastes 29.6%, minecraft:soul_sand_valley 18.5% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 24.6% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 1.9% (`surface.canopyPercent`) | |
| M:relief | median 110.0 blocks a patch; height {"p5": 20.0, "p25": 26.0, "p50": 65.0, "p75": 87.0, "p95": 116.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 4.8, "surface": 100.0, "underground": 99.0, "roof": 96.1} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 96.1, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 3.9} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (96.1% of columns)

## Shots

### the_furnace_halls-surface-t1

Picked for: surface type 1: 30.9% of cells; biome basalt_deltas; ground basalt; top basalt; height 53; bare rock 66% (layer 40%)
Census: sky 0%; near: basalt 62.3%; mid: basalt 30.8%, blackstone 1.5%; far: basalt 4.8%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-t1.png`

### the_furnace_halls-surface-t1-nv

Picked for: surface type 1: 30.9% of cells; biome basalt_deltas; ground basalt; top basalt; height 53; bare rock 66% (layer 40%)
Census: sky 0%; near: basalt 62.3%; mid: basalt 30.8%, blackstone 1.5%; far: basalt 4.8%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-t1-nv.png`

### the_furnace_halls-sky-t1

Picked for: sky type 1: 100.0% of cells; biome basalt_deltas; ground basalt; height 68
Census: sky 0%; near: basalt 37.5%, lava 10.2%; mid: basalt 40.2%, blackstone 2.8%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-sky-t1.png`

### the_furnace_halls-sky-t1-nv

Picked for: sky type 1: 100.0% of cells; biome basalt_deltas; ground basalt; height 68
Census: sky 0%; near: basalt 37.5%, lava 10.2%; mid: basalt 40.2%, blackstone 2.8%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-sky-t1-nv.png`

### the_furnace_halls-underground-t1

Picked for: underground type 1: 56.8% of cells; notableId blackstone; biome basalt_deltas; height 51
Census: sky 0%; near: basalt 91.7%, blackstone 2.4%; mid: basalt 5.3%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-underground-t1.png`

### the_furnace_halls-underground-t1-nv

Picked for: underground type 1: 56.8% of cells; notableId blackstone; biome basalt_deltas; height 51
Census: sky 0%; near: basalt 91.7%, blackstone 2.4%; mid: basalt 5.3%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-underground-t1-nv.png`

### the_furnace_halls-roof-t1

Picked for: roof type 1: 38.6% of cells; notableId blackstone; height 97; notable-block density (log) 1.2 (layer 0.8)
Census: sky 0%; near: basalt 50.3%, blackstone 4.1%; mid: basalt 16.4%, netherrack 14.7%; far: netherrack 3.5%, nether_wart_block 1.8%; biomes: basalt_deltas 74%, crimson_forest 26%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-roof-t1.png`

### the_furnace_halls-roof-t1-nv

Picked for: roof type 1: 38.6% of cells; notableId blackstone; height 97; notable-block density (log) 1.2 (layer 0.8)
Census: sky 0%; near: basalt 50.3%, blackstone 4.1%; mid: basalt 16.4%, netherrack 14.7%; far: netherrack 3.5%, nether_wart_block 1.8%; biomes: basalt_deltas 74%, crimson_forest 26%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-roof-t1-nv.png`

### the_furnace_halls-surface-t2

Picked for: surface type 2: 21.4% of cells; biome nether_wastes; ground netherrack; top netherrack; height 66; liquid cover 7% (layer 20%); bare rock 71% (layer 40%)
Census: sky 0%; near: netherrack 74.6%, gravel 14.6%; mid: netherrack 4.5%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-t2.png`

### the_furnace_halls-surface-t2-nv

Picked for: surface type 2: 21.4% of cells; biome nether_wastes; ground netherrack; top netherrack; height 66; liquid cover 7% (layer 20%); bare rock 71% (layer 40%)
Census: sky 0%; near: netherrack 74.6%, gravel 14.6%; mid: netherrack 4.5%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-t2-nv.png`

### the_furnace_halls-surface-lm1

Picked for: surface landmark: ground soul_sand (type soul_soil); top soul_sand (type soul_soil); notable-block density (log) 3.7 (type 0.1); relief over 16 blocks 2.0 (type 79.0); relief over the patch 101.0 (type 126.0)
Census: sky 0%; near: soul_soil 78.7%, soul_sand 8.5%; mid: soul_sand 4%, soul_soil 3.5%; far: netherrack 1.2%; biomes: soul_sand_valley 98.8%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-lm1.png`

### the_furnace_halls-surface-lm1-nv

Picked for: surface landmark: ground soul_sand (type soul_soil); top soul_sand (type soul_soil); notable-block density (log) 3.7 (type 0.1); relief over 16 blocks 2.0 (type 79.0); relief over the patch 101.0 (type 126.0)
Census: sky 0%; near: soul_soil 78.7%, soul_sand 8.5%; mid: soul_sand 4%, soul_soil 3.5%; far: netherrack 1.2%; biomes: soul_sand_valley 98.8%
Image: `/tmp/claude-0/w10c2/reviews/the_furnace_halls/screens/the_furnace_halls-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
