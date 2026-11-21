# Review packet: the_burning_archipelago

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_burning_archipelago.json`

> Burning islands in a void sea — nether terrain on end-island bones. The piglins and endermen fought across these straits; the islands that survived still smoulder.

Biomes: minecraft:crimson_forest, minecraft:warped_forest, minecraft:basalt_deltas, minecraft:nether_wastes, incendium:volcanic_deltas, incendium:ash_barrens, incendium:infernal_dunes, incendium:withered_forest, minecraft:soul_sand_valley, incendium:toxic_heap, incendium:weeping_valley, nullscape:void_barrens, regions_unexplored:inferno, regions_unexplored:infernal_holt, paradise_lost:calcite_craglands, terralith:volcanic_peaks

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_burning_archipelago.json`

> Burning islands in a void sea — nether terrain on end-island bones. The piglins and endermen fought across these straits; the islands that survived still smoulder.

Biomes: minecraft:crimson_forest, adventure:withered_forest, adventure:nether_jungle, minecraft:warped_forest, adventure:mycotoxic_undergrowth, minecraft:soul_sand_valley, minecraft:basalt_deltas, adventure:magma_land, minecraft:nether_wastes, adventure:ash_barrens, adventure:gravel_desert, adventure:windswept_isles, minecraft:end_barrens

Terrain: `{"base": "end", "params": {"coverage": -0.04}, "scales": {"climate": 2}, "layout": {"type": "radial", "centre": [0, 0], "radius": 8000, "falloff": 16, "strength": 1.0}}`; generator `{"type": "noise", "preset": "end", "endIsland": false}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_burning_archipelago/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_burning_archipelago/layers`

## Summary

- Top blocks seen from the sky: netherrack 8%, nether_wart_block 5.5%, warped_wart_block 5.1%, basalt 4.4%.
- Surface biomes: warped_forest 9.7%, crimson_forest 8.5%, adventure:magma_land 7.4%, basalt_deltas 7.1%, nether_wastes 5.8%.
- Ground blocks: netherrack 10.4%, warped_nylium 9%, crimson_nylium 8.1%, basalt 4.4%.
- Liquid cover 0% water, 3.5% lava, 0% other; snow or ice 0.1% (a snowing climate over 1.2%); tree canopy 10.9%; bare rock 16.5%; no ground (void) 46.1%.
- Trees: 63.8 per ha (warped_stem 25/ha, mean spacing 3.7 blocks, crimson_stem 22.1/ha, mean spacing 3.6 blocks, stripped_crimson_stem 9.3/ha, mean spacing 6.2 blocks, stripped_warped_stem 3.3/ha, mean spacing 11 blocks); ground plants grass 2.4%, other 1.9%, mushroom 0.4%, flower 0.1%, bush 0.1%.
- Surface height p5 41, median 59, p95 68, max 98; median relief per patch 37 blocks; slopes under 15° 32.8%, over 45° 26.4%, cliffs 8.3%; above the clouds 0%, below sea level 0%.
- Underground: 4.7% open, 0.1% liquid; cave biomes warped_forest 18.4%, crimson_forest 15.6%, basalt_deltas 14.8%, adventure:magma_land 12.2%.
- Notable clusters, fewest first: chiseled_polished_blackstone 6 clusters, spacing 7; chest 6 clusters, spacing 9.2; nether_bricks 7 clusters, spacing 8; moss_carpet 9 clusters, spacing 3.5; iron_chain 9 clusters, spacing 3.2; nether_brick_fence 10 clusters, spacing 4.5.
- 13 of 13 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1451 columns: height within 2 blocks 85.8%, biome match 100%.
- Landforms over the border (noise render): plains 3.8%, hills 2.5%, mountains 0%, plateaus 0.1%, basins 0%, islands 38.3%, water 0%, void 55.2%.
- Placeness: biome regions 9.7/km² (0 major, largest 0.4%), landform regions 10.8/km² (largest 35.5%), dominant place 35.5% of the border.

## Layers

- Columns by class: void 46.1%, roofed 0%, stacked 0.1%, overhang 7.4%, cave 2.5%, single 43.9%.
- Columns with each layer: sky 0.1%, surface 53.9%, underground 50.7%, roof 0%.
- Overhangs in 7.4% of columns, stacked layers in 0.1%, cave pockets in 3.4%.

