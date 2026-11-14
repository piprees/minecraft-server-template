# Review packet: the_ember_fields

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_ember_fields.json`

> Rolling ember meadows where piglin villages still tend the geyser fields. The gentlest face of piglin civilisation — farmers and traders, not soldiers. The fortress at the rim is a precaution, not a threat.

Biomes: betternether:nether_grasslands, betternether:poor_nether_grasslands, regions_unexplored:glistering_meadow, betternether:wart_forest_edge, betternether:nether_mushroom_forest, betterend:sulphur_springs, terralith:yellowstone, incendium:quartz_flats, incendium:weeping_valley, minecraft:crimson_forest, minecraft:nether_wastes, incendium:infernal_dunes, incendium:volcanic_deltas

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_ember_fields.json`

> Rolling ember meadows where piglin villages still tend the geyser fields. The gentlest face of piglin civilisation — farmers and traders, not soldiers. The fortress at the rim is a precaution, not a threat.

Biomes: adventure:nether_grasslands, adventure:sulphur_springs, minecraft:crimson_forest, adventure:quartz_flats, adventure:nether_jungle, adventure:crimson_pinewood, adventure:magma_land, adventure:mycotoxic_undergrowth, minecraft:nether_wastes, adventure:gravel_desert, minecraft:sulfur_caves

Terrain: `{"base": "roofed", "params": {"floorHeight": 44, "floorRelief": 8, "ceilingGap": 64, "roughness": 0.1, "flats": 0.2}, "scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c2/runs/c5/full/the_ember_fields/dossier.json`; layer maps in `/tmp/claude-0/w10c2/runs/c5/full/the_ember_fields/layers`

## Summary

- Top blocks seen from the sky: netherrack 24.5%, nether_sprouts 18.5%, crimson_roots 13.3%, warped_roots 9.2%.
- Surface biomes: adventure:nether_grasslands 51.2%, adventure:sulphur_springs 20%, adventure:crimson_pinewood 6.9%, crimson_forest 6.6%, adventure:nether_jungle 4.5%.
- Ground blocks: crimson_nylium 39.8%, netherrack 27.1%, warped_nylium 19.8%, glowstone 2.8%.
- Liquid cover 1.1% water, 1.1% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 8.7%; bare rock 25.8%; no ground (void) 0%.
- Trees: 39.4 per ha (crimson_stem 26.9/ha, mean spacing 4.4 blocks, stripped_crimson_stem 6.6/ha, mean spacing 7.1 blocks, warped_stem 4.7/ha, mean spacing 4.1 blocks, stripped_warped_stem 0.9/ha, mean spacing 10.3 blocks); ground plants grass 23.5%, other 19.3%, mushroom 6.1%, bush 0.1%.
- Surface height p5 37, median 43, p95 114, max 125; median relief per patch 80 blocks; slopes under 15° 61.6%, over 45° 20.2%, cliffs 14.4%; above the clouds 0%, below sea level 0.1%.
- Underground: 16% open, 1.1% liquid; cave biomes adventure:nether_grasslands 40.3%, adventure:sulphur_springs 24%, sulfur_caves 10%, nether_wastes 9.6%.
- Notable clusters, fewest first: red_mushroom 2 clusters, spacing n/a; moss_block 2 clusters, spacing n/a; glow_lichen 3 clusters, spacing n/a; mossy_cobblestone 7 clusters, spacing 14.6; chest 7 clusters, spacing 11.9; spawner 9 clusters, spacing 24.2.
- 11 of 11 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 85.4%, biome match 99.8%.
- Landforms over the border (noise render): plains 100%, hills 0%, mountains 0%, plateaus 0%, basins 0%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 4.5/km² (13 major, largest 51.8%), landform regions 0.2/km² (largest 100.0%), dominant place 100.0% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0%, surface 100%, underground 100%, roof 100%.
- Overhangs in 26.2% of columns, stacked layers in 0%, cave pockets in 18.9%.

## Places

