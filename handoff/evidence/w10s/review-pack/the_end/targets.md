# the_end: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.voidPercent` | between [35, 70] | 47.9 | yes | vanilla End: islands over the void |
| description | `surface.groundBlocksPercent.minecraft:end_stone` | >= 25 | 50.6 | yes | vanilla End: end stone ground |
| description | `surface.biomesPercent.minecraft:end_highlands + surface.biomesPercent.minecraft:end_midlands` | >= 30 | 46.0 | yes | "Cities and citadels": the highlands end cities stand on carry the land |
| description | `biomeExpression.biomes.minecraft:end_highlands.placementPercent.land?` | >= 80 | 98.8 | yes | "cities ... within honest reach": highlands are solid land |
| description | `surface.topBlocksPercent.minecraft:chorus_plant + surface.topBlocksPercent.minecraft:chorus_flower` | >= 0.5 | 1.4 | yes | vanilla End: chorus on the outer islands |
| description | `surface.fluidCoverPercent.water + surface.fluidCoverPercent.lava` | <= 0.5 | 0.0 | yes | 1.21.1 seedRoll water none |
| description | `vertical.layerColumnsPercent.roof` | <= 0 | 0.0 | yes | vanilla End: open sky |
| description | `placeness.landBiomeRegions.largestPercent` | >= 20 | 0.3 | no | place kind place: the highlands mass reads as one place |
| placeness | `surface.voidPercent` | between [35, 70] | 47.9 | yes | "The end": islands over the void |
| placeness | `placeness.islands.sizesHa.max` | >= 100 | 175.6 | yes | place kind place: the End's large islands, not only islets |
| placeness | `placeness.biomesPercent.minecraft:end_highlands + placeness.biomesPercent.minecraft:end_midlands` | >= 30 | 33.0 | yes | end-stone highlands and midlands over the border |
| placeness | `placeness.islands.dominantBiomePercent` | >= 40 | 45.9 | yes | place kind place, read per island (the w10t largest-region form cannot hold on islands parted by void, and vanilla rings each island highlands to barrens) |
