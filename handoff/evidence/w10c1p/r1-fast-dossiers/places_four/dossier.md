# Survey of adventure:places_four

3 patches, 768 columns (0.1 ha) of generated chunks, seed 20261025.

## Summary

- Top blocks seen from the sky: sand 32.7%, oak_leaves 28.9%, grass_block 22.5%, leaf_litter 10.2%.
- Surface biomes: forest 66.7%, desert 33.3%.
- Ground blocks: grass_block 65.1%, sand 33.1%, dirt 1.6%, stone 0.1%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 32.9%; bare rock 0.1%; no ground (void) 0%.
- Trees: 169.3 per ha (oak_log 143.2/ha, mean spacing 4.5 blocks, birch_log 26/ha, mean spacing n/a blocks); ground plants other 34.4%, grass 2.5%, bush 0.1%.
- Surface height p5 68, median 117, p95 125, max 126; median relief per patch 7 blocks; slopes under 15° 46.4%, over 45° 6.3%, cliffs 0.9%; above the clouds 0%, below sea level 0.5%.
- Underground: 9% open, 0.3% liquid; cave biomes forest 57.9%, desert 42.1%.
- Notable clusters, fewest first: deepslate_copper_ore 2 clusters, spacing n/a; gold_ore 2 clusters, spacing n/a; lava 4 clusters, spacing 7; lapis_ore 4 clusters, spacing 9.5; redstone_ore 4 clusters, spacing 6.1; glow_lichen 4 clusters, spacing 4.7.
- 2 of 2 placed biomes are expressed; 2 listed biomes were not found.
- Render against world at 12 columns: height within 2 blocks 100%, biome match 100%.
- Landforms over the border (noise render): plains 29%, hills 18.5%, mountains 5.3%, plateaus 2.4%, basins 3.6%, islands 4.7%, water 36.5%, void 0%.
- Placeness: biome regions 2.4/km² (8 major, largest 25.4%), landform regions 14.3/km² (largest 21.8%), dominant place 25.4% of the border.

## Layers

- Columns by class: void 0%, roofed 0%, stacked 0%, overhang 47.8%, cave 46%, single 6.3%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 0%.
- Overhangs in 47.8% of columns, stacked layers in 0%, cave pockets in 84%.

## Places

- **surface** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome forest; ground grass_block; top sand; height 103 — camera [-953.5,124,-263.5]
- **sky**: no cells.
- **underground** (3 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; notableId coal_ore; biome forest; height 103 — camera [-945.5,-35.12,-267.5]
- **roof**: no cells.

## Biome expression

2 of 2 placed biomes are expressed.

- minecraft:forest: expressed, 512 columns; placement land 100%; snow 0%, canopy 49.4%, trees 234.4/ha; top oak_leaves 43.4%, grass_block 33.8%, leaf_litter 15.2%
- minecraft:desert: expressed, 256 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top sand 98%, tall_dry_grass 0.4%, stone 0.4%

Listed in the definition, found in no sampled column: minecraft:plains, minecraft:snowy_plains.

## Notable clusters

- minecraft:deepslate_copper_ore: 2 clusters in 2 patches, median 4 blocks (max 7), spacing n/a blocks; blocks by layer underground 11
- minecraft:gold_ore: 2 clusters in 2 patches, median 4 blocks (max 6), spacing n/a blocks; blocks by layer underground 10
- minecraft:lava: 4 clusters in 2 patches, median 49 blocks (max 187), spacing 7 blocks; blocks by layer underground 317
- minecraft:lapis_ore: 4 clusters in 2 patches, median 3 blocks (max 6), spacing 9.5 blocks; blocks by layer underground 15
- minecraft:redstone_ore: 4 clusters in 3 patches, median 2 blocks (max 6), spacing 6.1 blocks; blocks by layer underground 11
- minecraft:glow_lichen: 4 clusters in 2 patches, median 1 blocks (max 1), spacing 4.7 blocks; blocks by layer underground 4
- minecraft:leaf_litter: 12 clusters in 2 patches, median 2 blocks (max 136), spacing 5.2 blocks; blocks by layer surface 264
- minecraft:deepslate_gold_ore: 14 clusters in 3 patches, median 6 blocks (max 10), spacing 5 blocks; blocks by layer underground 82
- minecraft:deepslate_lapis_ore: 16 clusters in 3 patches, median 4 blocks (max 8), spacing 5.3 blocks; blocks by layer underground 71
- minecraft:deepslate_diamond_ore: 20 clusters in 3 patches, median 3 blocks (max 8), spacing 4.2 blocks; blocks by layer underground 69
- minecraft:deepslate_redstone_ore: 25 clusters in 3 patches, median 3 blocks (max 8), spacing 4 blocks; blocks by layer underground 98
- minecraft:deepslate_iron_ore: 28 clusters in 3 patches, median 4 blocks (max 10), spacing 2.7 blocks; blocks by layer underground 108
- minecraft:iron_ore: 37 clusters in 3 patches, median 4 blocks (max 10), spacing 2 blocks; blocks by layer underground 168
- minecraft:coal_ore: 40 clusters in 3 patches, median 18 blocks (max 50), spacing 2.7 blocks; blocks by layer surface 4, underground 659
- minecraft:copper_ore: 41 clusters in 3 patches, median 8 blocks (max 26), spacing 2.3 blocks; blocks by layer underground 312

## Vertical profile (16-block bands)

| y | solid % | open % | sky-exposed % | liquid % |
|---|---|---|---|---|
| -64 | 95 | 2.4 | 0 | 2.6 |
| -48 | 94 | 6 | 0 | 0 |
| -32 | 93.3 | 6.7 | 0 | 0 |
| -16 | 98.2 | 1.8 | 0 | 0 |
| 0 | 89.2 | 10.8 | 0 | 0 |
| 16 | 91 | 9 | 0 | 0 |
| 32 | 83.7 | 16.3 | 0 | 0 |
| 48 | 71.9 | 28.1 | 0.2 | 0 |
| 64 | 78.8 | 21.2 | 20.3 | 0 |
| 80 | 65.3 | 34.7 | 33.3 | 0 |
| 96 | 59.7 | 40.3 | 33.3 | 0 |
| 112 | 41 | 59 | 54.1 | 0 |
| 128 | 0.8 | 99.2 | 99 | 0 |

(Bands entirely open sky are left out.)

## Layer maps

- layout: "patches tiled in plan order, 2 px a block, 2 px gaps; the legend is in the dossier"
- top.png: "top surface, black 66.0 to white 126.0"
- second.png: "second surface below the top, same scale; black where none"
- classes.png: "void black, roofed violet, stacked orange, overhang red, cave blue, single green"
- biomes.png: "surface biome in the render's palette"
- slices: ["slice_31.png","slice_59.png","slice_77.png","slice_138.png"]
- slicesLegend: "air white, terrain grey, liquid blue, plants light green, wood and leaves dark green"
- landforms.png: "one pixel a coarse cell (8 blocks): plains, hills, mountains, plateaus, basins, islands, water, void in that order of colour #9acd6a #c8b45a #8c6e50 #d2a46e #5a8c8c #e6d27a #2f6fd6 #000000"