- **surface** (225 cells, 5 types, silhouette 0.568):
  - type 1: 51.6% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 88% (layer 49%); bare rock 1% (layer 26%) — camera [-407.5,45,-7.5]
  - type 2: 25.3% of cells; biome adventure:sulphur_springs; ground netherrack; top netherrack; height 54; neighbour step 7.7 (layer 3.9); relief over 16 blocks 83.1 (layer 67.9); relief over the patch 88.2 (layer 82.6); notable-block density (log) 0.1 (layer 0.6); ground plants 1% (layer 49%); bare rock 88% (layer 26%); cave pockets 37% (layer 19%) — camera [-871.5,48,-936.5]
  - type 3: 16.4% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 47; relief over 16 blocks 56.1 (layer 67.9); trees per cell 4.6 (layer 0.9); canopy 49% (layer 9%); ground plants 18% (layer 49%); cave pockets 7% (layer 19%) — camera [936.5,45,-122.5]
  - type 4: 3.6% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 47; neighbour step 1.9 (layer 3.9); relief over the patch 77.0 (layer 82.6); ground plants 8% (layer 49%); liquid cover 18% (layer 2%); bare rock 4% (layer 26%); overhangs 9% (layer 26%) — camera [936.5,44,-839.5]
  - type 5: 3.1% of cells; biome adventure:mycotoxic_undergrowth; ground moss_block; top moss_block; height 50; relief over the patch 77.0 (layer 82.6); trees per cell 2.0 (layer 0.9); notable-block density (log) 1.0 (layer 0.6); ground plants 4% (layer 49%); bare rock 4% (layer 26%) — camera [280.5,45,840.5]
  - landmark at [-744,904]: biome adventure:gravel_desert (type adventure:sulphur_springs); ground gravel (type netherrack); top gravel (type netherrack); bare rock 17% (type 94%); relief over the patch 76.0 (type 90.0); height 69.9 (type 51.8)
  - landmark at [632,680]: biome sulfur_caves (type adventure:sulphur_springs); stacked layers 2% (type 0%); cave pockets 2% (type 53%); canopy 44% (type 0%)
  - landmark at [600,680]: biome adventure:crimson_pinewood (type adventure:sulphur_springs); cave pockets 2% (type 53%); notable-block density (log) 1.3 (type 0.0); neighbour step 1.2 (type 6.8)
  - landmark at [-760,904]: biome adventure:gravel_desert (type adventure:mycotoxic_undergrowth); ground gravel (type moss_block); top gravel (type moss_block); cave pockets 57% (type 3%); trees per cell 0.0 (type 3.0); notable-block density (log) 0.1 (type 0.6)
  - landmark at [904,-872]: biome adventure:quartz_flats (type adventure:mycotoxic_undergrowth); ground quartz_block (type moss_block); top quartz_block (type moss_block); stacked layers 1% (type 0%); neighbour step 9.4 (type 2.2); trees per cell 0.0 (type 3.0)
- **sky**: no cells.
- **underground** (225 cells, 2 types, silhouette 0.256):
  - type 1: 65.3% of cells; notableId magma_block; biome adventure:nether_grasslands; height 47 — camera [-334.5,47.88,497.5]
  - type 2: 34.7% of cells; notableId lava; biome adventure:sulphur_springs; height 54; neighbour step 7.0 (layer 3.9); relief over 16 blocks 79.9 (layer 67.9); cave air 22% (layer 13%); cave pockets 34% (layer 19%) — camera [459.5,43.88,-610.5]
  - landmark at [952,-136]: biome sulfur_caves (type adventure:sulphur_springs); cave liquid 8% (type 0%); relief over 16 blocks 13.0 (type 83.0); notable-block density (log) 1.8 (type 0.3)
  - landmark at [904,-408]: notableId magma_block (type lava); biome adventure:mycotoxic_undergrowth (type adventure:sulphur_springs); trees per cell 2.0 (type 0.0); cave air 50% (type 21%); cave pockets 59% (type 20%)
  - landmark at [-104,-776]: notableId cobblestone (type lava); biome sulfur_caves (type adventure:sulphur_springs); trees per cell 2.0 (type 0.0); cave pockets 73% (type 20%); height 38.1 (type 52.1)
  - landmark at [872,-408]: notableId lava (type magma_block); biome sulfur_caves (type none); cave liquid 10% (type 0%); cave pockets 68% (type 0%); notable-block density (log) 2.1 (type 0.7)
  - landmark at [888,-392]: notableId blackstone (type magma_block); biome adventure:nether_grasslands (type none); relief over 16 blocks 10.0 (type 71.0); cave pockets 51% (type 0%); cave liquid 5% (type 0%)
