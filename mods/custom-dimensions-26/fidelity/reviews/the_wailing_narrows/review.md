# Review packet: the_wailing_narrows

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_wailing_narrows.json`

> Narrow bridged chasms — quartz spans over the wail. The quarantine that didn't hold: the zombified remnants of the boneyard war were driven here, deeper and deeper. The deeper you go, the worse it gets. The quarantine leaks back through the frame — soul sand takes whatever stands beside the portal.

Biomes: minecraft:soul_sand_valley, minecraft:basalt_deltas, incendium:quartz_flats

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_wailing_narrows.json`

> Narrow bridged chasms — quartz spans over the wail. The quarantine that didn't hold: the zombified remnants of the boneyard war were driven here, deeper and deeper. The deeper you go, the worse it gets. The quarantine leaks back through the frame — soul sand takes whatever stands beside the portal.

Biomes: adventure:quartz_flats, minecraft:soul_sand_valley, minecraft:basalt_deltas

Terrain: `{"base": "roofed", "params": {"floorHeight": 80, "floorRelief": 8, "ceilingGap": 26, "roughness": 0.25, "chasms": 0.16, "chasmDepth": 62}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `runs/r11/full/the_wailing_narrows/dossier.json`; layer maps in `runs/r11/full/the_wailing_narrows/layers`

## Summary

- Top blocks seen from the sky: soul_sand 23%, basalt 22.8%, soul_soil 17.5%, quartz_block 12.6%.
- Surface biomes: soul_sand_valley 45.6%, basalt_deltas 38.8%, adventure:quartz_flats 15.7%.
- Ground blocks: basalt 26.8%, soul_sand 23.1%, soul_soil 17.7%, quartz_block 12.6%.
- Liquid cover 0% water, 9.2% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 31.7%; no ground (void) 0%.
- Trees: 0 per ha; ground plants other 0.2%, mushroom 0.1%, grass 0.1%.
- Surface height p5 18, median 72, p95 104, max 127; median relief per patch 104 blocks; slopes under 15° 38.6%, over 45° 39.3%, cliffs 15.3%; above the clouds 0%, below sea level 2.2%.
- Underground: 10% open, 0.5% liquid; cave biomes soul_sand_valley 48.5%, basalt_deltas 35.1%, adventure:quartz_flats 16.4%.
- Notable clusters, fewest first: dried_ghast 9 clusters, spacing 30.4; brown_mushroom 21 clusters, spacing 7.6; basalt 26 clusters, spacing 6.9; fire 39 clusters, spacing 6.5; red_mushroom 45 clusters, spacing 4.1; bone_block 51 clusters, spacing 9.1.
- 3 of 3 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 75.7%, biome match 100%.
- Landforms over the border (noise render): plains 34.2%, hills 31.3%, mountains 11.1%, plateaus 10.9%, basins 12.5%, islands 0%, water 0%, void 0%.
- Placeness: biome regions 1.6/km² (3 major, largest 53.6%), landform regions 24.6/km² (largest 10.1%), dominant place 53.6% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.1%, surface 100%, underground 97.9%, roof 100%.
- Overhangs in 13.2% of columns, stacked layers in 0.1%, cave pockets in 11.5%.

## Places

- **surface** (225 cells, 4 types, silhouette 0.429):
  - type 1: 38.7% of cells; biome basalt_deltas; ground basalt; top basalt; height 64; liquid cover 19% (layer 9%); bare rock 71% (layer 32%) — camera [-247.5,86,215.5]
  - type 2: 26.7% of cells; biome soul_sand_valley; ground soul_sand; top soul_sand; height 48; bare rock 7% (layer 32%) — camera [327.5,23,-552.5]
  - type 3: 19.1% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 38; relief over the patch 111.9 (layer 98.2); notable-block density (log) 1.2 (layer 0.8); bare rock 8% (layer 32%) — camera [136.5,22,88.5]
  - type 4: 15.6% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 70; relief over the patch 72.9 (layer 98.2); liquid cover 1% (layer 9%); bare rock 5% (layer 32%) — camera [-535.5,84,-392.5]
  - landmark at [-520,552]: biome basalt_deltas (type soul_sand_valley); ground bedrock (type soul_soil); top lava (type soul_soil); liquid cover 65% (type 1%); notable-block density (log) 3.0 (type 0.4); overhangs 43% (type 2%)
  - landmark at [392,-24]: ground bedrock (type soul_soil); top lava (type soul_soil); overhangs 69% (type 2%); stacked layers 1% (type 0%); neighbour step 11.9 (type 2.8)
  - landmark at [376,-24]: ground netherrack (type soul_sand); top lava (type soul_sand); neighbour step 17.5 (type 1.9); overhangs 51% (type 5%); stacked layers 1% (type 0%)
  - landmark at [104,-600]: biome soul_sand_valley (type basalt_deltas); ground netherrack (type basalt); top netherrack (type basalt); stacked layers 1% (type 0%); overhangs 29% (type 1%); cave pockets 30% (type 0%)
  - landmark at [104,264]: ground basalt (type soul_sand); top lava (type soul_sand); ground plants 2% (type 0%); overhangs 38% (type 5%); cave pockets 43% (type 0%)
- **sky**: no cells.
- **underground** (225 cells, 3 types, silhouette 0.244):
  - type 1: 39.6% of cells; notableId blackstone; biome soul_sand_valley; height 30; notable-block density (log) 0.8 (layer 1.3); cave pockets 1% (layer 12%) — camera [242.5,33.88,-280.5]
  - type 2: 30.2% of cells; notableId blackstone; biome basalt_deltas; height 73 — camera [43.5,60.88,573.5]
  - type 3: 30.2% of cells; notableId magma_block; biome soul_sand_valley; height 75; cave pockets 22% (layer 12%) — camera [349.5,78.88,-530.5]
  - landmark at [-520,552]: layer present in 35% (type 100%); cave air 75% (type 4%); neighbour step 15.4 (type 2.2)
  - landmark at [-504,552]: notableId magma_block (type blackstone); cave air 79% (type 4%); neighbour step 16.3 (type 2.2); layer present in 71% (type 100%)
  - landmark at [392,-24]: notableId soul_sand (type magma_block); cave air 74% (type 4%); layer present in 61% (type 100%); neighbour step 13.5 (type 2.6)
  - landmark at [-536,568]: biome basalt_deltas (type none); layer present in 25% (type 100%); cave air 48% (type 2%); neighbour step 11.0 (type 1.8)
  - landmark at [-536,552]: cave air 70% (type 4%); neighbour step 13.7 (type 2.2); layer present in 72% (type 100%)
- **roof** (225 cells, 6 types, silhouette 0.364):
  - type 1: 28.0% of cells; notableId nether_quartz_ore; height 107; notable-block density (log) 0.2 (layer 0.8) — camera [280.5,98.88,-295.5]
  - type 2: 23.6% of cells; notableId blackstone; height 106; neighbour step 0.1 (layer 0.5); relief over 16 blocks 6.3 (layer 11.3) — camera [-247.5,98.88,248.5]
  - type 3: 14.2% of cells; notableId soul_soil; height 107; notable-block density (log) 1.4 (layer 0.8) — camera [360.5,101.88,-551.5]
  - type 4: 14.2% of cells; notableId soul_sand; height 108; relief over 16 blocks 17.6 (layer 11.3); notable-block density (log) 1.6 (layer 0.8) — camera [536.5,101.88,-647.5]
  - type 5: 14.2% of cells; notableId blackstone; height 111; neighbour step 1.2 (layer 0.5) — camera [-535.5,101.88,552.5]
  - type 6: 5.8% of cells; notableId glowstone; height 107; notable-block density (log) 0.4 (layer 0.8) — camera [-551.5,100.88,-391.5]
  - landmark at [104,264]: notableId basalt (type soul_sand); relief over 16 blocks 103.0 (type 15.0); height 102.5 (type 108.0); neighbour step 1.2 (type 0.4)
  - landmark at [568,-200]: layer present in 100% (type 100%); relief over 16 blocks 42.0 (type 15.0); notable-block density (log) 0.0 (type 1.6)
  - landmark at [328,-536]: notableId basalt (type blackstone); height 116.4 (type 110.2); neighbour step 0.6 (type 1.4); notable-block density (log) 0.3 (type 0.1)
  - landmark at [-520,552]: notableId magma_block (type blackstone); height 117.9 (type 110.2); neighbour step 1.7 (type 1.4); notable-block density (log) 0.0 (type 0.1)
  - landmark at [168,120]: neighbour step 2.4 (type 0.4); height 112.2 (type 108.0); notable-block density (log) 0.8 (type 1.6)

## Biome expression

3 of 3 placed biomes are expressed.

- minecraft:soul_sand_valley: expressed, 26251 columns; placement land 98%, lava 1.9%, roofed 99.9%; snow 0%, canopy 0%, trees 0/ha; top soul_sand 50.5%, soul_soil 38.3%, netherrack 5.9%
- minecraft:basalt_deltas: expressed, 22330 columns; placement land 78.8%, lava 21.1%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top basalt 56.9%, lava 21.1%, blackstone 13.1%
- adventure:quartz_flats: expressed, 9057 columns; placement land 99.2%, lava 0.6%, roofed 99.8%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top quartz_block 80.1%, glowstone 8.4%, smooth_quartz_slab 5.1%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | minecraft:soul_sand_valley | minecraft:soul_sand_valley | 45.6 | 26251 | expressed | - |
| O2 | minecraft:basalt_deltas | minecraft:basalt_deltas | 38.8 | 22330 | expressed | - |
| O3 | incendium:quartz_flats | adventure:quartz_flats | 15.7 | 9057 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | minecraft:soul_sand_valley 45.6%, minecraft:basalt_deltas 38.8%, adventure:quartz_flats 15.7% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 9.2% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 104.0 blocks a patch; height {"p5": 18.0, "p25": 22.0, "p50": 72.0, "p75": 81.0, "p95": 104.0, "max": 127.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 100.0, "underground": 97.9, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `{"fogColor": "#2B1B3B", "ambientLight": 0.06}` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_wailing_narrows-surface-t1

Picked for: surface type 1: 38.7% of cells; biome basalt_deltas; ground basalt; top basalt; height 64; liquid cover 19% (layer 9%); bare rock 71% (layer 32%)
Census: sky 0.1%; unloaded 0.3%; near: basalt 63.9%; mid: basalt 31.2%; far: soul_soil 1.6%, basalt 1.5%; biomes: basalt_deltas 97.4%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t1.png`

