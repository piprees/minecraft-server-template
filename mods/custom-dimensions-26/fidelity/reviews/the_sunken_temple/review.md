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

Dossier: `/tmp/claude-0/w10c1/runs/sel/the_sunken_temple/dossier.json`; layer maps in `/tmp/claude-0/w10c1/runs/sel/the_sunken_temple/layers`

## Summary

- Top blocks seen from the sky: water 44.4%, grass_block 16%, oak_leaves 6.3%, jungle_leaves 5.9%.
- Surface biomes: lukewarm_ocean 22%, jungle 20.6%, warm_ocean 18.1%, adventure:sky_highlands 15.6%, deep_lukewarm_ocean 9.3%.
- Ground blocks: sand 45.7%, grass_block 35.1%, dirt 4.6%, calcite 0.5%.
- Liquid cover 44.6% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 14.7%; bare rock 0%; no ground (void) 12.9%.
- Trees: 110.2 per ha (jungle_log 92.5/ha, mean spacing 3.5 blocks, oak_log 17.7/ha, mean spacing 10.5 blocks); ground plants grass 6.5%, other 0.9%, flower 0.8%, mushroom 0%.
- Surface height p5 133, median 144, p95 233, max 246; median relief per patch 19 blocks; slopes under 15° 68.7%, over 45° 5.4%, cliffs 1.1%; above the clouds 18.2%, below sea level 0%.
- Underground: 13.6% open, 2.7% liquid; cave biomes adventure:hanging_gardens 96.8%, adventure:sky_highlands 1.5%, jungle 1%, warm_ocean 0.4%.
- Notable clusters, fewest first: pink_tulip 2 clusters, spacing n/a; brain_coral_block 6 clusters, spacing 13.5; brown_mushroom 7 clusters, spacing 3; emerald_ore 7 clusters, spacing 10.2; clay 9 clusters, spacing 9.3; melon 9 clusters, spacing 4.8.
- 5 of 7 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 909 columns: height within 2 blocks 99.2%, biome match 99.9%.
- Landforms over the border (noise render): plains 13.6%, hills 9.7%, mountains 3.1%, plateaus 2.4%, basins 5.1%, islands 15.2%, water 39.3%, void 11.6%.
- Placeness: biome regions 6.3/km² (14 major, largest 17.9%), landform regions 14.2/km² (largest 14.8%), dominant place 17.9% of the border.

## Layers

- Columns by class: void 12.9%, roofed 0%, stacked 19%, overhang 2.9%, cave 7.7%, single 57.6%.
- Columns with each layer: sky 19%, surface 87.1%, underground 84.7%, roof 0%.
- Overhangs in 3.8% of columns, stacked layers in 19%, cave pockets in 8.6%.

## Places

- **surface** (231 cells, 3 types, silhouette 0.54):
  - type 1: 57.1% of cells; biome lukewarm_ocean; ground sand; top water; height 136; ground plants 0% (layer 10%); liquid cover 87% (layer 50%) — camera [728.5,150,-2039.5]
  - type 2: 23.4% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 195; layer present in 92% (layer 98%); neighbour step 0.6 (layer 0.3); relief over 16 blocks 14.9 (layer 7.0); relief over the patch 36.4 (layer 20.4); notable-block density (log) 0.3 (layer 0.5); ground plants 18% (layer 10%); liquid cover 3% (layer 50%) — camera [-2888.5,196,-2712.5]
  - type 3: 19.5% of cells; biome jungle; ground grass_block; top jungle_leaves; height 184; trees per cell 9.9 (layer 2.1); notable-block density (log) 0.8 (layer 0.5); canopy 76% (layer 17%); ground plants 27% (layer 10%); liquid cover 2% (layer 50%); open to the sky 12% (layer 62%); cave pockets 26% (layer 10%) — camera [1400.5,170,-265.5]
  - landmark at [296,-2952]: biome jungle (type adventure:sky_highlands); neighbour step 1.7 (type 0.3); stacked layers 100% (type 0%); open to the sky 0% (type 100%)
  - landmark at [504,696]: biome deep_lukewarm_ocean (type lukewarm_ocean); neighbour step 1.8 (type 0.0); overhangs 29% (type 0%); relief over 16 blocks 27.0 (type 0.0)
  - landmark at [296,-2968]: biome jungle (type adventure:sky_highlands); neighbour step 1.7 (type 0.3); stacked layers 100% (type 0%); open to the sky 0% (type 100%)
  - landmark at [3688,-2440]: top water (type jungle_leaves); overhangs 43% (type 0%); relief over 16 blocks 25.0 (type 5.0); notable-block density (log) 0.4 (type 1.0)
  - landmark at [-1096,536]: biome warm_ocean (type lukewarm_ocean); top hanging_roots (type water); notable-block density (log) 1.8 (type 0.5); stacked layers 100% (type 0%); open to the sky 0% (type 100%)
