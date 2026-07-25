# Phase 3 — Entity Pass-Through

> **Depends on:** Phase 0 (config parsing + pre-loaded target world)
> **Unlocks:** Phase 4 (Polish)
> **Independent of:** Phases 1 and 2 (can be built in parallel)
> **Status:** Not started

## Goal

Items thrown into an immersive portal appear on the other side with preserved
velocity. Projectiles (arrows, tridents, potions, snowballs) fly through.
XP orbs drift through. The portal acts like an open doorway for non-player
entities.

## Connection to Overall Plan

Phase 3 is the interaction complement to Phase 1's visual preview. Together
they create the illusion that the portal is a physical opening: you can see
through it (Phase 1), hear through it (Phase 2), and throw things through it
(Phase 3).

This phase depends only on Phase 0 (the `ImmersiveSettings` config and the
pre-loaded target world). It does NOT depend on Phase 1 — entity pass-through
works regardless of whether the visual preview is active.

Gated by `ImmersiveSettings.entityPassthrough()` (default true).

## Implementation Checklist

### 3a. Entity detection in portal zones

- [ ] In `ServerWorldMixin.onTick()`, after the existing player teleport loop
  and after `ImmersiveProjector.tick()`, add an entity scan for immersive zones:
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

- [ ] The `boundsOf` helper computes an AABB from the zone's interior positions:
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

**File:** `ServerWorldMixin.java` (~40 lines)

### 3b. Entity eligibility filter

- [ ] Create `isPassthroughEligible` filter:
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

- [ ] **Explicitly excluded:**
  - `ServerPlayerEntity` — already handled by the existing player teleport loop
  - `LivingEntity` (mobs, villagers) — pathfinding, AI state, leash, spawn
    tracking all break on cross-dimension teleport. Future feature.
  - `VehicleEntity` (boats, minecarts) — rider state is complex
  - `ArmorStandEntity` — technically not living but shares placement concerns
  - `ItemFrameEntity`, `PaintingEntity` — attached to blocks
  - Any entity with `hasVehicle()` — passengers follow their vehicle

- [ ] Imports:
  - `net.minecraft.entity.ItemEntity`
  - `net.minecraft.entity.projectile.ProjectileEntity`
  - `net.minecraft.entity.ExperienceOrbEntity`
  - `net.minecraft.entity.FallingBlockEntity`

**File:** Same `ServerWorldMixin.java`

### 3c. Entity teleportation with velocity preservation

- [ ] Create `teleportEntity` method:
  ```java
  private static void teleportEntity(ServerWorld world, Entity entity,
          PortalHelper.PortalZone zone) {
      RegistryKey<World> targetKey = zone.targetWorld;
      ServerWorld targetWorld = world.getServer().getWorld(targetKey);
      if (targetWorld == null) return;

      PortalDefinition def = zone.definition;

      // Compute target position (same transform as player teleport)
      double scale = def.getScale();
      BlockPos portalCentre = PortalShape.centreOf(zone.interior);
      double tx, ty, tz;

      if (def.hasAnchor()) {
          int[] anchor = def.getAnchorPos();
          tx = anchor[0] + 0.5;
          ty = PortalHelper.findSurfaceY(targetWorld, anchor[0], anchor[2]);
          tz = anchor[2] + 0.5;
      } else {
          tx = entity.getX() + (portalCentre.getX() * scale - portalCentre.getX());
          ty = entity.getY(); // preserve relative height
          tz = entity.getZ() + (portalCentre.getZ() * scale - portalCentre.getZ());
      }

      // Capture velocity before teleport
      Vec3d velocity = entity.getVelocity();

      // Teleport
      entity.teleport(targetWorld, tx, ty, tz, Set.of(),
          entity.getYaw(), entity.getPitch());

      // Restore velocity (teleport resets it)
      entity.setVelocity(velocity);
      entity.velocityModified = true;

      // Set cooldown to prevent ping-pong
      entity.setPortalCooldown(def.getCooldown());

      MultiverseServer.LOGGER.debug("Entity {} passed through portal to {}",
          entity.getType().getTranslationKey(), targetKey.getValue());
  }
  ```

- [ ] `entity.velocityModified = true` tells the server to sync the velocity
  to the client on the next tick. Without this, the client predicts zero
  velocity and the entity appears to stop mid-air.

- [ ] The cooldown (`entity.setPortalCooldown()`) prevents immediate re-teleport
  if the entity lands inside the arrival portal's zone. Uses the portal's
  configured cooldown (default 40 ticks / 2 seconds).

**File:** Same `ServerWorldMixin.java` (~35 lines)

### 3d. Arrival-side return teleport for entities

