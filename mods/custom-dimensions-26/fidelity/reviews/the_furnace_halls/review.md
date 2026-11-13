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

Dossier: `/tmp/claude-0/w10c2/runs/r3/full/the_furnace_halls/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/r3/full/the_furnace_halls/layers`

## Summary

- Top blocks seen from the sky: lava 19.9%, netherrack 19.2%, basalt 18.3%, soul_soil 7.9%.
- Surface biomes: basalt_deltas 31.8%, nether_wastes 24.8%, soul_sand_valley 22.2%, crimson_forest 11.1%, adventure:magma_land 10%.
- Ground blocks: netherrack 27.4%, basalt 23.1%, soul_soil 9.9%, soul_sand 9.3%.
- Liquid cover 0% water, 19.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 3.6%; bare rock 40%; no ground (void) 0%.
- Trees: 31.5 per ha (crimson_stem 31.5/ha, mean spacing 3.2 blocks); ground plants grass 1.2%, other 0.9%, mushroom 0.3%.
- Surface height p5 21, median 56, p95 127, max 127; median relief per patch 115 blocks; slopes under 15° 30.2%, over 45° 48.3%, cliffs 27.9%; above the clouds 0%, below sea level 20.9%.
- Underground: 19.2% open, 1.8% liquid; cave biomes soul_sand_valley 42.9%, basalt_deltas 24%, crimson_forest 13.5%, nether_wastes 12%.
- Notable clusters, fewest first: dried_ghast 2 clusters, spacing n/a; basalt 5 clusters, spacing 20.1; nether_bricks 5 clusters, spacing 28.5; warped_fungus 9 clusters, spacing 10.8; bone_block 17 clusters, spacing 2.2; soul_fire 29 clusters, spacing 5.3.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 72.7%, biome match 100%.
- Landforms over the border (noise render): plains 37%, hills 25.9%, mountains 8.4%, plateaus 7%, basins 9.4%, islands 0.3%, water 12%, void 0%.
- Placeness: biome regions 1.2/km² (5 major, largest 28.1%), landform regions 16.7/km² (largest 9.5%), dominant place 28.1% of the border.

## Layers

- Columns by class: void 0%, roofed 93.6%, stacked 0%, overhang 0%, cave 0%, single 6.4%.
- Columns with each layer: sky 1.6%, surface 100%, underground 99.3%, roof 93.6%.
- Overhangs in 29.3% of columns, stacked layers in 1.6%, cave pockets in 27.4%.

## Places

- **surface** (243 cells, 7 types, silhouette 0.393):
  - type 1: 30.9% of cells; biome basalt_deltas; ground basalt; top basalt; height 53; bare rock 66% (layer 40%) — camera [-663.5,35,679.5]
  - type 2: 21.4% of cells; biome nether_wastes; ground netherrack; top netherrack; height 66; liquid cover 7% (layer 20%); bare rock 71% (layer 40%) — camera [600.5,45,-616.5]
  - type 3: 16.9% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 63; notable-block density (log) 1.0 (layer 0.6); bare rock 4% (layer 40%) — camera [-903.5,61,-503.5]
  - type 4: 9.5% of cells; biome crimson_forest; ground crimson_nylium; top crimson_nylium; height 73; trees per cell 4.0 (layer 0.4); canopy 37% (layer 4%); ground plants 14% (layer 2%); liquid cover 3% (layer 20%); bare rock 8% (layer 40%); overhangs 44% (layer 29%); cave pockets 43% (layer 27%) — camera [24.5,48,92.5]
  - type 5: 8.6% of cells; biome nether_wastes; ground netherrack; top lava; height 26; neighbour step 2.9 (layer 5.5); relief over the patch 106.8 (layer 112.5); liquid cover 72% (layer 20%); bare rock 9% (layer 40%); overhangs 13% (layer 29%); cave pockets 3% (layer 27%) — camera [984.5,24,-615.5]
  - type 6: 6.6% of cells; biome nether_wastes; ground bedrock; top bedrock; height 109; neighbour step 9.4 (layer 5.5); relief over 16 blocks 110.7 (layer 80.8); relief over the patch 119.6 (layer 112.5); bare rock 21% (layer 40%); open to the sky 68% (layer 6%); overhangs 1% (layer 29%); cave pockets 2% (layer 27%); under a roof 32% (layer 94%) — camera [776.5,128,-199.5]
  - type 7: 6.2% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 60; relief over the patch 106.6 (layer 112.5); ground plants 8% (layer 2%); bare rock 8% (layer 40%) — camera [920.5,53,424.5]
  - landmark at [-200,-488]: ground soul_sand (type soul_soil); top soul_sand (type soul_soil); notable-block density (log) 3.7 (type 0.1); relief over 16 blocks 2.0 (type 79.0); relief over the patch 101.0 (type 126.0)
  - landmark at [296,840]: biome crimson_forest (type nether_wastes); stacked layers 16% (type 0%); relief over the patch 93.0 (type 116.0); canopy 23% (type 0%)
  - landmark at [-136,-456]: ground soul_sand (type soul_soil); top soul_sand (type soul_soil); stacked layers 33% (type 0%); notable-block density (log) 2.9 (type 0.1); neighbour step 8.4 (type 3.3)
  - landmark at [-216,-488]: top lava (type soul_soil); neighbour step 16.5 (type 3.3); notable-block density (log) 2.7 (type 0.1); relief over the patch 101.0 (type 126.0)
  - landmark at [-216,-504]: ground soul_sand (type soul_soil); top soul_sand (type soul_soil); stacked layers 16% (type 0%); relief over the patch 101.0 (type 126.0); neighbour step 9.0 (type 3.3)
