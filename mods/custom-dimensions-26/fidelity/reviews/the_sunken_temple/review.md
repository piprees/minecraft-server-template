# Review packet: the_sunken_temple

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_sunken_temple.json`

> Temple-hunting across drowned skyland basins. Monuments and fortresses sit where the water pooled between paradise islands — half-submerged architecture that predates the flooding. Something sacred was kept here, and the rising water couldn't wash it away.

Biomes: paradise_lost:tradewinds, paradise_lost:highlands, minecraft:warm_ocean, terralith:deep_warm_ocean, minecraft:jungle, terralith:skylands_summer, terralith:mirage_isles, minecraft:lukewarm_ocean, minecraft:beach

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_sunken_temple.json`

> Temple-hunting across drowned skyland basins. Monuments and fortresses sit where the water pooled between paradise islands — half-submerged architecture that predates the flooding. Something sacred was kept here, and the rising water couldn't wash it away.

Biomes: minecraft:lukewarm_ocean, minecraft:warm_ocean, minecraft:deep_lukewarm_ocean, minecraft:jungle, adventure:sky_highlands, minecraft:meadow, adventure:hanging_gardens

Terrain: `{"base": "basin_islands", "params": {"lakeShare": 0.75, "basinDepth": 16, "rimHeight": 16, "islandShare": 0.2, "lakeSize": 2, "size": 2, "coverage": 0.55, "tiers": 2, "tierSpacing": 36, "tierGap": 16, "tierShrink": 0.6, "underside": 50}, "scales": {"climate": 3}}`; generator `{"type": "clone", "of": "adventure:the_aviary"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_sunken_temple/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_sunken_temple/layers`

## Summary

- Top blocks seen from the sky: water 56.7%, grass_block 21.7%, oak_leaves 4.1%, jungle_leaves 4%.
- Surface biomes: jungle 23.3%, warm_ocean 21%, lukewarm_ocean 16.5%, adventure:sky_highlands 11.2%, adventure:hanging_gardens 10.5%.
- Ground blocks: sand 35.9%, grass_block 34.5%, dirt 11.5%, stone 5.5%.
- Liquid cover 56.7% water, 0% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 9.8%; bare rock 0%; no ground (void) 5.9%.
- Trees: 109.6 per ha (jungle_log 96.3/ha, mean spacing 3.5 blocks, oak_log 13.3/ha, mean spacing 10.4 blocks); ground plants grass 3.6%, flower 1.5%, other 0.6%.
- Surface height p5 114, median 136, p95 208, max 240; median relief per patch 21 blocks; slopes under 15° 65.4%, over 45° 8.6%, cliffs 1.3%; above the clouds 7.3%, below sea level 0%.
- Underground: 0.8% open, 1.6% liquid; cave biomes adventure:hanging_gardens 99%, jungle 1%, warm_ocean 0%.
- Notable clusters, fewest first: glow_lichen 5 clusters, spacing 1.4; poppy 6 clusters, spacing 4; emerald_ore 7 clusters, spacing 16.5; flowering_azalea 8 clusters, spacing 40.8; bamboo 11 clusters, spacing 22.7; melon 13 clusters, spacing 4.
- 7 of 7 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 1083 columns: height within 2 blocks 97.5%, biome match 99.7%.
- Landforms over the border (noise render): plains 6.8%, hills 4%, mountains 1.8%, plateaus 2.2%, basins 3.5%, islands 20%, water 50.1%, void 11.5%.
- Placeness: biome regions 6.6/km² (17 major, largest 9.7%), landform regions 10.9/km² (largest 45.3%), dominant place 45.3% of the border.

## Layers

- Columns by class: void 5.9%, roofed 0%, stacked 25.7%, overhang 6.3%, cave 3.2%, single 58.9%.
- Columns with each layer: sky 25.7%, surface 94.1%, underground 90.3%, roof 0%.
- Overhangs in 6.5% of columns, stacked layers in 25.7%, cave pockets in 4.5%.

## Places

