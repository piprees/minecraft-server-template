# the_burning_archipelago — noise:end, no central island (1.21.1 nether_islands); place kind: meeting (islands and straits)
> "Burning islands in a void sea — nether terrain on end-island bones. The piglins and endermen fought across these straits; the islands that survived still smoulder."

- **First:** a warped outpost and ruined portal on a smouldering island (wants near_spawn).
- **Mostly:** Nether islands (crimson, warped, deltas, wastes, dunes, inferno) across void straits. **Rarely:** calcite crags, volcanic peaks.
- **Layers:** islands over the void; lava fluid; ultraWarm.
- **Atmosphere:** fogColor #3B2B4B, ambientLight 0.1, End effects, ultraWarm (kept).
- **Place kind:** a meeting of islands and straits.
- **Baseline wrong (W9g b1 full):** "nether terrain" is absent: end_stone 24.4% of ground, netherrack 0.5 despite `materials.defaultBlock` netherrack; eight of eleven placed biomes "placed, not expressed" (mostly void, or forests with no trees: crimson_forest, nether_jungle); trees 0.8/ha; fire only 0.3% of top blocks ("still smoulder").
- **v2 responsible:** end preset; neither the default block nor the Nether biomes' palettes show on the islands (measured; cause unverified); regions ignore the islands.
