# the_forged_depths — noise:nether; place kind: place (one small deep)
> "The last piglin forge still burning. Crimson forges and reactors in a small brutal deep — the fires here never went out because nobody survived to bank them. The deeper you go, the hotter and harder it gets. The heat comes back through the frame, and anything built beside the portal is fuel."

- **First:** a crimson forge at spawn (wants crimson_forge near_spawn), quartz and magma ground.
- **Mostly:** a deep: magma and basalt, crimson pinewood; hotter downward. **Rarely:** reactors, bastions.
- **Layers:** roof, a floor that falls away deeper (a vertical gradient), lava below.
- **Atmosphere:** fogColor #4B2B1B, ambientLight 0.08 (kept).
- **Place kind:** one small place (border 683).
- **Baseline wrong (W9g b1 full):** the deep is the vanilla Nether: no floor deeper than elsewhere (`terrain.surfaceHeight.p5` 20, p50 65); "hotter downward" is not measured; crimson_forest "mostly lava" (51.6% of its columns, `biomeExpression`); regions small for a 683 border (largest 13.5, 25 major).
- **v2 responsible:** nether preset; six regional biomes over a small border make many small regions.
- **Survey gap:** no metric for material by depth (hotter deeper).
