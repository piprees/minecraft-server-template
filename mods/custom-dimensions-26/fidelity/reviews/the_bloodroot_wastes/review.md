# Review packet: the_bloodroot_wastes

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_bloodroot_wastes.json`

> Crimson root-country — the piglins who survived the war retreated here. Forges and fungus in the red haze, a shadow of their former industry. They kept working, but the grandeur is gone.

Biomes: minecraft:crimson_forest, incendium:weeping_valley, incendium:inverted_forest, minecraft:nether_wastes, minecraft:basalt_deltas, regions_unexplored:infernal_holt

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_bloodroot_wastes.json`

> Crimson root-country — the piglins who survived the war retreated here. Forges and fungus in the red haze, a shadow of their former industry. They kept working, but the grandeur is gone.

Biomes: minecraft:crimson_forest, adventure:nether_jungle, adventure:upside_down_forest, adventure:crimson_pinewood, minecraft:nether_wastes, minecraft:basalt_deltas

Terrain: `{"scales": {"climate": 1.8}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/r3/full/the_bloodroot_wastes/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/r3/full/the_bloodroot_wastes/layers`

## Summary

- Top blocks seen from the sky: nether_wart_block 29.3%, crimson_nylium 19.8%, netherrack 17.5%, lava 14.9%.
- Surface biomes: crimson_forest 55.6%, adventure:nether_jungle 25.9%, adventure:upside_down_forest 7.4%, nether_wastes 4.4%, adventure:crimson_pinewood 3.7%.
- Ground blocks: netherrack 42.4%, crimson_nylium 42.2%, warped_nylium 4.4%, shroomlight 4%.
- Liquid cover 0% water, 14.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 33.3%; bare rock 28.1%; no ground (void) 0%.
- Trees: 222.8 per ha (crimson_stem 193.5/ha, mean spacing 3 blocks, stripped_crimson_stem 24.6/ha, mean spacing 5.9 blocks, stripped_warped_stem 4.7/ha, mean spacing 12.8 blocks); ground plants grass 5.6%, other 2.3%, mushroom 0.7%.
- Surface height p5 21, median 75, p95 109, max 127; median relief per patch 97 blocks; slopes under 15° 31.3%, over 45° 46%, cliffs 30.4%; above the clouds 0%, below sea level 16.6%.
- Underground: 22.3% open, 6.5% liquid; cave biomes crimson_forest 55.6%, adventure:nether_jungle 25.9%, adventure:upside_down_forest 7.1%, adventure:crimson_pinewood 5.4%.
- Notable clusters, fewest first: red_mushroom 11 clusters, spacing 3.4; glowstone 47 clusters, spacing 15.9; warped_fungus 61 clusters, spacing 11.8; twisting_vines_plant 173 clusters, spacing 4.2; fire 175 clusters, spacing 4.4; twisting_vines 183 clusters, spacing 3.9.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 76.7%, biome match 100%.
- Landforms over the border (noise render): plains 33.1%, hills 23.1%, mountains 7.7%, plateaus 6.6%, basins 7.7%, islands 0.8%, water 21%, void 0%.
- Placeness: biome regions 1.7/km² (6 major, largest 54.5%), landform regions 14.5/km² (largest 10.8%), dominant place 54.5% of the border.

## Layers

- Columns by class: void 0%, roofed 98.2%, stacked 0%, overhang 0%, cave 0%, single 1.8%.
- Columns with each layer: sky 4.2%, surface 100%, underground 99.3%, roof 98.2%.
- Overhangs in 44.6% of columns, stacked layers in 4.2%, cave pockets in 43.2%.

## Places