- **surface** (274 cells, 3 types, silhouette 0.447):
  - type 1: 61.7% of cells; biome warm_ocean; ground sand; top water; height 140; ground plants 0% (layer 6%); liquid cover 96% (layer 60%) — camera [3352.5,165,-375.5]
  - type 2: 26.3% of cells; biome adventure:hanging_gardens; ground grass_block; top grass_block; height 147; notable-block density (log) 0.2 (layer 0.4); liquid cover 1% (layer 60%) — camera [-3687.5,136,3192.5]
  - type 3: 12.0% of cells; biome jungle; ground grass_block; top jungle_leaves; height 189; layer present in 95% (layer 99%); trees per cell 10.6 (layer 1.3); notable-block density (log) 0.8 (layer 0.4); canopy 85% (layer 11%); ground plants 30% (layer 6%); liquid cover 0% (layer 60%); open to the sky 12% (layer 62%); stacked layers 3% (layer 27%); cave pockets 13% (layer 5%) — camera [-2981.5,178,2328.5]
  - landmark at [-3720,-3512]: biome jungle (type warm_ocean); ground dirt (type sand); relief over 16 blocks 27.0 (type 1.0); neighbour step 1.3 (type 0.1); cave pockets 33% (type 0%)
  - landmark at [-472,1544]: biome meadow (type warm_ocean); ground dirt (type sand); neighbour step 1.7 (type 0.1); relief over 16 blocks 26.0 (type 1.0); stacked layers 100% (type 0%)
  - landmark at [936,-1864]: biome adventure:sky_highlands (type warm_ocean); ground dirt (type sand); neighbour step 1.6 (type 0.1); relief over 16 blocks 26.0 (type 1.0); overhangs 29% (type 0%)
  - landmark at [-456,1544]: biome meadow (type warm_ocean); ground dirt (type sand); cave pockets 75% (type 0%); stacked layers 94% (type 0%); open to the sky 6% (type 100%)
  - landmark at [-3960,-2152]: biome jungle (type warm_ocean); ground dirt (type sand); neighbour step 1.6 (type 0.1); relief over 16 blocks 27.0 (type 1.0); overhangs 31% (type 0%)
- **sky** (83 cells, 2 types, silhouette 0.476):
  - type 1: 53.0% of cells; biome adventure:sky_highlands; ground grass_block; height 198; trees per cell 0.2 (layer 5.3); notable-block density (log) 0.4 (layer 0.6) — camera [2840.5,199,-951.5]
  - type 2: 47.0% of cells; biome jungle; ground grass_block; height 197; trees per cell 11.0 (layer 5.3); notable-block density (log) 0.8 (layer 0.6) — camera [2520.5,202,2647.5]
  - landmark at [-456,1560]: biome meadow (type adventure:sky_highlands); relief over 16 blocks 46.0 (type 9.0); neighbour step 1.9 (type 0.2); island layers above 1.1 (type 1.0)
  - landmark at [-3976,-2184]: neighbour step 1.2 (type 0.2); relief over 16 blocks 27.0 (type 9.0); layer present in 71% (type 100%)
  - landmark at [1112,-776]: layer present in 34% (type 88%); height 238.7 (type 197.2); neighbour step 0.9 (type 0.2)
  - landmark at [-1096,792]: layer present in 32% (type 88%); neighbour step 0.9 (type 0.2); notable-block density (log) 0.2 (type 0.5)
- **underground** (264 cells, 4 types, silhouette 0.39):
  - type 1: 60.2% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 145
  - type 2: 18.9% of cells; notableId rooted_dirt; biome adventure:hanging_gardens; height 149
  - type 3: 11.4% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 144; neighbour step 1.0 (layer 0.3); relief over 16 blocks 25.2 (layer 7.1); cave liquid 10% (layer 1%)
  - type 4: 9.5% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 168; cave air 5% (layer 0%); cave pockets 31% (layer 5%) — camera [1748.5,189.88,2705.5]
  - landmark at [-2520,-4024]: height 221.5 (type 134.2); layer present in 73% (type 100%); notable-block density (log) 0.0 (type 1.1)
  - landmark at [-2520,-4040]: height 224.2 (type 131.5); notable-block density (log) 0.2 (type 1.2); layer present in 81% (type 100%)
  - landmark at [-3656,3192]: cave air 10% (type 3%); cave pockets 60% (type 15%); height 133.1 (type 217.7)
  - landmark at [2728,3560]: notableId rooted_dirt (type cave_vines_plant); cave pockets 67% (type 15%); height 172.7 (type 217.7); cave air 5% (type 3%)
  - landmark at [-2520,-4008]: height 216.7 (type 131.5); notable-block density (log) 0.2 (type 1.2); layer present in 84% (type 100%)
- **roof**: no cells.

## Biome expression

7 of 7 placed biomes are expressed.