### the_wailing_narrows-surface-t1-nv

Picked for: surface type 1: 38.7% of cells; biome basalt_deltas; ground basalt; top basalt; height 64; liquid cover 19% (layer 9%); bare rock 71% (layer 32%)
Census: sky 0.1%; unloaded 0.3%; near: basalt 63.9%; mid: basalt 31.2%; far: soul_soil 1.6%, basalt 1.5%; biomes: basalt_deltas 97.4%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t1-nv.png`

### the_wailing_narrows-underground-t1

Picked for: underground type 1: 39.6% of cells; notableId blackstone; biome soul_sand_valley; height 30; notable-block density (log) 0.8 (layer 1.3); cave pockets 1% (layer 12%)
Census: sky 0%; unloaded 0.7%; near: soul_sand 8.4%, soul_soil 6.2%; mid: soul_soil 27.5%, soul_sand 21.5%; far: soul_soil 10.5%, soul_sand 8.7%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-underground-t1.png`

### the_wailing_narrows-underground-t1-nv

Picked for: underground type 1: 39.6% of cells; notableId blackstone; biome soul_sand_valley; height 30; notable-block density (log) 0.8 (layer 1.3); cave pockets 1% (layer 12%)
Census: sky 0%; unloaded 0.6%; near: soul_sand 8.4%, soul_soil 6.2%; mid: soul_soil 27.5%, soul_sand 21.5%; far: soul_soil 10.6%, soul_sand 8.8%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-underground-t1-nv.png`

### the_wailing_narrows-roof-t1

Picked for: roof type 1: 28.0% of cells; notableId nether_quartz_ore; height 107; notable-block density (log) 0.2 (layer 0.8)
Census: sky 2.3%; unloaded 1.7%; near: soul_soil 51.3%, soul_sand 15.1%; mid: soul_soil 10.8%, soul_sand 9.8%; far: soul_sand 4.1%, soul_soil 2.4%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-roof-t1.png`