## Places

- **surface** (409 cells, 8 types, silhouette 0.386):
  - type 1: 17.8% of cells; biome nether_wastes; ground netherrack; top netherrack; height 57; trees per cell 0.2 (layer 2.5); canopy 1% (layer 20%); ground plants 1% (layer 9%); snow or ice 1% (layer 0%); bare rock 88% (layer 31%); open to the sky 99% (layer 80%) — camera [-3992.5,59,520.5]
  - type 2: 17.6% of cells; biome warped_forest; ground warped_nylium; top warped_wart_block; height 58; trees per cell 5.4 (layer 2.5); canopy 49% (layer 20%); ground plants 24% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 51% (layer 80%) — camera [2084.5,66,-1751.5]
  - type 3: 15.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 57; trees per cell 5.9 (layer 2.5); canopy 53% (layer 20%); ground plants 18% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 47% (layer 80%) — camera [1208.5,59,3816.5]
  - type 4: 14.2% of cells; biome basalt_deltas; ground basalt; top basalt; height 57; trees per cell 0.0 (layer 2.5); canopy 0% (layer 20%); ground plants 0% (layer 9%); liquid cover 23% (layer 6%); bare rock 68% (layer 31%); open to the sky 100% (layer 80%) — camera [-1639.5,59,-3975.5]
  - type 5: 11.0% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 60; neighbour step 0.7 (layer 1.3); relief over 16 blocks 10.4 (layer 20.5); relief over the patch 21.9 (layer 39.1); trees per cell 0.0 (layer 2.5); canopy 0% (layer 20%); liquid cover 23% (layer 6%); bare rock 2% (layer 31%); open to the sky 100% (layer 80%) — camera [-3463.5,67,-1735.5]
  - type 6: 10.5% of cells; biome adventure:gravel_desert; ground gravel; top gravel; height 59; trees per cell 0.2 (layer 2.5); canopy 1% (layer 20%); ground plants 0% (layer 9%); bare rock 4% (layer 31%); open to the sky 99% (layer 80%); overhangs 22% (layer 14%) — camera [-3064.5,59,1048.5]
  - type 7: 7.3% of cells; biome adventure:withered_forest; ground soul_soil; top soul_soil; height 58; neighbour step 0.5 (layer 1.3); trees per cell 6.6 (layer 2.5); canopy 5% (layer 20%); liquid cover 0% (layer 6%); bare rock 6% (layer 31%); open to the sky 95% (layer 80%) — camera [3464.5,61,1144.5]
  - type 8: 6.4% of cells; biome adventure:nether_jungle; ground netherrack; top nether_wart_block; height 57; neighbour step 3.0 (layer 1.3); relief over 16 blocks 31.9 (layer 20.5); relief over the patch 54.3 (layer 39.1); canopy 44% (layer 20%); liquid cover 0% (layer 6%); open to the sky 56% (layer 80%); overhangs 27% (layer 14%); stacked layers 1% (layer 0%) — camera [1800.5,59,1719.5]
  - landmark at [-2184,3688]: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); overhangs 79% (type 9%); cave pockets 48% (type 0%); height 77.7 (type 58.1)
  - landmark at [-2168,3656]: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); cave pockets 63% (type 0%); height 87.3 (type 58.1); overhangs 76% (type 9%)
  - landmark at [-2152,3688]: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); cave pockets 58% (type 0%); height 77.6 (type 58.1); overhangs 58% (type 9%)
  - landmark at [-2184,3656]: biome nether_wastes (type basalt_deltas); ground polished_blackstone_bricks (type basalt); top polished_blackstone_bricks (type basalt); cave pockets 54% (type 0%); height 82.9 (type 57.7); overhangs 62% (type 5%)
  - landmark at [-2168,3688]: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); cave pockets 79% (type 0%); overhangs 89% (type 9%); height 78.7 (type 58.1)
