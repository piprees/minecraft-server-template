# Review packet: the_ember_fields

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_ember_fields.json`

> Rolling ember meadows where piglin villages still tend the geyser fields. The gentlest face of piglin civilisation — farmers and traders, not soldiers. The fortress at the rim is a precaution, not a threat.

Biomes: betternether:nether_grasslands, betternether:poor_nether_grasslands, regions_unexplored:glistering_meadow, betternether:wart_forest_edge, betternether:nether_mushroom_forest, betterend:sulphur_springs, terralith:yellowstone, incendium:quartz_flats, incendium:weeping_valley, minecraft:crimson_forest, minecraft:nether_wastes, incendium:infernal_dunes, incendium:volcanic_deltas

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_ember_fields.json`

> Rolling ember meadows where piglin villages still tend the geyser fields. The gentlest face of piglin civilisation — farmers and traders, not soldiers. The fortress at the rim is a precaution, not a threat.

Biomes: adventure:nether_grasslands, adventure:sulphur_springs, minecraft:crimson_forest, adventure:quartz_flats, adventure:nether_jungle, adventure:crimson_pinewood, adventure:magma_land, adventure:mycotoxic_undergrowth, minecraft:nether_wastes, adventure:gravel_desert, minecraft:sulfur_caves

Terrain: `{"base": "roofed", "params": {"floorHeight": 44, "floorRelief": 8, "ceilingGap": 64, "roughness": 0.15, "flats": 0.2}, "scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/fin/the_ember_fields/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/fin/the_ember_fields/layers`

## Summary

- Top blocks seen from the sky: netherrack 26.4%, nether_sprouts 18.9%, crimson_roots 13.3%, warped_roots 9.3%.
- Surface biomes: adventure:nether_grasslands 51.9%, adventure:sulphur_springs 23.1%, adventure:crimson_pinewood 6.7%, crimson_forest 5.7%, adventure:nether_jungle 4.7%.
- Ground blocks: crimson_nylium 40.1%, netherrack 28.7%, warped_nylium 20.2%, glowstone 2.8%.
- Liquid cover 1.2% water, 1% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 8.4%; bare rock 27.6%; no ground (void) 0%.
- Trees: 32.1 per ha (crimson_stem 24.4/ha, mean spacing 4.7 blocks, stripped_crimson_stem 6/ha, mean spacing 7.1 blocks, stripped_warped_stem 1.2/ha, mean spacing 16 blocks, oak_log 0.3/ha, mean spacing 10 blocks); ground plants grass 23.6%, other 19.7%, mushroom 6.2%, bush 0.1%.
- Surface height p5 37, median 43, p95 114, max 125; median relief per patch 80 blocks; slopes under 15° 60.9%, over 45° 19.2%, cliffs 14.3%; above the clouds 0%, below sea level 0.1%.
- Underground: 15.6% open, 1.2% liquid; cave biomes adventure:nether_grasslands 45%, adventure:sulphur_springs 26.3%, nether_wastes 10.2%, sulfur_caves 4.3%.
- Notable clusters, fewest first: red_mushroom 2 clusters, spacing n/a; glow_lichen 3 clusters, spacing n/a; mossy_cobblestone 7 clusters, spacing 4.7; spawner 7 clusters, spacing 21.7; chest 9 clusters, spacing 4.7; amethyst_block 10 clusters, spacing 28.9.
- 9 of 11 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 936 columns: height within 2 blocks 86.5%, biome match 99.9%.
- Landforms over the border (noise render): plains 100%, hills 0%, mountains 0%, plateaus 0%, basins 0%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 4.8/km² (13 major, largest 52.7%), landform regions 0.2/km² (largest 100.0%), dominant place 100.0% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.1%, surface 100%, underground 100%, roof 100%.
- Overhangs in 24.6% of columns, stacked layers in 0.1%, cave pockets in 18.9%.

## Places

