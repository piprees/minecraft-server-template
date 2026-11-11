# Review packet: the_nether

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_nether.json`

> The nether — piglin heartland, 1:8 compressed. Fortresses and bastions in reach, Incendium's sanctum near the rim. Every civilisation that touched this place left infrastructure; only the piglins called it home.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_nether.json`

> The nether — piglin heartland, 1:8 compressed. Fortresses and bastions in reach, Incendium's sanctum near the rim. Every civilisation that touched this place left infrastructure; only the piglins called it home.

Biomes: minecraft:nether_wastes, minecraft:crimson_forest, minecraft:warped_forest, minecraft:soul_sand_valley, minecraft:basalt_deltas, adventure:quartz_flats

Terrain: `{"scales": {"climate": 2.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fin/full/the_nether/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fin/full/the_nether/layers`

## Summary

- Top blocks seen from the sky: netherrack 30%, lava 17.3%, crimson_nylium 6.4%, bedrock 6.4%.
- Surface biomes: crimson_forest 29.7%, nether_wastes 25.9%, warped_forest 22.1%, soul_sand_valley 7.4%, basalt_deltas 7.4%.
- Ground blocks: netherrack 44.3%, crimson_nylium 13.2%, warped_nylium 12.7%, bedrock 7.5%.
- Liquid cover 0% water, 17.3% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 11.4%; bare rock 34.1%; no ground (void) 0%.
- Trees: 119.4 per ha (warped_stem 60.6/ha, mean spacing 3 blocks, crimson_stem 58.8/ha, mean spacing 3.2 blocks); ground plants grass 3.4%, other 1.8%, mushroom 0.5%.
- Surface height p5 21, median 85, p95 127, max 127; median relief per patch 113 blocks; slopes under 15° 31%, over 45° 44.1%, cliffs 31.8%; above the clouds 0%, below sea level 15.4%.
- Underground: 22.3% open, 6.6% liquid; cave biomes crimson_forest 34.6%, nether_wastes 25.5%, warped_forest 18.3%, soul_sand_valley 12.3%.
- Notable clusters, fewest first: basalt 10 clusters, spacing 14.2; soul_fire 10 clusters, spacing 5.2; brown_mushroom 12 clusters, spacing 11.1; bone_block 13 clusters, spacing 8; quartz_block 27 clusters, spacing 5.7; red_mushroom 43 clusters, spacing 5.4.
- 4 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 71%, biome match 100%.
- Landforms over the border (noise render): plains 33.3%, hills 21.8%, mountains 12.8%, plateaus 5.5%, basins 6.9%, islands 0.8%, water 18.9%, void 0%.
- Placeness: biome regions 2.6/km² (10 major, largest 28.0%), landform regions 15.3/km² (largest 8.2%), dominant place 28.0% of the border.

## Layers

- Columns by class: void 0%, roofed 93.6%, stacked 0%, overhang 0%, cave 0%, single 6.4%.
- Columns with each layer: sky 0.9%, surface 100%, underground 98.2%, roof 93.6%.
- Overhangs in 38.9% of columns, stacked layers in 0.9%, cave pockets in 40.4%.

## Places