- **sky**: no cells.
- **underground** (384 cells, 2 types, silhouette 0.222):
  - type 1: 69.0% of cells; notableId magma_block; biome warped_forest; height 59 — camera [3965.5,55.88,-1018.5]
  - type 2: 31.0% of cells; notableId blackstone; biome basalt_deltas; height 58 — camera [2446.5,57.88,2129.5]
  - landmark at [-2168,3656]: notableId lava (type magma_block); biome nether_wastes (type none); cave pockets 66% (type 0%); height 86.9 (type 58.4); cave liquid 1% (type 0%)
  - landmark at [2232,2280]: notableId weeping_vines_plant (type magma_block); biome adventure:nether_jungle (type none); neighbour step 6.6 (type 0.9); trees per cell 2.0 (type 0.0); relief over 16 blocks 49.0 (type 14.0)
  - landmark at [-2168,3688]: biome nether_wastes (type none); cave liquid 2% (type 0%); cave air 59% (type 0%); cave pockets 79% (type 0%)
  - landmark at [-2184,3656]: biome nether_wastes (type none); cave pockets 57% (type 0%); height 82.2 (type 57.6); notable-block density (log) 3.1 (type 0.4)
  - landmark at [-2152,3688]: biome nether_wastes (type none); cave pockets 58% (type 0%); cave air 37% (type 0%); height 77.6 (type 57.6)
- **roof**: no cells.

## Biome expression

13 of 13 placed biomes are expressed.

- minecraft:basalt_deltas: expressed, 27792 columns; placement land 33.3%, lava 10.7%, void 56%; snow 0%, canopy 0%, trees 0/ha; top basalt 26.7%, lava 10.7%, magma_block 3.5%
- minecraft:crimson_forest: expressed, 25819 columns; placement land 57.2%, void 42.6%, sky 0.2%; snow 0%, canopy 52.2%, trees 252/ha; top nether_wart_block 29.8%, crimson_nylium 18.3%, crimson_roots 4.2%
- minecraft:warped_forest: expressed, 25194 columns; placement land 66.2%, void 33.4%, sky 0.4%; snow 0%, canopy 48.1%, trees 235/ha; top warped_wart_block 31.7%, warped_nylium 20.1%, netherrack 4.3%
- adventure:magma_land: expressed, 18289 columns; placement land 55%, lava 15%, void 30%; snow 0%, canopy 0%, trees 0/ha; top magma_block 29.9%, lava 15%, obsidian 14.6%
- adventure:withered_forest: expressed, 16289 columns; placement land 47.2%, void 52.8%; snow 0%, canopy 5.4%, trees 280.8/ha; top soul_soil 36.3%, netherrack 4.9%, wither_rose 1.5%
- minecraft:nether_wastes: expressed, 14666 columns; placement land 66%, lava 1.8%, void 32%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 57%, polished_blackstone_bricks 4.1%, cracked_polished_blackstone_bricks 1.9%
- minecraft:soul_sand_valley: expressed, 10160 columns; placement land 16.4%, void 83.6%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 15.6%, nether_bricks 0.4%, bone_block 0.2%
- adventure:ash_barrens: expressed, 9169 columns; placement land 35.6%, void 64.4%; snow 0%, canopy 0%, trees 0/ha; top tuff 33.4%, soul_soil 0.7%, basalt 0.6%
- adventure:nether_jungle: expressed, 8252 columns; placement land 62.7%, void 36.9%, sky 0.3%; snow 0%, canopy 49.7%, trees 110.1/ha; top nether_wart_block 20.8%, netherrack 11.6%, warped_wart_block 10.3%
- adventure:gravel_desert: expressed, 7889 columns; placement land 63.4%, void 36.6%; snow 0%, canopy 0.1%, trees 0/ha; top gravel 61.7%, bone_block 0.9%, stone 0.3%
- adventure:mycotoxic_undergrowth: expressed, 3904 columns; placement land 30.6%, void 69.4%; snow 0%, canopy 1%, trees 67.1/ha; top moss_block 15%, slime_block 14.4%, warped_nylium 0.3%
- adventure:windswept_isles: expressed, 3280 columns; placement land 65.3%, void 34.7%; snow 9.6%, canopy 6.2%, trees 28/ha; top stone 50.7%, snow 6.3%, spruce_leaves 3.8%
- minecraft:end_barrens: expressed, 2304 columns; placement land 29.3%, void 70.7%; snow 0%, canopy 0%, trees 0/ha; top netherrack 29.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:crimson_forest | minecraft:crimson_forest | 8.5 | 25819 | expressed | - |
| O2 | minecraft:warped_forest | minecraft:warped_forest | 9.7 | 25194 | expressed | - |
| O3 | minecraft:basalt_deltas | minecraft:basalt_deltas | 7.1 | 27792 | expressed | - |
| O4 | minecraft:nether_wastes | minecraft:nether_wastes | 5.8 | 14666 | expressed | - |
| O5 | incendium:volcanic_deltas | adventure:magma_land | 7.4 | 18289 | expressed | - |
| O6 | incendium:ash_barrens | adventure:ash_barrens | 1.9 | 9169 | expressed | - |
| O7 | incendium:infernal_dunes | adventure:gravel_desert | 2.9 | 7889 | expressed | - |
| O8 | incendium:withered_forest | adventure:withered_forest | 4.5 | 16289 | expressed | - |
| O9 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 1.0 | 10160 | expressed | - |
| O10 | incendium:toxic_heap | adventure:mycotoxic_undergrowth | 0.7 | 3904 | expressed | - |
| O11 | incendium:weeping_valley | adventure:nether_jungle | 3.0 | 8252 | expressed | - |
| O12 | nullscape:void_barrens | minecraft:end_barrens | 0.4 | 2304 | expressed | - |
| O13 | regions_unexplored:inferno | adventure:magma_land | 7.4 | 18289 | expressed | - |
| O14 | regions_unexplored:infernal_holt | minecraft:crimson_forest | 8.5 | 25819 | expressed | - |
| O15 | paradise_lost:calcite_craglands | adventure:windswept_isles | 1.2 | 3280 | expressed | - |
| O16 | terralith:volcanic_peaks | adventure:magma_land | 7.4 | 18289 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:warped_forest 9.7%, minecraft:crimson_forest 8.5%, adventure:magma_land 7.4% | type `nether_islands` |
| M:snow | 0.1% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"defaultBlock": "minecraft:netherrack", "defaultFluid": "minecraft:lava", "seaLevel": -60, "endIsland": false}` |
| M:lava | 3.5% (`surface.fluidCoverPercent.lava`) | |
| M:void | 46.1% (`surface.voidPercent`) | |
| M:canopy | 10.9% (`surface.canopyPercent`) | |
| M:relief | median 37.0 blocks a patch; height {"p5": 41.0, "p25": 56.0, "p50": 59.0, "p75": 62.0, "p95": 68.0, "max": 98.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 53.9, "underground": 50.7, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#3B2B4B", "ambientLight": 0.1, "effects": "minecraft:the_end", "hasCeiling": false, "ultraWarm": true}` |
| M:classes | {"void": 46.1, "roofed": 0.0, "stacked": 0.1, "overhang": 7.4, "cave": 2.5, "single": 43.9} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (46.1% of columns)

