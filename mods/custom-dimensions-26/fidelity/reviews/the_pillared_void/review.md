# Review packet: the_pillared_void

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_pillared_void.json`

> Lonely pillars in the void. Spires, a dragon's skeleton, nothing else — the endermen's oldest construction, predating even the tiled expanse. Whatever they built here, they built it for a dragon. The dragon is dead. The pillars are not finished. They rise through the frame, through whatever you built beside it.

Biomes: nullscape:void_barrens, minecraft:end_barrens, minecraft:the_end, minecraft:small_end_islands, minecraft:end_highlands, nullscape:crystal_peaks

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_pillared_void.json`

> Lonely pillars in the void. Spires, a dragon's skeleton, nothing else — the endermen's oldest construction, predating even the tiled expanse. Whatever they built here, they built it for a dragon. The dragon is dead. The pillars are not finished. They rise through the frame, through whatever you built beside it.

Biomes: minecraft:end_barrens, minecraft:the_end, minecraft:small_end_islands, minecraft:end_highlands, adventure:sheared_highlands

Terrain: `{"base": "spires", "floor": "islands", "params": {"coverage": 0.03, "spireHeight": 150, "landHeight": 30, "thickness": 0.8, "taper": 0.05}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_pillared_void/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_pillared_void/layers`

## Summary

- Top blocks seen from the sky: end_stone 7.4%, smooth_basalt 0.4%, tuff 0.1%, chorus_plant 0%.
- Surface biomes: end_barrens 3.5%, small_end_islands 1.4%, the_end 1.1%, end_highlands 1.1%, adventure:sheared_highlands 0.8%.
- Ground blocks: end_stone 7.4%, smooth_basalt 0.4%, tuff 0.1%, chorus_plant 0%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 7.4%; no ground (void) 92.1%.
- Trees: 0 per ha; ground plants none.
- Surface height p5 32, median 33, p95 177, max 196; median relief per patch 32 blocks; slopes under 15° 21.8%, over 45° 51.1%, cliffs 25.8%; above the clouds 0%, below sea level 0%.
- Underground: 5.3% open, 0% liquid; cave biomes end_barrens 41.2%, small_end_islands 20%, end_highlands 15%, the_end 14.2%.
- Notable clusters, fewest first: basalt 5 clusters, spacing 3.5; polished_basalt 14 clusters, spacing 3.5; chorus_plant 22 clusters, spacing 7.1; chorus_flower 34 clusters, spacing 2; copper_ore 39 clusters, spacing 2.1; smooth_basalt 122 clusters, spacing 1.8.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 237 columns: height within 2 blocks 90.7%, biome match 100%.
- Landforms over the border (noise render): plains 0.1%, hills 0%, mountains 0%, plateaus 0%, basins 0%, islands 7.3%, water 0%, void 92.6%.
- Placeness: biome regions 0.0/km² (0 major, largest 0.0%), landform regions 0.0/km² (largest 92.6%), dominant place 92.6% of the border.

## Layers

- Columns by class: void 92.1%, roofed 0%, stacked 0.1%, overhang 0.3%, cave 0.5%, single 7%.
- Columns with each layer: sky 0.1%, surface 7.9%, underground 6%, roof 0%.
- Overhangs in 0.3% of columns, stacked layers in 0.1%, cave pockets in 0.6%.

## Places

- **surface** (76 cells, 3 types, silhouette 0.393):
  - type 1: 60.5% of cells; biome end_barrens; ground end_stone; top end_stone; height 68; neighbour step 13.1 (layer 9.0); relief over 16 blocks 148.5 (layer 103.7) — camera [-3159.5,34,-2794.5]
  - type 2: 32.9% of cells; biome small_end_islands; ground end_stone; top end_stone; height 40; layer present in 39% (layer 48%); neighbour step 0.7 (layer 9.0); relief over 16 blocks 13.0 (layer 103.7); relief over the patch 73.6 (layer 127.9); cave pockets 0% (layer 8%) — camera [-599.5,33,2200.5]
  - type 3: 6.6% of cells; biome adventure:sheared_highlands; ground smooth_basalt; top smooth_basalt; height 61; neighbour step 13.1 (layer 9.0); relief over 16 blocks 144.2 (layer 103.7); notable-block density (log) 0.8 (layer 0.1); bare rock 35% (layer 94%) — camera [-1338.5,33,295.5]
  - landmark at [-2920,3336]: biome adventure:sheared_highlands (type end_barrens); notable-block density (log) 0.7 (type 0.0); neighbour step 2.0 (type 13.9); layer present in 74% (type 55%)
  - landmark at [536,1512]: biome the_end (type end_barrens); layer present in 30% (type 55%); open to the sky 96% (type 100%); stacked layers 4% (type 0%)
  - landmark at [-136,-3112]: biome end_highlands (type end_barrens); cave pockets 46% (type 6%); height 91.7 (type 49.3); notable-block density (log) 0.3 (type 0.0)