- **surface** (243 cells, 8 types, silhouette 0.393):
  - type 1: 26.7% of cells; biome nether_wastes; ground netherrack; top netherrack; height 82; canopy 2% (layer 11%); bare rock 82% (layer 34%) — camera [56.5,113,297.5]
  - type 2: 15.6% of cells; biome warped_forest; ground warped_nylium; top warped_nylium; height 76; trees per cell 2.7 (layer 1.2); canopy 29% (layer 11%); ground plants 18% (layer 6%); bare rock 12% (layer 34%) — camera [-787.5,92,568.5]
  - type 3: 14.0% of cells; biome nether_wastes; ground netherrack; top lava; height 40; relief over 16 blocks 100.9 (layer 82.0); liquid cover 69% (layer 17%); bare rock 16% (layer 34%); cave pockets 10% (layer 40%) — camera [-183.5,32,120.5]
  - type 4: 13.6% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 79; trees per cell 4.4 (layer 1.2); canopy 38% (layer 11%); ground plants 14% (layer 6%); bare rock 12% (layer 34%); overhangs 59% (layer 39%) — camera [-440.5,86,297.5]
  - type 5: 10.3% of cells; biome crimson_forest; ground bedrock; top bedrock; height 100; neighbour step 10.4 (layer 6.2); relief over 16 blocks 105.9 (layer 82.0); relief over the patch 118.7 (layer 106.3); open to the sky 51% (layer 6%); overhangs 4% (layer 39%); cave pockets 5% (layer 40%); under a roof 49% (layer 94%) — camera [-535.5,128,-231.5]
  - type 6: 7.4% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 87; neighbour step 3.8 (layer 6.2); relief over 16 blocks 42.7 (layer 82.0); relief over the patch 78.5 (layer 106.3); trees per cell 0.0 (layer 1.2); canopy 0% (layer 11%); ground plants 0% (layer 6%); liquid cover 1% (layer 17%); bare rock 7% (layer 34%); cave pockets 66% (layer 40%) — camera [520.5,88,-102.5]
  - type 7: 7.0% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 78; neighbour step 4.0 (layer 6.2); relief over 16 blocks 64.5 (layer 82.0); relief over the patch 94.9 (layer 106.3); trees per cell 0.0 (layer 1.2); notable-block density (log) 0.8 (layer 0.4); canopy 0% (layer 11%); ground plants 0% (layer 6%); liquid cover 4% (layer 17%); bare rock 8% (layer 34%); cave pockets 66% (layer 40%) — camera [312.5,69,-919.5]
  - type 8: 5.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 75; trees per cell 0.0 (layer 1.2); canopy 0% (layer 11%); ground plants 0% (layer 6%); bare rock 58% (layer 34%); cave pockets 62% (layer 40%) — camera [392.5,66,-409.5]
  - landmark at [200,808]: biome crimson_forest (type nether_wastes); ground crimson_nylium (type netherrack); notable-block density (log) 3.2 (type 0.1); liquid cover 39% (type 82%); trees per cell 3.0 (type 0.0)
  - landmark at [-536,-216]: ground crimson_nylium (type bedrock); top lava (type bedrock); notable-block density (log) 1.5 (type 0.2); height 62.4 (type 102.5); open to the sky 26% (type 48%)
  - landmark at [-872,-40]: top lava (type nether_wart_block); notable-block density (log) 4.6 (type 0.3); neighbour step 16.1 (type 3.7); trees per cell 1.0 (type 6.0)
  - landmark at [952,-632]: top warped_wart_block (type warped_nylium); stacked layers 11% (type 0%); height 31.1 (type 74.8); cave pockets 3% (type 61%)
  - landmark at [-968,-248]: biome crimson_forest (type nether_wastes); notable-block density (log) 4.0 (type 0.1); trees per cell 3.0 (type 0.0); relief over 16 blocks 80.0 (type 115.0)
- **sky** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome nether_wastes; ground netherrack; height 89 — camera [-859.5,83,-1001.5]
- **underground** (243 cells, 8 types, silhouette 0.191):
  - type 1: 19.3% of cells; notableId blackstone; biome crimson_forest; height 77; neighbour step 8.5 (layer 6.1); relief over 16 blocks 93.1 (layer 78.9); notable-block density (log) 0.8 (layer 1.4); cave air 46% (layer 23%); cave liquid 11% (layer 7%) — camera [-874.5,30.88,-19.5]
  - type 2: 17.3% of cells; notableId blackstone; biome warped_forest; height 48; notable-block density (log) 0.6 (layer 1.4); cave pockets 21% (layer 41%) — camera [952.5,30.88,-407.5]
  - type 3: 15.2% of cells; notableId magma_block; biome nether_wastes; height 86; neighbour step 3.5 (layer 6.1); relief over 16 blocks 51.4 (layer 78.9); cave liquid 2% (layer 7%) — camera [-284.5,87.88,-812.5]
  - type 4: 13.2% of cells; notableId magma_block; biome crimson_forest; height 97; neighbour step 9.8 (layer 6.1); relief over 16 blocks 106.7 (layer 78.9); cave air 1% (layer 23%); cave liquid 0% (layer 7%); cave pockets 9% (layer 41%) — camera [696.5,86.88,842.5]
  - type 5: 11.9% of cells; notableId magma_block; biome warped_forest; height 83; trees per cell 5.5 (layer 1.1); cave pockets 65% (layer 41%) — camera [-702.5,62.88,945.5]
  - type 6: 8.2% of cells; notableId lava; biome nether_wastes; height 74; layer present in 95% (layer 98%); notable-block density (log) 3.1 (layer 1.4); cave liquid 14% (layer 7%) — camera [73.5,56.88,294.5]
  - type 7: 8.2% of cells; notableId nether_quartz_ore; biome adventure:quartz_flats; height 86; neighbour step 3.5 (layer 6.1); relief over 16 blocks 37.1 (layer 78.9); cave liquid 12% (layer 7%); cave pockets 83% (layer 41%) — camera [21.5,85.88,-263.5]
  - type 8: 6.6% of cells; notableId soul_sand; biome soul_sand_valley; height 80; neighbour step 3.1 (layer 6.1); relief over 16 blocks 64.8 (layer 78.9); cave air 36% (layer 23%); cave pockets 69% (layer 41%) — camera [328.5,30.88,-919.5]
  - landmark at [-184,-760]: notableId nether_gold_ore (type blackstone); biome nether_wastes (type crimson_forest); layer present in 58% (type 100%); cave liquid 27% (type 10%); neighbour step 18.0 (type 10.2)
  - landmark at [-184,-776]: cave liquid 35% (type 8%); layer present in 80% (type 100%); height 28.8 (type 73.8)
  - landmark at [-200,88]: notableId nether_gold_ore (type blackstone); biome nether_wastes (type crimson_forest); layer present in 66% (type 100%); cave pockets 1% (type 41%); neighbour step 14.6 (type 10.2)
  - landmark at [-456,312]: biome crimson_forest (type nether_wastes); cave liquid 25% (type 8%); cave air 49% (type 24%); notable-block density (log) 4.8 (type 3.6)
  - landmark at [-1000,-248]: notableId crimson_nylium (type nether_quartz_ore); biome crimson_forest (type adventure:quartz_flats); trees per cell 8.0 (type 0.0); cave air 26% (type 6%); notable-block density (log) 2.1 (type 1.3)
