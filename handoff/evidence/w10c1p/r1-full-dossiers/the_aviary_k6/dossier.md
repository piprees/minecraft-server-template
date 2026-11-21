# Survey of adventure:the_aviary_k6

29 patches, 66816 columns (6.7 ha) of generated chunks, seed 7826077224469180302.

## Summary

- Top blocks seen from the sky: grass_block 22.4%, snow_block 12.3%, oak_leaves 4.5%, short_grass 2.4%.
- Surface biomes: forest 7.4%, adventure:cloud_reef 7.1%, adventure:wisteria_grove 7%, snowy_slopes 6.9%, meadow 6.9%.
- Ground blocks: grass_block 40.7%, snow_block 13.2%, moss_block 1.5%, podzol 0.4%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 13.4% (a snowing climate over 6.9%); tree canopy 10.9%; bare rock 0.1%; no ground (void) 42%.
- Trees: 53.1 per ha (oak_log 26.6/ha, mean spacing 6.3 blocks, birch_log 13/ha, mean spacing 5.3 blocks, dark_oak_log 9.1/ha, mean spacing 5.6 blocks, cherry_log 3.7/ha, mean spacing 9.8 blocks); ground plants other 4.3%, flower 4.2%, grass 3.2%, bush 0%.
- Surface height p5 134, median 175, p95 236, max 254; median relief per patch 18 blocks; slopes under 15° 35.7%, over 45° 6.6%, cliffs 0.6%; above the clouds 35.7%, below sea level 0%.
- Underground: 9.6% open, 0% liquid; cave biomes adventure:hanging_gardens 95.7%, cherry_grove 0.7%, adventure:cloud_reef 0.6%, birch_forest 0.5%.
- Notable clusters, fewest first: poppy 3 clusters, spacing 8.1; powder_snow 3 clusters, spacing 15.2; dead_bubble_coral_fan 5 clusters, spacing 26.4; bee_nest 8 clusters, spacing 14.3; dead_brain_coral 8 clusters, spacing 21.5; dead_horn_coral 9 clusters, spacing 11.3.
- 12 of 12 placed biomes are expressed; 2 listed biomes were not found.
- Render against world at 605 columns: height within 2 blocks 98.8%, biome match 100%.
- Landforms over the border (noise render): plains 20.3%, hills 20.1%, mountains 4.5%, plateaus 3.4%, basins 5.4%, islands 5.2%, water 0%, void 41.1%.
- Placeness: biome regions 2.5/km² (12 major, largest 6.2%), landform regions 14.2/km² (largest 6.7%), dominant place 6.7% of the border.

## Layers

- Columns by class: void 42%, roofed 0%, stacked 10.4%, overhang 2.6%, cave 10.4%, single 34.6%.
- Columns with each layer: sky 10.4%, surface 58%, underground 52.9%, roof 0%.
- Overhangs in 3% of columns, stacked layers in 10.4%, cave pockets in 13.4%.

## Places

- **surface** (160 cells, 2 types, silhouette 0.386):
  - type 1: 76.3% of cells; biome cherry_grove; ground grass_block; top grass_block; height 180; snow or ice 0% (layer 23%) — camera [1848.5,215,-4023.5]
  - type 2: 23.8% of cells; biome adventure:cloud_reef; ground snow_block; top snow_block; height 189; relief over the patch 21.2 (layer 34.1); trees per cell 0.0 (layer 1.6); canopy 0% (layer 20%); ground plants 1% (layer 21%); snow or ice 95% (layer 23%); open to the sky 86% (layer 64%) — camera [-3399.5,180,-359.5]
  - landmark at [-3208,1000]: biome dark_forest (type adventure:wisteria_grove); top dark_oak_leaves (type grass_block); neighbour step 2.9 (type 0.2); relief over 16 blocks 75.0 (type 6.0); relief over the patch 92.0 (type 19.0)
