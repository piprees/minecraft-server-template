# Review packet: the_end_citadel

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_end_citadel.json`

> Citadel country — where the endermen built their densest urban centres. End cities and phantom citadels saturate the highlands. No ruins here; this is still maintained. Maintenance does not stop at the frame — build beside the portal and the citadel absorbs it.

Biomes: minecraft:end_highlands, nullscape:crystal_peaks, minecraft:end_midlands, minecraft:the_end, minecraft:end_barrens, nullscape:void_barrens, minecraft:small_end_islands

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_end_citadel.json`

> Citadel country — where the endermen built their densest urban centres. End cities and phantom citadels saturate the highlands. No ruins here; this is still maintained. Maintenance does not stop at the frame — build beside the portal and the citadel absorbs it.

Biomes: minecraft:end_highlands, minecraft:end_midlands, adventure:sheared_highlands, minecraft:the_end, minecraft:end_barrens, minecraft:small_end_islands

Terrain: `{"base": "end", "params": {"coverage": -0.04, "islandHeight": 24, "peaks": 0.3}, "layout": {"type": "radial", "centre": [0, 0], "radius": 8000, "falloff": 16, "strength": 1.0}}`; generator `{"type": "noise", "preset": "end", "endIsland": false}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c3/finals/the_end_citadel/dossier.json`; layer maps in `/tmp/claude-0/w10c3/finals/the_end_citadel/layers`

## Summary

- Top blocks seen from the sky: end_stone 45.2%, chorus_plant 0.6%, chorus_flower 0.5%, tuff 0.4%.
- Surface biomes: end_highlands 23.6%, end_midlands 7.2%, end_barrens 6.1%, adventure:sheared_highlands 4.3%, small_end_islands 3.3%.
- Ground blocks: end_stone 45.2%, chorus_plant 0.6%, chorus_flower 0.5%, tuff 0.4%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 45.6%; no ground (void) 52.8%.
- Trees: 0 per ha; ground plants none.
- Surface height p5 44, median 83, p95 91, max 111; median relief per patch 50 blocks; slopes under 15° 28.7%, over 45° 24.4%, cliffs 7.8%; above the clouds 0%, below sea level 0%.
- Underground: 2.7% open, 0% liquid; cave biomes end_highlands 45.2%, end_midlands 16.5%, end_barrens 12.9%, adventure:sheared_highlands 10.9%.
- Notable clusters, fewest first: basalt 9 clusters, spacing 3.4; smooth_basalt 55 clusters, spacing 3.1; polished_basalt 152 clusters, spacing 3.6; chorus_plant 281 clusters, spacing 7.3; chorus_flower 665 clusters, spacing 2.4.
- 6 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1207 columns: height within 2 blocks 98.3%, biome match 100%.
- Landforms over the border (noise render): plains 11.7%, hills 9%, mountains 2.3%, plateaus 1.3%, basins 0.2%, islands 25.3%, water 0%, void 50.1%.
- Placeness: biome regions 8.4/km² (2 major, largest 1.2%), landform regions 13.3/km² (largest 4.4%), dominant place 4.4% of the border.

## Layers

- Columns by class: void 52.8%, roofed 0%, stacked 0%, overhang 4.7%, cave 1.6%, single 40.8%.
- Columns with each layer: sky 0%, surface 47.2%, underground 44.9%, roof 0%.
- Overhangs in 4.7% of columns, stacked layers in 0%, cave pockets in 2.1%.

## Places

