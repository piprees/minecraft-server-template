# Review packet: the_gilded_pit

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_gilded_pit.json`

> The piglin capital. Every portal drops you at the same ledge overlooking what remains: a bastion, a forge, a palace, a forbidden castle — gold in every wall, treasure in every ruin. Nothing within arm's reach of the landing. Greed pays and costs.

Biomes: betternether:gravel_desert, betternether:poor_nether_grasslands, minecraft:nether_wastes, minecraft:basalt_deltas, minecraft:crimson_forest, regions_unexplored:inferno

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_gilded_pit.json`

> The piglin capital. Every portal drops you at the same ledge overlooking what remains: a bastion, a forge, a palace, a forbidden castle — gold in every wall, treasure in every ruin. Nothing within arm's reach of the landing. Greed pays and costs.

Biomes: adventure:blackstone_basin, minecraft:nether_wastes, minecraft:basalt_deltas, adventure:gravel_desert, minecraft:crimson_forest, adventure:magma_land

Terrain: `{"base": "roofed", "params": {"floorHeight": 84, "floorRelief": 6, "ceilingGap": 22, "roughness": 0.2, "pit": 0.02, "pitDepth": 60, "ledges": 0.7, "ledgeStep": 10}, "layout": {"type": "radial", "centre": [0, 0], "radius": 470, "falloff": 32, "strength": 1, "warp": {"blocks": 12, "wavelength": 256}}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_gilded_pit/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_gilded_pit/layers`

## Summary

- Top blocks seen from the sky: netherrack 38.6%, blackstone 33%, lava 7.1%, magma_block 3.8%.
- Surface biomes: nether_wastes 44.6%, adventure:blackstone_basin 41%, adventure:magma_land 7%, adventure:gravel_desert 3.8%, crimson_forest 2.4%.
- Ground blocks: netherrack 40.3%, blackstone 37.7%, magma_block 4.6%, gravel 3.4%.
- Liquid cover 0% water, 7.1% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 1.3%; bare rock 73.3%; no ground (void) 0%.
- Trees: 21.9 per ha (stripped_crimson_stem 13.5/ha, mean spacing 3.3 blocks, crimson_stem 7.1/ha, mean spacing 3.9 blocks, stripped_warped_stem 1/ha, mean spacing 5.1 blocks, warped_stem 0.3/ha, mean spacing 30.4 blocks); ground plants other 0.9%, grass 0.4%, mushroom 0.2%, bush 0.1%.
- Surface height p5 23, median 78, p95 97, max 119; median relief per patch 78 blocks; slopes under 15° 45%, over 45° 31.8%, cliffs 12.4%; above the clouds 0%, below sea level 1.6%.
- Underground: 13.1% open, 1.5% liquid; cave biomes nether_wastes 62.9%, adventure:blackstone_basin 26%, adventure:magma_land 7.1%, adventure:gravel_desert 3.4%.
- Notable clusters, fewest first: lever 2 clusters, spacing n/a; iron_block 2 clusters, spacing n/a; smithing_table 2 clusters, spacing n/a; blast_furnace 2 clusters, spacing n/a; lava_cauldron 2 clusters, spacing n/a; grindstone 3 clusters, spacing 16.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 70.8%, biome match 100%.
- Landforms over the border (noise render): plains 57%, hills 9.6%, mountains 20.1%, plateaus 2.6%, basins 10.6%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 3.2/km² (6 major, largest 50.7%), landform regions 5.3/km² (largest 40.0%), dominant place 50.7% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.2%, surface 100%, underground 99%, roof 100%.
- Overhangs in 17.2% of columns, stacked layers in 0.2%, cave pockets in 21.9%.

## Places

