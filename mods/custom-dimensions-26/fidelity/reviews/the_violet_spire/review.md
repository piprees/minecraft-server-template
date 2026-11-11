# Review packet: the_violet_spire

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: terralith:amethyst_canyon, terralith:windswept_spires, nullscape:crystal_peaks, terralith:haze_mountain, terralith:skylands_spring, paradise_lost:highlands, paradise_lost:highlands_thicket, paradise_lost:wisteria_woods, paradise_lost:highlands_forest, minecraft:cherry_grove, natures_spirit:lavender_fields

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: adventure:wisteria_grove, adventure:lavender_fields, adventure:sky_highlands, minecraft:cherry_grove, adventure:umbrella_jungle, adventure:chorus_forest

Terrain: `{"base": "floating_islands", "params": {"altitude": 120, "coverage": 0.5, "size": 1.75, "thickness": 14, "underside": 50, "spread": 10, "flatness": 0.5, "spires": 0.2, "spireHeight": 100, "spireThickness": 0.75, "spireTaper": 0.4}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 640}, "scale": 2}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: ?, ?

## Survey

Dossier: `runs/fin/full/the_violet_spire/dossier.json`; layer maps in `runs/fin/full/the_violet_spire/layers`

## Summary

- Top blocks seen from the sky: grass_block 21.7%, short_grass 4.7%, allium 4.7%, cherry_leaves 3.8%.
- Surface biomes: adventure:lavender_fields 18.2%, adventure:wisteria_grove 14%, adventure:sky_highlands 8.4%, cherry_grove 7.8%, adventure:umbrella_jungle 1.5%.
- Ground blocks: grass_block 43.2%, amethyst_block 3.8%, moss_block 2%, moss_carpet 0.6%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 7.6%; bare rock 0.1%; no ground (void) 50%.
- Trees: 20.8 per ha (oak_log 13.8/ha, mean spacing 10.2 blocks, cherry_log 5.7/ha, mean spacing 9.1 blocks, birch_log 1.3/ha, mean spacing 26 blocks); ground plants flower 10.3%, grass 5.8%, other 1.5%.
- Surface height p5 101, median 132, p95 233, max 241; median relief per patch 22 blocks; slopes under 15° 54.1%, over 45° 20.3%, cliffs 6.3%; above the clouds 25.2%, below sea level 0%.
- Underground: 2.1% open, 0% liquid; cave biomes adventure:lavender_fields 35.6%, adventure:wisteria_grove 27.5%, adventure:sky_highlands 17.2%, cherry_grove 16.4%.
- Notable clusters, fewest first: lava 2 clusters, spacing n/a; diamond_ore 6 clusters, spacing 14.6; pale_hanging_moss 6 clusters, spacing 7.9; bee_nest 9 clusters, spacing 25.6; quartz_block 17 clusters, spacing 16.4; cornflower 32 clusters, spacing 7.8.
- 5 of 8 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 523 columns: height within 2 blocks 99.4%, biome match 100%.
- Landforms over the border (noise render): plains 22.6%, hills 14.4%, mountains 1.4%, plateaus 0.1%, basins 5.2%, islands 0%, water 0%, void 56.3%.
- Placeness: biome regions 5.9/km² (9 major, largest 10.3%), landform regions 9.1/km² (largest 52.5%), dominant place 52.5% of the border.

## Layers

- Columns by class: void 50%, roofed 0%, stacked 0%, overhang 2.5%, cave 8.2%, single 39.3%.
- Columns with each layer: sky 0%, surface 50%, underground 48.3%, roof 0%.
- Overhangs in 2.5% of columns, stacked layers in 0%, cave pockets in 8.5%.

## Places

- **surface** (135 cells, 8 types, silhouette 0.279):
  - type 1: 21.5% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 147; neighbour step 0.3 (layer 1.5); relief over 16 blocks 11.4 (layer 41.2); trees per cell 0.3 (layer 1.0); canopy 3% (layer 15%); ground plants 49% (layer 35%); open to the sky 97% (layer 85%) — camera [344.5,137,536.5]
  - type 2: 19.3% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 121; neighbour step 0.3 (layer 1.5); relief over 16 blocks 12.7 (layer 41.2); trees per cell 2.3 (layer 1.0); notable-block density (log) 1.0 (layer 0.6); canopy 27% (layer 15%); open to the sky 73% (layer 85%) — camera [248.5,124,-247.5]
  - type 3: 18.5% of cells; biome adventure:lavender_fields; ground grass_block; top grass_block; height 177; neighbour step 3.6 (layer 1.5); relief over 16 blocks 97.4 (layer 41.2); relief over the patch 107.2 (layer 86.6); trees per cell 0.0 (layer 1.0); canopy 2% (layer 15%); open to the sky 98% (layer 85%); cave pockets 32% (layer 17%) — camera [439.5,127,589.5]
  - type 4: 14.1% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 114; layer present in 82% (layer 96%); neighbour step 0.4 (layer 1.5); relief over 16 blocks 8.1 (layer 41.2); relief over the patch 54.6 (layer 86.6); notable-block density (log) 0.3 (layer 0.6); cave pockets 1% (layer 17%) — camera [-647.5,108,-600.5]
  - type 5: 9.6% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 196; neighbour step 3.0 (layer 1.5); relief over 16 blocks 93.8 (layer 41.2); relief over the patch 115.8 (layer 86.6); trees per cell 1.8 (layer 1.0); notable-block density (log) 0.9 (layer 0.6); cave pockets 43% (layer 17%) — camera [120.5,221,-216.5]
  - type 6: 7.4% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 133; relief over 16 blocks 12.5 (layer 41.2); trees per cell 2.9 (layer 1.0); canopy 59% (layer 15%); ground plants 48% (layer 35%); open to the sky 41% (layer 85%); overhangs 0% (layer 5%) — camera [-23.5,128,-455.5]
  - type 7: 5.9% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 178; neighbour step 5.8 (layer 1.5); relief over 16 blocks 102.3 (layer 41.2); relief over the patch 111.5 (layer 86.6); trees per cell 0.1 (layer 1.0); canopy 1% (layer 15%); ground plants 15% (layer 35%); open to the sky 99% (layer 85%); cave pockets 4% (layer 17%) — camera [-439.5,219,647.5]
  - type 8: 3.7% of cells; biome cherry_grove; ground grass_block; top pink_petals; height 122; neighbour step 0.3 (layer 1.5); trees per cell 0.4 (layer 1.0); notable-block density (log) 0.2 (layer 0.6); ground plants 70% (layer 35%); cave pockets 4% (layer 17%) — camera [-39.5,121,-407.5]
  - landmark at [584,376]: ground amethyst_block (type grass_block); top amethyst_block (type grass_block); notable-block density (log) 0.8 (type 0.3); overhangs 11% (type 0%); ground plants 7% (type 23%)
  - landmark at [-56,-456]: biome cherry_grove (type adventure:wisteria_grove); neighbour step 6.3 (type 1.4); overhangs 19% (type 0%); height 154.1 (type 215.5)
  - landmark at [120,-200]: biome cherry_grove (type adventure:lavender_fields); overhangs 27% (type 0%); cave pockets 60% (type 15%); bare rock 1% (type 0%)
  - landmark at [360,-456]: biome adventure:wisteria_grove (type adventure:sky_highlands); bare rock 5% (type 0%); overhangs 35% (type 0%); layer present in 86% (type 100%)
  - landmark at [120,-232]: biome adventure:umbrella_jungle (type cherry_grove); neighbour step 5.2 (type 0.0); relief over 16 blocks 100.0 (type 3.0); ground plants 24% (type 48%)
- **sky**: no cells.
- **underground** (131 cells, 2 types, silhouette 0.392):
  - type 1: 62.6% of cells; notableId coal_ore; biome adventure:wisteria_grove; height 125; relief over 16 blocks 13.8 (layer 41.2); notable-block density (log) 0.4 (layer 0.7) — camera [311.5,118.88,337.5]
  - type 2: 37.4% of cells; notableId coal_ore; biome adventure:lavender_fields; height 188; neighbour step 3.3 (layer 1.6); relief over 16 blocks 87.1 (layer 41.2); notable-block density (log) 1.3 (layer 0.7); cave pockets 31% (layer 17%) — camera [516.5,106.88,636.5]
  - landmark at [104,-200]: biome cherry_grove (type adventure:lavender_fields); cave pockets 90% (type 15%); cave air 8% (type 1%); neighbour step 0.8 (type 3.0)
  - landmark at [-56,-456]: biome cherry_grove (type adventure:lavender_fields); neighbour step 6.3 (type 3.0); notable-block density (log) 0.8 (type 1.5); cave air 4% (type 1%)
  - landmark at [88,-200]: biome cherry_grove (type adventure:lavender_fields); cave air 10% (type 1%); cave pockets 80% (type 15%); layer present in 84% (type 100%)
  - landmark at [104,-216]: biome cherry_grove (type none); cave pockets 77% (type 0%); height 217.9 (type 122.8); cave air 3% (type 0%)
  - landmark at [328,344]: biome adventure:wisteria_grove (type none); cave air 12% (type 0%); cave pockets 18% (type 0%); cave liquid 0% (type 0%)
- **roof**: no cells.

## Biome expression

5 of 8 placed biomes are expressed.

- adventure:wisteria_grove: expressed, 19152 columns; placement land 48.7%, void 51.3%; snow 0%, canopy 23.2%, trees 86.8/ha; top grass_block 19.2%, vine 9.9%, oak_leaves 6.7%
- adventure:lavender_fields: expressed, 17904 columns; placement land 68%, void 32%; snow 0%, canopy 2.1%, trees 7.4/ha; top grass_block 31.1%, allium 15.9%, short_grass 7.8%
- adventure:sky_highlands: expressed, 10864 columns; placement land 51.8%, void 48.2%; snow 0%, canopy 9%, trees 21.3/ha; top grass_block 29.1%, short_grass 7.4%, amethyst_block 6%
- minecraft:cherry_grove: expressed, 7872 columns; placement land 66.3%, void 33.7%; snow 0%, canopy 35.8%, trees 69/ha; top cherry_leaves 22.6%, grass_block 18.7%, pink_petals 16.1%
- adventure:umbrella_jungle: expressed, 4032 columns; placement land 25.6%, void 74.4%; snow 0%, canopy 28.3%, trees 9.7/ha; top grass_block 14.8%, cherry_leaves 7.2%, pink_petals 1.8%
- adventure:chorus_forest: too few columns to judge, 2832 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top grass_block 0%
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
| O5 | terralith:skylands_spring | adventure:sky_highlands | 8.4 | 10864 | expressed | - |
| O6 | paradise_lost:highlands | adventure:sky_highlands | 8.4 | 10864 | expressed | - |
| O7 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:wisteria_woods | adventure:wisteria_grove | 14.0 | 19152 | expressed | - |
| O9 | paradise_lost:highlands_forest | minecraft:forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O10 | minecraft:cherry_grove | minecraft:cherry_grove | 7.8 | 7872 | expressed | - |
| O11 | natures_spirit:lavender_fields | adventure:lavender_fields | 18.2 | 17904 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:umbrella_jungle | 1.5 | 4032 | expressed | - |
| V2 | adventure:chorus_forest | 0.0 | 2832 | too few columns to judge | - |
| V3 | minecraft:sparse_jungle | 0.0 | 1936 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:lavender_fields 18.2%, adventure:wisteria_grove 14.0%, adventure:sky_highlands 8.4% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 50.0% (`surface.voidPercent`) | |
| M:canopy | 7.6% (`surface.canopyPercent`) | |
| M:relief | median 22.0 blocks a patch; height {"p5": 101.0, "p25": 120.0, "p50": 132.0, "p75": 192.0, "p95": 233.0, "max": 241.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 50.0, "underground": 48.3, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#6B4B9B", "fogColor": "#3A2352", "ambientLight": 0.25}` |
| M:classes | {"void": 50.0, "roofed": 0.0, "stacked": 0.0, "overhang": 2.5, "cave": 8.2, "single": 39.3} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 97.0%
- S:b — 1.21.1 biomes expressed: 45.5%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (50.0% of columns)