## Shots

### the_burning_archipelago-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 30.4%; void 12.2%; mid: basalt 20.7%, nether_bricks 1%; far: basalt 19.7%, nether_bricks 10.1%; biomes: basalt_deltas 98.5%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-far.png`

### the_burning_archipelago-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 30.4%; void 12.2%; mid: basalt 20.7%, nether_bricks 1%; far: basalt 19.7%, nether_bricks 10.1%; biomes: basalt_deltas 98.5%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-far-nv.png`

### the_burning_archipelago-surface-t2

Picked for: surface type 2: 17.6% of cells; biome warped_forest; ground warped_nylium; top warped_wart_block; height 58; trees per cell 5.4 (layer 2.5); canopy 49% (layer 20%); ground plants 24% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 51% (layer 80%)
Census: sky 2%; near: warped_wart_block 39.4%, warped_nylium 20.7%; biomes: warped_forest 99%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t2.png`

### the_burning_archipelago-surface-t2-nv

Picked for: surface type 2: 17.6% of cells; biome warped_forest; ground warped_nylium; top warped_wart_block; height 58; trees per cell 5.4 (layer 2.5); canopy 49% (layer 20%); ground plants 24% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 51% (layer 80%)
Census: sky 2%; near: warped_wart_block 39.4%, warped_nylium 20.7%; biomes: warped_forest 99%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t2-nv.png`

### the_burning_archipelago-surface-t3

