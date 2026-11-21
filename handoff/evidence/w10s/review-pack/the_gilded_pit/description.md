# the_gilded_pit — noise:nether; place kind: place (one pit seen from one ledge)
> "The piglin capital. Every portal drops you at the same ledge overlooking what remains: a bastion, a forge, a palace, a forbidden castle — gold in every wall, treasure in every ruin. Nothing within arm's reach of the landing. Greed pays and costs."

- **First:** a ledge high on a wall with the pit below and the capital in it.
- **Mostly:** the pit: gravel and wastes, basalt, gold-seamed blackstone. **Rarely:** anything near the landing (structures near_border only).
- **Layers:** roof, a high ledge, the pit floor far below.
- **Atmosphere:** no 1.21.1 environment; vanilla Nether.
- **Signature structures:** sanctum, blackstone walls, nether towers near the border (1.21.1 wants); bastion, forge, palace, forbidden castle in the description.
- **Place kind:** one composed place: a pit round a ledge.
- **Baseline wrong (W9g b1 full):** no pit: the terrain is the vanilla Nether's (`terrain.reliefMedian` 117, landforms plains 28.6, hills 17.9, mountains 17.8, basins 8.7); gold is absent from the surface (no gilded_blackstone or gold in the top ground blocks: netherrack 37.0, basalt 23.6, bedrock 11.8); lava 31.6%.
- **v2 responsible:** nether preset with no zone or layout; blackstone_basin ("gilded seams") is not listed; basalt_deltas weight 34.3 is the largest.
- **Conflict:** "the same ledge overlooking" needs a shaped pit round spawn, which the kit cannot build on the nether preset.
