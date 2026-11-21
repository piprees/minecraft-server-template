# the_ruined_timberland — noise:overworld, sea at 44; place kind: place (one autumn wood)
> "Autumn woods strewn with ruins. Every clearing has bones of something older — temples, crypts, pumpkin ruins from a harvest festival that was never finished. Rich pickings, no living towns. Whoever these people were, they left in a hurry."

- **First:** a forest ruin and pumpkin ruins (wants near_spawn) in autumn colour.
- **Mostly:** aspen and maple woodland, autumn maple forest, forest, birch, clearings. **Rarely:** dark forest, moonlight grove; crypts near the border.
- **Layers:** surface woods; deep caves and deep dark.
- **Atmosphere:** none in 1.21.1; autumn colour comes from the biomes.
- **Place kind:** one place: autumn woods.
- **Baseline wrong (W9g b1 full):** `minecraft:ocean` is 60.2% of biomes but water tops only 14.5% of columns: an ocean biome on dry land under the lowered sea (44); autumn thin: maple_woodland 2.8, aspen_forest 3.1 (poplar 23.8/ha carries some colour); pale_garden and birch_forest unplaced.
- **v2 responsible:** `liquids.seaLevel` 44 with the overworld ocean role; weights forest 45.9 against maple 4.1 and aspen 8.3.
