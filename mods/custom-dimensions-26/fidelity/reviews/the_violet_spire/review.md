# Review packet: the_violet_spire

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: terralith:amethyst_canyon, terralith:windswept_spires, nullscape:crystal_peaks, terralith:haze_mountain, terralith:skylands_spring, paradise_lost:highlands, paradise_lost:highlands_thicket, paradise_lost:wisteria_woods, paradise_lost:highlands_forest, minecraft:cherry_grove, natures_spirit:lavender_fields

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_violet_spire.json`

> Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke.

Biomes: adventure:wisteria_grove, adventure:lavender_fields, adventure:sky_highlands, adventure:umbrella_jungle, adventure:chorus_forest, adventure:crystal_hollows

Terrain: `{"base": "floating_islands", "params": {"altitude": 120, "coverage": 0.5, "size": 2.25, "thickness": 14, "underside": 50, "spread": 10, "flatness": 0.5, "spires": 0.3, "spireHeight": 100, "spireThickness": 1.1, "spireTaper": 0.25}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 640}, "scale": 2}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: ?, ?

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_violet_spire/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_violet_spire/layers`

## Summary

- Top blocks seen from the sky: grass_block 22.1%, amethyst_block 8.9%, short_grass 4%, vine 3.1%.
- Surface biomes: adventure:wisteria_grove 15.5%, adventure:sky_highlands 12.1%, adventure:lavender_fields 8.4%, adventure:crystal_hollows 6.7%, adventure:umbrella_jungle 4.4%.
- Ground blocks: grass_block 34.7%, amethyst_block 8.9%, moss_block 2.3%, moss_carpet 0.7%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 4.1%; bare rock 0.1%; no ground (void) 52.7%.
- Trees: 15.2 per ha (oak_log 14.2/ha, mean spacing 9.4 blocks, birch_log 1/ha, mean spacing 16.9 blocks); ground plants grass 4.3%, flower 4.3%, other 1.5%.
- Surface height p5 110, median 136, p95 236, max 247; median relief per patch 7 blocks; slopes under 15° 61.2%, over 45° 13.8%, cliffs 3.6%; above the clouds 31.3%, below sea level 0%.
- Underground: 2.1% open, 0% liquid; cave biomes adventure:wisteria_grove 32.8%, adventure:sky_highlands 22%, adventure:lavender_fields 20.6%, adventure:crystal_hollows 13.5%.
- Notable clusters, fewest first: mossy_cobblestone 2 clusters, spacing n/a; spawner 2 clusters, spacing n/a; chest 3 clusters, spacing 5.1; cobblestone 4 clusters, spacing 2.6; diamond_ore 4 clusters, spacing 4.1; lava 5 clusters, spacing 26.9.
- 6 of 8 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 513 columns: height within 2 blocks 99.6%, biome match 100%.
- Landforms over the border (noise render): plains 20.2%, hills 13.4%, mountains 2.5%, plateaus 0.8%, basins 5.8%, islands 0%, water 0%, void 57.4%.
- Placeness: biome regions 6.4/km² (9 major, largest 9.9%), landform regions 9.6/km² (largest 56.7%), dominant place 56.7% of the border.

## Layers

- Columns by class: void 52.7%, roofed 0%, stacked 0%, overhang 2.5%, cave 6.5%, single 38.2%.
- Columns with each layer: sky 0%, surface 47.3%, underground 43.3%, roof 0%.
- Overhangs in 2.5% of columns, stacked layers in 0%, cave pockets in 6.7%.

## Places

- **surface** (131 cells, 2 types, silhouette 0.372):
  - type 1: 78.6% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 153 — camera [72.5,134,-424.5]
  - type 2: 21.4% of cells; biome adventure:crystal_hollows; ground amethyst_block; top amethyst_block; height 171; neighbour step 1.8 (layer 0.8); relief over 16 blocks 56.6 (layer 24.0); relief over the patch 99.5 (layer 71.6); trees per cell 0.1 (layer 0.8); canopy 1% (layer 9%); ground plants 6% (layer 21%); open to the sky 99% (layer 91%); cave pockets 27% (layer 14%) — camera [456.5,137,408.5]
- **sky**: no cells.
- **underground** (120 cells, 6 types, silhouette 0.355):
  - type 1: 23.3% of cells; notableId coal_ore; height 125; layer present in 87% (layer 97%); relief over 16 blocks 4.2 (layer 25.6); notable-block density (log) 0.2 (layer 0.8); cave pockets 0% (layer 15%)
  - type 2: 20.8% of cells; notableId coal_ore; biome adventure:wisteria_grove; height 224; relief over 16 blocks 5.1 (layer 25.6); notable-block density (log) 1.4 (layer 0.8) — camera [334.5,62.88,-34.5]
  - type 3: 20.0% of cells; notableId coal_ore; biome adventure:lavender_fields; height 185; neighbour step 3.1 (layer 0.8); relief over 16 blocks 95.5 (layer 25.6); notable-block density (log) 1.1 (layer 0.8) — camera [289.5,87.88,-558.5]
  - type 4: 16.7% of cells; notableId coal_ore; biome adventure:wisteria_grove; height 128 — camera [129.5,94.88,-222.5]
  - type 5: 10.0% of cells; notableId calcite; biome adventure:crystal_hollows; height 143; notable-block density (log) 1.5 (layer 0.8); cave liquid 0% (layer 0%) — camera [489.5,122.88,435.5]
  - type 6: 9.2% of cells; notableId iron_ore; biome adventure:sky_highlands; height 127; neighbour step 0.1 (layer 0.8); relief over 16 blocks 5.3 (layer 25.6); notable-block density (log) 0.1 (layer 0.8); cave air 13% (layer 2%); cave pockets 42% (layer 15%) — camera [254.5,93.88,573.5]
  - landmark at [360,-40]: biome adventure:umbrella_jungle (type none); cave pockets 95% (type 0%); cave air 14% (type 0%); notable-block density (log) 0.0 (type 0.9)
  - landmark at [408,584]: biome adventure:crystal_hollows (type none); neighbour step 5.3 (type 2.2); cave pockets 32% (type 0%); notable-block density (log) 1.7 (type 0.9)
  - landmark at [392,552]: biome adventure:crystal_hollows (type none); notable-block density (log) 2.2 (type 0.9); cave pockets 47% (type 0%); neighbour step 3.5 (type 2.2)
  - landmark at [40,-648]: biome adventure:lavender_fields (type none); cave pockets 93% (type 0%); height 234.6 (type 186.6); cave air 6% (type 0%)
  - landmark at [392,568]: biome adventure:crystal_hollows (type none); neighbour step 6.3 (type 2.2); cave pockets 23% (type 0%); notable-block density (log) 1.3 (type 0.9)
- **roof**: no cells.

## Biome expression

6 of 8 placed biomes are expressed.

- adventure:lavender_fields: expressed, 23488 columns; placement land 24.8%, void 75.2%; snow 0%, canopy 3.1%, trees 13.7/ha; top grass_block 10.9%, allium 5.2%, amethyst_block 3.2%
- adventure:wisteria_grove: expressed, 17712 columns; placement land 60.3%, void 39.7%; snow 0%, canopy 21.4%, trees 74.9/ha; top grass_block 23.7%, vine 12.1%, oak_leaves 7.9%
- adventure:umbrella_jungle: expressed, 9344 columns; placement land 32.6%, void 67.4%; snow 0%, canopy 0.1%, trees 0/ha; top grass_block 32.2%, short_grass 0.4%, oak_leaves 0%
- adventure:sky_highlands: expressed, 9008 columns; placement land 92.7%, void 7.3%; snow 0%, canopy 4.8%, trees 20.4/ha; top grass_block 59%, short_grass 17.8%, amethyst_block 5.3%
- adventure:crystal_hollows: expressed, 4608 columns; placement land 100%; snow 0%, canopy 0%, trees 0/ha; top amethyst_block 95.7%, calcite 3.3%, stone 0.9%
- adventure:ice_starfield: too few columns to judge, 2624 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- minecraft:sparse_jungle: too few columns to judge, 1840 columns; placement void 100%; snow 0%, canopy 0%, trees 0/ha; top none
- adventure:chorus_forest: expressed, 496 columns; placement land 35.5%, void 64.5%; snow 0%, canopy 0%, trees 0/ha; top grass_block 34.3%, purpur_block 1.2%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | terralith:amethyst_canyon | adventure:crystal_hollows | 6.7 | 4608 | expressed | - |
| O2 | terralith:windswept_spires | minecraft:jagged_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O3 | nullscape:crystal_peaks | adventure:ice_starfield | 0.0 | 2624 | too few columns to judge | - |
| O4 | terralith:haze_mountain | minecraft:stony_peaks | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O5 | terralith:skylands_spring | adventure:sky_highlands | 12.1 | 9008 | expressed | - |
| O6 | paradise_lost:highlands | adventure:sky_highlands | 12.1 | 9008 | expressed | - |
| O7 | paradise_lost:highlands_thicket | minecraft:dark_forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | paradise_lost:wisteria_woods | adventure:wisteria_grove | 15.5 | 17712 | expressed | - |
| O9 | paradise_lost:highlands_forest | minecraft:forest | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O10 | minecraft:cherry_grove | minecraft:cherry_grove | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O11 | natures_spirit:lavender_fields | adventure:lavender_fields | 8.4 | 23488 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:umbrella_jungle | 4.4 | 9344 | expressed | - |
| V2 | adventure:chorus_forest | 0.3 | 496 | expressed | - |
| V3 | minecraft:sparse_jungle | 0.0 | 1840 | too few columns to judge | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:wisteria_grove 15.5%, adventure:sky_highlands 12.1%, adventure:lavender_fields 8.4% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 52.7% (`surface.voidPercent`) | |
| M:canopy | 4.1% (`surface.canopyPercent`) | |
| M:relief | median 7.0 blocks a patch; height {"p5": 110.0, "p25": 121.0, "p50": 136.0, "p75": 204.0, "p95": 236.0, "max": 247.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 47.3, "underground": 43.3, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `{"skyColor": "#6B4B9B", "fogColor": "#3A2352", "ambientLight": 0.25}` |
| M:classes | {"void": 52.7, "roofed": 0.0, "stacked": 0.0, "overhang": 2.5, "cave": 6.5, "single": 38.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 90.1%
- S:b — 1.21.1 biomes expressed: 45.5%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (47.3% of columns)

## Shots

### the_violet_spire-far

Picked for: far: the border ring at bearing 243, facing the centre; 0.0% of rays could reach the border
Census: sky 27.5%; void 17.2%; mid: stone 3.8%, grass_block 1.9%; far: stone 14.1%, grass_block 8.8%; biomes: adventure:lavender_fields 59.6%, adventure:wisteria_grove 40.3%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-far.png`

