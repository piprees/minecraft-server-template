# the_icebound_rift — void, End effects; place kind: place (the memory of a frozen world)
> "A void where a frozen world used to be. The biomes remain — their cold, their fog, their sounds — but the ground does not. The disintegration finished here. Whatever broke this world left only the memory of ice."

- **First / mostly:** void under a cold blue sky and pale fog, snow falling into nothing.
- **Rarely:** the biomes' own sounds and particles changing as you move.
- **Layers:** none but the air.
- **Atmosphere:** skyColor #1B2B3B, fogColor #A8D8E8, ambientLight 0.2, End effects (kept).
- **Place kind:** one place: an empty cold void whose biomes are felt, not seen.
- **Baseline wrong (W9b):** "The biomes remain" fails: the void generator places only crystal_hollows, its first biome; nine listed biomes not found (`biomeExpression.listedNotFound`); void 100.0 matches "the ground does not".
- **v2 responsible:** `generator` void takes one biome; the ten climate boxes are unused.
- **Survey gap:** biomes over void columns are not measured (the survey's biome shares are of surface columns).