### the_wailing_narrows-roof-t1-nv

Picked for: roof type 1: 28.0% of cells; notableId nether_quartz_ore; height 107; notable-block density (log) 0.2 (layer 0.8)
Census: sky 2.7%; unloaded 1.3%; near: soul_soil 51.3%, soul_sand 15.1%; mid: soul_soil 10.8%, soul_sand 9.8%; far: soul_sand 4.1%, soul_soil 2.4%; biomes: soul_sand_valley 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-roof-t1-nv.png`

### the_wailing_narrows-surface-t4

Picked for: surface type 4: 15.6% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 70; relief over the patch 72.9 (layer 98.2); liquid cover 1% (layer 9%); bare rock 5% (layer 32%)
Census: sky 0.2%; near: quartz_block 45.4%, quartz_pillar 12%; mid: glowstone 13.2%, netherrack 12.7%; far: glowstone 3.1%, netherrack 1%; biomes: adventure:quartz_flats 99%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t4.png`

### the_wailing_narrows-surface-t4-nv

Picked for: surface type 4: 15.6% of cells; biome adventure:quartz_flats; ground quartz_block; top quartz_block; height 70; relief over the patch 72.9 (layer 98.2); liquid cover 1% (layer 9%); bare rock 5% (layer 32%)
Census: sky 0.2%; near: quartz_block 45.4%, quartz_pillar 12%; mid: glowstone 13.2%, netherrack 12.7%; far: glowstone 3.1%, netherrack 1%; biomes: adventure:quartz_flats 99%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t4-nv.png`

### the_wailing_narrows-surface-t3

Picked for: surface type 3: 19.1% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 38; relief over the patch 111.9 (layer 98.2); notable-block density (log) 1.2 (layer 0.8); bare rock 8% (layer 32%)
Census: sky 0.1%; unloaded 0.6%; near: soul_soil 48%, soul_sand 3.9%; mid: soul_sand 16.4%, soul_soil 13.2%; far: basalt 6.9%, soul_soil 2%; biomes: soul_sand_valley 93.4%, basalt_deltas 6.6%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t3.png`

