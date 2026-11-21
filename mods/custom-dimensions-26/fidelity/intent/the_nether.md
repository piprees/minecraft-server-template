# the_nether — noise:nether (base world); place kind: meeting (the five Nether biomes)
> "The nether — piglin heartland, 1:8 compressed. Fortresses and bastions in reach, Incendium's sanctum near the rim. Every civilisation that touched this place left infrastructure; only the piglins called it home."

- **First:** a fortress or bastion within reach of the portal (1.21.1 seedRoll wants fortress, bastion, piglin_village spread; spawnFilter nether_wastes, warped_forest, quartz_flats).
- **Mostly:** netherrack wastes and fungal forests under the roof, lava sea in the low ground. **Rarely:** the sanctum near the rim (v2 `nova_structures:sealing_halls` near_border).
- **Layers:** roof over everything, cavern floor as the surface, lava sea below.
- **Atmosphere:** vanilla Nether (no 1.21.1 `environment`).
- **Place kind:** a meeting of the Nether's biomes; "piglin heartland" asks for the crimson forest (piglins, hoglins) to carry weight.
- **Baseline wrong (W9b, the_nether/dossier.json):** warped forest absent (`surface.biomesPercent` lists four: nether_wastes 60.4, crimson_forest 16.4, basalt_deltas 12.1, soul_sand_valley 11.1); nether_wastes 60.4% is one waste, not a meeting; 1.21.1's spawnFilter names `incendium:quartz_flats` and the v2 definition lists no biomes, so the Incendium-era variety is gone (`NEW.biomes` empty). Lava cover 22.5 is within the Nether's character.
- **v2 responsible:** `biomes` absent, so the preset's vanilla five; no `biomeLayout`. The kit refuses `terrain` on the nether preset (README § Generators), so relief comes from vanilla only.
- **Conflict:** the 1.21.1 Nether was Incendium's (AGENTS § Dimensions: `minecraft:nether` carries Incendium's surface rule); v2 has only vanilla plus library Nether biomes (quartz_flats, nether_grasslands, crimson_pinewood ...). Whether the base Nether gains library biomes is an owner choice.
