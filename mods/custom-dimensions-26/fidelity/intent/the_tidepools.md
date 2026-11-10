# the_tidepools — noise:overworld; place kind: place (one pocket of tidal shallows)
> "A pocket of tidal shallows — rock pools, gull-grey cliffs and a drowned wreck glinting under clear water. No civilisation ever claimed this; the animals have always had it. The wreck is the only evidence anyone else was ever here."

- **First:** grey stone cliffs over clear shallow water and rock pools (border 256).
- **Mostly:** shallows, rock pools, stony and gravel beaches, granite cliffs. **Rarely:** the drowned wreck (wants shipwreck).
- **Layers:** surface (shore, shallow water); lush caves below.
- **Atmosphere:** no 1.21.1 environment.
- **Place kind:** one small place: a shoreline.
- **Baseline wrong (W9g b1 full):** open sea, not shallows: water tops 90.4% of columns, lukewarm_ocean 63.8% and warm_ocean 24.4% of biomes; `terrain.surfaceHeight.p50` 38 is 25 below sea level (sea 63); sand 84.3% of ground; cliffs 1.0%; shore biomes (stony_shore 6.7, beach 3.9, windswept_gravelly_hills 1.1) a sliver; lush_caves listed but unplaced.
- **v2 responsible:** overworld preset with vanilla ocean depth; the regions put most of the border in the ocean role; weights stony_shore 43.4 and beach 21.2 cannot be met because the shore role is the thin coast band (placeness.md roles).
