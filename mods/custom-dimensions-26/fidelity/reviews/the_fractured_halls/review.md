# Review packet: the_fractured_halls

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_fractured_halls.json`

> Fractured halls — endermen gate fortresses and enderkeeps in broken crystal country. No cities survived whatever happened here; only the military architecture endured, and moonlight groves have taken the sheltered ground between the ridges. The keeps are still building. What stands beside the portal becomes part of the wall.

Biomes: nullscape:shadowlands, minecraft:small_end_islands, terralith:moonlight_valley, terralith:moonlight_grove, terralith:amethyst_canyon, minecraft:deep_dark, minecraft:end_midlands, minecraft:end_highlands, nullscape:crystal_peaks

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_fractured_halls.json`

> Fractured halls — endermen gate fortresses and enderkeeps in broken crystal country. No cities survived whatever happened here; only the military architecture endured, and moonlight groves have taken the sheltered ground between the ridges. The keeps are still building. What stands beside the portal becomes part of the wall.

Biomes: adventure:painted_mountains, adventure:crystal_hollows, minecraft:end_highlands, minecraft:end_midlands, adventure:lantern_woods, adventure:glowing_grasslands, minecraft:pale_garden, minecraft:old_growth_birch_forest, adventure:umbral_wastes, minecraft:small_end_islands, minecraft:deep_dark, adventure:ice_starfield

Terrain: `{"base": "end", "params": {"peaks": 1.0, "peakWidth": 0.05, "islandHeight": 32, "coverage": 0.06}, "scales": {"climate": 2}}`; generator `{"type": "noise", "preset": "end", "endIsland": false}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_fractured_halls/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_fractured_halls/layers`

## Summary

- Top blocks seen from the sky: blackstone 10.3%, end_stone 6.7%, white_terracotta 4.8%, amethyst_block 4.3%.
- Surface biomes: adventure:umbral_wastes 14%, adventure:painted_mountains 13.4%, adventure:crystal_hollows 4.5%, adventure:lantern_woods 3.9%, small_end_islands 3.7%.
- Ground blocks: blackstone 10.3%, end_stone 7.1%, pale_moss_block 5.5%, white_terracotta 4.8%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 5%; bare rock 17.1%; no ground (void) 48.5%.
- Trees: 14 per ha (pale_oak_log 8.4/ha, mean spacing 7 blocks, birch_log 5.6/ha, mean spacing 4.5 blocks); ground plants other 1.5%, grass 0.8%, flower 0.2%, mushroom 0%, bush 0%.
- Surface height p5 53, median 91, p95 102, max 142; median relief per patch 37 blocks; slopes under 15° 38.9%, over 45° 31.8%, cliffs 2.8%; above the clouds 0%, below sea level 0%.
- Underground: 3.7% open, 0% liquid; cave biomes adventure:painted_mountains 25.2%, adventure:umbral_wastes 22.1%, small_end_islands 10.6%, adventure:crystal_hollows 8%.
- Notable clusters, fewest first: lava 4 clusters, spacing 42.1; sculk_shrieker 7 clusters, spacing 7.1; closed_eyeblossom 12 clusters, spacing 3.3; sculk 21 clusters, spacing 7.1; sculk_catalyst 25 clusters, spacing 6.3; smooth_basalt 29 clusters, spacing 12.8.
- 10 of 10 placed biomes are expressed; 2 listed biomes were not found.
- Render against world at 1310 columns: height within 2 blocks 91.5%, biome match 100%.
- Landforms over the border (noise render): plains 16.7%, hills 13.4%, mountains 3.4%, plateaus 1.7%, basins 1.1%, islands 14.4%, water 0%, void 49.4%.
- Placeness: biome regions 5.8/km² (4 major, largest 2.9%), landform regions 12.3/km² (largest 30.4%), dominant place 30.4% of the border.

## Layers

- Columns by class: void 48.5%, roofed 0%, stacked 0.1%, overhang 6.2%, cave 4.5%, single 40.7%.
- Columns with each layer: sky 0.1%, surface 51.5%, underground 48.3%, roof 0%.
- Overhangs in 6.2% of columns, stacked layers in 0.1%, cave pockets in 5.6%.

## Places

