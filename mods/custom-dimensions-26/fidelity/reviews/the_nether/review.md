# Review packet: the_nether

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_nether.json`

> The nether — piglin heartland, 1:8 compressed. Fortresses and bastions in reach, Incendium's sanctum near the rim. Every civilisation that touched this place left infrastructure; only the piglins called it home.

Biomes: none listed

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_nether.json`

> The nether — piglin heartland, 1:8 compressed. Fortresses and bastions in reach, Incendium's sanctum near the rim. Every civilisation that touched this place left infrastructure; only the piglins called it home.

Biomes: minecraft:nether_wastes, minecraft:crimson_forest, minecraft:warped_forest, minecraft:soul_sand_valley, minecraft:basalt_deltas, adventure:quartz_flats

Terrain: `{"scales": {"climate": 2.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10c1q/final/the_nether/dossier.json`; layer maps in `/tmp/claude-0/w10c1q/final/the_nether/layers`

## Summary

- Top blocks seen from the sky: netherrack 32.4%, lava 17.9%, nether_wart_block 7.3%, bedrock 6%.
- Surface biomes: crimson_forest 24.4%, nether_wastes 23.8%, warped_forest 19.9%, soul_sand_valley 14.8%, basalt_deltas 9.7%.
- Ground blocks: netherrack 47.1%, crimson_nylium 13.9%, warped_nylium 8.6%, bedrock 7.1%.
- Liquid cover 0% water, 17.9% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 11.7%; bare rock 39.3%; no ground (void) 0%.
- Trees: 103.5 per ha (crimson_stem 69.3/ha, mean spacing 2.8 blocks, warped_stem 34.2/ha, mean spacing 3.6 blocks); ground plants grass 3.2%, other 1.2%, mushroom 0.5%.
- Surface height p5 21, median 85, p95 127, max 127; median relief per patch 113 blocks; slopes under 15° 31.8%, over 45° 43.6%, cliffs 30.4%; above the clouds 0%, below sea level 15.7%.
- Underground: 22% open, 6.6% liquid; cave biomes warped_forest 30.9%, nether_wastes 29.4%, crimson_forest 24.8%, basalt_deltas 6.6%.
- Notable clusters, fewest first: basalt 4 clusters, spacing 20.1; soul_fire 5 clusters, spacing 2.2; bone_block 7 clusters, spacing 10.2; brown_mushroom 16 clusters, spacing 6; quartz_block 26 clusters, spacing 5.6; quartz_pillar 30 clusters, spacing 5.2.
- 4 of 6 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 972 columns: height within 2 blocks 70.8%, biome match 100%.
- Landforms over the border (noise render): plains 33.3%, hills 21.8%, mountains 12.8%, plateaus 5.5%, basins 6.9%, islands 0.8%, water 18.9%, void 0%.
- Placeness: biome regions 1.4/km² (6 major, largest 28.1%), landform regions 15.3/km² (largest 8.2%), dominant place 28.1% of the border.

## Layers

- Columns by class: void 0%, roofed 94%, stacked 0%, overhang 0%, cave 0%, single 6%.
- Columns with each layer: sky 1%, surface 100%, underground 98.2%, roof 94%.
- Overhangs in 37.7% of columns, stacked layers in 1%, cave pockets in 39.9%.

## Places

