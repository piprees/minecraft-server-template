# Phase 3 — Entity Pass-Through

> **Depends on:** Phase 0 (config parsing + pre-loaded target world)
> **Unlocks:** Phase 4 (Polish)
> **Independent of:** Phases 1 and 2 (can be built in parallel)
> **Status:** Complete. Extended by **Phase 3b — living entities** (below),
> which inverts the original decision to exclude them.
> original plan were wrong and are corrected in place; the §3c coordinate
> snippet did NOT match the real player teleport and has been replaced.

## Goal

Items thrown into an immersive portal appear on the other side with preserved
velocity. Projectiles (arrows, tridents, potions, snowballs) fly through.
XP orbs drift through. Mobs, villagers and livestock walk through, as they do
through a vanilla nether portal. The portal acts like an open doorway for every
non-player entity that vanilla itself would let through.

## Connection to Overall Plan

Phase 3 is the interaction complement to Phase 1's visual preview. Together
they create the illusion that the portal is a physical opening: you can see
through it (Phase 1), hear through it (Phase 2), and throw things through it
(Phase 3).

This phase depends only on Phase 0 (the `ImmersiveSettings` config and the
pre-loaded target world). It does NOT depend on Phase 1 — entity pass-through
works regardless of whether the visual preview is active.

Gated by `ImmersiveSettings.entityPassthrough()` (default true).

## As shipped

All the logic lives in **`com.customdimensions.immersive.EntityPassthrough`**
(new file). `ServerWorldMixin` gains a one-line call and
`EntityTickPortalMixin` a five-line branch — the mixins stay readable and the
decision logic stays unit-testable.

| Decision | What shipped | Why |
| --- | --- | --- |
| Teleport API | `Entity.teleportTo(TeleportTarget)` | Returns the (possibly recreated) arrival entity and carries velocity as a record field, so position and motion move atomically. Dissolves Gotcha #10 — no UUID re-lookup. |
| `PostDimensionTransition` | `ADD_PORTAL_CHUNK_TICKET` | The destination usually has no player in it (that is the point of throwing something through first); with `NO_OP` an arrival can land in a chunk that unloads from under it. Vanilla's own portal ticket, added asynchronously. `SEND_TRAVEL_THROUGH_PORTAL_PACKET` is player-only. |
| Arrival height | `getChunkManager().getWorldChunk(cx, cz, false)`, null = skip | `PortalHelper.findSurfaceY` FORCE-GENERATES; sync-generating a chunk from the world tick is a documented server-wedge trigger (mods/AGENTS.md "Known issues"). Same maths, loaded chunks only — so when it resolves it agrees with the player exactly. |
| Coordinate transform | `ProjectionVolume.scaledMapping` / `anchorMapping` | The pure functions Phase 1 already validated against the real player teleport. Reused, not re-derived — there is no third copy of the maths. |
| Trigger | Entry edge, per world, keyed by entity UUID | Mirrors the player loop's `PLAYER_IN_ZONE` edge trigger. Level triggering makes an entity that RETURNS into its own source zone bounce straight back the moment its cooldown expires. |
| Crossing test | Swept path (previous position → current), block-set exact | A bow arrow covers ~3 blocks a tick and tunnels straight through a one-block-thick portal otherwise. The broad-phase box is padded 3 blocks; nothing teleports on that padding alone. |
| Gateway zones | Excluded | Gateway source zones contain a REAL block, so vanilla's own gateway travel already moves entities standing in them. |

**Log line to grep** (DEBUG level):

```
immersive: entity
```

Three forms: `immersive: entity <type> crossed <src> -> <dst> at (x, y, z) velocity (x, y, z)`,
`immersive: entity <type> returned <src> -> <dst> at (x, y, z)`, and
`immersive: entity <type> leash detached before crossing`.

---

## Phase 3b — Living entities

Phase 3 shipped with living entities excluded on the grounds that pathfinding
targets, AI memories, leashes and spawn tracking are world-scoped and break on
a cross-dimension recreate. The project owner reversed that deliberately,
accepting the extra compute:

> *"these should function like any other in-game portal so I'm willing to accept
> the extra compute for AI pathfinding etc, it'd be a shame if players couldn't
> bring villagers to their dimensions or build farms, etc."*

So mobs, villagers and animals now cross. **Vanilla nether portals are the
reference for "correct"** — every decision below either matches vanilla or
states why it deliberately doesn't.

### What the exclusion was actually protecting against, and what it costs

Most of the original worry turned out to be handled by the recreate itself:

