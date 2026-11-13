# Review packet: the_sunken_temple

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_sunken_temple.json`

> Temple-hunting across drowned skyland basins. Monuments and fortresses sit where the water pooled between paradise islands — half-submerged architecture that predates the flooding. Something sacred was kept here, and the rising water couldn't wash it away.

Biomes: paradise_lost:tradewinds, paradise_lost:highlands, minecraft:warm_ocean, terralith:deep_warm_ocean, minecraft:jungle, terralith:skylands_summer, terralith:mirage_isles, minecraft:lukewarm_ocean, minecraft:beach

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_sunken_temple.json`

> Temple-hunting across drowned skyland basins. Monuments and fortresses sit where the water pooled between paradise islands — half-submerged architecture that predates the flooding. Something sacred was kept here, and the rising water couldn't wash it away.

Biomes: minecraft:lukewarm_ocean, minecraft:warm_ocean, minecraft:deep_lukewarm_ocean, minecraft:jungle, adventure:sky_highlands, minecraft:meadow, adventure:hanging_gardens

Terrain: `{"base": "basin_islands", "params": {"lakeShare": 0.75, "basinDepth": 16, "rimHeight": 6, "islandShare": 0.2, "lakeSize": 2, "size": 2, "coverage": 0.55, "tiers": 2, "tierSpacing": 48, "tierGap": 20, "tierShrink": 0.6}, "scales": {"climate": 3}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1q/final/the_sunken_temple/dossier.json`; layer maps in `/tmp/claude-0/w10c1q/final/the_sunken_temple/layers`

## Summary

- Top blocks seen from the sky: water 46.6%, oak_leaves 11.2%, jungle_leaves 10.3%, grass_block 8.6%.
- Surface biomes: warm_ocean 40.1%, jungle 36.2%, lukewarm_ocean 7.6%, deep_lukewarm_ocean 5.5%, adventure:hanging_gardens 3.1%.
- Ground blocks: sand 51%, grass_block 35.8%, dirt 4%, horn_coral_block 0.3%.
- Liquid cover 46.6% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 26%; bare rock 0%; no ground (void) 6.9%.
- Trees: 240.7 per ha (jungle_log 211.8/ha, mean spacing 3.5 blocks, oak_log 28.9/ha, mean spacing 8.6 blocks); ground plants grass 7.3%, other 1.8%, flower 0.1%, mushroom 0%.
- Surface height p5 132, median 138, p95 231, max 264; median relief per patch 18 blocks; slopes under 15° 70.1%, over 45° 6.6%, cliffs 1.1%; above the clouds 17%, below sea level 0%.
- Underground: 4.7% open, 1.5% liquid; cave biomes adventure:hanging_gardens 96.6%, jungle 3%, warm_ocean 0.4%.
- Notable clusters, fewest first: flowering_azalea 2 clusters, spacing n/a; pink_tulip 5 clusters, spacing 22.3; calcite 7 clusters, spacing 7.1; quartz_block 8 clusters, spacing 20.7; bubble_coral_fan 15 clusters, spacing 6; fire_coral_block 16 clusters, spacing 9.3.
- 7 of 7 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 973 columns: height within 2 blocks 98.3%, biome match 99.7%.
- Landforms over the border (noise render): plains 13.6%, hills 9.7%, mountains 3.1%, plateaus 2.4%, basins 5.1%, islands 15.2%, water 39.3%, void 11.6%.
- Placeness: biome regions 5.7/km² (16 major, largest 17.6%), landform regions 14.2/km² (largest 14.8%), dominant place 17.6% of the border.

## Layers

- Columns by class: void 6.9%, roofed 0%, stacked 32.2%, overhang 3.1%, cave 4%, single 53.9%.
- Columns with each layer: sky 32.2%, surface 93.1%, underground 90.6%, roof 0%.
- Overhangs in 4.2% of columns, stacked layers in 32.2%, cave pockets in 4.2%.

## Places

- **surface** (245 cells, 2 types, silhouette 0.574):
  - type 1: 60.0% of cells; biome warm_ocean; ground sand; top water; height 136; trees per cell 0.1 (layer 3.5); canopy 2% (layer 28%); ground plants 0% (layer 10%); liquid cover 82% (layer 50%) — camera [3256.5,150,1256.5]
  - type 2: 40.0% of cells; biome jungle; ground grass_block; top oak_leaves; height 185; relief over the patch 39.4 (layer 22.7); trees per cell 8.7 (layer 3.5); canopy 67% (layer 28%); ground plants 24% (layer 10%); liquid cover 2% (layer 50%); open to the sky 13% (layer 38%) — camera [-2983.5,156,-727.5]
  - landmark at [-3544,936]: top grass_block (type oak_leaves); neighbour step 1.5 (type 0.0); trees per cell 0.0 (type 12.0); canopy 4% (type 89%)
  - landmark at [-2168,2248]: top grass_block (type oak_leaves); neighbour step 2.3 (type 0.0); trees per cell 0.0 (type 12.0); canopy 4% (type 89%)
  - landmark at [472,600]: biome adventure:sky_highlands (type jungle); top grass_block (type oak_leaves); overhangs 50% (type 0%); cave pockets 47% (type 0%); canopy 6% (type 89%)
  - landmark at [-2168,2264]: top grass_block (type oak_leaves); neighbour step 2.4 (type 0.0); canopy 0% (type 89%); trees per cell 0.0 (type 12.0)
  - landmark at [2120,-2776]: top grass_block (type oak_leaves); neighbour step 4.1 (type 0.0); relief over 16 blocks 101.0 (type 3.0); relief over the patch 117.0 (type 17.0)
