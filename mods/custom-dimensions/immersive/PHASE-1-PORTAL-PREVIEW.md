# Phase 1 — Portal Preview (Fake Block Projection)

> **Depends on:** Phase 0 (config parsing + pre-loaded target world/chunks)
> **Unlocks:** Phase 2 (Cross-Portal Audio), Phase 4 (Polish)
> **Status:** Not started

## Goal

Players can see real blocks from the destination dimension through the portal
frame, with natural parallax as they move. Server-side only — no client mod
required. This is the hero feature of the immersive portals system.

## Connection to Overall Plan

Phase 1 is the highest-value feature. It delivers the core "see through the
portal" experience using the immersive-cursedness technique: the server sends
fake block-update packets to the client, overwriting source-dimension blocks
behind the portal with target-dimension blocks. The vanilla client renders
them as real geometry — parallax works for free because the blocks sit at
real 3D coordinates.

Phase 0's pre-loading is a hard dependency: the projector samples block states
from the target dimension, which requires its world to be loaded and its
arrival chunks to be generated.

Phase 2 (audio) reuses this phase's per-player activation tracking and tick
loop. Phase 4 (polish) refines the visual output.

## Implementation Checklist

### 1a. Projection geometry (`ProjectionVolume`)

- [ ] Create `ProjectionVolume` in `com.customdimensions.immersive`:
  ```java
  public final class ProjectionVolume {
      // Compute the rectangular slab of source-dimension positions to project
      public static Set<BlockPos> computeSourcePositions(
              PortalHelper.PortalZone zone, int depth, int radius) {
          // 1. Find the portal's normal direction from its axis
          // 2. Extend `depth` blocks in the normal direction (away from player)
          // 3. Pad `radius` blocks on each non-normal axis beyond the interior bounds
          // 4. Return all positions in this rectangular prism
      }

      // Map a source position to its target-dimension equivalent
      public static BlockPos toTarget(BlockPos sourcePos, BlockPos portalCentre,
              int arrivalY, double scale, int portalMinY) {
          // Same coordinate transform as ServerWorldMixin's teleport logic:
          // targetX = (sourceX - portalCentreX) + round(portalCentreX * scale)
          // targetY = sourceY - portalMinY + arrivalY
          // targetZ = (sourceZ - portalCentreZ) + round(portalCentreZ * scale)
      }
  }
  ```
- [ ] The normal direction depends on the zone's `axis`:
  - `Axis.X` → portal plane is in the XY plane, normal is ±Z
  - `Axis.Z` → portal plane is in the ZY plane, normal is ±X
  - `Axis.Y` → portal plane is horizontal, normal is ±Y (down, for player looking into)
- [ ] The normal sign (which side to project on) is determined by which side
  has MORE air blocks adjacent to the interior — the "open" side where the
  player approaches from. Compute once at activation, cache.
- [ ] Anchor portals: target coordinates use the anchor position instead of
  scaled portal centre

**File:** New `immersive/ProjectionVolume.java` (~100 lines)

### 1b. Per-player projection state (`PlayerProjectionState`)

- [ ] Create `PlayerProjectionState` in `com.customdimensions.immersive`:
  ```java
  public final class PlayerProjectionState {
      private final ServerPlayerEntity player;
      private final PortalHelper.PortalZone zone;
      // Last-sent block state per position, for delta updates
      private final Map<BlockPos, BlockState> lastSent = new HashMap<>();
      private boolean active;

      // Send the full projection (initial activation)
      public void sendFull(ServerWorld sourceWorld, ServerWorld targetWorld,
              ImmersiveSettings settings) { ... }

      // Send only changed blocks (periodic refresh)
      public void sendDelta(ServerWorld sourceWorld, ServerWorld targetWorld,
              ImmersiveSettings settings) { ... }

      // Restore real source-dimension blocks
      public void cleanup(ServerWorld sourceWorld) { ... }
  }
  ```
- [ ] Track active projections in a static map:
  `Map<UUID, List<PlayerProjectionState>>` (player → active portal projections)

**File:** New `immersive/PlayerProjectionState.java` (~120 lines)

### 1c. Packet sending

The critical implementation detail. Two vanilla packet types:

**`BlockUpdateS2CPacket`** — single block update:
```java
player.networkHandler.sendPacket(
    new BlockUpdateS2CPacket(sourcePos, targetBlockState));
```