- **surface** (243 cells, 7 types, silhouette 0.373):
  - type 1: 33.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 80; canopy 2% (layer 12%); bare rock 78% (layer 39%); cave pockets 58% (layer 40%) — camera [984.5,78,-392.5]
  - type 2: 15.6% of cells; biome crimson_forest; ground crimson_nylium; top nether_wart_block; height 81; neighbour step 3.8 (layer 6.1); relief over 16 blocks 59.9 (layer 81.0); relief over the patch 95.5 (layer 106.7); trees per cell 5.2 (layer 1.3); canopy 42% (layer 12%); ground plants 17% (layer 5%); liquid cover 4% (layer 18%); bare rock 11% (layer 39%) — camera [-342.5,98,-68.5]
  - type 3: 14.4% of cells; biome warped_forest; ground netherrack; top lava; height 43; relief over 16 blocks 101.9 (layer 81.0); notable-block density (log) 0.7 (layer 0.4); liquid cover 68% (layer 18%); bare rock 12% (layer 39%); cave pockets 9% (layer 40%) — camera [-183.5,32,120.5]
  - type 4: 12.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 74; relief over the patch 117.5 (layer 106.7); trees per cell 0.0 (layer 1.3); canopy 1% (layer 12%); ground plants 0% (layer 5%); bare rock 59% (layer 39%) — camera [-792.5,68,584.5]
  - type 5: 9.1% of cells; biome warped_forest; ground warped_nylium; top warped_wart_block; height 76; trees per cell 4.0 (layer 1.3); canopy 38% (layer 12%); ground plants 16% (layer 5%); bare rock 12% (layer 39%); overhangs 65% (layer 38%) — camera [-439.5,81,291.5]
  - type 6: 7.8% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 90; relief over the patch 120.3 (layer 106.7); trees per cell 0.0 (layer 1.3); canopy 0% (layer 12%); ground plants 0% (layer 5%); bare rock 5% (layer 39%); overhangs 9% (layer 38%) — camera [696.5,102,871.5]
  - type 7: 7.4% of cells; biome soul_sand_valley; ground bedrock; top bedrock; height 102; neighbour step 11.1 (layer 6.1); relief over 16 blocks 111.8 (layer 81.0); relief over the patch 120.7 (layer 106.7); trees per cell 0.0 (layer 1.3); canopy 0% (layer 12%); ground plants 0% (layer 5%); bare rock 16% (layer 39%); open to the sky 57% (layer 6%); overhangs 2% (layer 38%); cave pockets 4% (layer 40%); under a roof 43% (layer 94%) — camera [168.5,128,824.5]
  - landmark at [40,312]: biome soul_sand_valley (type basalt_deltas); ground soul_soil (type basalt); top soul_soil (type basalt); stacked layers 14% (type 0%); neighbour step 14.4 (type 6.2); bare rock 25% (type 68%)
  - landmark at [184,248]: biome soul_sand_valley (type nether_wastes); ground soul_soil (type netherrack); top soul_soil (type netherrack); notable-block density (log) 1.7 (type 0.1); bare rock 13% (type 84%); relief over 16 blocks 31.0 (type 84.0)
  - landmark at [280,792]: biome soul_sand_valley (type adventure:quartz_flats); ground soul_sand (type quartz_block); top soul_sand (type quartz_block); notable-block density (log) 2.4 (type 0.1); relief over 16 blocks 44.0 (type 119.0); neighbour step 1.2 (type 7.1)
  - landmark at [-856,-1000]: biome crimson_forest (type warped_forest); ground crimson_nylium (type netherrack); stacked layers 28% (type 0%); overhangs 81% (type 25%); liquid cover 38% (type 82%)
  - landmark at [184,232]: biome soul_sand_valley (type nether_wastes); ground soul_soil (type netherrack); top soul_soil (type netherrack); relief over 16 blocks 8.0 (type 84.0); notable-block density (log) 1.3 (type 0.1); bare rock 27% (type 84%)
- **sky** (4 cells, 1 types, silhouette 0.0):
  - type 1: 100.0% of cells; biome crimson_forest; ground crimson_nylium; height 90 — camera [-855.5,81,-1006.5]
- **underground** (243 cells, 7 types, silhouette 0.202):
  - type 1: 20.2% of cells; notableId blackstone; biome nether_wastes; height 69; cave liquid 11% (layer 7%); cave pockets 58% (layer 40%) — camera [408.5,69.88,-420.5]
  - type 2: 18.1% of cells; notableId blackstone; biome warped_forest; height 66; layer present in 95% (layer 98%); notable-block density (log) 0.6 (layer 1.4); cave air 46% (layer 23%); cave liquid 12% (layer 7%); cave pockets 23% (layer 40%) — camera [-855.5,30.88,-55.5]
  - type 3: 16.9% of cells; notableId magma_block; biome crimson_forest; height 84; neighbour step 3.4 (layer 6.0); relief over 16 blocks 51.3 (layer 78.8); cave air 9% (layer 23%); cave liquid 4% (layer 7%) — camera [-290.5,91.88,-773.5]
  - type 4: 13.6% of cells; notableId nether_quartz_ore; biome adventure:quartz_flats; height 92; neighbour step 9.6 (layer 6.0); relief over 16 blocks 108.6 (layer 78.8); cave air 1% (layer 23%); cave liquid 1% (layer 7%); cave pockets 8% (layer 40%) — camera [696.5,86.88,842.5]
  - type 5: 11.5% of cells; notableId blackstone; biome basalt_deltas; height 72; cave liquid 4% (layer 7%) — camera [-621.5,48.88,1022.5]
  - type 6: 11.5% of cells; notableId lava; biome soul_sand_valley; height 77; notable-block density (log) 3.0 (layer 1.4); cave pockets 66% (layer 40%) — camera [73.5,56.88,294.5]
  - type 7: 8.2% of cells; notableId magma_block; biome crimson_forest; height 91; neighbour step 3.8 (layer 6.0); relief over 16 blocks 57.4 (layer 78.8); trees per cell 7.4 (layer 1.0); cave air 37% (layer 23%); cave pockets 70% (layer 40%) — camera [-343.5,57.88,-71.5]
  - landmark at [-184,-760]: notableId nether_gold_ore (type blackstone); biome crimson_forest (type warped_forest); layer present in 58% (type 100%); neighbour step 18.0 (type 8.5); cave liquid 27% (type 11%)
  - landmark at [-456,312]: biome warped_forest (type soul_sand_valley); trees per cell 7.0 (type 0.0); cave liquid 25% (type 8%); cave air 48% (type 24%)
  - landmark at [-184,-776]: cave liquid 35% (type 8%); layer present in 80% (type 100%); height 28.9 (type 73.8)
  - landmark at [24,-280]: notableId lava (type magma_block); cave liquid 28% (type 0%); notable-block density (log) 4.7 (type 1.5); cave pockets 100% (type 19%)
  - landmark at [-200,104]: notableId lava (type blackstone); layer present in 68% (type 100%); notable-block density (log) 5.2 (type 0.7); cave liquid 29% (type 11%)
