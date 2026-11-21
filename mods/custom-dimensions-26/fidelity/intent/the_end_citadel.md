# the_end_citadel — noise:end, no central island; place kind: place (citadel highlands)
> "Citadel country — where the endermen built their densest urban centres. End cities and phantom citadels saturate the highlands. No ruins here; this is still maintained. Maintenance does not stop at the frame — build beside the portal and the citadel absorbs it."

- **First:** an end city on highland ground at spawn (wants end_city, enderwatch_tower near_spawn; spawnFilter end_highlands).
- **Mostly:** broad end-stone highlands carrying cities. **Rarely:** barrens and islets between them.
- **Layers:** island surfaces over the void; no roof.
- **Atmosphere:** vanilla End (no 1.21.1 environment).
- **Signature structures:** end cities, phantom citadels, ender spires, monoliths; shuns ruins (giant skull, ruined pillar, endscraps).
- **Place kind:** one place: highland city country.
- **Baseline wrong (W9g b1 full):** biomes are laid over the void independently of the islands: end_highlands has 60.5% of its columns over void (`biomeExpression.biomes.minecraft:end_highlands.placementPercent`), five of six biomes "mostly void"; land regions are tiny (`placeness.biomeRegions.largestPercent` 1.3); highlands only 10.7% of the map; ice_starfield (from 1.21.1 crystal_peaks) is a snow biome in citadel country; no highland relief (`terrain.surfaceHeight.p95` 66, reliefMed 8).
- **v2 responsible:** `generator` end with `endIsland: false`; `biomeLayout` regions spread over the overworld climate (README § Generators: End lists take overworld temperature and vegetation), which does not follow the End's island shape. The kit refuses `terrain` on the end preset.
