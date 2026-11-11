# Review packet: the_violet_spire

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: terralith:amethyst_canyon, terralith:windswept_spires, nullscape:crystal_peaks, terralith:haze_mountain, terralith:skylands_spring, paradise_lost:highlands, paradise_lost:highlands_thicket, paradise_lost:wisteria_woods, paradise_lost:highlands_forest, minecraft:cherry_grove, natures_spirit:lavender_fields

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: adventure:wisteria_grove, adventure:lavender_fields, adventure:sky_highlands, minecraft:cherry_grove, adventure:umbrella_jungle, adventure:chorus_forest

Terrain: `{"base": "spires", "floor": "islands", "params": {"landHeight": 120, "spireHeight": 120, "coverage": 0.12, "thickness": 2.5, "taper": 0.2}, "scales": {"climate": 3}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: ?, ?

## Survey

Dossier: `/tmp/claude-0/w10c1/runs/sel/the_violet_spire/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/sel/the_violet_spire/layers`

## Summary

- Top blocks seen from the sky: grass_block 23.9%, amethyst_block 3.8%, short_grass 3.3%, allium 2.6%.
- Surface biomes: adventure:lavender_fields 14.7%, adventure:sky_highlands 11.2%, adventure:wisteria_grove 10.7%, adventure:chorus_forest 2.1%, adventure:umbrella_jungle 1.4%.
- Ground blocks: grass_block 34.5%, amethyst_block 4%, moss_block 1.3%, snow_block 0.9%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 1.2% (a snowing climate over 0%); tree canopy 3%; bare rock 0.3%; no ground (void) 58%.
- Trees: 11.5 per ha (oak_log 10.4/ha, mean spacing 8.6 blocks, birch_log 0.7/ha, mean spacing 9.4 blocks, jungle_log 0.3/ha, mean spacing 8.1 blocks, cherry_log 0.1/ha, mean spacing n/a blocks); ground plants flower 4.2%, grass 3.5%, other 1.2%.
- Surface height p5 91, median 123, p95 239, max 241; median relief per patch 12 blocks; slopes under 15° 47.6%, over 45° 22.5%, cliffs 7.4%; above the clouds 28.1%, below sea level 0%.
- Underground: 3.5% open, 0% liquid; cave biomes adventure:lavender_fields 34.5%, adventure:wisteria_grove 27.1%, adventure:sky_highlands 26%, adventure:chorus_forest 4.2%.
- Notable clusters, fewest first: bee_nest 3 clusters, spacing n/a; lava 4 clusters, spacing 29.6; quartz_block 20 clusters, spacing 16; moss_block 31 clusters, spacing 14.1; cornflower 37 clusters, spacing 7.1; pink_petals 42 clusters, spacing 2.8.
- 8 of 8 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 475 columns: height within 2 blocks 98.9%, biome match 100%.
- Landforms over the border (noise render): plains 6.9%, hills 5.2%, mountains 1.2%, plateaus 0.7%, basins 1.9%, islands 16%, water 0%, void 68.1%.
- Placeness: biome regions 9.6/km² (6 major, largest 1.7%), landform regions 8.0/km² (largest 66.6%), dominant place 66.6% of the border.

## Layers

- Columns by class: void 58%, roofed 0%, stacked 2.9%, overhang 2.3%, cave 4.2%, single 32.6%.
- Columns with each layer: sky 2.9%, surface 42%, underground 28.6%, roof 0%.
- Overhangs in 2.4% of columns, stacked layers in 2.9%, cave pockets in 4.5%.

## Places

- **surface** (135 cells, 8 types, silhouette 0.334):
  - type 1: 24.4% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 124; relief over the patch 32.0 (layer 79.0); trees per cell 1.8 (layer 0.6); notable-block density (log) 1.0 (layer 0.6); canopy 24% (layer 7%) — camera [359.5,122,-119.5]
  - type 2: 17.0% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 123; trees per cell 0.1 (layer 0.6); canopy 2% (layer 7%); ground plants 44% (layer 22%); bare rock 4% (layer 1%); overhangs 11% (layer 5%) — camera [376.5,124,584.5]
  - type 3: 14.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 176; neighbour step 4.7 (layer 1.7); relief over 16 blocks 105.5 (layer 42.3); relief over the patch 117.2 (layer 79.0) — camera [-54.5,124,-486.5]
  - type 4: 11.9% of cells; biome adventure:chorus_forest; ground grass_block; top grass_block; height 231; neighbour step 0.2 (layer 1.7); relief over 16 blocks 7.0 (layer 42.3); relief over the patch 117.6 (layer 79.0); notable-block density (log) 0.1 (layer 0.6); ground plants 12% (layer 22%); cave pockets 22% (layer 10%) — camera [-631.5,240,-231.5]
  - type 5: 11.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 122; layer present in 72% (layer 85%); neighbour step 0.4 (layer 1.7); relief over 16 blocks 10.6 (layer 42.3); relief over the patch 51.1 (layer 79.0); notable-block density (log) 0.1 (layer 0.6) — camera [280.5,123,-647.5]
  - type 6: 11.1% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 187; layer present in 99% (layer 85%); neighbour step 5.6 (layer 1.7); relief over 16 blocks 110.4 (layer 42.3); relief over the patch 116.1 (layer 79.0); notable-block density (log) 0.8 (layer 0.6); cave pockets 41% (layer 10%) — camera [661.5,229,39.5]
  - type 7: 6.7% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 94; layer present in 100% (layer 85%); relief over the patch 155.0 (layer 79.0); trees per cell 0.0 (layer 0.6); notable-block density (log) 0.3 (layer 0.6); canopy 0% (layer 7%); ground plants 3% (layer 22%); open to the sky 10% (layer 87%); stacked layers 90% (layer 6%) — camera [312.5,92,280.5]
  - type 8: 3.7% of cells; biome adventure:ice_starfield; ground snow_block; top snow_block; height 121; layer present in 63% (layer 85%); neighbour step 0.3 (layer 1.7); relief over 16 blocks 5.8 (layer 42.3); relief over the patch 12.0 (layer 79.0); trees per cell 0.0 (layer 0.6); canopy 0% (layer 7%); ground plants 0% (layer 22%); snow or ice 100% (layer 4%); open to the sky 100% (layer 87%) — camera [-151.5,121,248.5]
  - landmark at [632,8]: biome adventure:wisteria_grove (type adventure:lavender_fields); trees per cell 3.0 (type 0.0); canopy 30% (type 0%); open to the sky 70% (type 100%)
  - landmark at [584,24]: top vine (type grass_block); notable-block density (log) 2.2 (type 0.9); canopy 49% (type 19%); layer present in 46% (type 92%)
  - landmark at [-360,-168]: biome sparse_jungle (type adventure:sky_highlands); trees per cell 2.0 (type 0.0); layer present in 61% (type 100%); canopy 14% (type 0%)
  - landmark at [568,24]: top vine (type grass_block); canopy 37% (type 19%); layer present in 61% (type 92%); notable-block density (log) 0.3 (type 0.9)
  - landmark at [-72,104]: biome cherry_grove (type adventure:lavender_fields); layer present in 27% (type 100%); overhangs 28% (type 3%); relief over the patch 106.0 (type 22.0)
- **sky** (9 cells, 2 types, silhouette 0.557):
  - type 1: 66.7% of cells; biome adventure:lavender_fields; ground grass_block; height 238; layer present in 100% (layer 90%); neighbour step 0.3 (layer 1.8); relief over 16 blocks 15.2 (layer 44.1); trees per cell 0.0 (layer 0.2) — camera [328.5,240,312.5]
  - type 2: 33.3% of cells; biome adventure:lavender_fields; ground grass_block; height 228; layer present in 70% (layer 90%); neighbour step 4.9 (layer 1.8); relief over 16 blocks 102.0 (layer 44.1); trees per cell 0.7 (layer 0.2); notable-block density (log) 1.8 (layer 1.7) — camera [340.5,240,308.5]
- **underground** (94 cells, 2 types, silhouette 0.359):
  - type 1: 59.6% of cells; notableId coal_ore; biome adventure:chorus_forest; height 147; neighbour step 0.8 (layer 2.5); relief over 16 blocks 26.2 (layer 56.2); notable-block density (log) 0.2 (layer 0.6) — camera [347.5,113.88,621.5]
  - type 2: 40.4% of cells; notableId coal_ore; biome adventure:lavender_fields; height 182; neighbour step 5.0 (layer 2.5); relief over 16 blocks 100.5 (layer 56.2); notable-block density (log) 1.0 (layer 0.6); cave pockets 28% (layer 14%) — camera [650.5,95.88,24.5]
  - landmark at [-632,-664]: biome adventure:sky_highlands (type none); cave pockets 98% (type 0%); cave air 16% (type 0%); height 239.0 (type 122.4)
  - landmark at [568,376]: cave liquid 0% (type 0%); cave air 11% (type 3%); layer present in 57% (type 100%)
- **roof**: no cells.

## Biome expression

8 of 8 placed biomes are expressed.

- adventure:lavender_fields: expressed, 24309 columns; placement land 43%, void 48.4%, sky 8.5%; snow 0%, canopy 0.8%, trees 4.8/ha; top grass_block 23.7%, allium 6.8%, amethyst_block 6%
- adventure:wisteria_grove: expressed, 16736 columns; placement land 45.7%, void 54.3%; snow 0%, canopy 23%, trees 86.3/ha; top grass_block 18.9%, vine 9.6%, oak_leaves 6.4%
- adventure:sky_highlands: expressed, 15584 columns; placement land 51.1%, void 48.9%; snow 0%, canopy 2.5%, trees 10/ha; top grass_block 34.4%, short_grass 7.4%, amethyst_block 5.4%
- adventure:chorus_forest: expressed, 6080 columns; placement land 24.2%, void 75.8%; snow 0%, canopy 0%, trees 0/ha; top grass_block 23.2%, purpur_block 1%
- adventure:umbrella_jungle: expressed, 4944 columns; placement land 20.2%, void 79.8%; snow 0%, canopy 0%, trees 0/ha; top grass_block 20%, purpur_block 0.1%, lilac 0%
- minecraft:cherry_grove: expressed, 2976 columns; placement land 16%, void 84%; snow 0%, canopy 9%, trees 21/ha; top grass_block 8.7%, pink_petals 3.8%, short_grass 1.9%
- adventure:ice_starfield: expressed, 2304 columns; placement land 36%, void 64%; snow 100%, canopy 0%, trees 0/ha; top snow_block 24.7%, packed_ice 9.1%, snow 2.3%
- minecraft:sparse_jungle: expressed, 560 columns; placement land 30.5%, void 69.5%; snow 0%, canopy 17%, trees 117/ha; top grass_block 17.3%, short_grass 5.4%, jungle_leaves 2.7%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:amethyst_canyon | adventure:crystal_hollows | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O2 | terralith:windswept_spires | minecraft:jagged_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O3 | nullscape:crystal_peaks | adventure:ice_starfield | 1.2 | 2304 | expressed | - |
| O4 | terralith:haze_mountain | minecraft:stony_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O5 | terralith:skylands_spring | adventure:sky_highlands | 11.2 | 15584 | expressed | - |
| O6 | paradise_lost:highlands | adventure:sky_highlands | 11.2 | 15584 | expressed | - |
| O7 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:wisteria_woods | adventure:wisteria_grove | 10.7 | 16736 | expressed | - |
| O9 | paradise_lost:highlands_forest | minecraft:forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O10 | minecraft:cherry_grove | minecraft:cherry_grove | 0.7 | 2976 | expressed | - |
| O11 | natures_spirit:lavender_fields | adventure:lavender_fields | 14.7 | 24309 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:chorus_forest | 2.1 | 6080 | expressed | - |
| V2 | adventure:umbrella_jungle | 1.4 | 4944 | expressed | - |
| V3 | minecraft:sparse_jungle | 0.2 | 560 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:lavender_fields 14.7%, adventure:sky_highlands 11.2%, adventure:wisteria_grove 10.7% | type `paradise_lost:paradise_lost` |
| M:snow | 1.2% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 58.0% (`surface.voidPercent`) | |
| M:canopy | 3.0% (`surface.canopyPercent`) | |
| M:relief | median 12.0 blocks a patch; height {"p5": 91.0, "p25": 121.0, "p50": 123.0, "p75": 224.0, "p95": 239.0, "max": 241.0} (`terrain`) | |
| M:layers | {"sky": 2.9, "surface": 42.0, "underground": 28.6, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#6B4B9B", "fogColor": "#3A2352", "ambientLight": 0.25}` |
| M:classes | {"void": 58.0, "roofed": 0.0, "stacked": 2.9, "overhang": 2.3, "cave": 4.2, "single": 32.6} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 91.2%
- S:b — 1.21.1 biomes expressed: 54.5%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (42.0% of columns)

