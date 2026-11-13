# Review packet: the_buried_age

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:soul_sand_valley, betternether:old_swampland, betternether:flooded_deltas, betternether:upside_down_forest, betternether:upside_down_forest_cleared, minecraft:basalt_deltas, regions_unexplored:ancient_delta, minecraft:nether_wastes, incendium:quartz_flats

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:basalt_deltas, adventure:quartz_flats, minecraft:soul_sand_valley, minecraft:nether_wastes, adventure:upside_down_forest

Terrain: `{"base": "roofed", "params": {"floorHeight": 70, "floorRelief": 18, "ceilingGap": 40, "roughness": 0.3, "ledges": 0.4, "ledgeStep": 6}, "scales": {"climate": 1.6}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/r3/full/the_buried_age/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/r3/full/the_buried_age/layers`

## Summary

- Top blocks seen from the sky: lava 57.9%, nether_wart_block 14.2%, quartz_block 7.5%, basalt 7%.
- Surface biomes: basalt_deltas 30.7%, adventure:quartz_flats 19.7%, adventure:upside_down_forest 18.9%, soul_sand_valley 15.8%, nether_wastes 15%.
- Ground blocks: netherrack 43.6%, basalt 25.1%, quartz_block 7.5%, soul_sand 7.4%.
- Liquid cover 0% water, 57.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 14.3%; bare rock 13.2%; no ground (void) 0%.
- Trees: 65.4 per ha (crimson_stem 65.4/ha, mean spacing 3.4 blocks); ground plants other 0%, mushroom 0%.
- Surface height p5 56, median 69, p95 100, max 119; median relief per patch 66 blocks; slopes under 15° 38.4%, over 45° 30%, cliffs 13.8%; above the clouds 0%, below sea level 70.5%.
- Underground: 2.3% open, 5.5% liquid; cave biomes adventure:quartz_flats 34.8%, nether_wastes 23.9%, adventure:upside_down_forest 21.1%, basalt_deltas 17.7%.
- Notable clusters, fewest first: red_mushroom 4 clusters, spacing 3.7; quartz_block 4 clusters, spacing n/a; soul_fire 5 clusters, spacing 8.2; brown_mushroom 8 clusters, spacing 6.2; fire 9 clusters, spacing 3.4; basalt 28 clusters, spacing 4.3.
- 2 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 936 columns: height within 2 blocks 71.8%, biome match 100%.
- Landforms over the border (noise render): plains 12.6%, hills 8.7%, mountains 0%, plateaus 0.1%, basins 0%, islands 8.1%, water 70.5%, void 0%.
- Placeness: biome regions 5.2/km² (16 major, largest 11.4%), landform regions 6.7/km² (largest 70.2%), dominant place 70.2% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 4.2%, surface 100%, underground 100%, roof 100%.
- Overhangs in 13% of columns, stacked layers in 4.2%, cave pockets in 16.4%.

## Places