- **surface** (352 cells, 8 types, silhouette 0.416):
  - type 1: 27.3% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 85; bare rock 72% (layer 33%) — camera [1305.5,91,2407.5]
  - type 2: 15.9% of cells; biome adventure:painted_mountains; ground white_terracotta; top white_terracotta; height 93; ground plants 1% (layer 5%); bare rock 3% (layer 33%); cave pockets 1% (layer 11%) — camera [-1304.5,104,3209.5]
  - type 3: 13.6% of cells; biome adventure:painted_mountains; ground pink_terracotta; top pink_terracotta; height 85; relief over 16 blocks 34.3 (layer 22.3); relief over the patch 60.1 (layer 47.7); ground plants 1% (layer 5%); bare rock 2% (layer 33%) — camera [-2200.5,89,-1287.5]
  - type 4: 12.8% of cells; biome small_end_islands; ground end_stone; top end_stone; height 87; notable-block density (log) 0.0 (layer 0.6); ground plants 1% (layer 5%); bare rock 97% (layer 33%) — camera [2439.5,91,-1351.5]
  - type 5: 10.2% of cells; biome adventure:crystal_hollows; ground amethyst_block; top amethyst_block; height 85; bare rock 4% (layer 33%); cave pockets 35% (layer 11%) — camera [-2791.5,90,-3111.5]
  - type 6: 8.0% of cells; biome adventure:lantern_woods; ground pale_moss_block; top pale_moss_block; height 81; neighbour step 1.6 (layer 0.9); trees per cell 1.5 (layer 0.6); canopy 29% (layer 10%); bare rock 0% (layer 33%); open to the sky 68% (layer 90%); stacked layers 3% (layer 0%) — camera [-936.5,93,-3255.5]
  - type 7: 6.8% of cells; biome pale_garden; ground pale_moss_block; top pale_oak_leaves; height 92; neighbour step 0.4 (layer 0.9); relief over 16 blocks 6.2 (layer 22.3); relief over the patch 22.9 (layer 47.7); trees per cell 4.3 (layer 0.6); canopy 73% (layer 10%); ground plants 19% (layer 5%); bare rock 1% (layer 33%); open to the sky 27% (layer 90%); cave pockets 32% (layer 11%) — camera [392.5,96,-2948.5]
  - type 8: 5.4% of cells; biome old_growth_birch_forest; ground grass_block; top grass_block; height 80; layer present in 83% (layer 93%); relief over 16 blocks 33.7 (layer 22.3); relief over the patch 68.1 (layer 47.7); trees per cell 4.4 (layer 0.6); notable-block density (log) 0.1 (layer 0.6); canopy 41% (layer 10%); ground plants 9% (layer 5%); bare rock 0% (layer 33%); open to the sky 59% (layer 90%); cave pockets 30% (layer 11%) — camera [489.5,84,-1735.5]
  - landmark at [4040,-3640]: biome deep_dark (type adventure:painted_mountains); ground sculk (type pink_terracotta); top sculk (type pink_terracotta); layer present in 40% (type 100%); neighbour step 3.4 (type 1.1); notable-block density (log) 1.8 (type 0.3)
  - landmark at [4040,-3656]: biome deep_dark (type adventure:painted_mountains); ground sculk (type pink_terracotta); top sculk (type pink_terracotta); cave pockets 72% (type 1%); ground plants 17% (type 0%); neighbour step 2.9 (type 1.1)
  - landmark at [-904,1864]: top pale_oak_leaves (type pale_moss_block); neighbour step 4.2 (type 0.7); height 42.1 (type 92.8); layer present in 43% (type 100%)
  - landmark at [4040,-3624]: biome deep_dark (type adventure:painted_mountains); ground sculk (type pink_terracotta); top sculk (type pink_terracotta); layer present in 29% (type 100%); ground plants 20% (type 0%); relief over 16 blocks 3.0 (type 36.0)
  - landmark at [2456,3240]: biome deep_dark (type adventure:painted_mountains); ground sculk (type white_terracotta); top sculk (type white_terracotta); layer present in 49% (type 100%); ground plants 14% (type 0%); relief over the patch 24.0 (type 43.0)