- [ ] The existing `EntityTickPortalMixin` only handles `ServerPlayerEntity`.
  Extend it to also handle passthrough-eligible non-player entities standing
  in arrival portal blocks:
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

- [ ] This handles the case where an item or projectile lands in an ARRIVAL
  portal (which has real NETHER_PORTAL blocks) and needs to return to the
  source dimension.

- [ ] **Caution:** `EntityTickPortalMixin` targets `Entity.tickPortalTeleportation`.
  The non-player path must NOT cancel the callback unless it actually teleports —
  vanilla's portal logic for non-player entities is different from players
  (no loading screen, just delayed teleport after `netherPortalTime` ticks).

**File:** `EntityTickPortalMixin.java` (~30 lines)

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

- [ ] Spawn items near an immersive portal:
  ```bash
  # Give bot items and throw them toward the portal
  docker exec -i mc rcon-cli 'summon minecraft:item <portal_x> <portal_y+1> <portal_z> {Item:{id:"minecraft:diamond",Count:1b},Motion:[0.0,0.0,-0.5]}'
  ```
- [ ] Check item appeared in target dimension:
  ```bash
  docker exec -i mc rcon-cli 'execute in <ns>:<dim> run kill @e[type=item,distance=..10]'
  # Should report killing the diamond
  ```
- [ ] Shoot arrow through:
  ```bash
  docker exec -i mc rcon-cli 'summon minecraft:arrow <portal_x> <portal_y+1> <portal_z> {Motion:[0.0,0.0,-1.0]}'
  # Check arrow in target dimension
  ```
- [ ] XP orb through:
  ```bash
  docker exec -i mc rcon-cli 'summon minecraft:experience_orb <portal_x> <portal_y+1> <portal_z>'
  ```
- [ ] Non-immersive portal: entities do NOT pass through (existing behaviour)
- [ ] `"entityPassthrough": false`: entities do NOT pass through
- [ ] Entity with portal cooldown: does NOT re-teleport (no ping-pong)

### Manual (human-in-game) — REQUIRED

- [ ] **Throw item at portal:** item flies through, appears on other side
  with momentum preserved
- [ ] **Shoot arrow through:** arrow continues trajectory in target dimension
- [ ] **Throw ender pearl through:** player teleports to pearl's landing spot
  in the target dimension
- [ ] **Throw snowball through:** snowball continues, hits mob on other side
- [ ] **Drop XP near portal:** orbs drift through, collectible on other side
- [ ] **Falling sand:** sand entity falls through horizontal portal
- [ ] **Mob near portal:** mob does NOT pass through (excluded)

## Shipping Criteria

Phase 3 ships independently when:

1. Items, projectiles, XP orbs, and falling blocks pass through immersive portals
2. Velocity is preserved across the transition
3. Portal cooldown prevents ping-pong teleportation
4. Living entities (mobs) are explicitly excluded
5. `"entityPassthrough": false` disables the feature
6. Non-immersive portals are completely unaffected
7. No errors or entity duplication during pass-through
8. All existing portal tests pass unchanged
9. `./gradlew build` produces a valid remapped jar

## Research Notes

### Entity teleportation in 1.21.1

`Entity.teleport(ServerWorld world, double x, double y, double z,
Set<PositionFlag> flags, float yaw, float pitch)` — cross-dimension teleport
for any entity. This is the same method the player teleport uses.

For non-player entities, this:
1. Removes the entity from the source world
2. Creates a copy in the target world at the destination
3. The returned boolean indicates success

**Important:** after `teleport()`, the original entity reference may be invalid
(the entity is recreated in the new world). The velocity must be set on the
entity AFTER teleport, but the teleport itself returns void in 1.21.1 (it was
refactored from returning `Entity` in earlier versions). We need to verify
that `setVelocity()` on the same reference still works after cross-dimension
teleport in 1.21.1.

**Fallback:** If `setVelocity()` doesn't stick after teleport, we can:
1. Get the new entity from the target world by UUID
2. Set velocity on the looked-up entity
```java
Entity arrived = targetWorld.getEntity(entity.getUuid());
if (arrived != null) {
    arrived.setVelocity(velocity);
    arrived.velocityModified = true;
}
```

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
- **Gotcha #10 (Entity.teleport cross-dimension identity):** After
  cross-dimension `Entity.teleport()`, the original entity reference may be
  dead. Look up the entity in the target world by UUID to set velocity:
  ```java
  UUID id = entity.getUuid();
  entity.teleport(targetWorld, tx, ty, tz, Set.of(), yaw, pitch);
  Entity arrived = targetWorld.getEntity(id);
  if (arrived != null) {
      arrived.setVelocity(velocity);
      arrived.velocityModified = true;
  }
  ```
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
