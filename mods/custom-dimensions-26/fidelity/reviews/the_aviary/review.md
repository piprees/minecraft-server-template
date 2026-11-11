# Review packet: the_aviary

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/paradise_lost.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_aviary.json`

> The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew.

Biomes: adventure:sky_highlands, adventure:wisteria_grove, adventure:golden_skygrove, minecraft:cherry_grove, minecraft:meadow, minecraft:flower_forest, minecraft:plains, minecraft:forest, minecraft:birch_forest, adventure:cloud_reef, adventure:windswept_isles, minecraft:snowy_slopes, minecraft:dark_forest, adventure:hanging_gardens

Terrain: `{"base": "floating_islands", "setting": "extreme", "params": {"size": 3, "coverage": 0.45, "thickness": 16, "underside": 80, "spread": 20, "flatness": 0.6, "tiers": 2, "tierSpacing": 64, "tierGap": 20, "tierShrink": 0.6}, "scales": {"climate": 4}, "climateWarp": {"blocks": 160, "wavelength": 512}}`; generator `{"type": "noise", "preset": "floating_islands"}`

Zones: none

## Survey

Dossier: `runs/fin/full/the_aviary/dossier.json`; layer maps in `runs/fin/full/the_aviary/layers`

## Summary

- Top blocks seen from the sky: grass_block 32%, oak_leaves 8.1%, snow_block 6.1%, cherry_leaves 4.9%.
- Surface biomes: adventure:wisteria_grove 20.5%, cherry_grove 15%, adventure:golden_skygrove 7.7%, meadow 7.4%, adventure:sky_highlands 7.2%.
- Ground blocks: grass_block 62.5%, snow_block 7.2%, moss_block 2%, podzol 1.4%.
- Liquid cover 0% water, 0% lava, 0% other; snow or ice 8.4% (a snowing climate over 3.7%); tree canopy 19.4%; bare rock 0.1%; no ground (void) 23.3%.
- Trees: 60.8 per ha (oak_log 39.4/ha, mean spacing 7.1 blocks, cherry_log 9.1/ha, mean spacing 8.8 blocks, dark_oak_log 8.2/ha, mean spacing 6.1 blocks, birch_log 2.7/ha, mean spacing 8.8 blocks); ground plants flower 7.7%, other 6.1%, grass 4.9%.
- Surface height p5 134, median 181, p95 237, max 253; median relief per patch 19 blocks; slopes under 15° 36.9%, over 45° 6.9%, cliffs 0.4%; above the clouds 41.9%, below sea level 0%.
- Underground: 8.3% open, 0% liquid; cave biomes adventure:hanging_gardens 96%, meadow 0.7%, cherry_grove 0.6%, flower_forest 0.5%.
- Notable clusters, fewest first: end_rod 4 clusters, spacing 29.4; powder_snow 4 clusters, spacing 13.9; mushroom_stem 4 clusters, spacing 27.2; glow_lichen 4 clusters, spacing 7; bee_nest 4 clusters, spacing n/a; pink_tulip 8 clusters, spacing 4.7.
- 11 of 12 placed biomes are expressed; 2 listed biomes were not found.
- Render against world at 804 columns: height within 2 blocks 97.9%, biome match 100%.
- Landforms over the border (noise render): plains 20.3%, hills 20.1%, mountains 4.5%, plateaus 3.4%, basins 5.4%, islands 5.2%, water 0%, void 41.1%.
- Placeness: biome regions 3.0/km² (12 major, largest 7.9%), landform regions 14.2/km² (largest 6.7%), dominant place 7.9% of the border.

## Layers

- Columns by class: void 23.3%, roofed 0%, stacked 14%, overhang 2.7%, cave 13.9%, single 46.2%.
- Columns with each layer: sky 14%, surface 76.7%, underground 73.7%, roof 0%.
- Overhangs in 2.8% of columns, stacked layers in 14%, cave pockets in 15.7%.

## Places

- **surface** (207 cells, 8 types, silhouette 0.38):
  - type 1: 19.3% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 183; notable-block density (log) 0.9 (layer 0.5) — camera [-3607.5,164,-1303.5]
  - type 2: 17.9% of cells; biome meadow; ground grass_block; top grass_block; height 193; trees per cell 0.4 (layer 1.7); notable-block density (log) 0.2 (layer 0.5); canopy 7% (layer 25%); open to the sky 92% (layer 58%) — camera [1576.5,223,1944.5]
  - type 3: 16.9% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 156; relief over the patch 35.7 (layer 26.8); trees per cell 0.1 (layer 1.7); canopy 2% (layer 25%); ground plants 2% (layer 24%); open to the sky 7% (layer 58%); stacked layers 92% (layer 18%) — camera [-1623.5,140,1096.5]
  - type 4: 14.0% of cells; biome cherry_grove; ground grass_block; top cherry_leaves; height 195; canopy 40% (layer 25%); ground plants 57% (layer 24%) — camera [-695.5,177,664.5]
  - type 5: 12.1% of cells; biome adventure:cloud_reef; ground snow_block; top snow_block; height 206; trees per cell 0.1 (layer 1.7); canopy 2% (layer 25%); ground plants 3% (layer 24%); snow or ice 90% (layer 11%); open to the sky 98% (layer 58%) — camera [3768.5,227,-3847.5]
  - type 6: 7.2% of cells; biome dark_forest; ground grass_block; top dark_oak_leaves; height 197; trees per cell 5.8 (layer 1.7); canopy 78% (layer 25%); ground plants 50% (layer 24%); open to the sky 22% (layer 58%) — camera [2119.5,177,-2581.5]
  - type 7: 7.2% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 232; relief over the patch 15.4 (layer 26.8); trees per cell 4.3 (layer 1.7); canopy 63% (layer 25%); open to the sky 37% (layer 58%); cave pockets 80% (layer 20%) — camera [248.5,229,1208.5]
  - type 8: 5.3% of cells; biome adventure:golden_skygrove; ground grass_block; top oak_leaves; height 178; neighbour step 0.7 (layer 0.3); relief over 16 blocks 20.9 (layer 9.2); relief over the patch 71.3 (layer 26.8); trees per cell 3.7 (layer 1.7); canopy 57% (layer 25%); bare rock 2% (layer 0%); cave pockets 3% (layer 20%) — camera [1093.5,170,-1654.5]
  - landmark at [-2760,3640]: biome adventure:sky_highlands (type meadow); neighbour step 3.1 (type 0.4); relief over 16 blocks 63.0 (type 7.0); overhangs 36% (type 0%)
  - landmark at [3128,-952]: biome forest (type adventure:golden_skygrove); top grass_block (type oak_leaves); relief over 16 blocks 75.0 (type 8.0); neighbour step 1.6 (type 0.4); height 231.0 (type 170.2)
  - landmark at [3128,-984]: biome forest (type adventure:wisteria_grove); relief over the patch 77.0 (type 21.0); height 247.4 (type 162.2); neighbour step 0.9 (type 0.3)
  - landmark at [-824,296]: biome cherry_grove (type meadow); overhangs 57% (type 0%); neighbour step 1.4 (type 0.4); relief over 16 blocks 30.0 (type 7.0)
  - landmark at [-808,328]: biome cherry_grove (type meadow); neighbour step 2.0 (type 0.4); overhangs 20% (type 0%); relief over 16 blocks 30.0 (type 7.0)
- **sky** (43 cells, 3 types, silhouette 0.447):
  - type 1: 48.8% of cells; biome adventure:wisteria_grove; ground grass_block; height 216; relief over 16 blocks 11.8 (layer 9.5); trees per cell 2.1 (layer 1.3); notable-block density (log) 1.2 (layer 0.8) — camera [-1767.5,236,1160.5]
  - type 2: 30.2% of cells; biome adventure:sky_highlands; ground grass_block; height 204; trees per cell 0.2 (layer 1.3); notable-block density (log) 0.5 (layer 0.8) — camera [-2775.5,201,3671.5]
  - type 3: 20.9% of cells; biome cherry_grove; ground grass_block; height 247; layer present in 60% (layer 83%); relief over 16 blocks 7.0 (layer 9.5); notable-block density (log) 0.5 (layer 0.8) — camera [-309.5,247,-1389.5]
  - landmark at [-1656,1080]: height 178.8 (type 233.4); layer present in 52% (type 100%); neighbour step 0.7 (type 0.2)
  - landmark at [-296,-1416]: layer present in 100% (type 35%); trees per cell 2.0 (type 0.0); neighbour step 0.1 (type 0.5)
- **underground** (202 cells, 3 types, silhouette 0.337):
  - type 1: 48.0% of cells; notableId cave_vines_plant; height 182; cave air 0% (layer 5%); cave pockets 0% (layer 20%) — camera [1661.5,160.88,556.5]
  - type 2: 34.2% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 185 — camera [-721.5,146.88,3573.5]
  - type 3: 17.8% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 216; notable-block density (log) 1.5 (layer 1.1); cave air 21% (layer 5%); cave pockets 88% (layer 20%) — camera [232.5,174.88,1245.5]
  - landmark at [-824,296]: notableId moss_block (type cave_vines_plant); neighbour step 1.4 (type 0.4); relief over 16 blocks 30.0 (type 6.0); cave air 25% (type 2%)
  - landmark at [-808,328]: neighbour step 2.1 (type 0.4); relief over 16 blocks 26.0 (type 6.0); layer present in 69% (type 100%)
  - landmark at [-2760,3640]: neighbour step 2.8 (type 0.4); relief over 16 blocks 63.0 (type 6.0); cave air 21% (type 2%)
  - landmark at [-1160,1816]: biome adventure:wisteria_grove (type adventure:hanging_gardens); cave air 45% (type 16%); relief over 16 blocks 16.0 (type 4.0); neighbour step 0.5 (type 0.1)
  - landmark at [-104,2296]: notableId rooted_dirt (type cave_vines_plant); biome cherry_grove (type adventure:hanging_gardens); relief over 16 blocks 17.0 (type 4.0); neighbour step 0.6 (type 0.1); notable-block density (log) 1.5 (type 1.6)
- **roof**: no cells.

## Biome expression

11 of 12 placed biomes are expressed.

- adventure:wisteria_grove: expressed, 19159 columns; placement land 71.3%, void 3.7%, sky 25%; snow 0%, canopy 15.9%, trees 92.2/ha; top grass_block 38.6%, vine 10.7%, oak_leaves 6.9%
- adventure:hanging_gardens: expressed, 12940 columns; placement land 3.7%, void 96.3%; snow 0%, canopy 6.3%, trees 0/ha; top grass_block 3.2%, oak_leaves 0.1%, cave_vines_plant 0.1%
- minecraft:cherry_grove: expressed, 11639 columns; placement land 86%, sky 14%; snow 0%, canopy 32.4%, trees 61/ha; top grass_block 28%, cherry_leaves 26.9%, pink_petals 19.9%
- adventure:sky_highlands: expressed, 7608 columns; placement land 63.1%, sky 36.8%; snow 0%, canopy 2%, trees 14.6/ha; top grass_block 45.8%, hanging_roots 6.1%, short_grass 4.7%
- minecraft:meadow: placed, not expressed, 5272 columns; placement land 93.5%, void 5.7%, sky 0.8%; snow 0%, canopy 0.8%, trees 0/ha; top grass_block 69.8%, short_grass 9.7%, wildflowers 4.9%; **features name trees, none on 0.5 ha of land**
- adventure:golden_skygrove: expressed, 5135 columns; placement land 100%; snow 0.1%, canopy 63.9%, trees 157.8/ha; top oak_leaves 51.6%, grass_block 26.7%, yellow_poplar_leaves 10%
- minecraft:flower_forest: expressed, 3719 columns; placement land 44.1%, void 55.9%; snow 0%, canopy 42%, trees 158.6/ha; top grass_block 23.1%, oak_leaves 17%, birch_leaves 1.5%
- minecraft:dark_forest: expressed, 3712 columns; placement land 100%; snow 0.4%, canopy 77.9%, trees 234.4/ha; top dark_oak_leaves 63.7%, grass_block 12.9%, oak_leaves 11.3%
- adventure:cloud_reef: expressed, 3570 columns; placement land 100%; snow 88.7%, canopy 0.1%, trees 0/ha; top snow_block 79.2%, snow 5.6%, calcite 1.9%
- minecraft:snowy_slopes: expressed, 2447 columns; placement land 100%; snow 97.8%, canopy 4.8%, trees 4.1/ha; top snow_block 50.8%, powder_snow 43.3%, dark_oak_leaves 3.7%
- minecraft:forest: expressed, 815 columns; placement land 93.3%, sky 6.7%; snow 0%, canopy 43.4%, trees 197.4/ha; top grass_block 42%, oak_leaves 28.2%, birch_leaves 12.3%
- minecraft:birch_forest: expressed, 121 columns; placement land 100%; snow 5%, canopy 33.9%, trees 165.3/ha; top grass_block 52.1%, birch_leaves 33.9%, wildflowers 4.1%

Listed in the definition, found in no sampled column: minecraft:plains, adventure:windswept_isles.


v2 biomes not found in any sampled column: minecraft:plains, adventure:windswept_isles

## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | adventure:wisteria_grove | 20.5 | 19159 | expressed | - |
| V2 | minecraft:cherry_grove | 15.0 | 11639 | expressed | - |
| V3 | adventure:golden_skygrove | 7.7 | 5135 | expressed | - |
| V4 | minecraft:meadow | 7.4 | 5272 | placed, not expressed | features name trees, none on 0.5 ha of land |
| V5 | adventure:sky_highlands | 7.2 | 7608 | expressed | - |
| V6 | minecraft:dark_forest | 5.6 | 3712 | expressed | - |
| V7 | adventure:cloud_reef | 5.3 | 3570 | expressed | - |
| V8 | minecraft:snowy_slopes | 3.7 | 2447 | expressed | - |
| V9 | minecraft:flower_forest | 2.5 | 3719 | expressed | - |
| V10 | minecraft:forest | 1.1 | 815 | expressed | - |
| V11 | adventure:hanging_gardens | 0.7 | 12940 | expressed | - |
| V12 | minecraft:birch_forest | 0.2 | 121 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:wisteria_grove 20.5%, minecraft:cherry_grove 15.0%, adventure:golden_skygrove 7.7% | type `the_aviary` |
| M:snow | 8.4% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 23.3% (`surface.voidPercent`) | |
| M:canopy | 19.4% (`surface.canopyPercent`) | |
| M:relief | median 19.0 blocks a patch; height {"p5": 134.0, "p25": 164.0, "p50": 181.0, "p75": 221.0, "p95": 237.0, "max": 253.0} (`terrain`) | |
| M:layers | {"sky": 14.0, "surface": 76.7, "underground": 73.7, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 23.3, "roofed": 0.0, "stacked": 14.0, "overhang": 2.7, "cave": 13.9, "single": 46.2} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 1
- S:d — layers the 1.21.1 type implies: surface present (76.7% of columns)

## Shots

### the_aviary-far

Picked for: far: the border ring at bearing 92, facing the centre; 0.0% of rays could reach the border
Census: sky 48.4%; void 32.6%; mid: grass_block 1.6%, oak_leaves 1.2%; far: cave_vines_plant 4.1%, oak_leaves 3.1%; biomes: adventure:golden_skygrove 49%, adventure:hanging_gardens 42.4%, adventure:wisteria_grove 8.7%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-far.png`

