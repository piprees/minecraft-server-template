# the_slatemouth — void, Nether effects; place kind: place (an empty mouth)
> "A basalt-and-soul void, and nothing else — no floor, no horizon, no lava left to reflect a sky. Whatever was here, the void consumed it completely. The piglins' word for this place translates roughly as 'the mouth that swallowed', and it does not kill what it swallows: it puts it down in the capital."

- **First / mostly / rarely:** nothing: void in a red-black haze. The arrival platform is the only ground.
- **Layers:** none; the void is the place.
- **Atmosphere:** skyColor and fogColor #1B0B0B, ambientLight 0.05, Nether effects, ultraWarm, anchors work, beds do not (kept).
- **Signature structures:** none (1.21.1 structureDensity none); falling returns players to the capital (exits).
- **Place kind:** one place, deliberately empty.
- **Baseline (W9b):** void 100.0% (`surface.voidPercent`), no ground: matches. The seven listed biomes are bound by `climate` but the void generator takes only its first (`biomeExpression.listedNotFound` six), so the "basalt-and-soul" character exists only as one biome's fog and particles (nether_wastes).
- **v2 responsible:** `generator` void ("its first vanilla biome": README § Generators).
- **Conflict:** "basalt-and-soul" suggests the basalt deltas' and soul sand valley's air (particles, sounds) over the void; only one biome reaches it.
