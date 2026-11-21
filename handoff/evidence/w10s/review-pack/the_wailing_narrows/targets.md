# the_wailing_narrows: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `terrain.surfaceHeight.p75` | >= 70 | 77.0 | yes | "Narrow bridged chasms": walls everywhere. Restated from terrain.slopes.over45Percent >= 55: the slope share counts only the wall columns of a country of flat mesa tops and chasm floors (the vanilla Nether reaches 52.8 with a jagged floor); the walls are the drop between the mesa tops, a quarter of the floor at 70 or higher, and the chasm floors below |
| description | `terrain.surfaceHeight.p25` | <= 30 | 21.0 | yes | "chasms", "deeper and deeper": low cut ground. Restated from landforms basins + water >= 35: the landform classifier marks basins by their height against a 256-block window, so the floor of a cut wider than about 128 blocks reads as plains; a quarter of the floor or more lies 50 blocks under the mesa tops (the survey reports quartiles, so the 35% share itself is not measured) |
| description | `surface.fluidCoverPercent.lava` | <= 15 | 10.1 | yes | "quartz spans over the wail": chasms, not lava lakes |
| description | `surface.biomesPercent.minecraft:soul_sand_valley + surface.biomesPercent.minecraft:basalt_deltas` | >= 95 | 100.0 | yes | 1.21.1 biomes soul_sand_valley, basalt_deltas (quartz_flats removed by the owner) |
| description | `surface.biomesPercent.adventure:quartz_flats` | <= 0 | 0.0 | yes | the owner: de-emphasise or remove the quartz biome (removed) |
| description | `atmosphere.definitionAtmosphere.ambientLight?` | <= 0.1 | 0.06 | yes | 1.21.1 ambientLight 0.06 |
| description | `vertical.layerColumnsPercent.roof` | >= 85 | 100.0 | yes | 1.21.1 type nether: a roof over the world |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 59.1 | yes | place kind place (one chasm country): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.0 | yes | place kind place: no scatter of biome specks |
| placeness | `placeness.landBiomeRegions.largestPercent` | >= 50 | 59.1 | yes | place kind one place with a fringe: the soul sand chasm country is one region holding half the land |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.0 | yes | place kind one place: no scatter of biome specks |
| placeness | `placeness.places.byPlace.minecraft:soul_sand_valley.landPercent` | >= 45 | 59.1 | yes | "soul sand takes whatever stands beside the portal": the soul sand valley place holds about half the land round the frame |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 0.0 | yes | places, not perlin: no scatter of place specks |
