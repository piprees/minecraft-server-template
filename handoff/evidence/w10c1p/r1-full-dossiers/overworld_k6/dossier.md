# Survey of adventure:overworld_k6

31 patches, 71424 columns (7.1 ha) of generated chunks, seed 4705305050088502262.

## Summary

- Top blocks seen from the sky: water 40.9%, grass_block 33.2%, short_grass 7%, sand 6.2%.
- Surface biomes: plains 25.9%, meadow 12%, beach 10.1%, deep_ocean 9.8%, forest 7.1%.
- Ground blocks: grass_block 48%, gravel 25.2%, sand 17%, dirt 3.7%.
- Liquid cover 40.9% water, 0% lava, 0% other; snow or ice 3.2% (a snowing climate over 3.3%); tree canopy 5.7%; bare rock 0.2%; no ground (void) 0%.
- Trees: 24.6 per ha (oak_log 12.2/ha, mean spacing 6.1 blocks, birch_log 8.4/ha, mean spacing 5.7 blocks, spruce_log 4.1/ha, mean spacing 4.3 blocks); ground plants grass 7.6%, other 3.3%, flower 1.9%, bush 0.2%.
- Surface height p5 37, median 63, p95 120, max 168; median relief per patch 21 blocks; slopes under 15° 52.7%, over 45° 14%, cliffs 1%; above the clouds 0%, below sea level 46.1%.
- Underground: 7.1% open, 1.6% liquid; cave biomes meadow 35.8%, plains 26.2%, cold_ocean 10.3%, ocean 5.7%.
- Notable clusters, fewest first: sugar_cane 2 clusters, spacing n/a; raw_iron_block 3 clusters, spacing 33.1; firefly_bush 4 clusters, spacing 3.2; deepslate_emerald_ore 5 clusters, spacing 10.9; ice 7 clusters, spacing 13.8; mossy_cobblestone 7 clusters, spacing 11.7.
- 12 of 14 placed biomes are expressed; 13 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 98.7%, biome match 99.9%.
- Landforms over the border (noise render): plains 22.3%, hills 15.4%, mountains 4.1%, plateaus 1%, basins 1.7%, islands 6%, water 49.5%, void 0%.
- Placeness: biome regions 4.9/km² (17 major, largest 10.0%), landform regions 7.8/km² (largest 19.0%), dominant place 19.0% of the border.

## Layers

- Columns by class: void 0%, roofed 0%, stacked 0.1%, overhang 10.9%, cave 55.6%, single 33.3%.
- Columns with each layer: sky 0.1%, surface 100%, underground 100%, roof 0%.
- Overhangs in 10.9% of columns, stacked layers in 0.1%, cave pockets in 62.2%.

## Places

- **surface** (279 cells, 3 types, silhouette 0.444):
  - type 1: 48.4% of cells; biome plains; ground grass_block; top grass_block; height 87; liquid cover 2% (layer 41%) — camera [-1975.5,68,1960.5]
  - type 2: 41.6% of cells; biome deep_ocean; ground gravel; top water; height 47; ground plants 0% (layer 13%); liquid cover 96% (layer 41%) — camera [984.5,63,1160.5]
  - type 3: 10.0% of cells; biome forest; ground grass_block; top oak_leaves; height 67; trees per cell 5.5 (layer 0.6); canopy 49% (layer 6%); ground plants 37% (layer 13%); liquid cover 1% (layer 41%); snow or ice 18% (layer 3%); bare rock 1% (layer 0%); open to the sky 51% (layer 94%) — camera [-1703.5,67,2955.5]
  - landmark at [3928,1240]: biome grove (type forest); ground snow_block (type grass_block); top snow (type oak_leaves); snow or ice 100% (type 0%); ground plants 0% (type 54%); notable-block density (log) 1.6 (type 0.2)
  - landmark at [-3400,1624]: biome birch_forest (type forest); top grass_block (type oak_leaves); bare rock 7% (type 0%); ground plants 5% (type 54%); relief over 16 blocks 37.0 (type 4.0)
  - landmark at [3928,1256]: biome grove (type forest); ground snow_block (type grass_block); top snow (type oak_leaves); snow or ice 100% (type 0%); ground plants 0% (type 54%); notable-block density (log) 1.5 (type 0.2)
  - landmark at [-3416,1624]: biome birch_forest (type plains); overhangs 45% (type 0%); canopy 39% (type 0%); open to the sky 61% (type 100%)
  - landmark at [3944,1256]: biome grove (type plains); ground ice (type grass_block); top ice (type grass_block); snow or ice 100% (type 0%); overhangs 47% (type 0%); ground plants 0% (type 24%)
