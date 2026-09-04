# What is live through a portal

What a companion client actually sees through an immersive portal, per thing a
player might expect. Read [`portals.md`](portals.md) first for how the
projection is built; this file says what reaches the far side of it.

Every row is **measured** on the running local stack or **derived** from the
wire format, and says which. The nexus rig is the reference: overworld frame at
`1500-1501, 101-103, 1500` to `adventure:the_crimson_nexus`, scale 2.0.

## The wire is the whole answer

`CompanionPayloads.Projection` is sixteen fields:

```
destination, apertureOrigin, aperture, portalAxis, normal, origin,
sizeX, sizeY, sizeZ, states[], light[], skyColor, fogColor,
grassColor, foliageColor, waterColor
```

**Only `states[]` and `light[]` can change.** The five colours are sampled once
per payload, the rest is geometry. Nothing in the record can carry an entity, a
sky, a time of day or a weather state, so those are absent by construction
rather than slow — no refresh rate reaches them.

`ProjectionStream.sameContent` compares `origin`, the three sizes, `normal`,
`states[]` and `light[]`. An unchanged destination sends nothing at all.

## The table

Evidence grades, used in the last column and meant literally:

- **measured** — observed on the running stack this session.
- **bytecode** — read out of the mapped 1.21.1 jar or this mod's compiled
  classes. Not an opinion about the source, but not a runtime observation.
- **derived** — follows from the wire format above. A row that is absent from
  the record cannot appear however the code behaves.

| thing | verdict | latency to the client | evidence |
| --- | --- | --- | --- |
| blocks | **live** | **3s** | measured — a block placed at the arrival produced a send in 3s, three times, with two controls also at 3s |
| fluids | **live as state**; the flow animation is always local | **3s** | measured — water placed at the arrival, one send in 3s |
| light on the far side | **live** | **3s** | measured — a sea lantern produced a send in 3s AND moved `destLight.blockMax` from 3 to 14 on the client |
| block entities drawn as models (furnace, barrel, beacon) | **live** | not measured | bytecode: `getRenderType` returns `MODEL`. The runtime reading was taken while the projection was down and is discarded |
| block entities drawn by a renderer (chest, ender chest, bed, shulker) | **state travels, the block is not drawn** | 21s for the state | bytecode: `ENTITYBLOCK_ANIMATED`, and `ProjectionMesh.build` captures only `MODEL`. Measured: placing a chest did send — the STATE is on the wire — so the absence is in the mesher, not the channel |
| block entities with no model (sign, banner) | **absent** | never | bytecode: no `getRenderType` override, so `BlockWithEntity`'s `INVISIBLE` |
| entities — players, mobs, items, arrows | **absent** | **no send in 45s** | **measured** — a pig summoned at the arrival produced no send in 45 seconds, bracketed by block controls that sent in 3s on either side |
| sky | **absent** — a flat quad, not a sky | never | bytecode: `backdropPolygon` paints `fogColor`, falling back to `skyColor` |
| time of day | **absent from the wire**; the far side is drawn at the SOURCE dimension's time | inconclusive | derived. The probe saw a send 9s after `/time set` against a 3s block latency, with no mechanism that would explain it — treated as a coincident background send, not as evidence either way |
| weather | **absent** | never | derived — no field, and one save-wide flag serves every dimension |
| the portal's own light | **live**, fixed, does not track the destination | constant | **measured** — see Light, below |

**Latency is 3 seconds for everything the wire carries**, from five independent
readings. It is not the 1.0-1.6s the cadence constants predict
(`refreshInterval` 4, `* 5` for the companion rebuild, `* 4` again while
stationary), and the derivation should not be quoted in place of the
measurement. One outlier at 21s and one window with no send at all show the
sampler can stall when the arrival's chunks are not resident.

### Block entities split on render type, not on having a block entity

The maintainer's three examples land on both sides of the line: a **furnace
appears** and a **chest and a banner do not**. `ProjectionMesh.build` captures
`BlockRenderType.MODEL` quads and fluid quads only, and `ProjectionView`
returns `null` for every block entity, so a block whose visual comes from a
block-entity renderer has nothing to draw.

## The still-shot, quantified

Measured over one static view at the nexus rig, from the client's own log:

| | |
| --- | --- |
| rendered frames | 16,710 |
| destination meshes built | 33 |
| ratio | one rebuild per ~506 frames |

The rebuilds arrive in bursts while the far side's chunks load — quad count
climbing and falling through 2150, 4262, 2640, 3588, 3032, 2376, 4616 — then
stop entirely while nothing over there changes. **That is the design working**:
`sameContent` suppresses a resend for a destination that has not moved.

So the cadence is not why it reads as a photograph. Blocks refresh inside two
seconds. It reads as a photograph because everything that moves — every entity,
the sky, the clock, the weather — is absent from the channel.

## Light

The portal has two light paths and they answer different questions.

**The aperture's own light reaches the source world.** `PlayerProjectionState`
paints `minecraft:light` at the portal's configured `lightLevel` over the
opening, as a fake block sent to one viewer. Measured at the nexus rig: the
client holds `minecraft:light` at luminance 11 in all six opening cells and
computes block light 11 at each, where the server's own world reads 4-7 at the
same cells — a gain of +4 to +7. Stepping out of the opening towards the viewer
the client reads **11, 10, 9, 8**, one level per block, which is the signature
of a level-11 source in the frame.

**It does not track the destination.** The level is a constant from config, so a
portal onto a dark cave lights the source world exactly as much as one onto a
desert at noon. In an already-lit room the gain is invisible — the nexus rig
stands in a torch-lit room whose ambient is 4-7, and the crucible arrival's
ambient is 9-12 against the same level 11. Making the aperture level follow
`DestinationGlow.light` is the lever if portal light should read as light
through a portal; it is a design change and is not made.

**The destination's light reaches the window's pixels only.** `ProjectionStream`
packs the far side's sky and block light per cell, and the client meshes with
it through `ProjectionView.getLightLevel`. Measured: `destLight` block 0..3, sky
0..15 mean 6.2; the mesh built from it block 0..15, sky 0..15 mean 8.5. It
varies rather than being a constant 15, which is what proves the grid is being
consulted. The light LEVELS are the destination's; the lightmap that turns a
level into a colour is the client's own.

**`DestinationGlow` tints the opening's particle, and only that.** Measured at
the nexus: destination light 9, biome fog `#E0E9FF`, brightness 0.74, and the
configured `#AF2B2B` emerging as `#97747E`.

**It is inert on 33 of the 82 shipped dimensions.** `PortalHelper.apertureEffect`
returns a configured `particleType` before the glow is applied — a named effect
carries its own colour and is not the mod's to shade. Both live rigs are
`particleType: null`, which is why the glow applies there.

## Measuring it again

`scripts/e2e/e2e-c2-live-table.sh` fills the rows marked "awaiting the RCON
probe": it writes only inside the destination dimension, reads the two logs and
the bridge, restores every cell, and puts a positive control beside every
negative row. `scripts/e2e/e2e-c3-portal-light.sh` measures the light rows.
Both need `CUSTOMDIM_LOG_LEVEL='debug'` in the consumer `.env` —
`companion-send:projection` is a DEBUG line, and without it every row reads
absent ([T63](../../TROUBLESHOOTING.md#t63)).