**`ChunkDeltaUpdateS2CPacket`** — batched updates within one chunk section:
```java
// Group positions by SectionPos (chunk x, section y, chunk z)
// For each section, build a ShortSet of section-local positions
// and a BlockState[] of the target states
SectionPos section = SectionPos.from(pos);
ChunkDeltaUpdateS2CPacket packet = new ChunkDeltaUpdateS2CPacket(
    section, shortSet, blockStates);
player.networkHandler.sendPacket(packet);
```

- [ ] Use `BlockUpdateS2CPacket` for delta updates (typically 0-10 blocks)
- [ ] Use `ChunkDeltaUpdateS2CPacket` for initial full sends (up to 336 blocks,
  batched by chunk section — vanilla limit is 64 per section per packet)
- [ ] For cleanup, send `BlockUpdateS2CPacket` with the REAL source-dimension
  block state at each position in `lastSent`
- [ ] Import paths (1.21.1 Yarn mappings):
  - `net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket`
  - `net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket`
  - `net.minecraft.util.math.ChunkSectionPos` (the 1.21.1 name for SectionPos)

**Research note:** `ChunkDeltaUpdateS2CPacket` constructor in 1.21.1:
```java
public ChunkDeltaUpdateS2CPacket(ChunkSectionPos sectionPos,
    ShortSet positions, BlockState[] states)
```
The `ShortSet` contains section-local packed positions (x<<8 | z<<4 | y for
each block within the 16×16×16 section). Verify this constructor exists in
our Yarn mappings before implementation.

**Fallback:** If `ChunkDeltaUpdateS2CPacket`'s constructor isn't accessible
(it may be package-private or differently shaped in 1.21.1), use individual
`BlockUpdateS2CPacket` calls. Slightly more overhead but functionally identical.

**Files:** Integrated into `PlayerProjectionState.java`

### 1d. Main tick loop (`ImmersiveProjector`)

- [ ] Create `ImmersiveProjector` in `com.customdimensions.immersive`:
  ```java
  public final class ImmersiveProjector {
      private static final Map<UUID, Map<String, PlayerProjectionState>> ACTIVE = new ConcurrentHashMap<>();

      public static void tick(ServerWorld world) {
          RegistryKey<World> worldKey = world.getRegistryKey();
          long tick = world.getServer().getTicks();

          for (PortalHelper.PortalZone zone : PortalHelper.getSourceZones(worldKey)) {
              ImmersiveSettings imm = zone.definition.getImmersive();
              if (imm == null) continue;
              // Skip gateway portals (no projection plane)
              if (PortalShape.END_GATEWAY.equals(zone.definition.getShape())) continue;

              BlockPos centre = PortalShape.centreOf(zone.interior);
              String zoneKey = worldKey.getValue() + "|" + centre.toShortString();

              for (ServerPlayerEntity player : world.getPlayers()) {
                  double dist = player.getBlockPos().getSquaredDistance(centre);
                  UUID uuid = player.getUuid();
                  Map<String, PlayerProjectionState> states =
                      ACTIVE.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
                  PlayerProjectionState state = states.get(zoneKey);

                  if (dist > imm.activationRange() * imm.activationRange()) {
                      // Player too far: deactivate
                      if (state != null) {
                          state.cleanup(world);
                          states.remove(zoneKey);
                      }
                      continue;
                  }

                  ServerWorld targetWorld = world.getServer().getWorld(zone.targetWorld);
                  if (targetWorld == null) continue;

                  if (state == null) {
                      // New activation: full send
                      state = new PlayerProjectionState(player, zone);
                      state.sendFull(world, targetWorld, imm);
                      states.put(zoneKey, state);
                  } else if (tick % imm.refreshInterval() == 0) {
                      // Periodic refresh: delta only
                      state.sendDelta(world, targetWorld, imm);
                  }
              }
          }
      }

      // Called from disconnect event + zone removal + world unload
      public static void cleanupPlayer(UUID uuid, ServerWorld world) { ... }
      public static void cleanupZone(PortalHelper.PortalZone zone, ServerWorld world) { ... }
      public static void clear() { ACTIVE.clear(); }
  }
  ```
- [ ] Call from `ServerWorldMixin.onTick()`, after `PortalAuraManager.tick(world)`:
  ```java
  com.customdimensions.immersive.ImmersiveProjector.tick(world);
  ```
- [ ] Hook cleanup into existing removal paths:
  - `PortalHelper.removeZone()` → `ImmersiveProjector.cleanupZone()`
  - `ServerPlayConnectionEvents.DISCONNECT` → `ImmersiveProjector.cleanupPlayer()`
  - `WorldLoaderMixin.onShutdown()` → `ImmersiveProjector.clear()`

