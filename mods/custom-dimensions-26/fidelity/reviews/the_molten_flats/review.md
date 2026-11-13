# Review packet: the_molten_flats

## 1.21.1 intent

Source: `config/custom-dimensions/dimensions/the_molten_flats.json`

> Industrial lava flats — piglin pipelines and reactors built over the melt. The infrastructure still runs, still pumps, still processes. The workers are gone. The machines don't care.

Biomes: incendium:ash_barrens, minecraft:nether_wastes, minecraft:basalt_deltas, incendium:infernal_dunes, regions_unexplored:inferno, incendium:volcanic_deltas

## v2 definition

Source: `mods/custom-dimensions-26/definitions/the_molten_flats.json`

> Industrial lava flats — piglin pipelines and reactors built over the melt. The infrastructure still runs, still pumps, still processes. The workers are gone. The machines don't care.

Biomes: adventure:magma_land, adventure:ash_barrens, minecraft:basalt_deltas, adventure:gravel_desert, minecraft:nether_wastes

Terrain: `{"base": "roofed", "params": {"floorHeight": 33, "floorRelief": 8, "ceilingGap": 80, "roughness": 0.05, "flats": 0.5}, "scales": {"climate": 1.5}}`; generator `{"type": "noise", "preset": "nether"}`

Zones: none

## Survey

Dossier: `runs/r11/full/the_molten_flats/dossier.json`; layer maps in `runs/r11/full/the_molten_flats/layers`

## Summary

- Top blocks seen from the sky: magma_block 30.4%, lava 24%, tuff 15.9%, basalt 8%.
- Surface biomes: adventure:magma_land 56.3%, adventure:ash_barrens 20%, basalt_deltas 12.1%, adventure:gravel_desert 9.8%, nether_wastes 1.9%.
- Ground blocks: magma_block 38.1%, netherrack 18%, tuff 15.9%, gravel 9.9%.
- Liquid cover 0% water, 24% lava, 0% other; snow or ice 0% (a snowing climate over 0%); tree canopy 0%; bare rock 28.7%; no ground (void) 0%.
- Trees: 0 per ha; ground plants other 5.3%, bush 0%, mushroom 0%.
- Surface height p5 26, median 32, p95 44, max 119; median relief per patch 101 blocks; slopes under 15° 60.8%, over 45° 18.5%, cliffs 8%; above the clouds 0%, below sea level 25.7%.
- Underground: 8.8% open, 2.9% liquid; cave biomes adventure:magma_land 71.5%, adventure:ash_barrens 13.4%, basalt_deltas 10.9%, adventure:gravel_desert 3.5%.
- Notable clusters, fewest first: red_mushroom 2 clusters, spacing n/a; nether_bricks 4 clusters, spacing 18.2; glowstone 23 clusters, spacing 24.2; nether_brick_fence 61 clusters, spacing 2; smooth_basalt 131 clusters, spacing 4; soul_soil 181 clusters, spacing 3.5.
- 5 of 5 placed biomes are expressed; 0 listed biomes were not found.
- Render against world at 900 columns: height within 2 blocks 83.8%, biome match 100%.
- Landforms over the border (noise render): plains 88.2%, hills 0%, mountains 0%, plateaus 0%, basins 0%, islands 0.3%, water 11.5%, void 0%.
- Placeness: biome regions 1.7/km² (6 major, largest 54.6%), landform regions 3.1/km² (largest 88.2%), dominant place 88.2% of the border.

## Layers

- Columns by class: void 0%, roofed 100%, stacked 0%, overhang 0%, cave 0%, single 0%.
- Columns with each layer: sky 0.1%, surface 100%, underground 98%, roof 100%.
- Overhangs in 12.3% of columns, stacked layers in 0.1%, cave pockets in 3%.

## Places