### the_wailing_narrows-surface-t3-nv

Picked for: surface type 3: 19.1% of cells; biome soul_sand_valley; ground soul_soil; top soul_soil; height 38; relief over the patch 111.9 (layer 98.2); notable-block density (log) 1.2 (layer 0.8); bare rock 8% (layer 32%)
Census: sky 0.4%; near: soul_soil 48%, soul_sand 3.9%; mid: soul_sand 16.4%, soul_soil 13.2%; far: basalt 7.1%, soul_soil 2%; biomes: soul_sand_valley 93.1%, basalt_deltas 6.9%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-t3-nv.png`

### the_wailing_narrows-surface-lm1

Picked for: surface landmark: biome basalt_deltas (type soul_sand_valley); ground bedrock (type soul_soil); top lava (type soul_soil); liquid cover 65% (type 1%); notable-block density (log) 3.0 (type 0.4); overhangs 43% (type 2%)
Census: sky 0%; near: basalt 53.9%, lava 38.5%; mid: basalt 1.5%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-lm1.png`

### the_wailing_narrows-surface-lm1-nv

Picked for: surface landmark: biome basalt_deltas (type soul_sand_valley); ground bedrock (type soul_soil); top lava (type soul_soil); liquid cover 65% (type 1%); notable-block density (log) 3.0 (type 0.4); overhangs 43% (type 2%)
Census: sky 0%; near: basalt 53.9%, lava 38.5%; mid: basalt 1.5%; biomes: basalt_deltas 100%
Image: `/tmp/claude-0/w10c2/reviews/the_wailing_narrows/screens/the_wailing_narrows-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