- **sky**: no cells.
- **underground** (279 cells, 7 types, silhouette 0.19):
  - type 1: 24.4% of cells; notableId coal_ore; biome plains; height 77 — camera [-2004.5,-35.12,1986.5]
  - type 2: 22.2% of cells; notableId copper_ore; biome plains; height 67 — camera [-1975.5,-9.12,1997.5]
  - type 3: 16.8% of cells; notableId iron_ore; biome deep_ocean; height 46; notable-block density (log) 0.7 (layer 1.0); cave pockets 38% (layer 62%) — camera [-551.5,15.88,-1169.5]
  - type 4: 11.8% of cells; notableId coal_ore; biome ocean; height 49; notable-block density (log) 0.8 (layer 1.0); cave pockets 89% (layer 62%) — camera [3752.5,-9.12,-2641.5]
  - type 5: 10.4% of cells; notableId coal_ore; biome meadow; height 122; notable-block density (log) 1.2 (layer 1.0); cave air 17% (layer 6%); cave pockets 87% (layer 62%) — camera [-2157.5,-0.12,-273.5]
  - type 6: 7.9% of cells; notableId lava; biome plains; height 70; notable-block density (log) 1.6 (layer 1.0); cave liquid 3% (layer 2%); cave pockets 89% (layer 62%) — camera [-1437.5,-14.12,-3126.5]
  - type 7: 6.5% of cells; notableId deepslate_redstone_ore; biome river; height 44; neighbour step 1.4 (layer 0.4); relief over 16 blocks 28.8 (layer 9.5); notable-block density (log) 0.7 (layer 1.0); cave liquid 5% (layer 2%) — camera [-1178.5,-50.12,2924.5]
  - landmark at [3208,3944]: notableId deepslate_redstone_ore (type deepslate_diamond_ore); biome cold_ocean (type river); cave air 25% (type 2%); neighbour step 2.5 (type 1.3); cave liquid 8% (type 3%)
  - landmark at [56,632]: notableId smooth_basalt (type coal_ore); relief over 16 blocks 91.0 (type 2.0); cave air 49% (type 5%); neighbour step 1.1 (type 0.1)
  - landmark at [-1208,2920]: notableId deepslate_redstone_ore (type deepslate_diamond_ore); biome forest (type river); cave liquid 24% (type 3%); relief over 16 blocks 42.0 (type 25.0); cave pockets 91% (type 51%)
  - landmark at [-1208,2904]: notableId smooth_basalt (type lava); biome forest (type plains); cave liquid 8% (type 2%); relief over 16 blocks 16.0 (type 2.0); cave air 2% (type 9%)
  - landmark at [3208,3928]: notableId coal_ore (type deepslate_diamond_ore); biome cold_ocean (type river); neighbour step 3.8 (type 1.3); cave air 13% (type 2%); relief over 16 blocks 39.0 (type 25.0)
- **roof**: no cells.

## Biome expression

12 of 14 placed biomes are expressed.

