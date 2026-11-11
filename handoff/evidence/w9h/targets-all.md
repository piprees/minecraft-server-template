# Targets, all sets, W9h re-survey

W9c and W9h against the full survey, W9e and W9f against the fast survey (W9g check.sh rules). W9h = the W9f targets with the coast-following picks biome-region targets re-cut on `placeness.landBiomeRegions`, and a cave biome placed read from `biomeExpression` (surface columns or cave quarts).

| pick | w9c | w9e | w9f | w9h | misses |
|---|---|---|---|---|---|
| basalt_spires | - | 8/8 | 12/12 | 12/12 | — |
| blossom_gardens | - | 26/26 | 30/30 | 30/30 | — |
| crimson_nexus | 11/11 | 15/15 | 19/19 | 19/19 | — |
| crucible | 7/8 | 9/9 | 12/12 | 12/12 | w9c: `surface.canopyPercent` >= 70 (measured 60.9) |
| crystal_vale | 9/9 | 13/13 | 16/17 | 17/17 | w9f: `placeness.biomeRegions.perKm2` <= 3.9 (measured 4.1) |
| darkpine_depths | - | 10/11 | 13/15 | 15/15 | w9e: `placeness.biomeRegions.largestPercent` >= 36.3 (measured 28.0)<br>w9f: `placeness.biomeRegions.largestPercent` >= 36.3 (measured 28.0)<br>w9f: `placeness.biomeRegions.largestPercent` >= 38.7 (measured 28.0) |
| frozen_hearth | 7/7 | 12/12 | 16/16 | 16/16 | — |
| frozen_strait | - | 10/10 | 14/14 | 14/14 | — |
| gauntlet | - | 21/21 | 24/24 | 24/24 | — |
| gritlands | 11/11 | 6/6 | 9/9 | 9/9 | — |
| highland_crossing | 8/9 | 26/26 | 30/30 | 30/30 | w9c: `landforms.highGround.upperThreeQuarters.top3Percent` >= 80 (measured 5.2) |
| needlefall | - | 8/8 | 11/11 | 11/11 | — |
| scorched_mesa | 9/9 | 8/8 | 12/12 | 12/12 | — |
| shallows | 7/9 | 8/8 | 10/12 | 12/12 | w9c: `terrain.reliefMedian` <= 6 (measured 10.0)<br>w9c: `terrain.slopes.over45Percent` <= 4 (measured 5.3)<br>w9f: `placeness.biomeRegions.specksPercent` <= 2.2 (measured 14.5)<br>w9f: `placeness.biomeRegions.perKm2` <= 10.0 (measured 14.3) |
| stonemantle | - | 14/14 | 16/18 | 18/18 | w9f: `placeness.biomeRegions.largestPercent` >= 33.8 (measured 24.5)<br>w9f: `placeness.biomeRegions.top3Percent` >= 65.1 (measured 53.6) |
| sun_kingdoms | - | 23/23 | 27/27 | 27/27 | — |
| twisted_groves | - | 10/10 | 14/14 | 14/14 | — |
| verdant_hollow | - | 12/12 | 15/16 | 16/16 | w9f: `placeness.biomeRegions.perKm2` <= 3.4 (measured 4.1) |
| whitestone_ford | - | 7/7 | 11/11 | 11/11 | — |
| greenreach | 9/11 | 11/11 | - | - | w9c: `terrain.slopes.over45Percent` <= 5 (measured 8.3)<br>w9c: `surface.groundPlantsPercent.grass` >= 30 (measured 29.0) |
| overgrowth | - | 9/9 | - | - | — |
| **total** | 78/84 | 266/267 | 311/319 | 319/319 | |
