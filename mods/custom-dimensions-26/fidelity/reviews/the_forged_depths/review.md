# Review packet: the_forged_depths

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_forged_depths.json`

> The last piglin forge still burning. Crimson forges and reactors in a small brutal deep — the fires here never went out because nobody survived to bank them. The deeper you go, the hotter and harder it gets. The heat comes back through the frame, and anything built beside the portal is fuel.

Biomes: incendium:quartz_flats, minecraft:nether_wastes, betternether:crimson_pinewood, betternether:crimson_glowing_woods, minecraft:basalt_deltas, betternether:magma_land, incendium:volcanic_deltas, regions_unexplored:inferno

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_forged_depths.json`

> The last piglin forge still burning. Crimson forges and reactors in a small brutal deep — the fires here never went out because nobody survived to bank them. The deeper you go, the hotter and harder it gets. The heat comes back through the frame, and anything built beside the portal is fuel.

Biomes: adventure:quartz_flats, adventure:crimson_pinewood, minecraft:basalt_deltas, minecraft:crimson_forest, minecraft:nether_wastes, adventure:magma_land

Terrain: `{"base": "roofed", "params": {"floorHeight": 72, "floorRelief": 18, "ceilingGap": 40, "roughness": 0.6, "pit": 0.04, "pitDepth": 34, "ledges": 0.5, "ledgeStep": 8, "chasms": 0.08, "chasmDepth": 24, "pillars": 0.05}, "layout": {"type": "radial", "centre": [0, 300], "radius": 380, "falloff": 64, "strength": 1, "warp": {"blocks": 76, "wavelength": 512}}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_forged_depths/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_forged_depths/layers`

## Summary

- Top blocks seen from the sky: crimson_nylium 28.8%, lava 18.5%, magma_block 8.9%, netherrack 6.7%.
- Surface biomes: adventure:crimson_pinewood 35.5%, adventure:magma_land 33.6%, adventure:quartz_flats 11.3%, crimson_forest 8.8%, basalt_deltas 7.9%.
- Ground blocks: crimson_nylium 36.1%, blackstone 21.7%, magma_block 10.5%, netherrack 8.8%.
- Liquid cover 0% water, 18.5% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0.3%; bare rock 17%; no ground (void) 0%.
- Trees: 0.9 per ha (crimson_stem 0.9/ha, mean spacing 4.3 blocks); ground plants grass 6.2%, other 1.6%, mushroom 0.9%.
- Surface height p5 17, median 60, p95 108, max 127; median relief per patch 110 blocks; slopes under 15° 40.8%, over 45° 30.7%, cliffs 16.1%; above the clouds 0%, below sea level 16.9%.
- Underground: 9.7% open, 4.1% liquid; cave biomes adventure:magma_land 30.2%, adventure:quartz_flats 27.8%, adventure:crimson_pinewood 17.5%, basalt_deltas 10.1%.
- Notable clusters, fewest first: soul_lantern 2 clusters, spacing n/a; smooth_quartz_stairs 3 clusters, spacing 3; gold_block 4 clusters, spacing 5.8; quartz_bricks 4 clusters, spacing 2.7; trial_spawner 6 clusters, spacing 15; iron_door 7 clusters, spacing 5.3.
- 4 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1080 columns: height within 2 blocks 82%, biome match 92.6%.
- Landforms over the border (noise render): plains 47%, hills 31.7%, mountains 4.4%, plateaus 4.4%, basins 7.7%, islands 0.1%, water 4.7%, void 0%.
- Placeness: biome regions 7.5/km² (11 major, largest 37.8%), landform regions 18.2/km² (largest 30.8%), dominant place 37.8% of the border.

## Layers

- Columns by class: void 0%, roofed 98.4%, stacked 0%, overhang 0%, cave 0%, single 1.6%.
- Columns with each layer: sky 0.2%, surface 100%, underground 99.4%, roof 98.4%.
- Overhangs in 19.3% of columns, stacked layers in 0.2%, cave pockets in 15.6%.

## Places

