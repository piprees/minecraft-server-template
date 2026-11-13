# Review packet: the_buried_age

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:soul_sand_valley, betternether:old_swampland, betternether:flooded_deltas, betternether:upside_down_forest, betternether:upside_down_forest_cleared, minecraft:basalt_deltas, regions_unexplored:ancient_delta, minecraft:nether_wastes, incendium:quartz_flats

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:basalt_deltas, adventure:quartz_flats, minecraft:soul_sand_valley, minecraft:nether_wastes, adventure:upside_down_forest, minecraft:crimson_forest, adventure:ash_barrens

Terrain: `{"base": "roofed", "params": {"floorHeight": 70, "floorRelief": 18, "ceilingGap": 40, "roughness": 0.3, "ledges": 0.4, "ledgeStep": 6}, "scales": {"climate": 1.6}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/fin/the_buried_age/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/fin/the_buried_age/layers`

## Summary

- Top blocks seen from the sky: lava 58%, basalt 14.5%, nether_wart_block 9.3%, quartz_block 4.5%.
- Surface biomes: basalt_deltas 32.5%, adventure:ash_barrens 16.4%, adventure:quartz_flats 15.8%, crimson_forest 11.6%, nether_wastes 8.2%.
- Ground blocks: netherrack 27.8%, basalt 26.9%, smooth_basalt 12.9%, crimson_nylium 9.6%.
- Liquid cover 0% water, 58% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 11.2%; bare rock 21%; no ground (void) 0%.
- Trees: 57.4 per ha (crimson_stem 57.4/ha, mean spacing 3.3 blocks); ground plants mushroom 0.1%, other 0%, grass 0%.
- Surface height p5 57, median 69, p95 106, max 119; median relief per patch 67 blocks; slopes under 15° 37.7%, over 45° 34.5%, cliffs 14.5%; above the clouds 0%, below sea level 64.9%.
- Underground: 2.4% open, 6% liquid; cave biomes adventure:quartz_flats 40.2%, basalt_deltas 15.6%, adventure:upside_down_forest 14.4%, soul_sand_valley 13.2%.
- Notable clusters, fewest first: soul_fire 3 clusters, spacing 2.2; fire 7 clusters, spacing 14.9; quartz_block 7 clusters, spacing 17.3; crimson_nylium 9 clusters, spacing 18; basalt 25 clusters, spacing 4.7; brown_mushroom 31 clusters, spacing 4.8.
- 3 of 7 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 71.1%, biome match 100%.
- Landforms over the border (noise render): plains 12.6%, hills 8.7%, mountains 0%, plateaus 0.1%, basins 0%, islands 8.1%, water 70.5%, void 0%.
- Placeness: biome regions 7.9/km² (23 major, largest 12.6%), landform regions 6.7/km² (largest 70.2%), dominant place 70.2% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 1.8%, surface 100%, underground 99.9%, roof 100%.
- Overhangs in 13.6% of columns, stacked layers in 1.8%, cave pockets in 16%.

## Places

- **surface** (243 cells, 8 types, silhouette 0.349):
  - type 1: 22.6% of cells; biome basalt_deltas; ground basalt; top basalt; height 81; relief over 16 blocks 31.7 (layer 44.3); relief over the patch 57.2 (layer 73.2); liquid cover 23% (layer 58%); bare rock 70% (layer 21%) — camera [232.5,76,-904.5]
  - type 2: 14.0% of cells; biome adventure:ash_barrens; ground smooth_basalt; top lava; height 64; neighbour step 1.0 (layer 2.8); liquid cover 97% (layer 58%); bare rock 2% (layer 21%); overhangs 4% (layer 14%) — camera [-551.5,74,184.5]
  - type 3: 13.6% of cells; biome basalt_deltas; ground basalt; top lava; height 68; liquid cover 83% (layer 58%) — camera [792.5,74,-535.5]
  - type 4: 11.1% of cells; biome crimson_forest; ground crimson_nylium; top lava; height 67; trees per cell 5.7 (layer 0.7); canopy 48% (layer 11%); bare rock 2% (layer 21%) — camera [-391.5,74,-455.5]
  - type 5: 10.7% of cells; biome adventure:quartz_flats; ground netherrack; top lava; height 74; neighbour step 5.1 (layer 2.8); liquid cover 81% (layer 58%) — camera [-663.5,74,-631.5]
  - type 6: 10.7% of cells; biome nether_wastes; ground netherrack; top lava; height 66; relief over 16 blocks 60.2 (layer 44.3); relief over the patch 99.3 (layer 73.2); liquid cover 89% (layer 58%); bare rock 3% (layer 21%); overhangs 21% (layer 14%); cave pockets 35% (layer 16%) — camera [-215.5,74,-455.5]
  - type 7: 9.5% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 81; neighbour step 4.4 (layer 2.8); liquid cover 8% (layer 58%); bare rock 6% (layer 21%) — camera [-999.5,77,648.5]
  - type 8: 7.8% of cells; biome adventure:upside_down_forest; ground netherrack; top nether_wart_block; height 67; neighbour step 4.4 (layer 2.8); relief over 16 blocks 57.4 (layer 44.3); canopy 69% (layer 11%); liquid cover 26% (layer 58%); overhangs 23% (layer 14%); stacked layers 21% (layer 2%) — camera [-327.5,74,840.5]
  - landmark at [696,712]: biome soul_sand_valley (type adventure:quartz_flats); ground soul_sand (type netherrack); overhangs 82% (type 19%); relief over 16 blocks 117.0 (type 49.0); relief over the patch 117.0 (type 59.0)
  - landmark at [344,920]: biome soul_sand_valley (type basalt_deltas); ground soul_soil (type basalt); top soul_soil (type basalt); cave pockets 61% (type 0%); ground plants 1% (type 0%); height 89.5 (type 77.1)
  - landmark at [696,728]: biome soul_sand_valley (type adventure:quartz_flats); ground soul_soil (type netherrack); overhangs 63% (type 19%); relief over the patch 117.0 (type 59.0); notable-block density (log) 2.5 (type 0.2)
  - landmark at [392,696]: biome soul_sand_valley (type adventure:quartz_flats); ground soul_soil (type quartz_block); top soul_soil (type quartz_block); notable-block density (log) 3.4 (type 0.3); relief over the patch 111.0 (type 61.0); neighbour step 0.2 (type 4.3)
  - landmark at [680,712]: biome soul_sand_valley (type nether_wastes); ground soul_soil (type netherrack); neighbour step 8.1 (type 1.0); relief over 16 blocks 115.0 (type 52.0); overhangs 59% (type 25%)
