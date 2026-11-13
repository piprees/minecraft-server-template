# Review packet: the_gilded_pit

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_gilded_pit.json`

> The piglin capital. Every portal drops you at the same ledge overlooking what remains: a bastion, a forge, a palace, a forbidden castle — gold in every wall, treasure in every ruin. Nothing within arm's reach of the landing. Greed pays and costs.

Biomes: betternether:gravel_desert, betternether:poor_nether_grasslands, minecraft:nether_wastes, minecraft:basalt_deltas, minecraft:crimson_forest, regions_unexplored:inferno

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_gilded_pit.json`

> The piglin capital. Every portal drops you at the same ledge overlooking what remains: a bastion, a forge, a palace, a forbidden castle — gold in every wall, treasure in every ruin. Nothing within arm's reach of the landing. Greed pays and costs.

Biomes: adventure:blackstone_basin, minecraft:nether_wastes, minecraft:basalt_deltas, adventure:gravel_desert, minecraft:crimson_forest, adventure:magma_land

Terrain: `{"base": "roofed", "params": {"floorHeight": 84, "floorRelief": 6, "ceilingGap": 22, "roughness": 0.2, "pit": 0.02, "pitDepth": 60, "ledges": 0.7, "ledgeStep": 10}, "layout": {"type": "radial", "centre": [0, 0], "radius": 470, "falloff": 48, "strength": 1, "warp": {"blocks": 38, "wavelength": 256}}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `runs/r11/full/the_gilded_pit/dossier.json`; layer maps in `runs/r11/full/the_gilded_pit/layers`

## Summary

- Top blocks seen from the sky: netherrack 42.2%, blackstone 34.7%, lava 7.4%, magma_block 3.8%.
- Surface biomes: nether_wastes 44.6%, adventure:blackstone_basin 41%, adventure:magma_land 7%, adventure:gravel_desert 3.8%, crimson_forest 2.4%.
- Ground blocks: netherrack 44%, blackstone 39.5%, magma_block 4.6%, gravel 3.5%.
- Liquid cover 0% water, 7.4% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.2%; bare rock 77.7%; no ground (void) 0%.
- Trees: 5.3 per ha (crimson_stem 5.3/ha, mean spacing 3.7 blocks); ground plants other 1%, grass 0.5%, mushroom 0.2%, bush 0.1%.
- Surface height p5 23, median 77, p95 87, max 119; median relief per patch 78 blocks; slopes under 15° 47.8%, over 45° 29.2%, cliffs 9.9%; above the clouds 0%, below sea level 1.9%.
- Underground: 7.6% open, 1.6% liquid; cave biomes nether_wastes 45%, adventure:blackstone_basin 35.6%, adventure:magma_land 12.4%, adventure:gravel_desert 5.9%.
- Notable clusters, fewest first: soul_sand 6 clusters, spacing 12.7; basalt 9 clusters, spacing 1.6; coarse_dirt 18 clusters, spacing 6.5; dead_bush 28 clusters, spacing 2.7; glowstone 37 clusters, spacing 18.4; red_mushroom 37 clusters, spacing 3.6.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 76.9%, biome match 100%.
- Landforms over the border (noise render): plains 56.4%, hills 10.1%, mountains 19.8%, plateaus 2.8%, basins 10.8%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 3.2/km² (6 major, largest 50.7%), landform regions 5.3/km² (largest 39.4%), dominant place 50.7% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.1%, surface 100%, underground 98.9%, roof 100%.
- Overhangs in 10.7% of columns, stacked layers in 0.1%, cave pockets in 13.7%.

## Places

- **surface** (243 cells, 3 types, silhouette 0.482):
  - type 1: 48.6% of cells; biome nether_wastes; ground netherrack; top netherrack; height 71; liquid cover 1% (layer 7%) — camera [-407.5,75,-519.5]
  - type 2: 42.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 48; liquid cover 14% (layer 7%) — camera [-199.5,28,-8.5]
  - type 3: 9.5% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 82; trees per cell 1.4 (layer 0.1); notable-block density (log) 0.6 (layer 0.3); canopy 12% (layer 1%); ground plants 14% (layer 2%); bare rock 10% (layer 78%); stacked layers 0% (layer 0%) — camera [360.5,85,631.5]
  - landmark at [328,-344]: ground bedrock (type blackstone); top lava (type blackstone); liquid cover 82% (type 7%); neighbour step 13.8 (type 1.1); relief over 16 blocks 112.0 (type 11.0)
  - landmark at [120,488]: biome basalt_deltas (type adventure:blackstone_basin); ground basalt (type blackstone); top basalt (type blackstone); height 84.3 (type 25.7); liquid cover 33% (type 7%); cave pockets 42% (type 0%)
  - landmark at [-136,-408]: ground soul_sand (type netherrack); top soul_sand (type netherrack); notable-block density (log) 1.8 (type 0.1); neighbour step 8.4 (type 0.9); bare rock 36% (type 99%)
  - landmark at [-248,632]: biome adventure:gravel_desert (type nether_wastes); ground gravel (type netherrack); top gravel (type netherrack); bare rock 1% (type 99%); cave pockets 52% (type 0%); relief over 16 blocks 6.0 (type 39.0)
  - landmark at [120,504]: biome basalt_deltas (type nether_wastes); ground basalt (type netherrack); top basalt (type netherrack); notable-block density (log) 1.5 (type 0.1); cave pockets 32% (type 0%); relief over the patch 93.0 (type 62.0)
- **sky**: no cells.
- **underground** (242 cells, 3 types, silhouette 0.332):
  - type 1: 49.2% of cells; notableId magma_block; biome nether_wastes; height 81; notable-block density (log) 1.5 (layer 0.9) — camera [-482.5,32.88,173.5]
  - type 2: 27.7% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 35; notable-block density (log) 0.3 (layer 0.9); cave pockets 2% (layer 14%) — camera [-103.5,43.88,-385.5]
  - type 3: 23.1% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 58; neighbour step 4.5 (layer 2.1); relief over 16 blocks 60.0 (layer 41.3); notable-block density (log) 0.5 (layer 0.9); cave air 18% (layer 8%) — camera [149.5,53.88,-241.5]
  - landmark at [552,616]: notableId lava (type magma_block); biome adventure:magma_land (type nether_wastes); cave liquid 12% (type 0%); cave pockets 81% (type 9%); notable-block density (log) 3.5 (type 1.3)
  - landmark at [-248,616]: notableId bone_block (type magma_block); biome adventure:gravel_desert (type nether_wastes); cave pockets 93% (type 9%); cave liquid 13% (type 0%); cave air 39% (type 1%)
  - landmark at [40,-104]: layer present in 59% (type 100%); neighbour step 13.7 (type 2.6); cave air 55% (type 10%)
  - landmark at [-248,632]: notableId lava (type magma_block); biome adventure:gravel_desert (type nether_wastes); notable-block density (log) 3.5 (type 1.3); cave liquid 9% (type 0%); cave pockets 52% (type 9%)
  - landmark at [360,-360]: notableId nether_quartz_ore (type magma_block); neighbour step 5.4 (type 0.5); cave air 24% (type 1%); layer present in 92% (type 100%)
- **roof** (243 cells, 6 types, silhouette 0.413):
  - type 1: 30.5% of cells; notableId nether_quartz_ore; height 106 — camera [-487.5,102.88,168.5]
  - type 2: 24.3% of cells; notableId gilded_blackstone; height 106; neighbour step 0.0 (layer 0.4); relief over 16 blocks 1.4 (layer 8.4) — camera [-247.5,101.88,-279.5]
  - type 3: 16.9% of cells; notableId nether_gold_ore; height 107 — camera [-567.5,102.88,-359.5]
  - type 4: 12.3% of cells; notableId gilded_blackstone; height 109; neighbour step 0.9 (layer 0.4); relief over 16 blocks 15.1 (layer 8.4) — camera [168.5,101.88,-247.5]
  - type 5: 8.6% of cells; notableId nether_quartz_ore; height 112; neighbour step 1.1 (layer 0.4); relief over 16 blocks 15.7 (layer 8.4) — camera [-455.5,100.88,56.5]
  - type 6: 7.4% of cells; notableId glowstone; height 108; relief over 16 blocks 12.2 (layer 8.4); notable-block density (log) 0.3 (layer 0.1) — camera [-647.5,101.88,-631.5]
  - landmark at [-360,136]: height 118.2 (type 111.7); neighbour step 0.7 (type 1.0); notable-block density (log) 0.0 (type 0.1)
  - landmark at [120,504]: notableId blackstone (type gilded_blackstone); notable-block density (log) 1.3 (type 0.1); height 105.0 (type 107.0); relief over 16 blocks 0.0 (type 1.0)
  - landmark at [488,40]: neighbour step 2.1 (type 1.0); notable-block density (log) 0.0 (type 0.1); relief over 16 blocks 15.0 (type 17.0)
  - landmark at [-232,616]: notableId bone_block (type nether_quartz_ore); height 117.2 (type 111.7); neighbour step 1.4 (type 1.0); notable-block density (log) 0.1 (type 0.1)
  - landmark at [120,488]: notableId blackstone (type gilded_blackstone); notable-block density (log) 1.8 (type 0.1); height 105.5 (type 107.0); neighbour step 0.0 (type 0.0)

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:nether_wastes: expressed, 27729 columns; placement land 98.9%, lava 1.1%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 91.9%, soul_sand 2.2%, lava 1.1%
- adventure:blackstone_basin: expressed, 25522 columns; placement land 86.1%, lava 13.9%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top blackstone 82.8%, lava 13.9%, polished_blackstone 2.2%
- adventure:magma_land: expressed, 4356 columns; placement land 86%, lava 13.9%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top magma_block 49.8%, obsidian 14%, lava 13.9%
- adventure:gravel_desert: expressed, 2368 columns; placement land 99.6%, lava 0.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 86.1%, netherrack 7.5%, coarse_dirt 2.2%
- minecraft:crimson_forest: expressed, 1483 columns; placement land 98.1%, lava 1.1%, roofed 99.3%, sky 0.7%; snow 0%, canopy 49.6%, trees 226.8/ha; top nether_wart_block 47.5%, crimson_nylium 30.7%, crimson_roots 9.8%
- minecraft:basalt_deltas: expressed, 784 columns; placement land 84.4%, lava 15.6%, roofed 100%; snow 0%, canopy 4.2%, trees 0/ha; top basalt 55.5%, blackstone 16.5%, lava 15.6%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | betternether:gravel_desert | adventure:gravel_desert | 3.8 | 2368 | expressed | - |
| O2 | betternether:poor_nether_grasslands | minecraft:nether_wastes | 44.6 | 27729 | expressed | - |
| O3 | minecraft:nether_wastes | minecraft:nether_wastes | 44.6 | 27729 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 1.3 | 784 | expressed | - |
| O5 | minecraft:crimson_forest | minecraft:crimson_forest | 2.4 | 1483 | expressed | - |
| O6 | regions_unexplored:inferno | adventure:magma_land | 7.0 | 4356 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:blackstone_basin | 41.0 | 25522 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:nether_wastes 44.6%, adventure:blackstone_basin 41.0%, adventure:magma_land 7.0% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 7.4% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 1.2% (`surface.canopyPercent`) | |
| M:relief | median 78.0 blocks a patch; height {"p5": 23.0, "p25": 27.0, "p50": 77.0, "p75": 83.0, "p95": 87.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 100.0, "underground": 98.9, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 59.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_gilded_pit-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 3.3%; near: netherrack 38.2%; mid: netherrack 45.5%, lava 1.5%; far: netherrack 7.8%, blackstone 1.2%; biomes: nether_wastes 98.7%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-far.png`