- **sky** (29 cells, 5 types, silhouette 0.44):
  - type 1: 34.5% of cells; biome dark_forest; ground grass_block; height 231; layer present in 98% (layer 89%); relief over 16 blocks 15.4 (layer 11.1); trees per cell 5.6 (layer 3.4) — camera [-1751.5,225,1146.5]
  - type 2: 20.7% of cells; biome adventure:cloud_reef; ground snow_block; height 234; trees per cell 0.0 (layer 3.4) — camera [2120.5,230,3192.5]
  - type 3: 20.7% of cells; biome forest; ground grass_block; height 203; layer present in 99% (layer 89%); relief over 16 blocks 7.7 (layer 11.1); trees per cell 5.8 (layer 3.4) — camera [-2790.5,204,3674.5]
  - type 4: 13.8% of cells; biome meadow; ground grass_block; height 204; layer present in 72% (layer 89%); neighbour step 0.7 (layer 0.4); trees per cell 0.3 (layer 3.4) — camera [73.5,206,1674.5]
  - type 5: 10.3% of cells; biome forest; ground grass_block; height 202; layer present in 64% (layer 89%); neighbour step 0.7 (layer 0.4); notable-block density (log) 0.3 (layer 0.7) — camera [-2758.5,195,3673.5]
  - landmark at [-1736,1144]: neighbour step 1.1 (type 0.1); relief over 16 blocks 31.0 (type 16.0); trees per cell 5.0 (type 7.0)
- **underground** (148 cells, 2 types, silhouette 0.342):
  - type 1: 53.4% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 177; cave air 11% (layer 6%); cave pockets 44% (layer 24%) — camera [2136.5,150.88,3182.5]
  - type 2: 46.6% of cells; notableId cave_vines_plant; biome birch_forest; height 188; cave air 0% (layer 6%); cave pockets 0% (layer 24%) — camera [-1147.5,146.88,1809.5]
  - landmark at [216,1240]: notableId rooted_dirt (type cave_vines_plant); biome meadow (type adventure:hanging_gardens); cave pockets 100% (type 45%); height 231.0 (type 179.7); cave air 16% (type 3%)
  - landmark at [248,1208]: notableId rooted_dirt (type cave_vines_plant); biome meadow (type adventure:hanging_gardens); cave air 18% (type 3%); cave pockets 100% (type 45%); height 228.1 (type 179.7)
  - landmark at [-2776,3640]: neighbour step 1.8 (type 0.2); cave air 18% (type 3%); height 127.5 (type 179.7)
  - landmark at [3768,2424]: notableId rooted_dirt (type cave_vines_plant); layer present in 43% (type 98%); notable-block density (log) 0.1 (type 1.0); neighbour step 0.7 (type 0.3)
  - landmark at [-1160,1816]: biome birch_forest (type adventure:hanging_gardens); cave air 45% (type 3%); cave pockets 91% (type 45%); neighbour step 0.5 (type 0.2)
- **roof**: no cells.

## Biome expression

12 of 12 placed biomes are expressed.