| Worry | What really happens | Action |
| --- | --- | --- |
| Pathfinding targets | `EntityNavigation` is not serialised. The arrival is built by `EntityType.create(destination)` + `copyFrom(NBT)`, so it gets a fresh navigation bound to its new world. | None needed |
| AI memories | Brain memories are `GlobalPos`-qualified (world + position). A villager's old home and job site simply stop matching in the new dimension — exactly what happens when vanilla carries one into the Nether. | None needed |
| Attack target | `LivingEntity.target` is not written to NBT, so it clears on the recreate. | None needed |
| Spawn tracking | Per-world and live-counted; `ServerWorld.onDimensionChanged` re-indexes the arrival. Animals and villagers already refuse to despawn, so only hostile mobs face the destination's despawn rules — as vanilla. | None needed |
| Leashes | Real. See below. | Detach before crossing |
| Per-dimension difficulty | Real, and NOT a vanilla concern — it is this mod's own feature. See below. | Re-applied on arrival |

### Eligibility, as it now stands

`isPassthroughType(Class)` stays a class-level allow-list (world-free, so the
whole policy is unit-testable), with `LivingEntity` added to it. Three
exclusions survive:

- **`PlayerEntity`** — players have their own teleport path in
  `ServerWorldMixin` (origin tracking, portal sounds, single-use countdown,
  arrival portal creation). Letting them in here would double-teleport them.
  Written against `PlayerEntity`, not `ServerPlayerEntity`, so it holds for
  every subclass including Carpet fake players.
- **`ArmorStandEntity`** — living, but placed rather than wandering. A stand a
  player has stood inside the frame as decoration must not teleport itself
  away. It is the one living type that is decor, so it is the one living type
  excluded.
- **`VehicleEntity`** — see § Vehicles below.

`isEligible(Entity)` adds the live-state half, and gained two gates:

- **`hasPassengers()`, not just `hasVehicle()`.** `Entity.teleportTo` *carries
  passengers*: it detaches them, teleports each recursively and re-mounts on
  the far side. A ridden horse would therefore have dragged its **player**
  across on this path, bypassing origin tracking and racing the player's own
  teleport. Neither half of a mounted pair moves now; the player dismounts and
  walks through, which is what the player loop expects anyway (it already skips
  mounted players).
- **`canUsePortals(false)`** — vanilla's own per-entity veto, honoured
  verbatim. Decompiled 1.21.1: the base is `(allowVehicles || !hasVehicle()) &&
  isAlive()`; `LivingEntity` adds `&& !isSleeping()`; `EnderDragonEntity` and
  `WitherEntity` return `false` outright; `ThrownEntity` returns `true`
  unconditionally (so pearls, snowballs and potions are unaffected). This is
  the cleanest possible statement of "behaves like a vanilla portal".

  **One deliberate side effect:** `FishingBobberEntity` returns `false`, and it
  was eligible before Phase 3b purely because it is a `ProjectileEntity`. A
  bobber crossing while the angler holding it stays behind is exactly the
  dangling cross-world reference this file is careful about, and vanilla
  forbids it for the same reason. Treat this as a fix, not a regression — but
  it *is* a behaviour change to the pre-3b item path, so it is written down
  here rather than left to be discovered.

### Leashes — decision: detach and drop, before the teleport

`detachLeashBeforeCrossing` calls `Leashable.detachLeash(true, true)` — vanilla's
own break call, the one `tickLeash` makes when the holder dies or the mob is
dragged past the maximum leash length. It drops a lead item and sends the detach
packet so the holder's client stops drawing a rope to something that has gone.

**Why not refuse to move a leashed mob.** Verified against the decompiled
`Leashable.tickLeash`: it detaches **only** when one of the two is dead, and
when the holder is in a different world it skips its distance check entirely.
So a cross-world leash in vanilla is *permanent*. Refusing to move the mob
would therefore manufacture the exact dangling cross-world leash the refusal
was meant to prevent — the player walks through, the cow stays behind, and the
lead never breaks. It would also make "lead your cow through the portal"
impossible, which is most of the reason living entities are allowed through.