- **surface** (234 cells, 7 types, silhouette 0.411):
  - type 1: 19.7% of cells; biome basalt_deltas; ground basalt; top lava; height 65; relief over the patch 83.7 (layer 71.9); liquid cover 93% (layer 58%) — camera [-775.5,74,-103.5]
  - type 2: 19.2% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 67; canopy 74% (layer 14%); liquid cover 18% (layer 58%); stacked layers 21% (layer 4%) — camera [-279.5,74,-615.5]
  - type 3: 14.1% of cells; biome soul_sand_valley; ground soul_sand; top lava; height 71; neighbour step 0.6 (layer 2.6); relief over 16 blocks 23.3 (layer 39.7); bare rock 2% (layer 13%); overhangs 5% (layer 13%) — camera [-343.5,74,152.5]
  - type 4: 13.7% of cells; biome nether_wastes; ground netherrack; top lava; height 67; neighbour step 1.2 (layer 2.6); liquid cover 95% (layer 58%) — camera [-663.5,74,904.5]
  - type 5: 12.0% of cells; biome adventure:quartz_flats; ground netherrack; top lava; height 70; neighbour step 5.2 (layer 2.6); relief over 16 blocks 61.5 (layer 39.7); relief over the patch 89.5 (layer 71.9); liquid cover 83% (layer 58%); overhangs 23% (layer 13%); cave pockets 29% (layer 16%) — camera [792.5,74,-727.5]
  - type 6: 11.1% of cells; biome basalt_deltas; ground basalt; top basalt; height 83; relief over 16 blocks 25.9 (layer 39.7); relief over the patch 56.2 (layer 71.9); liquid cover 36% (layer 58%); bare rock 55% (layer 13%) — camera [8.5,84,762.5]
  - type 7: 10.3% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 84; neighbour step 4.2 (layer 2.6); liquid cover 9% (layer 58%) — camera [-583.5,90,-118.5]
  - landmark at [-536,-888]: overhangs 62% (type 1%); neighbour step 11.8 (type 1.6); notable-block density (log) 4.7 (type 0.0)
  - landmark at [824,600]: biome nether_wastes (type adventure:quartz_flats); ground plants 4% (type 0%); cave pockets 79% (type 30%); height 87.7 (type 69.0)
  - landmark at [824,824]: ground basalt (type netherrack); bare rock 76% (type 1%); overhangs 38% (type 7%); neighbour step 5.6 (type 0.6)
  - landmark at [904,-584]: cave pockets 73% (type 0%); relief over the patch 92.0 (type 33.0); neighbour step 10.0 (type 4.3)
  - landmark at [824,568]: biome nether_wastes (type adventure:quartz_flats); relief over 16 blocks 118.0 (type 59.0); cave pockets 64% (type 30%); relief over the patch 118.0 (type 87.0)
- **sky** (16 cells, 4 types, silhouette 0.302):
  - type 1: 37.5% of cells; biome adventure:upside_down_forest; ground shroomlight; height 103; layer present in 32% (layer 28%); neighbour step 2.0 (layer 2.4); notable-block density (log) 0.5 (layer 0.7) — camera [854.5,99,862.5]
  - type 2: 31.3% of cells; biome adventure:upside_down_forest; ground shroomlight; height 104; layer present in 26% (layer 28%); neighbour step 3.3 (layer 2.4); relief over 16 blocks 23.6 (layer 16.9) — camera [-260.5,105,-618.5]
  - type 3: 18.8% of cells; biome adventure:upside_down_forest; ground shroomlight; height 98; layer present in 26% (layer 28%); neighbour step 1.9 (layer 2.4); relief over 16 blocks 12.0 (layer 16.9); trees per cell 11.0 (layer 7.6); notable-block density (log) 1.1 (layer 0.7) — camera [71.5,90,-745.5]
  - type 4: 12.5% of cells; biome adventure:upside_down_forest; ground shroomlight; height 106; layer present in 27% (layer 28%); relief over 16 blocks 12.5 (layer 16.9); trees per cell 3.0 (layer 7.6); notable-block density (log) 1.3 (layer 0.7) — camera [104.5,103,-20.5]
