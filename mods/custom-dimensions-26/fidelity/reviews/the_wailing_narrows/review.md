# Review packet: the_wailing_narrows

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_wailing_narrows.json`

> Narrow bridged chasms — quartz spans over the wail. The quarantine that didn't hold: the zombified remnants of the boneyard war were driven here, deeper and deeper. The deeper you go, the worse it gets. The quarantine leaks back through the frame — soul sand takes whatever stands beside the portal.

Biomes: minecraft:soul_sand_valley, minecraft:basalt_deltas, incendium:quartz_flats

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_wailing_narrows.json`

> Narrow bridged chasms — quartz spans over the wail. The quarantine that didn't hold: the zombified remnants of the boneyard war were driven here, deeper and deeper. The deeper you go, the worse it gets. The quarantine leaks back through the frame — soul sand takes whatever stands beside the portal.

Biomes: minecraft:soul_sand_valley, minecraft:basalt_deltas

Terrain: `{"base": "roofed", "params": {"floorHeight": 80, "floorRelief": 8, "ceilingGap": 26, "roughness": 0.5, "chasms": 0.22, "chasmDepth": 62}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_wailing_narrows/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_wailing_narrows/layers`

## Summary

- Top blocks seen from the sky: basalt 31.2%, soul_sand 24.9%, soul_soil 20.8%, lava 10.1%.
- Surface biomes: soul_sand_valley 52%, basalt_deltas 48%.
- Ground blocks: basalt 35.7%, soul_sand 24.9%, soul_soil 21%, blackstone 8.2%.
- Liquid cover 0% water, 10.1% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 40.3%; no ground (void) 0%.
- Trees: 0 per ha; ground plants other 0.2%, mushroom 0.1%, grass 0.1%.
- Surface height p5 17, median 34, p95 101, max 127; median relief per patch 106 blocks; slopes under 15° 33.7%, over 45° 44.7%, cliffs 11.9%; above the clouds 0%, below sea level 2.5%.
- Underground: 12.3% open, 0.5% liquid; cave biomes soul_sand_valley 59.1%, basalt_deltas 40.9%.
- Notable clusters, fewest first: glowstone 14 clusters, spacing 26.2; brown_mushroom 42 clusters, spacing 5.2; red_mushroom 44 clusters, spacing 2.9; fire 54 clusters, spacing 5.7; soul_fire 59 clusters, spacing 3.7; basalt 85 clusters, spacing 5.4.
- 2 of 2 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 69.7%, biome match 100%.
- Landforms over the border (noise render): plains 37.6%, hills 26.9%, mountains 10%, plateaus 9.6%, basins 15.9%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 1.1/km² (2 major, largest 59.1%), landform regions 27.8/km² (largest 15.0%), dominant place 59.1% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0%, surface 100%, underground 97.7%, roof 100%.
- Overhangs in 12.2% of columns, stacked layers in 0%, cave pockets in 9.3%.

## Places

- **surface** (225 cells, 2 types, silhouette 0.47):
  - type 1: 52.9% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 44; bare rock 9% (layer 40%) — camera [328.5,21,-551.5]
  - type 2: 47.1% of cells; biome basalt_deltas; ground basalt; top basalt; height 52; liquid cover 18% (layer 10%); bare rock 75% (layer 40%) — camera [-263.5,60,87.5]
  - landmark at [-520,552]: biome basalt_deltas (type soul_sand_valley); ground bedrock (type soul_sand); top lava (type soul_sand); liquid cover 65% (type 0%); neighbour step 10.5 (type 1.6); notable-block density (log) 3.0 (type 0.6)
  - landmark at [8,24]: ground netherrack (type soul_sand); top netherrack (type soul_sand); stacked layers 0% (type 0%); neighbour step 9.8 (type 1.6); overhangs 47% (type 4%)
  - landmark at [392,-24]: ground bedrock (type soul_sand); top lava (type soul_sand); neighbour step 11.8 (type 1.6); overhangs 59% (type 4%); liquid cover 40% (type 0%)
  - landmark at [-232,-616]: ground blackstone (type basalt); top lava (type basalt); cave pockets 69% (type 0%); relief over the patch 40.0 (type 96.0); liquid cover 43% (type 12%)
  - landmark at [376,-24]: ground netherrack (type soul_sand); top netherrack (type soul_sand); stacked layers 1% (type 0%); overhangs 63% (type 4%); neighbour step 10.1 (type 1.6)
- **sky**: no cells.
- **underground** (224 cells, 3 types, silhouette 0.31):
  - type 1: 49.6% of cells; notableId blackstone; biome soul_sand_valley; height 28; cave pockets 0% (layer 10%) — camera [-266.5,33.88,238.5]
  - type 2: 25.4% of cells; notableId magma_block; biome soul_sand_valley; height 68; notable-block density (log) 1.7 (layer 1.2); cave pockets 23% (layer 10%) — camera [43.5,60.88,573.5]
  - type 3: 25.0% of cells; notableId blackstone; biome basalt_deltas; height 71 — camera [-345.5,82.88,337.5]
  - landmark at [-520,552]: notableId basalt (type blackstone); layer present in 35% (type 100%); neighbour step 15.5 (type 2.5); cave air 75% (type 10%)
  - landmark at [-504,552]: notableId basalt (type blackstone); cave air 82% (type 10%); layer present in 72% (type 100%); neighbour step 11.2 (type 2.5)
  - landmark at [392,-24]: notableId soul_sand (type magma_block); layer present in 60% (type 100%); cave air 73% (type 4%); neighbour step 12.9 (type 1.2)
  - landmark at [376,-24]: notableId soul_sand (type magma_block); cave air 83% (type 4%); neighbour step 10.4 (type 1.2); layer present in 86% (type 100%)
  - landmark at [-536,552]: notableId basalt (type blackstone); biome basalt_deltas (type none); neighbour step 14.9 (type 1.7); cave air 66% (type 2%); layer present in 71% (type 100%)
