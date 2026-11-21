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

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_nether/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_nether/layers`

## Summary

- Top blocks seen from the sky: netherrack 28.9%, lava 26.9%, nether_wart_block 7%, crimson_nylium 5.9%.
- Surface biomes: nether_wastes 29.6%, crimson_forest 25.9%, warped_forest 22.2%, basalt_deltas 9.3%, soul_sand_valley 9.2%.
- Ground blocks: netherrack 50.6%, crimson_nylium 13.6%, warped_nylium 12.2%, basalt 6.5%.
- Liquid cover 0% water, 26.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 12.6%; bare rock 34.1%; no ground (void) 0%.
- Trees: 116.5 per ha (crimson_stem 62.2/ha, mean spacing 2.9 blocks, warped_stem 54.3/ha, mean spacing 3 blocks); ground plants grass 3.5%, other 1.7%, mushroom 0.6%.
- Surface height p5 21, median 71, p95 115, max 127; median relief per patch 102 blocks; slopes under 15° 35.5%, over 45° 40.4%, cliffs 27.7%; above the clouds 0%, below sea level 25.3%.
- Underground: 25.3% open, 6.6% liquid; cave biomes warped_forest 37.7%, nether_wastes 33.2%, crimson_forest 16.7%, basalt_deltas 7.2%.
- Notable clusters, fewest first: dried_ghast 3 clusters, spacing 44.7; basalt 6 clusters, spacing 10.6; soul_fire 10 clusters, spacing 8.1; bone_block 19 clusters, spacing 3.6; brown_mushroom 37 clusters, spacing 2.8; red_mushroom 52 clusters, spacing 4.2.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 75%, biome match 100%.
- Landforms over the border (noise render): plains 33.3%, hills 21.8%, mountains 12.8%, plateaus 5.5%, basins 6.9%, islands 0.8%, water 18.9%, void 0%.
- Placeness: biome regions 1.4/km² (6 major, largest 31.6%), landform regions 15.3/km² (largest 8.2%), dominant place 31.6% of the border.

## Layers

- Columns by class: void 0%, roofed 96.8%, stacked 0%, overhang 0%, cave 0%, single 3.2%.
- Columns with each layer: sky 2.1%, surface 100%, underground 98.8%, roof 96.8%.
- Overhangs in 40% of columns, stacked layers in 2.1%, cave pockets in 34%.

## Places

- **surface** (243 cells, 6 types, silhouette 0.351):
  - type 1: 23.9% of cells; biome nether_wastes; ground netherrack; top netherrack; height 78; trees per cell 0.1 (layer 1.3); canopy 1% (layer 13%); ground plants 1% (layer 6%); liquid cover 5% (layer 27%); bare rock 77% (layer 34%); cave pockets 61% (layer 34%) — camera [40.5,85,168.5]
  - type 2: 23.5% of cells; biome nether_wastes; ground netherrack; top lava; height 32; trees per cell 0.1 (layer 1.3); canopy 2% (layer 13%); ground plants 1% (layer 6%); liquid cover 81% (layer 27%); bare rock 9% (layer 34%); overhangs 20% (layer 40%); cave pockets 5% (layer 34%) — camera [520.5,32,-343.5]
  - type 3: 14.8% of cells; biome warped_forest; ground warped_nylium; top warped_wart_block; height 78; trees per cell 3.6 (layer 1.3); canopy 34% (layer 13%); ground plants 19% (layer 6%); bare rock 11% (layer 34%); overhangs 65% (layer 40%); cave pockets 52% (layer 34%) — camera [-711.5,55,493.5]
  - type 4: 14.4% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 69; relief over 16 blocks 63.8 (layer 78.7); relief over the patch 83.9 (layer 102.6); trees per cell 5.0 (layer 1.3); canopy 45% (layer 13%); ground plants 16% (layer 6%); liquid cover 3% (layer 27%); bare rock 7% (layer 34%) — camera [-12.5,39,-839.5]
  - type 5: 12.8% of cells; biome crimson_forest; ground netherrack; top netherrack; height 86; neighbour step 9.1 (layer 5.3); relief over 16 blocks 100.5 (layer 78.7); relief over the patch 119.1 (layer 102.6); canopy 2% (layer 13%); ground plants 1% (layer 6%); liquid cover 7% (layer 27%); open to the sky 25% (layer 3%); overhangs 17% (layer 40%); cave pockets 16% (layer 34%); under a roof 75% (layer 97%) — camera [392.5,80,595.5]
  - type 6: 10.7% of cells; biome basalt_deltas; ground basalt; top basalt; height 65; neighbour step 7.9 (layer 5.3); relief over the patch 113.5 (layer 102.6); trees per cell 0.0 (layer 1.3); canopy 1% (layer 13%); ground plants 1% (layer 6%) — camera [-276.5,32,936.5]
  - landmark at [-920,-728]: biome crimson_forest (type soul_sand_valley); ground bedrock (type netherrack); top bedrock (type netherrack); notable-block density (log) 3.6 (type 0.1); open to the sky 48% (type 14%); under a roof 52% (type 86%)
  - landmark at [-888,904]: biome basalt_deltas (type soul_sand_valley); ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 86% (type 14%); under a roof 14% (type 86%); neighbour step 3.7 (type 10.8)
  - landmark at [-904,-712]: biome crimson_forest (type soul_sand_valley); ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 49% (type 14%); under a roof 51% (type 86%); bare rock 5% (type 57%)
  - landmark at [-904,904]: biome basalt_deltas (type soul_sand_valley); ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 51% (type 14%); under a roof 49% (type 86%); neighbour step 4.5 (type 10.8)
  - landmark at [-888,920]: biome basalt_deltas (type soul_sand_valley); ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 42% (type 14%); under a roof 58% (type 86%); neighbour step 5.5 (type 10.8)
- **sky** (6 cells, 2 types, silhouette 0.469):
  - type 1: 50.0% of cells; biome crimson_forest; ground crimson_nylium; height 96; layer present in 79% (layer 57%); trees per cell 8.0 (layer 4.0); notable-block density (log) 1.3 (layer 0.7) — camera [-841.5,50,-173.5]
  - type 2: 50.0% of cells; biome nether_wastes; ground netherrack; height 79; layer present in 36% (layer 57%); trees per cell 0.0 (layer 4.0); notable-block density (log) 0.1 (layer 0.7) — camera [841.5,49,232.5]
- **underground** (243 cells, 4 types, silhouette 0.194):
  - type 1: 28.8% of cells; notableId magma_block; biome crimson_forest; height 78; cave air 13% (layer 24%); cave liquid 2% (layer 7%) — camera [-913.5,55.88,-706.5]
  - type 2: 27.2% of cells; notableId blackstone; biome warped_forest; height 63; neighbour step 7.6 (layer 5.3); relief over 16 blocks 91.9 (layer 76.7); cave air 42% (layer 24%); cave liquid 11% (layer 7%) — camera [-855.5,30.88,-55.5]
  - type 3: 25.5% of cells; notableId blackstone; biome nether_wastes; height 79; neighbour step 3.1 (layer 5.3); relief over 16 blocks 58.0 (layer 76.7); cave pockets 74% (layer 34%) — camera [328.5,30.88,-951.5]
  - type 4: 18.5% of cells; notableId blackstone; biome crimson_forest; height 34; neighbour step 2.9 (layer 5.3); notable-block density (log) 0.6 (layer 1.2); cave air 10% (layer 24%); cave pockets 2% (layer 34%) — camera [-702.5,62.88,-478.5]
  - landmark at [-168,-312]: notableId lava (type magma_block); cave liquid 23% (type 0%); neighbour step 15.6 (type 4.8); notable-block density (log) 4.3 (type 1.9)
  - landmark at [-168,-328]: notableId weeping_vines_plant (type blackstone); biome crimson_forest (type warped_forest); layer present in 79% (type 100%); neighbour step 20.5 (type 8.5); cave liquid 20% (type 11%)
  - landmark at [776,40]: notableId lava (type blackstone); cave liquid 42% (type 11%); notable-block density (log) 4.3 (type 0.8); cave pockets 31% (type 89%)
- **roof** (242 cells, 2 types, silhouette 0.32):
  - type 1: 62.4% of cells; notableId nether_quartz_ore; height 106; neighbour step 0.8 (layer 1.6); relief over 16 blocks 18.0 (layer 32.6) — camera [296.5,99.88,-951.5]
  - type 2: 37.6% of cells; notableId nether_quartz_ore; height 87; neighbour step 3.1 (layer 1.6); relief over 16 blocks 56.8 (layer 32.6) — camera [-167.5,70.88,-294.5]
  - landmark at [392,616]: notableId soul_sand (type nether_quartz_ore); neighbour step 9.8 (type 3.0); notable-block density (log) 1.9 (type 0.3); relief over 16 blocks 102.0 (type 42.0)
  - landmark at [-920,-744]: notableId magma_block (type nether_quartz_ore); neighbour step 7.1 (type 3.0); relief over 16 blocks 100.0 (type 42.0); layer present in 79% (type 100%)
  - landmark at [-904,-712]: notableId crimson_nylium (type nether_quartz_ore); layer present in 51% (type 100%); trees per cell 3.0 (type 0.0); relief over 16 blocks 96.0 (type 42.0)
  - landmark at [-248,936]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 2.7 (type 0.3); height 66.3 (type 86.0); neighbour step 5.1 (type 3.0)
  - landmark at [-888,920]: notableId nether_gold_ore (type nether_quartz_ore); layer present in 58% (type 100%); relief over 16 blocks 90.0 (type 42.0); neighbour step 0.7 (type 3.0)

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:nether_wastes: expressed, 18808 columns; placement land 62%, lava 36%, roofed 98%, sky 2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 59.8%, lava 36%, nether_quartz_ore 0.6%
- minecraft:crimson_forest: expressed, 16836 columns; placement land 73.9%, lava 21.9%, roofed 92%, sky 4.2%; snow 0%, canopy 35.5%, trees 170.5/ha; top nether_wart_block 25.8%, lava 21.9%, crimson_nylium 21.8%
- minecraft:warped_forest: expressed, 13910 columns; placement land 72%, lava 27.4%, roofed 99.4%, sky 0.6%; snow 0%, canopy 34%, trees 130.8/ha; top lava 27.4%, warped_nylium 25.3%, warped_wart_block 23.5%
- minecraft:basalt_deltas: expressed, 5891 columns; placement land 65.2%, lava 32.9%, roofed 89.4%, sky 2%; snow 0%, canopy 0%, trees 0/ha; top basalt 42.4%, lava 32.9%, bedrock 8.6%
- minecraft:soul_sand_valley: expressed, 5745 columns; placement land 91.6%, lava 8.4%, roofed 94.4%; snow 0%, canopy 0%, trees 0/ha; top netherrack 32.3%, soul_sand 28.2%, soul_soil 22.8%
- adventure:quartz_flats: expressed, 2309 columns; placement land 96.7%, lava 3.1%, roofed 76.4%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 41.5%, bedrock 23.4%, quartz_block 22.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:nether_wastes | 29.6 | 18808 | expressed | - |
| V2 | minecraft:crimson_forest | 25.9 | 16836 | expressed | - |
| V3 | minecraft:warped_forest | 22.2 | 13910 | expressed | - |
| V4 | minecraft:basalt_deltas | 9.3 | 5891 | expressed | - |
| V5 | minecraft:soul_sand_valley | 9.2 | 5745 | expressed | - |
| V6 | adventure:quartz_flats | 3.7 | 2309 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:nether_wastes 29.6%, minecraft:crimson_forest 25.9%, minecraft:warped_forest 22.2% | type `the_nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 26.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 12.6% (`surface.canopyPercent`) | |
| M:relief | median 102.0 blocks a patch; height {"p5": 21.0, "p25": 29.0, "p50": 71.0, "p75": 92.0, "p95": 115.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 2.1, "surface": 100.0, "underground": 98.8, "roof": 96.8} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 96.8, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 3.2} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (96.8% of columns)

