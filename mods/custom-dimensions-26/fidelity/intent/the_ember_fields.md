# the_ember_fields — noise:nether; place kind: place (one meadow country)
> "Rolling ember meadows where piglin villages still tend the geyser fields. The gentlest face of piglin civilisation — farmers and traders, not soldiers. The fortress at the rim is a precaution, not a threat."

- **First:** nylium meadows, a piglin village and a crimson well (wants piglin_village spread, crimson_well near_spawn).
- **Mostly:** rolling grassland (1.21.1 nether_grasslands, glistering_meadow), geyser fields (sulphur_springs, yellowstone). **Rarely:** the fortress at the rim (near_border).
- **Layers:** roof, a rolling floor.
- **Atmosphere:** warm orange fog #E8A05A, ambientLight 0.25, beds work (kept: `rules.bedsWork`).
- **Place kind:** one place: gentle meadows, warmly lit.
- **Baseline wrong (W9g b1 full):** not rolling: `terrain.reliefMedian` 98, cliffPercent 25.7; lava 22.6% of the floor; regions scattered: largest 10.2, top3 17.4, 31 major regions, specks 6.1 (`placeness.biomeRegions`); magma_land 14.1 and gravel_desert 8.7 are not meadow.
- **v2 responsible:** nether preset; eleven listed biomes with magma_land 15.5 and gravel_desert 13.7 weights; sulfur_caves (a cave biome) at weight 10.7 as a region.
