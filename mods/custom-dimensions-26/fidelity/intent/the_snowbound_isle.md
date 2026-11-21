# the_snowbound_isle — clone of the_aviary (1.21.1 paradise_lost); place kind: place (one island)
> "A snowbound island adrift in the sky. Fir forests and drifts on floating ground — animal country where the cold keeps everything honest. The towers and vaults here grew naturally, like the trees."

- **First:** snowy plains (spawnFilter snowy_plains), an igloo and a lantern guide post near spawn.
- **Mostly:** fir (spruce) forest and snow drifts on one floating island. **Rarely:** frozen cliffs, a snowy cherry grove.
- **Layers:** surface of one island; the void round it; no roof.
- **Atmosphere:** cold, snowing (1.21.1 biomes all snowy); no `environment`.
- **Signature structures:** igloo, aurel towers spread, frozen dungeon near border.
- **Place kind:** a place: one island, "adrift".
- **Baseline wrong (W9g b1 full):** golden_skygrove is the largest biome (31.8%: 1.21.1 autumnal_tundra mapped to it), with oak and poplar (oak 39.5/ha of 109.1 trees/ha); snowIce 48.9 for a "snowbound" world; void 7.4 (`surface.voidPercent`): continuous land, not one island adrift; `vertical.layerColumnsPercent.sky` 13.4 (stacked islands).
- **v2 responsible:** clone of the_aviary (its full island field), weights golden_skygrove 31.9.
- **Conflict:** "one island" needs a shape (radial layout on floating islands) that a clone cannot take.