## Shots

### the_nether-surface-t2

Picked for: surface type 2: 23.5% of cells; biome nether_wastes; ground netherrack; top lava; height 32; trees per cell 0.1 (layer 1.3); canopy 2% (layer 13%); ground plants 1% (layer 6%); liquid cover 81% (layer 27%); bare rock 9% (layer 34%); overhangs 20% (layer 40%); cave pockets 5% (layer 34%)
Census: sky 0%; near: lava 51.6%; mid: netherrack 37.9%, lava 3.8%; far: netherrack 4.3%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10r/reviews/the_nether/screens/the_nether-surface-t2.png`

### the_nether-surface-t2-nv

Picked for: surface type 2: 23.5% of cells; biome nether_wastes; ground netherrack; top lava; height 32; trees per cell 0.1 (layer 1.3); canopy 2% (layer 13%); ground plants 1% (layer 6%); liquid cover 81% (layer 27%); bare rock 9% (layer 34%); overhangs 20% (layer 40%); cave pockets 5% (layer 34%)
Census: sky 0%; near: lava 51.6%; mid: netherrack 37.9%, lava 3.9%; far: netherrack 4.1%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10r/reviews/the_nether/screens/the_nether-surface-t2-nv.png`

### the_nether-roof-t1

Picked for: roof type 1: 62.4% of cells; notableId nether_quartz_ore; height 106; neighbour step 0.8 (layer 1.6); relief over 16 blocks 18.0 (layer 32.6)
Census: sky 0%; near: netherrack 90.5%; mid: netherrack 9%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10r/reviews/the_nether/screens/the_nether-roof-t1.png`

### the_nether-roof-t1-nv

Picked for: roof type 1: 62.4% of cells; notableId nether_quartz_ore; height 106; neighbour step 0.8 (layer 1.6); relief over 16 blocks 18.0 (layer 32.6)
Census: sky 0%; near: netherrack 90.5%; mid: netherrack 9%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10r/reviews/the_nether/screens/the_nether-roof-t1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
