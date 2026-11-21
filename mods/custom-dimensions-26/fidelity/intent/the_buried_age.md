# the_buried_age — noise:nether, lava sea at 74; place kind: broken (a drowned ruin field)
> "An ancient buried age — nether ruins and towers of a piglin civilisation the lava took, and is still taking. Two thirds of the world is under it. What is left is a corridor between the lava surface and the bedrock roof, and the only architecture you ever see is whatever was tall enough to keep its top floor. It takes through the portal too."

- **First:** a lava sea under the roof, ruin tops breaking its surface (wants start_nether_ruin near_spawn).
- **Mostly:** lava, with islands and ledges in a corridor to the roof. **Rarely:** towers tall enough to keep a floor dry.
- **Layers:** lava sea (two thirds), a thin corridor, the roof.
- **Atmosphere:** fogColor #3B2B1B, ambientLight 0.1 (kept).
- **Signature structures:** nether lava ruins, nether towers, pipelines, a copper tower near the border.
- **Place kind:** deliberately drowned; one lava sea is the place.
- **Baseline wrong (W9g b1 full):** lava covers 44.3% (`surface.fluidCoverPercent.lava`), short of "two thirds"; `terrain.surfaceHeight.p50` 78 sits just over the 74 lava line with p95 127, so land rises to the roof (relief 111) instead of a corridor; the 1.21.1 old_swampland, flooded_deltas and upside_down_forest_cleared are not mapped (upside_down_forest 6.5%).
- **v2 responsible:** nether preset with `liquids.seaLevel` 74 and lava as default fluid: the right tools; the vanilla Nether terrain stands too high above 74 for two thirds to drown.