- **roof** (240 cells, 2 types, silhouette 0.339):
  - type 1: 62.9% of cells; notableId nether_quartz_ore; height 106; neighbour step 1.2 (layer 2.4); relief over 16 blocks 22.9 (layer 41.7) — camera [-215.5,103.88,120.5]
  - type 2: 37.1% of cells; notableId nether_quartz_ore; height 87; layer present in 86% (layer 95%); neighbour step 4.4 (layer 2.4); relief over 16 blocks 73.6 (layer 41.7); trees per cell 1.4 (layer 0.7) — camera [920.5,82.88,-620.5]
  - landmark at [184,808]: notableId magma_block (type nether_quartz_ore); layer present in 29% (type 100%); height 39.1 (type 93.6); notable-block density (log) 1.7 (type 0.4)
  - landmark at [712,840]: notableId magma_block (type nether_quartz_ore); layer present in 50% (type 100%); height 52.6 (type 93.6); notable-block density (log) 1.5 (type 0.4)
  - landmark at [968,296]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 3.7 (type 0.4); neighbour step 15.4 (type 3.5); layer present in 46% (type 100%)
  - landmark at [168,824]: notableId crimson_nylium (type nether_quartz_ore); layer present in 43% (type 100%); height 59.1 (type 93.6); notable-block density (log) 1.3 (type 0.4)
  - landmark at [-568,-232]: notableId magma_block (type nether_quartz_ore); neighbour step 10.7 (type 3.5); layer present in 67% (type 100%); height 72.1 (type 93.6)

## Biome expression

4 of 6 placed biomes are expressed.

- minecraft:crimson_forest: expressed, 18628 columns; placement land 81.2%, lava 18%, roofed 86.4%, sky 0.8%; snow 0%, canopy 26%, trees 114.4/ha; top crimson_nylium 21.5%, netherrack 20.5%, nether_wart_block 20.2%
- minecraft:nether_wastes: placed, not expressed, 16401 columns; placement land 74.8%, lava 23.5%, roofed 97.2%, sky 1.7%; snow 0%, canopy 0%, trees 0/ha; top netherrack 70.1%, lava 23.5%, bedrock 1.1%; **mostly bare rock**
- minecraft:warped_forest: expressed, 13883 columns; placement land 83.9%, lava 15.4%, roofed 96.1%, sky 0.8%; snow 0%, canopy 27%, trees 104.8/ha; top warped_nylium 28%, warped_wart_block 21.4%, netherrack 19.5%
- adventure:quartz_flats: expressed, 4630 columns; placement land 98.7%, lava 0.8%, roofed 99.5%, sky 0.5%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 75.3%, glowstone 11.6%, netherrack 6.6%
- minecraft:soul_sand_valley: expressed, 4611 columns; placement land 92.2%, lava 7.8%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 49.2%, soul_soil 33.1%, lava 7.8%
- minecraft:basalt_deltas: placed, not expressed, 4610 columns; placement land 78.3%, lava 21.6%, roofed 79.4%; snow 0%, canopy 0%, trees 0/ha; top basalt 34.4%, lava 21.6%, bedrock 20.6%; **mostly bare rock**


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:crimson_forest | 29.7 | 18628 | expressed | - |
| V2 | minecraft:nether_wastes | 25.9 | 16401 | placed, not expressed | mostly bare rock |
| V3 | minecraft:warped_forest | 22.1 | 13883 | expressed | - |
| V4 | adventure:quartz_flats | 7.4 | 4630 | expressed | - |
| V5 | minecraft:soul_sand_valley | 7.4 | 4611 | expressed | - |
| V6 | minecraft:basalt_deltas | 7.4 | 4610 | placed, not expressed | mostly bare rock |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:crimson_forest 29.7%, minecraft:nether_wastes 25.9%, minecraft:warped_forest 22.1% | type `the_nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 17.3% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 11.4% (`surface.canopyPercent`) | |
| M:relief | median 113.0 blocks a patch; height {"p5": 21.0, "p25": 54.0, "p50": 85.0, "p75": 99.0, "p95": 127.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.9, "surface": 100.0, "underground": 98.2, "roof": 93.6} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 93.6, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 6.4} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (93.6% of columns)

## Shots

### the_nether-surface-t1

Picked for: surface type 1: 26.7% of cells; biome nether_wastes; ground netherrack; top netherrack; height 82; canopy 2% (layer 11%); bare rock 82% (layer 34%)
Census: sky 0%; near: netherrack 97.3%, nether_gold_ore 1.6%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-t1.png`