- **surface** (351 cells, 2 types, silhouette 0.294):
  - type 1: 65.8% of cells; biome end_highlands; ground end_stone; top end_stone; height 84; relief over 16 blocks 17.0 (layer 26.1) — camera [680.5,87,968.5]
  - type 2: 34.2% of cells; biome end_highlands; ground end_stone; top end_stone; height 68; layer present in 61% (layer 84%); neighbour step 2.3 (layer 1.2); relief over 16 blocks 43.7 (layer 26.1); overhangs 23% (layer 11%) — camera [1064.5,81,579.5]
  - landmark at [2216,3720]: biome adventure:sheared_highlands (type end_highlands); ground smooth_basalt (type end_stone); top smooth_basalt (type end_stone); bare rock 34% (type 100%); notable-block density (log) 1.4 (type 0.0); height 39.2 (type 70.7)
  - landmark at [-1928,-40]: biome adventure:sheared_highlands (type end_highlands); notable-block density (log) 0.7 (type 0.0); bare rock 81% (type 99%); cave pockets 22% (type 0%)
  - landmark at [2472,2776]: biome adventure:sheared_highlands (type end_highlands); notable-block density (log) 0.5 (type 0.0); bare rock 87% (type 100%); relief over 16 blocks 25.0 (type 49.0)
  - landmark at [2456,2808]: biome adventure:sheared_highlands (type end_highlands); notable-block density (log) 0.5 (type 0.0); bare rock 88% (type 100%); relief over the patch 38.0 (type 63.0)
  - landmark at [-24,-2984]: neighbour step 4.4 (type 1.4); notable-block density (log) 0.4 (type 0.0); layer present in 97% (type 48%)
- **sky**: no cells.
- **underground** (334 cells, 2 types, silhouette 0.389):
  - type 1: 71.3% of cells; biome end_highlands; notableId smooth_basalt; height 83 — camera [2092.5,82.88,1350.5]
  - type 2: 28.7% of cells; biome end_highlands; notableId chorus_flower; height 73; layer present in 60% (layer 84%); neighbour step 2.1 (layer 1.2); relief over 16 blocks 37.4 (layer 23.1); cave air 13% (layer 5%); cave pockets 11% (layer 5%) — camera [2666.5,57.88,364.5]
  - landmark at [552,3176]: biome small_end_islands (type end_highlands); cave pockets 64% (type 8%); cave air 36% (type 8%); layer present in 98% (type 34%)
  - landmark at [1960,-72]: biome end_midlands (type none); cave air 51% (type 0%); cave pockets 45% (type 0%); layer present in 39% (type 100%)
  - landmark at [2232,3736]: notableId smooth_basalt (type none); biome adventure:sheared_highlands (type end_highlands); notable-block density (log) 0.1 (type 0.0); layer present in 98% (type 34%); neighbour step 0.9 (type 2.1)
  - landmark at [568,3176]: biome small_end_islands (type end_highlands); cave air 47% (type 8%); neighbour step 5.4 (type 2.1); height 64.3 (type 78.3)
  - landmark at [-600,-408]: biome the_end (type end_highlands); cave air 39% (type 8%); relief over 16 blocks 3.0 (type 44.0); neighbour step 0.1 (type 2.1)
- **roof**: no cells.

## Biome expression

6 of 6 placed biomes are expressed.

- minecraft:end_highlands: expressed, 67114 columns; placement land 57.6%, void 42.4%; snow 0%, canopy 0%, trees 0/ha; top end_stone 55.1%, chorus_plant 1.4%, chorus_flower 1.1%
- minecraft:end_midlands: expressed, 28985 columns; placement land 40.9%, void 59.1%; snow 0%, canopy 0%, trees 0/ha; top end_stone 40.9%
- minecraft:end_barrens: expressed, 23762 columns; placement land 41.8%, void 58.2%; snow 0%, canopy 0%, trees 0/ha; top end_stone 41.8%
- minecraft:the_end: expressed, 17506 columns; placement land 25.1%, void 74.9%; snow 0%, canopy 0%, trees 0/ha; top end_stone 25%, polished_basalt 0%, tuff 0%
- adventure:sheared_highlands: expressed, 14720 columns; placement land 47.5%, void 52.5%; snow 0%, canopy 0%, trees 0/ha; top end_stone 37%, tuff 4.4%, smooth_basalt 3.6%
- minecraft:small_end_islands: expressed, 11520 columns; placement land 46.7%, void 53.3%; snow 0%, canopy 0%, trees 0/ha; top end_stone 46.7%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:end_highlands | minecraft:end_highlands | 23.6 | 67114 | expressed | - |
| O2 | nullscape:crystal_peaks | adventure:ice_starfield | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O3 | minecraft:end_midlands | minecraft:end_midlands | 7.2 | 28985 | expressed | - |
| O4 | minecraft:the_end | minecraft:the_end | 2.7 | 17506 | expressed | - |
| O5 | minecraft:end_barrens | minecraft:end_barrens | 6.1 | 23762 | expressed | - |
| O6 | nullscape:void_barrens | minecraft:end_barrens | 6.1 | 23762 | expressed | - |
| O7 | minecraft:small_end_islands | minecraft:small_end_islands | 3.3 | 11520 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:sheared_highlands | 4.3 | 14720 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:end_highlands 23.6%, minecraft:end_midlands 7.2%, minecraft:end_barrens 6.1% | type `end` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"endIsland": false}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 52.8% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 50.0 blocks a patch; height {"p5": 44.0, "p25": 80.0, "p50": 83.0, "p75": 87.0, "p95": 91.0, "max": 111.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 47.2, "underground": 44.9, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 52.8, "roofed": 0.0, "stacked": 0.0, "overhang": 4.7, "cave": 1.6, "single": 40.8} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 90.9%
- S:b — 1.21.1 biomes expressed: 85.7%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (47.2% of columns)