- **surface** (270 cells, 5 types, silhouette 0.372):
  - type 1: 40.0% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top crimson_nylium; height 68; neighbour step 1.8 (layer 4.0); ground plants 17% (layer 9%); liquid cover 2% (layer 19%) — camera [-375.5,63,-583.5]
  - type 2: 21.1% of cells; biome adventure:magma_land; ground blackstone; top lava; height 37; neighbour step 6.4 (layer 4.0); relief over 16 blocks 85.6 (layer 64.3); ground plants 1% (layer 9%); liquid cover 61% (layer 19%); cave pockets 4% (layer 16%) — camera [-263.5,32,408.5]
  - type 3: 15.6% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 41; relief over the patch 121.3 (layer 103.1) — camera [184.5,32,200.5]
  - type 4: 13.7% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 76; neighbour step 6.3 (layer 4.0); ground plants 0% (layer 9%); liquid cover 3% (layer 19%); overhangs 44% (layer 19%); stacked layers 1% (layer 0%); cave pockets 38% (layer 16%) — camera [392.5,73,-167.5]
  - type 5: 9.6% of cells; biome basalt_deltas; ground basalt; top basalt; height 61; relief over the patch 77.2 (layer 103.1); ground plants 1% (layer 9%); bare rock 71% (layer 17%); overhangs 8% (layer 19%); cave pockets 4% (layer 16%) — camera [-551.5,50,632.5]
  - landmark at [-344,344]: biome nether_wastes (type adventure:quartz_flats); ground netherrack (type quartz_block); top netherrack (type quartz_block); notable-block density (log) 3.0 (type 0.2); stacked layers 2% (type 0%); cave pockets 41% (type 8%)
  - landmark at [24,-40]: ground bedrock (type quartz_block); top bedrock (type quartz_block); open to the sky 43% (type 0%); under a roof 57% (type 100%); neighbour step 16.6 (type 4.7)
  - landmark at [-504,40]: biome adventure:crimson_pinewood (type adventure:quartz_flats); ground netherrack (type quartz_block); top netherrack (type quartz_block); bare rock 71% (type 8%); relief over 16 blocks 121.0 (type 57.0); neighbour step 10.8 (type 4.7)
  - landmark at [8,-344]: biome crimson_forest (type adventure:quartz_flats); ground nether_bricks (type quartz_block); top nether_bricks (type quartz_block); cave pockets 89% (type 8%); overhangs 84% (type 37%); relief over the patch 77.0 (type 115.0)
  - landmark at [-520,200]: biome adventure:crimson_pinewood (type adventure:quartz_flats); ground netherrack (type quartz_block); top netherrack (type quartz_block); bare rock 66% (type 8%); neighbour step 13.9 (type 4.7); relief over 16 blocks 118.0 (type 57.0)
- **sky**: no cells.
- **underground** (270 cells, 2 types, silhouette 0.22):
  - type 1: 60.7% of cells; notableId magma_block; biome adventure:magma_land; height 67 — camera [-506.5,70.88,74.5]
  - type 2: 39.3% of cells; notableId blackstone; biome adventure:quartz_flats; height 45 — camera [85.5,32.88,151.5]
  - landmark at [456,-552]: notableId lava (type magma_block); biome adventure:crimson_pinewood (type none); cave liquid 28% (type 1%); notable-block density (log) 4.2 (type 1.3); cave pockets 68% (type 15%)
  - landmark at [264,504]: notableId nether_bricks (type magma_block); biome basalt_deltas (type none); cave pockets 98% (type 15%); cave air 44% (type 0%); notable-block density (log) 3.9 (type 1.3)
  - landmark at [-520,200]: biome adventure:crimson_pinewood (type none); cave air 65% (type 13%); height 85.9 (type 35.1); cave liquid 19% (type 0%)
  - landmark at [-88,-120]: notableId nether_quartz_ore (type blackstone); biome adventure:quartz_flats (type none); cave pockets 74% (type 0%); cave liquid 18% (type 0%); height 77.1 (type 35.1)
  - landmark at [472,184]: notableId lava (type magma_block); biome adventure:crimson_pinewood (type none); notable-block density (log) 4.0 (type 1.3); cave air 24% (type 0%); cave liquid 15% (type 1%)
