# Review packet: the_end

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_end.json`

> The end — the endermen's current home, not their origin. Cities and citadels within honest reach of the gateways, built after they moved on from the older void dimensions.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_end.json`

> The end — the endermen's current home, not their origin. Cities and citadels within honest reach of the gateways, built after they moved on from the older void dimensions.

Biomes: none listed

Terrain: `{}`; generator `{"type": "noise", "preset": "end"}`

Zones: ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_end/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_end/layers`

## Summary

- Top blocks seen from the sky: end_stone 50.6%, chorus_plant 0.8%, chorus_flower 0.6%.
- Surface biomes: end_highlands 27.1%, end_midlands 18.9%, end_barrens 3.1%, small_end_islands 3%.
- Ground blocks: end_stone 50.6%, chorus_plant 0.8%, chorus_flower 0.6%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 50.6%; no ground (void) 47.9%.
- Trees: 0 per ha; ground plants none.
- Surface height p5 45, median 58, p95 65, max 82; median relief per patch 23 blocks; slopes under 15° 60.6%, over 45° 14.9%, cliffs 5.2%; above the clouds 0%, below sea level 0%.
- Underground: 1.3% open, 0% liquid; cave biomes end_midlands 41.7%, end_highlands 39.3%, small_end_islands 11%, end_barrens 8%.
- Notable clusters, fewest first: chorus_plant 146 clusters, spacing 6.9; chorus_flower 391 clusters, spacing 2.2.
- 4 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 562 columns: height within 2 blocks 94%, biome match 100%.
- Landforms over the border (noise render): plains 6.5%, hills 4.2%, mountains 0%, plateaus 0%, basins 0%, islands 30.6%, water 0%, void 58.6%.
- Placeness: biome regions 6.6/km² (0 major, largest 0.1%), landform regions 7.6/km² (largest 55.8%), dominant place 55.8% of the border.

## Layers

- Columns by class: void 47.9%, roofed 0%, stacked 0%, overhang 4.5%, cave 0.1%, single 47.5%.
- Columns with each layer: sky 0%, surface 52.1%, underground 47.4%, roof 0%.
- Overhangs in 4.5% of columns, stacked layers in 0%, cave pockets in 0.2%.

## Places

- **surface** (153 cells, 2 types, silhouette 0.38):
  - type 1: 62.1% of cells; biome end_midlands; ground end_stone; top end_stone; height 55; notable-block density (log) 0.0 (layer 0.1); bare rock 100% (layer 97%) — camera [-2023.5,57,1480.5]
  - type 2: 37.9% of cells; biome end_highlands; ground end_stone; top end_stone; height 60; notable-block density (log) 0.2 (layer 0.1); bare rock 94% (layer 97%) — camera [-952.5,61,-663.5]
  - landmark at [-2232,3080]: biome end_barrens (type end_midlands); cave pockets 14% (type 0%); layer present in 25% (type 100%); neighbour step 1.5 (type 0.3)
  - landmark at [-2936,3448]: overhangs 64% (type 8%); height 39.5 (type 56.2); neighbour step 1.6 (type 0.3)
  - landmark at [424,2776]: biome end_barrens (type end_midlands); height 28.8 (type 56.2); neighbour step 2.1 (type 0.3); layer present in 33% (type 100%)
  - landmark at [-2952,3432]: overhangs 55% (type 8%); neighbour step 1.9 (type 0.3); layer present in 41% (type 100%)
  - landmark at [-904,-2296]: biome small_end_islands (type end_midlands); layer present in 29% (type 100%); relief over the patch 0.0 (type 34.0); height 67.0 (type 56.2)
- **sky**: no cells.
- **underground** (140 cells, 6 types, silhouette 0.362):
  - type 1: 33.6% of cells; biome end_midlands; notableId chorus_flower; height 59 — camera [2862.5,60.88,-2494.5]
  - type 2: 21.4% of cells; biome end_highlands; notableId chorus_plant; height 60; neighbour step 1.0 (layer 0.7); relief over 16 blocks 19.7 (layer 13.9) — camera [-971.5,60.88,-648.5]
  - type 3: 21.4% of cells; height 59; neighbour step 0.1 (layer 0.7); relief over 16 blocks 4.4 (layer 13.9)
  - type 4: 12.1% of cells; biome end_midlands; height 56; layer present in 77% (layer 90%); neighbour step 0.3 (layer 0.7); relief over 16 blocks 7.4 (layer 13.9); cave air 11% (layer 2%); cave pockets 2% (layer 0%) — camera [-2625.5,44.88,-3829.5]
  - type 5: 7.9% of cells; biome small_end_islands; height 51; layer present in 49% (layer 90%); neighbour step 1.1 (layer 0.7) — camera [849.5,41.88,2932.5]
  - type 6: 3.6% of cells; biome end_midlands; height 46; layer present in 63% (layer 90%); neighbour step 2.0 (layer 0.7); relief over 16 blocks 23.0 (layer 13.9); cave air 10% (layer 2%) — camera [-2948.5,45.88,3425.5]
  - landmark at [-1192,1144]: cave air 26% (type 9%); layer present in 37% (type 95%); cave pockets 2% (type 0%)
  - landmark at [-2936,3432]: relief over 16 blocks 40.0 (type 18.0); cave air 10% (type 0%); cave pockets 2% (type 0%)
  - landmark at [856,2936]: biome small_end_islands (type none); cave air 14% (type 0%); neighbour step 1.9 (type 1.0); relief over 16 blocks 9.0 (type 15.0)
  - landmark at [-232,-3928]: biome end_barrens (type end_midlands); relief over 16 blocks 13.0 (type 2.0); height 52.8 (type 56.9); cave air 13% (type 9%)
  - landmark at [3912,424]: cave pockets 6% (type 0%); cave air 24% (type 9%); relief over 16 blocks 6.0 (type 2.0)
- **roof**: no cells.

## Biome expression

4 of 5 placed biomes are expressed.

- minecraft:small_end_islands: expressed, 25856 columns; placement land 8.1%, void 91.9%; snow 0%, canopy 0%, trees 0/ha; top end_stone 8.1%
- minecraft:end_highlands: expressed, 18944 columns; placement land 98.8%, void 1.2%; snow 0%, canopy 0%, trees 0/ha; top end_stone 93.7%, chorus_plant 2.9%, chorus_flower 2.3%
- minecraft:end_midlands: expressed, 16640 columns; placement land 78.5%, void 21.5%; snow 0%, canopy 0%, trees 0/ha; top end_stone 78.3%, chorus_flower 0.1%, chorus_plant 0.1%
- minecraft:end_barrens: expressed, 5376 columns; placement land 39.4%, void 60.6%; snow 0%, canopy 0%, trees 0/ha; top end_stone 39.4%
- minecraft:the_end: too few columns to judge, 2304 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:end_highlands | 27.1 | 18944 | expressed | - |
| V2 | minecraft:end_midlands | 18.9 | 16640 | expressed | - |
| V3 | minecraft:end_barrens | 3.1 | 5376 | expressed | - |
| V4 | minecraft:small_end_islands | 3.0 | 25856 | expressed | - |
| V5 | minecraft:the_end | 0.0 | 2304 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:end_highlands 27.1%, minecraft:end_midlands 18.9%, minecraft:end_barrens 3.1% | type `the_end` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 47.9% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 23.0 blocks a patch; height {"p5": 45.0, "p25": 56.0, "p50": 58.0, "p75": 60.0, "p95": 65.0, "max": 82.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 52.1, "underground": 47.4, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 47.9, "roofed": 0.0, "stacked": 0.0, "overhang": 4.5, "cave": 0.1, "single": 47.5} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (47.9% of columns)

## Shots

### the_end-far

Picked for: far: the border ring at bearing 288, facing the centre; 0.0% of rays could reach the border
Census: sky 20.8%; void 40.7%; mid: end_stone 13.9%; far: end_stone 20.5%, chorus_plant 2.7%; biomes: end_midlands 37.2%, end_highlands 26%, small_end_islands 20.6%
Image: `/tmp/claude-0/w10r/reviews/the_end/screens/the_end-far.png`

### the_end-surface-t1

Picked for: surface type 1: 62.1% of cells; biome end_midlands; ground end_stone; top end_stone; height 55; notable-block density (log) 0.0 (layer 0.1); bare rock 100% (layer 97%)
Census: sky 30.2%; unloaded 0.2%; near: end_stone 59.9%, chorus_plant 1.1%; mid: chorus_plant 5.3%, chorus_flower 1.2%; biomes: end_midlands 88%, end_highlands 11.6%
Image: `/tmp/claude-0/w10r/reviews/the_end/screens/the_end-surface-t1.png`

### the_end-underground-t1

Picked for: underground type 1: 33.6% of cells; biome end_midlands; notableId chorus_flower; height 59
Census: sky 26.5%; unloaded 2.5%; near: end_stone 50.2%, chorus_plant 6.6%; mid: end_stone 10.3%; far: end_stone 1%, chorus_plant 1%; biomes: end_highlands 80.7%, end_midlands 16.1%
Image: `/tmp/claude-0/w10r/reviews/the_end/screens/the_end-underground-t1.png`

### the_end-surface-lm1

Picked for: surface landmark: biome end_barrens (type end_midlands); cave pockets 14% (type 0%); layer present in 25% (type 100%); neighbour step 1.5 (type 0.3)
Census: sky 36.3%; void 11.8%; unloaded 3.2%; near: end_stone 35.6%, chorus_plant 1.5%; mid: end_stone 9.7%; biomes: end_midlands 43.3%, end_barrens 41.2%, small_end_islands 11.3%
Image: `/tmp/claude-0/w10r/reviews/the_end/screens/the_end-surface-lm1.png`

### the_end-structure-phantom_citadel

Picked for: want start of mes:phantom_citadel
Census: sky 37.1%; unloaded 2%; near: end_stone 40.9%; mid: end_stone 15.4%; far: purpur_block 2%, purpur_stairs 1.4%; biomes: end_midlands 76.6%, end_barrens 13.2%
Image: `/tmp/claude-0/w10r/reviews/the_end/screens/the_end-structure-phantom_citadel.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