- **surface** (243 cells, 3 types, silhouette 0.461):
  - type 1: 49.0% of cells; biome nether_wastes; ground netherrack; top netherrack; height 74 — camera [600.5,84,-167.5]
  - type 2: 41.6% of cells; biome adventure:blackstone_basin; ground blackstone; top blackstone; height 51; liquid cover 13% (layer 7%) — camera [-199.5,28,-8.5]
  - type 3: 9.5% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 83; trees per cell 1.7 (layer 0.2); notable-block density (log) 0.6 (layer 0.3); canopy 13% (layer 1%); ground plants 13% (layer 2%); bare rock 11% (layer 73%) — camera [360.5,85,631.5]
  - landmark at [328,-344]: ground bedrock (type blackstone); top lava (type blackstone); liquid cover 77% (type 1%); neighbour step 14.1 (type 1.2); relief over 16 blocks 112.0 (type 26.0)
  - landmark at [-136,-408]: ground soul_sand (type netherrack); top soul_sand (type netherrack); notable-block density (log) 1.8 (type 0.2); neighbour step 8.4 (type 0.6); relief over 16 blocks 93.0 (type 33.0)
  - landmark at [-456,56]: ground nether_bricks (type netherrack); top nether_bricks (type netherrack); neighbour step 8.3 (type 0.6); overhangs 57% (type 12%); relief over 16 blocks 92.0 (type 33.0)
  - landmark at [-584,-344]: ground nether_bricks (type netherrack); top nether_bricks (type netherrack); notable-block density (log) 1.6 (type 0.2); overhangs 81% (type 12%); bare rock 11% (type 96%)
  - landmark at [-600,-344]: ground nether_bricks (type netherrack); top nether_bricks (type netherrack); overhangs 100% (type 12%); bare rock 2% (type 96%); cave pockets 100% (type 20%)