- **sky** (4 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:upside_down_forest; ground shroomlight; height 101 — camera [-297.5,103,803.5]
- **underground** (243 cells, 5 types, silhouette 0.278):
  - type 1: 34.2% of cells; notableId magma_block; biome nether_wastes; height 67; neighbour step 1.5 (layer 2.8) — camera [-618.5,72.88,213.5]
  - type 2: 27.2% of cells; notableId blackstone; biome crimson_forest; height 73; relief over 16 blocks 30.8 (layer 43.7); cave air 0% (layer 2%) — camera [673.5,76.88,-862.5]
  - type 3: 18.5% of cells; notableId magma_block; biome adventure:quartz_flats; height 77; neighbour step 6.0 (layer 2.8); cave air 7% (layer 2%) — camera [577.5,72.88,420.5]
  - type 4: 12.3% of cells; notableId lava; biome adventure:upside_down_forest; height 69; notable-block density (log) 2.5 (layer 1.6); cave liquid 16% (layer 6%); cave pockets 40% (layer 16%) — camera [-220.5,72.88,-460.5]
  - type 5: 7.8% of cells; notableId blackstone; biome basalt_deltas; height 82; neighbour step 5.0 (layer 2.8); cave air 6% (layer 2%) — camera [314.5,98.88,917.5]
  - landmark at [680,712]: biome soul_sand_valley (type adventure:quartz_flats); cave pockets 52% (type 0%); cave liquid 25% (type 1%); cave air 15% (type 7%)
  - landmark at [-568,-648]: notableId lava (type magma_block); cave liquid 44% (type 1%); cave pockets 59% (type 0%); notable-block density (log) 4.1 (type 1.5)
  - landmark at [696,728]: notableId soul_sand (type blackstone); biome soul_sand_valley (type basalt_deltas); cave air 18% (type 2%); neighbour step 8.4 (type 4.4); cave liquid 14% (type 4%)
  - landmark at [696,712]: biome soul_sand_valley (type basalt_deltas); layer present in 97% (type 100%); cave air 15% (type 2%); cave liquid 40% (type 4%)
  - landmark at [-232,-488]: biome nether_wastes (type none); cave liquid 33% (type 0%); cave pockets 54% (type 0%); relief over 16 blocks 92.0 (type 51.0)
- **roof** (243 cells, 8 types, silhouette 0.291):
  - type 1: 18.5% of cells; notableId blackstone; height 110; neighbour step 0.1 (layer 0.5); relief over 16 blocks 3.5 (layer 9.4); notable-block density (log) 1.0 (layer 0.5) — camera [-599.5,105.88,-151.5]
  - type 2: 18.1% of cells; notableId nether_quartz_ore; height 110; relief over 16 blocks 14.0 (layer 9.4) — camera [-167.5,103.88,-679.5]
  - type 3: 16.0% of cells; notableId nether_quartz_ore; height 109; neighbour step 0.1 (layer 0.5); relief over 16 blocks 4.9 (layer 9.4); notable-block density (log) 0.1 (layer 0.5) — camera [-215.5,104.88,200.5]
  - type 4: 12.3% of cells; notableId glowstone; height 113; neighbour step 0.7 (layer 0.5) — camera [-551.5,108.88,-647.5]
  - type 5: 10.3% of cells; notableId nether_gold_ore; height 108 — camera [-263.5,100.88,456.5]
  - type 6: 9.9% of cells; notableId blackstone; height 113; neighbour step 0.8 (layer 0.5); relief over 16 blocks 12.3 (layer 9.4); notable-block density (log) 0.9 (layer 0.5) — camera [536.5,115.88,-503.5]
  - type 7: 8.2% of cells; notableId weeping_vines_plant; height 111; neighbour step 1.0 (layer 0.5); relief over 16 blocks 15.1 (layer 9.4); trees per cell 3.4 (layer 0.3) — camera [101.5,97.88,-504.5]
  - type 8: 6.6% of cells; notableId smooth_basalt; height 110; neighbour step 0.2 (layer 0.5) — camera [552.5,104.88,24.5]
  - landmark at [-312,840]: notableId weeping_vines (type weeping_vines_plant); neighbour step 2.1 (type 0.8); trees per cell 2.0 (type 4.0); notable-block density (log) 0.3 (type 0.6)
  - landmark at [120,-504]: notableId weeping_vines_plant (type blackstone); neighbour step 1.8 (type 0.8); trees per cell 1.0 (type 0.0); height 115.7 (type 113.7)
  - landmark at [392,696]: notableId soul_soil (type blackstone); notable-block density (log) 3.5 (type 0.9); height 108.4 (type 111.2); relief over 16 blocks 1.0 (type 3.0)
  - landmark at [680,744]: notableId soul_sand (type nether_gold_ore); notable-block density (log) 2.1 (type 0.0); relief over 16 blocks 16.0 (type 11.0); height 106.7 (type 107.1)
  - landmark at [680,728]: notableId soul_soil (type nether_gold_ore); notable-block density (log) 2.9 (type 0.0); relief over 16 blocks 9.0 (type 11.0); neighbour step 0.3 (type 0.5)

## Biome expression

3 of 7 placed biomes are expressed.

- minecraft:basalt_deltas: expressed, 20243 columns; placement land 51.8%, lava 48.1%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top lava 48.1%, basalt 44.1%, magma_block 3.4%
- adventure:ash_barrens: placed, not expressed, 10227 columns; placement land 16.8%, lava 83.2%, roofed 100%; snow 0%, canopy 0.8%, trees 0/ha; top lava 83.2%, tuff 15.1%, netherrack 0.7%; **mostly lava**
- adventure:quartz_flats: placed, not expressed, 9911 columns; placement land 45.7%, lava 53.7%, roofed 99.4%, sky 0.6%; snow 0%, canopy 0.7%, trees 4.4/ha; top lava 53.7%, quartz_block 28%, glowstone 9%; **mostly lava**
- minecraft:crimson_forest: placed, not expressed, 7242 columns; placement land 37.1%, lava 62.5%, roofed 99.6%, sky 0.4%; snow 0%, canopy 127.6%, trees 599/ha; top lava 62.5%, nether_wart_block 33.1%, netherrack 2.1%; **mostly lava**
- adventure:upside_down_forest: expressed, 5583 columns; placement land 63.8%, lava 17.9%, roofed 81.7%, sky 18.3%; snow 0%, canopy 91.7%, trees 311.7/ha; top nether_wart_block 57%, lava 17.9%, netherrack 3.9%
- minecraft:nether_wastes: placed, not expressed, 5104 columns; placement land 9.2%, lava 90.5%, roofed 99.7%, sky 0.3%; snow 0%, canopy 42.3%, trees 0/ha; top lava 90.5%, netherrack 4.1%, nether_wart_block 3.8%; **mostly lava**
- minecraft:soul_sand_valley: expressed, 5041 columns; placement land 53.8%, lava 46.2%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top lava 46.2%, soul_soil 25.5%, soul_sand 21.1%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 8.1 | 5041 | expressed | - |
| O2 | betternether:old_swampland | minecraft:soul_sand_valley | 8.1 | 5041 | expressed | - |
| O3 | betternether:flooded_deltas | minecraft:basalt_deltas | 32.5 | 20243 | expressed | - |
| O4 | betternether:upside_down_forest | adventure:upside_down_forest | 7.3 | 5583 | expressed | - |
| O5 | betternether:upside_down_forest_cleared | minecraft:crimson_forest | 11.6 | 7242 | placed, not expressed | mostly lava |
| O6 | minecraft:basalt_deltas | minecraft:basalt_deltas | 32.5 | 20243 | expressed | - |
| O7 | regions_unexplored:ancient_delta | adventure:ash_barrens | 16.4 | 10227 | placed, not expressed | mostly lava |
| O8 | minecraft:nether_wastes | minecraft:nether_wastes | 8.2 | 5104 | placed, not expressed | mostly lava |
| O9 | incendium:quartz_flats | adventure:quartz_flats | 15.8 | 9911 | placed, not expressed | mostly lava |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:basalt_deltas 32.5%, adventure:ash_barrens 16.4%, adventure:quartz_flats 15.8% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultFluid": "minecraft:lava", "seaLevel": 74}` |
| M:lava | 58.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 11.2% (`surface.canopyPercent`) | |
| M:relief | median 67.0 blocks a patch; height {"p5": 57.0, "p25": 64.0, "p50": 69.0, "p75": 76.0, "p95": 106.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 1.8, "surface": 100.0, "underground": 99.9, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#3B2B1B", "ambientLight": 0.1}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 55.6%
- S:c — v2 biomes placed but not expressed: 4
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