### the_gilded_pit-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 3.3%; near: netherrack 38.2%; mid: netherrack 45.5%, lava 1.5%; far: netherrack 7.8%, blackstone 1.2%; biomes: nether_wastes 98.7%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-far-nv.png`

### the_gilded_pit-underground-t1

Picked for: underground type 1: 49.2% of cells; notableId magma_block; biome nether_wastes; height 81; notable-block density (log) 1.5 (layer 0.9)
Census: sky 5.1%; near: netherrack 55.6%, nether_quartz_ore 1.9%; mid: netherrack 23.7%, lava 1%; far: netherrack 7.2%, blackstone 3.3%; biomes: nether_wastes 96.4%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-underground-t1.png`

### the_gilded_pit-underground-t1-nv

Picked for: underground type 1: 49.2% of cells; notableId magma_block; biome nether_wastes; height 81; notable-block density (log) 1.5 (layer 0.9)
Census: sky 5.1%; near: netherrack 55.6%, nether_quartz_ore 1.9%; mid: netherrack 23.7%, lava 1%; far: netherrack 7.2%, blackstone 3.3%; biomes: nether_wastes 96.4%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-underground-t1-nv.png`

### the_gilded_pit-roof-t1

Picked for: roof type 1: 30.5% of cells; notableId nether_quartz_ore; height 106
Census: sky 1.8%; unloaded 0.4%; near: netherrack 83%; mid: netherrack 5.5%, glowstone 1.6%; far: netherrack 2.9%, nether_wart_block 1.3%; biomes: nether_wastes 96.8%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-roof-t1.png`

### the_gilded_pit-roof-t1-nv

Picked for: roof type 1: 30.5% of cells; notableId nether_quartz_ore; height 106
Census: sky 2.1%; near: netherrack 83%; mid: netherrack 5.5%, glowstone 1.6%; far: netherrack 2.9%, nether_wart_block 1.3%; biomes: nether_wastes 96.8%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-roof-t1-nv.png`

