# the_gilded_pit: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.groundBlocksPercent.minecraft:gilded_blackstone + surface.groundBlocksPercent.minecraft:gold_block + surface.groundBlocksPercent.minecraft:nether_gold_ore + surface.groundBlocksPercent.minecraft:blackstone` | >= 15 | 37.7 | yes | "gold in every wall" |
| description | `terrain.surfaceHeight.p25` | <= 44 | 34.0 | yes | "a ledge overlooking what remains": the capital lies in a pit. Restated from landforms basins >= 25: the landform classifier marks basins by their height against a 256-block window, so the floor of a cut wider than about 128 blocks reads as plains; a quarter of the floor or more lies in the pit, 40 blocks or more under the ledge at 84 |
| description | `placeness.at.spawn.biomeEdgeBlocks` | >= 64 | 48.0 | no | "Nothing within arm's reach of the landing": spawn sits well inside one region |
| description | `surface.biomesPercent.adventure:blackstone_basin` | >= 15 | 41.0 | yes | "gold in every wall": the library's gilded-seam biome |
| description | `surface.fluidCoverPercent.lava` | <= 15 | 7.1 | yes | "treasure in every ruin": a city floor, not a lava lake |
| description | `surface.biomesPercent.minecraft:basalt_deltas` | <= 20 | 1.3 | yes | 1.21.1 biomes: gravel_desert, poor_nether_grasslands, nether_wastes, basalt_deltas, crimson_forest, inferno: deltas one of six |
| description | `vertical.layerColumnsPercent.roof` | >= 85 | 100.0 | yes | 1.21.1 type nether: a roof over the world |
| description | `placeness.landBiomeRegions.largestPercent` | >= 50 | 50.7 | yes | place kind place (one pit): one region holds half the land |
| description | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.1 | yes | place kind place: no scatter of biome specks |
| placeness | `placeness.landBiomeRegions.largestPercent` | >= 50 | 50.7 | yes | place kind one composed place: one region holds half the land |
| placeness | `placeness.landBiomeRegions.specksPercent` | <= 5 | 0.1 | yes | place kind one place: no scatter of biome specks |
| placeness | `placeness.at.spawn.biomeEdgeBlocks` | >= 64 | 48.0 | no | "nothing within arm's reach of the landing": spawn stands well inside its place |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 0.0 | yes | places, not perlin: no scatter of place specks |
