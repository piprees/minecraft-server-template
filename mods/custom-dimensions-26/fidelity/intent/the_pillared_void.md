# the_pillared_void — noise:end, no central island; place kind: place (an empty void of pillars)
> "Lonely pillars in the void. Spires, a dragon's skeleton, nothing else — the endermen's oldest construction, predating even the tiled expanse. Whatever they built here, they built it for a dragon. The dragon is dead. The pillars are not finished. They rise through the frame, through whatever you built beside it."

- **First:** a ruined pillar near spawn (wants ruined_pillar near_spawn) rising from the void.
- **Mostly:** void, with tall thin pillars and spires. **Rarely:** a dragon skeleton, small islands, a monolith near the border.
- **Layers:** void below and around; vertical columns; almost no surface.
- **Atmosphere:** near-black: skyColor and fogColor #0B0B14, ambientLight 0.05 (kept).
- **Place kind:** one place, deliberately empty.
- **Baseline wrong (W9g b1 full):** it is ordinary End islands, not pillars: void 51.9% (`surface.voidPercent`), island landforms 31.1%, `terrain.surfaceHeight.p95` 64 and max 78 (no tall columns); ice_starfield 14.1% of biomes (snow and packed ice 14.1% of ground) where 1.21.1 lists crystal_peaks; highlands, barrens and the_end "mostly void".
- **v2 responsible:** end preset with `endIsland: false` (outer islands everywhere); weights put ice_starfield highest (14); the kit has no pillar terrain on the end preset.