- **roof** (225 cells, 4 types, silhouette 0.359):
  - type 1: 41.8% of cells; notableId blackstone; height 108 — camera [-663.5,101.88,56.5]
  - type 2: 26.7% of cells; notableId nether_quartz_ore; height 108; notable-block density (log) 0.2 (layer 0.9) — camera [376.5,102.88,184.5]
  - type 3: 16.0% of cells; notableId soul_soil; height 108; notable-block density (log) 1.4 (layer 0.9) — camera [8.5,102.88,8.5]
  - type 4: 15.6% of cells; notableId soul_sand; height 108; notable-block density (log) 1.7 (layer 0.9) — camera [536.5,99.88,-647.5]
  - landmark at [-520,568]: notableId basalt (type nether_quartz_ore); notable-block density (log) 4.6 (type 0.1); layer present in 100% (type 100%); neighbour step 1.9 (type 0.4)
  - landmark at [-232,88]: neighbour step 2.2 (type 0.1); height 115.5 (type 107.1); relief over 16 blocks 18.0 (type 2.0)
  - landmark at [-520,552]: notableId magma_block (type nether_quartz_ore); layer present in 100% (type 100%); height 117.9 (type 107.6); neighbour step 1.8 (type 0.4)
  - landmark at [376,-24]: notableId nether_gold_ore (type nether_quartz_ore); layer present in 100% (type 100%); neighbour step 1.8 (type 0.4); height 114.8 (type 107.6)
  - landmark at [-504,552]: notableId blackstone (type nether_quartz_ore); layer present in 100% (type 100%); height 119.7 (type 107.6); neighbour step 0.8 (type 0.4)

## Biome expression

2 of 2 placed biomes are expressed.

- minecraft:soul_sand_valley: expressed, 29956 columns; placement land 97.9%, lava 2.1%, roofed 99.9%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 47.9%, soul_soil 40%, netherrack 7.7%
- minecraft:basalt_deltas: expressed, 27655 columns; placement land 81.2%, lava 18.8%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top basalt 64%, lava 18.8%, blackstone 9.8%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 52.0 | 29956 | expressed | - |
| O2 | minecraft:basalt_deltas | minecraft:basalt_deltas | 48.0 | 27655 | expressed | - |
| O3 | incendium:quartz_flats | adventure:quartz_flats | 0.0 | 0 | not in v2 definition | mapped id missing from the v2 biome list |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:soul_sand_valley 52.0%, minecraft:basalt_deltas 48.0% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 10.1% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 106.0 blocks a patch; height {"p5": 17.0, "p25": 21.0, "p50": 34.0, "p75": 77.0, "p95": 101.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 97.7, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#2B1B3B", "ambientLight": 0.06}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 66.7%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_wailing_narrows-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 0.1%; near: basalt 67.4%; mid: basalt 24.8%; far: basalt 4.2%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-far.png`

### the_wailing_narrows-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 0.1%; near: basalt 67.4%; mid: basalt 24.8%; far: basalt 4.2%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-far-nv.png`

### the_wailing_narrows-surface-t1

Picked for: surface type 1: 52.9% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 44; bare rock 9% (layer 40%)
Census: sky 0%; near: soul_sand 42.7%, soul_soil 12.2%; mid: soul_sand 14.9%, soul_soil 8.6%; far: soul_soil 4.9%, soul_sand 4.9%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t1.png`

### the_wailing_narrows-surface-t1-nv

Picked for: surface type 1: 52.9% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 44; bare rock 9% (layer 40%)
Census: sky 0%; near: soul_sand 42.7%, soul_soil 12.2%; mid: soul_sand 14.9%, soul_soil 8.6%; far: soul_soil 4.9%, soul_sand 4.9%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t1-nv.png`

### the_wailing_narrows-underground-t1

Picked for: underground type 1: 49.6% of cells; notableId blackstone; biome soul_sand_valley; height 28; cave pockets 0% (layer 10%)
Census: sky 0%; unloaded 6.2%; near: basalt 51%, gravel 4.2%; mid: basalt 17.1%; far: soul_sand 7.5%, soul_soil 7.2%; biomes: basalt_deltas 81.8%, soul_sand_valley 18.1%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-underground-t1.png`

### the_wailing_narrows-underground-t1-nv

Picked for: underground type 1: 49.6% of cells; notableId blackstone; biome soul_sand_valley; height 28; cave pockets 0% (layer 10%)
Census: sky 0%; unloaded 4.3%; near: basalt 51%, gravel 4.2%; mid: basalt 17.1%; far: soul_sand 8.4%, soul_soil 8.2%; biomes: basalt_deltas 80.3%, soul_sand_valley 19.7%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-underground-t1-nv.png`

### the_wailing_narrows-roof-t1

Picked for: roof type 1: 41.8% of cells; notableId blackstone; height 108
Census: sky 2.7%; near: basalt 80.2%; mid: basalt 7.9%; far: basalt 5.7%, soul_sand 1.2%; biomes: basalt_deltas 97.6%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-roof-t1.png`

### the_wailing_narrows-roof-t1-nv

Picked for: roof type 1: 41.8% of cells; notableId blackstone; height 108
Census: sky 2.8%; near: basalt 80.2%; mid: basalt 7.9%; far: basalt 5.7%, soul_sand 1.2%; biomes: basalt_deltas 97.6%
Image: `/tmp/claude-0/w10r/reviews/the_wailing_narrows/screens/the_wailing_narrows-roof-t1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
