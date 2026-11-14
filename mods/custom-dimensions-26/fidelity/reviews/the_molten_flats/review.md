# Review packet: the_molten_flats

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_molten_flats.json`

> Industrial lava flats — piglin pipelines and reactors built over the melt. The infrastructure still runs, still pumps, still processes. The workers are gone. The machines don't care.

Biomes: incendium:ash_barrens, minecraft:nether_wastes, minecraft:basalt_deltas, incendium:infernal_dunes, regions_unexplored:inferno, incendium:volcanic_deltas

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_molten_flats.json`

> Industrial lava flats — piglin pipelines and reactors built over the melt. The infrastructure still runs, still pumps, still processes. The workers are gone. The machines don't care.

Biomes: adventure:magma_land, adventure:ash_barrens, minecraft:basalt_deltas, adventure:gravel_desert, minecraft:nether_wastes

Terrain: `{"base": "roofed", "params": {"floorHeight": 36, "floorRelief": 8, "ceilingGap": 80, "roughness": 0, "flats": 0.65}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `/tmp/claude-0/w10r/runs/r3/full/the_molten_flats/dossier.json`; layer maps in `/tmp/claude-0/w10r/runs/r3/full/the_molten_flats/layers`

## Summary

- Top blocks seen from the sky: blackstone 39.9%, lava 16.8%, tuff 16.6%, gravel 9.2%.
- Surface biomes: adventure:magma_land 54.1%, adventure:ash_barrens 23.1%, basalt_deltas 11.6%, adventure:gravel_desert 9.4%, nether_wastes 1.8%.
- Ground blocks: blackstone 40.9%, tuff 16.6%, netherrack 10.6%, gravel 9.3%.
- Liquid cover 0% water, 16.8% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 66.4%; no ground (void) 0%.
- Trees: 7.8 per ha (stripped_crimson_stem 7.8/ha, mean spacing 4.1 blocks); ground plants other 0.1%, mushroom 0%, bush 0%.
- Surface height p5 28, median 35, p95 41, max 119; median relief per patch 101 blocks; slopes under 15° 75.4%, over 45° 11.9%, cliffs 6.9%; above the clouds 0%, below sea level 12.7%.
- Underground: 7% open, 3.1% liquid; cave biomes adventure:magma_land 71.7%, adventure:ash_barrens 15.9%, basalt_deltas 11.3%, adventure:gravel_desert 1.1%.
- Notable clusters, fewest first: brown_mushroom 6 clusters, spacing 2.5; coarse_dirt 6 clusters, spacing 6.5; dead_bush 7 clusters, spacing 3.9; red_mushroom 9 clusters, spacing 3.8; glowstone 37 clusters, spacing 21.8; basalt 138 clusters, spacing 3.4.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 936 columns: height within 2 blocks 87.8%, biome match 100%.
- Landforms over the border (noise render): plains 99%, hills 0%, mountains 0%, plateaus 0%, basins 0%, islands 0%, water 1%, void 0%.
- Placeness: biome regions 1.7/km² (6 major, largest 54.6%), landform regions 0.2/km² (largest 99.0%), dominant place 99.0% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0%, surface 100%, underground 98.1%, roof 100%.
- Overhangs in 13.7% of columns, stacked layers in 0%, cave pockets in 4.1%.

## Places

- **surface** (234 cells, 7 types, silhouette 0.465):
  - type 1: 44.0% of cells; biome adventure:magma_land; ground blackstone; top blackstone; height 37; bare rock 87% (layer 66%) — camera [-439.5,36,-471.5]
  - type 2: 17.5% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 36; relief over the patch 109.7 (layer 100.5); liquid cover 3% (layer 17%); bare rock 94% (layer 66%) — camera [408.5,36,-695.5]
  - type 3: 11.1% of cells; biome adventure:magma_land; ground magma_block; top lava; height 35; neighbour step 3.5 (layer 1.9); liquid cover 50% (layer 17%); bare rock 16% (layer 66%) — camera [-375.5,32,472.5]
  - type 4: 9.4% of cells; biome adventure:gravel_desert; ground gravel; top gravel; height 35; neighbour step 0.7 (layer 1.9); liquid cover 0% (layer 17%); bare rock 2% (layer 66%) — camera [-967.5,36,968.5]
  - type 5: 7.3% of cells; biome basalt_deltas; ground basalt; top basalt; height 39; relief over the patch 92.4 (layer 100.5) — camera [568.5,40,984.5]
  - type 6: 6.0% of cells; biome adventure:ash_barrens; ground smooth_basalt; top lava; height 25; relief over the patch 115.4 (layer 100.5); liquid cover 87% (layer 17%); bare rock 12% (layer 66%) — camera [-599.5,32,-679.5]
  - type 7: 4.7% of cells; biome adventure:magma_land; ground netherrack; top netherrack; height 43; relief over 16 blocks 36.3 (layer 58.2); relief over the patch 85.6 (layer 100.5); trees per cell 1.5 (layer 0.1); canopy 0% (layer 0%); ground plants 1% (layer 0%); overhangs 51% (layer 14%) — camera [25.5,37,-249.5]
  - landmark at [808,-488]: top magma_block (type lava); height 71.9 (type 30.1); neighbour step 11.0 (type 2.2); overhangs 61% (type 3%)
  - landmark at [920,184]: biome basalt_deltas (type adventure:ash_barrens); ground bedrock (type smooth_basalt); neighbour step 9.9 (type 1.4); overhangs 40% (type 1%); liquid cover 63% (type 100%)
  - landmark at [472,232]: overhangs 81% (type 1%); cave pockets 29% (type 0%); height 57.7 (type 35.3)
  - landmark at [936,200]: biome basalt_deltas (type adventure:magma_land); neighbour step 8.0 (type 0.7); overhangs 45% (type 1%); height 50.4 (type 35.3)
  - landmark at [-280,8]: ground bedrock (type magma_block); notable-block density (log) 4.3 (type 0.4); height 18.2 (type 30.1); overhangs 30% (type 3%)
- **sky**: no cells.
- **underground** (234 cells, 8 types, silhouette 0.309):
  - type 1: 29.5% of cells; notableId blackstone; biome adventure:ash_barrens; height 35; relief over 16 blocks 83.4 (layer 56.0) — camera [-561.5,35.88,433.5]
  - type 2: 23.1% of cells; notableId blackstone; height 34; neighbour step 0.3 (layer 1.9); relief over 16 blocks 3.8 (layer 56.0); cave air 0% (layer 5%)
  - type 3: 11.1% of cells; notableId blackstone; biome adventure:magma_land; height 39; trees per cell 0.8 (layer 0.1) — camera [21.5,61.88,-267.5]
  - type 4: 8.5% of cells; notableId blackstone; biome adventure:magma_land; height 49; neighbour step 6.6 (layer 1.9); relief over 16 blocks 89.1 (layer 56.0); cave air 32% (layer 5%) — camera [-55.5,35.88,13.5]
  - type 5: 7.7% of cells; notableId magma_block; biome adventure:magma_land; height 35; relief over 16 blocks 5.6 (layer 56.0); trees per cell 0.6 (layer 0.1); notable-block density (log) 1.3 (layer 0.9); cave air 0% (layer 5%) — camera [6.5,36.88,-254.5]
  - type 6: 7.3% of cells; notableId magma_block; biome adventure:ash_barrens; height 36; relief over 16 blocks 83.6 (layer 56.0) — camera [93.5,37.88,621.5]
  - type 7: 7.3% of cells; notableId nether_quartz_ore; biome basalt_deltas; height 37; layer present in 93% (layer 98%); notable-block density (log) 0.4 (layer 0.9) — camera [570.5,38.88,949.5]
  - type 8: 5.6% of cells; notableId lava; biome adventure:ash_barrens; height 39; neighbour step 4.4 (layer 1.9); notable-block density (log) 2.2 (layer 0.9); cave air 12% (layer 5%); cave liquid 14% (layer 4%); cave pockets 19% (layer 4%) — camera [-242.5,35.88,-915.5]
  - landmark at [920,184]: layer present in 37% (type 100%); neighbour step 12.5 (type 1.8); notable-block density (log) 4.6 (type 2.0)
  - landmark at [472,232]: notableId lava (type blackstone); notable-block density (log) 3.3 (type 0.5); cave liquid 31% (type 0%); cave pockets 30% (type 3%)
  - landmark at [152,-904]: biome adventure:ash_barrens (type none); neighbour step 16.5 (type 1.8); cave air 38% (type 10%); height 51.5 (type 38.3)
  - landmark at [376,-712]: notableId nether_gold_ore (type blackstone); cave liquid 45% (type 0%); layer present in 59% (type 100%); height 27.0 (type 35.0)
  - landmark at [984,-776]: biome adventure:ash_barrens (type none); cave pockets 50% (type 23%); notable-block density (log) 3.8 (type 2.0); cave liquid 23% (type 10%)
- **roof** (234 cells, 7 types, silhouette 0.408):
  - type 1: 31.6% of cells; notableId nether_quartz_ore; height 113 — camera [-391.5,107.88,456.5]
  - type 2: 13.7% of cells; notableId basalt; height 116; neighbour step 0.7 (layer 0.2); relief over 16 blocks 8.8 (layer 4.9) — camera [88.5,106.88,600.5]
  - type 3: 13.2% of cells; notableId bone_block; height 114 — camera [-567.5,108.88,472.5]
  - type 4: 12.0% of cells; notableId nether_gold_ore; height 113 — camera [616.5,107.88,232.5]
  - type 5: 10.7% of cells; notableId lava; height 113 — camera [-567.5,107.88,-695.5]
  - type 6: 10.3% of cells; notableId glowstone; height 113; relief over 16 blocks 7.9 (layer 4.9) — camera [408.5,107.88,-679.5]
  - type 7: 8.5% of cells; notableId smooth_basalt; height 113; neighbour step 0.0 (layer 0.2); relief over 16 blocks 1.4 (layer 4.9); notable-block density (log) 0.5 (layer 0.2) — camera [632.5,107.88,248.5]
  - landmark at [168,-904]: notableId smooth_basalt (type glowstone); notable-block density (log) 2.4 (type 0.1); relief over 16 blocks 15.0 (type 7.0); height 111.6 (type 113.1)
  - landmark at [936,200]: notableId basalt (type none); neighbour step 1.4 (type 0.6); notable-block density (log) 1.1 (type 0.0); relief over 16 blocks 10.0 (type 9.0)
  - landmark at [936,168]: notableId basalt (type none); notable-block density (log) 2.9 (type 0.0); height 113.2 (type 116.3); relief over 16 blocks 11.0 (type 9.0)
  - landmark at [920,200]: notableId basalt (type none); notable-block density (log) 1.1 (type 0.0); neighbour step 1.2 (type 0.6); height 116.0 (type 116.3)
  - landmark at [680,936]: notableId basalt (type none); notable-block density (log) 3.5 (type 0.0); height 112.0 (type 112.9); relief over 16 blocks 0.0 (type 1.0)

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:magma_land: expressed, 32419 columns; placement land 83.3%, lava 16.6%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 5.9/ha; top blackstone 65%, lava 16.6%, netherrack 6.5%
- adventure:ash_barrens: expressed, 13825 columns; placement land 76.5%, lava 23.5%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top tuff 72.1%, lava 23.5%, netherrack 1.6%
- minecraft:basalt_deltas: expressed, 6960 columns; placement land 81.9%, lava 18.1%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top blackstone 40%, basalt 37.8%, lava 18.1%
- adventure:gravel_desert: expressed, 5616 columns; placement land 99.6%, lava 0.4%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 96.6%, bone_block 2.4%, lava 0.4%
- minecraft:nether_wastes: expressed, 1104 columns; placement land 86.8%, lava 13.2%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 77%, lava 13.2%, gravel 3.9%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | incendium:ash_barrens | adventure:ash_barrens | 23.1 | 13825 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 1.8 | 1104 | expressed | - |
| O3 | minecraft:basalt_deltas | minecraft:basalt_deltas | 11.6 | 6960 | expressed | - |
| O4 | incendium:infernal_dunes | adventure:gravel_desert | 9.4 | 5616 | expressed | - |
| O5 | regions_unexplored:inferno | adventure:magma_land | 54.1 | 32419 | expressed | - |
| O6 | incendium:volcanic_deltas | adventure:magma_land | 54.1 | 32419 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:magma_land 54.1%, adventure:ash_barrens 23.1%, minecraft:basalt_deltas 11.6% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 16.8% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 101.0 blocks a patch; height {"p5": 28.0, "p25": 35.0, "p50": 35.0, "p75": 35.0, "p95": 41.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 0.0, "surface": 100.0, "underground": 98.1, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_molten_flats-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 12.7%; near: blackstone 2.8%, lava 1.8%; mid: blackstone 27.6%, lava 7.6%; far: netherrack 27.5%, blackstone 8%; biomes: adventure:magma_land 96.4%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-far.png`