- **sky** (98 cells, 2 types, silhouette 0.394):
  - type 1: 72.4% of cells; biome jungle; ground grass_block; height 205 — camera [-2963.5,196,-3369.5]
  - type 2: 27.6% of cells; biome adventure:sky_highlands; ground grass_block; height 213; trees per cell 0.3 (layer 7.2); notable-block density (log) 0.5 (layer 0.8) — camera [-1126.5,233,552.5]
  - landmark at [-2184,2264]: relief over 16 blocks 80.0 (type 12.0); neighbour step 1.9 (type 0.2); island layers above 1.3 (type 1.0)
  - landmark at [-3544,936]: relief over 16 blocks 78.0 (type 12.0); island layers above 1.2 (type 1.0); neighbour step 0.8 (type 0.2)
  - landmark at [-2968,-728]: layer present in 29% (type 100%); neighbour step 1.0 (type 0.2); notable-block density (log) 0.5 (type 0.9)
- **underground** (241 cells, 2 types, silhouette 0.465):
  - type 1: 79.3% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 140 — camera [-1596.5,148.88,737.5]
  - type 2: 20.7% of cells; notableId cave_vines_plant; biome jungle; height 214; relief over 16 blocks 15.4 (layer 6.6); notable-block density (log) 0.8 (layer 1.0); cave air 6% (layer 1%); cave pockets 19% (layer 5%) — camera [1737.5,195.88,2723.5]
  - landmark at [2120,-2776]: notableId rooted_dirt (type cave_vines_plant); neighbour step 6.5 (type 0.4); relief over 16 blocks 94.0 (type 10.0); layer present in 59% (type 100%)
  - landmark at [2536,2664]: notableId rooted_dirt (type cave_vines_plant); biome jungle (type none); cave pockets 100% (type 0%); cave air 24% (type 0%); notable-block density (log) 1.8 (type 0.7)
  - landmark at [168,2904]: notableId rooted_dirt (type cave_vines_plant); relief over 16 blocks 54.0 (type 10.0); layer present in 68% (type 100%); notable-block density (log) 1.2 (type 0.7)
  - landmark at [2552,2664]: biome jungle (type none); cave air 27% (type 0%); cave pockets 47% (type 0%); notable-block density (log) 1.3 (type 0.7)
  - landmark at [2520,2680]: biome jungle (type none); cave air 26% (type 0%); cave pockets 60% (type 0%); notable-block density (log) 1.6 (type 0.7)
- **roof**: no cells.

## Biome expression

7 of 7 placed biomes are expressed.