## Shots

### the_violet_spire-surface-t1

Picked for: surface type 1: 24.4% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 124; relief over the patch 32.0 (layer 79.0); trees per cell 1.8 (layer 0.6); notable-block density (log) 1.0 (layer 0.6); canopy 24% (layer 7%)
Census: sky 0.3%; near: vine 55.5%, moss_block 14.8%; biomes: adventure:wisteria_grove 100%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-surface-t1.png`

### the_violet_spire-sky-t1

Picked for: sky type 1: 66.7% of cells; biome adventure:lavender_fields; ground grass_block; height 238; layer present in 100% (layer 90%); neighbour step 0.3 (layer 1.8); relief over 16 blocks 15.2 (layer 44.1); trees per cell 0.0 (layer 0.2)
Census: sky 35.1%; near: lilac 31.1%, grass_block 13.6%; biomes: adventure:lavender_fields 99.1%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-sky-t1.png`

### the_violet_spire-underground-t1

Picked for: underground type 1: 59.6% of cells; notableId coal_ore; biome adventure:chorus_forest; height 147; neighbour step 0.8 (layer 2.5); relief over 16 blocks 26.2 (layer 56.2); notable-block density (log) 0.2 (layer 0.6)
Census: sky 0%; near: grass_block 55.9%, stone 43.9%; biomes: adventure:chorus_forest 99.7%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-underground-t1.png`

### the_violet_spire-surface-t3

Picked for: surface type 3: 14.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 176; neighbour step 4.7 (layer 1.7); relief over 16 blocks 105.5 (layer 42.3); relief over the patch 117.2 (layer 79.0)
Census: sky 11.3%; void 1.2%; near: short_grass 24.8%, grass_block 20.4%; mid: stone 2.7%, amethyst_block 1.5%; far: stone 3.6%; biomes: adventure:sky_highlands 93.2%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-surface-t3.png`

### the_violet_spire-surface-t2

Picked for: surface type 2: 17.0% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 123; trees per cell 0.1 (layer 0.6); canopy 2% (layer 7%); ground plants 44% (layer 22%); bare rock 4% (layer 1%); overhangs 11% (layer 5%)
Census: sky 6.7%; void 0.2%; near: water 35.5%, grass_block 17.1%; mid: dirt 1.5%; far: stone 14.2%, dirt 1.4%; biomes: adventure:lavender_fields 99.6%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-surface-t2.png`

### the_violet_spire-zone-crystal

Picked for: zone 0: the crystal patch (ice_starfield circle, radius 110, at -240, 200) from 160 blocks east
Census: sky 46.3%; mid: stone 2%, packed_ice 1.2%; far: stone 34.2%, packed_ice 6%; biomes: adventure:ice_starfield 41.1%, cherry_grove 21.8%, adventure:umbrella_jungle 14.9%
Image: `/tmp/claude-0/w10c1/reviews/the_violet_spire/screens/the_violet_spire-zone-crystal.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