- **surface** (243 cells, 7 types, silhouette 0.302):
  - type 1: 27.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 75; neighbour step 3.5 (layer 5.5); relief over 16 blocks 61.2 (layer 75.9); trees per cell 5.8 (layer 2.7); canopy 48% (layer 33%); ground plants 16% (layer 9%); liquid cover 3% (layer 15%); bare rock 9% (layer 28%) — camera [295.5,91,389.5]
  - type 2: 17.7% of cells; biome crimson_forest; ground crimson_nylium; top crimson_nylium; height 80; ground plants 13% (layer 9%); bare rock 15% (layer 28%); cave pockets 62% (layer 43%) — camera [-199.5,85,328.5]
  - type 3: 17.3% of cells; biome adventure:nether_jungle; ground netherrack; top netherrack; height 75; neighbour step 8.1 (layer 5.5); relief over 16 blocks 89.6 (layer 75.9); relief over the patch 113.2 (layer 102.2); trees per cell 1.1 (layer 2.7); canopy 15% (layer 33%); ground plants 5% (layer 9%); bare rock 54% (layer 28%) — camera [983.5,72,-713.5]
  - type 4: 14.8% of cells; biome crimson_forest; ground netherrack; top lava; height 40; relief over 16 blocks 91.2 (layer 75.9); trees per cell 0.5 (layer 2.7); canopy 8% (layer 33%); ground plants 2% (layer 9%); liquid cover 65% (layer 15%); overhangs 22% (layer 45%); cave pockets 13% (layer 43%) — camera [296.5,32,-343.5]
  - type 5: 12.3% of cells; biome adventure:nether_jungle; ground netherrack; top nether_wart_block; height 78; relief over 16 blocks 55.6 (layer 75.9); relief over the patch 89.3 (layer 102.2); canopy 51% (layer 33%); liquid cover 2% (layer 15%); overhangs 64% (layer 45%) — camera [-131.5,91,-662.5]
  - type 6: 7.4% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 56; trees per cell 0.6 (layer 2.7); notable-block density (log) 1.1 (layer 0.6); canopy 79% (layer 33%); ground plants 0% (layer 9%); bare rock 65% (layer 28%); stacked layers 25% (layer 4%); cave pockets 25% (layer 43%) — camera [584.5,69,360.5]
  - type 7: 3.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 77; relief over the patch 118.0 (layer 102.2); trees per cell 0.0 (layer 2.7); notable-block density (log) 0.2 (layer 0.6); canopy 0% (layer 33%); ground plants 0% (layer 9%); bare rock 61% (layer 28%); open to the sky 15% (layer 2%); cave pockets 11% (layer 43%); under a roof 85% (layer 98%) — camera [-805.5,52,904.5]
  - landmark at [-840,872]: biome nether_wastes (type adventure:nether_jungle); top bedrock (type netherrack); open to the sky 38% (type 1%); under a roof 63% (type 99%); neighbour step 11.1 (type 5.8)
  - landmark at [-840,-280]: ground bedrock (type crimson_nylium); top bedrock (type crimson_nylium); open to the sky 44% (type 0%); under a roof 56% (type 100%); cave pockets 1% (type 64%)
  - landmark at [-824,872]: ground bedrock (type basalt); top bedrock (type basalt); open to the sky 87% (type 0%); under a roof 13% (type 100%); height 121.3 (type 65.1)
  - landmark at [-824,-280]: ground bedrock (type crimson_nylium); top bedrock (type crimson_nylium); open to the sky 55% (type 0%); under a roof 45% (type 100%); cave pockets 21% (type 64%)
  - landmark at [1000,-744]: biome adventure:nether_jungle (type crimson_forest); neighbour step 18.3 (type 6.9); relief over the patch 126.0 (type 96.0); liquid cover 41% (type 81%)
- **sky** (16 cells, 3 types, silhouette 0.482):
  - type 1: 56.3% of cells; biome adventure:upside_down_forest; ground shroomlight; height 95; trees per cell 7.1 (layer 5.1) — camera [619.5,95,340.5]
  - type 2: 25.0% of cells; biome crimson_forest; ground crimson_nylium; height 70; layer present in 43% (layer 37%); trees per cell 3.0 (layer 5.1); island layers above 1.0 (layer 1.0) — camera [262.5,76,-375.5]
  - type 3: 18.8% of cells; biome adventure:nether_jungle; ground netherrack; height 77; layer present in 46% (layer 37%); neighbour step 6.6 (layer 3.3); trees per cell 2.0 (layer 5.1) — camera [-776.5,61,-709.5]
