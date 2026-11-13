# Review packet: the_violet_spire

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: terralith:amethyst_canyon, terralith:windswept_spires, nullscape:crystal_peaks, terralith:haze_mountain, terralith:skylands_spring, paradise_lost:highlands, paradise_lost:highlands_thicket, paradise_lost:wisteria_woods, paradise_lost:highlands_forest, minecraft:cherry_grove, natures_spirit:lavender_fields

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: adventure:wisteria_grove, adventure:lavender_fields, adventure:sky_highlands, minecraft:cherry_grove, adventure:umbrella_jungle, adventure:chorus_forest

Terrain: `{"base": "floating_islands", "params": {"altitude": 120, "coverage": 0.5, "size": 2.25, "thickness": 14, "underside": 50, "spread": 10, "flatness": 0.5, "spires": 0.2, "spireHeight": 100, "spireThickness": 0.75, "spireTaper": 0.4}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 640}, "scale": 2}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: ?, ?

## Survey

Dossier: `/tmp/claude-0/w10c1q/final/the_violet_spire/dossier.json`; layer maps in `/tmp/claude-0/w10c1q/final/the_violet_spire/layers`

## Summary

- Top blocks seen from the sky: grass_block 25.1%, short_grass 5.1%, allium 3.9%, cherry_leaves 3.7%.
- Surface biomes: adventure:sky_highlands 13%, adventure:lavender_fields 12.9%, cherry_grove 10%, adventure:wisteria_grove 5.5%, adventure:umbrella_jungle 4.2%.
- Ground blocks: grass_block 44.2%, amethyst_block 2.7%, purpur_block 0.9%, moss_block 0.8%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 5.6%; bare rock 0.1%; no ground (void) 50.7%.
- Trees: 13.6 per ha (oak_log 6.7/ha, mean spacing 11.4 blocks, cherry_log 5.8/ha, mean spacing 7.3 blocks, birch_log 1.2/ha, mean spacing 12 blocks); ground plants flower 10%, grass 6%, other 0.6%.
- Surface height p5 113, median 135, p95 236, max 243; median relief per patch 9 blocks; slopes under 15° 56.7%, over 45° 17.8%, cliffs 5.2%; above the clouds 21.5%, below sea level 0%.
- Underground: 1.7% open, 0% liquid; cave biomes adventure:lavender_fields 27.6%, adventure:sky_highlands 24.9%, cherry_grove 20%, adventure:wisteria_grove 11.9%.
- Notable clusters, fewest first: lava 2 clusters, spacing n/a; bee_nest 8 clusters, spacing 16; moss_block 19 clusters, spacing 10.8; quartz_block 21 clusters, spacing 18; vine 24 clusters, spacing 12.6; cornflower 54 clusters, spacing 7.4.
- 5 of 8 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 534 columns: height within 2 blocks 99.1%, biome match 100%.
- Landforms over the border (noise render): plains 20.4%, hills 14.8%, mountains 2%, plateaus 0.1%, basins 5.2%, islands 0%, water 0%, void 57.5%.
- Placeness: biome regions 4.8/km² (7 major, largest 11.6%), landform regions 8.5/km² (largest 56.7%), dominant place 56.7% of the border.

## Layers

- Columns by class: void 50.7%, roofed 0%, stacked 0%, overhang 3.1%, cave 5.3%, single 40.8%.
- Columns with each layer: sky 0%, surface 49.3%, underground 44%, roof 0%.
- Overhangs in 3.1% of columns, stacked layers in 0%, cave pockets in 5.6%.

## Places

- **surface** (138 cells, 2 types, silhouette 0.334):
  - type 1: 77.5% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 149 — camera [264.5,123,-263.5]
  - type 2: 22.5% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 146; trees per cell 1.8 (layer 0.7); canopy 38% (layer 11%); ground plants 56% (layer 33%); open to the sky 62% (layer 89%) — camera [344.5,130,344.5]
  - landmark at [-136,-584]: biome adventure:lavender_fields (type adventure:sky_highlands); ground amethyst_block (type grass_block); top amethyst_block (type grass_block); neighbour step 6.4 (type 0.7); height 215.1 (type 120.8); cave pockets 46% (type 0%)
  - landmark at [536,648]: biome adventure:chorus_forest (type adventure:sky_highlands); ground purpur_block (type grass_block); top purpur_block (type grass_block); cave pockets 84% (type 0%); neighbour step 5.6 (type 0.7); relief over 16 blocks 99.0 (type 12.0)
  - landmark at [520,648]: biome adventure:chorus_forest (type adventure:sky_highlands); ground purpur_block (type grass_block); top purpur_block (type grass_block); cave pockets 61% (type 0%); height 205.6 (type 120.8); relief over 16 blocks 100.0 (type 12.0)
- **sky**: no cells.
- **underground** (121 cells, 2 types, silhouette 0.367):
  - type 1: 71.1% of cells; notableId coal_ore; biome adventure:lavender_fields; height 136; neighbour step 0.4 (layer 1.5) — camera [350.5,109.88,349.5]
  - type 2: 28.9% of cells; notableId coal_ore; biome adventure:sky_highlands; height 191; neighbour step 4.2 (layer 1.5); relief over 16 blocks 90.7 (layer 39.8); notable-block density (log) 1.0 (layer 0.6); cave pockets 29% (layer 13%) — camera [461.5,87.88,586.5]
  - landmark at [504,472]: biome adventure:umbrella_jungle (type cherry_grove); cave air 11% (type 1%); notable-block density (log) 0.0 (type 1.5); cave pockets 79% (type 29%)
  - landmark at [536,472]: biome adventure:umbrella_jungle (type none); cave pockets 55% (type 0%); height 236.3 (type 135.0); cave air 3% (type 0%)
  - landmark at [520,472]: biome adventure:umbrella_jungle (type cherry_grove); notable-block density (log) 0.0 (type 1.5); cave air 6% (type 1%); cave pockets 61% (type 29%)
  - landmark at [40,-632]: biome adventure:lavender_fields (type none); cave air 7% (type 0%); cave pockets 32% (type 0%); relief over 16 blocks 68.0 (type 4.0)
  - landmark at [152,-632]: biome adventure:sky_highlands (type cherry_grove); cave air 8% (type 1%); relief over 16 blocks 6.0 (type 99.0); neighbour step 0.1 (type 3.8)
- **roof**: no cells.

## Biome expression

5 of 8 placed biomes are expressed.

- adventure:lavender_fields: expressed, 24864 columns; placement land 36%, void 64%; snow 0%, canopy 2%, trees 8.9/ha; top grass_block 15.2%, allium 10.1%, short_grass 4.9%
- adventure:umbrella_jungle: expressed, 11264 columns; placement land 25.7%, void 74.3%; snow 0%, canopy 0%, trees 0/ha; top grass_block 25.7%
- adventure:sky_highlands: expressed, 10352 columns; placement land 86.7%, void 13.3%; snow 0%, canopy 4.6%, trees 18.9/ha; top grass_block 52.8%, short_grass 13.5%, amethyst_block 11.1%
- adventure:wisteria_grove: expressed, 9008 columns; placement land 42%, void 58%; snow 0%, canopy 19.1%, trees 76.7/ha; top grass_block 17.3%, vine 7.6%, oak_leaves 4.8%
- minecraft:cherry_grove: expressed, 6912 columns; placement land 99.8%, void 0.2%; snow 0%, canopy 37.6%, trees 58/ha; top cherry_leaves 37.4%, pink_petals 26.4%, grass_block 25.1%
- adventure:chorus_forest: placed, not expressed, 2560 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top grass_block 75.5%, purpur_block 24.5%; **features name trees, none on 0.3 ha of land**
- adventure:ice_starfield: too few columns to judge, 2224 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:sparse_jungle: too few columns to judge, 1936 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:amethyst_canyon | adventure:crystal_hollows | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O2 | terralith:windswept_spires | minecraft:jagged_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O3 | nullscape:crystal_peaks | adventure:ice_starfield | 0.0 | 2224 | too few columns to judge | - |
| O4 | terralith:haze_mountain | minecraft:stony_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O5 | terralith:skylands_spring | adventure:sky_highlands | 13.0 | 10352 | expressed | - |
| O6 | paradise_lost:highlands | adventure:sky_highlands | 13.0 | 10352 | expressed | - |
| O7 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:wisteria_woods | adventure:wisteria_grove | 5.5 | 9008 | expressed | - |
| O9 | paradise_lost:highlands_forest | minecraft:forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O10 | minecraft:cherry_grove | minecraft:cherry_grove | 10.0 | 6912 | expressed | - |
| O11 | natures_spirit:lavender_fields | adventure:lavender_fields | 12.9 | 24864 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:umbrella_jungle | 4.2 | 11264 | expressed | - |
| V2 | adventure:chorus_forest | 3.7 | 2560 | placed, not expressed | features name trees, none on 0.3 ha of land |
| V3 | minecraft:sparse_jungle | 0.0 | 1936 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:sky_highlands 13.0%, adventure:lavender_fields 12.9%, minecraft:cherry_grove 10.0% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 50.7% (`surface.voidPercent`) | |
| M:canopy | 5.6% (`surface.canopyPercent`) | |
| M:relief | median 9.0 blocks a patch; height {"p5": 113.0, "p25": 123.0, "p50": 135.0, "p75": 144.0, "p95": 236.0, "max": 243.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 49.3, "underground": 44.0, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#6B4B9B", "fogColor": "#3A2352", "ambientLight": 0.25}` |
| M:classes | {"void": 50.7, "roofed": 0.0, "stacked": 0.0, "overhang": 3.1, "cave": 5.3, "single": 40.8} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 84.0%
- S:b — 1.21.1 biomes expressed: 45.5%
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: sky present (49.3% of columns)