- **surface** (234 cells, 3 types, silhouette 0.573):
  - type 1: 56.0% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 83% (layer 50%); bare rock 1% (layer 28%) — camera [-375.5,44,-24.5]
  - type 2: 28.2% of cells; biome adventure:sulphur_springs; ground netherrack; top netherrack; height 53; neighbour step 7.5 (layer 4.0); relief over 16 blocks 82.5 (layer 68.5); relief over the patch 87.7 (layer 82.9); notable-block density (log) 0.1 (layer 0.6); ground plants 1% (layer 50%); liquid cover 5% (layer 2%); bare rock 88% (layer 28%); cave pockets 35% (layer 19%) — camera [456.5,43,-615.5]
  - type 3: 15.8% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 46; trees per cell 4.4 (layer 0.7); canopy 51% (layer 8%); ground plants 19% (layer 50%); cave pockets 7% (layer 19%) — camera [296.5,45,856.5]
  - landmark at [632,680]: biome adventure:nether_jungle (type adventure:crimson_pinewood); ground netherrack (type crimson_nylium); stacked layers 4% (type 0%); liquid cover 14% (type 1%); relief over the patch 99.0 (type 75.0)
  - landmark at [904,-872]: biome adventure:quartz_flats (type adventure:nether_grasslands); ground quartz_block (type crimson_nylium); top quartz_block (type nether_sprouts); stacked layers 1% (type 0%); neighbour step 9.3 (type 0.9); ground plants 0% (type 94%)
  - landmark at [-744,904]: biome adventure:gravel_desert (type adventure:sulphur_springs); ground gravel (type netherrack); top gravel (type netherrack); cave pockets 72% (type 20%); bare rock 17% (type 95%); height 69.8 (type 51.9)
  - landmark at [920,-856]: biome adventure:magma_land (type adventure:nether_grasslands); ground magma_block (type crimson_nylium); top magma_block (type nether_sprouts); liquid cover 25% (type 0%); relief over 16 blocks 2.0 (type 71.0); ground plants 6% (type 94%)
  - landmark at [-760,904]: biome adventure:gravel_desert (type adventure:sulphur_springs); ground gravel (type netherrack); top gravel (type netherrack); bare rock 5% (type 95%); cave pockets 57% (type 20%); relief over the patch 76.0 (type 88.0)
- **sky**: no cells.
- **underground** (234 cells, 2 types, silhouette 0.309):
  - type 1: 72.2% of cells; notableId magma_block; biome adventure:nether_grasslands; height 48 — camera [138.5,43.88,-202.5]
  - type 2: 27.8% of cells; notableId lava; biome adventure:sulphur_springs; height 52; neighbour step 7.3 (layer 4.0); relief over 16 blocks 81.8 (layer 68.5); notable-block density (log) 0.6 (layer 1.0); cave liquid 3% (layer 1%); cave pockets 34% (layer 19%) — camera [-427.5,42.88,-1003.5]
  - landmark at [952,-136]: biome adventure:crimson_pinewood (type adventure:sulphur_springs); relief over 16 blocks 13.0 (type 84.0); notable-block density (log) 1.8 (type 0.1); trees per cell 1.0 (type 0.0)
  - landmark at [-104,-776]: notableId cobblestone (type lava); biome sulfur_caves (type adventure:sulphur_springs); trees per cell 2.0 (type 0.0); cave pockets 73% (type 27%); notable-block density (log) 0.9 (type 0.1)
  - landmark at [264,840]: biome adventure:crimson_pinewood (type adventure:sulphur_springs); trees per cell 2.0 (type 0.0); cave liquid 11% (type 2%); notable-block density (log) 1.9 (type 0.1)
  - landmark at [-744,904]: biome adventure:gravel_desert (type adventure:nether_grasslands); cave pockets 72% (type 2%); cave air 43% (type 2%); height 69.8 (type 43.0)
  - landmark at [-760,872]: biome nether_wastes (type adventure:nether_grasslands); height 93.4 (type 43.0); cave air 56% (type 2%); cave pockets 69% (type 2%)
