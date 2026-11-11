# the_violet_spire — review answers

Evidence: the facts table in `review.md` (O/V/M/S rows, from `/tmp/claude-0/w10c1/reviews/the_violet_spire` and the W9c final survey), the shots' frame census. Every statement cites a row or a dossier key.

1. **Do the shots show the dominant character?** Yes. sky-t1 shows lavender fields, surface-t1 wisteria, surface-t2 and t3 pale stone spires with amethyst faces beside lavender and water, zone-crystal the spire islets from below with vines hung from them.

2. **Unsupported claims.** The shot captions here are the census captions, built from each frame's rays, so they carry no claim the census does not. No caption claims more than the census.

3. **1.21.1 elements absent from the dossier.** 5 of 11 1.21.1 biomes are not expressed (S:b 54.5%): O1 amethyst_canyon -> crystal_hollows: not in v2 definition (mapped id missing from the v2 biome list); O2 windswept_spires -> jagged_peaks: not in v2 definition (mapped id missing from the v2 biome list); O4 haze_mountain -> stony_peaks: not in v2 definition (mapped id missing from the v2 biome list); O7 highlands_thicket -> dark_forest: not in v2 definition (mapped id missing from the v2 biome list); O9 highlands_forest -> forest: not in v2 definition (mapped id missing from the v2 biome list). Absent outright: O1, O2, O4, O7, O9; the rest are placed with a flag. Placed v2 biomes no 1.21.1 biome maps to: V1 chorus_forest 2.1%, V2 umbrella_jungle 1.4%, V3 sparse_jungle 0.2%.

4. **Measured elements that contradict the intent.** (Literal checks: a description word that names another place still fires.) the description says "spires" but median relief 12.0 blocks a patch (M:relief).

5. **Fidelity: 3/5** — Violet now leads (O11 lavender 14.7%, O8 wisteria 10.7%), amethyst is 3.8% of top blocks (`surface.topBlocksPercent`), spires reach y 239 (`terrain.surfaceHeight.p95`), and every listed biome is expressed. Named exception: each spire stands on an islet of its own, so the land is 82 islets of at most 4.9 ha (`placeness.islands`) and its regions a scatter (`placeness.landBiomeRegions.specksPercent` 51.3); stacked floating islands in its place lost the spires (cliffs 1.5%). Spires standing on shared floating islands (a spires floor of floating_islands) would close it.