**Files:** New `immersive/ImmersiveProjector.java` (~150 lines),
modified `ServerWorldMixin.java` (~2 lines), modified `MultiverseServer.java`
(~3 lines for disconnect hook), modified `WorldLoaderMixin.java` (~1 line)

### 1e. Cleanup safety net

- [ ] On player relog, any leaked fake blocks are corrected by vanilla's chunk
  resend (the client reloads chunks from the server on join). Document this
  as the defence-in-depth layer.
- [ ] On zone removal (`isZoneValid` fails → `clearInteriorPortals` →
  `removeZone`), call `ImmersiveProjector.cleanupZone()` which iterates all
  `PlayerProjectionState` entries for that zone and calls `cleanup()`
- [ ] `cleanup()` iterates `lastSent` and sends `BlockUpdateS2CPacket` with
  the real `world.getBlockState(pos)` for each position
- [ ] If `cleanup()` finds the player disconnected (networkHandler null),
  skip — the relog resend handles it

### 1f. Unit tests

- [ ] `ProjectionVolumeTest.java`:
  - `testVerticalPortalXAxis` — 2×3 portal on X axis, depth=4, radius=1 →
    correct slab dimensions and positions
  - `testVerticalPortalZAxis` — same but Z axis
  - `testHorizontalPortal` — Y axis, depth extends downward
  - `testTargetMapping` — source→target coordinate transform with scale 0.5
  - `testAnchorTargetMapping` — anchor portal targets the anchor position

**File:** New `ImmersiveProjectionTest.java` (~60 lines)

## Verification Checklist

### Automated (Carpet bot + RCON + log grep)

- [ ] Boot with `"immersive": true` on a test dimension
- [ ] Spawn Carpet bot near the portal — log shows "immersive projection
  activated" (or equivalent)
- [ ] Move bot away — log shows "immersive projection deactivated"
- [ ] Break portal frame while bot is near — log shows cleanup
- [ ] No errors in server log throughout lifecycle
- [ ] Non-immersive portal: no projection logs at all
- [ ] Unit tests pass

### Manual (human-in-game) — REQUIRED for this phase

- [ ] **Approach the portal:** destination blocks appear behind the frame
- [ ] **Walk around:** parallax is correct (view through frame shifts naturally)
- [ ] **Walk away:** fake blocks disappear, real terrain restored
- [ ] **Break the frame:** fake blocks cleaned up immediately
- [ ] **Relog near portal:** projection re-establishes, no phantom blocks
- [ ] **Two players near same portal:** each sees the projection independently
- [ ] **Portal to a void dimension:** projection shows void-dimension terrain
  (or source blocks if arrival chunks are ungenerated — graceful degradation)
- [ ] **Portal with scale != 1:** projected blocks match the scaled destination

## Shipping Criteria

Phase 1 ships independently when:

1. Fake blocks are visible through portal frames for immersive portals
2. Parallax works naturally (blocks at real 3D coordinates)
3. Cleanup is reliable: no phantom blocks after walking away, breaking frame,
   relogging, or disconnecting
4. Non-immersive portals are completely unaffected
5. Gateway portals are silently excluded (no crash, no error)
6. Performance is acceptable: <5ms per tick with one active portal per player
7. All existing portal tests pass unchanged
8. Unit tests for projection geometry pass
9. `./gradlew build` produces a valid remapped jar

## Research Notes

### The immersive-cursedness reference implementation

TheEpicBlock/immersive-cursedness (1.16–1.19) proves this technique works. Key
differences from our implementation:

- **Their `PortalManager`** runs a dedicated tick loop at ~50ms intervals with
  its own `PlayerManager` per player. We run from the existing `ServerWorldMixin`
  tick loop — simpler, no threading concerns.
- **Their coordinate transform** assumes vanilla nether scaling (1:8). Ours uses
  the portal's configured `scale` field (already in `PortalDefinition`).
- **Their packet format** targets 1.19. We target 1.21.1 — the
  `BlockUpdateS2CPacket` API is stable but `ChunkDeltaUpdateS2CPacket` may
  have constructor changes. Verify before implementing.
- **Their limitations** are our limitations: no block entities (chests render
  without contents), no entities, source-dimension lighting applies.

### Packet budget analysis

Worst case per portal per player per refresh (with defaults depth=8, radius=2):
- Projection volume for a 2×3 doorway portal:
  - Width: 2 + 2×2 = 6 blocks
  - Height: 3 + 2×2 = 7 blocks
  - Depth: 8 blocks
  - Total: 6 × 7 × 8 = 336 positions