- **underground** (243 cells, 6 types, silhouette 0.199):
  - type 1: 24.3% of cells; notableId blackstone; biome crimson_forest; height 48; relief over 16 blocks 88.1 (layer 75.1); trees per cell 0.3 (layer 1.6); notable-block density (log) 0.8 (layer 1.4); cave pockets 13% (layer 43%) — camera [599.5,30.88,-123.5]
  - type 2: 21.0% of cells; notableId magma_block; biome crimson_forest; height 75; trees per cell 3.6 (layer 1.6); cave air 14% (layer 22%); cave liquid 3% (layer 7%) — camera [638.5,68.88,-122.5]
  - type 3: 20.2% of cells; notableId blackstone; biome crimson_forest; height 80; notable-block density (log) 0.8 (layer 1.4); cave air 31% (layer 22%); cave liquid 11% (layer 7%); cave pockets 73% (layer 43%) — camera [-179.5,30.88,920.5]
  - type 4: 19.8% of cells; notableId magma_block; biome adventure:nether_jungle; height 82; cave air 14% (layer 22%); cave liquid 2% (layer 7%) — camera [-136.5,100.88,-667.5]
  - type 5: 10.7% of cells; notableId lava; biome crimson_forest; height 77; notable-block density (log) 3.3 (layer 1.4); cave liquid 12% (layer 7%); cave pockets 68% (layer 43%) — camera [-193.5,34.88,913.5]
  - type 6: 4.1% of cells; notableId weeping_vines_plant; biome adventure:upside_down_forest; height 55; neighbour step 13.3 (layer 5.5); relief over 16 blocks 93.1 (layer 75.1); trees per cell 0.0 (layer 1.6); notable-block density (log) 0.9 (layer 1.4); cave air 48% (layer 22%); cave liquid 10% (layer 7%); cave pockets 11% (layer 43%) — camera [344.5,30.88,856.5]
  - landmark at [392,-168]: notableId nether_quartz_ore (type blackstone); relief over 16 blocks 16.0 (type 91.0); cave air 0% (type 26%); neighbour step 0.4 (type 5.0)
  - landmark at [-264,-136]: notableId magma_block (type blackstone); layer present in 91% (type 100%); cave liquid 18% (type 7%); cave air 3% (type 26%)
  - landmark at [1000,-744]: biome adventure:nether_jungle (type crimson_forest); neighbour step 19.3 (type 5.0); layer present in 87% (type 100%); cave air 2% (type 26%)
