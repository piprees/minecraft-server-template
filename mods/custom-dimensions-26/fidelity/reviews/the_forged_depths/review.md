# Review packet: the_forged_depths

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_forged_depths.json`

> The last piglin forge still burning. Crimson forges and reactors in a small brutal deep — the fires here never went out because nobody survived to bank them. The deeper you go, the hotter and harder it gets. The heat comes back through the frame, and anything built beside the portal is fuel.

Biomes: incendium:quartz_flats, minecraft:nether_wastes, betternether:crimson_pinewood, betternether:crimson_glowing_woods, minecraft:basalt_deltas, betternether:magma_land, incendium:volcanic_deltas, regions_unexplored:inferno

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_forged_depths.json`

> The last piglin forge still burning. Crimson forges and reactors in a small brutal deep — the fires here never went out because nobody survived to bank them. The deeper you go, the hotter and harder it gets. The heat comes back through the frame, and anything built beside the portal is fuel.

Biomes: adventure:quartz_flats, adventure:crimson_pinewood, minecraft:basalt_deltas, minecraft:crimson_forest, minecraft:nether_wastes, adventure:magma_land

Terrain: `{"base": "roofed", "params": {"floorHeight": 72, "floorRelief": 10, "ceilingGap": 32, "roughness": 0.3, "pit": 0.04, "pitDepth": 34, "ledges": 0.5, "ledgeStep": 8}, "layout": {"type": "radial", "centre": [0, 300], "radius": 380, "falloff": 64, "strength": 1, "warp": {"blocks": 76, "wavelength": 512}}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `runs/r11/full/the_forged_depths/dossier.json`; layer maps in `runs/r11/full/the_forged_depths/layers`

## Summary

- Top blocks seen from the sky: nether_wart_block 21.2%, magma_block 18.6%, crimson_nylium 14.4%, netherrack 8.9%.
- Surface biomes: adventure:crimson_pinewood 37.5%, adventure:magma_land 30.2%, basalt_deltas 10.2%, adventure:quartz_flats 9.9%, crimson_forest 8.5%.
- Ground blocks: crimson_nylium 37.1%, magma_block 22.3%, netherrack 14.1%, basalt 8.2%.
- Liquid cover 0% water, 7.5% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 21.2%; bare rock 17%; no ground (void) 0%.
- Trees: 85.8 per ha (crimson_stem 85.8/ha, mean spacing 5.6 blocks); ground plants grass 7.7%, other 3.7%, mushroom 1.2%.
- Surface height p5 34, median 65, p95 103, max 119; median relief per patch 84 blocks; slopes under 15° 43.9%, over 45° 34.5%, cliffs 17.9%; above the clouds 0%, below sea level 1.4%.
- Underground: 8.4% open, 3.3% liquid; cave biomes adventure:quartz_flats 31.9%, adventure:magma_land 26.4%, adventure:crimson_pinewood 21.1%, basalt_deltas 10.2%.
- Notable clusters, fewest first: nether_bricks 2 clusters, spacing n/a; brown_mushroom 10 clusters, spacing 3.7; crimson_nylium 26 clusters, spacing 21.5; red_mushroom 30 clusters, spacing 4; quartz_pillar 61 clusters, spacing 5; warped_fungus 74 clusters, spacing 11.4.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1116 columns: height within 2 blocks 78.2%, biome match 95.4%.
- Landforms over the border (noise render): plains 81%, hills 11.7%, mountains 0.2%, plateaus 2.8%, basins 4.3%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 4.8/km² (8 major, largest 51.5%), landform regions 4.3/km² (largest 66.6%), dominant place 66.6% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.2%, surface 100%, underground 99.8%, roof 100%.
- Overhangs in 18.8% of columns, stacked layers in 0.2%, cave pockets in 14.1%.

## Places

- **surface** (279 cells, 4 types, silhouette 0.524):
  - type 1: 45.2% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 69; trees per cell 4.4 (layer 2.0); canopy 46% (layer 21%); ground plants 20% (layer 13%); liquid cover 2% (layer 8%) — camera [360.5,62,104.5]
  - type 2: 30.8% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 42; neighbour step 6.5 (layer 4.2); relief over 16 blocks 77.7 (layer 59.3); relief over the patch 96.6 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); liquid cover 15% (layer 8%); bare rock 3% (layer 17%) — camera [232.5,38,56.5]
  - type 3: 13.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 73; relief over 16 blocks 40.8 (layer 59.3); relief over the patch 58.9 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); ground plants 1% (layer 13%); liquid cover 13% (layer 8%); bare rock 74% (layer 17%); overhangs 9% (layer 19%) — camera [-295.5,77,616.5]
  - type 4: 10.8% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 74; neighbour step 6.3 (layer 4.2); relief over the patch 111.3 (layer 84.3); trees per cell 0.4 (layer 2.0); canopy 4% (layer 21%); ground plants 2% (layer 13%); overhangs 33% (layer 19%) — camera [25.5,79,-87.5]
  - landmark at [-520,200]: biome adventure:crimson_pinewood (type adventure:quartz_flats); ground netherrack (type quartz_block); top netherrack (type quartz_block); stacked layers 3% (type 0%); overhangs 82% (type 16%); neighbour step 16.1 (type 7.1)
  - landmark at [440,-552]: top netherrack (type nether_wart_block); overhangs 88% (type 11%); relief over 16 blocks 114.0 (type 43.0); cave pockets 65% (type 4%)
  - landmark at [-520,216]: biome adventure:crimson_pinewood (type adventure:quartz_flats); ground netherrack (type quartz_block); top netherrack (type quartz_block); overhangs 71% (type 16%); cave pockets 75% (type 30%); neighbour step 11.9 (type 7.1)
  - landmark at [8,-344]: biome crimson_forest (type adventure:crimson_pinewood); ground netherrack (type crimson_nylium); top netherrack (type nether_wart_block); cave pockets 84% (type 4%); bare rock 55% (type 0%); ground plants 7% (type 21%)
  - landmark at [-264,-24]: biome crimson_forest (type adventure:crimson_pinewood); ground netherrack (type crimson_nylium); top netherrack (type nether_wart_block); stacked layers 3% (type 0%); bare rock 48% (type 0%); relief over 16 blocks 81.0 (type 43.0)
- **sky**: no cells.
- **underground** (279 cells, 2 types, silhouette 0.249):
  - type 1: 63.4% of cells; notableId magma_block; biome adventure:crimson_pinewood; height 68 — camera [-645.5,73.88,-214.5]
  - type 2: 36.6% of cells; notableId blackstone; biome adventure:quartz_flats; height 51; neighbour step 6.8 (layer 4.1); relief over 16 blocks 75.1 (layer 57.8); cave air 14% (layer 9%) — camera [-116.5,39.88,-2.5]
  - landmark at [472,184]: notableId lava (type magma_block); biome adventure:crimson_pinewood (type none); trees per cell 2.0 (type 0.0); notable-block density (log) 4.0 (type 1.4); cave pockets 45% (type 0%)
  - landmark at [-520,216]: biome adventure:crimson_pinewood (type none); cave pockets 75% (type 0%); cave air 33% (type 1%); neighbour step 11.9 (type 1.7)
  - landmark at [-488,56]: notableId lava (type magma_block); biome adventure:magma_land (type none); cave pockets 75% (type 0%); cave liquid 22% (type 0%); notable-block density (log) 3.7 (type 1.4)
  - landmark at [456,-552]: notableId lava (type magma_block); biome adventure:crimson_pinewood (type none); cave liquid 29% (type 0%); notable-block density (log) 4.3 (type 1.4); cave pockets 67% (type 0%)
  - landmark at [-520,200]: biome adventure:crimson_pinewood (type adventure:quartz_flats); cave air 59% (type 10%); layer present in 93% (type 100%); height 91.8 (type 43.4)
- **roof** (279 cells, 4 types, silhouette 0.415):
  - type 1: 60.2% of cells; notableId nether_quartz_ore; height 105 — camera [-647.5,98.88,-215.5]
  - type 2: 15.4% of cells; notableId glowstone; height 107; neighbour step 0.8 (layer 0.5); relief over 16 blocks 15.5 (layer 12.5) — camera [-7.5,102.88,-23.5]
  - type 3: 14.0% of cells; notableId nether_gold_ore; height 105; notable-block density (log) 0.1 (layer 0.3) — camera [-199.5,99.88,-279.5]
  - type 4: 10.4% of cells; notableId blackstone; height 105; notable-block density (log) 1.2 (layer 0.3) — camera [-103.5,97.88,376.5]
  - landmark at [264,520]: notableId blackstone (type glowstone); neighbour step 1.8 (type 0.6); height 114.8 (type 108.3); relief over 16 blocks 13.0 (type 16.0)
  - landmark at [-520,200]: neighbour step 2.3 (type 0.3); height 114.6 (type 103.9); relief over 16 blocks 18.0 (type 13.0)
  - landmark at [-552,632]: notableId blackstone (type glowstone); neighbour step 2.1 (type 0.6); height 112.5 (type 108.3); relief over 16 blocks 17.0 (type 16.0)
  - landmark at [-520,216]: neighbour step 2.3 (type 0.3); height 112.5 (type 103.9); relief over 16 blocks 18.0 (type 13.0)
  - landmark at [-536,648]: notableId lava (type glowstone); neighbour step 1.4 (type 0.6); height 111.8 (type 108.3); notable-block density (log) 0.1 (type 0.4)

## Biome expression

6 of 6 placed biomes are expressed.

- adventure:crimson_pinewood: expressed, 26804 columns; placement land 99.1%, lava 0.8%, roofed 99.9%, sky 0.1%; snow 0%, canopy 45.3%, trees 158.9/ha; top nether_wart_block 44.7%, crimson_nylium 31.3%, crimson_roots 9.3%
- adventure:magma_land: expressed, 21537 columns; placement land 83%, lava 17%, roofed 100%; snow 0%, canopy 0.6%, trees 0/ha; top magma_block 59.4%, lava 17%, fire 11.6%
- minecraft:basalt_deltas: expressed, 7297 columns; placement land 82.5%, lava 17.4%, roofed 99.8%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top basalt 65.1%, lava 17.4%, blackstone 9%
- adventure:quartz_flats: expressed, 7106 columns; placement land 97.5%, lava 1.6%, roofed 99.1%, sky 0.9%; snow 0%, canopy 0.6%, trees 1.4/ha; top quartz_block 55.9%, glowstone 23.5%, netherrack 12.3%
- minecraft:crimson_forest: expressed, 6125 columns; placement land 98.7%, lava 1%, roofed 99.7%, sky 0.3%; snow 0%, canopy 49.2%, trees 233.3/ha; top nether_wart_block 48.3%, crimson_nylium 30.2%, netherrack 9.8%
- minecraft:nether_wastes: expressed, 2689 columns; placement land 97.8%, lava 2.2%, roofed 100%; snow 0%, canopy 0.5%, trees 3.8/ha; top netherrack 69.8%, nether_bricks 22.1%, lava 2.2%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | incendium:quartz_flats | adventure:quartz_flats | 9.9 | 7106 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 3.8 | 2689 | expressed | - |
| O3 | betternether:crimson_pinewood | adventure:crimson_pinewood | 37.5 | 26804 | expressed | - |
| O4 | betternether:crimson_glowing_woods | minecraft:crimson_forest | 8.5 | 6125 | expressed | - |
| O5 | minecraft:basalt_deltas | minecraft:basalt_deltas | 10.2 | 7297 | expressed | - |
| O6 | betternether:magma_land | adventure:magma_land | 30.2 | 21537 | expressed | - |
| O7 | incendium:volcanic_deltas | adventure:magma_land | 30.2 | 21537 | expressed | - |
| O8 | regions_unexplored:inferno | adventure:magma_land | 30.2 | 21537 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:crimson_pinewood 37.5%, adventure:magma_land 30.2%, minecraft:basalt_deltas 10.2% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 7.5% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 21.2% (`surface.canopyPercent`) | |
| M:relief | median 84.0 blocks a patch; height {"p5": 34.0, "p25": 38.0, "p50": 65.0, "p75": 72.0, "p95": 103.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 0.2, "surface": 100.0, "underground": 99.8, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#4B2B1B", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_forged_depths-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 4.6%; near: basalt 30.2%, glowstone 7.4%; mid: basalt 39.8%, lava 3.3%; far: basalt 6.7%, nether_wart_block 2.4%; biomes: basalt_deltas 96.2%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-far.png`

