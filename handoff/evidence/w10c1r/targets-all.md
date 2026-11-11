# Targets, Batch 1 (w10c1r)

Baseline: the definitions W10c1 left. Final: the committed definitions (the_nether, the_end, the_snowbound_isle and the_sunken_temple unchanged, so their final is W10c1's survey). w10t from the 1.21.1 descriptions; w10c the placeness targets.

| dimension | w10t baseline | w10t final | w10c baseline | w10c final | final misses |
|---|---|---|---|---|---|
| the_aviary | 4/9 | 6/9 | 4/7 | 4/7 | w10t: landforms.sharesPercent.plateaus + landforms.sharesPercent.plains >= 35: 23.7<br>w10t: placeness.landBiomeRegions.top3Percent >= 60: 33.8<br>w10t: placeness.landBiomeRegions.specksPercent <= 5: 5.5<br>w10c: landforms.sharesPercent.plateaus + landforms.sharesPercent.plains >= 0.35: 0.31 (per vertical.layerColumnsPercent.surface)<br>w10c: placeness.islands.dominantBiomePercent >= 55: 32.2<br>w10c: placeness.landBiomeRegions.specksPercent <= 5: 5.5 |
| overworld | 5/9 | 8/9 | 1/4 | 2/4 | w10t: placeness.landBiomeRegions.specksPercent <= 5: 7.2<br>w10c: placeness.landBiomeRegions.perKm2 <= 6: 6.5<br>w10c: placeness.landBiomeRegions.specksPercent <= 5: 7.2 |
| the_nether | 9/10 | 9/10 | 4/5 | 4/5 | w10t: surface.groundBlocksPercent.minecraft:netherrack + surface.groundBlocksPercent.minecraft:crimson_nylium + surface.groundBlocksPercent.minecraft:warped<br>w10c: placeness.landBiomeRegions.major <= 8: 10.0 |
| the_end | 7/8 | 7/8 | 4/4 | 4/4 | w10t: placeness.landBiomeRegions.largestPercent >= 20: 0.2 |
| the_snowbound_isle | 5/9 | 5/9 | 7/7 | 7/7 | w10t: surface.snowIcePercent >= 70: 25.6<br>w10t: surface.biomesPercent.minecraft:snowy_taiga + surface.biomesPercent.minecraft:snowy_plains + surface.biomesPercent.minecraft:snowy_slopes + surface.bi<br>w10t: surface.precipitationPercent.snow >= 85: 31.6<br>w10t: placeness.landBiomeRegions.largestPercent >= 50: 36.8 |
| the_abyssal_shrine | 5/10 | 7/10 | 3/6 | 4/6 | w10t: surface.fluidCoverPercent.water between 25-60: 7.0<br>w10t: surface.biomesPercent.minecraft:deep_ocean + surface.biomesPercent.minecraft:deep_cold_ocean >= 20: 7.7<br>w10t: landforms.sharesPercent.plateaus >= 15: 2.9<br>w10c: surface.fluidCoverPercent.water between 0.25-0.6: 0.15 (per vertical.layerColumnsPercent.surface)<br>w10c: surface.biomesPercent.minecraft:deep_ocean + surface.biomesPercent.minecraft:deep_cold_ocean >= 0.2: 0.17 (per vertical.layerColumnsPercent.surface) |
| the_violet_spire | 6/10 | 6/10 | 4/7 | 3/7 | w10t: terrain.slopes.cliffPercent >= 15: 6.3<br>w10t: placeness.landBiomeRegions.top3Percent >= 60: 51.5<br>w10t: placeness.landBiomeRegions.major <= 8: 13.0<br>w10t: placeness.landBiomeRegions.specksPercent <= 5: 13.9<br>w10c: placeness.landBiomeRegions.top3Percent >= 60: 51.5<br>w10c: placeness.landBiomeRegions.major <= 8: 13.0<br>w10c: placeness.landBiomeRegions.specksPercent <= 5: 13.9<br>w10c: placeness.islands.dominantBiomePercent >= 60: 50.0 |
| the_sunken_temple | 7/10 | 7/10 | 4/6 | 4/6 | w10t: placeness.landBiomeRegions.top3Percent >= 60: 14.4<br>w10t: placeness.landBiomeRegions.major <= 8: 20.0<br>w10t: placeness.landBiomeRegions.specksPercent <= 5: 10.7<br>w10c: placeness.landBiomeRegions.top3Percent >= 60: 14.4<br>w10c: placeness.landBiomeRegions.specksPercent <= 5: 10.7 |

## Placeness (w10c) misses by cause

| dimension | passing | waits for the places layout (K6) | other cause |
|---|---|---|---|
| the_aviary | 4/7 | islands.dominantBiomePercent 32.2, landBiomeRegions.specksPercent 5.5 | plateaus + plains 31% of the ground: island tops read as hills (not tuned) |
| overworld | 2/4 | landBiomeRegions.perKm2 6.5, specksPercent 7.2 | — |
| the_nether | 4/5 | landBiomeRegions.major 10 | — |
| the_end | 4/4 | — | — |
| the_snowbound_isle | 7/7 | — | — |
| the_abyssal_shrine | 4/6 | — | water 15% and deep oceans 17% of the ground: the hollow is only as wide as the ring (220 of 512); named exception |
| the_violet_spire | 3/7 | landBiomeRegions.top3Percent 51.5, major 13, specksPercent 13.9, islands.dominantBiomePercent 50.0 | — |
| the_sunken_temple | 4/6 | landBiomeRegions.top3Percent 14.4 | specksPercent 10.7: basins and the upper layer's islets split the land; named exception |
