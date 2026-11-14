# the_souldrift — noise:overworld, fixed dusk; place kind: meeting (a surface scar over the deep dark)
> "Soul-sand drifting into shadow. The surface scar where the underground sculk infection broke through from the ancient cities — nether ash and soul valleys over a deep dark that shouldn't be this close to the surface. The drift does not stop at the portal: what it reaches, it takes, and a build near the frame is part of what it reaches."

- **First:** a graveyard on soul sand and ash at dusk (wants graveyard near_spawn; fixedTime 13000).
- **Mostly:** soul valleys and nether ash over scarred badlands; sculk breaking through. **Rarely:** white cliffs, moonlight groves, prismachasm.
- **Layers:** surface scar; the deep dark just under it.
- **Atmosphere:** skyColor #3A2E3E, fogColor #241B2E, ambientLight 0.25 (kept).
- **Place kind:** a meeting: the Nether's ash and soul, the sculk, the badlands they scar.
- **Baseline wrong (W9g b1 full):** soul sand valley unplaced (`biomeExpression.listedNotFound` soul_sand_valley, deep_dark, redstone_abyss, magma_land) despite the 540, -420 patch; warm_ocean 28.1% and water 38.9% of top blocks; soul ground absent from the top ground blocks (sand 24.5, blackstone 12.2); 17 regional biomes give 27 major regions (largest 27.5, top3 34.5).
- **v2 responsible:** 17 regional biomes of similar weight on a 4096 border; deep_dark as a surface region (not under the scar).
