# Review packet: the_crumbling_reaches

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_crumbling_reaches.json`

> What happens when the endermen leave and nobody maintains the infrastructure. Crystal peaks shearing into shadow, crashed ships in the rubble. Everything intact is already gone — only wreckage tells the story.

Biomes: nullscape:shadowlands, minecraft:small_end_islands, minecraft:end_barrens, minecraft:end_midlands, minecraft:end_highlands, nullscape:void_barrens, terralith:basalt_cliffs, terralith:stony_spires, nullscape:crystal_peaks

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_crumbling_reaches.json`

> What happens when the endermen leave and nobody maintains the infrastructure. Crystal peaks shearing into shadow, crashed ships in the rubble. Everything intact is already gone — only wreckage tells the story.

Biomes: adventure:umbral_wastes, adventure:sheared_highlands, minecraft:end_barrens, minecraft:small_end_islands, minecraft:end_highlands, minecraft:end_midlands, minecraft:basalt_deltas, minecraft:stony_peaks, adventure:painted_mountains, adventure:ice_starfield

Terrain: `{"base": "end", "params": {"coverage": -0.06, "peaks": 1.0, "peakWidth": 0.05, "islandHeight": 40}, "scales": {"climate": 2}, "layout": {"type": "radial", "centre": [0, 0], "radius": 8000, "falloff": 16, "strength": 1.0}}`; generator `{"type": "noise", "preset": "end", "endIsland": false}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_crumbling_reaches/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_crumbling_reaches/layers`

## Summary

- Top blocks seen from the sky: end_stone 28.5%, blackstone 10.3%, basalt 1.6%, obsidian 1.5%.
- Surface biomes: adventure:umbral_wastes 13.5%, adventure:sheared_highlands 8.7%, end_barrens 6.4%, stony_peaks 6.2%, small_end_islands 4.1%.
- Ground blocks: end_stone 28.5%, blackstone 10.6%, basalt 1.6%, obsidian 1.5%.
- Liquid cover 0% water, 0.4% lava, 0% other; snow or ice 0.5% (a snowing climate over 0%); tree canopy 0%; bare rock 41.1%; no ground (void) 49.3%.
- Trees: 0.5 per ha (oak_log 0.5/ha, mean spacing 2.3 blocks); ground plants other 0.9%.
- Surface height p5 56, median 98, p95 109, max 135; median relief per patch 72 blocks; slopes under 15° 27.9%, over 45° 38.5%, cliffs 8.4%; above the clouds 0%, below sea level 0%.
- Underground: 6.2% open, 0% liquid; cave biomes adventure:umbral_wastes 22.8%, adventure:sheared_highlands 16.2%, end_barrens 12.1%, small_end_islands 11.3%.
- Notable clusters, fewest first: chorus_plant 40 clusters, spacing 5.9; purple_terracotta 40 clusters, spacing 3.9; rail 44 clusters, spacing 3.5; orange_terracotta 45 clusters, spacing 2.6; chorus_flower 61 clusters, spacing 3; oak_planks 64 clusters, spacing 3.4.
- 10 of 10 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1285 columns: height within 2 blocks 92%, biome match 100%.
- Landforms over the border (noise render): plains 12.3%, hills 8.9%, mountains 2.4%, plateaus 2.6%, basins 1%, islands 20%, water 0%, void 52.7%.
- Placeness: biome regions 9.7/km² (4 major, largest 4.3%), landform regions 11.3/km² (largest 30.0%), dominant place 30.0% of the border.

## Layers

- Columns by class: void 49.3%, roofed 0%, stacked 0.3%, overhang 6.7%, cave 5.5%, single 38.2%.
- Columns with each layer: sky 0.3%, surface 50.7%, underground 47.6%, roof 0%.
- Overhangs in 6.7% of columns, stacked layers in 0.3%, cave pockets in 7.3%.

## Places

