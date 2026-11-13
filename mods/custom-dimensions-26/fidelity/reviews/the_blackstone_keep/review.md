# Review packet: the_blackstone_keep

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_blackstone_keep.json`

> The piglin military frontier. Bastions and blackstone walls from spawn, fortified against something that came from below. The deeper you dig, the harder it fights back — as if the keep itself remembers the threat.

Biomes: minecraft:soul_sand_valley, regions_unexplored:blackstone_basin, incendium:ash_barrens, minecraft:basalt_deltas, minecraft:nether_wastes, betternether:crimson_glowing_woods, minecraft:crimson_forest, betternether:wart_forest, betternether:wart_forest_edge

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_blackstone_keep.json`

> The piglin military frontier. Bastions and blackstone walls from spawn, fortified against something that came from below. The deeper you dig, the harder it fights back — as if the keep itself remembers the threat.

Biomes: adventure:blackstone_basin, minecraft:basalt_deltas, minecraft:soul_sand_valley, adventure:ash_barrens, adventure:crimson_pinewood, minecraft:crimson_forest, minecraft:nether_wastes, adventure:upside_down_forest

Terrain: `{"scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/fin/the_blackstone_keep/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/fin/the_blackstone_keep/layers`

## Summary

- Top blocks seen from the sky: blackstone 30.8%, lava 17.4%, netherrack 14.8%, tuff 8.5%.
- Surface biomes: adventure:blackstone_basin 44%, adventure:ash_barrens 12.8%, soul_sand_valley 11.1%, crimson_forest 9.6%, basalt_deltas 7.5%.
- Ground blocks: blackstone 41.8%, netherrack 19.3%, crimson_nylium 9.8%, tuff 8.5%.
- Liquid cover 0% water, 17.4% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 7.7%; bare rock 60.4%; no ground (void) 0%.
- Trees: 55.5 per ha (crimson_stem 55.5/ha, mean spacing 3.3 blocks); ground plants grass 1.6%, other 0.2%, mushroom 0.2%.
- Surface height p5 21, median 68, p95 110, max 127; median relief per patch 99 blocks; slopes under 15° 21%, over 45° 50.1%, cliffs 24.8%; above the clouds 0%, below sea level 12.8%.
- Underground: 25.9% open, 2.1% liquid; cave biomes adventure:blackstone_basin 42.2%, soul_sand_valley 18.4%, crimson_forest 11%, adventure:ash_barrens 10.7%.
- Notable clusters, fewest first: red_mushroom 16 clusters, spacing 4.6; warped_fungus 17 clusters, spacing 13.6; brown_mushroom 24 clusters, spacing 3.4; soul_fire 27 clusters, spacing 3.1; glowstone 58 clusters, spacing 24.6; fire 76 clusters, spacing 4.7.
- 8 of 8 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 70.2%, biome match 100%.
- Landforms over the border (noise render): plains 37.2%, hills 25.1%, mountains 12.9%, plateaus 6%, basins 9.4%, islands 0.2%, water 9.2%, void 0%.
- Placeness: biome regions 2.9/km² (12 major, largest 51.5%), landform regions 12.2/km² (largest 11.0%), dominant place 51.5% of the border.

## Layers

- Columns by class: void 0%, roofed 98.8%, stacked 0%, overhang 0%, cave 0%, single 1.2%.
- Columns with each layer: sky 2.6%, surface 100%, underground 99.5%, roof 98.8%.
- Overhangs in 33.9% of columns, stacked layers in 2.6%, cave pockets in 45.8%.

## Places

