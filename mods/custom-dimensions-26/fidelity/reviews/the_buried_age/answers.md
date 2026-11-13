# the_buried_age — review answers

Evidence: the facts table in `review.md` (O/V/M/S rows, from `/tmp/claude-0/w10c2/reviews/the_buried_age` and the W9c final survey), the shots' frame census. Every statement cites a row or a dossier key.

1. **Do the shots show the dominant character?** Yes for the sea: surface-t1, surface-t2 and the landmark look across an open lava sea under the roof with basalt and blackstone islands on the horizon, underground-t1 a soul soil shore going under the lava, surface-t7 a quartz flat of pillars on an island. No far view or sky shot passed the gate.

2. **Unsupported claims.** The shot captions here are the census captions, built from each frame's rays, so they carry no claim the census does not. No caption claims more than the census.

3. **1.21.1 elements absent from the dossier.** 7 of 9 1.21.1 biomes are not expressed (S:b 22.2%): O1 soul_sand_valley -> soul_sand_valley: placed, not expressed (mostly lava); O2 old_swampland -> soul_sand_valley: placed, not expressed (mostly lava); O3 flooded_deltas -> basalt_deltas: placed, not expressed (mostly lava); O5 upside_down_forest_cleared -> crimson_forest: not in v2 definition (mapped id missing from the v2 biome list); O6 basalt_deltas -> basalt_deltas: placed, not expressed (mostly lava); O7 ancient_delta -> ash_barrens: not in v2 definition (mapped id missing from the v2 biome list); O8 nether_wastes -> nether_wastes: placed, not expressed (mostly lava). Absent outright: O5, O7; the rest are placed with a flag.

4. **Measured elements that contradict the intent.** (Literal checks: a description word that names another place still fires.) O1 soul_sand_valley (15.8% of the surface) is flagged mostly lava; O3 basalt_deltas (30.7% of the surface) is flagged mostly lava; O8 nether_wastes (15.0% of the surface) is flagged mostly lava.

5. **Fidelity: 4/5** — Drowned as described: on the whole border the lava sea covers 70.6% of the floor (fast render), the survey's patches 57.9% (`surface.fluidCoverPercent.lava`; wants 60-75), the floor's median is 69 under the sea at 74 (`terrain.surfaceHeight.p50`) with p95 100, the lava sea one region of 70.2% (`placeness.landformRegions.largestPercent`). The biomes are laid blind to the sea: nether_wastes, soul_sand_valley and basalt_deltas are mostly lava (O1, O3, O8) and two of five are expressed. Named exception for expression: the kit cannot give a roofed world's islands and sea their own biomes (kit gap 1).