- **roof** (241 cells, 2 types, silhouette 0.407):
  - type 1: 71.4% of cells; notableId nether_quartz_ore; height 105; relief over 16 blocks 25.1 (layer 41.4) — camera [40.5,104.88,-279.5]
  - type 2: 28.6% of cells; notableId nether_quartz_ore; height 83; layer present in 83% (layer 95%); neighbour step 4.8 (layer 2.3); relief over 16 blocks 82.1 (layer 41.4); notable-block density (log) 1.0 (layer 0.5) — camera [680.5,88.88,870.5]
  - landmark at [712,840]: notableId magma_block (type nether_quartz_ore); layer present in 50% (type 98%); neighbour step 9.6 (type 4.4); height 51.8 (type 79.2)
  - landmark at [184,808]: notableId magma_block (type nether_quartz_ore); layer present in 29% (type 98%); height 39.1 (type 79.2); neighbour step 7.4 (type 4.4)
  - landmark at [168,824]: notableId soul_sand (type nether_quartz_ore); layer present in 43% (type 98%); notable-block density (log) 2.4 (type 1.0); height 59.0 (type 79.2)
  - landmark at [248,776]: notableId soul_sand (type nether_quartz_ore); layer present in 42% (type 98%); neighbour step 0.4 (type 4.4); notable-block density (log) 2.0 (type 1.0)
  - landmark at [-568,-248]: notableId magma_block (type nether_quartz_ore); layer present in 32% (type 98%); neighbour step 7.3 (type 4.4); height 97.1 (type 79.2)

## Biome expression

4 of 6 placed biomes are expressed.

- minecraft:crimson_forest: expressed, 15529 columns; placement land 82.5%, lava 15.2%, roofed 93%, sky 2.3%; snow 0%, canopy 35.7%, trees 167.1/ha; top nether_wart_block 28.8%, crimson_nylium 23.9%, netherrack 17.4%
- minecraft:nether_wastes: placed, not expressed, 14967 columns; placement land 87.3%, lava 11.5%, roofed 98.8%, sky 1.2%; snow 0%, canopy 0.3%, trees 1.5/ha; top netherrack 82.1%, lava 11.5%, nether_bricks 1.9%; **mostly bare rock**
- minecraft:warped_forest: expressed, 12442 columns; placement land 63%, lava 36.5%, roofed 99.5%, sky 0.5%; snow 0%, canopy 33.6%, trees 137.8/ha; top lava 36.5%, warped_wart_block 20.6%, warped_nylium 20.2%
- minecraft:soul_sand_valley: expressed, 9256 columns; placement land 92.6%, lava 7%, roofed 79.7%, sky 0.4%; snow 0%, canopy 0%, trees 0/ha; top netherrack 32.1%, soul_sand 21.2%, bedrock 19.9%
- minecraft:basalt_deltas: placed, not expressed, 6050 columns; placement land 76.8%, lava 23.2%, roofed 98.9%; snow 0%, canopy 0.2%, trees 0/ha; top basalt 55%, lava 23.2%, blackstone 9.8%; **mostly bare rock**
- adventure:quartz_flats: expressed, 4612 columns; placement land 90%, lava 9.9%, roofed 76%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 54.3%, bedrock 24%, lava 9.9%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