- **roof** (270 cells, 8 types, silhouette 0.463):
  - type 1: 38.1% of cells; notableId nether_quartz_ore; height 110 — camera [-263.5,103.88,424.5]
  - type 2: 15.6% of cells; notableId glowstone; height 112 — camera [-55.5,106.88,-135.5]
  - type 3: 11.9% of cells; notableId nether_gold_ore; height 109; notable-block density (log) 0.1 (layer 0.2) — camera [-119.5,102.88,-503.5]
  - type 4: 9.6% of cells; notableId blackstone; height 109; notable-block density (log) 0.8 (layer 0.2) — camera [-535.5,102.88,616.5]
  - type 5: 7.0% of cells; notableId nether_quartz_ore; height 102; layer present in 91% (layer 98%); neighbour step 3.0 (layer 0.7); relief over 16 blocks 86.1 (layer 17.8) — camera [-487.5,96.88,56.5]
  - type 6: 6.7% of cells; notableId lava; height 111; notable-block density (log) 0.0 (layer 0.2) — camera [616.5,107.88,-23.5]
  - type 7: 6.3% of cells; notableId fire; height 116; notable-block density (log) 0.0 (layer 0.2) — camera [264.5,109.88,504.5]
  - type 8: 4.8% of cells; notableId weeping_vines_plant; height 107 — camera [456.5,104.88,184.5]
  - landmark at [472,168]: notableId magma_block (type nether_quartz_ore); layer present in 78% (type 100%); neighbour step 5.2 (type 1.8); notable-block density (log) 1.2 (type 0.2)
  - landmark at [-360,344]: notableId polished_blackstone (type nether_quartz_ore); neighbour step 7.6 (type 1.8); height 88.5 (type 100.8); notable-block density (log) 0.7 (type 0.2)
  - landmark at [-504,40]: neighbour step 7.6 (type 0.2); relief over 16 blocks 102.0 (type 6.0); height 95.2 (type 108.6)
  - landmark at [-520,56]: notableId magma_block (type nether_quartz_ore); neighbour step 4.1 (type 1.8); height 91.9 (type 100.8); notable-block density (log) 0.7 (type 0.2)
  - landmark at [-552,184]: notableId magma_block (type nether_quartz_ore); layer present in 86% (type 100%); notable-block density (log) 0.6 (type 0.2); neighbour step 2.9 (type 1.8)

## Biome expression

4 of 6 placed biomes are expressed.

- adventure:crimson_pinewood: placed, not expressed, 24518 columns; placement land 99.2%, lava 0.8%, roofed 98%; snow 0%, canopy 0.7%, trees 0/ha; top crimson_nylium 63.9%, crimson_roots 17.2%, netherrack 8.2%; **features name trees, none on 2.4 ha of land**
- adventure:magma_land: expressed, 23240 columns; placement land 50.1%, lava 49.8%, roofed 100%; snow 0%, canopy 0.3%, trees 0/ha; top lava 49.8%, magma_block 25.5%, blackstone 9.2%
- adventure:quartz_flats: expressed, 7874 columns; placement land 98.4%, lava 0.5%, roofed 93.9%, sky 1.1%; snow 0%, canopy 0.1%, trees 1.3/ha; top quartz_block 54.4%, glowstone 17.2%, netherrack 8.3%
- minecraft:crimson_forest: placed, not expressed, 6104 columns; placement land 99%, lava 1%, roofed 99.9%; snow 0%, canopy 0%, trees 0/ha; top crimson_nylium 68.2%, polished_blackstone 7.4%, netherrack 6.2%; **features name trees, none on 0.6 ha of land**
- minecraft:basalt_deltas: expressed, 5468 columns; placement land 83.7%, lava 16.3%, roofed 99.3%; snow 0%, canopy 0%, trees 0/ha; top basalt 44.8%, blackstone 18.2%, lava 16.3%
- minecraft:nether_wastes: expressed, 2025 columns; placement land 97.4%, lava 2%, roofed 89.3%, sky 0.5%; snow 0%, canopy 0%, trees 0/ha; top netherrack 42.3%, polished_blackstone 13.5%, bedrock 10.2%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | incendium:quartz_flats | adventure:quartz_flats | 11.3 | 7874 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 2.9 | 2025 | expressed | - |
| O3 | betternether:crimson_pinewood | adventure:crimson_pinewood | 35.5 | 24518 | placed, not expressed | features name trees, none on 2.4 ha of land |
| O4 | betternether:crimson_glowing_woods | minecraft:crimson_forest | 8.8 | 6104 | placed, not expressed | features name trees, none on 0.6 ha of land |
| O5 | minecraft:basalt_deltas | minecraft:basalt_deltas | 7.9 | 5468 | expressed | - |
| O6 | betternether:magma_land | adventure:magma_land | 33.6 | 23240 | expressed | - |
| O7 | incendium:volcanic_deltas | adventure:magma_land | 33.6 | 23240 | expressed | - |
| O8 | regions_unexplored:inferno | adventure:magma_land | 33.6 | 23240 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:crimson_pinewood 35.5%, adventure:magma_land 33.6%, adventure:quartz_flats 11.3% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 18.5% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.3% (`surface.canopyPercent`) | |
| M:relief | median 110.0 blocks a patch; height {"p5": 17.0, "p25": 36.0, "p50": 60.0, "p75": 73.0, "p95": 108.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.2, "surface": 100.0, "underground": 99.4, "roof": 98.4} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#4B2B1B", "ambientLight": 0.08}` |
| M:classes | {"void": 0.0, "roofed": 98.4, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 1.6} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 75.0%
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (98.4% of columns)

