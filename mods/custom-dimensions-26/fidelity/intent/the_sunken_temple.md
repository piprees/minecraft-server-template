# the_sunken_temple — clone of the_aviary (1.21.1 paradise_lost, seaLevel 90); place kind: meeting (islands and drowned basins)
> "Temple-hunting across drowned skyland basins. Monuments and fortresses sit where the water pooled between paradise islands — half-submerged architecture that predates the flooding. Something sacred was kept here, and the rising water couldn't wash it away."

- **First:** skyland highlands at spawn (spawnFilter paradise_lost:highlands) looking over water pooled between islands.
- **Mostly:** warm shallow basins between paradise islands, jungle on the islands. **Rarely:** monuments and ocean temples half above the water.
- **Layers:** surface islands with water held between them at y 90; sky islands above; the void below the basins.
- **Atmosphere:** no 1.21.1 `environment`; warm (tradewinds, warm ocean, jungle).
- **Signature structures:** monument, underwater dungeon spread, ocean temple near border (v2 `roll.wants`; 1.21.1 noise maritime dense).
- **Place kind:** a meeting: islands and the water between them.
- **Baseline wrong (W9g b1 full):** water covers only 21.3% (`surface.fluidCoverPercent.water`) of a "drowned" world; the biggest biome is hanging_gardens 25.5 (an underside biome) and desert_oasis 13.1, neither in the 1.21.1 list's spirit; warm_ocean listed but not placed (`biomeExpression.listedNotFound`); regions are small: largest 9.3, top3 21.3, 26 major regions (`placeness.biomeRegions`).
- **v2 responsible:** clone of the_aviary: a sea level of 90 over floating islands fills the void below 90 (worldgen.md § Terrain library) rather than pooling between islands; `id-mapping` turns tradewinds into hanging_gardens and mirage_isles into desert_oasis.
- **Conflict:** "basins" want `basin_islands` with `liquids.basins`, which a clone cannot take: un-cloning is the owner's call.