## Shots

### the_violet_spire-far

Picked for: far: the border ring at bearing 85, facing the centre; 0.0% of rays could reach the border
Census: sky 19.2%; void 57.3%; far: stone 8.6%, grass_block 5.4%; biomes: cherry_grove 99.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-far.png`

### the_violet_spire-surface-t1

Picked for: surface type 1: 77.5% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 149
Census: sky 3%; unloaded 0.1%; near: short_grass 25.3%, grass_block 19.1%; mid: stone 6.3%; far: stone 5.5%, amethyst_block 2.4%; biomes: adventure:sky_highlands 85.9%, adventure:wisteria_grove 12.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-surface-t1.png`

### the_violet_spire-underground-t1

Picked for: underground type 1: 71.1% of cells; notableId coal_ore; biome adventure:lavender_fields; height 136; neighbour step 0.4 (layer 1.5)
Census: sky 7.3%; void 35.7%; near: stone 32.9%, dirt 9.3%; far: stone 3.3%; biomes: cherry_grove 92.7%, adventure:sky_highlands 6.9%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-underground-t1.png`

### the_violet_spire-surface-t2

Picked for: surface type 2: 22.5% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 146; trees per cell 1.8 (layer 0.7); canopy 38% (layer 11%); ground plants 56% (layer 33%); open to the sky 62% (layer 89%)
Census: sky 12.7%; void 1.8%; near: cherry_leaves 42.3%, grass_block 19.3%; mid: grass_block 1.3%, short_grass 1%; biomes: cherry_grove 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-surface-t2.png`