- **roof** (225 cells, 5 types, silhouette 0.416):
  - type 1: 39.1% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6) — camera [136.5,102.88,-199.5]
  - type 2: 21.3% of cells; notableId sulfur; height 110; neighbour step 1.7 (layer 0.6); relief over 16 blocks 19.0 (layer 10.3) — camera [-439.5,103.88,-983.5]
  - type 3: 17.8% of cells; notableId nether_gold_ore; height 108; neighbour step 0.1 (layer 0.6); relief over 16 blocks 5.6 (layer 10.3); notable-block density (log) 0.1 (layer 0.2) — camera [-135.5,102.88,792.5]
  - type 4: 12.4% of cells; notableId crimson_nylium; height 111; relief over 16 blocks 13.6 (layer 10.3); notable-block density (log) 0.4 (layer 0.2) — camera [-71.5,101.88,-135.5]
  - type 5: 9.3% of cells; notableId weeping_vines_plant; height 109; neighbour step 0.2 (layer 0.6); relief over 16 blocks 7.1 (layer 10.3); notable-block density (log) 0.6 (layer 0.2) — camera [952.5,103.88,-119.5]
  - landmark at [-104,760]: notableId warped_nylium (type crimson_nylium); height 118.3 (type 110.4); notable-block density (log) 0.9 (type 0.4); relief over 16 blocks 14.0 (type 15.0)
  - landmark at [-120,776]: notableId nether_sprouts (type crimson_nylium); height 114.7 (type 110.4); notable-block density (log) 0.6 (type 0.4); neighbour step 1.3 (type 1.0)
  - landmark at [-104,776]: height 118.7 (type 110.4); notable-block density (log) 1.1 (type 0.4); relief over 16 blocks 13.0 (type 15.0)
  - landmark at [-360,520]: notableId glowstone (type crimson_nylium); height 106.5 (type 110.4); neighbour step 0.5 (type 1.0); relief over 16 blocks 18.0 (type 15.0)
  - landmark at [904,-408]: notableId cave_vines_plant (type crimson_nylium); height 113.7 (type 110.4); notable-block density (log) 0.2 (type 0.4); neighbour step 1.3 (type 1.0)

## Biome expression

11 of 11 placed biomes are expressed.

