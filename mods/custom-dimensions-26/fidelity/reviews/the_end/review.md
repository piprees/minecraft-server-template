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

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/fin/full/the_end/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/fin/full/the_end/layers`

## Summary

- Top blocks seen from the sky: end_stone 52.4%, chorus_plant 0.4%, chorus_flower 0.3%.
- Surface biomes: end_midlands 24.7%, end_highlands 17.1%, end_barrens 6.7%, small_end_islands 4.7%.
- Ground blocks: end_stone 52.4%, chorus_plant 0.4%, chorus_flower 0.3%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 52.4%; no ground (void) 46.8%.
- Trees: 0 per ha; ground plants none.
- Surface height p5 43, median 58, p95 64, max 84; median relief per patch 26 blocks; slopes under 15° 61.4%, over 45° 12.3%, cliffs 3.2%; above the clouds 0%, below sea level 0%.
- Underground: 2% open, 0% liquid; cave biomes end_midlands 46.4%, end_highlands 23%, end_barrens 16.4%, small_end_islands 14.2%.
- Notable clusters, fewest first: chorus_plant 79 clusters, spacing 8.3; chorus_flower 211 clusters, spacing 2.1.
- 4 of 4 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 570 columns: height within 2 blocks 96.7%, biome match 100%.
- Landforms over the border (noise render): plains 6.5%, hills 4.2%, mountains 0%, plateaus 0%, basins 0%, islands 30.6%, water 0%, void 58.6%.
- Placeness: biome regions 6.6/km² (0 major, largest 0.1%), landform regions 7.6/km² (largest 55.8%), dominant place 55.8% of the border.

## Layers

- Columns by class: void 46.8%, roofed 0%, stacked 0%, overhang 5.7%, cave 0.1%, single 47.4%.
- Columns with each layer: sky 0%, surface 53.2%, underground 48.5%, roof 0%.
- Overhangs in 5.7% of columns, stacked layers in 0%, cave pockets in 0.2%.

## Places

- **surface** (159 cells, 3 types, silhouette 0.343):
  - type 1: 56.0% of cells; biome end_midlands; ground end_stone; top end_stone; height 57; neighbour step 0.2 (layer 0.5) — camera [2904.5,58,1800.5]
  - type 2: 22.0% of cells; biome end_highlands; ground end_stone; top end_stone; height 61; layer present in 100% (layer 88%); neighbour step 0.8 (layer 0.5); relief over 16 blocks 21.0 (layer 13.8); notable-block density (log) 0.2 (layer 0.0); bare rock 95% (layer 99%) — camera [2312.5,63,1272.5]
  - type 3: 22.0% of cells; biome end_barrens; ground end_stone; top end_stone; height 50; layer present in 59% (layer 88%); neighbour step 1.0 (layer 0.5); overhangs 25% (layer 12%) — camera [2888.5,49,1784.5]
  - landmark at [-2936,3448]: biome end_midlands (type end_barrens); overhangs 64% (type 16%); relief over the patch 51.0 (type 27.0); neighbour step 1.9 (type 1.1)
  - landmark at [-2936,3432]: overhangs 55% (type 4%); relief over 16 blocks 49.0 (type 19.0); relief over the patch 51.0 (type 32.0)
  - landmark at [-1176,1160]: cave pockets 7% (type 0%); relief over the patch 65.0 (type 32.0); relief over 16 blocks 45.0 (type 19.0)
  - landmark at [-1240,3080]: biome end_midlands (type end_barrens); cave pockets 3% (type 0%); height 56.3 (type 48.1); relief over the patch 45.0 (type 27.0)
  - landmark at [-3880,1848]: biome end_midlands (type end_barrens); cave pockets 7% (type 0%); layer present in 84% (type 45%); relief over the patch 48.0 (type 27.0)
- **sky**: no cells.
- **underground** (143 cells, 4 types, silhouette 0.405):
  - type 1: 51.0% of cells; height 59; neighbour step 0.2 (layer 0.5) — camera [-1201.5,44.88,3085.5]
  - type 2: 22.4% of cells; biome end_midlands; height 56; cave air 8% (layer 3%); cave pockets 2% (layer 0%) — camera [2899.5,41.88,1761.5]
  - type 3: 14.0% of cells; biome end_highlands; notableId chorus_flower; height 60; neighbour step 1.0 (layer 0.5); relief over 16 blocks 22.0 (layer 11.3); notable-block density (log) 0.0 (layer 0.0) — camera [2331.5,61.88,1281.5]
  - type 4: 12.6% of cells; biome end_midlands; height 50; layer present in 52% (layer 89%); neighbour step 1.3 (layer 0.5); relief over 16 blocks 15.6 (layer 11.3); cave air 7% (layer 3%) — camera [-1994.5,45.88,-757.5]
  - landmark at [-2936,3432]: notableId chorus_flower (type none); notable-block density (log) 0.0 (type 0.0); relief over 16 blocks 40.0 (type 19.0); cave air 11% (type 1%)
  - landmark at [-1240,3080]: relief over 16 blocks 25.0 (type 6.0); cave air 17% (type 2%); layer present in 55% (type 96%)
  - landmark at [-3880,1848]: cave pockets 8% (type 0%); height 46.1 (type 56.8); relief over 16 blocks 22.0 (type 6.0)
  - landmark at [-2936,3448]: biome end_midlands (type none); cave air 33% (type 0%); neighbour step 2.1 (type 1.3); height 42.3 (type 47.4)
  - landmark at [920,-1512]: biome end_barrens (type none); cave air 25% (type 0%); layer present in 26% (type 49%); neighbour step 0.8 (type 1.3)
- **roof**: no cells.

## Biome expression

4 of 4 placed biomes are expressed.

- minecraft:small_end_islands: expressed, 30464 columns; placement land 10.7%, void 89.3%; snow 0%, canopy 0%, trees 0/ha; top end_stone 10.7%
- minecraft:end_midlands: expressed, 18944 columns; placement land 90%, void 10%; snow 0%, canopy 0%, trees 0/ha; top end_stone 89.9%, chorus_plant 0.1%, chorus_flower 0.1%
- minecraft:end_highlands: expressed, 12032 columns; placement land 98.2%, void 1.8%; snow 0%, canopy 0%, trees 0/ha; top end_stone 94.1%, chorus_plant 2.2%, chorus_flower 1.9%
- minecraft:end_barrens: expressed, 7680 columns; placement land 60.3%, void 39.7%; snow 0%, canopy 0%, trees 0/ha; top end_stone 60.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:end_midlands | 24.7 | 18944 | expressed | - |
| V2 | minecraft:end_highlands | 17.1 | 12032 | expressed | - |
| V3 | minecraft:end_barrens | 6.7 | 7680 | expressed | - |
| V4 | minecraft:small_end_islands | 4.7 | 30464 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:end_midlands 24.7%, minecraft:end_highlands 17.1%, minecraft:end_barrens 6.7% | type `the_end` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 46.8% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 26.0 blocks a patch; height {"p5": 43.0, "p25": 56.0, "p50": 58.0, "p75": 60.0, "p95": 64.0, "max": 84.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 53.2, "underground": 48.5, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 46.8, "roofed": 0.0, "stacked": 0.0, "overhang": 5.7, "cave": 0.1, "single": 47.4} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (46.8% of columns)

## Shots

### the_end-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 7.2%; void 8.9%; mid: end_stone 45.8%, chorus_plant 4.2%; far: end_stone 24.8%, chorus_plant 4.9%; biomes: end_highlands 47.6%, end_midlands 39.8%, end_barrens 8.6%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-far.png`

