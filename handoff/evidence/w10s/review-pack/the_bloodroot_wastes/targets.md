# the_bloodroot_wastes: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.biomesPercent.minecraft:crimson_forest + surface.biomesPercent.adventure:nether_jungle + surface.biomesPercent.adventure:upside_down_forest + surface.biomesPercent.adventure:crimson_pinewood` | >= 60 | 92.6 | yes | "Crimson root-country"; 1.21.1 crimson_forest, weeping_valley, inverted_forest, infernal_holt |
| description | `surface.groundBlocksPercent.minecraft:crimson_nylium + surface.groundBlocksPercent.minecraft:nether_wart_block + surface.groundBlocksPercent.minecraft:shroomlight + surface.topBlocksPercent.minecraft:nether_wart_block + surface.topBlocksPercent.minecraft:weeping_vines_plant + surface.topBlocksPercent.minecraft:weeping_vines + surface.topBlocksPercent.minecraft:crimson_roots` | >= 35 | 73.9 | yes | "Crimson root-country", "fungus in the red haze" |
| description | `vegetation.treesPerHectareByLayer.surface` | >= 40 | 82.8 | yes | "fungus": huge crimson fungi |
| description | `surface.biomesPercent.minecraft:basalt_deltas` | <= 10 | 3.0 | yes | "root-country": deltas are one minor biome of six |
| description | `vertical.layerColumnsPercent.roof` | >= 85 | 97.7 | yes | 1.21.1 type nether: a roof over the world |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 55.5 | yes | place kind place (one crimson country): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.7 | yes | place kind place: no scatter of biome specks |
| placeness | `placeness.landBiomeRegions.largestPercent` | >= 50 | 55.5 | yes | place kind one place with a fringe: the place is one region holding half the land |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.7 | yes | place kind one place: no scatter of biome specks |
| placeness | `placeness.biomesPercent.minecraft:crimson_forest` | >= 45 | 54.5 | yes | "Crimson root-country": the crimson forest is the place |
| placeness | `placeness.biomesPercent.minecraft:nether_wastes` | <= 10 | 5.1 | yes | "crimson fungus at spawn": the wastes are a fringe, not the country |
| placeness | `placeness.places.byPlace.minecraft:crimson_forest.landPercent` | >= 50 | 55.7 | yes | one place with a fringe: the crimson forest place holds half the land |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 0.5 | yes | places, not perlin: no scatter of place specks |
