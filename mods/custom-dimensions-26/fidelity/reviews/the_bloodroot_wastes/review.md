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

Dossier: `/tmp/claude-0/w10c2/runs/c1/full/the_bloodroot_wastes/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c1/full/the_bloodroot_wastes/layers`

## Summary

- Top blocks seen from the sky: nether_wart_block 26.9%, netherrack 21%, crimson_nylium 18.5%, lava 15.7%.
- Surface biomes: crimson_forest 57.6%, adventure:nether_jungle 22.2%, adventure:upside_down_forest 9.1%, nether_wastes 4.4%, adventure:crimson_pinewood 3.7%.
- Ground blocks: netherrack 46.7%, crimson_nylium 38%, warped_nylium 3.7%, shroomlight 3.2%.
- Liquid cover 0% water, 15.7% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 29.6%; bare rock 32.2%; no ground (void) 0%.
- Trees: 213.3 per ha (crimson_stem 198.5/ha, mean spacing 2.9 blocks, stripped_crimson_stem 11.3/ha, mean spacing 7 blocks, stripped_warped_stem 3.5/ha, mean spacing 13.1 blocks); ground plants grass 4.9%, other 2.1%, mushroom 0.8%.
- Surface height p5 21, median 75, p95 112, max 127; median relief per patch 111 blocks; slopes under 15° 35%, over 45° 43.1%, cliffs 29%; above the clouds 0%, below sea level 21.6%.
- Underground: 22.2% open, 3.6% liquid; cave biomes crimson_forest 62.1%, adventure:nether_jungle 17.9%, adventure:upside_down_forest 7.1%, adventure:crimson_pinewood 7%.
- Notable clusters, fewest first: basalt 3 clusters, spacing 1.3; soul_sand 5 clusters, spacing 10.6; red_mushroom 29 clusters, spacing 2.9; glowstone 43 clusters, spacing 16; warped_fungus 57 clusters, spacing 11.4; twisting_vines_plant 112 clusters, spacing 5.1.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 76.7%, biome match 100%.
- Landforms over the border (noise render): plains 36.6%, hills 25.9%, mountains 9.2%, plateaus 7.6%, basins 8%, islands 0.4%, water 12.4%, void 0%.
- Placeness: biome regions 1.7/km² (6 major, largest 54.5%), landform regions 14.8/km² (largest 10.8%), dominant place 54.5% of the border.

## Layers

- Columns by class: void 0%, roofed 97.7%, stacked 0%, overhang 0%, cave 0%, single 2.3%.
- Columns with each layer: sky 2.2%, surface 100%, underground 99.1%, roof 97.7%.
- Overhangs in 37% of columns, stacked layers in 2.2%, cave pockets in 43.3%.

## Places

- **surface** (243 cells, 4 types, silhouette 0.302):
  - type 1: 42.8% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 78; trees per cell 4.0 (layer 2.1); ground plants 14% (layer 8%); liquid cover 2% (layer 16%); bare rock 15% (layer 32%); cave pockets 63% (layer 43%) — camera [217.5,77,-377.5]
  - type 2: 28.4% of cells; biome crimson_forest; ground netherrack; top netherrack; height 73; neighbour step 7.5 (layer 5.1); trees per cell 0.7 (layer 2.1); canopy 10% (layer 30%); bare rock 52% (layer 32%); open to the sky 7% (layer 2%); under a roof 93% (layer 98%) — camera [-775.5,57,117.5]
  - type 3: 18.9% of cells; biome crimson_forest; ground netherrack; top lava; height 30; trees per cell 0.5 (layer 2.1); canopy 10% (layer 30%); ground plants 1% (layer 8%); liquid cover 60% (layer 16%); overhangs 15% (layer 37%); cave pockets 3% (layer 43%) — camera [-519.5,24,840.5]
  - type 4: 9.9% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 67; canopy 80% (layer 30%); ground plants 1% (layer 8%); bare rock 75% (layer 32%); stacked layers 12% (layer 2%) — camera [616.5,76,376.5]
  - landmark at [-520,-152]: ground bedrock (type netherrack); top bedrock (type netherrack); open to the sky 83% (type 0%); under a roof 17% (type 100%); height 117.7 (type 61.3)
  - landmark at [-536,-152]: ground crimson_nylium (type netherrack); top bedrock (type netherrack); open to the sky 47% (type 0%); under a roof 53% (type 100%); bare rock 0% (type 50%)