- **roof** (242 cells, 3 types, silhouette 0.335):
  - type 1: 41.7% of cells; notableId nether_quartz_ore; height 103; notable-block density (log) 0.2 (layer 0.5) — camera [632.5,96.88,-151.5]
  - type 2: 37.2% of cells; notableId weeping_vines_plant; height 106; notable-block density (log) 0.7 (layer 0.5) — camera [-695.5,98.88,-967.5]
  - type 3: 21.1% of cells; notableId nether_quartz_ore; height 86; layer present in 93% (layer 99%); neighbour step 4.5 (layer 1.7); relief over 16 blocks 73.0 (layer 30.2); trees per cell 1.6 (layer 0.7) — camera [-214.5,95.88,935.5]
  - landmark at [1000,-744]: notableId magma_block (type nether_quartz_ore); neighbour step 10.8 (type 4.6); height 55.5 (type 84.0); notable-block density (log) 1.1 (type 0.4)
  - landmark at [-808,872]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 1.3 (type 0.4); layer present in 85% (type 100%); height 101.8 (type 84.0)
  - landmark at [-968,-952]: notableId magma_block (type nether_quartz_ore); layer present in 79% (type 100%); neighbour step 8.3 (type 4.6); notable-block density (log) 1.0 (type 0.4)
  - landmark at [-840,888]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 2.0 (type 0.4); neighbour step 0.2 (type 4.6); layer present in 88% (type 100%)
  - landmark at [1000,-728]: notableId glowstone (type nether_quartz_ore); neighbour step 9.6 (type 4.6); layer present in 92% (type 100%); height 94.2 (type 84.0)

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:crimson_forest: expressed, 35359 columns; placement land 81.3%, lava 16.5%, roofed 96.2%, sky 2.3%; snow 0%, canopy 38%, trees 177.1/ha; top nether_wart_block 30.1%, crimson_nylium 28.8%, lava 16.5%
- adventure:nether_jungle: expressed, 16771 columns; placement land 84.6%, lava 11.6%, roofed 95.2%, sky 3.8%; snow 0%, canopy 38.8%, trees 92.3/ha; top netherrack 22.3%, nether_wart_block 19.7%, lava 11.6%
- adventure:upside_down_forest: expressed, 5783 columns; placement land 71.8%, lava 7.8%, roofed 79.7%, sky 20.3%; snow 0%, canopy 87.3%, trees 363.4/ha; top nether_wart_block 62.7%, netherrack 8.9%, lava 7.8%
- minecraft:nether_wastes: expressed, 2741 columns; placement land 85.6%, lava 14.3%, roofed 91.1%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 73.5%, lava 14.3%, bedrock 8.8%
- adventure:crimson_pinewood: expressed, 2313 columns; placement land 83.3%, lava 16.3%, roofed 99.6%, sky 0.4%; snow 0%, canopy 35.7%, trees 119.4/ha; top crimson_nylium 39.3%, nether_wart_block 29.7%, lava 16.3%
- minecraft:basalt_deltas: expressed, 1877 columns; placement land 83.7%, lava 16%, roofed 89.7%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top basalt 56.4%, lava 16%, bedrock 10%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:crimson_forest | minecraft:crimson_forest | 55.6 | 35359 | expressed | - |
| O2 | incendium:weeping_valley | adventure:nether_jungle | 25.9 | 16771 | expressed | - |
| O3 | incendium:inverted_forest | adventure:upside_down_forest | 7.4 | 5783 | expressed | - |
| O4 | minecraft:nether_wastes | minecraft:nether_wastes | 4.4 | 2741 | expressed | - |
| O5 | minecraft:basalt_deltas | minecraft:basalt_deltas | 3.0 | 1877 | expressed | - |
| O6 | regions_unexplored:infernal_holt | minecraft:crimson_forest | 55.6 | 35359 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:crimson_pinewood | 3.7 | 2313 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:crimson_forest 55.6%, adventure:nether_jungle 25.9%, adventure:upside_down_forest 7.4% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 14.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 33.3% (`surface.canopyPercent`) | |
| M:relief | median 97.0 blocks a patch; height {"p5": 21.0, "p25": 52.0, "p50": 75.0, "p75": 90.0, "p95": 109.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 4.2, "surface": 100.0, "underground": 99.3, "roof": 98.2} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#5B1B1B"}` |
| M:classes | {"void": 0.0, "roofed": 98.2, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 1.8} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 96.3%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (98.2% of columns)

## Shots

### the_bloodroot_wastes-surface-t1

Picked for: surface type 1: 27.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 75; neighbour step 3.5 (layer 5.5); relief over 16 blocks 61.2 (layer 75.9); trees per cell 5.8 (layer 2.7); canopy 48% (layer 33%); ground plants 16% (layer 9%); liquid cover 3% (layer 15%); bare rock 9% (layer 28%)
Census: sky 0%; near: weeping_vines_plant 22.9%, crimson_nylium 20.2%; mid: nether_wart_block 6.1%, netherrack 6%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t1.png`

### the_bloodroot_wastes-surface-t1-nv

Picked for: surface type 1: 27.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 75; neighbour step 3.5 (layer 5.5); relief over 16 blocks 61.2 (layer 75.9); trees per cell 5.8 (layer 2.7); canopy 48% (layer 33%); ground plants 16% (layer 9%); liquid cover 3% (layer 15%); bare rock 9% (layer 28%)
Census: sky 0%; near: weeping_vines_plant 22.9%, crimson_nylium 20.2%; mid: nether_wart_block 6.1%, netherrack 6%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t1-nv.png`