- At 5 Hz refresh: 336 × 5 = 1680 block updates/second (worst case, all changed)
- In practice, delta updates send only changed blocks — static terrain means
  0-5 updates per refresh cycle after the initial send
- Vanilla handles ~3000 block updates/second during normal gameplay (chunk loading,
  redstone, world gen). Our worst case adds ~50% — acceptable for one portal.
- Two portals in view: 672 initial, then near-zero steady state.

### Block state lookup cost

`targetWorld.getBlockState(pos)` on a loaded chunk is a direct array lookup
(O(1), ~20ns). For 336 positions per refresh: ~7µs — negligible.

The position→chunk lookup (`ChunkManager.getChunk()` with `ChunkStatus.FULL`)
is also O(1) for loaded chunks. Unloaded chunks would synchronously generate,
which is why Phase 0's pre-loading is critical.

### Known limitations (acceptable for MVP)

1. **Block entities** (chests, signs, heads) render as their block model without
   NBT data (empty chest, blank sign). Acceptable — it's a preview.
2. **Lighting** comes from the source dimension, not the target. A portal from
   a sunny overworld to a dark cave shows the cave blocks in full daylight.
   Phase 4 adds LIGHT blocks as a partial mitigation.
3. **Entities** in the target dimension are not visible through the portal.
   This would require custom entity-data packets — out of scope for MVP.
4. **Weather** effects (rain, snow) are not projected. Acceptable.
5. **Biome-dependent rendering** (water colour, foliage colour) uses the source
   biome's palette, not the target's. This is a fundamental vanilla limitation —
   biome colours are computed client-side from the client's current biome data.
6. **Translucent blocks** (water, ice, stained glass) are sent as block states
   and render correctly in most cases. Water may look odd because its flow
   state depends on neighbouring blocks that may be real source-dimension blocks.

### Gotchas (see PLAN.md § Agent Gotchas for full list)

- **Gotcha #1 (packet-fixer):** The server runs `packet-fixer`. Before building
  the full projection system, test that a single manually-sent
  `BlockUpdateS2CPacket` actually renders on the client. If packet-fixer
  intercepts it, you may need a different sending path.
- **Gotcha #2 (Supplementaries piston crash):** Fake block packets are
  CLIENT-ONLY (never placed in the world), so NOTIFY_NEIGHBORS is irrelevant.
  But if you ever need to place a real block, use `NOTIFY_LISTENERS | FORCE_STATE`.
- **Gotcha #5 (source zones have NO portal blocks):** The projection volume
  extends behind the portal frame where there are NORMAL WORLD BLOCKS. You're
  overwriting dirt/air/stone visually. This is the correct behaviour.
- **Gotcha #8 (cleanup must restore real blocks):** If a projection position
  overlaps a real portal block (anchor portals), `cleanup()` must use
  `world.getBlockState(pos)` to restore the REAL state, not AIR.
- **Gotcha #11 (idle unloader):** The target world can be unloaded mid-projection
  if no player enters it within 5 minutes. The refresh cycle must check for null
  target world every tick.
- **Gotcha #12 (tick ordering):** `ImmersiveProjector.tick()` must come AFTER
  the player teleport loop (step 4) and aura manager (step 7) in the existing
  tick order. See PLAN.md for the full ordering.
- **Gotcha #13 (build system):** New files need no config changes.

### Critical code paths to understand

**`PortalHelper.removeZone()`** (line 271) — called when a zone becomes invalid
(frame broken). This is where we hook cleanup. The method mutates the
`PORTAL_ZONES` list, so we must capture the zone reference before it's removed.

**`ServerWorldMixin.onTick()`** (line 31) — our injection point. The existing
structure is:
1. `restoreZones(world)` — claim pending zones
2. Validity check loop — removes broken zones
3. Particle spawn loop — spawn zone particles
4. Player proximity loop — detect zone entry, trigger teleport
5. `spawnTargetPortalParticles(world)` — target-side particles
6. `ExitPortalManager.tick(world)` — exit portal maintenance
7. `PortalAuraManager.tick(world)` — aura conversion passes
8. `ExitConditions.tick(world)` — void/fall exits
9. `ExitShrineManager.processQueued(world)` — shrine beacon lighting
10. `DimensionManager.updatePlayerPresence()` — idle tracking

We add `ImmersiveProjector.tick(world)` after step 7 (aura manager), before
exit conditions. This ensures:
- Zones are validated (invalid zones already cleaned up at step 2)
- Particles are spawned (visual consistency)
- Aura palettes are sampled (if we ever want to use them for projection colouring)
- Player presence is tracked AFTER our tick (so idle unload considers our activity)