- **sky**: no cells.
- **underground** (335 cells, 4 types, silhouette 0.32):
  - type 1: 57.6% of cells; notableId orange_terracotta; biome pale_garden; height 91 — camera [1402.5,95.88,-274.5]
  - type 2: 23.0% of cells; biome adventure:painted_mountains; notableId orange_terracotta; height 82; layer present in 81% (layer 91%); neighbour step 1.7 (layer 0.9); relief over 16 blocks 34.6 (layer 20.0) — camera [-2234.5,71.88,-1265.5]
  - type 3: 10.1% of cells; notableId smooth_basalt; biome adventure:crystal_hollows; height 90; notable-block density (log) 1.3 (layer 0.7); cave liquid 0% (layer 0%); cave pockets 44% (layer 12%) — camera [-1530.5,27.88,-3284.5]
  - type 4: 9.3% of cells; notableId blackstone; biome adventure:umbral_wastes; height 83 — camera [2648.5,34.88,-2945.5]
  - landmark at [-1512,-3272]: notableId amethyst_block (type none); biome adventure:crystal_hollows (type adventure:painted_mountains); layer present in 34% (type 100%); height 50.9 (type 84.5); cave air 34% (type 0%)
  - landmark at [4040,-3640]: notableId sculk (type none); biome deep_dark (type adventure:painted_mountains); neighbour step 4.4 (type 1.1); layer present in 30% (type 100%); notable-block density (log) 4.3 (type 0.0)
  - landmark at [-904,1848]: notableId pale_moss_carpet (type none); biome adventure:lantern_woods (type adventure:painted_mountains); neighbour step 4.1 (type 1.1); cave air 40% (type 0%); height 62.0 (type 84.5)
  - landmark at [4040,-3656]: notableId sculk_vein (type none); biome deep_dark (type adventure:painted_mountains); cave pockets 72% (type 1%); neighbour step 2.9 (type 1.1); layer present in 75% (type 100%)
  - landmark at [1272,1544]: notableId crying_obsidian (type blackstone); neighbour step 4.5 (type 0.6); layer present in 43% (type 100%); relief over 16 blocks 59.0 (type 18.0)
- **roof**: no cells.

## Biome expression

10 of 10 placed biomes are expressed.

- adventure:painted_mountains: expressed, 38571 columns; placement land 56.8%, void 43.2%, sky 0.1%; snow 0%, canopy 0.1%, trees 0/ha; top white_terracotta 20.4%, pink_terracotta 17.3%, orange_terracotta 10.5%
- adventure:umbral_wastes: expressed, 35809 columns; placement land 64%, void 35.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top blackstone 47.3%, obsidian 6.2%, soul_fire 4.5%
- adventure:lantern_woods: expressed, 21716 columns; placement land 29.1%, void 70.4%, sky 0.5%; snow 0%, canopy 30.7%, trees 66.5/ha; top pale_moss_block 19.1%, pale_oak_leaves 8.9%, pale_moss_carpet 0.7%
- minecraft:small_end_islands: expressed, 15456 columns; placement land 39%, void 61%; snow 0%, canopy 0%, trees 0/ha; top end_stone 39%
- adventure:crystal_hollows: expressed, 13824 columns; placement land 52.7%, void 47.3%; snow 0%, canopy 0%, trees 0/ha; top amethyst_block 50.5%, end_stone 1.5%, smooth_basalt 0.5%
- minecraft:deep_dark: expressed, 8676 columns; placement land 39.8%, void 60.2%; snow 0%, canopy 0%, trees 0/ha; top sculk 25.1%, sculk_vein 7.1%, end_stone 6.7%
- minecraft:old_growth_birch_forest: expressed, 8662 columns; placement land 49.3%, void 50.6%, sky 0.1%; snow 0%, canopy 44.1%, trees 213.1/ha; top grass_block 24.9%, birch_leaves 21.5%, wildflowers 1.5%
- minecraft:pale_garden: expressed, 8032 columns; placement land 71.2%, void 28.8%; snow 0%, canopy 75.4%, trees 167.9/ha; top pale_oak_leaves 53.2%, grass_block 8.5%, pale_moss_block 4.6%
- minecraft:end_midlands: expressed, 6912 columns; placement land 54%, void 46%; snow 0%, canopy 0%, trees 0/ha; top end_stone 54%
- adventure:glowing_grasslands: expressed, 6112 columns; placement land 42.6%, void 57.4%; snow 0%, canopy 1.3%, trees 0/ha; top moss_block 36.9%, moss_carpet 2.5%, glow_lichen 1%

Listed in the definition, found in no sampled column: minecraft:end_highlands, adventure:ice_starfield.