- **surface** (243 cells, 8 types, silhouette 0.385):
  - type 1: 38.3% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 70 — camera [918.5,36,-471.5]
  - type 2: 14.0% of cells; biome soul_sand_valley; ground netherrack; top netherrack; height 80; neighbour step 7.5 (layer 5.0); bare rock 44% (layer 60%) — camera [870.5,86,472.5]
  - type 3: 9.9% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 61; trees per cell 4.8 (layer 0.7); canopy 45% (layer 8%); ground plants 16% (layer 2%); liquid cover 4% (layer 17%); bare rock 7% (layer 60%); overhangs 51% (layer 34%) — camera [-792.5,57,378.5]
  - type 4: 9.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 38; liquid cover 67% (layer 17%); bare rock 21% (layer 60%); overhangs 16% (layer 34%); cave pockets 7% (layer 46%) — camera [-456.5,27,-359.5]
  - type 5: 9.1% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 63; neighbour step 2.7 (layer 5.0); liquid cover 4% (layer 17%); bare rock 92% (layer 60%) — camera [279.5,74,-807.5]
  - type 6: 8.2% of cells; biome nether_wastes; ground netherrack; top netherrack; height 68; neighbour step 2.9 (layer 5.0); relief over the patch 91.3 (layer 100.5); liquid cover 4% (layer 17%); bare rock 87% (layer 60%) — camera [-55.5,82,-967.5]
  - type 7: 7.4% of cells; biome basalt_deltas; ground basalt; top basalt; height 66; neighbour step 8.9 (layer 5.0); relief over 16 blocks 87.9 (layer 69.6); relief over the patch 111.0 (layer 100.5); open to the sky 8% (layer 1%); under a roof 92% (layer 99%) — camera [-774.5,60,-645.5]
  - type 8: 3.7% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 56; neighbour step 9.6 (layer 5.0); relief over 16 blocks 88.2 (layer 69.6); relief over the patch 118.0 (layer 100.5); trees per cell 1.9 (layer 0.7); canopy 61% (layer 8%); liquid cover 3% (layer 17%); bare rock 78% (layer 60%); overhangs 53% (layer 34%); stacked layers 10% (layer 3%) — camera [-743.5,42,182.5]
  - landmark at [-568,520]: biome crimson_forest (type soul_sand_valley); ground crimson_nylium (type netherrack); relief over the patch 125.0 (type 97.0); open to the sky 9% (type 0%); under a roof 91% (type 100%)
  - landmark at [-616,-104]: ground bedrock (type blackstone); top bedrock (type lava); open to the sky 88% (type 0%); under a roof 12% (type 100%); height 119.2 (type 24.0)
  - landmark at [-392,680]: biome crimson_forest (type soul_sand_valley); stacked layers 56% (type 0%); trees per cell 7.0 (type 0.0); height 42.3 (type 92.1)
  - landmark at [-376,696]: biome adventure:crimson_pinewood (type adventure:blackstone_basin); ground netherrack (type blackstone); stacked layers 97% (type 0%); ground plants 16% (type 0%); trees per cell 3.0 (type 0.0)
  - landmark at [-568,488]: top crimson_nylium (type nether_wart_block); trees per cell 2.0 (type 6.0); height 101.7 (type 52.2); cave pockets 85% (type 1%)
- **sky** (7 cells, 2 types, silhouette 0.322):
  - type 1: 71.4% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; height 69; relief over 16 blocks 64.4 (layer 53.6) — camera [-389.5,50,696.5]
  - type 2: 28.6% of cells; biome adventure:ash_barrens; ground basalt; height 92; neighbour step 3.6 (layer 5.6); relief over 16 blocks 26.5 (layer 53.6); trees per cell 0.0 (layer 2.0); island layers above 1.1 (layer 1.0) — camera [310.5,90,-776.5]