- **surface** (225 cells, 5 types, silhouette 0.501):
  - type 1: 40.4% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 34; ground plants 12% (layer 5%); bare rock 2% (layer 29%) — camera [-439.5,33,-455.5]
  - type 2: 20.4% of cells; biome adventure:magma_land; ground netherrack; top lava; height 34; neighbour step 4.4 (layer 2.3); relief over 16 blocks 85.5 (layer 64.5); ground plants 2% (layer 5%); liquid cover 68% (layer 24%); overhangs 23% (layer 12%) — camera [536.5,32,-199.5]
  - type 3: 18.7% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 35; relief over the patch 108.5 (layer 101.5); ground plants 0% (layer 5%); liquid cover 3% (layer 24%); bare rock 91% (layer 29%) — camera [408.5,33,-679.5]
  - type 4: 11.1% of cells; biome basalt_deltas; ground basalt; top basalt; height 42; relief over 16 blocks 45.2 (layer 64.5); relief over the patch 91.4 (layer 101.5); ground plants 0% (layer 5%); bare rock 73% (layer 29%) — camera [552.5,46,969.5]
  - type 5: 9.3% of cells; biome adventure:gravel_desert; ground gravel; top gravel; height 32; ground plants 0% (layer 5%); bare rock 0% (layer 29%) — camera [-999.5,33,968.5]
  - landmark at [824,-472]: top netherrack (type lava); neighbour step 15.8 (type 1.8); height 63.3 (type 33.6); overhangs 40% (type 7%)
  - landmark at [472,232]: overhangs 81% (type 1%); cave pockets 25% (type 0%); neighbour step 10.0 (type 0.9)
  - landmark at [152,-904]: biome adventure:ash_barrens (type adventure:magma_land); ground smooth_basalt (type netherrack); neighbour step 18.4 (type 1.8); height 50.0 (type 33.6); notable-block density (log) 2.1 (type 0.5)
  - landmark at [936,200]: neighbour step 13.6 (type 1.5); relief over the patch 118.0 (type 86.0); overhangs 38% (type 0%)
  - landmark at [376,-696]: biome adventure:ash_barrens (type adventure:magma_land); ground bedrock (type netherrack); notable-block density (log) 3.8 (type 0.5); height 13.4 (type 33.6); relief over 16 blocks 34.0 (type 91.0)
- **sky**: no cells.
- **underground** (225 cells, 2 types, silhouette 0.51):
  - type 1: 87.6% of cells; notableId blackstone; biome adventure:magma_land; height 34 — camera [-987.5,36.88,-782.5]
  - type 2: 12.4% of cells; notableId blackstone; biome adventure:magma_land; height 48; neighbour step 8.0 (layer 2.2); relief over 16 blocks 90.5 (layer 62.4); cave air 33% (layer 6%) — camera [829.5,31.88,-453.5]
  - landmark at [920,184]: notableId lava (type blackstone); biome basalt_deltas (type adventure:magma_land); layer present in 41% (type 100%); notable-block density (log) 4.5 (type 0.8); cave liquid 30% (type 0%)
  - landmark at [472,232]: notableId lava (type blackstone); notable-block density (log) 3.4 (type 0.8); cave liquid 29% (type 0%); cave pockets 27% (type 0%)
  - landmark at [936,200]: notableId lava (type blackstone); biome basalt_deltas (type adventure:magma_land); neighbour step 13.6 (type 7.3); notable-block density (log) 2.0 (type 0.8); height 54.4 (type 44.5)
  - landmark at [-968,952]: notableId lava (type blackstone); cave liquid 34% (type 0%); cave pockets 23% (type 0%); notable-block density (log) 2.3 (type 0.6)
  - landmark at [984,-776]: notableId lava (type blackstone); cave pockets 47% (type 0%); notable-block density (log) 3.8 (type 0.6); cave liquid 27% (type 0%)