- adventure:hanging_gardens: expressed, 22565 columns; placement land 1.1%, void 98.8%; snow 0%, canopy 6.7%, trees 0/ha; top grass_block 1%, oak_leaves 0.1%, birch_leaves 0%
- adventure:cloud_reef: expressed, 8294 columns; placement land 57.4%, void 26.2%, sky 16.5%; snow 91.7%, canopy 0%, trees 0/ha; top snow_block 45.3%, hanging_roots 3.5%, snow 2.3%
- minecraft:forest: expressed, 6970 columns; placement land 70.7%, sky 29.3%; snow 0%, canopy 31.2%, trees 239.6/ha; top grass_block 35.4%, oak_leaves 16.9%, leaf_litter 6.2%
- minecraft:dark_forest: expressed, 6348 columns; placement land 59.2%, sky 40.8%; snow 0%, canopy 23.8%, trees 218.1/ha; top grass_block 35.9%, dark_oak_leaves 12.5%, hanging_roots 5.9%
- minecraft:cherry_grove: expressed, 5512 columns; placement land 83.3%, void 16.7%; snow 0%, canopy 31.1%, trees 54.5/ha; top grass_block 26.7%, cherry_leaves 25.8%, pink_petals 21.1%
- minecraft:meadow: expressed, 5193 columns; placement land 88.7%, sky 11.3%; snow 0%, canopy 1.1%, trees 4.3/ha; top grass_block 64.5%, short_grass 8.6%, wildflowers 4.6%
- adventure:wisteria_grove: expressed, 4696 columns; placement land 100%; snow 0%, canopy 24.4%, trees 91.6/ha; top grass_block 39.1%, vine 22.9%, oak_leaves 14.8%
- minecraft:snowy_slopes: expressed, 4633 columns; placement land 100%; snow 98.5%, canopy 0%, trees 0/ha; top snow_block 95.5%, powder_snow 3%, stone 1.1%
- adventure:sky_highlands: expressed, 4392 columns; placement land 45.1%, void 47.4%, sky 7.5%; snow 0%, canopy 4.9%, trees 25.2/ha; top grass_block 27.6%, short_grass 6.9%, hanging_roots 2.2%
- minecraft:birch_forest: expressed, 2286 columns; placement land 100%; snow 0%, canopy 45.6%, trees 227.5/ha; top grass_block 49.9%, birch_leaves 45.6%, wildflowers 2.5%
- adventure:golden_skygrove: expressed, 2234 columns; placement land 72.9%, void 27.1%; snow 0%, canopy 65.7%, trees 172/ha; top oak_leaves 40.8%, grass_block 18.8%, yellow_poplar_leaves 7.1%
- minecraft:plains: expressed, 651 columns; placement land 94.6%, sky 5.4%; snow 0%, canopy 0%, trees 0/ha; top grass_block 75.9%, short_grass 12%, hanging_roots 3.8%

Listed in the definition, found in no sampled column: minecraft:flower_forest, adventure:windswept_isles.

## Notable clusters