## Shots

### the_end_citadel-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 0.4%; void 35.1%; mid: end_stone 18%, chorus_plant 1.6%; far: end_stone 35.5%, chorus_plant 6%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-far.png`

### the_end_citadel-surface-t1

Picked for: surface type 1: 65.8% of cells; biome end_highlands; ground end_stone; top end_stone; height 84; relief over 16 blocks 17.0 (layer 26.1)
Census: sky 22.8%; unloaded 0.3%; near: end_stone 60.9%, chorus_plant 7.2%; mid: end_stone 3.2%, chorus_plant 2.8%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-surface-t1.png`

### the_end_citadel-underground-t1

Picked for: underground type 1: 71.3% of cells; biome end_highlands; notableId smooth_basalt; height 83
Census: sky 18.5%; near: end_stone 56%, chorus_plant 12.3%; mid: end_stone 6%, chorus_plant 2.1%; far: end_stone 2.5%; biomes: end_highlands 99.9%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-underground-t1.png`

### the_end_citadel-surface-t2

Picked for: surface type 2: 34.2% of cells; biome end_highlands; ground end_stone; top end_stone; height 68; layer present in 61% (layer 84%); neighbour step 2.3 (layer 1.2); relief over 16 blocks 43.7 (layer 26.1); overhangs 23% (layer 11%)
Census: sky 25%; void 2.1%; unloaded 0.3%; near: end_stone 59%, chorus_plant 3.2%; mid: end_stone 1.1%; far: end_stone 7.1%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-surface-t2.png`

### the_end_citadel-underground-t2

Picked for: underground type 2: 28.7% of cells; biome end_highlands; notableId chorus_flower; height 73; layer present in 60% (layer 84%); neighbour step 2.1 (layer 1.2); relief over 16 blocks 37.4 (layer 23.1); cave air 13% (layer 5%); cave pockets 11% (layer 5%)
Census: sky 4.9%; void 2.9%; near: end_stone 72.5%; mid: end_stone 17.7%; far: end_stone 1.5%; biomes: end_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-underground-t2.png`

### the_end_citadel-surface-lm1

Picked for: surface landmark: biome adventure:sheared_highlands (type end_highlands); ground smooth_basalt (type end_stone); top smooth_basalt (type end_stone); bare rock 34% (type 100%); notable-block density (log) 1.4 (type 0.0); height 39.2 (type 70.7)
Census: sky 1%; void 7.1%; near: smooth_basalt 40.5%, end_stone 11.1%; mid: end_stone 35.4%, smooth_basalt 3.8%; biomes: adventure:sheared_highlands 99.9%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-surface-lm1.png`

### the_end_citadel-feature-basalt

Picked for: basalt: 9 clusters in 4 patches, median 1.0 blocks, surface layer
Census: sky 23.7%; near: polished_basalt 31.7%, tuff 23.4%; mid: polished_basalt 1.2%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-feature-basalt.png`

### the_end_citadel-feature-smooth_basalt

Picked for: smooth_basalt: 55 clusters in 4 patches, median 5.0 blocks, surface layer
Census: sky 23.8%; near: end_stone 43.1%, smooth_basalt 28.4%; biomes: adventure:sheared_highlands 100%
Image: `/tmp/claude-0/w10c3/reviews/the_end_citadel/screens/the_end_citadel-feature-smooth_basalt.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
