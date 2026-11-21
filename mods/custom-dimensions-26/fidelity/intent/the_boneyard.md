# the_boneyard — noise:nether; place kind: place (one battlefield)
> "The battlefield. Piglins met endermen here and neither side won. Bones of giants lie in soul sand, a crashed ship rusts in the distance, and the sculk that crept in wasn't either side's weapon — it fed on the war itself."

- **First:** soul sand and ash under a bone-pale haze (1.21.1 spawnFilter soul_sand_valley, ash_barrens; fogColor #D8C8A0).
- **Mostly:** soul sand plains with giant bones (1.21.1 bone_reef, sulfuric_bone_reef, soul_plain), ash heaps. **Rarely:** sculk (1.21.1 deep_dark), a crashed ship, withered trees.
- **Layers:** Nether roof, cavern floor; sculk below.
- **Atmosphere:** pale fog #D8C8A0, ambientLight 0.08 (kept in v2).
- **Signature structures:** nether graveyards, dungeons, infernal altars (1.21.1 wants); shuns piglin villages.
- **Place kind:** one place, a battlefield: soul ground with bones throughout.
- **Baseline wrong (W9g b1 full):** bone is nearly invisible: no bone_block in the top 8 ground blocks (soul_soil 28.9, netherrack 20.4, soul_sand 11.8, tuff 9.2); lava 24.9% of the floor; the bone reefs have no library equivalent (v2 maps them to gravel_desert 4.2% and quartz_flats 8.4%); withered_forest trees 209/ha and mycotoxic_undergrowth 9.1% put forest on a battlefield; regions are small (largest 14.9, 28 major, specks 5.3).
- **v2 responsible:** nether preset (no terrain, lava sea at 32); weights put soul_sand_valley 32.5 but withered_forest 19.9 and mycotoxic 8.5; deep_dark at weight 1 is on the surface, not below.