- minecraft:plains: expressed, 18592 columns; placement land 93.4%, water 6.2%, sky 0.3%; snow 0%, canopy 0.6%, trees 2.3/ha; top grass_block 71.3%, short_grass 18.4%, water 6.2%
- minecraft:meadow: placed, not expressed, 8593 columns; placement land 95.9%, water 4.1%; snow 0%, canopy 0%, trees 0/ha; top grass_block 72.5%, short_grass 9.6%, wildflowers 5.2%; **features name trees, none on 0.8 ha of land**
- minecraft:beach: expressed, 7200 columns; placement land 60.6%, water 39.4%; snow 0%, canopy 0%, trees 0/ha; top sand 58.9%, water 39.4%, grass_block 0.9%
- minecraft:deep_ocean: expressed, 7008 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:forest: expressed, 5088 columns; placement land 91.5%, water 8.5%; snow 0%, canopy 52.1%, trees 225.6/ha; top oak_leaves 38.2%, grass_block 28.5%, leaf_litter 13.6%
- minecraft:ocean: expressed, 4816 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:cold_ocean: expressed, 4567 columns; placement land 0.2%, water 99.6%, sky 0.2%; snow 0.2%, canopy 0%, trees 0/ha; top water 99.6%, ice 0.2%, tall_seagrass 0%
- minecraft:warm_ocean: expressed, 3824 columns; placement water 100%; snow 0%, canopy 0%, trees 0/ha; top water 100%
- minecraft:river: expressed, 3472 columns; placement land 0.1%, water 99.9%; snow 0%, canopy 100%, trees 0/ha; top water 99.9%, oak_leaves 0.1%, seagrass 0%
- minecraft:sunflower_plains: placed, not expressed, 2944 columns; placement land 85.2%, water 14.8%; snow 0%, canopy 0%, trees 0/ha; top grass_block 59.3%, short_grass 20.6%, water 14.8%; **features name trees, none on 0.3 ha of land**
- minecraft:birch_forest: expressed, 2304 columns; placement land 88.1%, water 11.9%; snow 0%, canopy 40.7%, trees 187.2/ha; top grass_block 42.6%, birch_leaves 35.9%, water 11.9%
- minecraft:grove: expressed, 1776 columns; placement land 100%; snow 100%, canopy 38.6%, trees 163.3/ha; top snow 67.3%, powder_snow 20.8%, ice 10.8%
- minecraft:badlands: expressed, 736 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top red_sand 89.5%, dead_bush 5.6%, sand 3.7%
- minecraft:snowy_beach: expressed, 603 columns; placement land 88.6%, water 7%, sky 4.5%; snow 92.7%, canopy 1.3%, trees 0/ha; top snow 85.6%, water 7%, powder_snow 1.7%

Listed in the definition, found in no sampled column: minecraft:flower_forest, minecraft:dark_forest, minecraft:taiga, minecraft:snowy_taiga, minecraft:snowy_plains, minecraft:savanna, minecraft:desert, minecraft:swamp, minecraft:jungle, minecraft:mangrove_swamp, minecraft:frozen_ocean, minecraft:lukewarm_ocean, minecraft:frozen_river.

## Notable clusters