- **roof** (234 cells, 5 types, silhouette 0.455):
  - type 1: 44.0% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6) — camera [-823.5,102.88,-311.5]
  - type 2: 26.5% of cells; notableId sulfur; height 110; neighbour step 1.6 (layer 0.6); relief over 16 blocks 18.6 (layer 10.6) — camera [-439.5,103.88,-983.5]
  - type 3: 13.7% of cells; notableId nether_gold_ore; height 109; neighbour step 0.1 (layer 0.6); relief over 16 blocks 5.4 (layer 10.6); notable-block density (log) 0.1 (layer 0.2) — camera [296.5,103.88,856.5]
  - type 4: 9.0% of cells; notableId crimson_nylium; height 112; neighbour step 1.0 (layer 0.6); notable-block density (log) 0.5 (layer 0.2) — camera [-55.5,101.88,-135.5]
  - type 5: 6.8% of cells; notableId weeping_vines_plant; height 109; neighbour step 0.2 (layer 0.6); relief over 16 blocks 6.7 (layer 10.6); notable-block density (log) 0.7 (layer 0.2) — camera [600.5,102.88,648.5]
  - landmark at [-104,760]: notableId warped_nylium (type crimson_nylium); height 118.4 (type 110.7); notable-block density (log) 0.9 (type 0.5); relief over 16 blocks 14.0 (type 11.0)
  - landmark at [-360,520]: notableId glowstone (type nether_quartz_ore); relief over 16 blocks 18.0 (type 8.0); notable-block density (log) 0.4 (type 0.1); height 106.6 (type 107.6)
  - landmark at [-104,776]: height 118.8 (type 110.7); notable-block density (log) 1.1 (type 0.5); neighbour step 0.8 (type 1.0)
  - landmark at [-120,776]: notableId warped_nylium (type crimson_nylium); height 114.9 (type 110.7); relief over 16 blocks 14.0 (type 11.0); neighbour step 1.3 (type 1.0)
  - landmark at [-120,760]: notableId nether_sprouts (type sulfur); neighbour step 1.0 (type 1.8); relief over 16 blocks 14.0 (type 18.0); height 109.1 (type 110.3)

## Biome expression

9 of 11 placed biomes are expressed.

- adventure:nether_grasslands: expressed, 31072 columns; placement land 99.4%, lava 0.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top nether_sprouts 36%, crimson_roots 23.4%, warped_roots 17.8%
- adventure:sulphur_springs: expressed, 13827 columns; placement land 94.6%, water 5.3%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 94.6%, water 5.3%
- adventure:crimson_pinewood: expressed, 4032 columns; placement land 99.4%, lava 0.6%, roofed 100%; snow 0%, canopy 44.8%, trees 132.2/ha; top nether_wart_block 44%, crimson_nylium 28.6%, netherrack 12%
- minecraft:crimson_forest: expressed, 3450 columns; placement land 99.2%, lava 0.5%, roofed 99.7%, sky 0.3%; snow 0%, canopy 49.3%, trees 230.8/ha; top nether_wart_block 48.5%, crimson_nylium 29.8%, crimson_roots 10%
- adventure:nether_jungle: expressed, 2837 columns; placement land 99%, lava 0.7%, roofed 99.7%, sky 0.3%; snow 0%, canopy 55.7%, trees 142.5/ha; top nether_wart_block 31.4%, warped_wart_block 15.9%, weeping_vines_plant 13.5%
- adventure:magma_land: expressed, 1680 columns; placement land 81.3%, lava 18.8%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top magma_block 57.2%, lava 18.8%, obsidian 10%
- minecraft:nether_wastes: expressed, 1234 columns; placement land 99.6%, lava 0.2%, roofed 99.8%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 93%, glowstone 1.6%, gravel 1.1%
- adventure:gravel_desert: expressed, 1104 columns; placement land 99.5%, lava 0.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 75.5%, netherrack 11.9%, coarse_dirt 5.6%
- adventure:quartz_flats: expressed, 626 columns; placement land 97.1%, lava 2.6%, roofed 99.7%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 66.1%, netherrack 12.6%, glowstone 7.2%
- minecraft:sulfur_caves: too few columns to judge, 46 columns; placement land 21.7%, lava 78.3%, roofed 100%; snow 0%, canopy 90%, trees 0/ha; top lava 78.3%, nether_wart_block 13%, weeping_vines_plant 4.3%
- adventure:mycotoxic_undergrowth: too few columns to judge, 32 columns; placement land 100%, roofed 100%; snow 0%, canopy 12.5%, trees 0/ha; top slime_block 37.5%, moss_block 25%, crimson_nylium 15.6%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | betternether:nether_grasslands | adventure:nether_grasslands | 51.9 | 31072 | expressed | - |
| O2 | betternether:poor_nether_grasslands | minecraft:nether_wastes | 2.1 | 1234 | expressed | - |
| O3 | regions_unexplored:glistering_meadow | adventure:nether_grasslands | 51.9 | 31072 | expressed | - |
| O4 | betternether:wart_forest_edge | adventure:crimson_pinewood | 6.7 | 4032 | expressed | - |
| O5 | betternether:nether_mushroom_forest | adventure:mycotoxic_undergrowth | 0.1 | 32 | too few columns to judge | - |
| O6 | betterend:sulphur_springs | adventure:sulphur_springs | 23.1 | 13827 | expressed | - |
| O7 | terralith:yellowstone | minecraft:sulfur_caves | 0.1 | 46 | too few columns to judge | - |
| O8 | incendium:quartz_flats | adventure:quartz_flats | 1.0 | 626 | expressed | - |
| O9 | incendium:weeping_valley | adventure:nether_jungle | 4.7 | 2837 | expressed | - |
| O10 | minecraft:crimson_forest | minecraft:crimson_forest | 5.7 | 3450 | expressed | - |
| O11 | minecraft:nether_wastes | minecraft:nether_wastes | 2.1 | 1234 | expressed | - |
| O12 | incendium:infernal_dunes | adventure:gravel_desert | 1.8 | 1104 | expressed | - |
| O13 | incendium:volcanic_deltas | adventure:magma_land | 2.8 | 1680 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:nether_grasslands 51.9%, adventure:sulphur_springs 23.1%, adventure:crimson_pinewood 6.7% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 1.2% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 1.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 8.4% (`surface.canopyPercent`) | |
| M:relief | median 80.0 blocks a patch; height {"p5": 37.0, "p25": 42.0, "p50": 43.0, "p75": 45.0, "p95": 114.0, "max": 125.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 100.0, "underground": 100.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"bedWorks": true, "fogColor": "#E8A05A", "ambientLight": 0.25}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 84.6%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_ember_fields-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 11.4%; mid: crimson_roots 17.2%, warped_roots 11.5%; far: netherrack 21.1%, crimson_roots 4.3%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-far.png`

### the_ember_fields-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 11.4%; mid: crimson_roots 17.2%, warped_roots 11.5%; far: netherrack 21.1%, crimson_roots 4.3%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-far-nv.png`

