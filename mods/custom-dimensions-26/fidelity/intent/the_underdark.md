# the_underdark — noise:caves; place kind: meeting (deep dark, mushroom light, Nether incursions)
> "The deep dark as an endless cavern world. Ancient cities built by the endermen, abandoned during their migration, colonised by the sculk. The humans found them and the sculk woke. Mushroom light and sculk glow in equal measure, and crimson and warped fungus where the nether has pushed up through the floor."

- **First:** a sculk-lit cavern with a warden monument and camp near spawn (v2 wants near_spawn).
- **Mostly:** deep dark caverns and sculk; mushroom-lit halls in equal measure. **Rarely:** crimson and warped fungus pushing up through the floor; an ancient city forced at 900, -1500 in a deep-dark patch.
- **Layers:** roof everywhere, cavern floor as the surface, deeper caves below.
- **Atmosphere:** fogColor #1B2B1B, ambientLight 0.06, no skylight (kept).
- **Signature structures:** ancient cities (spread), trial chambers, mineshafts, catacombs.
- **Place kind:** a meeting of three: sculk, mushroom light, Nether fungus.
- **Baseline wrong (W9g b1 full):** flooded: water tops 33.8% of floor columns and deep_dark is 53.8% water (`biomeExpression.biomes.minecraft:deep_dark.placementPercent`); grass_block is the commonest ground (27.5%) in a sunless world; mushroom light is missing (1.21.1 mushroom_fields, fungal_fen, moonlight_grove dropped from the v2 list); only two of six biomes expressed (warped_forest, lush_caves, dripstone_caves "features name trees, none").
- **v2 responsible:** caves preset with aquifers on (default); the v2 list keeps six of 14 biomes; no floor palette (`materials`) for a cave world.
- **Conflict:** v2 dropped half the 1.21.1 list (mushrooms, moonlight grove, swamp, dark forest, spruce taiga); the library has bioshroom_caves and fungal_fen to restore the mushroom half.