Picked for: surface type 3: 15.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 57; trees per cell 5.9 (layer 2.5); canopy 53% (layer 20%); ground plants 18% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 47% (layer 80%)
Census: sky 36.2%; near: soul_sand 54.6%, bone_block 3%; mid: soul_sand 3.9%, soul_soil 1%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t3.png`

### the_burning_archipelago-surface-t3-nv

Picked for: surface type 3: 15.2% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 57; trees per cell 5.9 (layer 2.5); canopy 53% (layer 20%); ground plants 18% (layer 9%); liquid cover 0% (layer 6%); bare rock 5% (layer 31%); open to the sky 47% (layer 80%)
Census: sky 36.3%; near: soul_sand 54.6%, bone_block 3%; mid: soul_sand 3.9%, soul_soil 1%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t3-nv.png`

### the_burning_archipelago-surface-t4

Picked for: surface type 4: 14.2% of cells; biome basalt_deltas; ground basalt; top basalt; height 57; trees per cell 0.0 (layer 2.5); canopy 0% (layer 20%); ground plants 0% (layer 9%); liquid cover 23% (layer 6%); bare rock 68% (layer 31%); open to the sky 100% (layer 80%)
Census: sky 40.3%; unloaded 2.4%; near: basalt 19.2%, lava 5.3%; mid: basalt 25.3%, blackstone 2.5%; far: basalt 1%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t4.png`

### the_burning_archipelago-surface-t4-nv

Picked for: surface type 4: 14.2% of cells; biome basalt_deltas; ground basalt; top basalt; height 57; trees per cell 0.0 (layer 2.5); canopy 0% (layer 20%); ground plants 0% (layer 9%); liquid cover 23% (layer 6%); bare rock 68% (layer 31%); open to the sky 100% (layer 80%)
Census: sky 40.5%; unloaded 2%; near: basalt 19.2%, lava 5.3%; mid: basalt 25.3%, blackstone 2.5%; far: basalt 1.2%; biomes: basalt_deltas 99.9%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t4-nv.png`

### the_burning_archipelago-surface-t8

Picked for: surface type 8: 6.4% of cells; biome adventure:nether_jungle; ground netherrack; top nether_wart_block; height 57; neighbour step 3.0 (layer 1.3); relief over 16 blocks 31.9 (layer 20.5); relief over the patch 54.3 (layer 39.1); canopy 44% (layer 20%); liquid cover 0% (layer 6%); open to the sky 56% (layer 80%); overhangs 27% (layer 14%); stacked layers 1% (layer 0%)
Census: sky 9.6%; near: soul_soil 38.9%, netherrack 11.4%; mid: netherrack 5%, nether_wart_block 2%; biomes: adventure:nether_jungle 56.8%, adventure:withered_forest 43.2%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t8.png`

### the_burning_archipelago-surface-t8-nv

Picked for: surface type 8: 6.4% of cells; biome adventure:nether_jungle; ground netherrack; top nether_wart_block; height 57; neighbour step 3.0 (layer 1.3); relief over 16 blocks 31.9 (layer 20.5); relief over the patch 54.3 (layer 39.1); canopy 44% (layer 20%); liquid cover 0% (layer 6%); open to the sky 56% (layer 80%); overhangs 27% (layer 14%); stacked layers 1% (layer 0%)
Census: sky 9.6%; near: soul_soil 38.9%, netherrack 11.4%; mid: netherrack 5%, nether_wart_block 2%; biomes: adventure:nether_jungle 56.8%, adventure:withered_forest 43.2%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-t8-nv.png`

### the_burning_archipelago-surface-lm1

Picked for: surface landmark: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); overhangs 79% (type 9%); cave pockets 48% (type 0%); height 77.7 (type 58.1)
Census: sky 40.3%; void 6.7%; unloaded 5.4%; mid: netherrack 16.5%, weeping_vines_plant 8.6%; biomes: adventure:nether_jungle 99.6%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-lm1.png`

### the_burning_archipelago-surface-lm1-nv

Picked for: surface landmark: biome nether_wastes (type adventure:gravel_desert); ground polished_blackstone_bricks (type gravel); top polished_blackstone_bricks (type gravel); overhangs 79% (type 9%); cave pockets 48% (type 0%); height 77.7 (type 58.1)
Census: sky 40.5%; void 6.7%; unloaded 5.2%; mid: netherrack 16.5%, weeping_vines_plant 8.6%; biomes: adventure:nether_jungle 99.6%
Image: `/tmp/claude-0/w10c3/reviews/the_burning_archipelago/screens/the_burning_archipelago-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