- minecraft:sugar_cane: 2 clusters in 2 patches, median 2 blocks (max 2), spacing n/a blocks; blocks by layer surface 4
- minecraft:raw_iron_block: 3 clusters in 2 patches, median 1 blocks (max 1), spacing 33.1 blocks; blocks by layer underground 3
- minecraft:firefly_bush: 4 clusters in 2 patches, median 1 blocks (max 1), spacing 3.2 blocks; blocks by layer surface 4
- minecraft:deepslate_emerald_ore: 5 clusters in 3 patches, median 2 blocks (max 2), spacing 10.9 blocks; blocks by layer underground 8
- minecraft:ice: 7 clusters in 2 patches, median 8 blocks (max 171), spacing 13.8 blocks; blocks by layer sky 46, surface 198
- minecraft:mossy_cobblestone: 7 clusters in 5 patches, median 44 blocks (max 64), spacing 11.7 blocks; blocks by layer underground 319
- minecraft:spawner: 7 clusters in 5 patches, median 1 blocks (max 1), spacing 12.3 blocks; blocks by layer underground 7
- minecraft:wall_torch: 8 clusters in 5 patches, median 1 blocks (max 1), spacing 26.9 blocks; blocks by layer underground 8
- minecraft:chest: 8 clusters in 5 patches, median 1 blocks (max 1), spacing 7.3 blocks; blocks by layer underground 8
- minecraft:cobblestone: 12 clusters in 5 patches, median 56 blocks (max 141), spacing 3.5 blocks; blocks by layer underground 716
- minecraft:amethyst_block: 12 clusters in 11 patches, median 147 blocks (max 573), spacing 10.9 blocks; blocks by layer underground 2666
- minecraft:amethyst_cluster: 12 clusters in 6 patches, median 1 blocks (max 2), spacing 5.1 blocks; blocks by layer underground 13
- minecraft:medium_amethyst_bud: 13 clusters in 8 patches, median 1 blocks (max 1), spacing 2.2 blocks; blocks by layer underground 13
- minecraft:smooth_basalt: 14 clusters in 13 patches, median 216 blocks (max 1105), spacing 8 blocks; blocks by layer underground 4504
- minecraft:calcite: 14 clusters in 13 patches, median 170 blocks (max 943), spacing 9.5 blocks; blocks by layer underground 3467
- minecraft:sandstone: 15 clusters in 8 patches, median 24 blocks (max 4784), spacing 9.7 blocks; blocks by layer surface 15965, underground 15
- minecraft:large_amethyst_bud: 15 clusters in 6 patches, median 1 blocks (max 2), spacing 4.5 blocks; blocks by layer underground 17
- minecraft:poppy: 16 clusters in 5 patches, median 1 blocks (max 3), spacing 4.8 blocks; blocks by layer surface 23
- minecraft:raw_copper_block: 17 clusters in 6 patches, median 1 blocks (max 2), spacing 8.4 blocks; blocks by layer underground 18
- minecraft:small_amethyst_bud: 17 clusters in 8 patches, median 1 blocks (max 2), spacing 3.7 blocks; blocks by layer underground 18
- minecraft:oxeye_daisy: 23 clusters in 6 patches, median 1 blocks (max 3), spacing 5.8 blocks; blocks by layer surface 28
- minecraft:cornflower: 27 clusters in 6 patches, median 1 blocks (max 12), spacing 7.1 blocks; blocks by layer surface 47
- minecraft:clay: 27 clusters in 7 patches, median 9 blocks (max 37), spacing 10.9 blocks; blocks by layer surface 280
- minecraft:rail: 33 clusters in 6 patches, median 1 blocks (max 10), spacing 5.1 blocks; blocks by layer underground 80
- minecraft:diamond_ore: 34 clusters in 25 patches, median 2 blocks (max 8), spacing 12.9 blocks; blocks by layer underground 96
- minecraft:sunflower: 39 clusters in 2 patches, median 2 blocks (max 26), spacing 2.7 blocks; blocks by layer surface 158
- minecraft:deepslate_coal_ore: 41 clusters in 23 patches, median 4 blocks (max 18), spacing 12.7 blocks; blocks by layer underground 269
- minecraft:leaf_litter: 41 clusters in 2 patches, median 1 blocks (max 1160), spacing 4.8 blocks; blocks by layer surface 2361
- minecraft:magma_block: 47 clusters in 19 patches, median 6 blocks (max 13), spacing 13.8 blocks; blocks by layer surface 5, underground 268
- minecraft:cobweb: 64 clusters in 9 patches, median 1 blocks (max 16), spacing 3.8 blocks; blocks by layer underground 83
- minecraft:bush: 85 clusters in 10 patches, median 1 blocks (max 3), spacing 4 blocks; blocks by layer surface 102
- minecraft:lava: 100 clusters in 28 patches, median 23 blocks (max 4096), spacing 14.1 blocks; blocks by layer underground 12865
- minecraft:azure_bluet: 113 clusters in 8 patches, median 1 blocks (max 14), spacing 3.4 blocks; blocks by layer surface 232
- minecraft:emerald_ore: 115 clusters in 5 patches, median 1 blocks (max 4), spacing 4.5 blocks; blocks by layer surface 16, underground 132
- minecraft:budding_amethyst: 153 clusters in 11 patches, median 1 blocks (max 7), spacing 1.3 blocks; blocks by layer underground 252
- minecraft:oak_planks: 159 clusters in 10 patches, median 3 blocks (max 38), spacing 2.6 blocks; blocks by layer underground 429
- minecraft:dandelion: 189 clusters in 12 patches, median 1 blocks (max 17), spacing 2.9 blocks; blocks by layer surface 348
- minecraft:redstone_ore: 198 clusters in 31 patches, median 4 blocks (max 9), spacing 12.7 blocks; blocks by layer underground 845
- minecraft:oak_fence: 210 clusters in 10 patches, median 2 blocks (max 2), spacing 1.6 blocks; blocks by layer underground 420
- minecraft:gold_ore: 230 clusters in 31 patches, median 5 blocks (max 11), spacing 10.9 blocks; blocks by layer surface 8, underground 1104
- minecraft:infested_deepslate: 240 clusters in 6 patches, median 6 blocks (max 18), spacing 3.8 blocks; blocks by layer underground 1402
- minecraft:infested_stone: 252 clusters in 6 patches, median 6 blocks (max 17), spacing 3.4 blocks; blocks by layer surface 70, underground 1463
- minecraft:deepslate_copper_ore: 360 clusters in 31 patches, median 5 blocks (max 15), spacing 7.9 blocks; blocks by layer underground 1821
- minecraft:wildflowers: 393 clusters in 6 patches, median 1 blocks (max 11), spacing 3.2 blocks; blocks by layer surface 590
- minecraft:glow_lichen: 569 clusters in 31 patches, median 1 blocks (max 4), spacing 5.8 blocks; blocks by layer surface 1, underground 806
- minecraft:lapis_ore: 676 clusters in 31 patches, median 4 blocks (max 10), spacing 5.9 blocks; blocks by layer surface 151, underground 2713
- minecraft:kelp_plant: 788 clusters in 8 patches, median 7 blocks (max 148), spacing 2.8 blocks; blocks by layer surface 8164
- minecraft:tall_seagrass: 827 clusters in 14 patches, median 2 blocks (max 120), spacing 3 blocks; blocks by layer surface 3372
- minecraft:deepslate_lapis_ore: 899 clusters in 31 patches, median 4 blocks (max 12), spacing 4.9 blocks; blocks by layer underground 4013
- minecraft:seagrass: 937 clusters in 14 patches, median 1 blocks (max 87), spacing 2.8 blocks; blocks by layer surface 2042
- minecraft:deepslate_gold_ore: 1037 clusters in 31 patches, median 6 blocks (max 26), spacing 4.7 blocks; blocks by layer underground 6008
- minecraft:kelp: 1482 clusters in 8 patches, median 1 blocks (max 16), spacing 1.7 blocks; blocks by layer surface 1999, underground 1
- minecraft:coal_ore: 1660 clusters in 31 patches, median 20 blocks (max 96), spacing 3.4 blocks; blocks by layer surface 4448, underground 24838
- minecraft:deepslate_diamond_ore: 1665 clusters in 31 patches, median 4 blocks (max 16), spacing 3.7 blocks; blocks by layer underground 6876
- minecraft:deepslate_redstone_ore: 1819 clusters in 31 patches, median 5 blocks (max 17), spacing 3.4 blocks; blocks by layer underground 9254
- minecraft:deepslate_iron_ore: 1864 clusters in 31 patches, median 3 blocks (max 24), spacing 3.2 blocks; blocks by layer underground 6784
- minecraft:iron_ore: 2894 clusters in 31 patches, median 5 blocks (max 20), spacing 2.6 blocks; blocks by layer surface 998, underground 13499
- minecraft:copper_ore: 3068 clusters in 31 patches, median 7 blocks (max 88), spacing 2.6 blocks; blocks by layer surface 2410, underground 19273