### the_bloodroot_wastes-underground-t4

Picked for: underground type 4: 19.8% of cells; notableId magma_block; biome adventure:nether_jungle; height 82; cave air 14% (layer 22%); cave liquid 2% (layer 7%)
Census: sky 0%; near: weeping_vines_plant 49.6%, stripped_crimson_stem 20.7%; biomes: adventure:nether_jungle 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-underground-t4.png`

### the_bloodroot_wastes-underground-t4-nv

Picked for: underground type 4: 19.8% of cells; notableId magma_block; biome adventure:nether_jungle; height 82; cave air 14% (layer 22%); cave liquid 2% (layer 7%)
Census: sky 0%; near: weeping_vines_plant 49.6%, stripped_crimson_stem 20.7%; biomes: adventure:nether_jungle 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-underground-t4-nv.png`

### the_bloodroot_wastes-roof-t2

Picked for: roof type 2: 37.2% of cells; notableId weeping_vines_plant; height 106; notable-block density (log) 0.7 (layer 0.5)
Census: sky 0%; near: weeping_vines_plant 41.4%, netherrack 15.4%; mid: weeping_vines_plant 1%; biomes: adventure:nether_jungle 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-roof-t2.png`

### the_bloodroot_wastes-roof-t2-nv

Picked for: roof type 2: 37.2% of cells; notableId weeping_vines_plant; height 106; notable-block density (log) 0.7 (layer 0.5)
Census: sky 0%; near: weeping_vines_plant 41.4%, netherrack 15.4%; mid: weeping_vines_plant 1%; biomes: adventure:nether_jungle 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-roof-t2-nv.png`

### the_bloodroot_wastes-surface-t4

Picked for: surface type 4: 14.8% of cells; biome crimson_forest; ground netherrack; top lava; height 40; relief over 16 blocks 91.2 (layer 75.9); trees per cell 0.5 (layer 2.7); canopy 8% (layer 33%); ground plants 2% (layer 9%); liquid cover 65% (layer 15%); overhangs 22% (layer 45%); cave pockets 13% (layer 43%)
Census: sky 0%; near: lava 51.6%; mid: netherrack 14.1%, crimson_nylium 7.2%; far: netherrack 5.3%, nether_wart_block 4.1%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t4.png`

### the_bloodroot_wastes-surface-t4-nv

Picked for: surface type 4: 14.8% of cells; biome crimson_forest; ground netherrack; top lava; height 40; relief over 16 blocks 91.2 (layer 75.9); trees per cell 0.5 (layer 2.7); canopy 8% (layer 33%); ground plants 2% (layer 9%); liquid cover 65% (layer 15%); overhangs 22% (layer 45%); cave pockets 13% (layer 43%)
Census: sky 0%; near: lava 51.6%; mid: netherrack 14.1%, nether_wart_block 6.9%; far: netherrack 5.3%, nether_wart_block 4%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t4-nv.png`

### the_bloodroot_wastes-surface-t2

Picked for: surface type 2: 17.7% of cells; biome crimson_forest; ground crimson_nylium; top crimson_nylium; height 80; ground plants 13% (layer 9%); bare rock 15% (layer 28%); cave pockets 62% (layer 43%)
Census: sky 0%; near: netherrack 36.1%, nether_wart_block 20.1%; mid: netherrack 12.9%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t2.png`

### the_bloodroot_wastes-surface-t2-nv

Picked for: surface type 2: 17.7% of cells; biome crimson_forest; ground crimson_nylium; top crimson_nylium; height 80; ground plants 13% (layer 9%); bare rock 15% (layer 28%); cave pockets 62% (layer 43%)
Census: sky 0%; near: netherrack 36.1%, nether_wart_block 20.1%; mid: netherrack 12.9%; biomes: crimson_forest 100%
Image: `/tmp/claude-0/w10c2/reviews/the_bloodroot_wastes/screens/the_bloodroot_wastes-surface-t2-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