### the_violet_spire-far-nv

Picked for: far: the border ring at bearing 243, facing the centre; 0.0% of rays could reach the border
Census: sky 27.5%; void 17.2%; mid: stone 3.8%, grass_block 1.9%; far: stone 14.1%, grass_block 8.8%; biomes: adventure:lavender_fields 59.6%, adventure:wisteria_grove 40.3%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-far-nv.png`

### the_violet_spire-surface-t1

Picked for: surface type 1: 78.6% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 153
Census: sky 0.3%; near: vine 31.2%, grass_block 20.2%; mid: stone 10.4%; biomes: adventure:lavender_fields 60.4%, adventure:wisteria_grove 39.6%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-surface-t1.png`

### the_violet_spire-surface-t1-nv

Picked for: surface type 1: 78.6% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 153
Census: sky 0.3%; near: vine 31.2%, grass_block 20.2%; mid: stone 10.4%; biomes: adventure:lavender_fields 60.4%, adventure:wisteria_grove 39.6%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-surface-t1-nv.png`

### the_violet_spire-underground-t3

Picked for: underground type 3: 20.0% of cells; notableId coal_ore; biome adventure:lavender_fields; height 185; neighbour step 3.1 (layer 0.8); relief over 16 blocks 95.5 (layer 25.6); notable-block density (log) 1.1 (layer 0.8)
Census: sky 0.3%; void 1.6%; near: stone 72.6%, amethyst_block 21.7%; mid: stone 1.8%; biomes: adventure:sky_highlands 100%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-underground-t3.png`