- **underground** (243 cells, 5 types, silhouette 0.222):
  - type 1: 32.1% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 66; notable-block density (log) 0.4 (layer 1.1) — camera [573.5,68.88,508.5]
  - type 2: 19.3% of cells; notableId magma_block; biome nether_wastes; height 75; trees per cell 1.2 (layer 0.4); cave pockets 64% (layer 46%) — camera [-74.5,23.88,-984.5]
  - type 3: 18.5% of cells; notableId lava; biome adventure:blackstone_basin; height 70; cave liquid 5% (layer 2%) — camera [-311.5,33.88,-338.5]
  - type 4: 18.1% of cells; notableId blackstone; biome crimson_forest; height 47; cave pockets 11% (layer 46%) — camera [-813.5,23.88,344.5]
  - type 5: 11.9% of cells; notableId soul_sand; biome soul_sand_valley; height 77; neighbour step 7.8 (layer 5.0); relief over 16 blocks 88.4 (layer 68.3); notable-block density (log) 1.9 (layer 1.1); cave air 43% (layer 24%) — camera [440.5,33.88,952.5]
  - landmark at [-568,520]: notableId crimson_nylium (type magma_block); biome crimson_forest (type nether_wastes); trees per cell 4.0 (type 0.0); cave liquid 13% (type 1%); neighbour step 12.7 (type 3.6)
  - landmark at [-808,-680]: biome basalt_deltas (type adventure:blackstone_basin); cave liquid 15% (type 1%); neighbour step 12.8 (type 3.7); notable-block density (log) 2.5 (type 0.5)
  - landmark at [-584,520]: biome crimson_forest (type adventure:blackstone_basin); cave liquid 16% (type 1%); notable-block density (log) 3.7 (type 0.5); height 94.1 (type 71.4)
  - landmark at [-808,-664]: biome basalt_deltas (type crimson_forest); neighbour step 11.8 (type 3.4); cave liquid 6% (type 0%); cave pockets 48% (type 2%)
  - landmark at [664,-728]: biome adventure:ash_barrens (type adventure:blackstone_basin); cave liquid 13% (type 1%); notable-block density (log) 3.0 (type 0.5); neighbour step 6.5 (type 3.7)
- **roof** (242 cells, 2 types, silhouette 0.304):
  - type 1: 65.3% of cells; notableId gilded_blackstone; height 103; neighbour step 0.9 (layer 2.1); relief over 16 blocks 19.4 (layer 33.6) — camera [-151.5,102.88,88.5]
  - type 2: 34.7% of cells; notableId nether_quartz_ore; height 82; neighbour step 4.2 (layer 2.1); relief over 16 blocks 60.4 (layer 33.6) — camera [-375.5,63.88,696.5]
  - landmark at [-808,-680]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 2.8 (type 0.4); layer present in 95% (type 100%); height 58.8 (type 82.1)
  - landmark at [-808,-648]: notableId blackstone (type nether_quartz_ore); layer present in 76% (type 100%); notable-block density (log) 1.8 (type 0.4); height 43.7 (type 82.1)
  - landmark at [-792,-648]: notableId blackstone (type nether_quartz_ore); layer present in 72% (type 100%); notable-block density (log) 3.7 (type 0.4); relief over 16 blocks 91.0 (type 58.0)
  - landmark at [-808,-664]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 3.3 (type 0.4); neighbour step 9.1 (type 5.1); relief over 16 blocks 98.0 (type 58.0)
  - landmark at [-776,-680]: notableId blackstone (type nether_quartz_ore); layer present in 66% (type 100%); notable-block density (log) 1.6 (type 0.4); relief over 16 blocks 87.0 (type 58.0)

## Biome expression

8 of 8 placed biomes are expressed.