### the_ember_fields-surface-t1

Picked for: surface type 1: 56.0% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 83% (layer 50%); bare rock 1% (layer 28%)
Census: sky 9.3%; near: warped_roots 18.2%, crimson_roots 17.9%; mid: crimson_roots 1%; far: netherrack 31.3%, glowstone 1.3%; biomes: adventure:nether_grasslands 99.8%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t1.png`

### the_ember_fields-surface-t1-nv

Picked for: surface type 1: 56.0% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 83% (layer 50%); bare rock 1% (layer 28%)
Census: sky 9.3%; near: warped_roots 18.2%, crimson_roots 17.9%; mid: crimson_roots 1%; far: netherrack 31.1%, glowstone 1.3%; biomes: adventure:nether_grasslands 99.8%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t1-nv.png`

### the_ember_fields-underground-t1

Picked for: underground type 1: 72.2% of cells; notableId magma_block; biome adventure:nether_grasslands; height 48
Census: sky 4.5%; unloaded 6%; near: warped_roots 34%, crimson_roots 8.9%; mid: crimson_roots 1%; far: netherrack 25.1%, glowstone 1.5%; biomes: adventure:nether_grasslands 96.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t1.png`

### the_ember_fields-underground-t1-nv

Picked for: underground type 1: 72.2% of cells; notableId magma_block; biome adventure:nether_grasslands; height 48
Census: sky 5.6%; unloaded 4.7%; near: warped_roots 34%, crimson_roots 8.9%; mid: crimson_roots 1%; far: netherrack 25.2%, glowstone 1.5%; biomes: adventure:nether_grasslands 96.5%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t1-nv.png`

### the_ember_fields-roof-t1

Picked for: roof type 1: 44.0% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6)
Census: sky 8.6%; near: netherrack 79.3%, glowstone 1.8%; mid: netherrack 6.3%; far: netherrack 1.9%, glowstone 1%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-roof-t1.png`

### the_ember_fields-roof-t1-nv

Picked for: roof type 1: 44.0% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6)
Census: sky 8.6%; near: netherrack 79.3%, glowstone 1.8%; mid: netherrack 6.3%; far: netherrack 1.9%, glowstone 1%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-roof-t1-nv.png`

### the_ember_fields-surface-t2