### the_nether-surface-t1-nv

Picked for: surface type 1: 26.7% of cells; biome nether_wastes; ground netherrack; top netherrack; height 82; canopy 2% (layer 11%); bare rock 82% (layer 34%)
Census: sky 0%; near: netherrack 97.3%, nether_gold_ore 1.6%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-t1-nv.png`

### the_nether-sky-t1

Picked for: sky type 1: 100.0% of cells; biome nether_wastes; ground netherrack; height 89
Census: sky 0%; near: crimson_nylium 31.2%, crimson_roots 26%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-sky-t1.png`

### the_nether-sky-t1-nv

Picked for: sky type 1: 100.0% of cells; biome nether_wastes; ground netherrack; height 89
Census: sky 0%; near: crimson_nylium 31.2%, crimson_roots 26%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-sky-t1-nv.png`

### the_nether-roof-t1

Picked for: roof type 1: 62.9% of cells; notableId nether_quartz_ore; height 106; neighbour step 1.2 (layer 2.4); relief over 16 blocks 22.9 (layer 41.7)
Census: sky 0%; near: netherrack 80.5%, lava 1.6%; mid: netherrack 12.2%; far: netherrack 3.2%; biomes: nether_wastes 98.2%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-roof-t1.png`

### the_nether-roof-t1-nv

Picked for: roof type 1: 62.9% of cells; notableId nether_quartz_ore; height 106; neighbour step 1.2 (layer 2.4); relief over 16 blocks 22.9 (layer 41.7)
Census: sky 0%; near: netherrack 80.5%, lava 1.6%; mid: netherrack 12.2%; far: netherrack 3.2%; biomes: nether_wastes 98.2%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-roof-t1-nv.png`

### the_nether-surface-t2

Picked for: surface type 2: 15.6% of cells; biome warped_forest; ground warped_nylium; top warped_nylium; height 76; trees per cell 2.7 (layer 1.2); canopy 29% (layer 11%); ground plants 18% (layer 6%); bare rock 12% (layer 34%)
Census: sky 0%; near: warped_wart_block 36.7%, warped_stem 19.4%; mid: netherrack 10.7%, warped_wart_block 3.1%; biomes: warped_forest 99.2%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-t2.png`

### the_nether-surface-t2-nv

Picked for: surface type 2: 15.6% of cells; biome warped_forest; ground warped_nylium; top warped_nylium; height 76; trees per cell 2.7 (layer 1.2); canopy 29% (layer 11%); ground plants 18% (layer 6%); bare rock 12% (layer 34%)
Census: sky 0%; near: warped_wart_block 36.7%, warped_stem 19.4%; mid: netherrack 10.7%, warped_wart_block 3.1%; biomes: warped_forest 99.2%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-t2-nv.png`

### the_nether-surface-lm1

Picked for: surface landmark: biome crimson_forest (type nether_wastes); ground crimson_nylium (type netherrack); notable-block density (log) 3.2 (type 0.1); liquid cover 39% (type 82%); trees per cell 3.0 (type 0.0)
Census: sky 0%; near: netherrack 55.4%, lava 41.4%; mid: netherrack 1.2%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-lm1.png`

### the_nether-surface-lm1-nv

Picked for: surface landmark: biome crimson_forest (type nether_wastes); ground crimson_nylium (type netherrack); notable-block density (log) 3.2 (type 0.1); liquid cover 39% (type 82%); trees per cell 3.0 (type 0.0)
Census: sky 0%; near: netherrack 55.4%, lava 41.4%; mid: netherrack 1.2%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c1/reviews/the_nether/screens/the_nether-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
