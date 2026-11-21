# the_molten_flats: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `terrain.slopes.flatUnder15Percent` | >= 55 | 75.4 | yes | "Industrial lava flats" |
| description | `terrain.surfaceHeight.p95` | <= 50 | 41.0 | yes | "flats", against the vanilla Nether's cliffs. Restated from terrain.reliefMedian <= 50: patch relief counts the few columns the survey reads in pockets of the roof mass, at up to 119 in every patch of every Nether preset; the 95th percentile of the floor is the flat's own height |
| description | `surface.fluidCoverPercent.lava` | between [20, 45] | 16.8 | no | "built over the melt": lava beside the flats |
| description | `surface.groundBlocksPercent.minecraft:magma_block + surface.groundBlocksPercent.minecraft:basalt + surface.groundBlocksPercent.minecraft:smooth_basalt + surface.groundBlocksPercent.minecraft:blackstone + surface.groundBlocksPercent.minecraft:tuff + surface.groundBlocksPercent.minecraft:obsidian` | >= 35 | 76.7 | yes | 1.21.1 volcanic_deltas, inferno, ash_barrens: melt-country ground |
| description | `surface.biomesPercent.adventure:ash_barrens` | >= 10 | 23.1 | yes | 1.21.1 lists incendium:ash_barrens first |
| description | `vegetation.treesPerHectareByLayer.surface` | <= 2 | 2.7 | no | "Industrial": no forest |
| description | `vertical.layerColumnsPercent.roof` | >= 85 | 100.0 | yes | 1.21.1 type nether: a roof over the world |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 54.5 | yes | place kind place (one flat): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.1 | yes | place kind place: no scatter of biome specks |
| placeness | `placeness.places.byPlace.adventure:magma_land.landPercent` | >= 50 | 54.5 | yes | place kind one place with a fringe: the magma flat holds half the dry land (lava channels cut the flat, so the place is measured by its land share, not one unbroken region) |
| placeness | `placeness.places.byPlace.adventure:magma_land.largestPercent` | >= 25 | 54.5 | yes | one flat: the magma place's largest piece of land is a quarter of the dry land, not a scatter of islands |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.1 | yes | place kind one place: no scatter of biome specks |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 0.0 | yes | places, not perlin: no scatter of place specks |