### the_violet_spire-underground-t2

Picked for: underground type 2: 28.9% of cells; notableId coal_ore; biome adventure:sky_highlands; height 191; neighbour step 4.2 (layer 1.5); relief over 16 blocks 90.7 (layer 39.8); notable-block density (log) 1.0 (layer 0.6); cave pockets 29% (layer 13%)
Census: sky 0%; void 5.1%; near: stone 49.6%, grass_block 38.2%; mid: water 1.4%; biomes: cherry_grove 99.8%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-underground-t2.png`

### the_violet_spire-surface-lm1

Picked for: surface landmark: biome adventure:lavender_fields (type adventure:sky_highlands); ground amethyst_block (type grass_block); top amethyst_block (type grass_block); neighbour step 6.4 (type 0.7); height 215.1 (type 120.8); cave pockets 46% (type 0%)
Census: sky 32.8%; near: lilac 39.7%; mid: stone 4.3%, dirt 4.1%; far: stone 5.7%, grass_block 2.3%; biomes: adventure:lavender_fields 94.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-surface-lm1.png`

### the_violet_spire-feature-moss_block

Picked for: moss_block: 19 clusters in 3 patches, median 30.0 blocks, surface layer
Census: sky 0%; near: vine 30.3%, grass_block 23%; biomes: adventure:wisteria_grove 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_violet_spire/screens/the_violet_spire-feature-moss_block.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
