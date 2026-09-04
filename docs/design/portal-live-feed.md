# P3b — the live destination feed

What keeps the destination `ClientWorld` current after P3a stands it up. Sits
between P3 and P4 in [`portal-realtime-view.md`](portal-realtime-view.md), and
is what makes P7 — *"fire an arrow through the portal and see where it goes"* —
reachable. **P7 does not arrive free with the camera.** No phase in that plan
adds entities, block updates or a clock.

The arrow already travels: `immersive/EntityPassthrough.java` (639 lines)
driven by `mixin/EntityTickPortalMixin.java:24` (`tickPortalTeleportation`,
HEAD, cancellable). It cannot be seen.

## What is

`DestinationFeed.pump` records every chunk key in `SENT`
(`companion/DestinationFeed.java:59,242`) and never re-sends it. Nothing feeds
entities, block updates, time or weather. **The destination world is a one-shot
snapshot** — correct as of the tick each chunk was serialised, and frozen after.

The feed reaches the client only when a companion declares
`rendersLocally && !keepSlab` (`companion/PortalViewPreference.java:41`,
`immersive/PlayerProjectionState.java:577`), which is not the default
(`client/config/RealtimeSettings.java:29,44`).

## The crux: vanilla packets are world-agnostic, vanilla HANDLERS are not

Read from 1.21.1 bytecode, `net.minecraft.client.network.ClientPlayNetworkHandler`:

| Handler | What it does | Bound to |
| --- | --- | --- |
| `onEntitySpawn` | `createEntity(packet)`, then `world.addEntity(entity)` | `this.world` |
| `onEntityPosition` | `world.getEntityById(id)`, then `updateTrackedPositionAndAngles` | `this.world` |
| `onEntityTrackerUpdate` | `world.getEntityById(id).getDataTracker()` | `this.world` |
| `onBlockUpdate` | `world.handleBlockUpdate(pos, state, flags)` | `this.world` |
| `onWorldTimeUpdate` | `client.world.setTime` / `setTimeOfDay` | `client.world` |

Every one routes into the handler's single `world` field. **A raw vanilla
packet sent to a player not in that world is not ignored — it is applied to the
wrong world.** That is worse than dropped: an entity spawns beside the viewer,
a block changes under their feet.

The packet CLASSES carry no world. So the precedent holds: wrap the vanilla
packet in a companion payload and apply it client-side to the chosen world.
`DestinationWorlds.load` already does exactly this, calling
`loadChunkFromPacket` on the destination world's own chunk manager
(`client/realtime/DestinationWorlds.java:152`).

It generalises because the application entry points are public
(`net.minecraft.client.world.ClientWorld`): `addEntity(Entity)`,
`removeEntity(int, Entity.RemovalReason)`, `getEntityById(int)`,
`handleBlockUpdate(BlockPos, BlockState, int)`, `setTime(long)`,
`setTimeOfDay(long)`, `tickEntities()`, `tickEntity(Entity)`.

**One gap.** `ClientPlayNetworkHandler.createEntity(EntitySpawnS2CPacket)` is
`private`, so the spawn path cannot be reused. Every piece it needs is public —
`EntityType.create(World)`, `Entity.setId(int)`, `Entity.setUuid(UUID)`,
`Entity.onSpawnPacket(EntitySpawnS2CPacket)`, `ClientWorld.addEntity` — so it
is reproduced against public API only. That is the same move already made for
light, and for the same reason: `DestinationWorlds.updateLighting`
(`client/realtime/DestinationWorlds.java:179-195`) reproduces vanilla's
world-bound private loop rather than invoking it.

## Poll on a cadence, do not simulate events

The plan's § Approach forbids reproducing block updates, entity spawns, weather
and time as individual synchronised events. That instruction is already the
house idiom and nothing here breaks it:

- **The slab is a poll, not an event stream.** `ProjectionStream.build` samples
  the whole volume, `sameContent` diffs it, and only a difference is sent
  (`immersive/PlayerProjectionState.java:610-613`).
- **The chunk feed is a poll** — `DestinationFeed.feed` runs every projection
  pass and sends what `SENT` says is missing.

P3b is the same shape one step further: re-sample the destination on a cadence
and send what changed. No event hooks, no per-spawn callbacks, no weather
listener.

**The cheap answer, taken first.** Blocks need no new subsystem: expire entries
from `SENT` so the existing pump re-sends a chunk after N ticks. Entities cannot
ride that path — a chunk packet carries block data only
(`ChunkDataS2CPacket`), and re-serialising a whole chunk per entity movement is
the wrong cost curve. They need their own payload.

## The three streams

| Stream | Mechanism | Cost driver | Verdict |
| --- | --- | --- | --- |
| Blocks | Expire `SENT` on a cadence; existing `pump` re-sends the chunk | Chunks in the wedge x resend rate | **Reuse.** No new payload |
| Entities | New payload; server enumerates near the arrival, client applies to the destination world | Entity count near the arrival | **New.** `destination-entities/v1` |
| Clock and weather | Two longs and a weather flag on the existing `portal-frame` resend | Constant | **Extend**, as `portal-frame/v2` |