### the_forged_depths-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 4.6%; near: basalt 30.2%, glowstone 7.4%; mid: basalt 39.8%, lava 3.3%; far: basalt 6.7%, nether_wart_block 2.4%; biomes: basalt_deltas 96.2%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-far-nv.png`

### the_forged_depths-surface-t1

Picked for: surface type 1: 45.2% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 69; trees per cell 4.4 (layer 2.0); canopy 46% (layer 21%); ground plants 20% (layer 13%); liquid cover 2% (layer 8%)
Census: sky 0%; unloaded 0.7%; near: nether_wart_block 31.1%, crimson_roots 29.6%; mid: crimson_nylium 1.1%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t1.png`

### the_forged_depths-surface-t1-nv

Picked for: surface type 1: 45.2% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top nether_wart_block; height 69; trees per cell 4.4 (layer 2.0); canopy 46% (layer 21%); ground plants 20% (layer 13%); liquid cover 2% (layer 8%)
Census: sky 0%; unloaded 0.7%; near: nether_wart_block 31.1%, crimson_roots 29.6%; mid: crimson_nylium 1.1%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t1-nv.png`

### the_forged_depths-roof-t1

Picked for: roof type 1: 60.2% of cells; notableId nether_quartz_ore; height 105
Census: sky 0.9%; unloaded 1%; near: netherrack 69.6%, weeping_vines_plant 11.5%; mid: netherrack 6.7%, weeping_vines_plant 2.5%; far: basalt 1.1%; biomes: adventure:crimson_pinewood 98.6%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-roof-t1.png`

### the_forged_depths-roof-t1-nv

Picked for: roof type 1: 60.2% of cells; notableId nether_quartz_ore; height 105
Census: sky 1%; unloaded 0.7%; near: netherrack 69.6%, weeping_vines_plant 11.5%; mid: netherrack 6.7%, weeping_vines_plant 2.5%; far: basalt 1.3%; biomes: adventure:crimson_pinewood 98.5%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-roof-t1-nv.png`

