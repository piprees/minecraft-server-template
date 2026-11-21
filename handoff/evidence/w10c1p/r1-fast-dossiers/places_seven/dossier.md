# Survey of adventure:places_seven

3 patches, 768 columns (0.1 ha) of generated chunks, seed 20261025.

## Summary

- Top blocks seen from the sky: grass_block 52.1%, sand 29.2%, short_grass 14.3%, water 4%.
- Surface biomes: plains 66.7%, desert 33.3%.
- Ground blocks: grass_block 66.7%, sand 32.3%, dirt 0.9%, gravel 0.1%.
- Liquid cover 4% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 0%; no ground (void) 0%.
- Trees: 13 per ha (oak_log 13/ha, mean spacing n/a blocks); ground plants grass 14.5%, bush 0.1%, flower 0.1%.
- Surface height p5 64, median 117, p95 125, max 126; median relief per patch 7 blocks; slopes under 15° 46.6%, over 45° 9.5%, cliffs 0.9%; above the clouds 0%, below sea level 4.3%.
- Underground: 7.6% open, 0.6% liquid; cave biomes plains 68.7%, desert 31.3%.
- Notable clusters, fewest first: deepslate_copper_ore 2 clusters, spacing n/a; lava 3 clusters, spacing 3.4; gold_ore 3 clusters, spacing 7.4; redstone_ore 4 clusters, spacing 6.1; lapis_ore 5 clusters, spacing 9.5; glow_lichen 6 clusters, spacing 4.6.
- 2 of 2 placed biomes are expressed; 6 listed biomes were not found.
- Render against world at 12 columns: height within 2 blocks 100%, biome match 100%.
- Landforms over the border (noise render): plains 29%, hills 18.5%, mountains 5.3%, plateaus 2.4%, basins 3.6%, islands 4.7%, water 36.5%, void 0%.
- Placeness: biome regions 3.1/km² (11 major, largest 25.3%), landform regions 14.3/km² (largest 21.8%), dominant place 25.3% of the border.

## Layers

- Columns by class: void 0%, roofed 0%, stacked 0%, overhang 23.6%, cave 69%, single 7.4%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 0%.
- Overhangs in 23.6% of columns, stacked layers in 0%, cave pockets in 91%.

## Places

- **surface** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome plains; ground grass_block; top grass_block; height 102 — camera [-455.5,118,-711.5]
- **sky**: no cells.
- **underground** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; notableId coal_ore; biome plains; height 102 — camera [-945.5,-35.12,-267.5]
- **roof**: no cells.

## Biome expression

2 of 2 placed biomes are expressed.

- minecraft:plains: expressed, 512 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top grass_block 78.1%, short_grass 21.5%, dandelion 0.2%
- minecraft:desert: expressed, 256 columns; placement land 87.9%, water 12.1%; snow 0%, canopy 0%, trees 0/ha; top sand 87.5%, water 12.1%, dead_bush 0.4%

Listed in the definition, found in no sampled column: minecraft:forest, minecraft:birch_forest, minecraft:taiga, minecraft:savanna, minecraft:swamp, minecraft:snowy_plains.

## Notable clusters

- minecraft:deepslate_copper_ore: 2 clusters in 2 patches, median 1 blocks (max 7), spacing n/a blocks; blocks by layer underground 8
- minecraft:lava: 3 clusters in 2 patches, median 49 blocks (max 66), spacing 3.4 blocks; blocks by layer underground 116
- minecraft:gold_ore: 3 clusters in 2 patches, median 6 blocks (max 6), spacing 7.4 blocks; blocks by layer underground 16
- minecraft:redstone_ore: 4 clusters in 3 patches, median 2 blocks (max 6), spacing 6.1 blocks; blocks by layer underground 13
- minecraft:lapis_ore: 5 clusters in 2 patches, median 4 blocks (max 6), spacing 9.5 blocks; blocks by layer underground 21
- minecraft:glow_lichen: 6 clusters in 3 patches, median 1 blocks (max 2), spacing 4.6 blocks; blocks by layer underground 7
- minecraft:deepslate_gold_ore: 10 clusters in 3 patches, median 5 blocks (max 10), spacing 5.2 blocks; blocks by layer underground 54
- minecraft:deepslate_lapis_ore: 12 clusters in 3 patches, median 5 blocks (max 8), spacing 6 blocks; blocks by layer underground 61
- minecraft:deepslate_diamond_ore: 19 clusters in 3 patches, median 3 blocks (max 8), spacing 3.8 blocks; blocks by layer underground 70
- minecraft:deepslate_iron_ore: 19 clusters in 3 patches, median 4 blocks (max 10), spacing 3.1 blocks; blocks by layer underground 81
- minecraft:deepslate_redstone_ore: 23 clusters in 3 patches, median 4 blocks (max 8), spacing 4.3 blocks; blocks by layer underground 89
- minecraft:coal_ore: 42 clusters in 3 patches, median 18 blocks (max 50), spacing 2.6 blocks; blocks by layer surface 4, underground 702
- minecraft:iron_ore: 44 clusters in 3 patches, median 4 blocks (max 13), spacing 2.1 blocks; blocks by layer underground 207
- minecraft:copper_ore: 47 clusters in 3 patches, median 8 blocks (max 26), spacing 2 blocks; blocks by layer underground 349

## Vertical profile (16-block bands)

| y | solid % | open % | sky-exposed % | liquid % |
|---|---|---|---|---|
| -64 | 98.2 | 0.8 | 0 | 0.9 |
| -48 | 94.4 | 5.6 | 0 | 0 |
| -32 | 90.7 | 9.3 | 0 | 0 |
| -16 | 78.2 | 21.8 | 0 | 0 |
| 0 | 87.6 | 12.4 | 0 | 0 |
| 16 | 91.3 | 8.6 | 0 | 0.1 |
| 32 | 95.3 | 1.4 | 0 | 3.2 |
| 48 | 89.8 | 6.2 | 0.3 | 4 |
| 64 | 73.8 | 26.2 | 25.3 | 0 |
| 80 | 65.3 | 34.7 | 33.3 | 0 |
| 96 | 59.7 | 40.3 | 33.3 | 0 |
| 112 | 35.9 | 64.1 | 64.1 | 0 |

(Bands entirely open sky are left out.)

## Layer maps

- layout: "patches tiled in plan order, 2 px a block, 2 px gaps; the legend is in the dossier"
- top.png: "top surface, black 52.0 to white 126.0"
- second.png: "second surface below the top, same scale; black where none"
- classes.png: "void black, roofed violet, stacked orange, overhang red, cave blue, single green"
- biomes.png: "surface biome in the render's palette"
- slices: ["slice_31.png","slice_59.png","slice_77.png","slice_138.png"]
- slicesLegend: "air white, terrain grey, liquid blue, plants light green, wood and leaves dark green"
- landforms.png: "one pixel a coarse cell (8 blocks): plains, hills, mountains, plateaus, basins, islands, water, void in that order of colour #9acd6a #c8b45a #8c6e50 #d2a46e #5a8c8c #e6d27a #2f6fd6 #000000"
