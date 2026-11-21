# the_nether: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.groundBlocksPercent.minecraft:netherrack + surface.groundBlocksPercent.minecraft:crimson_nylium + surface.groundBlocksPercent.minecraft:warped_nylium + surface.groundBlocksPercent.minecraft:soul_sand + surface.groundBlocksPercent.minecraft:soul_soil + surface.groundBlocksPercent.minecraft:basalt + surface.groundBlocksPercent.minecraft:blackstone + surface.groundBlocksPercent.minecraft:magma_block + surface.groundBlocksPercent.minecraft:gravel` | >= 85 | 92.0 | yes | "The nether": vanilla Nether ground throughout |
| description | `surface.biomesPercent.minecraft:warped_forest` | >= 3 | 22.2 | yes | "The nether": all five vanilla Nether biomes, warped forest included (v2 lists none, so the preset's five) |
| description | `surface.biomesPercent.minecraft:crimson_forest + surface.biomesPercent.minecraft:warped_forest` | >= 15 | 48.1 | yes | "piglin heartland": the fungal forests piglins and hoglins live in carry a real share |
| description | `surface.biomesPercent.minecraft:nether_wastes` | <= 55 | 29.6 | yes | "The nether": a meeting of the five biomes, not one waste |
| description | `vertical.layerColumnsPercent.roof` | >= 90 | 96.8 | yes | "The nether": a ceiling over every column |
| description | `surface.fluidCoverPercent.lava` | between [8, 30] | 26.9 | yes | "The nether": the lava sea is present but does not drown the floor |
| description | `vegetation.treesPerHectareByLayer.surface` | >= 15 | 51.3 | yes | "piglin heartland": huge fungi stand in the forests |
| description | `terrain.reliefMedian` | >= 40 | 102.0 | yes | "The nether": cliffs and lava-sea basins, not a flat floor |
| description | `placeness.landBiomeRegions.top3Percent` | >= 60 | 70.0 | yes | place kind meeting: five Nether biomes in a few large regions |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 1.4 | yes | place kind meeting: no scatter of biome specks |
| placeness | `surface.groundBlocksPercent.minecraft:netherrack + surface.groundBlocksPercent.minecraft:crimson_nylium + surface.groundBlocksPercent.minecraft:warped_nylium + surface.groundBlocksPercent.minecraft:soul_sand + surface.groundBlocksPercent.minecraft:soul_soil + surface.groundBlocksPercent.minecraft:basalt + surface.groundBlocksPercent.minecraft:blackstone + surface.groundBlocksPercent.minecraft:magma_block + surface.groundBlocksPercent.minecraft:gravel + surface.groundBlocksPercent.minecraft:quartz_block` | >= 85 | 92.8 | yes | "The nether": Nether ground throughout, quartz_flats' quartz included (1.21.1 spawnFilter names quartz_flats) |
| placeness | `placeness.landBiomeRegions.top3Percent` | >= 60 | 70.0 | yes | place kind meeting of the Nether's biomes |
| placeness | `placeness.landBiomeRegions.major` | <= 8 | 6.0 | yes | place kind meeting |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 1.4 | yes | place kind meeting |
| placeness | `placeness.biomesPercent.minecraft:crimson_forest` | >= 15 | 25.0 | yes | "piglin heartland": the crimson forest carries weight |