v2 biomes not found in any sampled column: minecraft:end_highlands, adventure:ice_starfield

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 14.0 | 35809 | expressed | - |
| O2 | minecraft:small_end_islands | minecraft:small_end_islands | 3.7 | 15456 | expressed | - |
| O3 | terralith:moonlight_valley | minecraft:old_growth_birch_forest | 2.6 | 8662 | expressed | - |
| O4 | terralith:moonlight_grove | minecraft:pale_garden | 3.5 | 8032 | expressed | - |
| O5 | terralith:amethyst_canyon | adventure:crystal_hollows | 4.5 | 13824 | expressed | - |
| O6 | minecraft:deep_dark | minecraft:deep_dark | 2.1 | 8676 | expressed | - |
| O7 | minecraft:end_midlands | minecraft:end_midlands | 2.3 | 6912 | expressed | - |
| O8 | minecraft:end_highlands | minecraft:end_highlands | 0.0 | 0 | listed, not found | in the v2 definition, in no sampled column |
| O9 | nullscape:crystal_peaks | adventure:ice_starfield | 0.0 | 0 | listed, not found | in the v2 definition, in no sampled column |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:painted_mountains | 13.4 | 38571 | expressed | - |
| V2 | adventure:lantern_woods | 3.9 | 21716 | expressed | - |
| V3 | adventure:glowing_grasslands | 1.6 | 6112 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:umbral_wastes 14.0%, adventure:painted_mountains 13.4%, adventure:crystal_hollows 4.5% | type `end` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 48.5% (`surface.voidPercent`) | |
| M:canopy | 5.0% (`surface.canopyPercent`) | |
| M:relief | median 37.0 blocks a patch; height {"p5": 53.0, "p25": 88.0, "p50": 91.0, "p75": 94.0, "p95": 102.0, "max": 142.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 51.5, "underground": 48.3, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 48.5, "roofed": 0.0, "stacked": 0.1, "overhang": 6.2, "cave": 4.5, "single": 40.7} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 63.4%
- S:b — 1.21.1 biomes expressed: 77.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (48.5% of columns)

## Shots

### the_fractured_halls-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 0.3%; void 29.8%; mid: amethyst_block 14.7%; far: amethyst_block 20.6%, end_stone 16.5%; biomes: adventure:crystal_hollows 70.9%, adventure:painted_mountains 11.4%, pale_garden 11.2%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-far.png`

### the_fractured_halls-underground-t1

Picked for: underground type 1: 38.8% of cells; notableId pale_moss_block; biome small_end_islands; height 91; cave pockets 1% (layer 12%)
Census: sky 2.8%; void 23%; unloaded 3.5%; near: end_stone 50.2%; far: end_stone 20.5%; biomes: end_midlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-underground-t1.png`

### the_fractured_halls-surface-t7

Picked for: surface type 7: 6.8% of cells; biome pale_garden; ground pale_moss_block; top pale_oak_leaves; height 92; neighbour step 0.4 (layer 0.9); relief over 16 blocks 6.2 (layer 22.3); relief over the patch 22.9 (layer 47.7); trees per cell 4.4 (layer 0.7); canopy 75% (layer 10%); ground plants 20% (layer 5%); bare rock 1% (layer 33%); open to the sky 25% (layer 90%); cave pockets 32% (layer 11%)
Census: sky 0%; near: pale_oak_log 27.7%, pale_hanging_moss 21.9%; biomes: pale_garden 100%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-surface-t7.png`

### the_fractured_halls-surface-t4

Picked for: surface type 4: 13.4% of cells; biome small_end_islands; ground end_stone; top end_stone; height 87; notable-block density (log) 0.0 (layer 0.6); bare rock 96% (layer 33%)
Census: sky 33.4%; unloaded 6.2%; near: end_stone 51.7%; mid: end_stone 1.7%; far: end_stone 7%; biomes: small_end_islands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-surface-t4.png`

### the_fractured_halls-surface-t3

Picked for: surface type 3: 13.9% of cells; biome adventure:painted_mountains; ground pink_terracotta; top pink_terracotta; height 85; relief over 16 blocks 34.8 (layer 22.3); relief over the patch 60.3 (layer 47.7); ground plants 1% (layer 5%); bare rock 2% (layer 33%)
Census: sky 2.4%; near: pink_terracotta 41.2%, orange_terracotta 33%; biomes: adventure:painted_mountains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-surface-t3.png`

### the_fractured_halls-surface-t2

Picked for: surface type 2: 15.3% of cells; biome adventure:painted_mountains; ground white_terracotta; top white_terracotta; height 94; ground plants 1% (layer 5%); bare rock 2% (layer 33%); cave pockets 1% (layer 11%)
Census: sky 16.3%; unloaded 3%; near: white_terracotta 28.3%, purple_terracotta 24%; mid: white_terracotta 2.7%, pink_terracotta 1.6%; biomes: adventure:painted_mountains 100%
Image: `/tmp/claude-0/w10c3/reviews/the_fractured_halls/screens/the_fractured_halls-surface-t2.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