### the_violet_spire-underground-t3-nv

Picked for: underground type 3: 20.0% of cells; notableId coal_ore; biome adventure:lavender_fields; height 185; neighbour step 3.1 (layer 0.8); relief over 16 blocks 95.5 (layer 25.6); notable-block density (log) 1.1 (layer 0.8)
Census: sky 0.3%; void 1.6%; near: stone 72.6%, amethyst_block 21.7%; mid: stone 1.8%; biomes: adventure:sky_highlands 100%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-underground-t3-nv.png`

### the_violet_spire-underground-lm1

Picked for: underground landmark: biome adventure:umbrella_jungle (type none); cave pockets 95% (type 0%); cave air 14% (type 0%); notable-block density (log) 0.0 (type 0.9)
Census: sky 0%; near: stone 45.8%, grass_block 39.7%; mid: stone 3%, grass_block 2%; biomes: adventure:umbrella_jungle 100%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-underground-lm1.png`

### the_violet_spire-underground-lm1-nv

Picked for: underground landmark: biome adventure:umbrella_jungle (type none); cave pockets 95% (type 0%); cave air 14% (type 0%); notable-block density (log) 0.0 (type 0.9)
Census: sky 0%; near: stone 45.8%, grass_block 39.7%; mid: stone 3%, grass_block 2%; biomes: adventure:umbrella_jungle 100%
Image: `/tmp/claude-0/w10r/reviews/the_violet_spire/screens/the_violet_spire-underground-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