## Shots

### the_forged_depths-surface-t1

Picked for: surface type 1: 40.0% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top crimson_nylium; height 68; neighbour step 1.8 (layer 4.0); ground plants 17% (layer 9%); liquid cover 2% (layer 19%)
Census: sky 0%; near: crimson_roots 31.9%, crimson_nylium 30.6%; mid: netherrack 4.3%; far: netherrack 8.2%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-surface-t1.png`

### the_forged_depths-surface-t1-nv

Picked for: surface type 1: 40.0% of cells; biome adventure:crimson_pinewood; ground crimson_nylium; top crimson_nylium; height 68; neighbour step 1.8 (layer 4.0); ground plants 17% (layer 9%); liquid cover 2% (layer 19%)
Census: sky 0%; near: crimson_roots 31.9%, crimson_nylium 30.6%; mid: netherrack 4.3%; far: netherrack 8.2%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-surface-t1-nv.png`

### the_forged_depths-underground-t1

Picked for: underground type 1: 60.7% of cells; notableId magma_block; biome adventure:magma_land; height 67
Census: sky 0.4%; near: netherrack 54.4%, crimson_nylium 22.9%; mid: netherrack 8.2%, crimson_nylium 1.5%; far: netherrack 3%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-underground-t1.png`

### the_forged_depths-underground-t1-nv

Picked for: underground type 1: 60.7% of cells; notableId magma_block; biome adventure:magma_land; height 67
Census: sky 0.4%; near: netherrack 54.4%, crimson_nylium 22.9%; mid: netherrack 8.2%, crimson_nylium 1.5%; far: netherrack 3%; biomes: adventure:crimson_pinewood 100%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-underground-t1-nv.png`

### the_forged_depths-roof-t1

Picked for: roof type 1: 38.1% of cells; notableId nether_quartz_ore; height 110
Census: sky 0%; unloaded 1.6%; near: netherrack 78.6%; mid: netherrack 11.5%, basalt 5.5%; biomes: nether_wastes 91.8%, basalt_deltas 8.2%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-roof-t1.png`

### the_forged_depths-roof-t1-nv

Picked for: roof type 1: 38.1% of cells; notableId nether_quartz_ore; height 110
Census: sky 0%; unloaded 1.6%; near: netherrack 78.6%; mid: netherrack 11.5%, basalt 5.5%; biomes: nether_wastes 91.7%, basalt_deltas 8.3%
Image: `/tmp/claude-0/w10r/reviews/the_forged_depths/screens/the_forged_depths-roof-t1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