- minecraft:jungle: expressed, 38935 columns; placement land 60.7%, water 1.5%, sky 37.9%; snow 0%, canopy 70.2%, trees 674.1/ha; top oak_leaves 18.2%, jungle_leaves 16.9%, grass_block 11.2%
- minecraft:warm_ocean: expressed, 26927 columns; placement land 13.9%, water 85.7%, sky 0.4%; snow 0%, canopy 15.2%, trees 13.4/ha; top water 85.6%, hanging_roots 6.8%, sand 2.6%
- adventure:hanging_gardens: expressed, 6865 columns; placement land 20.9%, water 9.5%, void 66.7%, sky 2.9%; snow 0%, canopy 15.7%, trees 13.9/ha; top grass_block 14.1%, water 9.5%, oak_leaves 1.5%
- minecraft:lukewarm_ocean: expressed, 5088 columns; placement land 13.8%, water 86.2%; snow 0%, canopy 0.6%, trees 0/ha; top water 86.2%, hanging_roots 11.2%, cave_vines_plant 1.7%
- adventure:sky_highlands: expressed, 4235 columns; placement land 9.1%, sky 90.9%; snow 0%, canopy 5.4%, trees 233.2/ha; top grass_block 6.8%, short_grass 1.4%, oak_leaves 0.5%
- minecraft:deep_lukewarm_ocean: expressed, 3680 columns; placement land 33%, water 67%; snow 0%, canopy 0%, trees 0/ha; top water 67%, hanging_roots 27.2%, cave_vines_plant 5.7%
- minecraft:meadow: expressed, 2620 columns; placement sky 100%; snow 0%, canopy 0%, trees 0/ha; top none


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | paradise_lost:tradewinds | minecraft:meadow | 0.0 | 2620 | expressed | - |
| O2 | paradise_lost:highlands | adventure:sky_highlands | 0.6 | 4235 | expressed | - |
| O3 | minecraft:warm_ocean | minecraft:warm_ocean | 40.1 | 26927 | expressed | - |
| O4 | terralith:deep_warm_ocean | minecraft:deep_lukewarm_ocean | 5.5 | 3680 | expressed | - |
| O5 | minecraft:jungle | minecraft:jungle | 36.2 | 38935 | expressed | - |
| O6 | terralith:skylands_summer | adventure:hanging_gardens | 3.1 | 6865 | expressed | - |
| O7 | terralith:mirage_isles | adventure:desert_oasis | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | minecraft:lukewarm_ocean | minecraft:lukewarm_ocean | 7.6 | 5088 | expressed | - |
| O9 | minecraft:beach | minecraft:beach | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:warm_ocean 40.1%, minecraft:jungle 36.2%, minecraft:lukewarm_ocean 7.6% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 46.6% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 6.9% (`surface.voidPercent`) | |
| M:canopy | 26.0% (`surface.canopyPercent`) | |
| M:relief | median 18.0 blocks a patch; height {"p5": 132.0, "p25": 133.0, "p50": 138.0, "p75": 159.0, "p95": 231.0, "max": 264.0} (`terrain`) | |
| M:layers | {"sky": 32.2, "surface": 93.1, "underground": 90.6, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 6.9, "roofed": 0.0, "stacked": 32.2, "overhang": 3.1, "cave": 4.0, "single": 53.9} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 77.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (32.2% of columns)

## Shots

### the_sunken_temple-far

Picked for: far: the border ring at bearing 96, facing the centre; 0.0% of rays could reach the border
Census: sky 33.1%; far: water 33.8%, jungle_leaves 7.6%; biomes: warm_ocean 51.8%, jungle 37.9%, adventure:hanging_gardens 10.4%
Image: `/tmp/claude-0/w10c1p/reviews/the_sunken_temple/screens/the_sunken_temple-far.png`

### the_sunken_temple-surface-t1

Picked for: surface type 1: 60.0% of cells; biome warm_ocean; ground sand; top water; height 136; trees per cell 0.1 (layer 3.5); canopy 2% (layer 28%); ground plants 0% (layer 10%); liquid cover 82% (layer 50%)
Census: sky 3%; unloaded 11.3%; near: water 51.5%; mid: cave_vines_plant 8.4%, water 3.9%; far: vine 1.9%, jungle_leaves 1.8%; biomes: warm_ocean 71.3%, adventure:hanging_gardens 14.8%, jungle 13.9%
Image: `/tmp/claude-0/w10c1p/reviews/the_sunken_temple/screens/the_sunken_temple-surface-t1.png`

### the_sunken_temple-sky-t2

Picked for: sky type 2: 27.6% of cells; biome adventure:sky_highlands; ground grass_block; height 213; trees per cell 0.3 (layer 7.2); notable-block density (log) 0.5 (layer 0.8)
Census: sky 27.8%; unloaded 4.3%; near: grass_block 34.2%, short_grass 14.3%; mid: grass_block 5.2%, short_grass 3%; far: grass_block 1.6%; biomes: adventure:sky_highlands 92.1%, meadow 5.7%
Image: `/tmp/claude-0/w10c1p/reviews/the_sunken_temple/screens/the_sunken_temple-sky-t2.png`

### the_sunken_temple-underground-t2

Picked for: underground type 2: 20.7% of cells; notableId cave_vines_plant; biome jungle; height 214; relief over 16 blocks 15.4 (layer 6.6); notable-block density (log) 0.8 (layer 1.0); cave air 6% (layer 1%); cave pockets 19% (layer 5%)
Census: sky 0%; near: grass_block 59.6%, stone 24.1%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_sunken_temple/screens/the_sunken_temple-underground-t2.png`

### the_sunken_temple-surface-lm1

Picked for: surface landmark: top grass_block (type oak_leaves); neighbour step 1.5 (type 0.0); trees per cell 0.0 (type 12.0); canopy 4% (type 89%)
Census: sky 6.7%; unloaded 8.1%; near: grass_block 41.5%, vine 5.4%; mid: water 6.6%, vine 4%; far: vine 1.3%, jungle_leaves 1.3%; biomes: jungle 85.6%, warm_ocean 11%
Image: `/tmp/claude-0/w10c1p/reviews/the_sunken_temple/screens/the_sunken_temple-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