## Vertical profile (16-block bands)

| y | solid % | open % | sky-exposed % | liquid % |
|---|---|---|---|---|
| -64 | 97.5 | 2.1 | 0 | 0.4 |
| -48 | 92.1 | 7.3 | 0 | 0.7 |
| -32 | 89.8 | 9.5 | 0 | 0.6 |
| -16 | 88.4 | 11.3 | 0 | 0.3 |
| 0 | 88.7 | 7 | 0 | 4.2 |
| 16 | 88.9 | 6.5 | 0 | 4.6 |
| 32 | 85.9 | 5 | 0 | 9.1 |
| 48 | 64 | 4.4 | 3 | 31.7 |
| 64 | 29.7 | 70.3 | 66.8 | 0 |
| 80 | 18.9 | 81 | 80 | 0.1 |
| 96 | 13.2 | 86.8 | 85.5 | 0 |
| 112 | 5.6 | 94.4 | 93.9 | 0 |
| 128 | 2.6 | 97.4 | 97.3 | 0 |
| 144 | 0.7 | 99.3 | 99.3 | 0 |

(Bands entirely open sky are left out.)

## Layer maps

- layout: "patches tiled in plan order, 2 px a block, 2 px gaps; the legend is in the dossier"
- top.png: "top surface, black 26.0 to white 149.0"
- second.png: "second surface below the top, same scale; black where none"
- classes.png: "void black, roofed violet, stacked orange, overhang red, cave blue, single green"
- biomes.png: "surface biome in the render's palette"
- slices: ["slice_31.png","slice_59.png","slice_77.png","slice_139.png"]
- slicesLegend: "air white, terrain grey, liquid blue, plants light green, wood and leaves dark green"
- landforms.png: "one pixel a coarse cell (32 blocks): plains, hills, mountains, plateaus, basins, islands, water, void in that order of colour #9acd6a #c8b45a #8c6e50 #d2a46e #5a8c8c #e6d27a #2f6fd6 #000000"