**Why not just let it cross leashed** (vanilla's literal behaviour). It
self-heals, but badly and only eventually. The teleport recreates the mob from
NBT, so the arrival carries an *unresolved* holder — a UUID, or a block
position for a fence knot. `resolveLeashData` then either drops a lead in the
destination once `age > 100` (a phantom leash on any mob younger than five
seconds), or, for a fence-tied mob, calls `LeashKnotEntity.getOrCreate` and
**spawns a stray leash knot in the destination world** at the mapped position,
tied to nothing. That litter is the deciding argument.

**The cost of the choice:** the lead lands on the *near* side, whereas vanilla
would (eventually) drop it on the far side. A player leading an animal through
has to step back for the lead. That is the whole divergence.

**Mobs leashed TO a crossing entity** need no code: the teleport removes the
original, and their next `tickLeash` sees a holder that is no longer alive and
drops their own leads.

### Per-dimension difficulty — applied on arrival

`MobAttributeMixin` applies this mod's per-dimension mob multipliers at
`MobEntity.initialize`, which runs at natural spawn and **never** for an entity
that walked in from somewhere else. Without this, a mob walked into a hard
dimension would keep its origin dimension's stats forever.

`applyArrivalDifficulty` calls `DifficultyManager.applyMobModifiers(mob)` on the
**arrival** entity — the same public entry point the mixin uses, no new API, no
reaching around it. It is idempotent by modifier id (remove-then-add), so it
replaces the origin's modifiers rather than stacking on them. Applied on both
the source-side crossing and the arrival-side return.

Two inherited behaviours worth knowing: scaling only ever applies to
MONSTER-spawn-group mobs (villagers and livestock are untouched — the mod's
existing policy), and a mob whose health scales is set back to full, so an
arrival in a scaled dimension is treated exactly like a spawn there.

**Known gap (not hacked around):** a destination with no scaling — multiplier
1.0, or no dimension config at all, e.g. the plain overworld — makes
`applyMobModifiers` return early *without* stripping the origin's modifiers, so
a mob walked OUT of a hard dimension keeps its boost. Closing that needs a
strip-only path inside `DifficultyManager`, which owns the private modifier id.
Duplicating that id from here would be a hack, so it is recorded as a gap
instead.

### Vehicles and passengers — a known gap, stated deliberately

`VehicleEntity` (boats, minecarts) stays excluded, and entities with passengers
are now excluded too. **This is a known gap, not an oversight.** A boat with a
passenger crossing a dimension boundary is a bigger piece of work than this
phase: the vehicle and every rider have to arrive together and re-mount
correctly, a player rider must not be moved by this path at all (they have
their own), and the arrival column has to be safe for a boat rather than merely
loaded. Until that is built:

- A boat or minecart does not cross, with or without a rider.
- A ridden horse, pig, strider or llama does not cross while anyone is aboard.
- An **unridden** horse, pig, strider or llama *does* cross — it is just a mob.
- A player leading a mount through must dismount first, which the player
  teleport loop already requires of them.

### Performance

The broad-phase is unchanged and the search box is **not** widened: it is still
the interior's bounding box plus `SCAN_MARGIN` (3 blocks, there so fast movers
become candidates), queried through `world.getOtherEntities`. Cost stays
proportional to entities actually near an immersive portal, never to the world's
entity count.

What Phase 3b adds:

- **Source side:** mobs near a portal now clear the class gate and reach the
  swept `crossedInterior` test. A mob moves well under half a block a tick, so
  that is one or two `BlockPos` lookups each. Negligible.
- **Arrival side:** `tryReturnFromArrivalPortal` runs for every non-player
  entity every tick, and mobs now get past the class check. The next two gates
  are a plain integer field read (`getPortalCooldown`) and
  `getBlockStateAtPos()`, which vanilla caches per tick — so the added cost is
  one comparison and one cached read per mob in a loaded world. The map lookup
  and the config read stay behind the portal-block test, which almost nothing
  passes.

### Single-use portals are still player-only

Unchanged and re-verified: `startSingleUseCountdown` is not called from either
entity path. A wandering mob cannot burn a one-shot portal a player is saving,
and neither can a thrown item. Only a player traversal arms the countdown.

## Implementation Checklist

### 3a. Entity detection in portal zones

- [x] In `ServerWorldMixin.onTick()`, after the existing player teleport loop
  and after `ImmersiveProjector.tick()`, add an entity scan for immersive zones.
  **Shipped as a one-line call** to `EntityPassthrough.tick(world)`, which
  fetches its own zones exactly the way `ImmersiveProjector.tick` does. The
  sketch below is the shape of what that method does:
  ```java
  // Entity pass-through for immersive portals
  for (PortalHelper.PortalZone zone : zones) {
      ImmersiveSettings imm = zone.definition.getImmersive();
      if (imm == null || !imm.entityPassthrough()) continue;

      // Build a tight bounding box around the zone interior
      // (not the frame — only entities INSIDE the portal)
      Box zoneBounds = boundsOf(zone.interior).expand(0.5);

      List<Entity> entities = world.getOtherEntities(null, zoneBounds,
          e -> isPassthroughEligible(e) && e.getPortalCooldown() == 0);

      for (Entity entity : entities) {
          teleportEntity(world, entity, zone);
      }
  }
  ```

- [x] The `boundsOf` helper computes an AABB from the zone's interior positions.
  **Shipped without the `.expand(0.5)`**: at 0.5 an item lying one block in
  front of the frame intersects the box and is teleported — dropping something
  next to the portal makes it vanish. The scan instead pads by 3 blocks purely
  to make fast movers *candidates*, and then runs an exact swept test
  (`crossedInterior`) that rejects everything which did not actually pass
  through an interior block. Both are pure and unit-tested.
  ```java
  private static Box boundsOf(Set<BlockPos> positions) {
      int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (BlockPos p : positions) {
          minX = Math.min(minX, p.getX());
          minY = Math.min(minY, p.getY());
          minZ = Math.min(minZ, p.getZ());
          maxX = Math.max(maxX, p.getX() + 1);
          maxY = Math.max(maxY, p.getY() + 1);
          maxZ = Math.max(maxZ, p.getZ() + 1);
      }
      return new Box(minX, minY, minZ, maxX, maxY, maxZ);
  }
  ```

