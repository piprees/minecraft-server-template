# the_soulfields — noise:overworld, sea at -60, soul soil default, fixed midnight; place kind: place (one memorial plain, graves thickening outward)
> "Soul-sand meadows and grave fields without end — the piglin memorial dimension, always at midnight. The dead are the crop. You arrive in an empty plain and the graves begin some way out and thicken all the way to the edge. Nothing living is welcome."

- **First:** an empty soul-sand plain at midnight; the first nether graveyard some way out (wants near_spawn).
- **Mostly:** soul-sand meadows and grave fields. **Rarely:** weeping valley, withered trees.
- **Layers:** surface plain.
- **Atmosphere:** fogColor #3B5B7B, ambientLight 0.22, fixedTime 18000 (kept).
- **Place kind:** one place composed outward from spawn: empty at the centre, graves thickening to the edge.
- **Baseline wrong (W9g b1 full):** grass_block 53.4% and sand 22.3% of ground (Nether biomes take the overworld surface rule on the overworld preset); warm_ocean 22.8% of the map on dry land (sea at -60) with grass; soul_soil only 14.1; not a plain: hills 33.0, mountains 9.2, p95 119; three of six biomes "placed, not expressed".
- **v2 responsible:** overworld preset with Nether biomes and no per-biome palettes; `materials.defaultBlock` soul_soil shows only below the top; the ocean role on a dry sea floor.
- **Survey gap:** "graves thicken all the way to the edge" needs structure density by distance from spawn.
