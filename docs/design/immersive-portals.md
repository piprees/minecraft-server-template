# Immersive portals — how it should all work

The governing document for the portal view: what it is FOR, how it is built, and
what it must survive. `docs/mod-internals/portals.md` covers the portal
subsystem's internals; `TROUBLESHOOTING.md` carries the traps; `.handoff/SHADERS.md`
carries the shader measurements and their conditions.

**Every portal decision — geometry, lighting, entities, shaders, Distant
Horizons, performance — is judged against this document.** Where a proposal and
this document disagree, the proposal needs an argument, not a workaround.

## The illusion, stated once

**A portal is a doorway. Not a screen, not a window, not a picture of somewhere
else — a doorway, minus the door, cut into a wall of reality.**

The wall is the blocks it is made of, and those blocks are real, whole, and one
block thick. What lies through the doorway is simply *there*, the way the next
room is there when you look through a door frame in a house. You do not think of
the next room as a rendered image; you think of it as the room. That is the
standard.

Everything below follows from that sentence. When a question arises that this
document does not answer, ask it of the doorway: *would a real doorway in a real
wall behave like this?* That question has settled every geometry argument in
this system so far.

**Acceptance, as a test rather than a feeling:** fire an arrow through a portal
and watch where it goes.

### What breaks the illusion, in order of how badly

1. **Geometry that does not line up.** The picture starting in the wrong place,
   the wall reading thinner than it is, the image sliding as you walk past.
2. **Light that does not join up.** The far side lit by the near side's sun, or
   flat where the near side is shaded, or blown white by a shader pack.
3. **Things that stop at the threshold.** An arrow that vanishes at the frame, a
   player who disappears mid-stride, an item that pops in on the far side.
4. **A hitch on crossing.** A pipeline reload, a chunk pop, a black frame.
5. **Missing detail.** No particles, no sky, unmipmapped leaves. Real, accepted
   for now, and the least damaging of the five.

---

# Part 1 — The geometry must line up

## The surface is the aperture block's destination-side face

The opening cell spans `[plane, plane + 1)` on the normal axis. **The destination
surface sits on the face the destination lies behind** — `plane + 1` where the
normal points high, `plane + 0` where it points low. `CompositeQuad.surface`
owns that, and every reader calls it.

That single choice is what makes the doorway read as a doorway:

- **The frame ring's inner faces are drawn for their full one-block depth, from
  both sides.** The block is whole because all of it is drawn. A surface that
  bisected the cell would paint over the far half of every inner face, and the
  wall would read half a block thick from either side — visibly so at a grazing
  angle, where the covered fraction changes as you walk past.
- **The destination begins exactly where the wall ends.** That is what a doorway
  is.
- **The two clip rectangles are the hole's two real mouths**, at `plane` and
  `plane + 1`. Looking obliquely through a hole in a thick wall, the far mouth
  limits your view, exactly as in life. `windowLabel` prints both on the emit
  line; two distinct values one block apart is the runtime witness that the
  tunnel is intact.
- **Every normal-axis coordinate is an integer block face**, so no half block
  enters any depth calculation.

**The surface is also the slab's own near face, so nothing has to be moved to
meet it.** `surfaceOffset()` is exactly 0.0 on both signs and `meshShift` is
`{0, 0, 0}` — the destination mesh draws precisely where the server placed it.
The renderer prints `surface=0.00`; anything else means the server's layout has
moved and the arithmetic, not a constant, is what will catch it.

**The depth band is the aperture block's own thickness**, `[plane, plane + 0.9]`,
fixed by two invariants: it must start no nearer than the camera-side face, so a
real block in front of the frame is nearer and wins, and end short of `plane + 1`,
where source geometry behind the opening begins. `SLICE_FRACTION` is 0.9 of that
block and `BAND_LIMIT` is the same reach measured from the near face, pinned to
each other by test. `sliceFor` derives the band from
`apertureMaxCoord - apertureMinCoord` — the block's own measured depth — so there
is no literal that can drift out of step with the geometry.

**`bandOpens()` still warns on a 3x2 opening** (`reach=1.41 over 0.9`): seen
obliquely enough, the slice cannot cover the whole opening and the destination is
drawn in front of source terrain at the far side. It is a one-shot WARN with no
behaviour hanging off it, and it is not cleared.

## The convention drifted because it was written down four times

There was no shared constant for "where the surface is", and four independent
`+ 0.5` literals carried it — which is precisely how a convention rots:

| site | side | role |
| --- | --- | --- |
| `ClientProjection.java:83` | client | `planeCoord` — the origin of all of it |
| `CompositeQuad.java:34` | client | the spectator composite quad |
| `PortalCamera.java:49` | client | `destinationPlane`; staged for the composited path, currently unwired |
| `DestinationFeed.java:308` | server | `planeN` — gates which chunks are sent at all |