**File:** `EntityPassthrough.java` (`tick`), called from `ServerWorldMixin.java` (1 line)

### 3b. Entity eligibility filter

- [x] Create the eligibility filter. **Shipped as three functions:**
  `isPassthroughType(Class<?>)` (pure type policy), `isPassthroughState(...)`
  (pure live-state policy, added by Phase 3b so the rider/passenger/vanilla-veto
  gates are pinned by test rather than only by reading the call site), and
  `isEligible(Entity)` which composes the two against a real entity. Keeping
  both halves world-free is what makes the whole policy testable without
  bootstrapping a registry — which these unit tests deliberately never do.

  Unit-tested positive: `ItemEntity`/`ArrowEntity`/`EnderPearlEntity`/
  `ExperienceOrbEntity`/`FallingBlockEntity` and (Phase 3b)
  `ZombieEntity`/`VillagerEntity`/`CowEntity`. Negative:
  `PlayerEntity`/`ServerPlayerEntity`/`ArmorStandEntity`/`BoatEntity`/
  `MinecartEntity`/`ItemFrameEntity`/`PaintingEntity`/bare `Entity`/null.

  Original sketch (superseded twice over — it excluded living entities and did
  not know `teleportTo` carries passengers):
  ```java
  private static boolean isPassthroughEligible(Entity entity) {
      if (entity instanceof ServerPlayerEntity) return false; // players have their own path
      if (entity.isRemoved()) return false;
      if (entity.hasVehicle()) return false;
      return entity instanceof ItemEntity
          || entity instanceof ProjectileEntity  // arrows, tridents, potions, snowballs, ender pearls
          || entity instanceof ExperienceOrbEntity
          || entity instanceof FallingBlockEntity;
  }
  ```

- [x] **Explicitly excluded** (updated by Phase 3b):
  - `PlayerEntity` — already handled by the existing player teleport loop.
    Widened from `ServerPlayerEntity` so it covers every player subclass.
  - ~~`LivingEntity` (mobs, villagers)~~ — **now allowed** (Phase 3b).
  - `VehicleEntity` (boats, minecarts) — known gap, see § Vehicles above
  - `ArmorStandEntity` — living, but placed decor rather than wandering
  - `ItemFrameEntity`, `PaintingEntity` — attached to blocks
  - Any entity with `hasVehicle()` — passengers follow their vehicle
  - Any entity with `hasPassengers()` (Phase 3b) — `teleportTo` would drag
    them across, players included
  - Anything vanilla vetoes via `canUsePortals(false)` (Phase 3b) — ender
    dragon, wither, sleeping mobs, fishing bobbers

- [x] Imports:
  - `net.minecraft.entity.ItemEntity`
  - `net.minecraft.entity.projectile.ProjectileEntity`
  - `net.minecraft.entity.ExperienceOrbEntity`
  - `net.minecraft.entity.FallingBlockEntity`
  - `net.minecraft.entity.LivingEntity`, `net.minecraft.entity.Leashable`,
    `net.minecraft.entity.mob.MobEntity`,
    `net.minecraft.entity.player.PlayerEntity`,
    `net.minecraft.entity.decoration.ArmorStandEntity` (Phase 3b)

**File:** Same `ServerWorldMixin.java`

### 3c. Entity teleportation with velocity preservation

> **The snippet originally here was wrong twice over** and has been replaced.
> It invented a transform (`portalCentre.getX() * scale - portalCentre.getX()`,
> raw `entity.getY()`) that does not match the real player teleport in
> `ServerWorldMixin`, and it used the `teleport(world, x, y, z, flags, yaw,
> pitch)` overload, which recreates a non-player entity in the destination and
> leaves the original reference pointing at a corpse — so every line after it
> was a no-op. What shipped:

- [x] `passThrough` in `EntityPassthrough`:
  ```java
  ProjectionVolume.TargetMapping mapping = mappingFor(zone, def);   // scaled or anchor
  int arrivalY = arrivalSurfaceY(targetWorld, mapping.arrivalX(), mapping.arrivalZ());
  if (arrivalY == NO_ARRIVAL) return;      // chunk not loaded — never force-generate

  double floorY = arrivalY + (zone.axis == Direction.Axis.Y ? 1 : 0);
  double tx = entity.getX() + mapping.dx();
  double ty = floorY + (entity.getY() - mapping.interiorMinY());
  double tz = entity.getZ() + mapping.dz();

  Vec3d velocity = entity.getVelocity();
  Entity arrived = entity.teleportTo(new TeleportTarget(
          targetWorld, new Vec3d(tx, ty, tz), velocity,
          entity.getYaw(), entity.getPitch(), TeleportTarget.ADD_PORTAL_CHUNK_TICKET));
  if (arrived == null) return;
  arrived.velocityModified = true;
  arrived.setPortalCooldown(def.getCooldown());
  ```

