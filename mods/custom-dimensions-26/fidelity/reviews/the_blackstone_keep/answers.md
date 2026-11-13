# the_blackstone_keep — review answers

Evidence: the facts table in `review.md` (O/V/M/S rows, from `/tmp/claude-0/w10c2/reviews/the_blackstone_keep` and the W9c final survey), the shots' frame census. Every statement cites a row or a dossier key.

1. **Do the shots show the dominant character?** Yes. surface-t1 and sky-t1 stand on blackstone shelves over lava pockets (place type 1, adventure:blackstone_basin, ground blackstone, 47.0% of surface cells), surface-t3 and underground-t4 look across the basin's low lava floor broken by blackstone stacks (type 3, top lava, 11.5%), surface-t4 is the crimson wart wood (type 4, crimson_forest, canopy 39%), roof-t8 the blackstone roof. The far view and the landmark were dropped: both cameras stood over the bedrock roof (the vista summit is a floor-to-roof column at y 128).

2. **Unsupported claims.** The shot captions here are the census captions, built from each frame's rays, so they carry no claim the census does not. No caption claims more than the census.

3. **1.21.1 elements absent from the dossier.** 2 of 9 1.21.1 biomes are not expressed (S:b 77.8%): O5 nether_wastes -> nether_wastes: not in v2 definition (mapped id missing from the v2 biome list); O8 wart_forest -> upside_down_forest: not in v2 definition (mapped id missing from the v2 biome list). Absent outright: O5, O8; the rest are placed with a flag.

4. **Measured elements that contradict the intent.** (Literal checks: a description word that names another place still fires.) None found by the checks (description words against M rows, S:d, flags on 5%+ biomes).

5. **Fidelity: 4/5** — One blackstone place round spawn: adventure:blackstone_basin is 55.4% of the surface biomes (O2) and the largest land region holds 54.3% of the land (`placeness.landBiomeRegions.largestPercent`) with 0.5% specks; blackstone is 52.2% of the ground (`surface.groundBlocksPercent`), trees hung in the sky and roof layers are 3.8 per hectare (`vegetation.treesPerHectareByLayer`), and all six listed biomes are expressed (`biomeExpression.expressed` 6). nether_wastes and wart_forest (O5, O8) are no longer listed: the wastes' ground is the basin's blackstone, and wart_forest's inverted trees hung a forest from the roof. The place's edges are straight power-diagram sides.
