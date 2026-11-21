# the_dustbowl — noise:overworld, sea at -55; place kind: place (one dust plain)
> "A tiny, exhausted dust plain. One farmstead stands in the north — the last holdout of someone who refused to leave. The wind owns everything else. Whatever was here dried up and blew away."

- **First:** dry steppe at spawn (1.21.1 spawnFilter steppe), the farmstead to the north (forced at -87, -312).
- **Mostly:** flat dust: steppe, xeric plains, outback, dry bushland. **Rarely:** desert, badlands, wooded drylands.
- **Layers:** surface; andesite and dripstone caves below.
- **Atmosphere:** none in 1.21.1.
- **Place kind:** one small place (border 512).
- **Baseline wrong (W9g b1 full):** warm_ocean is the largest biome (30.5%) on dry land (`biomeExpression.biomes.minecraft:warm_ocean.placementPercent.land` 99.9): the ocean role laid where the sea level of -55 left the sea floor dry; Joshua trees 33.9/ha in joshua_desert (23.6%), the largest land biome; dry_steppe 2.9% where steppe is the spawn; relief 20, flat 51.8%.
- **v2 responsible:** weights joshua_desert 33.4, saguaro_desert 23.7, prairie 26.8 against dry_steppe 1, savanna 1, badlands 1; the coast role applies though the sea is gone.