### the_aviary-surface-t5

Picked for: surface type 5: 12.1% of cells; biome adventure:cloud_reef; ground snow_block; top snow_block; height 206; trees per cell 0.1 (layer 1.7); canopy 2% (layer 25%); ground plants 3% (layer 24%); snow or ice 90% (layer 11%); open to the sky 98% (layer 58%)
Census: sky 18.6%; unloaded 0.4%; near: snow_block 54%, snow 4%; biomes: adventure:cloud_reef 96.4%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-surface-t5.png`

### the_aviary-sky-t1

Picked for: sky type 1: 48.8% of cells; biome adventure:wisteria_grove; ground grass_block; height 216; relief over 16 blocks 11.8 (layer 9.5); trees per cell 2.1 (layer 1.3); notable-block density (log) 1.2 (layer 0.8)
Census: sky 17.2%; unloaded 11.6%; near: lilac 27.1%, grass_block 18.6%; mid: vine 2.9%, oak_leaves 2.1%; far: vine 1.6%; biomes: adventure:wisteria_grove 99.8%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-sky-t1.png`

### the_aviary-underground-t1

Picked for: underground type 1: 48.0% of cells; notableId cave_vines_plant; height 182; cave air 0% (layer 5%); cave pockets 0% (layer 20%)
Census: sky 0.4%; void 0.3%; unloaded 7.9%; near: cave_vines_plant 29.6%, stone 18.2%; mid: snow_block 3.3%, cave_vines_plant 1.7%; far: cave_vines_plant 4%; biomes: snowy_slopes 53.4%, adventure:hanging_gardens 44.7%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-underground-t1.png`

### the_aviary-surface-t3

Picked for: surface type 3: 16.9% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 156; relief over the patch 35.7 (layer 26.8); trees per cell 0.1 (layer 1.7); canopy 2% (layer 25%); ground plants 2% (layer 24%); open to the sky 7% (layer 58%); stacked layers 92% (layer 18%)
Census: sky 0%; unloaded 2.8%; near: grass_block 29.3%, cave_vines_plant 22%; mid: grass_block 6.8%, cave_vines_plant 5.3%; biomes: adventure:wisteria_grove 66.9%, adventure:hanging_gardens 33.1%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-surface-t3.png`