- **sky**: no cells.
- **underground** (65 cells, 8 types, silhouette 0.419):
  - type 1: 26.2% of cells; biome end_barrens; height 70; layer present in 52% (layer 44%); neighbour step 14.8 (layer 10.7); relief over 16 blocks 145.9 (layer 112.2) — camera [3140.5,118.88,-179.5]
  - type 2: 24.6% of cells; biome end_barrens; height 33; layer present in 32% (layer 44%); neighbour step 0.8 (layer 10.7); relief over 16 blocks 10.6 (layer 112.2); cave pockets 0% (layer 11%)
  - type 3: 18.5% of cells; biome small_end_islands; notableId smooth_basalt; height 62; cave pockets 1% (layer 11%) — camera [1138.5,4.88,-2535.5]
  - type 4: 10.8% of cells; biome end_highlands; height 80; relief over 16 blocks 150.0 (layer 112.2); cave air 11% (layer 4%); cave pockets 24% (layer 11%) — camera [-2014.5,29.88,4033.5]
  - type 5: 7.7% of cells; notableId copper_ore; biome end_barrens; height 69; neighbour step 17.2 (layer 10.7); relief over 16 blocks 144.8 (layer 112.2) — camera [3132.5,7.88,-202.5]
  - type 6: 6.2% of cells; notableId smooth_basalt; biome adventure:sheared_highlands; height 69; relief over 16 blocks 145.5 (layer 112.2); notable-block density (log) 0.8 (layer 0.1) — camera [-1357.5,110.88,280.5]
  - type 7: 3.1% of cells; biome end_barrens; height 131; layer present in 68% (layer 44%); relief over 16 blocks 148.5 (layer 112.2); cave pockets 68% (layer 11%) — camera [-3885.5,6.88,3160.5]
  - type 8: 3.1% of cells; notableId copper_ore; biome end_barrens; height 90; neighbour step 19.5 (layer 10.7); relief over 16 blocks 148.0 (layer 112.2); notable-block density (log) 0.3 (layer 0.1); cave air 23% (layer 4%); cave pockets 47% (layer 11%) — camera [2733.5,14.88,2893.5]
  - landmark at [-1944,-664]: notableId smooth_basalt (type none); notable-block density (log) 1.6 (type 0.0); layer present in 29% (type 54%); height 44.5 (type 55.9)
  - landmark at [-440,744]: biome small_end_islands (type end_barrens); layer present in 74% (type 50%); height 93.1 (type 57.4); cave air 8% (type 2%)
  - landmark at [-632,-4088]: notable-block density (log) 1.6 (type 0.4); neighbour step 8.2 (type 16.1); cave pockets 25% (type 12%)
- **roof**: no cells.

## Biome expression

5 of 5 placed biomes are expressed.