### the_molten_flats-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 12.7%; near: blackstone 2.8%, lava 1.8%; mid: blackstone 27.6%, lava 7.6%; far: netherrack 27.5%, blackstone 8%; biomes: adventure:magma_land 96.4%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-far-nv.png`

### the_molten_flats-surface-t1

Picked for: surface type 1: 44.0% of cells; biome adventure:magma_land; ground blackstone; top blackstone; height 37; bare rock 87% (layer 66%)
Census: sky 8.9%; unloaded 5.1%; near: blackstone 52%; mid: blackstone 3.6%; far: netherrack 28.8%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-surface-t1.png`

### the_molten_flats-surface-t1-nv

Picked for: surface type 1: 44.0% of cells; biome adventure:magma_land; ground blackstone; top blackstone; height 37; bare rock 87% (layer 66%)
Census: sky 10.4%; unloaded 3.4%; near: blackstone 52%; mid: blackstone 3.6%; far: netherrack 29.1%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-surface-t1-nv.png`

### the_molten_flats-underground-t1

Picked for: underground type 1: 29.5% of cells; notableId blackstone; biome adventure:ash_barrens; height 35; relief over 16 blocks 83.4 (layer 56.0)
Census: sky 0%; unloaded 17.1%; near: blackstone 57.2%, bone_block 2.7%; mid: bone_block 2.4%, gravel 1.1%; far: netherrack 16.6%; biomes: adventure:magma_land 73.4%, adventure:gravel_desert 22.8%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-underground-t1.png`

### the_molten_flats-underground-t1-nv

Picked for: underground type 1: 29.5% of cells; notableId blackstone; biome adventure:ash_barrens; height 35; relief over 16 blocks 83.4 (layer 56.0)
Census: sky 0%; unloaded 15.7%; near: blackstone 57.2%, bone_block 2.7%; mid: bone_block 2.4%, gravel 1.1%; far: netherrack 17.9%; biomes: adventure:magma_land 72.6%, adventure:gravel_desert 23.1%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-underground-t1-nv.png`

### the_molten_flats-roof-t1

Picked for: roof type 1: 31.6% of cells; notableId nether_quartz_ore; height 113
Census: sky 5.3%; unloaded 2.3%; near: netherrack 81.2%; mid: netherrack 6.5%, glowstone 1%; far: netherrack 1.2%, glowstone 1%; biomes: adventure:magma_land 99.5%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-roof-t1.png`

### the_molten_flats-roof-t1-nv

Picked for: roof type 1: 31.6% of cells; notableId nether_quartz_ore; height 113
Census: sky 6.8%; unloaded 0.8%; near: netherrack 81.2%; mid: netherrack 6.5%, glowstone 1%; far: netherrack 1.2%, glowstone 1%; biomes: adventure:magma_land 99.5%
Image: `/tmp/claude-0/w10r/reviews/the_molten_flats/screens/the_molten_flats-roof-t1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