- adventure:nether_grasslands: expressed, 29492 columns; placement land 99.5%, lava 0.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top nether_sprouts 35.6%, crimson_roots 23.7%, warped_roots 17.9%
- adventure:sulphur_springs: expressed, 11520 columns; placement land 94.7%, water 5.3%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 94.7%, water 5.3%
- adventure:crimson_pinewood: expressed, 3964 columns; placement land 99.5%, lava 0.5%, roofed 100%; snow 0%, canopy 42.4%, trees 129.2/ha; top nether_wart_block 41.8%, crimson_nylium 25.3%, netherrack 18.7%
- minecraft:crimson_forest: expressed, 3782 columns; placement land 99.3%, lava 0.6%, roofed 99.8%, sky 0.2%; snow 0%, canopy 49.5%, trees 239.7/ha; top nether_wart_block 48.1%, crimson_nylium 33.1%, crimson_roots 8.6%
- adventure:nether_jungle: expressed, 2612 columns; placement land 99.3%, lava 0.5%, roofed 99.8%, sky 0.2%; snow 0%, canopy 55.5%, trees 150.4/ha; top nether_wart_block 31.6%, warped_wart_block 16%, netherrack 14.1%
- adventure:magma_land: expressed, 1680 columns; placement land 78.3%, lava 21.7%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top magma_block 51.9%, lava 21.7%, obsidian 11.8%
- adventure:mycotoxic_undergrowth: expressed, 1504 columns; placement land 99.4%, lava 0.6%, roofed 100%; snow 0%, canopy 0.8%, trees 80.3/ha; top moss_block 49.3%, slime_block 26.4%, netherrack 7.4%
- minecraft:nether_wastes: expressed, 1218 columns; placement land 99.6%, lava 0.2%, roofed 99.8%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top netherrack 92.8%, glowstone 1.6%, nether_quartz_ore 1.3%
- adventure:gravel_desert: expressed, 1104 columns; placement land 99.5%, lava 0.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 74.9%, netherrack 11.8%, coarse_dirt 5.5%
- adventure:quartz_flats: expressed, 626 columns; placement land 98.1%, lava 1.6%, roofed 99.7%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 66.5%, netherrack 12.6%, glowstone 7.2%
- minecraft:sulfur_caves: expressed, 121 columns; placement land 62.8%, lava 37.2%, roofed 100%; snow 0%, canopy 22.4%, trees 0/ha; top netherrack 37.2%, lava 37.2%, weeping_vines_plant 9.1%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | betternether:nether_grasslands | adventure:nether_grasslands | 51.2 | 29492 | expressed | - |
| O2 | betternether:poor_nether_grasslands | minecraft:nether_wastes | 2.1 | 1218 | expressed | - |
| O3 | regions_unexplored:glistering_meadow | adventure:nether_grasslands | 51.2 | 29492 | expressed | - |
| O4 | betternether:wart_forest_edge | adventure:crimson_pinewood | 6.9 | 3964 | expressed | - |
| O5 | betternether:nether_mushroom_forest | adventure:mycotoxic_undergrowth | 2.6 | 1504 | expressed | - |
| O6 | betterend:sulphur_springs | adventure:sulphur_springs | 20.0 | 11520 | expressed | - |
| O7 | terralith:yellowstone | minecraft:sulfur_caves | 0.2 | 121 | expressed | - |
| O8 | incendium:quartz_flats | adventure:quartz_flats | 1.1 | 626 | expressed | - |
| O9 | incendium:weeping_valley | adventure:nether_jungle | 4.5 | 2612 | expressed | - |
| O10 | minecraft:crimson_forest | minecraft:crimson_forest | 6.6 | 3782 | expressed | - |
| O11 | minecraft:nether_wastes | minecraft:nether_wastes | 2.1 | 1218 | expressed | - |
| O12 | incendium:infernal_dunes | adventure:gravel_desert | 1.9 | 1104 | expressed | - |
| O13 | incendium:volcanic_deltas | adventure:magma_land | 2.9 | 1680 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:nether_grasslands 51.2%, adventure:sulphur_springs 20.0%, adventure:crimson_pinewood 6.9% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 1.1% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 1.1% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 8.7% (`surface.canopyPercent`) | |
| M:relief | median 80.0 blocks a patch; height {"p5": 37.0, "p25": 42.0, "p50": 43.0, "p75": 45.0, "p95": 114.0, "max": 125.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 100.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"bedWorks": true, "fogColor": "#E8A05A", "ambientLight": 0.25}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_ember_fields-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 10.5%; near: quartz_block 7.1%, quartz_pillar 4.3%; mid: quartz_block 16.6%, quartz_pillar 7.7%; far: netherrack 19.5%, glowstone 7.9%; biomes: adventure:quartz_flats 77.5%, adventure:sulphur_springs 16%, adventure:magma_land 6.3%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-far.png`

### the_ember_fields-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 10.5%; near: quartz_block 7.1%, quartz_pillar 4.3%; mid: quartz_block 16.6%, quartz_pillar 7.7%; far: netherrack 19.5%, glowstone 7.9%; biomes: adventure:quartz_flats 77.5%, adventure:sulphur_springs 16%, adventure:magma_land 6.3%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-far-nv.png`

### the_ember_fields-surface-t1

Picked for: surface type 1: 51.6% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 88% (layer 49%); bare rock 1% (layer 26%)
Census: sky 0%; unloaded 12.2%; near: crimson_roots 23%, warped_roots 11.7%; far: netherrack 25.6%, glowstone 1.1%; biomes: adventure:nether_grasslands 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t1.png`

### the_ember_fields-surface-t1-nv

Picked for: surface type 1: 51.6% of cells; biome adventure:nether_grasslands; ground crimson_nylium; top nether_sprouts; height 48; ground plants 88% (layer 49%); bare rock 1% (layer 26%)
Census: sky 0%; unloaded 10.9%; near: crimson_roots 23%, warped_roots 11.7%; far: netherrack 26.9%, glowstone 1.1%; biomes: adventure:nether_grasslands 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t1-nv.png`

### the_ember_fields-underground-t1