- **roof** (225 cells, 2 types, silhouette 0.336):
  - type 1: 70.2% of cells; notableId nether_quartz_ore; height 111; neighbour step 0.1 (layer 0.2) — camera [536.5,105.88,-231.5]
  - type 2: 29.8% of cells; notableId nether_quartz_ore; height 114; neighbour step 0.7 (layer 0.2); relief over 16 blocks 10.3 (layer 6.0) — camera [776.5,115.88,-487.5]
  - landmark at [936,168]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 1.6 (type 0.1); neighbour step 1.1 (type 0.6); height 110.9 (type 113.4)
  - landmark at [936,200]: notableId blackstone (type nether_quartz_ore); neighbour step 1.7 (type 0.6); notable-block density (log) 1.0 (type 0.1); height 115.5 (type 113.4)
  - landmark at [168,-904]: notableId smooth_basalt (type nether_quartz_ore); notable-block density (log) 2.4 (type 0.1); relief over 16 blocks 12.0 (type 4.0); height 109.8 (type 111.0)
  - landmark at [952,184]: notableId blackstone (type nether_quartz_ore); notable-block density (log) 1.5 (type 0.1); relief over 16 blocks 8.0 (type 4.0); neighbour step 0.4 (type 0.1)

## Biome expression

5 of 5 placed biomes are expressed.

- adventure:magma_land: expressed, 32418 columns; placement land 71.6%, lava 28.3%, roofed 99.9%, sky 0.1%; snow 0%, canopy 0%, trees 0/ha; top magma_block 52.8%, lava 28.3%, fire 9.4%
- adventure:ash_barrens: expressed, 11521 columns; placement land 87.3%, lava 12.7%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top tuff 79.3%, lava 12.7%, netherrack 2.1%
- minecraft:basalt_deltas: expressed, 6974 columns; placement land 80.3%, lava 19.5%, roofed 99.8%, sky 0.2%; snow 0%, canopy 0%, trees 0/ha; top basalt 62.9%, lava 19.5%, blackstone 6.7%
- adventure:gravel_desert: expressed, 5618 columns; placement land 70.7%, lava 29.3%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top gravel 68%, lava 29.3%, bone_block 1.5%
- minecraft:nether_wastes: expressed, 1104 columns; placement land 85.1%, lava 14.9%, roofed 100%; snow 0%, canopy 0%, trees 0/ha; top netherrack 74.4%, lava 14.9%, magma_block 5.3%


## Facts

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

### 1.21.1 biomes (O rows)

| row | 1.21.1 biome | v2 id | surface % | columns | state | flag reason |
|---|---|---|---|---|---|---|
| O1 | incendium:ash_barrens | adventure:ash_barrens | 20.0 | 11521 | expressed | - |
| O2 | minecraft:nether_wastes | minecraft:nether_wastes | 1.9 | 1104 | expressed | - |
| O3 | minecraft:basalt_deltas | minecraft:basalt_deltas | 12.1 | 6974 | expressed | - |
| O4 | incendium:infernal_dunes | adventure:gravel_desert | 9.8 | 5618 | expressed | - |
| O5 | regions_unexplored:inferno | adventure:magma_land | 56.3 | 32418 | expressed | - |
| O6 | incendium:volcanic_deltas | adventure:magma_land | 56.3 | 32418 | expressed | - |

### Placed v2 biomes no 1.21.1 biome maps to (V rows)

None.

### Measures (M rows) beside the 1.21.1 settings

| key | dossier | 1.21.1 |
|---|---|---|
| M:topBiomes | adventure:magma_land 56.3%, adventure:ash_barrens 20.0%, minecraft:basalt_deltas 12.1% | type `nether` |
| M:snow | 0.0% (`surface.snowIcePercent`) | noiseSettings `None` |
| M:water | 0.0% (`surface.fluidCoverPercent.water`) | settingsOverrides `null` |
| M:lava | 24.0% (`surface.fluidCoverPercent.lava`) | |
| M:void | 0.0% (`surface.voidPercent`) | |
| M:canopy | 0.0% (`surface.canopyPercent`) | |
| M:relief | median 101.0 blocks a patch; height {"p5": 26.0, "p25": 31.0, "p50": 32.0, "p75": 33.0, "p95": 44.0, "max": 119.0} (`terrain`) | |
| M:layers | {"sky": 0.1, "surface": 100.0, "underground": 98.0, "roof": 100.0} (`vertical.layerColumnsPercent`) | environment `null` |
| M:classes | {"void": 0.0, "roofed": 100.0, "stacked": 0.0, "overhang": 0.0, "cave": 0.0, "single": 0.0} (`vertical.columnClassPercent`); ceiling True, skylight False | |

### Scorecard (S rows)