- **underground** (234 cells, 5 types, silhouette 0.246):
  - type 1: 33.3% of cells; notableId magma_block; biome adventure:quartz_flats; height 69; neighbour step 1.2 (layer 2.6) — camera [-393.5,72.88,-462.5]
  - type 2: 26.1% of cells; notableId blackstone; biome nether_wastes; height 68 — camera [862.5,82.88,817.5]
  - type 3: 17.9% of cells; notableId magma_block; biome adventure:quartz_flats; height 74; neighbour step 5.7 (layer 2.6); relief over 16 blocks 57.6 (layer 39.3); cave air 7% (layer 2%); cave liquid 13% (layer 5%); cave pockets 34% (layer 16%) — camera [809.5,72.88,-732.5]
  - type 4: 14.5% of cells; notableId blackstone; biome basalt_deltas; height 78; neighbour step 3.9 (layer 2.6) — camera [23.5,89.88,775.5]
  - type 5: 8.1% of cells; notableId lava; biome basalt_deltas; height 71; notable-block density (log) 3.0 (layer 1.7); cave liquid 14% (layer 5%); cave pockets 32% (layer 16%) — camera [213.5,72.88,373.5]
  - landmark at [824,600]: biome nether_wastes (type basalt_deltas); cave air 20% (type 1%); cave pockets 79% (type 2%); cave liquid 21% (type 0%)
  - landmark at [824,568]: notableId nether_quartz_ore (type magma_block); biome nether_wastes (type adventure:quartz_flats); layer present in 99% (type 100%); cave liquid 46% (type 8%); cave air 15% (type 7%)
  - landmark at [-536,-888]: layer present in 100% (type 100%); cave air 14% (type 1%); cave liquid 31% (type 0%)
  - landmark at [840,584]: biome nether_wastes (type adventure:quartz_flats); cave liquid 29% (type 8%); cave pockets 80% (type 35%); cave air 14% (type 7%)
  - landmark at [88,-728]: biome adventure:upside_down_forest (type none); cave air 11% (type 2%); notable-block density (log) 5.0 (type 2.8); neighbour step 6.5 (type 1.9)
- **roof** (234 cells, 8 types, silhouette 0.277):
  - type 1: 18.4% of cells; notableId blackstone; height 109; neighbour step 0.1 (layer 0.4); relief over 16 blocks 4.0 (layer 9.3); notable-block density (log) 0.9 (layer 0.6) — camera [184.5,103.88,-775.5]
  - type 2: 17.1% of cells; notableId nether_quartz_ore; height 107; relief over 16 blocks 14.8 (layer 9.3) — camera [-359.5,99.88,-455.5]
  - type 3: 16.7% of cells; notableId nether_quartz_ore; height 110; neighbour step 0.2 (layer 0.4); relief over 16 blocks 4.5 (layer 9.3); notable-block density (log) 0.1 (layer 0.6) — camera [-775.5,105.88,-679.5]
  - type 4: 14.1% of cells; notableId glowstone; height 111; neighbour step 0.7 (layer 0.4) — camera [-599.5,104.88,-151.5]
  - type 5: 9.8% of cells; notableId weeping_vines; height 114; trees per cell 1.1 (layer 0.3) — camera [840.5,102.88,862.5]
  - type 6: 9.4% of cells; notableId blackstone; height 108; neighbour step 1.1 (layer 0.4); relief over 16 blocks 18.5 (layer 9.3); notable-block density (log) 0.9 (layer 0.6) — camera [-519.5,100.88,-919.5]
  - type 7: 8.5% of cells; notableId soul_soil; height 111; neighbour step 0.1 (layer 0.4); notable-block density (log) 1.6 (layer 0.6) — camera [472.5,105.88,136.5]
  - type 8: 6.0% of cells; notableId weeping_vines_plant; height 114; neighbour step 0.8 (layer 0.4); trees per cell 3.2 (layer 0.3) — camera [-264.5,111.88,-619.5]
  - landmark at [72,-712]: trees per cell 6.0 (type 3.0); neighbour step 1.9 (type 0.7); height 109.8 (type 116.4)
  - landmark at [88,-728]: notableId shroomlight (type blackstone); trees per cell 5.0 (type 0.0); notable-block density (log) 0.4 (type 1.1); neighbour step 1.1 (type 0.9)
  - landmark at [88,-712]: trees per cell 6.0 (type 3.0); neighbour step 1.6 (type 0.7); relief over 16 blocks 19.0 (type 10.0)
  - landmark at [824,824]: notableId basalt (type glowstone); notable-block density (log) 1.7 (type 0.5); trees per cell 1.0 (type 0.0); height 114.3 (type 111.0)
  - landmark at [-344,120]: notableId soul_sand (type soul_soil); relief over 16 blocks 44.0 (type 1.0); neighbour step 0.5 (type 0.1); height 113.1 (type 111.5)