- **sky** (57 cells, 2 types, silhouette 0.474):
  - type 1: 59.6% of cells; biome adventure:sky_highlands; ground grass_block; height 229; trees per cell 0.3 (layer 4.1) — camera [-1911.5,235,-3766.5]
  - type 2: 40.4% of cells; biome jungle; ground grass_block; height 206; trees per cell 9.7 (layer 4.1); notable-block density (log) 0.9 (layer 0.7) — camera [294.5,219,-2969.5]
  - landmark at [-1544,-3240]: layer present in 34% (type 100%); relief over 16 blocks 2.0 (type 12.0); height 184.9 (type 216.3)
  - landmark at [312,-2936]: layer present in 32% (type 100%); notable-block density (log) 0.2 (type 1.0); relief over 16 blocks 3.0 (type 12.0)
- **underground** (227 cells, 2 types, silhouette 0.621):
  - type 1: 91.6% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 152
  - type 2: 8.4% of cells; notableId cave_vines_plant; biome jungle; height 228; notable-block density (log) 1.5 (layer 1.1); cave air 39% (layer 3%); cave pockets 91% (layer 10%) — camera [2537.5,180.88,2643.5]
  - landmark at [-3944,-552]: notableId sandstone (type cave_vines_plant); biome adventure:hanging_gardens (type jungle); notable-block density (log) 2.8 (type 1.4); cave air 56% (type 23%); cave liquid 5% (type 0%)
  - landmark at [168,2904]: notableId rooted_dirt (type cave_vines_plant); relief over 16 blocks 54.0 (type 2.0); layer present in 68% (type 100%); neighbour step 1.1 (type 0.1)
  - landmark at [504,696]: notableId rooted_dirt (type cave_vines_plant); cave liquid 19% (type 0%); neighbour step 1.8 (type 0.1); relief over 16 blocks 27.0 (type 2.0)
  - landmark at [56,3832]: notableId rooted_dirt (type cave_vines_plant); cave liquid 18% (type 0%); relief over 16 blocks 26.0 (type 2.0); neighbour step 1.0 (type 0.1)
  - landmark at [520,680]: notableId rooted_dirt (type cave_vines_plant); cave liquid 21% (type 0%); relief over 16 blocks 27.0 (type 2.0); cave pockets 39% (type 0%)
- **roof**: no cells.

## Biome expression

5 of 7 placed biomes are expressed.

- minecraft:jungle: expressed, 18558 columns; placement land 69.7%, water 4.6%, sky 25.7%; snow 0%, canopy 69.6%, trees 543.3/ha; top jungle_leaves 20.6%, oak_leaves 19.3%, grass_block 13.3%
- adventure:sky_highlands: expressed, 16344 columns; placement land 63.9%, void 0.6%, sky 35.5%; snow 0%, canopy 4.4%, trees 28.7/ha; top grass_block 44.2%, short_grass 10.3%, oak_leaves 2.8%
- minecraft:lukewarm_ocean: expressed, 14783 columns; placement land 5.5%, water 94%, sky 0.5%; snow 0%, canopy 33.4%, trees 24.4/ha; top water 94%, hanging_roots 2.3%, oak_leaves 0.9%
- minecraft:warm_ocean: placed, not expressed, 12402 columns; placement land 21.4%, water 76.2%, void 2.2%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top water 75.5%, hanging_roots 13%, sand 4.9%; **features name trees, none on 0.3 ha of land**
- adventure:hanging_gardens: placed, not expressed, 8782 columns; placement land 0.5%, water 3.5%, void 93.8%, sky 2.2%; snow 0%, canopy 33.3%, trees 0/ha; top water 3.5%, grass_block 0.3%, vine 0.1%; **mostly water**
- minecraft:deep_lukewarm_ocean: expressed, 6200 columns; placement land 14.6%, water 85.3%, sky 0.1%; snow 0%, canopy 7.2%, trees 11.1/ha; top water 84.1%, hanging_roots 6%, grass_block 5.1%
- minecraft:meadow: expressed, 2428 columns; placement land 25.4%, sky 74.6%; snow 0%, canopy 0%, trees 0/ha; top grass_block 20.7%, short_grass 2.1%, dandelion 1.4%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | paradise_lost:tradewinds | minecraft:meadow | 0.9 | 2428 | expressed | - |
| O2 | paradise_lost:highlands | adventure:sky_highlands | 15.6 | 16344 | expressed | - |
| O3 | minecraft:warm_ocean | minecraft:warm_ocean | 18.1 | 12402 | placed, not expressed | features name trees, none on 0.3 ha of land |
| O4 | terralith:deep_warm_ocean | minecraft:deep_lukewarm_ocean | 9.3 | 6200 | expressed | - |
| O5 | minecraft:jungle | minecraft:jungle | 20.6 | 18558 | expressed | - |
| O6 | terralith:skylands_summer | adventure:hanging_gardens | 0.5 | 8782 | placed, not expressed | mostly water |
| O7 | terralith:mirage_isles | adventure:desert_oasis | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | minecraft:lukewarm_ocean | minecraft:lukewarm_ocean | 22.0 | 14783 | expressed | - |
| O9 | minecraft:beach | minecraft:beach | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:lukewarm_ocean 22.0%, minecraft:jungle 20.6%, minecraft:warm_ocean 18.1% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 44.6% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 12.9% (`surface.voidPercent`) | |
| M:canopy | 14.7% (`surface.canopyPercent`) | |
| M:relief | median 19.0 blocks a patch; height {"p5": 133.0, "p25": 133.0, "p50": 144.0, "p75": 167.0, "p95": 233.0, "max": 246.0} (`terrain`) | |
| M:layers | {"sky": 19.0, "surface": 87.1, "underground": 84.7, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 12.9, "roofed": 0.0, "stacked": 19.0, "overhang": 2.9, "cave": 7.7, "single": 57.6} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 55.6%
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: sky present (19.0% of columns)