- [x] The transform is the player's, via Phase 1's already-validated pure
  functions. `scaledMapping` reproduces `ServerWorldMixin`'s
  integer-averaged-centre × scale (truncation and all); `anchorMapping`
  reproduces `teleportToAnchor`'s MIN-corner translation — **not** its centre.
  An entity in the interior's centre column lands in exactly the block a player
  lands in (pinned by `EntityPassthroughTest`); off-centre entities keep their
  offset through the doorway, which is the same translation from a different
  starting point rather than a second, divergent transform. The
  `arrivalY + 1` for horizontal portals matches the player's `landY`.

- [x] `arrived.velocityModified = true` tells the server to sync velocity to
  the client next tick. Without it the client predicts zero motion and the
  entity appears to stop dead at the far side. Note it is set on the **returned**
  entity, not the original.

- [x] The cooldown prevents immediate re-teleport. Note that an entity resting
  in a real arrival portal block also has its cooldown pinned by vanilla
  (`Entity.tryUsePortal` calls `resetPortalCooldown()` every tick while inside),
  which is what stops a crossed item from oscillating.

**File:** `EntityPassthrough.java` (`passThrough`)

### 3d. Arrival-side return teleport for entities

- [x] The existing `EntityTickPortalMixin` only handled `ServerPlayerEntity`.
  Extended to delegate the non-player case to
  `EntityPassthrough.tryReturnFromArrivalPortal(self, this.world)`, which
  **returns true only when it actually teleported** — and the mixin cancels on
  true and on true alone. Shape of the original sketch:
  ```java
  // At the top of onTickPortal, before the instanceof ServerPlayerEntity check:
  Entity self = (Entity) (Object) this;
  if (!(self instanceof ServerPlayerEntity)) {
      // Non-player entity in a portal block with a return target
      if (isPassthroughEligible(self) && self.getPortalCooldown() == 0) {
          // ... lookup return target, teleport with velocity preservation
      }
      return;
  }
  ```

- [x] This handles the case where an item or projectile lands in an ARRIVAL
  portal (which has real NETHER_PORTAL blocks) and needs to return to the
  source dimension.

- [x] **Caution honoured:** the non-player path never cancels unless it
  teleported. This callback fires for **every** non-player entity in the game
  every tick, so the gates are ordered cheapest-and-most-selective first — the
  class check rejects all mobs and players before anything touches the world,
  and the block state comes from `getBlockStateAtPos()`, which vanilla caches
  per tick. Unlike the player path it checks only the entity's own block, not
  also the blocks above and below: items, orbs, arrows and falling blocks are
  sub-block entities whose block position is where they visually are.

- [x] **Immersive gate on the arrival side.** There is no source zone in the
  arrival world to read `ImmersiveSettings` from, so the gate is
  `MultiverseConfig.getImmersiveFor(<world the portal block is in>)` — the same
  settings object every source zone targeting that world carries. Without it,
  arrival portals of NON-immersive dimensions would silently gain new
  behaviour, breaking the zero-change guarantee.

- [x] **Target lookup is a direct map read**, not `collectPortalArea`: every
  interior block of an arrival portal is registered individually, so the flood
  fill the player path runs would buy nothing and cost up to 128 block reads
  per entity per tick. A null target means it is not one of our portals (a
  player-built vanilla one, say) and it is left alone.

- [x] **Exit modes are honoured only where they mean something without a
  player.** `"worldSpawn"` resolves; a plain link or `"origin"` falls back to
  the recorded source world and Y (an item has no bed and no tracked origin);
  `"bed"` and `"dim!ns:slug!arrival"` are skipped rather than guessed at.
  Single-use countdowns are deliberately **not** armed from this path — a
  thrown item must not burn a one-shot portal the player is saving.

**File:** `EntityPassthrough.tryReturnFromArrivalPortal`, called from
`EntityTickPortalMixin.java` (5 lines)

### 3e. Ender pearl special case

- [ ] When an `EnderPearlEntity` crosses through a portal, vanilla's
  `EnderPearlEntity.onCollision()` teleports the THROWER to the pearl's
  position — which is now in the target dimension. This should work
  naturally because:
  1. Pearl entity crosses via Phase 3's teleport
  2. Pearl hits a block in the target dimension
  3. `onCollision()` fires, teleporting the owner to the pearl's position
  4. Owner is now in the target dimension

- [ ] **Test explicitly**: if the pearl's owner is in the source dimension
  and the pearl is in the target dimension, does `Entity.teleport()` on the
  owner work cross-dimensionally? In vanilla 1.21.1, yes — `Entity.teleport()`
  accepts a `ServerWorld` parameter for the destination.

- [ ] **Edge case**: if the pearl owner is too far from the portal to be
  loaded in the target world's player tracking, the teleport may fail silently.
  This is acceptable — it matches vanilla behaviour for long-distance pearls.

**No additional code needed** — this is a test-and-verify item, not an
implementation item.

