# the_catalyst_maw — noise:end (1.21.1 nether_islands, adventure:compressed); place kind: broken (chaotic corruption)
> "The sculk that fed on the boneyard war escaped here — corrupted end terrain bleeding into dark overworld, oppressive and chaotic. The catalyst is still feeding, still growing. The reactors and bastions it consumed are unrecognisable, and what it reaches near the portal it takes."

- **First:** a sculk dungeon at spawn (wants near_spawn) on corrupted end stone.
- **Mostly:** End terrain bleeding into dark overworld (dark forest, mangrove, moonlight) and Nether ash and soul sand; sculk spreading. **Rarely:** a deep-dark patch at 1180, -860.
- **Layers:** End islands; sculk underneath and spreading over the surface.
- **Atmosphere:** fogColor #0B2B3B, ambientLight 0.06, End effects, monsters at any light (kept).
- **Place kind:** deliberately chaotic.
- **Baseline wrong (W9g b1 full):** sculk absent (no sculk in the top ground blocks: end_stone 17.9, tuff 13.3); four listed biomes unplaced (pale_garden, end_midlands, end_highlands, dark_forest: `biomeExpression.listedNotFound`), nine "mostly void"; trees 2.2/ha in a "dark overworld" bleed; 16 regional biomes on a 4096 border with every land region tiny (largest 0.8).
- **v2 responsible:** end preset with `terrain.base` null (adventure:compressed did not convert); regions ignore the islands.