## Shots

### the_sunken_temple-surface-t1

Picked for: surface type 1: 57.1% of cells; biome lukewarm_ocean; ground sand; top water; height 136; ground plants 0% (layer 10%); liquid cover 87% (layer 50%)
Census: sky 6.1%; unloaded 23%; near: water 51.6%; mid: water 3.6%, vine 2.3%; far: jungle_leaves 1%; biomes: lukewarm_ocean 81%, jungle 15.9%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-surface-t1.png`

### the_sunken_temple-sky-t1

Picked for: sky type 1: 59.6% of cells; biome adventure:sky_highlands; ground grass_block; height 229; trees per cell 0.3 (layer 4.1)
Census: sky 34.3%; unloaded 14.1%; near: grass_block 23.3%, short_grass 20%; far: water 3.2%; biomes: adventure:sky_highlands 93.4%, warm_ocean 6.4%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-sky-t1.png`

### the_sunken_temple-underground-t2

Picked for: underground type 2: 8.4% of cells; notableId cave_vines_plant; biome jungle; height 228; notable-block density (log) 1.5 (layer 1.1); cave air 39% (layer 3%); cave pockets 91% (layer 10%)
Census: sky 0%; unloaded 2.7%; near: grass_block 37.7%, cave_vines_plant 12.5%; mid: grass_block 13.5%, oak_leaves 8.3%; far: oak_leaves 1.9%, vine 1.6%; biomes: jungle 83.7%, adventure:hanging_gardens 15.8%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-underground-t2.png`

### the_sunken_temple-surface-t3

Picked for: surface type 3: 19.5% of cells; biome jungle; ground grass_block; top jungle_leaves; height 184; trees per cell 9.9 (layer 2.1); notable-block density (log) 0.8 (layer 0.5); canopy 76% (layer 17%); ground plants 27% (layer 10%); liquid cover 2% (layer 50%); open to the sky 12% (layer 62%); cave pockets 26% (layer 10%)
Census: sky 0%; unloaded 0.2%; near: oak_leaves 20.9%, vine 20.4%; mid: vine 1.4%; biomes: jungle 99.9%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-surface-t3.png`

### the_sunken_temple-surface-t2

Picked for: surface type 2: 23.4% of cells; biome adventure:sky_highlands; ground grass_block; top grass_block; height 195; layer present in 92% (layer 98%); neighbour step 0.6 (layer 0.3); relief over 16 blocks 14.9 (layer 7.0); relief over the patch 36.4 (layer 20.4); notable-block density (log) 0.3 (layer 0.5); ground plants 18% (layer 10%); liquid cover 3% (layer 50%)
Census: sky 17.7%; unloaded 18.1%; near: grass_block 40.4%, short_grass 18.4%; far: cave_vines_plant 1%; biomes: adventure:sky_highlands 98.2%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-surface-t2.png`

### the_sunken_temple-sky-t2

Picked for: sky type 2: 40.4% of cells; biome jungle; ground grass_block; height 206; trees per cell 9.7 (layer 4.1); notable-block density (log) 0.9 (layer 0.7)
Census: sky 13.4%; unloaded 5.4%; near: jungle_leaves 32.2%, vine 20.5%; mid: vine 2.9%, jungle_leaves 2.5%; biomes: jungle 100%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-sky-t2.png`

### the_sunken_temple-surface-lm1

Picked for: surface landmark: biome jungle (type adventure:sky_highlands); neighbour step 1.7 (type 0.3); stacked layers 100% (type 0%); open to the sky 0% (type 100%)
Census: sky 4.1%; unloaded 5.5%; near: grass_block 43%, cave_vines_plant 4.6%; mid: water 8%, cave_vines_plant 7.7%; far: cave_vines_plant 4.8%, jungle_leaves 2.6%; biomes: jungle 70.5%, adventure:hanging_gardens 17.2%, lukewarm_ocean 12.3%
Image: `/tmp/claude-0/w10c1/reviews/the_sunken_temple/screens/the_sunken_temple-surface-lm1.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