## Verification Checklist

### Automated (Carpet bot + RCON)

> **The crossing log is at DEBUG**, per the phase brief. It will not appear
> under the server's default log level — enable debug logging (or flip the two
> `LOGGER.debug` calls in `EntityPassthrough` to `info`) before relying on log
> greps. The `data get entity`/`kill @e` assertions below need no log at all
> and are the stronger oracle.
>
> `summon` places the entity with previous-position == current-position, which
> the edge trigger treats as a fresh entry — so the RCON recipes below work as
> written.

- [x] Spawn items near an immersive portal:
  ```bash
  # Give bot items and throw them toward the portal
  docker exec -i mc rcon-cli 'summon minecraft:item <portal_x> <portal_y+1> <portal_z> {Item:{id:"minecraft:diamond",Count:1b},Motion:[0.0,0.0,-0.5]}'
  ```
- [x] Check item appeared in target dimension:
  ```bash
  docker exec -i mc rcon-cli 'execute in <ns>:<dim> run kill @e[type=item,distance=..10]'
  # Should report killing the diamond
  ```
- [x] Shoot arrow through:
  ```bash
  docker exec -i mc rcon-cli 'summon minecraft:arrow <portal_x> <portal_y+1> <portal_z> {Motion:[0.0,0.0,-1.0]}'
  # Check arrow in target dimension
  ```
- [x] XP orb through:
  ```bash
  docker exec -i mc rcon-cli 'summon minecraft:experience_orb <portal_x> <portal_y+1> <portal_z>'
  ```
- [ ] Non-immersive portal: entities do NOT pass through (existing behaviour)
- [x] `"entityPassthrough": false`: entities do NOT pass through
- [x] Entity with portal cooldown: does NOT re-teleport (no ping-pong)

### Phase 3b additions (RCON, headless) — NOT YET RUN ON A LIVE SERVER

> These invert the old load-bearing negative ("a pig must NOT cross"). The
> `data get entity`/`kill @e` assertions are the oracle; the DEBUG log is
> supporting evidence only.

- [ ] **Mob crosses.** `summon minecraft:pig <zone x> <y> <z>` then
  `execute in <ns>:<dim> positioned <ax> <ay> <az> run kill @e[type=pig,distance=..48]`
  — must report one kill. Baseline the source side first.
- [ ] **Villager crosses**, and is still a villager with its trades on the far
  side (`data get entity @e[type=villager,limit=1] Offers`).
- [ ] **Player does NOT double-teleport.** Walk a Carpet bot through and assert
  `data get entity Bot Pos` matches the player path's landing, and that the
  log shows exactly one traversal for it.
- [ ] **Ridden mount stays put.** `ride` a bot onto a pig inside the zone; the
  pig must NOT cross and the bot must NOT move (`data get entity Bot Dimension`).
- [ ] **Leashed mob crosses and the lead drops on the NEAR side.** Leash a
  sheep to the bot, walk it into the zone, then assert the sheep arrived in
  the target dimension AND a `minecraft:lead` item entity exists near the
  source portal (`execute positioned <zone> run data get entity @e[type=item,distance=..6]`).
- [ ] **Wither/ender dragon do NOT cross** (vanilla veto) — `summon` a wither
  inside the zone, assert it is still in the source world one cooldown later.
- [ ] **Single-use portal survives a mob.** On a `singleUse` dimension, send a
  pig through and assert the zone record's `singleUseTicksLeft` is still
  absent/-1 in `portal_links.json` (the countdown must not be armed).
- [ ] **Difficulty applied on arrival.** Send a zombie into a dimension with a
  `difficulty.mobMultiplier` > 1 and assert its
  `Attributes` carry the `customdimensions:dimension_difficulty` modifier
  (`data get entity @e[type=zombie,limit=1] attributes`).

### Manual (human-in-game) — REQUIRED

> Not verified — needs a human in-game. The ender-pearl behaviour (3e)
> is also UNTESTED: it is a test-and-verify item with no code of its own,
> and driving a pearl throw through a Carpet bot was out of scope.

- [ ] **Throw item at portal:** item flies through, appears on other side
  with momentum preserved
- [ ] **Shoot arrow through:** arrow continues trajectory in target dimension
- [ ] **Throw ender pearl through:** player teleports to pearl's landing spot
  in the target dimension
- [ ] **Throw snowball through:** snowball continues, hits mob on other side
- [ ] **Drop XP near portal:** orbs drift through, collectible on other side
- [ ] **Falling sand:** sand entity falls through horizontal portal
- [ ] **Mob near portal:** mob DOES pass through (Phase 3b — was the load-bearing
  negative before, is the load-bearing positive now)
- [ ] **Villager brought through:** trades, profession and workstation
  behaviour on the far side look sane to a human
- [ ] **Leashed animal:** crosses, lead drops on the near side, no rope is
  still drawn to it afterwards
- [ ] **Ridden horse:** does not cross; the player dismounts and walks through