- S:a — surface traceable to the 1.21.1 biomes (of the columns that have ground): 100.0%
- S:b — 1.21.1 biomes expressed: 100.0%
- S:c — v2 biomes placed but not expressed: 0
- S:d — layers the 1.21.1 type implies: surface present (100.0% of columns), roof present (100.0% of columns)

## Shots

### the_molten_flats-far

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 13.6%; near: magma_block 6.5%, lava 5%; mid: magma_block 20.6%, lava 5.1%; far: netherrack 27.5%, magma_block 6.5%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-far.png`

### the_molten_flats-far-nv

Picked for: vista: summit of the coarse map, facing its largest drop
Census: sky 13.6%; near: magma_block 6.5%, lava 5%; mid: magma_block 20.6%, lava 5.1%; far: netherrack 27.5%, magma_block 6.5%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-far-nv.png`

### the_molten_flats-surface-t1

Picked for: surface type 1: 40.4% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 34; ground plants 12% (layer 5%); bare rock 2% (layer 29%)
Census: sky 3.2%; unloaded 12.8%; near: magma_block 45.2%, fire 6.8%; mid: magma_block 2.2%, fire 1.3%; far: netherrack 27.3%; biomes: adventure:magma_land 99.9%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t1.png`

### the_molten_flats-surface-t1-nv

Picked for: surface type 1: 40.4% of cells; biome adventure:magma_land; ground magma_block; top magma_block; height 34; ground plants 12% (layer 5%); bare rock 2% (layer 29%)
Census: sky 4.2%; unloaded 10.7%; near: magma_block 45.2%, fire 6.8%; mid: magma_block 2.2%, fire 1.3%; far: netherrack 28.2%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t1-nv.png`

### the_molten_flats-underground-t1

Picked for: underground type 1: 87.6% of cells; notableId blackstone; biome adventure:magma_land; height 34
Census: sky 12.8%; near: tuff 38.8%, basalt 5.5%; mid: tuff 7.2%, basalt 3%; far: netherrack 21.7%; biomes: adventure:ash_barrens 87.3%, adventure:magma_land 12.7%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-underground-t1.png`

### the_molten_flats-underground-t1-nv

Picked for: underground type 1: 87.6% of cells; notableId blackstone; biome adventure:magma_land; height 34
Census: sky 12.8%; near: tuff 38.8%, basalt 5.5%; mid: tuff 7.2%, basalt 3%; far: netherrack 21.7%; biomes: adventure:ash_barrens 87.3%, adventure:magma_land 12.7%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-underground-t1-nv.png`

### the_molten_flats-roof-t1

Picked for: roof type 1: 70.2% of cells; notableId nether_quartz_ore; height 111; neighbour step 0.1 (layer 0.2)
Census: sky 1.9%; unloaded 5.2%; near: netherrack 76.3%, glowstone 3.5%; mid: netherrack 7.6%, glowstone 1.3%; far: netherrack 1%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-roof-t1.png`

### the_molten_flats-roof-t1-nv

Picked for: roof type 1: 70.2% of cells; notableId nether_quartz_ore; height 111; neighbour step 0.1 (layer 0.2)
Census: sky 2.7%; unloaded 4.4%; near: netherrack 76.3%, glowstone 3.5%; mid: netherrack 7.6%, glowstone 1.3%; far: netherrack 1%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-roof-t1-nv.png`

### the_molten_flats-surface-t2

Picked for: surface type 2: 20.4% of cells; biome adventure:magma_land; ground netherrack; top lava; height 34; neighbour step 4.4 (layer 2.3); relief over 16 blocks 85.5 (layer 64.5); ground plants 2% (layer 5%); liquid cover 68% (layer 24%); overhangs 23% (layer 12%)
Census: sky 12.9%; near: lava 44%, magma_block 4.9%; mid: lava 3.1%; far: netherrack 27.7%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t2.png`

### the_molten_flats-surface-t2-nv

Picked for: surface type 2: 20.4% of cells; biome adventure:magma_land; ground netherrack; top lava; height 34; neighbour step 4.4 (layer 2.3); relief over 16 blocks 85.5 (layer 64.5); ground plants 2% (layer 5%); liquid cover 68% (layer 24%); overhangs 23% (layer 12%)
Census: sky 12.6%; near: lava 44.4%, magma_block 4.9%; mid: lava 3.6%; far: netherrack 27.1%; biomes: adventure:magma_land 100%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t2-nv.png`