Picked for: surface type 2: 28.2% of cells; biome adventure:sulphur_springs; ground netherrack; top netherrack; height 53; neighbour step 7.5 (layer 4.0); relief over 16 blocks 82.5 (layer 68.5); relief over the patch 87.7 (layer 82.9); notable-block density (log) 0.1 (layer 0.6); ground plants 1% (layer 50%); liquid cover 5% (layer 2%); bare rock 88% (layer 28%); cave pockets 35% (layer 19%)
Census: sky 3.4%; unloaded 4.6%; near: netherrack 55%; mid: netherrack 4.7%; far: netherrack 28.9%, glowstone 2.3%; biomes: adventure:sulphur_springs 81.7%, adventure:nether_grasslands 14.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t2.png`

### the_ember_fields-surface-t2-nv

Picked for: surface type 2: 28.2% of cells; biome adventure:sulphur_springs; ground netherrack; top netherrack; height 53; neighbour step 7.5 (layer 4.0); relief over 16 blocks 82.5 (layer 68.5); relief over the patch 87.7 (layer 82.9); notable-block density (log) 0.1 (layer 0.6); ground plants 1% (layer 50%); liquid cover 5% (layer 2%); bare rock 88% (layer 28%); cave pockets 35% (layer 19%)
Census: sky 3.4%; unloaded 4.6%; near: netherrack 55%; mid: netherrack 4.7%; far: netherrack 28.9%, glowstone 2.3%; biomes: adventure:sulphur_springs 81.7%, adventure:nether_grasslands 14.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t2-nv.png`

### the_ember_fields-surface-t3

Picked for: surface type 3: 15.8% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 46; trees per cell 4.4 (layer 0.7); canopy 51% (layer 8%); ground plants 19% (layer 50%); cave pockets 7% (layer 19%)
Census: sky 0%; near: nether_wart_block 30.3%, crimson_nylium 24.4%; biomes: adventure:crimson_pinewood 92.4%, adventure:mycotoxic_undergrowth 7.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t3.png`

### the_ember_fields-surface-t3-nv

Picked for: surface type 3: 15.8% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 46; trees per cell 4.4 (layer 0.7); canopy 51% (layer 8%); ground plants 19% (layer 50%); cave pockets 7% (layer 19%)
Census: sky 0%; near: nether_wart_block 30.3%, crimson_nylium 24.4%; biomes: adventure:crimson_pinewood 92.4%, adventure:mycotoxic_undergrowth 7.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t3-nv.png`

### the_ember_fields-underground-t2

Picked for: underground type 2: 27.8% of cells; notableId lava; biome adventure:sulphur_springs; height 52; neighbour step 7.3 (layer 4.0); relief over 16 blocks 81.8 (layer 68.5); notable-block density (log) 0.6 (layer 1.0); cave liquid 3% (layer 1%); cave pockets 34% (layer 19%)
Census: sky 0%; unloaded 14.8%; near: netherrack 57.9%; mid: netherrack 7.5%; far: netherrack 19%; biomes: adventure:sulphur_springs 98.1%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t2.png`

### the_ember_fields-underground-t2-nv

Picked for: underground type 2: 27.8% of cells; notableId lava; biome adventure:sulphur_springs; height 52; neighbour step 7.3 (layer 4.0); relief over 16 blocks 81.8 (layer 68.5); notable-block density (log) 0.6 (layer 1.0); cave liquid 3% (layer 1%); cave pockets 34% (layer 19%)
Census: sky 0%; unloaded 13.9%; near: netherrack 57.9%; mid: netherrack 7.5%; far: netherrack 19.9%; biomes: adventure:sulphur_springs 98.2%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t2-nv.png`

### the_ember_fields-surface-lm1

Picked for: surface landmark: biome adventure:nether_jungle (type adventure:crimson_pinewood); ground netherrack (type crimson_nylium); stacked layers 4% (type 0%); liquid cover 14% (type 1%); relief over the patch 99.0 (type 75.0)
Census: sky 0%; near: netherrack 39.1%, twisting_vines_plant 30.1%; biomes: adventure:nether_jungle 97.1%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-lm1.png`

### the_ember_fields-surface-lm1-nv

Picked for: surface landmark: biome adventure:nether_jungle (type adventure:crimson_pinewood); ground netherrack (type crimson_nylium); stacked layers 4% (type 0%); liquid cover 14% (type 1%); relief over the patch 99.0 (type 75.0)
Census: sky 0%; near: netherrack 39.1%, twisting_vines_plant 30.1%; biomes: adventure:nether_jungle 97.1%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
