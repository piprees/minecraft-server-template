# W10u kit gaps

## Sloped shore for a ring hollow (the_abyssal_shrine)
- Asks: a lake in a drowned hollow, its shore not a machine-made moat wall (blind review: "a dead-level band of constant width hugging the whole shore").
- Lacks: `hollowApron` holds ground at the rim's crest height, level, from the hollow's edge outwards; a narrow apron (16) or a wandering edge (`hollowJitter` 90) reopens the void between shore and ring, which the owner ruled out.
- Shape: `hollowApronSlope` (blocks of fall from the rim's crest to the water across the apron) and `hollowApronRelief` (noise on the apron's height), so the apron reads as a beach or a bench, not a ledge.
- Also useful to: the_sunken_temple (basin shores), any `basin_islands` ring.

## Structures over a sky world's islands (the_violet_spire, the_sunken_temple, the_abyssal_shrine)
- Asks: endermen architecture on the End-ward band, temples at the water, a shrine on the rim.
- Lacks: in `structures.list` the sky worlds' library biomes are in no mod's biome tags, so no organic start; a `want` bypasses the biome check but jigsaw and template starts that look for ground at a fixed height still give no start (enderwatch_tower, mystical_archway, small_tower, shrine_tower, ocean_temple, monument, underwater_dungeon, warden_monument).
- Shape: SP2's site filters with an island-top seat (sample the island's own surface for the start height) and a water-line seat (the basin's level).

## Fog over a Nether pit
- Built this round as `atmosphere.fogDistance` (start, end), held over the biomes with `overBiomes`.

## Upper islands down to the basin water (the_sunken_temple)
- Asks: the owner: "the water level needs to be higher to touch the bottoms of the skyland islands".
- Lacks: `tierGap` (16 at least) keeps open sky over the tier below, the basin's water included, so an upper island's underside stops 1-6 blocks over the water; a deeper basin (`basinDepth` 26) left the frames unchanged, and `tierSpacing` cannot go under 32.
- Shape: `tierGapOverWater` (0 lets an upper tier's underside dip into a lower basin's water), or a tier mode that seats the upper islands on the water line.
