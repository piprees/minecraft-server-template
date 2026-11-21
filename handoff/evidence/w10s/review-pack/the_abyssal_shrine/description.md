# the_abyssal_shrine — clone of the_aviary (1.21.1 paradise_lost, seaLevel 90); place kind: place (a ring round a drowned hollow)
> "A shrine over the abyss — plateau islands ringing deep drowned hollows. Something is worshipped here, or was. Guardians circle below; the sky above is indifferent."

- **First:** calcite crags and a plateau rim (spawnFilter calcite_craglands, continental_plateau, grand_glade) looking down into deep water.
- **Mostly:** plateau islands in a ring, deep cold water in the hollow. **Rarely:** sculk and the deep dark under it (1.21.1 deep_dark, dripstone_caves), a vault forced at 180, -140.
- **Layers:** surface plateaus; deep water below them; underground deep dark.
- **Atmosphere:** dark blue: skyColor #1B3B6B, fogColor #0E2A44, ambientLight 0.15 (kept).
- **Signature structures:** monument (guardians), underwater dungeon, warden monument near border.
- **Place kind:** a place composed round the hollow: rim of plateaus, water in the middle.
- **Baseline wrong (W9g b1 full):** deep_ocean 37.5% of the map but 71.3% of its columns are land (`biomeExpression.biomes.minecraft:deep_ocean.placementPercent.land`): an ocean biome on islands; water cover 20.3; four listed biomes unplaced (umbral_wastes, windswept_isles, deep_cold_ocean, dripstone_caves: `biomeExpression.listedNotFound`); deep_dark on the surface (17.2%) with sculk as 10.2% of ground; golden_skygrove 20.5 (gold, not "indifferent").
- **v2 responsible:** clone of the_aviary with seaLevel 90 (no ring); weights put four biomes at 1; `id-mapping` makes shadowlands umbral_wastes and the paradise glades golden_skygrove.
- **Conflict:** the ring wants a layout or basin terrain that a clone cannot take.