- adventure:blackstone_basin: expressed, 27369 columns; placement land 75%, lava 24.9%, roofed 98.7%; snow 0%, canopy 0%, trees 0/ha; top blackstone 68.3%, lava 24.9%, polished_blackstone 4.5%
- adventure:ash_barrens: expressed, 8243 columns; placement land 81.3%, lava 15%, roofed 96.3%, sky 3.7%; snow 0%, canopy 0%, trees 0/ha; top tuff 63.7%, lava 15%, netherrack 12.1%
- minecraft:soul_sand_valley: expressed, 6986 columns; placement land 87.6%, lava 11.3%, roofed 98.7%, sky 1.1%; snow 0%, canopy 0%, trees 0/ha; top netherrack 34.7%, soul_soil 25.8%, soul_sand 23.1%
- minecraft:crimson_forest: expressed, 6125 columns; placement land 92.2%, lava 5%, roofed 96.8%, sky 2.8%; snow 0%, canopy 35.8%, trees 177.1/ha; top nether_wart_block 32.1%, netherrack 25.2%, crimson_nylium 25.1%
- minecraft:basalt_deltas: expressed, 4779 columns; placement land 80.6%, lava 17.1%, roofed 90.1%, sky 2.2%; snow 0%, canopy 0%, trees 0/ha; top basalt 53.4%, lava 17.1%, blackstone 8.4%
- adventure:crimson_pinewood: expressed, 4093 columns; placement land 69.7%, lava 13.6%, roofed 83.3%, sky 16.7%; snow 0%, canopy 47.8%, trees 203.4/ha; top nether_wart_block 33.3%, crimson_nylium 26.8%, lava 13.6%
- minecraft:nether_wastes: expressed, 3812 columns; placement land 94.5%, lava 5.4%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top netherrack 90.3%, lava 5.4%, glowstone 1%
- adventure:upside_down_forest: expressed, 2395 columns; placement land 87.3%, lava 2.9%, roofed 90.2%, sky 9.8%; snow 0%, canopy 65.8%, trees 186.5/ha; top nether_wart_block 54.7%, netherrack 23.3%, weeping_vines_plant 4.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 11.1 | 6986 | expressed | - |
| O2 | regions_unexplored:blackstone_basin | adventure:blackstone_basin | 44.0 | 27369 | expressed | - |
| O3 | incendium:ash_barrens | adventure:ash_barrens | 12.8 | 8243 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 7.5 | 4779 | expressed | - |
| O5 | minecraft:nether_wastes | minecraft:nether_wastes | 6.1 | 3812 | expressed | - |
| O6 | betternether:crimson_glowing_woods | minecraft:crimson_forest | 9.6 | 6125 | expressed | - |
| O7 | minecraft:crimson_forest | minecraft:crimson_forest | 9.6 | 6125 | expressed | - |
| O8 | betternether:wart_forest | adventure:upside_down_forest | 3.5 | 2395 | expressed | - |
| O9 | betternether:wart_forest_edge | adventure:crimson_pinewood | 5.5 | 4093 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:blackstone_basin 44.0%, adventure:ash_barrens 12.8%, minecraft:soul_sand_valley 11.1% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 17.4% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 7.7% (`surface.canopyPercent`) | |
| M:relief | median 99.0 blocks a patch; height {"p5": 21.0, "p25": 40.0, "p50": 68.0, "p75": 89.0, "p95": 110.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 2.6, "surface": 100.0, "underground": 99.5, "roof": 98.8} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#1B1B2B", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 98.8, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 1.2} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (98.8% of columns)

## Shots

### the_blackstone_keep-surface-t1

Picked for: surface type 1: 47.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 65
Census: sky 0%; near: blackstone 87%, polished_blackstone 1%; mid: blackstone 10.5%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t1.png`

### the_blackstone_keep-surface-t1-nv

Picked for: surface type 1: 47.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 65
Census: sky 0%; near: blackstone 87%, polished_blackstone 1%; mid: blackstone 10.5%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t1-nv.png`

### the_blackstone_keep-sky-t1

Picked for: sky type 1: 85.7% of cells; biome adventure:blackstone_basin; ground blackstone; height 80
Census: sky 0%; near: blackstone 68.3%, lava 1.1%; mid: blackstone 28.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-sky-t1.png`

### the_blackstone_keep-sky-t1-nv

Picked for: sky type 1: 85.7% of cells; biome adventure:blackstone_basin; ground blackstone; height 80
Census: sky 0%; near: blackstone 68.3%, lava 1.1%; mid: blackstone 28.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-sky-t1-nv.png`

### the_blackstone_keep-underground-t4