**`ProjectionVolume.java:292` looks like a fifth and is not.** Its `+ 0.5` is the
aperture cell's CENTRE, compared against block centres (`blockN + 0.5`) as a side
discriminator in `seesThroughOpening`'s source-block occlusion mask. Moving it to
a face would misclassify a source block sitting in the aperture cell's own layer.
It does not call the helper and it is not a reader of the surface convention.

`DestinationFeed` is the sharpest illustration: `:306-307` sets `a1 = onA + 1.0`
under the comment *"A block spans `[n, n+1)`; the opening's far edge is the far
face"*, and `:308` immediately sets `planeN = coord + 0.5`. **Far-face tangential
bounds and a bisecting normal, in one loop.**

**Rule going forward: one helper owns the surface coordinate and every reader
calls it.** A new reader added without one is how this drifts again.

## What does not move

The portal's block mechanics are integer cells end to end. `PortalSite` —
interior, egress, arrival, carving — never sees a fractional coordinate, and
`PORTAL_TARGETS` is keyed on exact `BlockPos` derived from interior cells.
**Ignition, traversal, breaking and the arrival zone stay where they are.** No
portal render geometry is serialised into `portal_links.json`, so this is not a
persisted-format change and an older jar reads a newer file unchanged.

---

# Part 2 — Light and environment must join up

**The two worlds should look like one continuous space, lit consistently across
the threshold.** Standing in the overworld at noon looking into a dim cavern
dimension, the cavern should be dim — its own dim, from its own config — and the
transition across the frame should read as a change of room, not a change of
renderer.

This is where the architecture is thinnest, and the cause is concrete: **the
destination is drawn at SOURCE coordinates, inside the source frame's own pass.**
A shader pack therefore shades it with the source world's shadow map, lightmap
and fog. Three consequences, all observed:

- **The dark entity.** Destination geometry shaded from the source shadow map
  goes dark head-on and correct obliquely — same cow, same light, same jar, with
  a hard shadow boundary across it. Cause established, fix outstanding.
- **The blown backdrop**, fixed at `4c8613ad`: the source lightmap and fog
  reaching destination pixels.
- **Two untested conditions remain** — the source lightmap term shows at night or
  underground, and the source `linear_fog` term shows across a long sightline.
  Both real, both invisible at the current test rig.

**With an Iris pack loaded, a destination view also takes its time and celestial
uniforms from the source dimension.** Iris reads `MinecraftClient.world` directly
in `IrisRenderingPipeline`, `ShadowRenderer` and its uniform classes. Accepted:
it bites only with a pack loaded.

## What the client needs and does not have

**The client mod needs the destination dimension's environment config the same
way the server has it.** `projection/v3` carries `skyColor`, `fogColor`,
`ambientLight`, `tintPalette` and `columnTints`. What is still missing:

- sky type
- fog density and distance profile
- **time and weather.** `RealtimeView.syncClock` sets the destination's time,
  time-of-day, rain and thunder from the SOURCE player's world, so a dimension
  declaring `environment.fixedTime` renders at the viewer's time instead. This
  is Part 2's first failure by construction — the far side lit by the near
  side's sun — and it is the one to fix first, because a fixed-time dimension
  is the case the config already describes and the client already ignores.
- whatever `settingsOverrides` the dimension declares that affect appearance

A destination `ClientWorld` is built with no time in it and vanilla's time
packet updates the player's own world only, so an unfed destination renders at
time 0 forever; borrowing the viewer's clock is what stops that. The fix is to
carry the destination's own clock rather than to stop syncing.

`custom-dimensions` already owns every one of these server-side, in the dimension
config it reads at boot. **Getting them to the client is plumbing, not research.**

---

# Part 3 — Things must pass through

Stand at a portal and watch a player walk through, an arrow fly through, an ender
pearl arc through, a dropped item tumble through. Each should be continuously
visible — near side, in the opening, far side — with no vanish, no pop, and no
flicker at the threshold.

**The arrow already travels; it cannot yet be seen.** `immersive/EntityPassthrough.java`
covers items, projectiles, experience orbs, falling blocks, vehicles and leashed
entities, driven per tick by `mixin/EntityTickPortalMixin.java`.

## A fed entity is FROZEN between snapshots — the velocity is discarded

**MEASURED, 251 samples.** A fed entity does not move at all between snapshots,
on any axis. Every held sample reads `dy = +0.00` exactly; each snapshot then
teleports it the whole distance the real entity travelled.

```
tick   y       dy      x        z
21592  106.8           3466.6   2597.7
21593  106.8   +0.00   3466.6   2597.7   HELD
21596  106.8   +0.00   3466.6   2597.7   HELD
21597  104.3   -2.50   3465.5   2597.0   moved
21605   97.2   -3.80   3463.6   2595.7   moved
21617   81.8   -5.70   3460.9   2593.9   moved
```