### the_forged_depths-surface-t2

Picked for: surface type 2: 30.8% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 42; neighbour step 6.5 (layer 4.2); relief over 16 blocks 77.7 (layer 59.3); relief over the patch 96.6 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); liquid cover 15% (layer 8%); bare rock 3% (layer 17%)
Census: sky 7.5%; near: magma_block 26.6%, fire 13.4%; mid: magma_block 4.3%, fire 1%; far: netherrack 14.1%, glowstone 11.5%; biomes: adventure:magma_land 62.1%, adventure:quartz_flats 22.1%, basalt_deltas 9.1%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t2.png`

### the_forged_depths-surface-t2-nv

Picked for: surface type 2: 30.8% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 42; neighbour step 6.5 (layer 4.2); relief over 16 blocks 77.7 (layer 59.3); relief over the patch 96.6 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); liquid cover 15% (layer 8%); bare rock 3% (layer 17%)
Census: sky 7.5%; near: magma_block 26.6%, fire 13.4%; mid: magma_block 4.3%, fire 1%; far: netherrack 14.1%, glowstone 11.4%; biomes: adventure:magma_land 62.1%, adventure:quartz_flats 22.1%, basalt_deltas 9.1%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t2-nv.png`

### the_forged_depths-surface-t3

Picked for: surface type 3: 13.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 73; relief over 16 blocks 40.8 (layer 59.3); relief over the patch 58.9 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); ground plants 1% (layer 13%); liquid cover 13% (layer 8%); bare rock 74% (layer 17%); overhangs 9% (layer 19%)
Census: sky 0%; near: basalt 91%; mid: basalt 8.5%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t3.png`

### the_forged_depths-surface-t3-nv

Picked for: surface type 3: 13.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 73; relief over 16 blocks 40.8 (layer 59.3); relief over the patch 58.9 (layer 84.3); trees per cell 0.0 (layer 2.0); canopy 0% (layer 21%); ground plants 1% (layer 13%); liquid cover 13% (layer 8%); bare rock 74% (layer 17%); overhangs 9% (layer 19%)
Census: sky 0%; near: basalt 91%; mid: basalt 8.5%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t3-nv.png`