## Shipping Criteria

Phase 3 ships independently when:

1. Items, projectiles, XP orbs, and falling blocks pass through immersive portals
2. Velocity is preserved across the transition
3. Portal cooldown prevents ping-pong teleportation
4. **(Phase 3b, was: "living entities are explicitly excluded")** Living
   entities pass through, players do not, and vanilla's own `canUsePortals`
   veto is honoured
5. `"entityPassthrough": false` disables the feature
6. Non-immersive portals are completely unaffected
7. No errors or entity duplication during pass-through
8. All existing portal tests pass unchanged
9. `./gradlew build` produces a valid remapped jar

## Known behaviour and limits (as shipped)

- **Arrival chunk must be loaded.** If the arrival column's chunk is not
  loaded, the crossing is skipped and retried on later ticks rather than
  force-generated. In practice Phase 0's proximity pre-loader and Phase 1's
  chunk ticket have it loaded whenever a player is near enough to throw
  anything at the portal; a dispenser firing at an unattended portal in an
  unvisited target world will not cross until something else loads that chunk.
  This is the deliberate trade against the sync-load wedge.
- **No arrival-portal reuse.** The player path calls `findExistingPortal`
  (an ~11×33×11 `getBlockState` sweep) to land on an existing arrival portal.
  Entities skip that — it is far too expensive per entity per tick and it
  sync-loads. Entities land on the mapped arrival column, which is where that
  portal was built, so in practice they arrive at or inside it anyway.
- **One bounded round trip is possible.** An entity returning from an arrival
  portal can land inside its own source zone. The edge trigger stops it
  re-crossing while it sits there; it will cross again only if it leaves and
  re-enters. Entities that cross INTO a real arrival portal block have their
  cooldown pinned at 300 by vanilla's own `tryUsePortal` reset and settle.
- **Very long single-tick movements** (>16 blocks) are sampled at coarser than
  half-block resolution by the swept test's 32-sample cap. No vanilla
  pass-through-eligible entity moves that fast.
- **Ender pearl chain teleport (3e) is unverified** — it needs a human with a
  pearl. The pearl itself crosses like any other projectile.

### Added by Phase 3b

- **Vehicles and their passengers do not cross** — a known gap, stated in full
  in § Vehicles above. Boats, minecarts, and any mount with someone aboard stay
  put; an unridden mount crosses as a plain mob.
- **A leashed mob's lead drops on the NEAR side**, not the far side. That is
  the one deliberate divergence from vanilla, taken to avoid a stray leash knot
  being spawned in the destination world. See § Leashes.
- **Difficulty is re-applied but never stripped.** A mob walked INTO a scaled
  dimension gets the destination's modifiers; a mob walked OUT of one into an
  unscaled world keeps its old boost, because `DifficultyManager.applyMobModifiers`
  returns early before its remove-then-add. Needs a strip-only path in
  `DifficultyManager` to close.
- **A mob resting ON a horizontal (END_PORTAL) arrival does not return.** The
  arrival-side check looks at the entity's own block position only, and an
  entity standing on top of a floor portal has its feet block in the air above
  it. Pre-existing for items; more visible now that mobs walk about on arrival
  portals. Fixing it costs two extra block lookups per non-player entity per
  tick, which is the price the arrival-side design explicitly declines to pay.
- **Mobs will oscillate near a portal pair**, wandering out and back in and
  crossing again. The cooldown and the entry-edge trigger bound how fast, but
  they do not stop it — and vanilla nether portals behave the same way.
- **`FishingBobberEntity` no longer crosses.** It did before 3b, purely because
  it is a `ProjectileEntity`; vanilla's `canUsePortals` forbids it. Behaviour
  change to the pre-3b item path, deliberate.
- **Documentation elsewhere is now stale and was not editable from this
  workstream:** `mods/AGENTS.md` still says "Living entities are excluded
  wholesale" and carries the verification line "Load-bearing negative: a pig
  must NOT cross (living entities excluded)"; `immersive/PLAN.md` §Phase
  Summary still describes Phase 3 as items/projectiles/orbs/falling blocks.
  Both need updating alongside this change.

## Research Notes

### Entity teleportation in 1.21.1 — VERIFIED against the Yarn-mapped jar

The claim above that `teleport(...)` "returns void in 1.21.1" was **wrong**;
so was the implied need for a UUID re-lookup. Ground truth:

```java
public boolean Entity.teleport(ServerWorld, double, double, double, Set<PositionFlag>, float, float)
public Entity  Entity.teleportTo(TeleportTarget)                 // returns the ARRIVAL entity
public boolean Entity.velocityModified;                          // public field
public void    Entity.setVelocity(Vec3d)
public void    Entity.setPortalCooldown(int)
public int     Entity.getPortalCooldown()

public record TeleportTarget(ServerWorld world, Vec3d pos, Vec3d velocity,
                             float yaw, float pitch, PostDimensionTransition)
// constants: NO_OP, SEND_TRAVEL_THROUGH_PORTAL_PACKET, ADD_PORTAL_CHUNK_TICKET
```