Picked for: underground type 1: 65.3% of cells; notableId magma_block; biome adventure:nether_grasslands; height 47
Census: sky 0%; unloaded 15.2%; near: crimson_roots 19.1%, warped_roots 13.5%; mid: crimson_roots 3.4%, warped_roots 3.1%; far: netherrack 21.3%, glowstone 1.3%; biomes: adventure:nether_grasslands 99.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t1.png`

### the_ember_fields-underground-t1-nv

Picked for: underground type 1: 65.3% of cells; notableId magma_block; biome adventure:nether_grasslands; height 47
Census: sky 0.2%; unloaded 13.8%; near: crimson_roots 19.1%, warped_roots 13.5%; mid: crimson_roots 3.4%, warped_roots 3.1%; far: netherrack 21.8%, glowstone 1.3%; biomes: adventure:nether_grasslands 99.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-underground-t1-nv.png`

### the_ember_fields-roof-t1

Picked for: roof type 1: 39.1% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6)
Census: sky 4.6%; unloaded 3.4%; near: netherrack 76.9%, lava 1.3%; mid: netherrack 10.2%; far: netherrack 1.4%, glowstone 1.1%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-roof-t1.png`

### the_ember_fields-roof-t1-nv

Picked for: roof type 1: 39.1% of cells; notableId nether_quartz_ore; height 108; neighbour step 0.2 (layer 0.6)
Census: sky 5.3%; unloaded 2.8%; near: netherrack 76.9%, lava 1.3%; mid: netherrack 10.2%; far: netherrack 1.4%, glowstone 1.1%; biomes: adventure:nether_grasslands 100%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-roof-t1-nv.png`

### the_ember_fields-surface-t3

Picked for: surface type 3: 16.4% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 47; relief over 16 blocks 56.1 (layer 67.9); trees per cell 4.6 (layer 0.9); canopy 49% (layer 9%); ground plants 18% (layer 49%); cave pockets 7% (layer 19%)
Census: sky 0%; unloaded 0.9%; near: nether_wart_block 62.6%, shroomlight 12.4%; mid: cave_vines_plant 1.9%; far: cave_vines_plant 4.1%, netherrack 1.4%; biomes: adventure:crimson_pinewood 90.3%, adventure:mycotoxic_undergrowth 9.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t3.png`

### the_ember_fields-surface-t3-nv

Picked for: surface type 3: 16.4% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 47; relief over 16 blocks 56.1 (layer 67.9); trees per cell 4.6 (layer 0.9); canopy 49% (layer 9%); ground plants 18% (layer 49%); cave pockets 7% (layer 19%)
Census: sky 0.5%; unloaded 0.4%; near: nether_wart_block 62.6%, shroomlight 12.4%; mid: cave_vines_plant 1.9%; far: cave_vines_plant 4.1%, netherrack 1.4%; biomes: adventure:crimson_pinewood 90.3%, adventure:mycotoxic_undergrowth 9.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-t3-nv.png`

### the_ember_fields-surface-lm1

Picked for: surface landmark: biome adventure:gravel_desert (type adventure:sulphur_springs); ground gravel (type netherrack); top gravel (type netherrack); bare rock 17% (type 94%); relief over the patch 76.0 (type 90.0); height 69.9 (type 51.8)
Census: sky 0%; unloaded 7.5%; near: gravel 35.8%, bone_block 30.2%; mid: netherrack 2.5%; far: netherrack 21.1%; biomes: adventure:gravel_desert 72.5%, nether_wastes 20.9%, adventure:nether_grasslands 6.6%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-lm1.png`

### the_ember_fields-surface-lm1-nv

Picked for: surface landmark: biome adventure:gravel_desert (type adventure:sulphur_springs); ground gravel (type netherrack); top gravel (type netherrack); bare rock 17% (type 94%); relief over the patch 76.0 (type 90.0); height 69.9 (type 51.8)
Census: sky 0%; unloaded 7.3%; near: gravel 35.8%, bone_block 30.2%; mid: netherrack 2.5%; far: netherrack 21.2%; biomes: adventure:gravel_desert 72.4%, nether_wastes 20.9%, adventure:nether_grasslands 6.7%
Image: `/tmp/claude-0/w10c2/reviews/the_ember_fields/screens/the_ember_fields-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