## Biome expression

2 of 5 placed biomes are expressed.

- minecraft:basalt_deltas: placed, not expressed, 18395 columns; placement land 28%, lava 72%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0.4%, trees 0/ha; top lava 72%, basalt 21.7%, magma_block 3.3%; **mostly lava**
- adventure:upside_down_forest: expressed, 13778 columns; placement land 68%, lava 14.1%, roofed 82.1%, sky 17.9%; snow 0%, canopy 90.8%, trees 331.9/ha; top nether_wart_block 61.2%, lava 14.1%, netherrack 5.5%
- adventure:quartz_flats: expressed, 11833 columns; placement land 54.6%, lava 45.1%, roofed 99.7%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top lava 45.1%, quartz_block 37.7%, glowstone 9.3%
- minecraft:soul_sand_valley: placed, not expressed, 9440 columns; placement land 37.8%, lava 62.2%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top lava 62.2%, soul_sand 19.9%, soul_soil 15.4%; **mostly lava**
- minecraft:nether_wastes: placed, not expressed, 8993 columns; placement land 7.6%, lava 92.2%, roofed 99.8%, sky 0.2%; snow 0%, canopy 4.3%, trees 0/ha; top lava 92.2%, netherrack 6.4%, glowstone 0.4%; **mostly lava**


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 15.8 | 9440 | placed, not expressed | mostly lava |
| O2 | betternether:old_swampland | minecraft:soul_sand_valley | 15.8 | 9440 | placed, not expressed | mostly lava |
| O3 | betternether:flooded_deltas | minecraft:basalt_deltas | 30.7 | 18395 | placed, not expressed | mostly lava |
| O4 | betternether:upside_down_forest | adventure:upside_down_forest | 18.9 | 13778 | expressed | - |
| O5 | betternether:upside_down_forest_cleared | minecraft:crimson_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O6 | minecraft:basalt_deltas | minecraft:basalt_deltas | 30.7 | 18395 | placed, not expressed | mostly lava |
| O7 | regions_unexplored:ancient_delta | adventure:ash_barrens | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | minecraft:nether_wastes | minecraft:nether_wastes | 15.0 | 8993 | placed, not expressed | mostly lava |
| O9 | incendium:quartz_flats | adventure:quartz_flats | 19.7 | 11833 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:basalt_deltas 30.7%, adventure:quartz_flats 19.7%, adventure:upside_down_forest 18.9% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultFluid": "minecraft:lava", "seaLevel": 74}` |
| M:lava | 57.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 14.3% (`surface.canopyPercent`) | |
| M:relief | median 66.0 blocks a patch; height {"p5": 56.0, "p25": 64.0, "p50": 69.0, "p75": 75.0, "p95": 100.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 4.2, "surface": 100.0, "underground": 100.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#3B2B1B", "ambientLight": 0.1}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 22.2%
- S:c — v2 biomes placed but not expressed: 3
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_buried_age-surface-t2

Picked for: surface type 2: 19.2% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 67; canopy 74% (layer 14%); liquid cover 18% (layer 58%); stacked layers 21% (layer 4%)
Census: sky 0%; unloaded 9.6%; near: lava 51.6%; mid: netherrack 18.2%, lava 4.2%; far: netherrack 13.2%, glowstone 1%; biomes: adventure:upside_down_forest 55.7%, nether_wastes 42.7%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t2.png`

### the_buried_age-surface-t2-nv

Picked for: surface type 2: 19.2% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 67; canopy 74% (layer 14%); liquid cover 18% (layer 58%); stacked layers 21% (layer 4%)
Census: sky 0%; unloaded 8.9%; near: lava 51.6%; mid: netherrack 18.2%, lava 4.2%; far: netherrack 13.4%, glowstone 1.4%; biomes: adventure:upside_down_forest 55.2%, nether_wastes 42.7%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t2-nv.png`

### the_buried_age-underground-t1

Picked for: underground type 1: 33.3% of cells; notableId magma_block; biome adventure:quartz_flats; height 69; neighbour step 1.2 (layer 2.6)
Census: sky 0%; unloaded 8.5%; near: lava 33.2%, soul_soil 13.5%; mid: soul_sand 10.9%, soul_soil 7.9%; far: soul_soil 6.6%, soul_sand 6%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-underground-t1.png`

### the_buried_age-underground-t1-nv

Picked for: underground type 1: 33.3% of cells; notableId magma_block; biome adventure:quartz_flats; height 69; neighbour step 1.2 (layer 2.6)
Census: sky 0%; unloaded 7.8%; near: lava 33.2%, soul_soil 13.5%; mid: soul_sand 10.9%, soul_soil 7.9%; far: soul_soil 6.9%, soul_sand 6.2%; biomes: soul_sand_valley 99.8%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-underground-t1-nv.png`

### the_buried_age-surface-t1

Picked for: surface type 1: 19.7% of cells; biome basalt_deltas; ground basalt; top lava; height 65; relief over the patch 83.7 (layer 71.9); liquid cover 93% (layer 58%)
Census: sky 0%; unloaded 0.5%; near: lava 51.6%; mid: basalt 22.9%, lava 1.9%; far: basalt 13.5%, glowstone 3.7%; biomes: basalt_deltas 91.5%, adventure:quartz_flats 8.2%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t1.png`

### the_buried_age-surface-t1-nv

Picked for: surface type 1: 19.7% of cells; biome basalt_deltas; ground basalt; top lava; height 65; relief over the patch 83.7 (layer 71.9); liquid cover 93% (layer 58%)
Census: sky 0%; unloaded 0.4%; near: lava 51.6%; mid: basalt 22.9%, lava 1.9%; far: basalt 13.6%, glowstone 3.7%; biomes: basalt_deltas 91.5%, adventure:quartz_flats 8.2%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t1-nv.png`

### the_buried_age-surface-t7

Picked for: surface type 7: 10.3% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 84; neighbour step 4.2 (layer 2.6); liquid cover 9% (layer 58%)
Census: sky 0.3%; unloaded 0.1%; near: quartz_block 30.6%, quartz_pillar 30.4%; mid: glowstone 10.7%, netherrack 10.2%; far: glowstone 1.1%; biomes: adventure:quartz_flats 99.1%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t7.png`

### the_buried_age-surface-t7-nv

Picked for: surface type 7: 10.3% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 84; neighbour step 4.2 (layer 2.6); liquid cover 9% (layer 58%)
Census: sky 0.3%; near: quartz_block 30.6%, quartz_pillar 30.4%; mid: glowstone 10.7%, netherrack 10.2%; far: glowstone 1.1%; biomes: adventure:quartz_flats 99%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t7-nv.png`

### the_buried_age-surface-lm1

Picked for: surface landmark: overhangs 62% (type 1%); neighbour step 11.8 (type 1.6); notable-block density (log) 4.7 (type 0.0)
Census: sky 2%; near: lava 51.3%; mid: basalt 10.9%, netherrack 5.7%; far: netherrack 8.6%, weeping_vines_plant 5.3%; biomes: basalt_deltas 67.8%, nether_wastes 15.1%, adventure:upside_down_forest 8.9%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-lm1.png`

### the_buried_age-surface-lm1-nv

Picked for: surface landmark: overhangs 62% (type 1%); neighbour step 11.8 (type 1.6); notable-block density (log) 4.7 (type 0.0)
Census: sky 2%; near: lava 51.3%; mid: basalt 10.9%, netherrack 5.7%; far: netherrack 8.6%, weeping_vines_plant 5.3%; biomes: basalt_deltas 67.8%, nether_wastes 15.1%, adventure:upside_down_forest 8.9%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