- minecraft:poppy: 3 clusters in 2 patches, median 1 blocks (max 1), spacing 8.1 blocks; blocks by layer sky 2, surface 1
- minecraft:powder_snow: 3 clusters in 2 patches, median 6 blocks (max 132), spacing 15.2 blocks; blocks by layer surface 143
- minecraft:dead_bubble_coral_fan: 5 clusters in 3 patches, median 1 blocks (max 1), spacing 26.4 blocks; blocks by layer surface 5
- minecraft:bee_nest: 8 clusters in 6 patches, median 1 blocks (max 1), spacing 14.3 blocks; blocks by layer surface 8
- minecraft:dead_brain_coral: 8 clusters in 3 patches, median 1 blocks (max 1), spacing 21.5 blocks; blocks by layer sky 3, surface 5
- minecraft:dead_horn_coral: 9 clusters in 2 patches, median 1 blocks (max 1), spacing 11.3 blocks; blocks by layer sky 4, surface 5
- minecraft:dead_tube_coral: 10 clusters in 3 patches, median 1 blocks (max 1), spacing 10.9 blocks; blocks by layer sky 3, surface 7
- minecraft:end_rod: 10 clusters in 3 patches, median 1 blocks (max 2), spacing 14.9 blocks; blocks by layer sky 2, surface 9
- minecraft:dead_horn_coral_fan: 11 clusters in 3 patches, median 1 blocks (max 1), spacing 11.2 blocks; blocks by layer sky 4, surface 7
- minecraft:dead_tube_coral_fan: 12 clusters in 4 patches, median 1 blocks (max 1), spacing 6.2 blocks; blocks by layer sky 5, surface 7
- minecraft:dead_bubble_coral: 12 clusters in 2 patches, median 1 blocks (max 1), spacing 8 blocks; blocks by layer sky 6, surface 6
- minecraft:bush: 13 clusters in 3 patches, median 1 blocks (max 4), spacing 3.2 blocks; blocks by layer sky 1, surface 17
- minecraft:dead_fire_coral_fan: 13 clusters in 3 patches, median 1 blocks (max 1), spacing 8.9 blocks; blocks by layer sky 5, surface 8
- minecraft:dead_brain_coral_fan: 13 clusters in 3 patches, median 1 blocks (max 2), spacing 7.3 blocks; blocks by layer sky 2, surface 12
- minecraft:dead_fire_coral: 14 clusters in 3 patches, median 1 blocks (max 1), spacing 8.8 blocks; blocks by layer sky 3, surface 11
- minecraft:calcite: 20 clusters in 5 patches, median 5 blocks (max 6920), spacing 4.5 blocks; blocks by layer sky 4265, surface 12803
- minecraft:emerald_ore: 22 clusters in 7 patches, median 1 blocks (max 3), spacing 21.3 blocks; blocks by layer sky 1, surface 27, underground 2
- minecraft:snow_block: 23 clusters in 7 patches, median 5 blocks (max 9810), spacing 8.2 blocks; blocks by layer sky 1317, surface 21381, underground 4
- minecraft:dead_tube_coral_block: 34 clusters in 3 patches, median 1 blocks (max 3), spacing 3.7 blocks; blocks by layer sky 7, surface 32
- minecraft:dead_brain_coral_block: 37 clusters in 3 patches, median 1 blocks (max 4), spacing 4.3 blocks; blocks by layer sky 11, surface 43
- minecraft:dead_tube_coral_wall_fan: 40 clusters in 3 patches, median 1 blocks (max 2), spacing 4.3 blocks; blocks by layer sky 11, surface 35
- minecraft:vine: 41 clusters in 5 patches, median 111 blocks (max 1067), spacing 6.9 blocks; blocks by layer sky 4, surface 6415
- minecraft:cornflower: 41 clusters in 3 patches, median 1 blocks (max 12), spacing 4.6 blocks; blocks by layer surface 90
- minecraft:dead_fire_coral_wall_fan: 42 clusters in 3 patches, median 1 blocks (max 3), spacing 3.9 blocks; blocks by layer sky 13, surface 35
- minecraft:dead_horn_coral_wall_fan: 44 clusters in 3 patches, median 1 blocks (max 3), spacing 4.4 blocks; blocks by layer sky 15, surface 36
- minecraft:dead_bubble_coral_wall_fan: 45 clusters in 4 patches, median 1 blocks (max 3), spacing 4 blocks; blocks by layer sky 13, surface 48
- minecraft:dead_fire_coral_block: 45 clusters in 3 patches, median 1 blocks (max 3), spacing 3.7 blocks; blocks by layer sky 11, surface 44
- minecraft:dead_brain_coral_wall_fan: 50 clusters in 3 patches, median 1 blocks (max 3), spacing 3 blocks; blocks by layer sky 23, surface 35
- minecraft:dead_horn_coral_block: 56 clusters in 4 patches, median 1 blocks (max 3), spacing 3 blocks; blocks by layer sky 21, surface 51
- minecraft:dead_bubble_coral_block: 56 clusters in 3 patches, median 1 blocks (max 3), spacing 2.9 blocks; blocks by layer sky 23, surface 46
- minecraft:azure_bluet: 58 clusters in 5 patches, median 1 blocks (max 8), spacing 3.5 blocks; blocks by layer sky 3, surface 76
- minecraft:bone_block: 65 clusters in 3 patches, median 1 blocks (max 11), spacing 2.7 blocks; blocks by layer sky 33, surface 86
- minecraft:dandelion: 99 clusters in 3 patches, median 1 blocks (max 12), spacing 3.1 blocks; blocks by layer sky 35, surface 180
- minecraft:snow: 103 clusters in 4 patches, median 1 blocks (max 15), spacing 3.4 blocks; blocks by layer sky 69, surface 208
- minecraft:lilac: 108 clusters in 3 patches, median 2 blocks (max 16), spacing 3.3 blocks; blocks by layer surface 310
- minecraft:moss_carpet: 115 clusters in 2 patches, median 1 blocks (max 11), spacing 2.9 blocks; blocks by layer surface 267
- minecraft:allium: 125 clusters in 3 patches, median 1 blocks (max 6), spacing 3.5 blocks; blocks by layer surface 166
- minecraft:pink_petals: 180 clusters in 5 patches, median 1 blocks (max 278), spacing 3.3 blocks; blocks by layer surface 1722
- minecraft:leaf_litter: 214 clusters in 6 patches, median 2 blocks (max 930), spacing 3.9 blocks; blocks by layer sky 1974, surface 2123
- minecraft:coal_ore: 234 clusters in 22 patches, median 17 blocks (max 51), spacing 7.5 blocks; blocks by layer sky 545, surface 2759, underground 352
- minecraft:wildflowers: 262 clusters in 3 patches, median 1 blocks (max 10), spacing 2.9 blocks; blocks by layer sky 27, surface 367, underground 1
- minecraft:iron_ore: 294 clusters in 19 patches, median 5 blocks (max 26), spacing 6 blocks; blocks by layer sky 316, surface 1152, underground 137
- minecraft:rooted_dirt: 520 clusters in 25 patches, median 6 blocks (max 717), spacing 4.7 blocks; blocks by layer sky 1666, surface 1544, underground 15796
- minecraft:moss_block: 897 clusters in 25 patches, median 2 blocks (max 504), spacing 3.3 blocks; blocks by layer sky 1556, surface 1833, underground 13054
- minecraft:cave_vines_plant: 1713 clusters in 24 patches, median 17 blocks (max 359), spacing 2.5 blocks; blocks by layer surface 4415, underground 39116
- minecraft:cave_vines: 2864 clusters in 24 patches, median 1 blocks (max 8), spacing 1.6 blocks; blocks by layer surface 316, underground 2913

