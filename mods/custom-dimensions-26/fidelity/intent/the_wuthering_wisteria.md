# the_wuthering_wisteria — noise:overworld, sea at 66, fixed dusk; place kind: place (one sealed grove)
> "A broken torii gate opens onto a pocket grove of wisteria. Someone sealed this place deliberately — sweet blossom on the breeze, a stream winding beneath the canopy. Whatever they were protecting, the gate is the only way in, and it's still standing."

- **First:** the torii gate (portal) opening on wisteria and blossom under a pink haze.
- **Mostly:** wisteria forest and sakura, a stream under the canopy. **Rarely:** meadow and moonlight valley.
- **Layers:** surface grove; lush caves below.
- **Atmosphere:** fogColor #F0D8E8, fixedTime 12000 (kept).
- **Place kind:** one small place (border 512): a grove, "pocket", "sealed".
- **Baseline wrong (W9g b1 full):** wisteria is 8.5% of biomes; old_growth_birch_forest leads (33.6; birch 39.9 of 50.6 trees/ha); ocean 20.2% and water 56.5% of top blocks (a pocket grove with a stream, not a lake); old_growth_birch_forest "mostly water".
- **v2 responsible:** weights old_growth_birch_forest 52.9 (1.21.1 moonlight_valley mapped to it) vs wisteria_grove 11.1; seaLevel 66 over vanilla terrain floods the low ground.