### the_molten_flats-surface-t3

Picked for: surface type 3: 18.7% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 35; relief over the patch 108.5 (layer 101.5); ground plants 0% (layer 5%); liquid cover 3% (layer 24%); bare rock 91% (layer 29%)
Census: sky 7.3%; near: tuff 49.1%, basalt 9.2%; far: netherrack 27.2%, lava 1%; biomes: adventure:ash_barrens 76.9%, adventure:magma_land 23.1%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t3.png`

### the_molten_flats-surface-t3-nv

Picked for: surface type 3: 18.7% of cells; biome adventure:ash_barrens; ground tuff; top tuff; height 35; relief over the patch 108.5 (layer 101.5); ground plants 0% (layer 5%); liquid cover 3% (layer 24%); bare rock 91% (layer 29%)
Census: sky 7.3%; near: tuff 49.1%, basalt 9.2%; far: netherrack 27.2%, lava 1%; biomes: adventure:ash_barrens 76.9%, adventure:magma_land 23.1%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t3-nv.png`

### the_molten_flats-surface-t4

Picked for: surface type 4: 11.1% of cells; biome basalt_deltas; ground basalt; top basalt; height 42; relief over 16 blocks 45.2 (layer 64.5); relief over the patch 91.4 (layer 101.5); ground plants 0% (layer 5%); bare rock 73% (layer 29%)
Census: sky 0%; near: basalt 56.9%, blackstone 17.1%; far: basalt 6.6%; biomes: basalt_deltas 99.3%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t4.png`

### the_molten_flats-surface-t4-nv

Picked for: surface type 4: 11.1% of cells; biome basalt_deltas; ground basalt; top basalt; height 42; relief over 16 blocks 45.2 (layer 64.5); relief over the patch 91.4 (layer 101.5); ground plants 0% (layer 5%); bare rock 73% (layer 29%)
Census: sky 0%; near: basalt 56.9%, blackstone 17.1%; far: basalt 6.6%; biomes: basalt_deltas 99.3%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-t4-nv.png`

### the_molten_flats-surface-lm1

Picked for: surface landmark: top netherrack (type lava); neighbour step 15.8 (type 1.8); height 63.3 (type 33.6); overhangs 40% (type 7%)
Census: sky 3%; unloaded 10.7%; near: magma_block 37.6%, fire 10.5%; mid: lava 1.8%, magma_block 1.3%; far: netherrack 27.9%; biomes: adventure:magma_land 99.2%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-lm1.png`

### the_molten_flats-surface-lm1-nv

Picked for: surface landmark: top netherrack (type lava); neighbour step 15.8 (type 1.8); height 63.3 (type 33.6); overhangs 40% (type 7%)
Census: sky 4.8%; unloaded 8.2%; near: magma_block 37.6%, fire 10.5%; mid: lava 1.8%, magma_block 1.3%; far: netherrack 28.4%, basalt 1%; biomes: adventure:magma_land 98.9%
Image: `/tmp/claude-0/w10c2/reviews/the_molten_flats/screens/the_molten_flats-surface-lm1-nv.png`

## Review questions

Every factual statement in an answer cites a facts row (O<n>, V<n>, M:<key>, S:<a-d>) or a dossier key (for example `surface.snowIcePercent`); a statement nothing here backs is left out, not guessed. A biome is absent only when its row says so: a placed, unexpressed biome is present with a flag, not absent.

1. Taken together, do the shots show the world's dominant character as the dossier measures it?
2. Is every claim in the captions and descriptions supported by the frame census or the dossier? List the unsupported ones.
3. Which elements of the 1.21.1 description or biome list are absent from the dossier? Quote the O rows that are not expressed.
4. Which measured elements contradict the intent (for example, snow expected but snow cover under 5%)?
5. Fidelity to the 1.21.1 intent, 0-5, with one line why, citing the scorecard.
