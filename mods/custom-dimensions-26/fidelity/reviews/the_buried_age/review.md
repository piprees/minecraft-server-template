# Review packet: the_buried_age

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:soul_sand_valley, betternether:old_swampland, betternether:flooded_deltas, betternether:upside_down_forest, betternether:upside_down_forest_cleared, minecraft:basalt_deltas, regions_unexplored:ancient_delta, minecraft:nether_wastes, incendium:quartz_flats

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_buried_age.json`

> An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too.

Biomes: minecraft:basalt_deltas, adventure:quartz_flats, minecraft:soul_sand_valley, minecraft:nether_wastes, adventure:upside_down_forest, minecraft:crimson_forest, adventure:ash_barrens

Terrain: `{"base": "roofed", "params": {"floorHeight": 69, "floorRelief": 16, "ceilingGap": 40, "roughness": 0.3, "ledges": 0.4, "ledgeStep": 6}, "scales": {"climate": 4.0}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/c5/full/the_buried_age/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c5/full/the_buried_age/layers`

## Summary

- Top blocks seen from the sky: lava 60.9%, basalt 14.5%, netherrack 9.6%, tuff 3.1%.
- Surface biomes: basalt_deltas 74.6%, nether_wastes 9.6%, adventure:quartz_flats 5%, adventure:ash_barrens 3.7%, soul_sand_valley 3.6%.
- Ground blocks: basalt 65.2%, netherrack 12.8%, blackstone 8.7%, tuff 3.1%.
- Liquid cover 0% water, 60.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 2.2%; bare rock 28.4%; no ground (void) 0%.
- Trees: 9.5 per ha (crimson_stem 9.5/ha, mean spacing 3.8 blocks); ground plants grass 0.4%, mushroom 0.2%, other 0.1%.
- Surface height p5 57, median 71, p95 107, max 127; median relief per patch 68 blocks; slopes under 15° 22.1%, over 45° 46.1%, cliffs 12.4%; above the clouds 0%, below sea level 64.1%.
- Underground: 2.5% open, 5.7% liquid; cave biomes basalt_deltas 70.2%, nether_wastes 13.9%, adventure:quartz_flats 7.8%, adventure:upside_down_forest 3.1%.
- Notable clusters, fewest first: crimson_nylium 6 clusters, spacing 7.9; quartz_block 20 clusters, spacing 10.5; fire 34 clusters, spacing 4.3; quartz_pillar 36 clusters, spacing 4.9; bone_block 37 clusters, spacing 5.3; smooth_basalt 47 clusters, spacing 3.7.
- 7 of 7 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1008 columns: height within 2 blocks 55.5%, biome match 100%.
- Landforms over the border (noise render): plains 10.3%, hills 3.5%, mountains 0%, plateaus 0%, basins 0%, islands 10.4%, water 75.8%, void 0%.
- Placeness: biome regions 6.2/km² (7 major, largest 78.0%), landform regions 5.7/km² (largest 75.5%), dominant place 78.0% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.4%, surface 100%, underground 99.9%, roof 100%.
- Overhangs in 12.3% of columns, stacked layers in 0.4%, cave pockets in 15.7%.

## Places

- **surface** (252 cells, 2 types, silhouette 0.444):
  - type 1: 81.7% of cells; biome basalt_deltas; ground basalt; top lava; height 70 — camera [-663.5,74,-631.5]
  - type 2: 18.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 79; liquid cover 7% (layer 61%); bare rock 74% (layer 28%); stacked layers 2% (layer 0%) — camera [377.5,75,776.5]
  - landmark at [216,664]: biome adventure:upside_down_forest (type nether_wastes); top nether_wart_block (type netherrack); canopy 64% (type 0%); stacked layers 12% (type 0%); cave pockets 88% (type 8%)
  - landmark at [600,184]: biome crimson_forest (type basalt_deltas); ground crimson_nylium (type basalt); top nether_wart_block (type lava); ground plants 22% (type 0%); trees per cell 5.0 (type 0.0); canopy 44% (type 0%)
  - landmark at [600,168]: biome crimson_forest (type basalt_deltas); ground crimson_nylium (type basalt); top nether_wart_block (type lava); trees per cell 7.0 (type 0.0); canopy 53% (type 0%); ground plants 13% (type 0%)
  - landmark at [-136,-136]: biome adventure:quartz_flats (type basalt_deltas); ground quartz_block (type basalt); top quartz_block (type lava); overhangs 40% (type 0%); neighbour step 6.3 (type 1.3); liquid cover 34% (type 93%)
  - landmark at [616,184]: biome crimson_forest (type basalt_deltas); ground crimson_nylium (type basalt); top nether_wart_block (type lava); ground plants 34% (type 0%); trees per cell 7.0 (type 0.0); canopy 59% (type 0%)
- **sky**: no cells.
- **underground** (252 cells, 2 types, silhouette 0.351):
  - type 1: 66.3% of cells; notableId blackstone; biome nether_wastes; height 70; cave air 0% (layer 2%) — camera [-662.5,72.88,-637.5]
  - type 2: 33.7% of cells; notableId blackstone; biome basalt_deltas; height 76; neighbour step 4.7 (layer 2.8); relief over 16 blocks 58.3 (layer 42.6); cave air 6% (layer 2%) — camera [-558.5,81.88,-651.5]
  - landmark at [344,776]: notableId lava (type blackstone); biome nether_wastes (type basalt_deltas); cave liquid 33% (type 4%); notable-block density (log) 4.1 (type 1.3); relief over 16 blocks 101.0 (type 47.0)
  - landmark at [-328,808]: notableId lava (type blackstone); cave air 16% (type 3%); cave liquid 29% (type 4%); notable-block density (log) 4.1 (type 1.3)
  - landmark at [216,664]: biome adventure:upside_down_forest (type basalt_deltas); cave liquid 48% (type 4%); cave pockets 88% (type 21%); relief over 16 blocks 104.0 (type 47.0)
  - landmark at [232,680]: notableId lava (type blackstone); cave liquid 41% (type 4%); cave pockets 96% (type 21%); notable-block density (log) 5.3 (type 1.3)
  - landmark at [232,664]: notableId magma_block (type blackstone); biome adventure:upside_down_forest (type basalt_deltas); cave liquid 47% (type 4%); cave pockets 82% (type 21%); cave air 8% (type 3%)
- **roof** (252 cells, 2 types, silhouette 0.358):
  - type 1: 67.1% of cells; notableId blackstone; height 110 — camera [568.5,102.88,424.5]
  - type 2: 32.9% of cells; notableId nether_quartz_ore; height 110; notable-block density (log) 0.1 (layer 0.7) — camera [376.5,103.88,696.5]
  - landmark at [-984,632]: height 117.2 (type 108.6); neighbour step 1.3 (type 0.3); relief over 16 blocks 18.0 (type 10.0)
  - landmark at [-296,840]: notableId nether_gold_ore (type nether_quartz_ore); neighbour step 1.4 (type 0.3); relief over 16 blocks 20.0 (type 10.0); height 103.1 (type 108.6)
  - landmark at [-968,648]: notableId basalt (type blackstone); notable-block density (log) 2.0 (type 0.9); neighbour step 1.1 (type 0.4); height 115.4 (type 110.3)
  - landmark at [376,-1000]: notableId smooth_basalt (type blackstone); height 101.5 (type 110.3); notable-block density (log) 2.0 (type 0.9); relief over 16 blocks 17.0 (type 11.0)
  - landmark at [-1000,648]: notableId basalt (type blackstone); notable-block density (log) 2.1 (type 0.9); relief over 16 blocks 17.0 (type 11.0); height 107.4 (type 110.3)

## Biome expression

7 of 7 placed biomes are expressed.

- minecraft:basalt_deltas: expressed, 48131 columns; placement land 21.1%, lava 78.8%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0.6%, trees 0/ha; top lava 78.8%, basalt 18.2%, magma_block 1.1%
- minecraft:nether_wastes: expressed, 6211 columns; placement land 93.1%, lava 6.6%, roofed 99.7%, sky 0.3%; snow 0%, canopy 1.7%, trees 3.5/ha; top netherrack 85.2%, lava 6.6%, basalt 2.1%
- adventure:quartz_flats: expressed, 3249 columns; placement land 80.6%, lava 18.9%, roofed 99.5%, sky 0.5%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 52.9%, lava 18.9%, basalt 8.9%
- adventure:ash_barrens: expressed, 2400 columns; placement land 97.6%, lava 2.4%, roofed 100%; snow 0%, canopy 0.3%, trees 0/ha; top tuff 83.8%, netherrack 4.9%, basalt 3.1%
- minecraft:soul_sand_valley: expressed, 2304 columns; placement land 97.1%, lava 2.9%, roofed 99.9%; snow 0%, canopy 0%, trees 0/ha; top soul_soil 51.1%, soul_sand 37.1%, netherrack 5.6%
- minecraft:crimson_forest: expressed, 1426 columns; placement land 89.3%, lava 10.5%, roofed 99.9%, sky 0.1%; snow 0%, canopy 54.5%, trees 235.5/ha; top nether_wart_block 48.7%, crimson_nylium 26.4%, lava 10.5%
- adventure:upside_down_forest: expressed, 1021 columns; placement land 76%, lava 8.6%, roofed 84.6%, sky 15.4%; snow 0%, canopy 75.5%, trees 270.6/ha; top nether_wart_block 56.4%, netherrack 15.4%, lava 8.6%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 3.6 | 2304 | expressed | - |
| O2 | betternether:old_swampland | minecraft:soul_sand_valley | 3.6 | 2304 | expressed | - |
| O3 | betternether:flooded_deltas | minecraft:basalt_deltas | 74.6 | 48131 | expressed | - |
| O4 | betternether:upside_down_forest | adventure:upside_down_forest | 1.3 | 1021 | expressed | - |
| O5 | betternether:upside_down_forest_cleared | minecraft:crimson_forest | 2.2 | 1426 | expressed | - |
| O6 | minecraft:basalt_deltas | minecraft:basalt_deltas | 74.6 | 48131 | expressed | - |
| O7 | regions_unexplored:ancient_delta | adventure:ash_barrens | 3.7 | 2400 | expressed | - |
| O8 | minecraft:nether_wastes | minecraft:nether_wastes | 9.6 | 6211 | expressed | - |
| O9 | incendium:quartz_flats | adventure:quartz_flats | 5.0 | 3249 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:basalt_deltas 74.6%, minecraft:nether_wastes 9.6%, adventure:quartz_flats 5.0% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultFluid": "minecraft:lava", "seaLevel": 74}` |
| M:lava | 60.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 2.2% (`surface.canopyPercent`) | |
| M:relief | median 68.0 blocks a patch; height {"p5": 57.0, "p25": 66.0, "p50": 71.0, "p75": 75.0, "p95": 107.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.4, "surface": 100.0, "underground": 99.9, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#3B2B1B", "ambientLight": 0.1}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_buried_age-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 3.5%; near: soul_sand 23.3%, soul_soil 12.2%; mid: soul_sand 20.9%, soul_soil 14.6%; far: basalt 8.6%, soul_soil 1.6%; biomes: soul_sand_valley 89.3%, basalt_deltas 9.5%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-far.png`

### the_buried_age-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 3.5%; near: soul_sand 23.3%, soul_soil 12.2%; mid: soul_sand 20.9%, soul_soil 14.6%; far: basalt 8.6%, soul_soil 1.6%; biomes: soul_sand_valley 89.3%, basalt_deltas 9.5%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-far-nv.png`

### the_buried_age-surface-t1

Picked for: surface type 1: 81.7% of cells; biome basalt_deltas; ground basalt; top lava; height 70
Census: sky 0.2%; unloaded 0.4%; near: lava 47.4%, basalt 15.5%; mid: basalt 6.7%, soul_sand 4.6%; far: basalt 10.3%; biomes: basalt_deltas 80.7%, soul_sand_valley 12.5%, adventure:quartz_flats 6.8%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t1.png`

### the_buried_age-surface-t1-nv

Picked for: surface type 1: 81.7% of cells; biome basalt_deltas; ground basalt; top lava; height 70
Census: sky 0.2%; unloaded 0.4%; near: lava 47.4%, basalt 15.5%; mid: basalt 6.7%, soul_sand 4.6%; far: basalt 10.3%; biomes: basalt_deltas 80.7%, soul_sand_valley 12.5%, adventure:quartz_flats 6.8%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t1-nv.png`

### the_buried_age-roof-t1

Picked for: roof type 1: 67.1% of cells; notableId blackstone; height 110
Census: sky 0%; unloaded 6.8%; near: basalt 70.6%; mid: basalt 14.9%, weeping_vines_plant 1%; far: basalt 3.1%; biomes: basalt_deltas 97.2%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-roof-t1.png`

### the_buried_age-roof-t1-nv

Picked for: roof type 1: 67.1% of cells; notableId blackstone; height 110
Census: sky 0%; unloaded 6.6%; near: basalt 70.6%; mid: basalt 14.9%, weeping_vines_plant 1%; far: basalt 3.3%; biomes: basalt_deltas 97.2%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-roof-t1-nv.png`

### the_buried_age-surface-t2

Picked for: surface type 2: 18.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 79; liquid cover 7% (layer 61%); bare rock 74% (layer 28%); stacked layers 2% (layer 0%)
Census: sky 0%; unloaded 2.5%; near: netherrack 54.3%, lava 4.2%; mid: basalt 12.4%, netherrack 7.7%; far: basalt 8.5%, weeping_vines_plant 2.4%; biomes: nether_wastes 71.2%, basalt_deltas 25.9%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t2.png`

### the_buried_age-surface-t2-nv

Picked for: surface type 2: 18.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 79; liquid cover 7% (layer 61%); bare rock 74% (layer 28%); stacked layers 2% (layer 0%)
Census: sky 0%; unloaded 2.2%; near: netherrack 54.3%, lava 4.2%; mid: basalt 12.4%, netherrack 7.7%; far: basalt 8.7%, weeping_vines_plant 2.4%; biomes: nether_wastes 71%, basalt_deltas 26.1%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-t2-nv.png`

### the_buried_age-underground-t2

Picked for: underground type 2: 33.7% of cells; notableId blackstone; biome basalt_deltas; height 76; neighbour step 4.7 (layer 2.8); relief over 16 blocks 58.3 (layer 42.6); cave air 6% (layer 2%)
Census: sky 3.9%; near: basalt 19.5%, quartz_block 13%; mid: basalt 23.9%, lava 2.1%; far: basalt 14.5%, glowstone 2.1%; biomes: basalt_deltas 59.4%, adventure:quartz_flats 38.6%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-underground-t2.png`

### the_buried_age-underground-t2-nv

Picked for: underground type 2: 33.7% of cells; notableId blackstone; biome basalt_deltas; height 76; neighbour step 4.7 (layer 2.8); relief over 16 blocks 58.3 (layer 42.6); cave air 6% (layer 2%)
Census: sky 3.9%; near: basalt 19.5%, quartz_block 13%; mid: basalt 23.9%, lava 2.1%; far: basalt 14.5%, glowstone 2.1%; biomes: basalt_deltas 59.4%, adventure:quartz_flats 38.6%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-underground-t2-nv.png`

### the_buried_age-roof-t2

Picked for: roof type 2: 32.9% of cells; notableId nether_quartz_ore; height 110; notable-block density (log) 0.1 (layer 0.7)
Census: sky 0%; near: netherrack 67.2%, nether_wart_block 13.2%; biomes: nether_wastes 89.4%, adventure:upside_down_forest 10.6%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-roof-t2.png`

### the_buried_age-roof-t2-nv

Picked for: roof type 2: 32.9% of cells; notableId nether_quartz_ore; height 110; notable-block density (log) 0.1 (layer 0.7)
Census: sky 0%; near: netherrack 67.2%, nether_wart_block 13.2%; biomes: nether_wastes 89.4%, adventure:upside_down_forest 10.6%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-roof-t2-nv.png`

### the_buried_age-surface-lm1

Picked for: surface landmark: biome adventure:upside_down_forest (type nether_wastes); top nether_wart_block (type netherrack); canopy 64% (type 0%); stacked layers 12% (type 0%); cave pockets 88% (type 8%)
Census: sky 0.3%; near: lava 27.2%, basalt 22%; mid: basalt 9.9%, netherrack 5.7%; far: basalt 4.3%, netherrack 1.9%; biomes: adventure:upside_down_forest 46.8%, basalt_deltas 43.4%, crimson_forest 9.8%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-lm1.png`

### the_buried_age-surface-lm1-nv

Picked for: surface landmark: biome adventure:upside_down_forest (type nether_wastes); top nether_wart_block (type netherrack); canopy 64% (type 0%); stacked layers 12% (type 0%); cave pockets 88% (type 8%)
Census: sky 0.3%; near: lava 27.2%, basalt 22%; mid: basalt 9.9%, netherrack 5.7%; far: basalt 4.3%, netherrack 1.9%; biomes: adventure:upside_down_forest 46.8%, basalt_deltas 43.4%, crimson_forest 9.8%
Image: `/tmp/claude-0/w10c2/reviews/the_buried_age/screens/the_buried_age-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