## Vertical profile (16-block bands)

| y | solid % | open % | sky-exposed % | liquid % |
|---|---|---|---|---|
| -64 | 0 | 100 | 41.6 | 0 |
| -48 | 0 | 100 | 41.6 | 0 |
| -32 | 0 | 100 | 41.6 | 0 |
| -16 | 0 | 100 | 41.6 | 0 |
| 0 | 0 | 100 | 41.6 | 0 |
| 16 | 0 | 100 | 41.6 | 0 |
| 32 | 0 | 100 | 41.6 | 0 |
| 48 | 0.7 | 99.3 | 41.6 | 0 |
| 64 | 4.4 | 95.6 | 41.6 | 0 |
| 80 | 15.4 | 84.6 | 41.7 | 0 |
| 96 | 21.6 | 78.4 | 41.7 | 0 |
| 112 | 26.6 | 73.4 | 42 | 0 |
| 128 | 29.8 | 70.2 | 44.9 | 0 |
| 144 | 23 | 77 | 53.6 | 0 |
| 160 | 19 | 81 | 59.9 | 0 |
| 176 | 14.2 | 85.8 | 66.4 | 0 |
| 192 | 18.9 | 81.1 | 70.5 | 0 |
| 208 | 20.7 | 79.3 | 75.3 | 0 |
| 224 | 11.6 | 88.4 | 86.8 | 0 |
| 240 | 1.5 | 98.5 | 98 | 0 |

(Bands entirely open sky are left out.)

## Layer maps

- layout: "patches tiled in plan order, 2 px a block, 2 px gaps; the legend is in the dossier"
- top.png: "top surface, black 121.0 to white 247.0"
- second.png: "second surface below the top, same scale; black where none"
- classes.png: "void black, roofed violet, stacked orange, overhang red, cave blue, single green"
- biomes.png: "surface biome in the render's palette"
- slices: ["slice_145.png","slice_173.png","slice_191.png","slice_269.png"]
- slicesLegend: "air white, terrain grey, liquid blue, plants light green, wood and leaves dark green"
- landforms.png: "one pixel a coarse cell (32 blocks): plains, hills, mountains, plateaus, basins, islands, water, void in that order of colour #9acd6a #c8b45a #8c6e50 #d2a46e #5a8c8c #e6d27a #2f6fd6 #000000"
