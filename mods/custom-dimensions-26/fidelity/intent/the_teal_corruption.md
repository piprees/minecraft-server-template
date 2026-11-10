# the_teal_corruption — noise:overworld, sea at -55; place kind: place (a mesa with corruption spreading from the centre)
> "Warped corruption spreading over a mesa world — teal fungus over red rock. The nether bleeding through, but slowly, almost beautifully. It came through at the centre and it is still spreading. The piglins who lived here didn't flee; they adapted. Their outposts stand in the fungus like houses in a rising tide. The tide comes through the portal as well, and it does not ask what you built there."

- **First:** warped forest at the centre (zone radius 420 at 0, 0) over red rock.
- **Mostly:** badlands and eroded badlands mesas, warped fungus spreading over them. **Rarely:** crimson, scarlet mountains, ashen savanna, volcanic crater.
- **Layers:** surface mesa; dripstone and thermal caves.
- **Atmosphere:** fogColor #1B4B4B, ambientLight 0.2 (kept).
- **Place kind:** one place composed round the centre: teal inside, red rock outside, the edge between them.
- **Baseline wrong (W9g b1 full):** grass_block 53.7% of ground: warped_forest and crimson_forest on the overworld preset take its grass (both "features name trees, none": no fungi grow); warm_ocean 16.7% on dry land (sea at -55); red rock only partly (terracotta 7.2, red_sand 5.4, red_terracotta 2.3); upside_down_forest 19.4% and maple_woodland 9.7 are not mesa; no mesa landforms (plateaus 2.9).
- **v2 responsible:** overworld preset with Nether biomes and no palettes; weights warped_forest 21.8, maple_woodland 15.4 (1.21.1 scarlet_mountains), upside_down_forest 12.1 (inverted_forest).