- **sky** (1 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:blackstone_basin; ground blackstone; height 60 — camera [-7.5,63,343.5]
- **underground** (243 cells, 2 types, silhouette 0.262):
  - type 1: 56.0% of cells; notableId magma_block; biome nether_wastes; height 80; notable-block density (log) 1.7 (layer 1.1) — camera [-469.5,83.88,149.5]
  - type 2: 44.0% of cells; notableId gilded_blackstone; biome adventure:blackstone_basin; height 48; notable-block density (log) 0.4 (layer 1.1) — camera [180.5,22.88,-251.5]
  - landmark at [344,-344]: notableId lava (type magma_block); biome adventure:blackstone_basin (type nether_wastes); cave liquid 11% (type 0%); cave air 47% (type 3%); notable-block density (log) 4.0 (type 1.7)
  - landmark at [328,-344]: layer present in 27% (type 100%); neighbour step 33.1 (type 1.6); cave liquid 17% (type 0%)
  - landmark at [40,-104]: layer present in 59% (type 100%); neighbour step 13.7 (type 1.6); cave air 55% (type 2%)
  - landmark at [-584,-344]: notableId nether_bricks (type magma_block); notable-block density (log) 5.2 (type 1.7); cave air 59% (type 3%); cave pockets 100% (type 20%)
  - landmark at [-248,616]: notableId bone_block (type magma_block); biome adventure:gravel_desert (type nether_wastes); cave liquid 13% (type 0%); cave pockets 93% (type 20%); cave air 39% (type 3%)
- **roof** (243 cells, 4 types, silhouette 0.359):
  - type 1: 39.9% of cells; notableId nether_quartz_ore; height 107 — camera [488.5,102.88,216.5]
  - type 2: 24.3% of cells; notableId gilded_blackstone; height 106; neighbour step 0.0 (layer 0.4); relief over 16 blocks 1.5 (layer 8.7) — camera [-247.5,101.88,-279.5]
  - type 3: 18.1% of cells; notableId nether_gold_ore; height 107 — camera [-567.5,102.88,-359.5]
  - type 4: 17.7% of cells; notableId gilded_blackstone; height 110; neighbour step 0.9 (layer 0.4); relief over 16 blocks 16.9 (layer 8.7) — camera [168.5,101.88,-247.5]
  - landmark at [-232,-24]: notableId iron_chain (type gilded_blackstone); relief over 16 blocks 47.0 (type 14.0); notable-block density (log) 0.3 (type 0.1); height 107.0 (type 108.7)
  - landmark at [488,40]: neighbour step 2.1 (type 0.7); height 111.4 (type 108.7); notable-block density (log) 0.0 (type 0.1)
  - landmark at [-360,136]: height 118.2 (type 108.7); notable-block density (log) 0.0 (type 0.1); relief over 16 blocks 18.0 (type 14.0)
  - landmark at [-232,616]: notableId bone_block (type gilded_blackstone); height 117.2 (type 108.7); neighbour step 1.4 (type 0.7); relief over 16 blocks 16.0 (type 14.0)
  - landmark at [-424,-504]: neighbour step 1.7 (type 0.2); height 115.2 (type 107.9); relief over 16 blocks 18.0 (type 10.0)

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:nether_wastes: expressed, 27731 columns; placement land 98.7%, lava 1.2%, roofed 100%; snow 0%, canopy 0%, trees 0.4/ha; top netherrack 83.9%, nether_bricks 5.7%, soul_sand 1.7%
- adventure:blackstone_basin: expressed, 25607 columns; placement land 86.3%, lava 13.3%, roofed 99.6%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top blackstone 78.8%, lava 13.3%, polished_blackstone 2.3%
- adventure:magma_land: expressed, 4356 columns; placement land 86%, lava 13.9%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top magma_block 49.8%, obsidian 14%, lava 13.9%
- adventure:gravel_desert: expressed, 2368 columns; placement land 99.6%, lava 0.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 86.1%, netherrack 7.5%, coarse_dirt 2.2%
- minecraft:crimson_forest: expressed, 1480 columns; placement land 98.4%, lava 1%, roofed 99.5%, sky 0.5%; snow 0%, canopy 53.3%, trees 260.8/ha; top nether_wart_block 51.6%, crimson_nylium 28.9%, crimson_roots 8%
- minecraft:basalt_deltas: expressed, 784 columns; placement land 92.2%, lava 7.8%, roofed 100%; snow 0%, canopy 4.1%, trees 0/ha; top basalt 76%, lava 7.8%, blackstone 5.6%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | betternether:gravel_desert | adventure:gravel_desert | 3.8 | 2368 | expressed | - |
| O2 | betternether:poor_nether_grasslands | minecraft:nether_wastes | 44.6 | 27731 | expressed | - |
| O3 | minecraft:nether_wastes | minecraft:nether_wastes | 44.6 | 27731 | expressed | - |
| O4 | minecraft:basalt_deltas | minecraft:basalt_deltas | 1.3 | 784 | expressed | - |
| O5 | minecraft:crimson_forest | minecraft:crimson_forest | 2.4 | 1480 | expressed | - |
| O6 | regions_unexplored:inferno | adventure:magma_land | 7.0 | 4356 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:blackstone_basin | 41.0 | 25607 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:nether_wastes 44.6%, adventure:blackstone_basin 41.0%, adventure:magma_land 7.0% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 7.1% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 1.3% (`surface.canopyPercent`) | |
| M:relief | median 78.0 blocks a patch; height {"p5": 23.0, "p25": 34.0, "p50": 78.0, "p75": 83.0, "p95": 97.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 0.2, "surface": 100.0, "underground": 99.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 59.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_gilded_pit-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 1.4%; near: netherrack 8.6%, glowstone 3.5%; mid: netherrack 19.5%, polished_blackstone_bricks 14.9%; far: blackstone 9%, netherrack 7.5%; biomes: adventure:blackstone_basin 45%, nether_wastes 39.8%, crimson_forest 15.2%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-far.png`

### the_gilded_pit-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 1.4%; near: netherrack 8.6%, glowstone 3.5%; mid: netherrack 19.5%, polished_blackstone_bricks 14.9%; far: blackstone 9%, netherrack 7.5%; biomes: adventure:blackstone_basin 45%, nether_wastes 39.8%, crimson_forest 15.2%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-far-nv.png`

### the_gilded_pit-surface-t1

Picked for: surface type 1: 49.0% of cells; biome nether_wastes; ground netherrack; top netherrack; height 74
Census: sky 3.7%; near: netherrack 40.5%; mid: netherrack 40.7%, lava 1.1%; far: netherrack 6.8%, blackstone 4.3%; biomes: nether_wastes 95.1%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-surface-t1.png`

### the_gilded_pit-surface-t1-nv

Picked for: surface type 1: 49.0% of cells; biome nether_wastes; ground netherrack; top netherrack; height 74
Census: sky 3.7%; near: netherrack 40.5%; mid: netherrack 40.7%, lava 1.1%; far: netherrack 6.8%, blackstone 4.3%; biomes: nether_wastes 95.1%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-surface-t1-nv.png`

### the_gilded_pit-sky-t1

Picked for: sky type 1: 100.0% of cells; biome adventure:blackstone_basin; ground blackstone; height 60
Census: sky 0%; near: blackstone 5.2%; mid: blackstone 34.3%, lava 3.4%; far: netherrack 31.9%, blackstone 7.2%; biomes: adventure:blackstone_basin 51.9%, crimson_forest 46.3%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-sky-t1.png`

### the_gilded_pit-sky-t1-nv

Picked for: sky type 1: 100.0% of cells; biome adventure:blackstone_basin; ground blackstone; height 60
Census: sky 0%; near: blackstone 5.2%; mid: blackstone 34.3%, lava 3.4%; far: netherrack 31.9%, blackstone 7.2%; biomes: adventure:blackstone_basin 51.9%, crimson_forest 46.3%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-sky-t1-nv.png`

### the_gilded_pit-underground-t1

Picked for: underground type 1: 56.0% of cells; notableId magma_block; biome nether_wastes; height 80; notable-block density (log) 1.7 (layer 1.1)
Census: sky 4.4%; near: netherrack 49.3%, nether_quartz_ore 2.9%; mid: netherrack 21%, nether_bricks 2.4%; far: netherrack 6.3%, blackstone 5%; biomes: nether_wastes 87.6%, adventure:blackstone_basin 12.4%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-underground-t1.png`

### the_gilded_pit-underground-t1-nv

Picked for: underground type 1: 56.0% of cells; notableId magma_block; biome nether_wastes; height 80; notable-block density (log) 1.7 (layer 1.1)
Census: sky 4.4%; near: netherrack 49.3%, nether_quartz_ore 2.9%; mid: netherrack 21%, nether_bricks 2.4%; far: netherrack 6.3%, blackstone 5%; biomes: nether_wastes 87.6%, adventure:blackstone_basin 12.4%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-underground-t1-nv.png`

### the_gilded_pit-roof-t1

Picked for: roof type 1: 39.9% of cells; notableId nether_quartz_ore; height 107
Census: sky 2.1%; near: netherrack 83%; mid: netherrack 5.5%, glowstone 1.8%; far: netherrack 2.6%, nether_wart_block 1.2%; biomes: nether_wastes 96.9%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-roof-t1.png`

### the_gilded_pit-roof-t1-nv

Picked for: roof type 1: 39.9% of cells; notableId nether_quartz_ore; height 107
Census: sky 2.1%; near: netherrack 83%; mid: netherrack 5.5%, glowstone 1.8%; far: netherrack 2.6%, nether_wart_block 1.2%; biomes: nether_wastes 96.9%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-roof-t1-nv.png`

### the_gilded_pit-landing

Picked for: the arrival: spawn on the ledge, looking over the pit
Census: sky 4.1%; near: netherrack 48.2%; mid: netherrack 16.1%, blackstone 7.7%; far: blackstone 19.3%; biomes: nether_wastes 70.2%, adventure:blackstone_basin 29.8%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-landing.png`

### the_gilded_pit-landing-nv

Picked for: the arrival: spawn on the ledge, looking over the pit
Census: sky 4.1%; near: netherrack 48.2%; mid: netherrack 16.1%, blackstone 7.7%; far: blackstone 19.3%; biomes: nether_wastes 70.2%, adventure:blackstone_basin 29.8%
Image: `/tmp/claude-0/w10r/reviews/the_gilded_pit/screens/the_gilded_pit-landing-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
