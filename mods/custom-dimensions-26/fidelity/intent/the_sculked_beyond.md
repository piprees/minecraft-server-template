# the_sculked_beyond — noise:caves; place kind: place (one sculk-eaten cavern)
> "Sculk-eaten caverns. The deep dark outlived the world it consumed and tunnelled on — this is where the underground sculk broke through from the ancient cities. The humans who explored too deep triggered this, and the infection never stopped. It does not stop at the portal either: what stands near the frame is what it eats next."

- **First:** a sculk dungeon and camp at spawn (wants near_spawn) in near-total dark.
- **Mostly:** sculk over everything: deep dark, infested and mantle caves. **Rarely:** fungal and tuff caves, an ancient city near the border.
- **Layers:** roof, cavern floors, deep tunnels.
- **Atmosphere:** fogColor #0B2B3B, ambientLight 0.03, no skylight, monsters at any light (kept).
- **Place kind:** one place: sculk everywhere.
- **Baseline wrong (W9g b1 full):** no sculk: dripstone_caves is 61.8% of the floor, and deep_dark, umbral_wastes and withered_forest are listed but unplaced (weight 1 each: `biomeExpression.listedNotFound`); sculk absent from the top ground blocks (stone 25.6, gravel 23.2, dripstone 13.9); water 29.8% of the floor.
- **v2 responsible:** weights dripstone_caves 60.5, deep_dark 1: the reverse of the words.
