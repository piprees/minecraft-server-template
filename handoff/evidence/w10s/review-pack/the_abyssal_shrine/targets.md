# the_abyssal_shrine: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.fluidCoverPercent.water` | between [25, 60] | 29.1 | yes | "deep drowned hollows" |
| description | `surface.waterDepth.over12Percent` | >= 40 | 68.9 | yes | "deep drowned hollows", "Guardians circle below" |
| description | `biomeExpression.biomes.minecraft:deep_ocean.placementPercent.water? + biomeExpression.biomes.minecraft:deep_cold_ocean.placementPercent.water?` | >= 80 | 180.6 | yes | "drowned hollows": the deep ocean biomes lie under water, not on islands |
| description | `surface.biomesPercent.minecraft:deep_ocean + surface.biomesPercent.minecraft:deep_cold_ocean` | >= 20 | 31.5 | yes | 1.21.1 biomes deep_ocean, deep_cold_ocean |
| description | `landforms.sharesPercent.plateaus` | >= 15 | 4.3 | no | "plateau islands ringing" the hollows; 1.21.1 continental_plateau |
| description | `surface.biomesPercent.adventure:golden_skygrove` | <= 5 | 0.0 | yes | "the sky above is indifferent": no gold groves |
| description | `surface.biomesPercent.minecraft:deep_dark` | <= 3 | 0.0 | yes | 1.21.1 deep_dark is a cave biome: below, not on the surface |
| description | `atmosphere.definitionAtmosphere.ambientLight?` | <= 0.25 | 0.15 | yes | 1.21.1 ambientLight 0.15: dim |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 56.4 | yes | place kind place (a ring of plateaus round one hollow): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.3 | yes | place kind place: no scatter of biome specks |
| placeness | `surface.fluidCoverPercent.water` | between [0.25, 0.6] | 0.41 | yes | "deep drowned hollows", over the columns that hold ground or water (the w10t form counts the void) |
| placeness | `surface.biomesPercent.minecraft:deep_ocean + surface.biomesPercent.minecraft:deep_cold_ocean` | >= 0.2 | 0.45 | yes | the deep oceans in the hollows, over the same columns |
| placeness | `surface.waterDepth.over12Percent` | >= 40 | 68.9 | yes | "deep drowned hollows" |
| placeness | `placeness.islands.count` | <= 3 | 1.0 | yes | place kind place: one composition round the hollow |
| placeness | `placeness.landBiomeRegions.largestPercent` | >= 30 | 56.4 | yes | place kind place: the rim reads as one land |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.3 | yes | place kind place |