- **sky** (6 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:upside_down_forest; ground shroomlight; height 93 — camera [642.5,100,353.5]
- **underground** (243 cells, 2 types, silhouette 0.226):
  - type 1: 62.1% of cells; notableId magma_block; biome crimson_forest; height 80; cave pockets 62% (layer 44%) — camera [-334.5,85.88,327.5]
  - type 2: 37.9% of cells; notableId blackstone; biome adventure:nether_jungle; height 45; trees per cell 0.3 (layer 2.1); notable-block density (log) 0.7 (layer 1.2); cave pockets 14% (layer 44%) — camera [-269.5,23.88,-836.5]
  - landmark at [-792,600]: notableId lava (type magma_block); biome nether_wastes (type crimson_forest); cave liquid 20% (type 0%); notable-block density (log) 3.8 (type 1.4); layer present in 93% (type 100%)
  - landmark at [-216,984]: notableId lava (type magma_block); notable-block density (log) 4.4 (type 1.4); cave liquid 16% (type 0%); trees per cell 7.0 (type 2.0)
- **roof** (242 cells, 7 types, silhouette 0.345):
  - type 1: 31.8% of cells; notableId weeping_vines_plant; height 107; relief over 16 blocks 17.5 (layer 34.1) — camera [-647.5,104.88,-951.5]
  - type 2: 27.3% of cells; notableId nether_quartz_ore; height 105; relief over 16 blocks 18.0 (layer 34.1); notable-block density (log) 0.2 (layer 0.5) — camera [-519.5,101.88,824.5]
  - type 3: 12.8% of cells; notableId nether_quartz_ore; height 96; trees per cell 1.9 (layer 0.8) — camera [-23.5,100.88,-455.5]
  - type 4: 11.2% of cells; notableId nether_quartz_ore; height 79; layer present in 88% (layer 98%); neighbour step 6.1 (layer 1.8); relief over 16 blocks 86.2 (layer 34.1) — camera [-231.5,37.88,984.5]
  - type 5: 6.6% of cells; notableId nether_gold_ore; height 103; notable-block density (log) 0.2 (layer 0.5) — camera [792.5,85.88,712.5]
  - type 6: 6.2% of cells; notableId crimson_nylium; height 97; trees per cell 1.8 (layer 0.8); notable-block density (log) 0.9 (layer 0.5) — camera [232.5,86.88,-762.5]
  - type 7: 4.1% of cells; notableId blackstone; height 63; layer present in 90% (layer 98%); neighbour step 4.6 (layer 1.8); relief over 16 blocks 75.2 (layer 34.1); notable-block density (log) 1.7 (layer 0.5) — camera [-983.5,36.88,616.5]
  - landmark at [-536,-152]: layer present in 53% (type 100%); trees per cell 4.0 (type 0.0); relief over 16 blocks 92.0 (type 31.0)
  - landmark at [-968,-952]: notableId magma_block (type nether_quartz_ore); layer present in 79% (type 100%); neighbour step 8.4 (type 4.9); height 95.2 (type 76.8)
  - landmark at [-968,-536]: notableId magma_block (type nether_quartz_ore); layer present in 83% (type 100%); trees per cell 3.0 (type 0.0); notable-block density (log) 0.9 (type 0.4)
  - landmark at [-24,-88]: notableId crimson_nylium (type nether_quartz_ore); layer present in 74% (type 100%); height 61.3 (type 76.8); trees per cell 1.0 (type 0.0)
  - landmark at [-40,-88]: notableId magma_block (type nether_quartz_ore); trees per cell 4.0 (type 0.0); height 96.8 (type 76.8); relief over 16 blocks 113.0 (type 93.0)

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:crimson_forest: expressed, 36089 columns; placement land 83.7%, lava 15.5%, roofed 95.9%, sky 0.7%; snow 0%, canopy 32.5%, trees 137/ha; top nether_wart_block 26.4%, crimson_nylium 25.8%, netherrack 20.1%
- adventure:nether_jungle: expressed, 14172 columns; placement land 75.5%, lava 22.1%, roofed 97%, sky 2.5%; snow 0%, canopy 31%, trees 72/ha; top lava 22.1%, netherrack 19.8%, nether_wart_block 13.7%
- adventure:upside_down_forest: expressed, 6392 columns; placement land 84.5%, lava 3.9%, roofed 88.4%, sky 11.6%; snow 0%, canopy 86.9%, trees 213/ha; top nether_wart_block 73.3%, netherrack 10.6%, lava 3.9%
- minecraft:nether_wastes: expressed, 2721 columns; placement land 89.3%, lava 10.7%, roofed 96.7%; snow 0%, canopy 0%, trees 0/ha; top netherrack 77%, lava 10.7%, basalt 4.8%
- adventure:crimson_pinewood: expressed, 2312 columns; placement land 94.6%, lava 5%, roofed 99.7%, sky 0.3%; snow 0%, canopy 25.7%, trees 82.3/ha; top crimson_nylium 46.1%, nether_wart_block 24.3%, crimson_roots 10.8%
- minecraft:basalt_deltas: expressed, 1888 columns; placement land 81.5%, lava 18.5%, roofed 96.7%; snow 0%, canopy 0%, trees 0/ha; top basalt 56.3%, lava 18.5%, blackstone 13.7%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:crimson_forest | minecraft:crimson_forest | 57.6 | 36089 | expressed | - |
| O2 | incendium:weeping_valley | adventure:nether_jungle | 22.2 | 14172 | expressed | - |
| O3 | incendium:inverted_forest | adventure:upside_down_forest | 9.1 | 6392 | expressed | - |
| O4 | minecraft:nether_wastes | minecraft:nether_wastes | 4.4 | 2721 | expressed | - |
| O5 | minecraft:basalt_deltas | minecraft:basalt_deltas | 3.0 | 1888 | expressed | - |
| O6 | regions_unexplored:infernal_holt | minecraft:crimson_forest | 57.6 | 36089 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:crimson_pinewood | 3.7 | 2312 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:crimson_forest 57.6%, adventure:nether_jungle 22.2%, adventure:upside_down_forest 9.1% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 15.7% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 29.6% (`surface.canopyPercent`) | |
| M:relief | median 111.0 blocks a patch; height {"p5": 21.0, "p25": 33.0, "p50": 75.0, "p75": 90.0, "p95": 112.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 2.2, "surface": 100.0, "underground": 99.1, "roof": 97.7} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#5B1B1B"}` |
| M:classes | {"void": 0.0, "roofed": 97.7, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 2.3} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 96.3%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (97.7% of columns)

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
