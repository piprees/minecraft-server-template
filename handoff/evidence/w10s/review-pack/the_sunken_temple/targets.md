# the_sunken_temple: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.fluidCoverPercent.water` | between [35, 70] | 56.7 | yes | "drowned skyland basins", "where the water pooled between paradise islands" |
| description | `surface.waterDepth.shallow1To4Percent + surface.waterDepth.over12Percent` | >= 50 | 85.6 | yes | "half-submerged architecture": basins with measured depth (shallow shelves and deep pools) |
| description | `surface.biomesPercent.minecraft:warm_ocean + surface.biomesPercent.minecraft:lukewarm_ocean + surface.biomesPercent.minecraft:deep_lukewarm_ocean + surface.biomesPercent.minecraft:beach` | >= 30 | 42.7 | yes | 1.21.1 biomes warm_ocean, deep_warm_ocean, lukewarm_ocean, beach |
| description | `surface.biomesPercent.minecraft:jungle + surface.biomesPercent.adventure:sky_highlands` | >= 20 | 34.5 | yes | 1.21.1 biomes jungle, highlands: the paradise islands |
| description | `surface.biomesPercent.adventure:hanging_gardens + surface.biomesPercent.adventure:desert_oasis` | <= 10 | 10.5 | no | 1.21.1 list has no underside garden or desert oasis |
| description | `surface.voidPercent` | <= 15 | 5.9 | yes | "basins": the water is held, not falling into the void |
| description | `surface.snowIcePercent` | <= 1 | 0.0 | yes | warm drowned basins |
| description | `placeness.landBiomeRegions.top3Percent` | >= 60 | 16.9 | no | place kind meeting (islands and the water between): the largest three regions hold most of the land |
| description | `placeness.landBiomeRegions.major` | <= 8 | 18.0 | no | place kind meeting: a few places, not many |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 15.1 | no | place kind meeting: no scatter of biome specks |
| placeness | `surface.fluidCoverPercent.water` | between [0.35, 0.7] | 0.6 | yes | "drowned skyland basins", over the columns that hold ground or water (the w10t form counts the void) |
| placeness | `biomeExpression.biomes.minecraft:lukewarm_ocean.placementPercent.water? + biomeExpression.biomes.minecraft:warm_ocean.placementPercent.water?` | >= 120 | 193.9 | yes | the warm oceans lie in the basins' water, not on land (two biomes, at least 60% each on average) |
| placeness | `surface.voidPercent` | <= 15 | 5.9 | yes | basins between islands, little open void |
| placeness | `placeness.islands.sizesHa.max` | >= 1000 | 5889.6 | yes | place kind meeting: the basin masses join the islands |
| placeness | `placeness.landBiomeRegions.top3Percent` | >= 60 | 16.9 | no | place kind meeting: islands and water |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 15.1 | no | place kind meeting |