- **surface** (377 cells, 2 types, silhouette 0.369):
  - type 1: 72.1% of cells; biome adventure:sheared_highlands; ground end_stone; top end_stone; height 91 — camera [440.5,107,345.5]
  - type 2: 27.9% of cells; biome adventure:umbral_wastes; ground blackstone; top blackstone; height 93; notable-block density (log) 1.0 (layer 0.5); ground plants 6% (layer 2%) — camera [-358.5,99,-486.5]
  - landmark at [-1592,568]: biome stony_peaks (type adventure:sheared_highlands); neighbour step 9.8 (type 1.4); layer present in 28% (type 100%); open to the sky 85% (type 100%)
  - landmark at [-88,664]: biome adventure:painted_mountains (type adventure:sheared_highlands); ground purple_terracotta (type end_stone); top purple_terracotta (type end_stone); bare rock 0% (type 96%); layer present in 27% (type 100%); notable-block density (log) 2.2 (type 0.1)
  - landmark at [712,2024]: biome stony_peaks (type adventure:sheared_highlands); layer present in 30% (type 100%); cave pockets 57% (type 0%); overhangs 54% (type 12%)
  - landmark at [328,1320]: biome basalt_deltas (type adventure:sheared_highlands); ground basalt (type end_stone); top basalt (type end_stone); liquid cover 11% (type 0%); layer present in 53% (type 100%); height 69.7 (type 93.5)
  - landmark at [344,1320]: biome basalt_deltas (type adventure:sheared_highlands); ground basalt (type end_stone); top basalt (type end_stone); liquid cover 12% (type 0%); notable-block density (log) 1.9 (type 0.1); neighbour step 5.2 (type 1.4)