### the_aviary-surface-t2

Picked for: surface type 2: 17.9% of cells; biome meadow; ground grass_block; top grass_block; height 193; trees per cell 0.4 (layer 1.7); notable-block density (log) 0.2 (layer 0.5); canopy 7% (layer 25%); open to the sky 92% (layer 58%)
Census: sky 24.7%; unloaded 7.5%; near: grass_block 44.7%, tall_grass 6.9%; biomes: meadow 99.9%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-surface-t2.png`

### the_aviary-surface-t1

Picked for: surface type 1: 19.3% of cells; biome adventure:wisteria_grove; ground grass_block; top grass_block; height 183; notable-block density (log) 0.9 (layer 0.5)
Census: sky 2.3%; unloaded 0.2%; near: vine 32.5%, grass_block 27.1%; mid: cave_vines_plant 2.1%, stone 1.2%; biomes: adventure:wisteria_grove 94.2%, adventure:hanging_gardens 5.8%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-surface-t1.png`

### the_aviary-surface-lm1

Picked for: surface landmark: biome adventure:sky_highlands (type meadow); neighbour step 3.1 (type 0.4); relief over 16 blocks 63.0 (type 7.0); overhangs 36% (type 0%)
Census: sky 3.3%; unloaded 17.1%; near: grass_block 25.1%, short_grass 13.9%; mid: grass_block 3.1%, oak_leaves 2.7%; far: cave_vines_plant 5.3%, stone 3.5%; biomes: adventure:sky_highlands 72.4%, adventure:hanging_gardens 27.5%
Image: `/tmp/claude-0/w10c1r/reviews/the_aviary/screens/the_aviary-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