`CompanionPayloads` rule (`companion/CompanionPayloads.java:18`): *"Never widen
a record in place — add /v2 beside it."* The clock fields make a
`portal-frame/v2` beside `portal-frame/v1`
(`companion/CompanionPayloads.java:278`), not a wider v1.

### Blocks

`DestinationFeed.SENT` becomes key to last-sent tick. A chunk older than the
resend interval is eligible again and the existing nearest-first budget picks it
up. Bounded by the same budget of 4 per pass
(`companion/DestinationFeed.java:53`), so the resend competes with first-fill
rather than adding to it.

Per-block granularity is possible later (a `BlockUpdateS2CPacket` wrapper), but
it needs a server-side change tracker per destination, which is an event
subscription and is what the maintainer ruled out. **Whole-chunk resend first.**

### Entities

Server, on the projection pass: enumerate entities in a box around the arrival
and send id, type, uuid, position, angles and velocity. The idiom exists —
`EntityPassthrough` already scans with
`world.getOtherEntities(null, box, filter)` over the zone's bounds expanded by a
margin (`immersive/EntityPassthrough.java:265,273-274`); P3b does the same
around the ARRIVAL rather than the source zone.

Client: for an id it does not hold, construct via `EntityType.create(World)`
against the destination world, `setId`, `setUuid`, `addEntity`. For one it
holds, `updateTrackedPositionAndAngles`. For an id absent from the snapshot,
`removeEntity`.

A snapshot is deliberately chosen over a delta stream: it is idempotent, it
needs no server-side tracker, and a dropped one self-heals on the next pass.

## Bounds

| | Bound | Failure shape |
| --- | --- | --- |
| Slab (fallback) | depth 12, radius 6, refresh 4 ticks (`config/ImmersiveSettings.java:47,64,68`) — *"the slab grows with the depth times the square of the radius"* (`:46`) | Cubic in volume |
| Chunk feed | `MAX_RADIUS = 16` chunks, budget 4 per pass, wedge-filtered (`companion/DestinationFeed.java:53,56,131`) | Linear in chunks, rate-limited |
| Entity feed | **Proposed:** a hard cap of N nearest entities inside the wedge box, nearest first, the rest dropped | Linear in entity count, uncapped without N |

**A mob farm is the failure case.** Entity count near an arrival is unbounded
and player-controllable; a spawner room or an iron farm behind a portal makes
the box arbitrarily expensive. The cap is not optional, and dropping the
furthest is correct — the near ones are what is seen through a 2x3 opening.

**N companions, one destination.** The wedge is per portal and per viewer, and
`SENT` is keyed per player (`companion/DestinationFeed.java:59`), so cost is
linear in viewers with no sharing. Serialisation is the shared part and is not
currently pooled. **Unestablished:** whether the per-tick serialisation cost at
realistic companion counts is material. It needs a measurement, not an estimate.

## Reuse versus new

| Reuse unchanged | Extend | New |
| --- | --- | --- |
| `CompanionNetwork.COMPANIONS` / `isCompanion(UUID)` gate | `DestinationFeed.SENT` → key to tick | `destination-entities/v1` payload |
| `PortalViewPreference.streamsSlab()` fork (`companion/PortalViewPreference.java:41`) | `portal-frame/v1` → `/v2` with clock | Client-side entity apply (reproduced `createEntity`) |
| Nearest-first budget pump (`companion/DestinationFeed.java:112`) | `DestinationWorlds` gains an entity map | Server-side arrival-box enumeration |
| `PortalHelper.residentChunk` (`portal/PortalHelper.java:1120`) | | |

## Acceptance