## Shots

### the_violet_spire-far

Picked for: far: the border ring at bearing 100, facing the centre; 0.0% of rays could reach the border
Census: sky 25.7%; void 56%; far: stone 7.7%, grass_block 3.9%; biomes: adventure:lavender_fields 88.2%, adventure:wisteria_grove 11.8%
Image: `/tmp/claude-0/w10c1r/reviews/the_violet_spire/screens/the_violet_spire-far.png`

### the_violet_spire-surface-t2

Picked for: surface type 2: 19.3% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 121; neighbour step 0.3 (layer 1.5); relief over 16 blocks 12.7 (layer 41.2); trees per cell 2.3 (layer 1.0); notable-block density (log) 1.0 (layer 0.6); canopy 27% (layer 15%); open to the sky 73% (layer 85%)
Census: sky 0%; near: grass_block 32.9%, vine 20.6%; mid: stone 2%, vine 1%; biomes: adventure:wisteria_grove 71.4%, cherry_grove 20.5%, adventure:umbrella_jungle 8.2%
Image: `/tmp/claude-0/w10c1r/reviews/the_violet_spire/screens/the_violet_spire-surface-t2.png`

### the_violet_spire-underground-t1

Picked for: underground type 1: 62.6% of cells; notableId coal_ore; biome adventure:wisteria_grove; height 125; relief over 16 blocks 13.8 (layer 41.2); notable-block density (log) 0.4 (layer 0.7)
Census: sky 0%; near: stone 50.3%, grass_block 37.9%; mid: grass_block 4.9%, dirt 2.1%; biomes: adventure:wisteria_grove 78.9%, adventure:lavender_fields 21.1%
Image: `/tmp/claude-0/w10c1r/reviews/the_violet_spire/screens/the_violet_spire-underground-t1.png`

### the_violet_spire-surface-t6

Picked for: surface type 6: 7.4% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 133; relief over 16 blocks 12.5 (layer 41.2); trees per cell 2.9 (layer 1.0); canopy 59% (layer 15%); ground plants 48% (layer 35%); open to the sky 41% (layer 85%); overhangs 0% (layer 5%)
Census: sky 0%; near: cherry_log 24.4%, short_grass 23.8%; biomes: cherry_grove 88.7%, adventure:sky_highlands 10.9%
Image: `/tmp/claude-0/w10c1r/reviews/the_violet_spire/screens/the_violet_spire-surface-t6.png`

### the_violet_spire-surface-lm1

Picked for: surface landmark: ground amethyst_block (type grass_block); top amethyst_block (type grass_block); notable-block density (log) 0.8 (type 0.3); overhangs 11% (type 0%); ground plants 7% (type 23%)
Census: sky 43.4%; void 25.8%; near: lilac 1.8%; far: stone 10.5%, amethyst_block 5.9%; biomes: adventure:wisteria_grove 62.9%, adventure:lavender_fields 37.1%
Image: `/tmp/claude-0/w10c1r/reviews/the_violet_spire/screens/the_violet_spire-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