**The x axis settles it.** Gravity has no x component, so a copy that was merely
missing gravity would still track truth in x. Measured x jumps are **-1.1, -1.0,
-0.9, -0.8** — the full per-window displacement, decaying with the 0.99 drag.

**The cause, and it is narrower than "the client discards velocity".**
`Entity.onSpawnPacket` applies id, position, pitch, yaw and uuid — and **no
`setVelocity`**. `LivingEntity` OVERRIDES it and does call
`setVelocity(getVelocityX/Y/Z)`. `MobEntity` and `PersistentProjectileEntity`
declare no override.

**So a mob, villager or player copy already had its velocity; an arrow, item,
experience orb or falling block did not.** The acceptance test's own entity is
exactly the class that loses it, which is why every held sample read
`dy = +0.00`.

`DestinationEntities.Live.applyVelocity` now applies the spawn packet's velocity
from BOTH `spawn` and `move`. The feed re-sends a full `EntitySpawnS2CPacket` for
every present entity every snapshot, so a move has one to read;
`EntityTrackerUpdateS2CPacket` has no velocity accessor at all.

**The error is the entire per-window displacement**, `v*k + 0.05*k(k-1)/2`, which
**scales with speed and has no ceiling.** Measured jumps of 2.5-5.7 blocks at
~1.4 blocks/tick; a full-draw bow arrow at 3.0 blocks/tick would jump ~12. A
walking mob barely moves in a window and is nearly unaffected — **the artefact is
proportional to speed, so the acceptance test's own entity is the worst case in
the game.**

**Fixed at `4fc3af06`.** With the velocity applied, **x and z now track exactly**
— the copy runs the same drag in its own tick — and the residual is y only:
`0.05 * k(k-1)/2` = **0.30 blocks worst case at k=4**, corrected by the next
snapshot.

**That 0.30-block sawtooth was real all along, buried under an error 8-19x
larger.** Matching the server's gravity is what removes it, and it stays bundled
with suppressing `attemptTickInVoid` — restoring gravity alone loses entities
over unfed columns unrecoverably.

**Only a `LivingEntity` interpolates.** `Entity.updateTrackedPositionAndAngles`
and `PersistentProjectileEntity`'s override both ignore the step count and call
`setPosition` plus `setRotation`.

**`puppet()`'s `noClip` does not reach an arrow** —
`PersistentProjectileEntity.isNoClip()` reads bit 2 of the tracked
`PROJECTILE_FLAGS`, not the field the client sets — so a fed arrow runs its full
collision path against fed blocks.

## The destination ticks only when someone is standing in it

**MEASURED.** With a Carpet bot at the arrival: `entitySnapshots` 1 -> 70,
`entitiesSpawned` 24 -> 109, `entitiesMoved` 0 -> 1548, `entitiesRemoved`
0 -> 60, `apertureEntities` 0 -> 2, `destinationChunks` 6 -> 68. Without one,
nothing moves and `changed()` correctly suppresses every snapshot after the
first.

**So an acceptance bar counting snapshots must say who is standing where.** A
still destination yields one snapshot however long you wait, and that is correct
behaviour rather than a defect.

## The crossing seam — the identity does not survive, and that is vanilla

`EntityPassthrough.moveEntity` uses `Entity.teleportTo(TeleportTarget)`. A
cross-dimension move **recreates** a non-player entity in the destination and
leaves the original removed, returning the live arrival. So at the instant of
crossing:

1. Source world: the original is removed, vanilla sends `EntitiesDestroyS2CPacket`,
   the client drops it.
2. Destination: a **different** entity with a **different network id** exists, and
   the feed picks it up on its next pass.

No shared identity, no duplicate id, no two-index confusion — but a **gap**. The
source copy vanishes at the plane and the destination copy appears one feed pass
later, which is exactly what the acceptance test forbids.

**Three options, none measured. This is a maintainer decision, not a builder's:**

| Option | Cost |
| --- | --- |
| Shorten the entity cadence until the gap is under a frame or two | Bandwidth, and it never reaches zero |
| The server names the handover explicitly — old id, new id, tick — so the client carries the fed copy across | A new payload, and a seam the client interpolates |
| Accept the gap | A visible stutter at exactly the moment the acceptance test watches |

**RULED: the server names the handover, and the client INSERTS on it.** Old id,
new id, tick. The maintainer's standing preference — smoothest and most immersive,
accepting a performance cost and more work — settles the two open choices here:
the client carries the copy across itself rather than waiting for a snapshot, and
the cadence is not merely shortened to hide the gap.

**And naming it is not sufficient on its own.** At 3 blocks/tick an arrow crosses
the plane and the destination feed does not see it for up to `INTERVAL` = 4
ticks — **up to 12 blocks past the opening before the first snapshot carries
it**, with the source copy already gone. So the handover must let the client
**insert the carried copy itself at the crossing tick**, not merely learn what to
rename when a snapshot eventually arrives.