The 1.21.1 definition lists no biomes.

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

| row | v2 biome | surface % | columns | state | flag reason |
|---|---|---|---|---|---|
| V1 | minecraft:crimson_forest | 24.4 | 15529 | expressed | - |
| V2 | minecraft:nether_wastes | 23.8 | 14967 | placed, not expressed | mostly bare rock |
| V3 | minecraft:warped_forest | 19.9 | 12442 | expressed | - |
| V4 | minecraft:soul_sand_valley | 14.8 | 9256 | expressed | - |
| V5 | minecraft:basalt_deltas | 9.7 | 6050 | placed, not expressed | mostly bare rock |
| V6 | adventure:quartz_flats | 7.4 | 4612 | expressed | - |

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:crimson_forest 24.4%, minecraft:nether_wastes 23.8%, minecraft:warped_forest 19.9% | type `the_nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 17.9% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 11.7% (`surface.canopyPercent`) | |
| M:relief | median 113.0 blocks a patch; height {"p5": 21.0, "p25": 54.0, "p50": 85.0, "p75": 99.0, "p95": 127.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 1.0, "surface": 100.0, "underground": 98.2, "roof": 94.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 94.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 6.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): n/a
- S:b — 1.21.1 biomes expressed: n/a
- S:c — v2 biomes placed but not expressed: 2
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (94.0% of columns)

## Shots

### the_nether-surface-t1

Picked for: surface type 1: 33.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 80; canopy 2% (layer 12%); bare rock 78% (layer 39%); cave pockets 58% (layer 40%)
Census: sky 0%; unloaded 1%; near: netherrack 80.8%; mid: netherrack 12.5%; far: netherrack 3.9%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-t1.png`

### the_nether-surface-t1-nv

Picked for: surface type 1: 33.3% of cells; biome nether_wastes; ground netherrack; top netherrack; height 80; canopy 2% (layer 12%); bare rock 78% (layer 39%); cave pockets 58% (layer 40%)
Census: sky 0%; unloaded 0.7%; near: netherrack 80.8%; mid: netherrack 12.5%; far: netherrack 4.2%; biomes: nether_wastes 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-t1-nv.png`

### the_nether-surface-t3

Picked for: surface type 3: 14.4% of cells; biome warped_forest; ground netherrack; top lava; height 43; relief over 16 blocks 101.9 (layer 81.0); notable-block density (log) 0.7 (layer 0.4); liquid cover 68% (layer 18%); bare rock 12% (layer 39%); cave pockets 9% (layer 40%)
Census: sky 0%; near: lava 51.3%, warped_wart_block 4.3%; mid: warped_wart_block 12.2%, netherrack 11.7%; far: netherrack 4.5%; biomes: warped_forest 88.4%, crimson_forest 11.6%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-t3.png`

### the_nether-surface-t3-nv

Picked for: surface type 3: 14.4% of cells; biome warped_forest; ground netherrack; top lava; height 43; relief over 16 blocks 101.9 (layer 81.0); notable-block density (log) 0.7 (layer 0.4); liquid cover 68% (layer 18%); bare rock 12% (layer 39%); cave pockets 9% (layer 40%)
Census: sky 0%; near: lava 51.3%, warped_wart_block 4.3%; mid: warped_wart_block 12.2%, netherrack 11.7%; far: netherrack 4.5%; biomes: warped_forest 88.5%, crimson_forest 11.5%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-t3-nv.png`

### the_nether-surface-lm1

Picked for: surface landmark: biome soul_sand_valley (type basalt_deltas); ground soul_soil (type basalt); top soul_soil (type basalt); stacked layers 14% (type 0%); neighbour step 14.4 (type 6.2); bare rock 25% (type 68%)
Census: sky 0%; near: netherrack 48.7%, lava 38.5%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-lm1.png`

### the_nether-surface-lm1-nv

Picked for: surface landmark: biome soul_sand_valley (type basalt_deltas); ground soul_soil (type basalt); top soul_soil (type basalt); stacked layers 14% (type 0%); neighbour step 14.4 (type 6.2); bare rock 25% (type 68%)
Census: sky 0%; near: netherrack 48.7%, lava 38.5%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c1p/reviews/the_nether/screens/the_nether-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