- minecraft:jungle: expressed, 26195 columns; placement land 46%, water 19.5%, sky 34.5%; snow 0%, canopy 57.6%, trees 660.2/ha; top water 19.5%, grass_block 15.4%, jungle_leaves 11.2%
- minecraft:warm_ocean: expressed, 15571 columns; placement land 3.7%, water 95.9%, sky 0.4%; snow 0%, canopy 33.6%, trees 0/ha; top water 95.9%, hanging_roots 1.9%, oak_leaves 1%
- adventure:sky_highlands: expressed, 14941 columns; placement land 22.4%, water 32.9%, sky 44.6%; snow 0%, canopy 2.1%, trees 38.8/ha; top water 32.9%, grass_block 17.8%, short_grass 2%
- adventure:hanging_gardens: expressed, 12265 columns; placement land 57.6%, water 5.7%, void 35.7%, sky 0.9%; snow 0%, canopy 0%, trees 0/ha; top grass_block 51.9%, water 5.7%, dandelion 1%
- minecraft:lukewarm_ocean: expressed, 12202 columns; placement land 1.7%, water 98%, sky 0.3%; snow 0%, canopy 4.9%, trees 0/ha; top water 98%, hanging_roots 1.3%, cave_vines_plant 0.2%
- minecraft:meadow: expressed, 7525 columns; placement land 52.6%, water 8.8%, sky 38.6%; snow 0%, canopy 0%, trees 0/ha; top grass_block 39.4%, water 8.8%, short_grass 4.2%
- minecraft:deep_lukewarm_ocean: expressed, 3972 columns; placement land 8.8%, water 88.1%, sky 3.1%; snow 0%, canopy 0%, trees 0/ha; top water 88.1%, hanging_roots 7.6%, cave_vines_plant 1.2%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | paradise_lost:tradewinds | minecraft:meadow | 6.3 | 7525 | expressed | - |
| O2 | paradise_lost:highlands | adventure:sky_highlands | 11.2 | 14941 | expressed | - |
| O3 | minecraft:warm_ocean | minecraft:warm_ocean | 21.0 | 15571 | expressed | - |
| O4 | terralith:deep_warm_ocean | minecraft:deep_lukewarm_ocean | 5.2 | 3972 | expressed | - |
| O5 | minecraft:jungle | minecraft:jungle | 23.3 | 26195 | expressed | - |
| O6 | terralith:skylands_summer | adventure:hanging_gardens | 10.5 | 12265 | expressed | - |
| O7 | terralith:mirage_isles | adventure:desert_oasis | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |
| O8 | minecraft:lukewarm_ocean | minecraft:lukewarm_ocean | 16.5 | 12202 | expressed | - |
| O9 | minecraft:beach | minecraft:beach | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:jungle 23.3%, minecraft:warm_ocean 21.0%, minecraft:lukewarm_ocean 16.5% | type `paradise_lost:paradise_lost` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 56.7% (`surface.fluidCoverPercent.water`) | settingsOverrides `{"seaLevel": 90}` |
| M:lava | 0.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 5.9% (`surface.voidPercent`) | |
| M:canopy | 9.8% (`surface.canopyPercent`) | |
| M:relief | median 21.0 blocks a patch; height {"p5": 114.0, "p25": 133.0, "p50": 136.0, "p75": 160.0, "p95": 208.0, "max": 240.0} (`terrain`) | |
| M:layers | {"sky": 25.7, "surface": 94.1, "underground": 90.3, "roof": 0.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 5.9, "roofed": 0.0, "stacked": 25.7, "overhang": 6.3, "cave": 3.2, "single": 58.9} (`vertical.columnClassPercent`); ceiling False, skylight True | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 77.8%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: sky present (25.7% of columns)

## Shots

### the_sunken_temple-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 11.3%; near: jungle_leaves 5.9%, vine 1%; mid: jungle_leaves 5.9%, oak_leaves 4.9%; far: water 47.3%, oak_leaves 5.1%; biomes: jungle 81.8%, warm_ocean 14.3%
Image: `/tmp/claude-0/w10r/reviews/the_sunken_temple/screens/the_sunken_temple-far.png`

### the_sunken_temple-surface-t1

Picked for: surface type 1: 61.7% of cells; biome warm_ocean; ground sand; top water; height 140; ground plants 0% (layer 6%); liquid cover 96% (layer 60%)
Census: sky 4.2%; unloaded 23.4%; near: water 51.6%; mid: water 3.9%, oak_leaves 1.8%; far: vine 2.5%, jungle_leaves 2.3%; biomes: warm_ocean 74.4%, jungle 20.3%, adventure:hanging_gardens 5.2%
Image: `/tmp/claude-0/w10r/reviews/the_sunken_temple/screens/the_sunken_temple-surface-t1.png`

### the_sunken_temple-sky-t1

Picked for: sky type 1: 53.0% of cells; biome adventure:sky_highlands; ground grass_block; height 198; trees per cell 0.2 (layer 5.3); notable-block density (log) 0.4 (layer 0.6)
Census: sky 18.7%; unloaded 18.8%; near: grass_block 24.3%, quartz_block 22.3%; far: grass_block 1.2%; biomes: adventure:sky_highlands 98.4%
Image: `/tmp/claude-0/w10r/reviews/the_sunken_temple/screens/the_sunken_temple-sky-t1.png`

### the_sunken_temple-underground-t4

Picked for: underground type 4: 9.5% of cells; notableId cave_vines_plant; biome adventure:hanging_gardens; height 168; cave air 5% (layer 0%); cave pockets 31% (layer 5%)
Census: sky 0%; near: grass_block 57.9%, stone 18.6%; biomes: adventure:hanging_gardens 100%
Image: `/tmp/claude-0/w10r/reviews/the_sunken_temple/screens/the_sunken_temple-underground-t4.png`

### the_sunken_temple-structure-sinking_temple

Picked for: want start of ati_structures:sinking_temple
Census: sky 5.3%; void 38.9%; unloaded 1.6%; mid: grass_block 21.6%, short_grass 4.7%; far: cave_vines_plant 1.3%; biomes: adventure:sky_highlands 86.4%, adventure:hanging_gardens 12.2%
Image: `/tmp/claude-0/w10r/reviews/the_sunken_temple/screens/the_sunken_temple-structure-sinking_temple.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