- **sky** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome basalt_deltas; ground basalt; height 68 — camera [-868.5,58,296.5]
- **underground** (243 cells, 2 types, silhouette 0.214):
  - type 1: 56.8% of cells; notableId blackstone; biome basalt_deltas; height 51 — camera [-125.5,28.88,478.5]
  - type 2: 43.2% of cells; notableId magma_block; biome soul_sand_valley; height 74; notable-block density (log) 1.8 (layer 1.3) — camera [-458.5,46.88,-929.5]
- **roof** (236 cells, 3 types, silhouette 0.282):
  - type 1: 38.6% of cells; notableId blackstone; height 97; notable-block density (log) 1.2 (layer 0.8) — camera [-119.5,88.88,808.5]
  - type 2: 36.9% of cells; notableId nether_quartz_ore; height 104; neighbour step 0.8 (layer 2.2); relief over 16 blocks 19.9 (layer 40.5); notable-block density (log) 0.3 (layer 0.8) — camera [264.5,102.88,872.5]
  - type 3: 24.6% of cells; notableId nether_quartz_ore; height 70; layer present in 86% (layer 96%); neighbour step 5.1 (layer 2.2); relief over 16 blocks 85.4 (layer 40.5) — camera [616.5,48.88,-600.5]
  - landmark at [792,-168]: notableId lava (type nether_quartz_ore); layer present in 49% (type 100%); height 101.1 (type 69.5); neighbour step 3.5 (type 5.7)
  - landmark at [792,-184]: notableId lava (type nether_quartz_ore); relief over 16 blocks 96.0 (type 12.0); layer present in 72% (type 100%); neighbour step 1.7 (type 0.3)
  - landmark at [808,-200]: notableId lava (type nether_quartz_ore); layer present in 59% (type 100%); height 42.3 (type 69.5); notable-block density (log) 1.5 (type 0.6)
  - landmark at [584,-616]: notableId magma_block (type nether_quartz_ore); layer present in 34% (type 100%); height 40.1 (type 69.5); neighbour step 3.3 (type 5.7)
  - landmark at [-936,-168]: notableId magma_block (type nether_quartz_ore); layer present in 55% (type 100%); height 97.0 (type 69.5); neighbour step 3.5 (type 5.7)

## Biome expression

5 of 5 placed biomes are expressed.

- minecraft:basalt_deltas: expressed, 20389 columns; placement land 73.4%, lava 23.8%, roofed 94.4%, sky 2.8%; snow 0%, canopy 0%, trees 0/ha; top basalt 55.4%, lava 23.8%, blackstone 6.7%
- minecraft:nether_wastes: expressed, 15520 columns; placement land 78.6%, lava 21%, roofed 83.6%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top netherrack 56%, lava 21%, bedrock 16%
- minecraft:soul_sand_valley: expressed, 14045 columns; placement land 78.7%, lava 19.7%, roofed 98.2%, sky 1.6%; snow 0%, canopy 0%, trees 0/ha; top soul_soil 35.1%, soul_sand 29.9%, lava 19.7%
- minecraft:crimson_forest: expressed, 7016 columns; placement land 92.7%, lava 5.3%, roofed 94.9%, sky 1.9%; snow 0%, canopy 34.4%, trees 153.7/ha; top crimson_nylium 37.5%, nether_wart_block 31%, netherrack 9.7%
- adventure:magma_land: expressed, 6240 columns; placement land 82.2%, lava 17.8%, roofed 89.3%; snow 0%, canopy 0%, trees 0/ha; top magma_block 42.1%, lava 17.8%, netherrack 12.7%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:nether_wastes | minecraft:nether_wastes | 24.8 | 15520 | expressed | - |
| O2 | minecraft:basalt_deltas | minecraft:basalt_deltas | 31.8 | 20389 | expressed | - |
| O3 | minecraft:crimson_forest | minecraft:crimson_forest | 11.1 | 7016 | expressed | - |
| O4 | incendium:volcanic_deltas | adventure:magma_land | 10.0 | 6240 | expressed | - |
| O5 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 22.2 | 14045 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:basalt_deltas 31.8%, minecraft:nether_wastes 24.8%, minecraft:soul_sand_valley 22.2% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 19.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 3.6% (`surface.canopyPercent`) | |
| M:relief | median 115.0 blocks a patch; height {"p5": 21.0, "p25": 27.0, "p50": 56.0, "p75": 89.0, "p95": 127.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 1.6, "surface": 100.0, "underground": 99.3, "roof": 93.6} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 93.6, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 6.4} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (93.6% of columns)

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