- minecraft:end_barrens: expressed, 73825 columns; placement land 8%, void 91.8%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top end_stone 8%, granite 0%, copper_ore 0%
- minecraft:the_end: expressed, 33987 columns; placement land 5.6%, void 94.4%; snow 0%, canopy 0%, trees 0/ha; top end_stone 5.6%
- minecraft:end_highlands: expressed, 23328 columns; placement land 7.9%, void 92.1%; snow 0%, canopy 0%, trees 0/ha; top end_stone 7.5%, chorus_plant 0.2%, chorus_flower 0.2%
- minecraft:small_end_islands: expressed, 21024 columns; placement land 11.2%, void 88.8%; snow 0%, canopy 0%, trees 0/ha; top end_stone 11.2%, granite 0%
- adventure:sheared_highlands: expressed, 18448 columns; placement land 7.5%, void 92.4%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top smooth_basalt 3.5%, end_stone 3.4%, tuff 0.5%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | nullscape:void_barrens | minecraft:end_barrens | 3.5 | 73825 | expressed | - |
| O2 | minecraft:end_barrens | minecraft:end_barrens | 3.5 | 73825 | expressed | - |
| O3 | minecraft:the_end | minecraft:the_end | 1.1 | 33987 | expressed | - |
| O4 | minecraft:small_end_islands | minecraft:small_end_islands | 1.4 | 21024 | expressed | - |
| O5 | minecraft:end_highlands | minecraft:end_highlands | 1.1 | 23328 | expressed | - |
| O6 | nullscape:crystal_peaks | adventure:ice_starfield | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:sheared_highlands | 0.8 | 18448 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:end_barrens 3.5%, minecraft:small_end_islands 1.4%, minecraft:the_end 1.1% | type `end` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 92.1% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 32.0 blocks a patch; height {"p5": 32.0, "p25": 32.0, "p50": 33.0, "p75": 49.0, "p95": 177.0, "max": 196.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 7.9, "underground": 6.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#0B0B14", "fogColor": "#0B0B14", "ambientLight": 0.05}` |
| M:classes | {"void": 92.1, "roofed": 0.0, "stacked": 0.1, "overhang": 0.3, "cave": 0.5, "single": 7.0} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 89.9%
- S:b — 1.21.1 biomes expressed: 83.3%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (7.9% of columns)

## Shots

### the_pillared_void-far

Picked for: far: the border ring at bearing 72, facing the centre; 0.0% of rays could reach the border
Census: sky 18.4%; void 44.4%; mid: end_stone 3.9%; far: end_stone 33.1%; biomes: end_barrens 100%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-far.png`

### the_pillared_void-far-nv

Picked for: far: the border ring at bearing 72, facing the centre; 0.0% of rays could reach the border
Census: sky 18.4%; void 44.4%; mid: end_stone 3.9%; far: end_stone 33.1%; biomes: end_barrens 100%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-far-nv.png`

### the_pillared_void-surface-t1

Picked for: surface type 1: 60.5% of cells; biome end_barrens; ground end_stone; top end_stone; height 68; neighbour step 13.1 (layer 9.0); relief over 16 blocks 148.5 (layer 103.7)
Census: sky 20.2%; void 12.2%; unloaded 29.6%; mid: end_stone 12.6%; far: end_stone 25.1%; biomes: end_barrens 66%, small_end_islands 26.1%, the_end 7.9%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t1.png`

### the_pillared_void-surface-t1-nv

Picked for: surface type 1: 60.5% of cells; biome end_barrens; ground end_stone; top end_stone; height 68; neighbour step 13.1 (layer 9.0); relief over 16 blocks 148.5 (layer 103.7)
Census: sky 21.1%; void 14.1%; unloaded 25.8%; mid: end_stone 12.6%; far: end_stone 26.1%; biomes: end_barrens 66.7%, small_end_islands 25.5%, the_end 7.7%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t1-nv.png`

### the_pillared_void-underground-t1

Picked for: underground type 1: 26.2% of cells; biome end_barrens; height 70; layer present in 52% (layer 44%); neighbour step 14.8 (layer 10.7); relief over 16 blocks 145.9 (layer 112.2)
Census: sky 8.8%; void 5.7%; unloaded 16.9%; near: end_stone 36.5%; mid: end_stone 8.1%; far: end_stone 23.7%; biomes: end_barrens 100%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t1.png`

### the_pillared_void-underground-t1-nv

Picked for: underground type 1: 26.2% of cells; biome end_barrens; height 70; layer present in 52% (layer 44%); neighbour step 14.8 (layer 10.7); relief over 16 blocks 145.9 (layer 112.2)
Census: sky 9.1%; void 7.2%; unloaded 13.3%; near: end_stone 36.5%; mid: end_stone 8.1%; far: end_stone 25.5%; biomes: end_barrens 99.8%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t1-nv.png`

### the_pillared_void-surface-t2

Picked for: surface type 2: 32.9% of cells; biome small_end_islands; ground end_stone; top end_stone; height 40; layer present in 39% (layer 48%); neighbour step 0.7 (layer 9.0); relief over 16 blocks 13.0 (layer 103.7); relief over the patch 73.6 (layer 127.9); cave pockets 0% (layer 8%)
Census: sky 4.5%; void 12.2%; unloaded 18.8%; near: end_stone 45.5%; mid: end_stone 7.1%; far: end_stone 10.3%; biomes: small_end_islands 70.6%, end_highlands 25.8%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t2.png`

### the_pillared_void-surface-t2-nv

Picked for: surface type 2: 32.9% of cells; biome small_end_islands; ground end_stone; top end_stone; height 40; layer present in 39% (layer 48%); neighbour step 0.7 (layer 9.0); relief over 16 blocks 13.0 (layer 103.7); relief over the patch 73.6 (layer 127.9); cave pockets 0% (layer 8%)
Census: sky 5.5%; void 13.1%; unloaded 15.4%; near: end_stone 45.5%; mid: end_stone 7.1%; far: end_stone 11.8%; biomes: small_end_islands 68.9%, end_highlands 25.5%, adventure:sheared_highlands 5.6%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t2-nv.png`

### the_pillared_void-surface-t3

Picked for: surface type 3: 6.6% of cells; biome adventure:sheared_highlands; ground smooth_basalt; top smooth_basalt; height 61; neighbour step 13.1 (layer 9.0); relief over 16 blocks 144.2 (layer 103.7); notable-block density (log) 0.8 (layer 0.1); bare rock 35% (layer 94%)
Census: sky 2.8%; void 14.4%; unloaded 11.7%; near: end_stone 19.4%, smooth_basalt 19.2%; mid: end_stone 8.7%, smooth_basalt 2.6%; far: end_stone 9.9%; biomes: adventure:sheared_highlands 98.9%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t3.png`

### the_pillared_void-surface-t3-nv

Picked for: surface type 3: 6.6% of cells; biome adventure:sheared_highlands; ground smooth_basalt; top smooth_basalt; height 61; neighbour step 13.1 (layer 9.0); relief over 16 blocks 144.2 (layer 103.7); notable-block density (log) 0.8 (layer 0.1); bare rock 35% (layer 94%)
Census: sky 3.8%; void 14.5%; unloaded 10.1%; near: end_stone 19.4%, smooth_basalt 19.2%; mid: end_stone 8.7%, smooth_basalt 2.6%; far: end_stone 10.2%; biomes: adventure:sheared_highlands 98.4%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-t3-nv.png`

### the_pillared_void-underground-t3

Picked for: underground type 3: 18.5% of cells; biome small_end_islands; notableId smooth_basalt; height 62; cave pockets 1% (layer 11%)
Census: sky 0.1%; void 43.8%; unloaded 20.3%; near: end_stone 2.6%; mid: end_stone 19.1%, deepslate 1.3%; far: end_stone 11.7%; biomes: small_end_islands 65.7%, end_highlands 19%, the_end 15.3%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t3.png`

### the_pillared_void-underground-t3-nv

Picked for: underground type 3: 18.5% of cells; biome small_end_islands; notableId smooth_basalt; height 62; cave pockets 1% (layer 11%)
Census: sky 0.2%; void 44.9%; unloaded 18.8%; near: end_stone 2.6%; mid: end_stone 19.1%, deepslate 1.3%; far: end_stone 12%; biomes: small_end_islands 65.3%, end_highlands 19.6%, the_end 15.1%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t3-nv.png`

### the_pillared_void-underground-t6

Picked for: underground type 6: 6.2% of cells; notableId smooth_basalt; biome adventure:sheared_highlands; height 69; relief over 16 blocks 145.5 (layer 112.2); notable-block density (log) 0.8 (layer 0.1)
Census: sky 21.4%; void 15.3%; unloaded 9.7%; near: smooth_basalt 18.5%; mid: end_stone 1.7%; far: end_stone 30.7%, smooth_basalt 1.4%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t6.png`

### the_pillared_void-underground-t6-nv

Picked for: underground type 6: 6.2% of cells; notableId smooth_basalt; biome adventure:sheared_highlands; height 69; relief over 16 blocks 145.5 (layer 112.2); notable-block density (log) 0.8 (layer 0.1)
Census: sky 22.2%; void 16.8%; unloaded 7.2%; near: smooth_basalt 18.5%; mid: end_stone 1.7%; far: end_stone 30.8%, smooth_basalt 1.4%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-underground-t6-nv.png`

### the_pillared_void-surface-lm1

Picked for: surface landmark: biome adventure:sheared_highlands (type end_barrens); notable-block density (log) 0.7 (type 0.0); neighbour step 2.0 (type 13.9); layer present in 74% (type 55%)
Census: sky 3.1%; void 16.7%; unloaded 33.5%; near: polished_basalt 23%, end_stone 4.2%; mid: end_stone 3.6%; far: end_stone 11.6%, smooth_basalt 1%; biomes: adventure:sheared_highlands 92.1%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-lm1.png`

### the_pillared_void-surface-lm1-nv

Picked for: surface landmark: biome adventure:sheared_highlands (type end_barrens); notable-block density (log) 0.7 (type 0.0); neighbour step 2.0 (type 13.9); layer present in 74% (type 55%)
Census: sky 4%; void 18.1%; unloaded 30.3%; near: polished_basalt 23%, end_stone 4.2%; mid: end_stone 3.6%; far: end_stone 12.4%, smooth_basalt 1%; biomes: adventure:sheared_highlands 90.6%, end_barrens 5.3%
Image: `/tmp/claude-0/w10c3/reviews/the_pillared_void/screens/the_pillared_void-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