### the_forged_depths-surface-t4

Picked for: surface type 4: 10.8% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 74; neighbour step 6.3 (layer 4.2); relief over the patch 111.3 (layer 84.3); trees per cell 0.4 (layer 2.0); canopy 4% (layer 21%); ground plants 2% (layer 13%); overhangs 33% (layer 19%)
Census: sky 0.2%; near: quartz_block 34.6%, smooth_quartz_slab 5.9%; mid: netherrack 14.8%, glowstone 14.6%; far: glowstone 4.5%, nether_wart_block 2.7%; biomes: adventure:quartz_flats 94.7%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t4.png`

### the_forged_depths-surface-t4-nv

Picked for: surface type 4: 10.8% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 74; neighbour step 6.3 (layer 4.2); relief over the patch 111.3 (layer 84.3); trees per cell 0.4 (layer 2.0); canopy 4% (layer 21%); ground plants 2% (layer 13%); overhangs 33% (layer 19%)
Census: sky 0.2%; near: quartz_block 34.6%, smooth_quartz_slab 5.9%; mid: netherrack 14.8%, glowstone 14.6%; far: glowstone 4.5%, nether_wart_block 2.7%; biomes: adventure:quartz_flats 94.8%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-surface-t4-nv.png`

### the_forged_depths-landing

Picked for: the landing: spawn in the forge, looking south into the deep (centre [0, 300], floor 34 blocks down)
Census: sky 5%; near: quartz_block 42.9%, netherrack 12.3%; mid: netherrack 3.8%, glowstone 3.3%; far: glowstone 8.5%, netherrack 7.5%; biomes: adventure:quartz_flats 91.2%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-landing.png`

### the_forged_depths-landing-nv

Picked for: the landing: spawn in the forge, looking south into the deep (centre [0, 300], floor 34 blocks down)
Census: sky 5%; near: quartz_block 42.9%, netherrack 12.3%; mid: netherrack 3.8%, glowstone 3.3%; far: glowstone 8.5%, netherrack 7.5%; biomes: adventure:quartz_flats 91.2%
Image: `/tmp/claude-0/w10c2/reviews/the_forged_depths/screens/the_forged_depths-landing-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
