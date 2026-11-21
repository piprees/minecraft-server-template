# the_starwell — noise:floating_islands, sea at -64, y 0–256; place kind: place (a pocket of night sky)
> "A well of night sky — a few silvered islands adrift around a monolith. The endermen's last gift: a pocket of void where nothing hunts, falling sends you home, and the stars are close enough to touch."

- **First:** night sky (fixedTime 18000, End sky effects) and a mystical archway forced at 200, 68.
- **Mostly:** void and stars; a few small pale islands. **Rarely:** shadowlands patch (168, -140), a monolith.
- **Layers:** sky islands only; the void below is the point.
- **Atmosphere:** skyColor #0B0E2E, fogColor #05060F, ambientLight 0.15, fixedTime 18000, effects minecraft:the_end (kept).
- **Signature structures:** monolith and ruined pillar (1.21.1 wants), the archway.
- **Place kind:** one small place (border 256).
- **Baseline wrong (W9g b1 full):** a desert, not a well of sky: desert_oasis 68.4% of biomes, sand 60.8% of ground (1.21.1 mirage_isles mapped to desert_oasis); void 5.3% (`surface.voidPercent`), land on 94.7% of columns (`vertical.layerColumnsPercent.surface`); "a few islands" reads as continuous land with relief 129.
- **v2 responsible:** floating_islands with no `terrain` (the preset's dense masses); `biomeLayout` weight desert_oasis 68.4; windswept_isles listed but unplaced.