The existing code supports that with no reshaping: if the client pre-inserts the
carried entity under the NEW id, `EntityFeedPlan.apply` sees that id already held
on the next snapshot and issues a move rather than a spawn — no flicker, no
double entity. `anIdInBothListsStays` already pins the boundary case.

Two things not to trip over: **`retain` drops an unframed destination's
entities**, so a handover landing in the same tick as a `ProjectionClear` for the
last frame naming that destination would apply to entities just dropped — order
the handover first, or let it count as naming the destination. And **do not widen
`destination-entities/v1`**; the handover is a new record beside it, under a
channel id of its own.

**Why this does not breach the no-simulated-events rule.** That rule exists
because reproducing block updates, entity spawns, weather and time as individual
synchronised events is a *simulation of world state*, and a simulation always lags
the world it copies — so we poll and send what changed. **A crossing handover is
not state.** It is a correspondence between two ids that exists at exactly one
instant, which the server uniquely knows, and which **polling cannot recover even
in principle**: by the time the next snapshot arrives the source entity is gone
and the destination entity carries a different network id with nothing linking
them. A poll can describe the world; it cannot describe an identity that was
destroyed between two polls.

That is the test for any future event-shaped payload: **if a poll could eventually
discover the same fact, poll for it. If the fact stops existing before the next
poll, it has to be named.**

## Entity identity across two live worlds

Read from 1.21.1 bytecode, `net.minecraft.client.world.ClientWorld`:

- **The index is per-world.** Each `ClientWorld` holds its own
  `ClientEntityManager` and `EntityList`; `getEntityById` and `removeEntity`
  resolve through that world's manager alone. **A destination id colliding with a
  source id is harmless** — nothing consults more than one world.
- **`addEntity` replaces silently.** It first calls `removeEntity(id, DISCARDED)`
  on itself, then adds. Convenient for a snapshot re-apply; dangerous if an id is
  ever reused for a different entity in the same destination.
- **`addEntity` does NOT reassign the entity's world.** It touches only the
  manager. An entity constructed against `client.world` and added to a
  destination has a `getWorld()` that disagrees with the world holding it, and
  that disagreement surfaces differently in ticking, rendering and removal.
  **Construct against the destination world** — `EntityType.create(World)` is the
  only place the reference is set.

**Removal has three exits and all must be specified:** absent from a snapshot →
`removeEntity(id, DISCARDED)`; portal frame cleared → `PortalFrames.remove` fires
and the destination's entity map goes with it; destination torn down →
`DestinationWorlds.drop` takes the world and its manager wholesale. An entity
left in a world later dropped is collected with it, so the failure mode is a slow
leak within a live destination, never a crash — but the snapshot-absent rule is
still required or a mob that wanders out of the box stands there forever.

---

# Part 4 — The architecture

## Two paths, and the fallback must never regress

| | Companion client | Vanilla client |
| --- | --- | --- |
| Renders | The destination world, from a camera, masked into the aperture | A block slab streamed from the server |
| Extent | Client render distance + DH | 12 deep x 6 padding, `[16, 32]` clamped |
| Server sends | Portal geometry and destination identity. **No block data** | The slab, every 4 ticks |
| Fidelity | Live | Blocks only, refreshed on an interval |