- **sky** (2 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome adventure:umbral_wastes; ground blackstone; height 98 — camera [1366.5,100,120.5]
- **underground** (354 cells, 2 types, silhouette 0.321):
  - type 1: 67.8% of cells; biome stony_peaks; notableId smooth_basalt; height 97 — camera [1102.5,50.88,605.5]
  - type 2: 32.2% of cells; notableId blackstone; biome adventure:umbral_wastes; height 87; neighbour step 2.4 (layer 1.5); relief over 16 blocks 44.5 (layer 29.4); notable-block density (log) 0.8 (layer 0.4); cave air 16% (layer 9%) — camera [1148.5,71.88,-1930.5]
  - landmark at [712,2024]: notableId iron_chain (type blackstone); biome stony_peaks (type adventure:umbral_wastes); layer present in 26% (type 91%); cave air 45% (type 9%); cave pockets 64% (type 12%)
  - landmark at [328,1320]: notableId basalt (type blackstone); biome basalt_deltas (type adventure:umbral_wastes); cave air 57% (type 9%); layer present in 34% (type 91%); cave liquid 0% (type 0%)
  - landmark at [-1880,-1096]: notableId smooth_basalt (type none); biome adventure:sheared_highlands (type none); cave pockets 80% (type 0%); neighbour step 5.7 (type 0.7); cave air 41% (type 0%)
  - landmark at [712,2008]: notableId iron_chain (type blackstone); biome stony_peaks (type adventure:umbral_wastes); cave pockets 68% (type 12%); cave air 42% (type 9%); height 63.4 (type 89.1)
  - landmark at [696,2040]: biome stony_peaks (type none); neighbour step 10.5 (type 0.7); layer present in 25% (type 100%); cave air 33% (type 0%)
- **roof**: no cells.

## Biome expression

10 of 10 placed biomes are expressed.

- adventure:umbral_wastes: expressed, 41627 columns; placement land 53.2%, void 46.3%, sky 0.5%; snow 0%, canopy 0%, trees 0/ha; top blackstone 39.6%, obsidian 6.1%, soul_fire 3.5%
- adventure:sheared_highlands: expressed, 31923 columns; placement land 44.6%, void 55.1%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top end_stone 31.1%, smooth_basalt 7.1%, tuff 3.9%
- minecraft:end_barrens: expressed, 18928 columns; placement land 55.2%, void 44.8%; snow 0%, canopy 0%, trees 0/ha; top end_stone 55.2%
- minecraft:stony_peaks: expressed, 16737 columns; placement land 60.6%, void 39%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top end_stone 59.7%, pink_terracotta 0.3%, oak_planks 0.2%
- minecraft:small_end_islands: expressed, 15810 columns; placement land 42.8%, void 56.6%, sky 0.6%; snow 0%, canopy 0%, trees 0/ha; top end_stone 42.7%, blackstone 0%, crying_obsidian 0%
- minecraft:end_highlands: expressed, 13322 columns; placement land 38.4%, void 61.3%, sky 0.3%; snow 0%, canopy 0%, trees 0/ha; top end_stone 37.1%, chorus_plant 0.8%, chorus_flower 0.5%
- adventure:painted_mountains: expressed, 11184 columns; placement land 46.6%, void 53.4%; snow 0%, canopy 0%, trees 0/ha; top pink_terracotta 17%, purple_terracotta 16.7%, white_terracotta 9.6%
- minecraft:end_midlands: expressed, 6544 columns; placement land 64.5%, void 35.5%; snow 0%, canopy 0%, trees 0/ha; top end_stone 64.5%, lava 0%
- minecraft:basalt_deltas: expressed, 5716 columns; placement land 58.3%, lava 10.5%, void 31.1%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top basalt 45.2%, lava 10.5%, blackstone 5.4%
- adventure:ice_starfield: expressed, 2304 columns; placement land 33.6%, void 66.4%; snow 100%, canopy 0%, trees 0/ha; top snow_block 26%, packed_ice 5.6%, snow 1.9%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:shadowlands | adventure:umbral_wastes | 13.5 | 41627 | expressed | - |
| O2 | minecraft:small_end_islands | minecraft:small_end_islands | 4.1 | 15810 | expressed | - |
| O3 | minecraft:end_barrens | minecraft:end_barrens | 6.4 | 18928 | expressed | - |
| O4 | minecraft:end_midlands | minecraft:end_midlands | 2.6 | 6544 | expressed | - |
| O5 | minecraft:end_highlands | minecraft:end_highlands | 3.1 | 13322 | expressed | - |
| O6 | nullscape:void_barrens | minecraft:end_barrens | 6.4 | 18928 | expressed | - |
| O7 | terralith:basalt_cliffs | minecraft:basalt_deltas | 2.4 | 5716 | expressed | - |
| O8 | terralith:stony_spires | minecraft:stony_peaks | 6.2 | 16737 | expressed | - |
| O9 | nullscape:crystal_peaks | adventure:ice_starfield | 0.5 | 2304 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:sheared_highlands | 8.7 | 31923 | expressed | - |
| V2 | adventure:painted_mountains | 3.2 | 11184 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:umbral_wastes 13.5%, adventure:sheared_highlands 8.7%, minecraft:end_barrens 6.4% | type `end` |
| M:snow | 0.5% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"endIsland": false}` |
| M:lava | 0.4% (`surface.fluidCoverPercent.lava`) | |
| M:void | 49.3% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 72.0 blocks a patch; height {"p5": 56.0, "p25": 86.0, "p50": 98.0, "p75": 101.0, "p95": 109.0, "max": 135.0} (`terrain`) | |
| M:layers | {"sky": 0.3, "surface": 50.7, "underground": 47.6, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#0B0B14", "fogColor": "#241C2E", "ambientLight": 0.05, "effects": "minecraft:the_end"}` |
| M:classes | {"void": 49.3, "roofed": 0.0, "stacked": 0.3, "overhang": 6.7, "cave": 5.5, "single": 38.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 76.5%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (49.3% of columns)

## Shots

### the_crumbling_reaches-far

Picked for: far: the border ring at bearing 94, facing the centre; 0.0% of rays could reach the border
Census: sky 19.6%; void 18.3%; mid: snow_block 3.6%, white_terracotta 3.6%; far: end_stone 11.4%, snow_block 8.5%; biomes: adventure:painted_mountains 52.6%, adventure:ice_starfield 45.2%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-far.png`

### the_crumbling_reaches-far-nv

Picked for: far: the border ring at bearing 94, facing the centre; 0.0% of rays could reach the border
Census: sky 19.6%; void 18.3%; mid: snow_block 3.6%, white_terracotta 3.6%; far: end_stone 11.4%, snow_block 8.5%; biomes: adventure:painted_mountains 52.6%, adventure:ice_starfield 45.2%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-far-nv.png`

### the_crumbling_reaches-surface-t1

Picked for: surface type 1: 72.1% of cells; biome adventure:sheared_highlands; ground end_stone; top end_stone; height 91
Census: sky 31.3%; void 0.6%; unloaded 1.3%; near: polished_basalt 25%, end_stone 12.4%; mid: end_stone 8.7%, polished_basalt 2.3%; far: end_stone 12.7%, polished_basalt 1.8%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-surface-t1.png`

### the_crumbling_reaches-surface-t1-nv

Picked for: surface type 1: 72.1% of cells; biome adventure:sheared_highlands; ground end_stone; top end_stone; height 91
Census: sky 31.5%; void 0.6%; unloaded 0.9%; near: polished_basalt 25%, end_stone 12.4%; mid: end_stone 8.7%, polished_basalt 2.3%; far: end_stone 12.8%, polished_basalt 1.9%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-surface-t1-nv.png`

### the_crumbling_reaches-sky-t1

Picked for: sky type 1: 100.0% of cells; biome adventure:umbral_wastes; ground blackstone; height 98
Census: sky 36.5%; unloaded 1.1%; near: end_stone 58.7%; far: end_stone 3.8%; biomes: small_end_islands 99.3%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-sky-t1.png`

### the_crumbling_reaches-sky-t1-nv

Picked for: sky type 1: 100.0% of cells; biome adventure:umbral_wastes; ground blackstone; height 98
Census: sky 36.6%; unloaded 0.8%; near: end_stone 58.7%; far: end_stone 3.9%; biomes: small_end_islands 99.3%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-sky-t1-nv.png`

### the_crumbling_reaches-underground-t1

Picked for: underground type 1: 67.8% of cells; biome stony_peaks; notableId smooth_basalt; height 97
Census: sky 0%; void 5.8%; near: end_stone 46.7%; mid: end_stone 47.6%; biomes: end_barrens 84.5%, end_highlands 11.8%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-underground-t1.png`

### the_crumbling_reaches-underground-t1-nv

Picked for: underground type 1: 67.8% of cells; biome stony_peaks; notableId smooth_basalt; height 97
Census: sky 0%; void 5.8%; near: end_stone 46.7%; mid: end_stone 47.6%; biomes: end_barrens 84.5%, end_highlands 11.8%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-underground-t1-nv.png`

### the_crumbling_reaches-underground-t2

Picked for: underground type 2: 32.2% of cells; notableId blackstone; biome adventure:umbral_wastes; height 87; neighbour step 2.4 (layer 1.5); relief over 16 blocks 44.5 (layer 29.4); notable-block density (log) 0.8 (layer 0.4); cave air 16% (layer 9%)
Census: sky 7.5%; void 4.7%; near: blackstone 37.7%, end_stone 17.5%; mid: end_stone 8.2%, blackstone 4.9%; far: end_stone 3.8%, blackstone 2.6%; biomes: adventure:umbral_wastes 100%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-underground-t2.png`

### the_crumbling_reaches-underground-t2-nv

Picked for: underground type 2: 32.2% of cells; notableId blackstone; biome adventure:umbral_wastes; height 87; neighbour step 2.4 (layer 1.5); relief over 16 blocks 44.5 (layer 29.4); notable-block density (log) 0.8 (layer 0.4); cave air 16% (layer 9%)
Census: sky 7.5%; void 4.7%; near: blackstone 37.7%, end_stone 17.5%; mid: end_stone 8.2%, blackstone 4.9%; far: end_stone 3.8%, blackstone 2.6%; biomes: adventure:umbral_wastes 100%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-underground-t2-nv.png`

### the_crumbling_reaches-surface-lm1

Picked for: surface landmark: biome stony_peaks (type adventure:sheared_highlands); neighbour step 9.8 (type 1.4); layer present in 28% (type 100%); open to the sky 85% (type 100%)
Census: sky 0.3%; void 20.6%; unloaded 0.4%; near: oak_planks 36.5%, iron_chain 4.1%; mid: end_stone 20.4%, iron_chain 1.5%; far: end_stone 13.2%; biomes: stony_peaks 99.1%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-surface-lm1.png`

### the_crumbling_reaches-surface-lm1-nv

Picked for: surface landmark: biome stony_peaks (type adventure:sheared_highlands); neighbour step 9.8 (type 1.4); layer present in 28% (type 100%); open to the sky 85% (type 100%)
Census: sky 0.3%; void 20.6%; unloaded 0.4%; near: oak_planks 36.5%, iron_chain 4.1%; mid: end_stone 20.4%, iron_chain 1.5%; far: end_stone 13.2%; biomes: stony_peaks 99.1%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-surface-lm1-nv.png`

### the_crumbling_reaches-feature-magma_block

Picked for: magma_block: 153 clusters in 2 patches, median 2.0 blocks, surface layer
Census: sky 10%; near: basalt 58.9%, end_stone 10.4%; mid: blackstone 2%, end_stone 2%; biomes: basalt_deltas 92.4%, adventure:sheared_highlands 6.7%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-feature-magma_block.png`

### the_crumbling_reaches-feature-magma_block-nv

Picked for: magma_block: 153 clusters in 2 patches, median 2.0 blocks, surface layer
Census: sky 10%; near: basalt 58.9%, end_stone 10.4%; mid: blackstone 2%, end_stone 2%; biomes: basalt_deltas 92.4%, adventure:sheared_highlands 6.7%
Image: `/tmp/claude-0/w10c3/reviews/the_crumbling_reaches/screens/the_crumbling_reaches-feature-magma_block-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