### the_gilded_pit-surface-t2

Picked for: surface type 2: 42.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 48; liquid cover 14% (layer 7%)
Census: sky 0%; near: blackstone 76.8%; mid: blackstone 1.5%; far: blackstone 16.6%, basalt 3.3%; biomes: adventure:blackstone_basin 94.7%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-surface-t2.png`

### the_gilded_pit-surface-t2-nv

Picked for: surface type 2: 42.0% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 48; liquid cover 14% (layer 7%)
Census: sky 0%; near: blackstone 76.8%; mid: blackstone 1.5%; far: blackstone 16.6%, basalt 3.3%; biomes: adventure:blackstone_basin 94.7%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-surface-t2-nv.png`

### the_gilded_pit-landing

Picked for: the landing: spawn on the wastes ledge, looking south over the pit (centre 560 blocks away, floor 60 blocks down)
Census: sky 4.3%; near: netherrack 53.6%; mid: netherrack 23.1%; far: blackstone 11.4%, netherrack 1.9%; biomes: nether_wastes 86.8%, adventure:blackstone_basin 13.2%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-landing.png`

### the_gilded_pit-landing-nv

Picked for: the landing: spawn on the wastes ledge, looking south over the pit (centre 560 blocks away, floor 60 blocks down)
Census: sky 4.3%; near: netherrack 53.6%; mid: netherrack 23.1%; far: blackstone 11.3%, netherrack 1.9%; biomes: nether_wastes 86.8%, adventure:blackstone_basin 13.2%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-landing-nv.png`

### the_gilded_pit-surface-lm1

Picked for: surface landmark: ground bedrock (type blackstone); top lava (type blackstone); liquid cover 82% (type 7%); neighbour step 13.8 (type 1.1); relief over 16 blocks 112.0 (type 11.0)
Census: sky 0.1%; near: blackstone 48%, lava 40.3%; far: blackstone 10.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-surface-lm1.png`

### the_gilded_pit-surface-lm1-nv

Picked for: surface landmark: ground bedrock (type blackstone); top lava (type blackstone); liquid cover 82% (type 7%); neighbour step 13.8 (type 1.1); relief over 16 blocks 112.0 (type 11.0)
Census: sky 0.1%; near: blackstone 48%, lava 40.3%; far: blackstone 10.9%; biomes: adventure:blackstone_basin 100%
Image: `/tmp/claude-0/w10c2/reviews/the_gilded_pit/screens/the_gilded_pit-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
