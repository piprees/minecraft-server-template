# the_violet_spire — clone of the_aviary (1.21.1 paradise_lost); place kind: meeting (two worlds overlapping)
> "Violet spires in the sky — where endermen architecture meets the natural skylands. Wisteria islands and amethyst canyons, aurel towers and birdcages. Arcane but not hostile; a place where two worlds overlapped and neither broke."

- **First:** lavender and amethyst at spawn (1.21.1 spawnFilter lavender_fields, amethyst_canyon) under a violet sky, an aurel tower forced at 60, 60.
- **Mostly:** wisteria islands and violet spires; skyland meadows and cherry. **Rarely:** crystal peaks (patch at -240, 200), a jungle patch (-420, -230).
- **Layers:** sky islands (stacked), surface; "spires" and "canyons" are vertical: tall thin masses and cuts.
- **Atmosphere:** violet: 1.21.1 skyColor #6B4B9B, fogColor #3A2352, ambientLight 0.25 (kept in v2 `atmosphere`).
- **Signature structures:** aurel tower (v2 `mss:large_tower` near_spawn), birdcages, wizard towers, mystical archways.
- **Place kind:** a meeting of two: endermen (End-coloured crystal, purpur) and natural skylands (wisteria, lavender).
- **Baseline wrong (W9g b1 full):** violet is nearly absent: wisteria_grove 1.1%, lavender_fields 4.5% of `surface.biomesPercent`; ground is grass 57.8 and snow 23.5 (`surface.groundBlocksPercent`), snowIce 27.3 from jagged_peaks 15.1 and ice_starfield 12.5 (1.21.1 crystal_peaks mapped to ice_starfield). Amethyst canyon mapped to crystal_hollows, a cave biome (17.2% of the surface). No spires: `terrain.slopes.cliffPercent` 8.5 on plateau land; void 9.4.
- **v2 responsible:** `generator` clone of the_aviary, so no terrain of its own (clones take only `liquids`/`materials`); `biomeLayout` weights put wisteria_grove at 1 and stony_peaks at 1 of 94.6; `id-mapping` turns crystal_peaks into a snow biome.
- **Conflict:** "violet spires" are terrain a clone cannot have; the owner should accept un-cloning (floating_islands plus `spires`-like terrain) or accept spires as structures only.