Picked for: underground type 4: 15.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 32; neighbour step 1.5 (layer 5.2); relief over 16 blocks 35.6 (layer 65.3); notable-block density (log) 0.3 (layer 1.0); cave air 2% (layer 21%); cave pockets 1% (layer 40%)
Census: sky 0%; unloaded 1.7%; near: lava 31.2%, blackstone 27.6%; mid: blackstone 26.7%; far: blackstone 10.4%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-underground-t4.png`

### the_blackstone_keep-underground-t4-nv

Picked for: underground type 4: 15.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 32; neighbour step 1.5 (layer 5.2); relief over 16 blocks 35.6 (layer 65.3); notable-block density (log) 0.3 (layer 1.0); cave air 2% (layer 21%); cave pockets 1% (layer 40%)
Census: sky 0%; unloaded 1.7%; near: lava 31.2%, blackstone 27.6%; mid: blackstone 26.7%; far: blackstone 10.4%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-underground-t4-nv.png`

### the_blackstone_keep-roof-t8

Picked for: roof type 8: 4.3% of cells; notableId blackstone; height 81; layer present in 80% (layer 98%); neighbour step 4.3 (layer 1.8); relief over 16 blocks 89.8 (layer 32.6); notable-block density (log) 2.0 (layer 0.4)
Census: sky 0%; near: basalt 91.5%, netherrack 3.6%; mid: basalt 3.9%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-roof-t8.png`

### the_blackstone_keep-roof-t8-nv

Picked for: roof type 8: 4.3% of cells; notableId blackstone; height 81; layer present in 80% (layer 98%); neighbour step 4.3 (layer 1.8); relief over 16 blocks 89.8 (layer 32.6); notable-block density (log) 2.0 (layer 0.4)
Census: sky 0%; near: basalt 91.5%, netherrack 3.6%; mid: basalt 3.9%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-roof-t8-nv.png`

### the_blackstone_keep-surface-t4

Picked for: surface type 4: 7.7% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 59; relief over 16 blocks 84.9 (layer 67.5); relief over the patch 114.0 (layer 101.7); trees per cell 4.7 (layer 0.4); canopy 39% (layer 4%); ground plants 15% (layer 1%); liquid cover 5% (layer 20%); bare rock 11% (layer 60%)
Census: sky 0%; near: netherrack 45.3%, nether_wart_block 32.2%; mid: netherrack 7.2%, nether_wart_block 5.8%; biomes: crimson_forest 93.8%, adventure:crimson_pinewood 6.2%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t4.png`

### the_blackstone_keep-surface-t4-nv

Picked for: surface type 4: 7.7% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 59; relief over 16 blocks 84.9 (layer 67.5); relief over the patch 114.0 (layer 101.7); trees per cell 4.7 (layer 0.4); canopy 39% (layer 4%); ground plants 15% (layer 1%); liquid cover 5% (layer 20%); bare rock 11% (layer 60%)
Census: sky 0%; near: netherrack 45.3%, nether_wart_block 32.2%; mid: netherrack 7.2%, nether_wart_block 5.8%; biomes: crimson_forest 93.8%, adventure:crimson_pinewood 6.2%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t4-nv.png`

### the_blackstone_keep-surface-t3

Picked for: surface type 3: 11.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 28; liquid cover 69% (layer 20%); bare rock 27% (layer 60%); overhangs 11% (layer 30%); cave pockets 2% (layer 40%)
Census: sky 0%; near: lava 44.7%, blackstone 18.4%; mid: blackstone 27.7%; far: blackstone 7.7%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t3.png`

### the_blackstone_keep-surface-t3-nv

Picked for: surface type 3: 11.5% of cells; biome adventure:blackstone_basin; ground blackstone; top lava; height 28; liquid cover 69% (layer 20%); bare rock 27% (layer 60%); overhangs 11% (layer 30%); cave pockets 2% (layer 40%)
Census: sky 0%; near: lava 44.7%, blackstone 18.4%; mid: blackstone 27.7%; far: blackstone 7.7%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_blackstone_keep/screens/the_blackstone_keep-surface-t3-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