Counts over a fixed window, never an absence ([T63](../../TROUBLESHOOTING.md#t63)).

1. **Blocks stay current.** Break a block in the fed wedge. Within 2 resend
   intervals the client's `companion-client:destination-chunk` count for that
   destination increases by at least 1 AND a re-read of that position in the
   destination `ClientWorld` returns air. Assert both.
2. **The arrow is held.** Fire an arrow through the nexus portal. Within W = 40
   ticks of the crossing line (`immersive: entity entity.minecraft.arrow
   crossed ...`), at least 3 entity-snapshot payloads naming that entity id
   arrive at the client, AND `DestinationWorlds.get(destination)
   .getEntityById(id)` is non-null. A count of arrivals plus a positive lookup;
   never "no error".
3. **The fallback is untouched.** In the same run, a Carpet bot — a vanilla
   client by construction — still receives its slab. Assert its
   `companion-send:projection` count over the window is unchanged from
   baseline.

The dev bridge already reports `destinationWorlds` and `destinationChunks`
(`client/dev/DevServer.java:194,196`); an entity count belongs beside them.

## Hazards

1. **Never sync-load or force-generate a chunk from a tick path.** The
   projection pass runs from `ServerWorldMixin`, a tick entry class
   (`portal/TickPathChunkLoadTest.java:65-68`), so the entity enumeration is on
   a tick path. `getWorldChunk(cx, cz, false)` **waits** — `create` only
   decides which future it joins (`portal/PortalHelper.java:1107-1116`). Use
   `PortalHelper.residentChunk` / `isColumnResident` only.
   `TickPathChunkLoadTest` walks the compiled call graph from every tick entry
   and fails the build on a violation, so this is enforced, not advisory.
2. **`getOtherEntities` on a non-resident region.** Establish before building
   whether it can touch an unloaded chunk. If it can, the box must be clipped to
   resident columns first. **Unestablished here.**
3. **`ConcurrentModificationException`.** Never mutate the worlds map, or any
   collection vanilla iterates per tick, from a world-tick path. Defer to
   `ServerTickEvents.END_SERVER_TICK` (`mods/AGENTS.md` § Threading).
4. **Applying to the wrong world.** Every apply goes through
   `DestinationWorlds`, keyed by destination `Identifier`. Never hand a
   destination packet to `ClientPlayNetworkHandler`.
5. **The `SENT` expiry is a resend, not a diff.** Expiring too fast turns the
   feed into a chunk-rate stream. Start slow and measure.

## Entity identity across two worlds

Two `ClientWorld`s are live at once and the server has one id space. Read from
1.21.1 bytecode, `net.minecraft.client.world.ClientWorld`:

- **The index is per-world.** `ClientWorld` holds its own
  `private final ClientEntityManager<Entity> entityManager` and its own
  `final EntityList entityList`; `getEntityById(int)` resolves through
  `getEntityLookup()` into that world's manager, and `removeEntity(int,
  RemovalReason)` does the same. **A destination id colliding with a
  source-world id is harmless** — each lookup hits its own map. Nothing
  consults more than one world.
- **`addEntity` replaces silently.** `ClientWorld.addEntity(Entity)` first
  calls `removeEntity(entity.getId(), RemovalReason.DISCARDED)` **on itself**,
  then `entityManager.addEntity`. Within one world, re-adding an id discards
  the incumbent with no error. That is convenient for a snapshot re-apply and
  dangerous if an id is ever reused for a different entity in the same
  destination.
- **`addEntity` does NOT reassign the entity's world.** It only touches the
  manager. An entity constructed against `client.world` and added to a
  destination has a `getWorld()` that disagrees with the world holding it, and
  that disagreement surfaces differently in ticking, rendering and removal.
  **Construct against the destination world:** `EntityType.create(World)` takes
  the world and is the only place the reference is set.

**Removal.** Three exits, all of which must be specified:

| Trigger | Action |
| --- | --- |
| Entity absent from a snapshot | `destinationWorld.removeEntity(id, DISCARDED)` |
| Portal frame cleared | `PortalFrames.remove` already fires (`client/CustomDimensionsClient.java:121`); the entity map for that destination goes with it |
| Destination torn down | `DestinationWorlds.drop` (`client/realtime/DestinationWorlds.java:198`) drops the world and its manager wholesale |

An entity left in a destination world that is later dropped is collected with
the world, so the failure mode is a slow leak within a live destination, never
a crash. It still needs the snapshot-absent rule or a mob that wanders out of
the box stands there forever.

## The crossing seam

**The identity does not survive the crossing, and that is vanilla, not ours.**
`EntityPassthrough.moveEntity` uses `Entity.teleportTo(TeleportTarget)` and
documents why (`immersive/EntityPassthrough.java:371-376`): a cross-dimension
move **recreates** a non-player entity in the destination world and leaves the
original removed, returning the live arrival
(`immersive/EntityPassthrough.java:407-409`).

So at the instant of crossing:

1. Source world: the original entity is removed. Vanilla sends its own
   `EntitiesDestroyS2CPacket`, and the client's source world drops it.
2. Destination: a **different** entity with a **different network id** exists,
   and the feed picks it up on its next pass.

There is therefore no shared identity, no duplicate id, and no two-index
confusion. What there is instead is a **gap**: the source copy vanishes at the
plane and the destination copy appears one feed pass later. That gap is exactly
what P7 forbids — *"without vanishing at the plane, without jumping"*.

**Unestablished, and the builder must decide it deliberately.** Three options,
none measured:

| Option | Cost |
| --- | --- |
| Shorten the entity cadence so the gap is under a frame or two | Bandwidth, and it never reaches zero |
| Have the server name the handover explicitly — old id, new id, tick — so the client can carry the fed copy across | A new payload and a seam the client has to interpolate |
| Accept the gap | A visible stutter at exactly the moment the acceptance test watches |

The second is the only one that can actually satisfy P7's wording. It is also
the one closest to "simulating an event", so it is a maintainer decision, not a
builder's.

## Unestablished

- Whether the destination `ClientWorld` must be ticked for entities to
  interpolate rather than snap. `ClientWorld.tick(BooleanSupplier)`,
  `tickEntities()` and `tickEntity(Entity)` are public and nothing in the
  client mod calls any of them. Ticking a second world also runs its block
  entities, particles and sounds, which is a cost and a correctness question of
  its own. **This is the largest open design decision in P3b.**
- Whether `getOtherEntities` can touch a non-resident chunk (hazard 2).
- How the crossing seam is closed — the three options above are unmeasured and
  the choice is the maintainer's.
- The per-tick serialisation cost at realistic companion counts.