Decompiling `Entity.teleportTo` confirms the mechanics: for a cross-dimension
move it builds `this.getType().create(destination)`, `copyFrom`s the original
(NBT, **including portal cooldown**), calls `removeFromDimension()` on the
original, then `refreshPositionAndAngles(...)` and
`setVelocity(teleportTarget.velocity())` on the NEW entity, and finally
`postDimensionTransition().onTransition(newEntity)`.

So velocity is a first-class field of the target and lands on the entity that
actually arrives, in the same operation as the position — **which dissolves
Gotcha #10 entirely**. `teleportTo` also hands back the live reference, so the
plan's teleport → look-up-by-UUID → set-velocity dance is unnecessary. The
`teleport(world, x, y, z, ...)` overload is the trap: it recreates the entity
too, but returns only a boolean, so `entity.setVelocity(...)` afterwards writes
to a removed entity.

`ADD_PORTAL_CHUNK_TICKET` resolves to
`entity.addPortalChunkTicketAt(BlockPos.ofFloored(entity.getPos()))` →
`chunkManager.addTicket(ChunkTicketType.PORTAL, chunkPos, 3, pos)` — an
asynchronous ticket in the DESTINATION world. That is why it is safe under the
never-sync-load rule, and why it is the right choice over `NO_OP` for arrivals
into a world with no players in it.

### Velocity preservation details

`Entity.getVelocity()` returns a `Vec3d` — the entity's motion per tick.
After cross-dimension teleport, this needs to be restored because:
- `teleport()` resets the entity's motion to zero
- The client needs to be told about the new velocity (`velocityModified = true`
  triggers a `EntityVelocityUpdateS2CPacket`)

For projectiles, the velocity determines their flight trajectory. For items,
it determines their bounce/slide behaviour. For XP orbs, it affects their
drift toward players.

### Entity duplication risk

Cross-dimension entity teleport in vanilla has a known edge case: if the
source world's entity list is iterated during the teleport (which removes
the entity from the source), a `ConcurrentModificationException` can occur.

Our scan uses `world.getOtherEntities()` which returns a snapshot list.
The teleport inside the iteration is safe because:
1. The snapshot is already captured
2. The entity is removed from the source world's entity list by `teleport()`
3. Future iterations of the snapshot skip removed entities (`entity.isRemoved()`)

The `isRemoved()` check in `isPassthroughEligible` also prevents double-processing
if the snapshot somehow contains the entity twice.

### Interaction with the existing player teleport loop

The player teleport loop in `ServerWorldMixin.onTick()` runs BEFORE the entity
scan. This means:
1. Player enters portal zone → player teleport fires (existing code)
2. Items/projectiles in the same zone → entity scan fires (new code)

The entity scan must NOT process entities that were just spawned by the
player teleport (e.g., items dropped on death during teleport). The
`getPortalCooldown() == 0` check handles this — entities spawned during
the current tick have cooldown=0 but haven't been in the zone long enough
to be detected (they spawn at the arrival position, not the source zone).

### Gotchas (see PLAN.md § Agent Gotchas for full list)

- **Gotcha #5 (source zones have NO portal blocks):** Entities in source zones
  are standing in AIR, not portal blocks. `EntityTickPortalMixin` only fires
  for entities IN portal blocks — so it only handles the arrival-side return
  path. Source-side pass-through MUST use the world-tick entity scan.
- **Gotcha #6 (namespace guard):** The entity scan iterates `PORTAL_ZONES`
  (populated only for our dimensions), so the namespace guard is not needed
  here. But if you add any lookup by world key path, apply it.
- **Gotcha #10 (Entity.teleport cross-dimension identity):** RESOLVED, not
  worked around. `teleportTo(TeleportTarget)` returns the arrival entity and
  carries velocity in the target record, so there is no window in which a dead
  reference can be written to. See the corrected research notes above.
- **Gotcha #12 (tick ordering):** The entity scan goes AFTER
  `ImmersiveProjector.tick()` (step 8) and before `ExitConditions.tick()`
  (step 9). A player who teleported this tick (step 4) is already gone —
  the entity scan processes the remaining non-player entities.

### Why not extend EntityTickPortalMixin for source-side pass-through?

`EntityTickPortalMixin` hooks `Entity.tickPortalTeleportation()`, which only
fires when an entity is standing in a PORTAL BLOCK (NETHER_PORTAL, END_PORTAL,
END_GATEWAY). Source-side portal zones are INVISIBLE — they have no portal
blocks (only arrival portals do). So `tickPortalTeleportation` never fires
for entities in source zones.

That's why Phase 3a uses the world-tick entity scan approach instead: it
checks position against zone interiors, not block states.

Phase 3d DOES extend `EntityTickPortalMixin` for the ARRIVAL side, where real
portal blocks exist and `tickPortalTeleportation` does fire.
