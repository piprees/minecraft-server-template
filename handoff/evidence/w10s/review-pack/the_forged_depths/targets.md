# the_forged_depths: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `terrain.surfaceHeight.p25` | <= 52 | 36.0 | yes | "a small brutal deep". Restated from landforms basins >= 20: the landform classifier marks basins by their height against a 256-block window, so the floor of a cut wider than about 128 blocks reads as plains; a quarter of the floor or more lies in the deep, 20 blocks or more under the rim at 72 |
| description | `surface.groundBlocksPercent.minecraft:magma_block + surface.groundBlocksPercent.minecraft:basalt + surface.groundBlocksPercent.minecraft:blackstone + surface.groundBlocksPercent.minecraft:obsidian + surface.groundBlocksPercent.minecraft:quartz_block` | >= 30 | 45.2 | yes | "the fires here never went out"; 1.21.1 magma_land, volcanic_deltas, inferno, quartz_flats |
| description | `surface.biomesPercent.adventure:magma_land + surface.biomesPercent.adventure:crimson_pinewood` | >= 35 | 69.1 | yes | 1.21.1 betternether magma_land, crimson_pinewood, crimson_glowing_woods |
| description | `biomeExpression.biomes.minecraft:crimson_forest.placementPercent.land?` | >= 60 | 99.0 | yes | "Crimson forges": the crimson forest stands on land |
| description | `atmosphere.definitionAtmosphere.ambientLight?` | <= 0.12 | 0.08 | yes | 1.21.1 ambientLight 0.08 |
| description | `vertical.layerColumnsPercent.roof` | >= 85 | 98.4 | yes | 1.21.1 type nether: a roof over the world |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 39.7 | no | place kind place ("a small brutal deep"): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 6.9 | no | place kind place: no scatter of biome specks |
| placeness | `placeness.landBiomeRegions.largestPercent` | >= 50 | 39.7 | no | place kind one small place ("a small brutal deep"): one region holds half the land |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 6.9 | no | place kind one place: no scatter of biome specks |
| placeness | `placeness.places.byPlace.adventure:crimson_pinewood.landPercent` | >= 45 | 65.7 | yes | "crimson forges ... crimson pinewood": the pinewood place holds about half the land |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 2.5 | yes | places, not perlin: no scatter of place specks |