### the_end-surface-t1

Picked for: surface type 1: 56.0% of cells; biome end_midlands; ground end_stone; top end_stone; height 57; neighbour step 0.2 (layer 0.5)
Census: sky 33.8%; near: end_stone 52.6%; mid: end_stone 9.5%, chorus_plant 3%; biomes: end_midlands 75.6%, end_highlands 22.5%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-surface-t1.png`

### the_end-underground-t1

Picked for: underground type 1: 51.0% of cells; height 59; neighbour step 0.2 (layer 0.5)
Census: sky 0%; near: end_stone 100%; biomes: end_midlands 94.8%, end_highlands 5.2%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-underground-t1.png`

### the_end-surface-t3

Picked for: surface type 3: 22.0% of cells; biome end_barrens; ground end_stone; top end_stone; height 50; layer present in 59% (layer 88%); neighbour step 1.0 (layer 0.5); overhangs 25% (layer 12%)
Census: sky 14.7%; void 12.6%; near: end_stone 67.6%; mid: end_stone 1%; far: end_stone 3.9%; biomes: end_barrens 60.5%, end_midlands 28.8%, small_end_islands 10.3%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-surface-t3.png`

### the_end-surface-t2

Picked for: surface type 2: 22.0% of cells; biome end_highlands; ground end_stone; top end_stone; height 61; layer present in 100% (layer 88%); neighbour step 0.8 (layer 0.5); relief over 16 blocks 21.0 (layer 13.8); notable-block density (log) 0.2 (layer 0.0); bare rock 95% (layer 99%)
Census: sky 21.4%; unloaded 0.3%; near: end_stone 53.3%, chorus_plant 10.4%; mid: chorus_plant 8.2%, chorus_flower 2%; far: chorus_plant 1%; biomes: end_highlands 98.7%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-surface-t2.png`

### the_end-underground-t2

Picked for: underground type 2: 22.4% of cells; biome end_midlands; height 56; cave air 8% (layer 3%); cave pockets 2% (layer 0%)
Census: sky 2%; void 18.5%; near: end_stone 45.7%; mid: end_stone 33.8%; biomes: end_midlands 58.7%, small_end_islands 31.1%, end_barrens 10.1%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-underground-t2.png`

### the_end-underground-t4

Picked for: underground type 4: 12.6% of cells; biome end_midlands; height 50; layer present in 52% (layer 89%); neighbour step 1.3 (layer 0.5); relief over 16 blocks 15.6 (layer 11.3); cave air 7% (layer 3%)
Census: sky 12.3%; void 22%; near: end_stone 47.4%; mid: end_stone 16%; far: end_stone 1.9%; biomes: end_barrens 86.9%, small_end_islands 10.6%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-underground-t4.png`

### the_end-surface-lm1

Picked for: surface landmark: biome end_midlands (type end_barrens); overhangs 64% (type 16%); relief over the patch 51.0 (type 27.0); neighbour step 1.9 (type 1.1)
Census: sky 0%; near: end_stone 99.8%; biomes: end_midlands 59.2%, end_highlands 40.8%
Image: `/tmp/claude-0/w10c1/reviews/the_end/screens/the_end-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