**The slab is the fallback, not the baseline.** It exists so a player without the
mod still sees something, and it must keep working unchanged. Its cubic cost
(`ImmersiveSettings.java:46` — *"the slab grows with the depth times the square of
the radius"*) is a server-streaming constraint that does not bound a local render,
but it still bounds the fallback: leave those constants alone.

The companion gate is a handshake: `CompanionNetwork` registers a C2S
`Hello(modVersion)` carrying the release that built the client jar, and a player
joins `COMPANIONS` only when it equals the release that built the server's, so
version skew degrades to vanilla rather than to a hybrid. Nothing here is
maintained by hand — `release.yml` stamps one tag into both mods'
`fabric.mod.json`, the client reads its own through `CompanionVersion.current()`
and the server reads its own through `Artefacts.stackVersion()`. A Carpet bot
never handshakes, so **it is a vanilla client by construction** — which is how
both halves of a fallback assertion get measured in one run.

## A camera, not a simulation

Render the destination as a real scene from a transformed camera, masked into the
aperture. **Do not reproduce block updates, entity spawns, weather or time as
individual synchronised events** — that is a simulation of a world and it will
always lag the world it copies. Poll on a cadence and send what changed.

The scale is already spent, and **spending it twice is the trap.** The server
divides by a dimension's `scale` and bakes the result into a flat offset;
`toTarget` is then pure addition and the `dx`/`dy`/`dz` on
`CompanionPayloads.PortalFrame` are that offset. A camera that divides again is
right at scale 1 and wrong at `the_crimson_nexus` (2.0) and `the_crucible` (4.0).

## The one-pipeline-per-frame constraint — settled by measurement

**Any architecture that calls `WorldRenderer.render` twice in a frame is dead on
arrival.** This was established by measurement, not inference: a second render
per frame produced whole-screen corruption tracking camera rotation, and removing
it (`387d986e`) took the source control from 98.14% to 3.24%.

The mechanism, from bytecode: Iris's Sodium compatibility rebinds *its own*
gbuffer for every terrain draw from *any* `WorldRenderer`, choosing the terrain
program from a global pipeline manager and the viewport from a global static. Our
destination terrain lands in the pack's `colortex` set no matter which renderer
issues it, and our `finalizeLevelRendering` then runs the pack's composite and
final passes over it.

**That is not merely a constraint to work around — it is why the current design is
right.** The portal view SHOULD be part of the one frame the pack composites.
Drawing the destination inside the source frame's own pass is correct, not
tolerated.

Iris knows which pass is running from globals — `ShadowRenderer.ACTIVE`,
`MODELVIEW`, `PROJECTION`, `FRUSTUM`, `visibleBlockEntities`, all `public static`
— and its Sodium integration is pass-specific. **Two states are representable,
main and shadow. Nothing outside Iris can add a third.**

## Rules that hold whatever the path

- **Never write `MinecraftClient.world`.** Other mods read that field from other
  threads, so a destination world written into it is observable by code never told
  the world exists — Sound Physics Remastered caches a level clone per
  `ClientWorld` and NPEs on ours from the sound-engine thread. The destination
  reaches the render through redirects on the field's reads in `render`,
  `renderSky` and `renderWeather`, plus a held reference for
  `LightmapTextureManager.update`, which takes no world argument.
- **`WorldRenderer.setWorld` is destructive and must not be used per frame.** It
  calls `entityRenderDispatcher.setWorld`, assigns `this.world`, then `reload()`,
  `BuiltChunkStorage.clear()` and `ChunkBuilder.stop()` — a full teardown and
  rebuild of chunk render state.
- **The dispatchers need no mixin.** `EntityRenderDispatcher.setWorld` is public;
  `BlockEntityRenderDispatcher.world` is a public non-final field.
- **Every swap is set inside `try` and restored in `finally`, innermost last.** An
  exception mid-pass that leaves the client pointing at the destination is an
  unrecoverable client, and it reads as a crash in someone else's mod. A pass that
  has failed once does not run again until the frame counter resets — a per-frame
  exception is a per-frame log flood.
- **1.21.1's framebuffer has no stencil attachment.** `Framebuffer.initFbo`
  creates the depth texture as `GL_DEPTH_COMPONENT`; there is no stencil field and
  no stencil method. The stencil operations exist and have nothing to write into.
  **The aperture geometry is the mask.**
- **`Framebuffer.endWrite()` does not restore the previous binding** — it hard-binds
  the default. Capture the binding with `glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)`
  and restore through `GlStateManager._glBindFramebuffer`, never raw LWJGL: Iris
  tracks bindings and skips redundant binds, so a raw bind leaves its tracker
  stale and its next bind wrongly skipped.
- **`WorldRenderer.render` re-binds the main framebuffer from inside itself**, at
  bytecode 700, on every ordinary frame. Hand it an offscreen target and it
  returns having bound the main one. The fix is to make what it re-binds TO be
  correct — a `@Mutable @Accessor` on `MinecraftClient.framebuffer`, released in a
  `finally` — rather than enumerating who re-binds, which goes stale every MC
  version.
- **The mod is standalone.** Sodium cannot be assumed and must not be depended on.
  Build against the vanilla renderer; anything Sodium does is a consequence, never
  the mechanism.
- **Entities in a destination world set `ignoreCameraFrustum`.**
  `EntityRenderer.shouldRender` returns on that field before reaching
  `Frustum.isVisible`, which is where Sodium wraps the cull — so it works with
  Sodium present or absent and needs no mixin.

## Sodium, specifically

**Chunk state is per-`WorldRenderer`-instance.** Sodium's `LevelRendererMixin`
holds a private `SodiumWorldRenderer` constructed per instance, so a second
`WorldRenderer` gets its own `RenderSectionManager` and cannot corrupt the source
world's terrain. `setWorld` is hooked per instance and reads no global.

**The one leak is entity culling.** `SodiumWorldRenderer.instanceNullable()` reads
`MinecraftClient.getInstance().worldRenderer` and **always resolves to the main
renderer, whichever renderer is executing.** `EntityRendererMixin.preShouldRender`
wraps the frustum test with it, so a destination entity is asked about against the
source world's section manager. Symptom: destination entities wrongly culled or
wrongly kept — mobs and projectiles flickering or missing through the portal. It
lands directly on the acceptance test, because the arrow is an entity.

---

# Part 5 — The live feed

## What is built

The chunk feed sends resident chunks near the arrival, nearest-first, on a budget.
`DestinationFeed.pump` records every chunk key in `SENT` and never re-sends it, so
**blocks are a one-shot snapshot** — correct as of the tick each chunk was
serialised, and frozen after.

**Entities ARE fed.** `destination-entities/v1` carries them
(`CompanionPayloads.DestinationEntities`), `DestinationEntityFeed` enumerates them
server-side, and `DestinationEntities` applies them client-side against the
destination world through `EntityType.create` / `addEntity` / `removeEntity`.
Whether that stream satisfies the arrow acceptance test is a separate question
from whether it exists.

**Not fed: block updates after the first send, time, and weather.**

**Two things a chunk needs before the renderer will build its section**, both
satisfied by the feed: the chunk AND its 8 neighbours carrying block-data and
light-data status. The feed sends a filled core (`CORE_RADIUS 2`, a 5x5 whose 3x3
interior qualifies) rather than a visibility cone, which has no interior.

**The feed sends only RESIDENT chunks and may not load or generate one.**
Residency around an arrival decays after a boot and the feed with it, so any
measurement round needs its own server restart.

## Vanilla packets are world-agnostic; vanilla HANDLERS are not

Every relevant `ClientPlayNetworkHandler` method — `onEntitySpawn`,
`onEntityPosition`, `onEntityTrackerUpdate`, `onBlockUpdate`, `onWorldTimeUpdate`
— routes into the handler's single `world` field. **A raw vanilla packet sent to a
player not in that world is not ignored; it is applied to the wrong world.** That
is worse than dropped: an entity spawns beside the viewer, a block changes under
their feet.

The packet classes carry no world, so the precedent holds: **wrap the vanilla
packet in a companion payload and apply it client-side to the chosen world.**
`DestinationWorlds.load` already does exactly this. It generalises because the
application entry points on `ClientWorld` are public. One gap:
`createEntity(EntitySpawnS2CPacket)` is private, so the spawn path is reproduced
against public API — the same move already made for light.

## The three streams

| Stream | Mechanism | Cost driver | Verdict |
| --- | --- | --- | --- |
| Blocks | Expire `SENT` on a cadence; the existing pump re-sends the chunk | chunks in the wedge x resend rate | **Reuse.** No new payload |
| Entities | New payload; server enumerates near the arrival, client applies to the destination world | entity count near the arrival | **New.** `destination-entities/v1` |
| Clock and weather | Two longs and a weather flag on the existing frame resend | constant | **Extend**, as `portal-frame/v2` |

`CompanionPayloads`' own rule: *never widen a record in place — add `/v2` beside
it.*

A snapshot is deliberately chosen over a delta stream: idempotent, no server-side
tracker, and a dropped one self-heals on the next pass. Per-block granularity is
possible later but needs a server-side change tracker per destination, which is an
event subscription and is the thing ruled out. **Whole-chunk resend first.**

## Bounds

| | Bound | Failure shape |
| --- | --- | --- |
| Slab (fallback) | depth 12, radius 6, refresh 4 ticks | cubic in volume |
| Chunk feed | `MAX_RADIUS` 16 chunks, budget 4 per pass, wedge-filtered | linear in chunks, rate-limited |
| Entity feed | **Proposed:** a hard cap of N nearest inside the wedge box, the rest dropped | linear in entity count, **uncapped without N** |

**A mob farm is the failure case.** Entity count near an arrival is unbounded and
player-controllable; a spawner room behind a portal makes the box arbitrarily
expensive. The cap is not optional, and dropping the furthest is correct — the
near ones are what is seen through a 2x3 opening.

**N companions, one destination.** The wedge is per portal and per viewer and
`SENT` is keyed per player, so cost is linear in viewers with no sharing.
Serialisation is the shared part and is not pooled.

## Hazards

1. **Never sync-load or force-generate a chunk from a tick path.** The projection
   pass runs from a tick entry class, so entity enumeration is on a tick path.
   `getWorldChunk(cx, cz, false)` **waits**. Use `PortalHelper.residentChunk` /
   `isColumnResident` only. `TickPathChunkLoadTest` walks the compiled call graph
   from every tick entry and fails the build on a violation — but its predicate
   flags only a `getChunk` call and `getWorldChunk(IIZ)`, so it guards those two
   shapes rather than proving a path safe. **A green suite is not the reason
   `getOtherEntities` is safe; the call chain above is.**

---

# Part 6 — The stack must survive, and must not be detected

We run Complementary Reimagined + EuphoriaPatches, Distant Horizons, Sodium, c2me
and roughly 150 server mods **because that combination performs well together and
looks good.** The portal has to work inside it.

## The standing rulings, and they are not negotiable

Recorded because each gets rediscovered about once per agent:

- **Never pin Iris to a version.** That is the retreat ImmersivePortals took, and
  clearing that bar is the job.
- **Never detect the shader pack and fall back.** Built once, reverted.
- **Never degrade under shaders.** Degrading is not an outcome.
- **No quality slider.** The same retreat wearing a different hat.
- **No Iris or Sodium API.** Standalone, or it is not a solution.

**Fakery is allowed. Retreat is not.** A fog backdrop standing in for a real sky
is fakery, and fine. Switching the feature off when a pack is loaded is retreat,
and forbidden.

## The dimension-change reload, and a lead worth chasing

**Symptom:** crossing between dimensions with shaders on triggers a visible
reload. With shaders off the crossing is near-instant and only Distant Horizons
reloads.

**Mechanism, from bytecode and unmeasured:** `PipelineManager.preparePipeline`
takes a dimension id from `Iris.getCurrentDimension()`, which reads
`MinecraftClient.world`. It caches per dimension in a `HashMap` — swapping a
pointer on a hit, **creating only on a miss**, and never destroying.

**The lead:** if the hitch is the create-on-miss, a dimension already in that map
costs a pointer swap. Pre-warming the destination's pipeline — at ignition, or on
first sight through an opening, well before the player crosses — would move the
cost off the crossing frame.

Labelled honestly: **a hypothesis from bytecode with no measurement behind it.**
It predicts something testable — that the second crossing into a dimension is
cheap and the first is not — and that prediction should be measured before
anything is built.

## Distant Horizons

DH builds its per-level state **exclusively** from `ServerWorldEvents.LOAD`/`UNLOAD`.
Any path adding or removing a `ServerWorld` must fire them: skipping `LOAD` NPEs
DH on the first portal teleport into a runtime dimension and has locked a player
out of production once.

**LODs through the aperture are deferred, not impossible**, and the route needs no
mixin into DH. DH renders from `WorldRenderEvents` handlers that write four public
mutable fields on `ClientApi.RENDER_STATE` and then call the public
`renderLods()`. `RenderParams.update` derives the level, its `RenderBufferHandler`
and the lightmap from `clientLevelWrapper` alone — **choosing the level is a field
write, not a hook.** The camera is not in that state object; it comes live from
`mc.gameRenderer.getCamera()`, so a pass that moves the real camera gets DH's
culling and LOD centring for free.

A keyed wrapper is needed before DH will load the level (`RequestLevelInitMessage`);
its handler checks only that `sendLevelKeys` is on and the level is loaded, **not
that the player is in it**. Occupancy was never the question — measured with DH's
own `/dh debug`: five tracked levels, one occupied, including a runtime custom
dimension.

**The gap:** `DhServerWorld.getOrLoadLevel` registers no data-request handlers for
players already online, so a dimension activating after a join serves that player
no LODs until they reconnect. Fixable from outside by calling
`level.registerNetworkHandlers(state)` per online player on `DhApiLevelLoadEvent`.

**Why it waits.** Everything load-bearing is `core.*`/`common.*`, not DH's
versioned `api`, so every pin bump is a re-verification. The destination must stay
loaded or there is no key and no data, which idle unload actively fights. And DH's
own integration with the one other mod that renders dimensions through portals
answers this situation by **cancelling the LOD render outright** — possibly a
considered correctness choice we would otherwise re-learn in production.

---

# Part 7 — What to take from ImmersivePortals, and what not to

**ImmersivePortals is the best implementation going, and studying it is
worthwhile.** Its core trick is the right instinct: rather than compositing a
picture, it convinces the game engine that a whole world is genuinely right there,
and lets the engine's own machinery — culling, lighting, entity rendering — do the
work.

**What to study:**

- How it makes the client hold two worlds live at once, and what it does to chunk
  loading and entity tracking to support that.
- Its entity handover across the threshold — the case named in Part 3 and not yet
  solved here.
- Its clipping against a portal plane, and how it handles the wall's own thickness.
- Its coordinate transform for scaled and rotated portals.

**The sources, and the licence position.**

- Upstream: `iPortalTeam/ImmersivePortalsMod`, **Apache-2.0**.
- A community fork on a 1.21 branch: `DigitalWolf1313/ImmersivePortalsMod-Updated`,
  Fabric, ~3,100 commits, stated goals of compatibility fixes and bug fixes. Its
  README notes it contains some AI-generated code — a provenance caveat, not a
  licence one, and a reason to read what you take rather than lift it.

**Read them for insight; implement everything here.** Apache-2.0 is compatible
with this project's MIT, so lifting code would be permitted — but the ruling is
that we do not. Nobody owns *how* a problem is solved, and an approach understood
and then written from scratch against our own architecture is worth more than a
port: it carries no attribution surface, no provenance question about
AI-generated sections, and no boundary to maintain between borrowed and native
code. Where a source's technique informs something here, cite it in the commit
message as the insight it was, not as a licence obligation.

**Do not expect a shader fix from them.** The fork's own README says the portal
rendering is *"roughly compatible with some versions of Sodium and Iris"*. That is
the same qualified position upstream has always held, and it is precisely the bar
this project already cleared. **What we solved is still their open problem**, so
the shader work is not a place to look for borrowed answers — the borrowing runs
the other way.

**What is worth taking is what we have NOT solved:** the entity handover across
the threshold (Part 3's crossing seam), and how they hold two worlds live on the
client at once. Read those against our architecture rather than porting them —
our destination is drawn inside the source frame's own pass and theirs is not, so
their rendering-side answers may not transfer even where their state-management
ones do.

**What not to take:**

- **Its Iris version pin.** That is the wall it hit and the one we are clearing.
- **Any dependence on its worldgen arrangements.** Our dimensions are created at
  boot from config by `custom-dimensions`; that is settled and is not changing to
  accommodate a rendering technique.
- **A second full world render per frame**, if that is how it gets there. See the
  one-pipeline constraint.

The honest summary: **they solved presence and lost the renderer; we kept the
renderer and are rebuilding presence.** Their answers to presence are worth
reading closely. Their answer to shaders is the problem statement, not the
solution.

---

# Part 8 — Where we actually are

Stated plainly so nobody mistakes intent for status.

**Working, measured:**

- The destination renders in real time with a pack loaded, from four camera
  angles, no whole-screen corruption (`387d986e`).
- Destination terrain is visible under a pack (`6d6f755c`).
- The backdrop is no longer blown white (`4c8613ad`), and that fix costs nothing
  with no pack (`836c4fb8`).
- The opening is shaded per pixel from the destination's own depth, on by default
  (`fe233d71`, `b764d02f`). Opening contrast 31.25 against a 23.1 unstamped bar
  and a 69.4 no-pack truth.
- Shaders-OFF does not regress: 200 changed pixels against a same-condition floor
  of 25,207.
- The camera transform is verified at scale 2.0 against a known landmark, and at
  4.0 on the transform only.

**Open:**

- **Geometry** — Part 1. The half block, and the wall that is never whole.
- **Light** — Part 2. The dark entity, and the client's missing environment config.
- **Passage** — Part 3. The entity stream, and the crossing seam.
- **Crossing** — Part 6. The pipeline reload and the DH reload.
- **Framerate** — 18-23 fps with everything on, unattributed. `.handoff/PERF.md`.

**Accepted costs, named rather than discovered:** no mipmapped entity cutout layer,
so leaves and grass lose mipmapping through a portal; no sun, moon or stars, with a
fog backdrop shipping instead of a real sky; no particles; terrain bounded by the
capture volume; far-side block updates lag the rebuild. Each is a candidate for
removal; none is a defect against this document today.

---

# Instruments and constraints

- **The client emit line:** `surface=`, `stamp=`, `gated=`, `renderUs=`, `slice=`.
  Extend it when a change adds a term worth watching. `ProjectionRenderer.costSummary`
  exists so `renderUs` is comparable across builds — frame rate cannot measure a
  single pass.
- **`/state.realtime.apertureStamps`** counts calls and corners per stage. It
  separates a stamp that never ran from a stamp that ran and changed nothing — the
  failure that looks like a pass. Note its `DESTINATION` counter freezes while the
  far stamps are on; `DESTINATION_FAR`/`NEAR_DEPTH`/`FAR_DEPTH` are the live ones.
- **The dev bridge** on `127.0.0.1:8766` — `/state`, `/screenshot` (path required),
  `/input`, which takes exactly one top-level key.
- **Log markers:** `companion-accept:handshake`, `companion-refuse:handshake`
  (names both releases), `companion-send:projection`, `companion-client:emit`.
- **Assert counts over a fixed window, never an absence.**
- **A client mod change needs a client relaunch**, not `./dev up`. Confirm the
  install by hash before relaunching.
- **The client dies of its own accord** — a HotSpot SIGBUS with no fix. A client
  death invalidates assertions downstream of it and is not a finding about the code.
- **Keep both suites green.** Any test touching a `RenderLayer` dies in `<clinit>`
  (`RenderLayer -> ItemRenderer -> Items -> Blocks -> SoundEvents`).

---

# How to use this document

**Before building:** check the proposal against the illusion. Would a real doorway
behave like this? If not, it is a workaround and needs saying so.

**Before accepting a fix:** check it does not buy one part of the illusion by
spending another. A change that lines the geometry up and blows the lighting out
has not moved forward.

**Before quoting a number:** every measurement here belongs to a jar, a camera, a
time of day and a feed size. `.handoff/SHADERS.md` carries the conditions. A
number without its condition is not evidence.

**Standard of proof:** a mechanism read from source or bytecode is not a
measurement — label it static and require a runtime rung before building on it. A
result without a positive control is a description, and descriptions can be
selectively true. Measure your own noise floor, in your own session, back to back,
before trusting any delta.
