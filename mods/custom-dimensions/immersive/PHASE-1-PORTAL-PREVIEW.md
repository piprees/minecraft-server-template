# Phase 1 — Portal Preview (Fake Block Projection)

> **Depends on:** Phase 0 (config parsing + pre-loaded target world/chunks)
> **Unlocks:** Phase 2 (Cross-Portal Audio), Phase 4 (Polish)
> **Status:** Complete

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

- [x] Create `ProjectionVolume` in `com.customdimensions.immersive`:
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
- [x] The normal direction depends on the zone's `axis`:
  - `Axis.X` → portal plane is in the XY plane, normal is ±Z
  - `Axis.Z` → portal plane is in the ZY plane, normal is ±X
  - `Axis.Y` → portal plane is horizontal, normal is ±Y (down, for player looking into)
- [x] The normal sign (which side to project on) is determined by which side
  has MORE air blocks adjacent to the interior — the "open" side where the
  player approaches from. Compute once at activation, cache.
- [x] Anchor portals: target coordinates use the anchor position instead of
  scaled portal centre

**File:** New `immersive/ProjectionVolume.java` (~100 lines)

### 1b. Per-player projection state (`PlayerProjectionState`)

- [x] Create `PlayerProjectionState` in `com.customdimensions.immersive`:
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
- [x] Track active projections in a static map:
  `Map<UUID, List<PlayerProjectionState>>` (player → active portal projections)

**File:** New `immersive/PlayerProjectionState.java` (~120 lines)

### 1c. Packet sending

Every packet is a **`BlockUpdateS2CPacket`** — full sends, deltas, and cleanup.
Verified public constructor in 1.21.1:

```java
public BlockUpdateS2CPacket(net.minecraft.util.math.BlockPos pos,
    net.minecraft.block.BlockState state)
```

Sent directly on the connection (`networkHandler` is a public field on
`ServerPlayerEntity`), which also side-steps Gotcha #1's packet-fixer concern:

```java
player.networkHandler.sendPacket(
    new BlockUpdateS2CPacket(sourcePos, targetBlockState));
```

- [x] Use `BlockUpdateS2CPacket` for the initial full send, for delta updates,
  and for cleanup (cleanup sends the REAL source-dimension block state at each
  position in `lastSent` — never a hardcoded AIR, see Gotcha #8)
- [x] Import path (1.21.1 Yarn mappings):
  - `net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket`

**Research note (corrected 2026-07-25 — the previous note documented a
constructor that does not exist).** `ChunkDeltaUpdateS2CPacket` is **unusable
for this feature**. Decompiling the Yarn-mapped 1.21.1 jar gives exactly one
public constructor:

```java
public ChunkDeltaUpdateS2CPacket(net.minecraft.util.math.ChunkSectionPos,
    it.unimi.dsi.fastutil.shorts.ShortSet,
    net.minecraft.world.chunk.ChunkSection)
```

It takes a **`ChunkSection`** and reads the block states *out of that section*
— i.e. out of the real world. There is no `BlockState[]` constructor, so the
packet cannot express fake states at all, and synthesising a `ChunkSection`
to fool it is not on the table. Do not reintroduce it.

**Packet budget makes batching unnecessary.** The worst-case initial send —
default 2×3 doorway at `previewDepth` 8 / `previewRadius` 2 — is 336 positions
× ~14 bytes ≈ **5 KB, once per activation**. Steady state is near zero: the
delta pass only sends positions whose target block actually changed. This
constraint and its rationale are recorded in `PlayerProjectionState`'s class
comment so nobody "optimises" it back.

**Files:** Integrated into `PlayerProjectionState.java`

### 1d. Main tick loop (`ImmersiveProjector`)

- [x] Create `ImmersiveProjector` in `com.customdimensions.immersive`:
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
- [x] Call from `ServerWorldMixin.onTick()`, after `PortalAuraManager.tick(world)`:
  ```java
  com.customdimensions.immersive.ImmersiveProjector.tick(world);
  ```
- [x] Hook cleanup into existing removal paths:
  - `PortalHelper.removeZone()` → `ImmersiveProjector.cleanupZone()`
  - `ServerPlayConnectionEvents.DISCONNECT` → `ImmersiveProjector.cleanupPlayer()`
  - `WorldLoaderMixin.onShutdown()` → `ImmersiveProjector.clear()`

**Files:** New `immersive/ImmersiveProjector.java` (~150 lines),
modified `ServerWorldMixin.java` (~2 lines), modified `MultiverseServer.java`
(~3 lines for disconnect hook), modified `WorldLoaderMixin.java` (~1 line)

### 1e. Cleanup safety net

- [x] On player relog, any leaked fake blocks are corrected by vanilla's chunk
  resend (the client reloads chunks from the server on join). Document this
  as the defence-in-depth layer.
- [x] On zone removal (`isZoneValid` fails → `clearInteriorPortals` →
  `removeZone`), call `ImmersiveProjector.cleanupZone()` which iterates all
  `PlayerProjectionState` entries for that zone and calls `cleanup()`
- [x] `cleanup()` iterates `lastSent` and sends `BlockUpdateS2CPacket` with
  the real `world.getBlockState(pos)` for each position
- [x] If `cleanup()` finds the player disconnected (networkHandler null),
  skip — the relog resend handles it

#### The relog backstop assumes the server keeps running (found 2026-07-25)

The first item above is true for a player who relogs, but it quietly assumes
there is still a server on the other side that knows what it faked. **A server
restart breaks that assumption**: `ImmersiveProjector.clear()` used to release
chunk tickets and drop `ACTIVE` without sending anything, so every projected
position on every still-connected client was orphaned — and after the restart
the server has no record those positions were ever faked, so it will never
correct them. The client renders destination terrain over what is, server-side,
plain air.

This masqueraded as a masking bug for most of a session. A tester's screenshots
showed foliage floating outside a portal frame; the server's last projection
activity was an hour old with a restart in between. Local iteration installs a
jar and restarts on every change, so it was minting fresh ghosts continuously.

- [x] `clear()` now restores every live projection first, in both directions,
  through the ordinary `PlayerProjectionState.cleanup()` — same real-block
  restore (Gotcha #8), same loaded-chunk guard, no parallel path
- [x] The hook is sound for it: `WorldLoaderMixin.onShutdown` injects at
  `MinecraftServer.shutdown` HEAD, which runs **before** `getNetworkIo().stop()`
  and **before** `PlayerManager.disconnectAllPlayers()`, so the player list is
  live and the channels are open. `ClientConnection.send` writes with
  `flush = true`; the later `disconnect()` does
  `channel.close().awaitUninterruptibly()`, a close queued behind writes that
  have already been flushed
- [x] Nothing in the restore pass can prevent the server stopping —
  `shutdown()` saves every world after this returns, so a failure there would
  cost world data. Each projection is isolated and the whole pass is wrapped
- [x] Logged as a count (`restored N projected positions for M player(s)
  before shutdown`), which is the only evidence a restart handed the blocks
  back rather than orphaning them

**Limit, stated honestly:** this cannot help a hard crash, an OOM kill,
`kill -9`, or the container being torn away — none of them run `shutdown()`,
and no server-side mechanism could, because the knowledge of what was faked
dies with the process. That residue is correctable only by the CLIENT
reloading the affected chunks: relog, F3+A, or walk out past render distance
and back. **Diagnostic rule:** before treating "blocks from another dimension
near a portal" as a projection defect, check the log for a recent
`restored ... before shutdown` line and for a restart in the window — absence
of the former plus presence of the latter is the signature of stale ghosts,
not a live bug.

### 1f. Unit tests

- [x] `ProjectionVolumeTest.java`:
  - `testVerticalPortalXAxis` — 2×3 portal on X axis, depth=4, radius=1 →
    correct slab dimensions and positions
  - `testVerticalPortalZAxis` — same but Z axis
  - `testHorizontalPortal` — Y axis, depth extends downward
  - `testTargetMapping` — source→target coordinate transform with scale 0.5
  - `testAnchorTargetMapping` — anchor portal targets the anchor position

**File:** New `ImmersiveProjectionTest.java` (~60 lines)

### 1g. Arrival-chunk residency (found by live testing, not in the plan)

Two defects surfaced only on the real server. Both had the same signature —
**the projection silently never appears** — and neither is reachable by code
review or unit tests. Recorded here because the fixes look removable if you
don't know what they cost.

**The preview worked exactly once, then died forever.** Approach → activated.
Leave → cleared. Return → nothing, ever again, frame intact. Two
correct-looking mechanisms were guarding each other: the projector refuses to
load chunks (right — sync-loading an ungenerated chunk from the world tick is
the Epic Dungeons + c2me wedge in AGENTS.md "Known issues"), while Phase 0's
`ImmersivePreloader` dedupe only clears on `ServerWorldEvents.UNLOAD`. Only
the *chunks* had unloaded; the world stayed up, so nothing could ever
regenerate them and `arrivalSurfaceY` returned `NO_ARRIVAL` forever.

- [x] The projector holds a **chunk ticket** on the arrival chunks while any
  player is near the zone, released on every teardown path
- [x] The ticket carries a 100-tick **expiry**, refreshed every 20 ticks while
  wanted — a missed release self-heals in 5s rather than pinning chunks forever
- [x] `wantedByAnotherZone` guard: anchor dimensions share one arrival between
  many source portals, and a ticket is one entry keyed on
  `(type, level, argument)`, so a naive release drops it for *every* holder

**Relog while still in range showed nothing.** With stale `lastSent` state the
refresh takes the delta branch, finds nothing changed, and sends zero packets
to a client that has fresh real block data.

- [x] `ServerPlayConnectionEvents.JOIN` → `forgetPlayer` (the non-sending
  teardown; a joining client's chunk data is already authoritative)

Verification note: the initial full send can legitimately report fewer than
the candidate count because the ticket loads asynchronously and the far chunk
misses that tick; the delta pass fills it ~0.2s later. A short count is
therefore expected, not a defect. Since 1h below, the count is also bounded by
the sightline mask rather than by the slab — see there for the numbers.

### 1h. Sightline masking (found by human testing, not in the plan)

Reported in-game: *"the new immersive blocks appear to be happening outside of
the portal frame rather than only inside it, so the server is rendering stuff
when I just look in the general direction of the portal; it needs to be masked
or something to avoid that."* Screenshots showed destination blocks rendered
beside and above the frame, occluding the real world.

`computeSourcePositions` builds a rectangular slab: the interior's in-plane
bounding box **padded by `previewRadius` on both in-plane axes**, extended
`previewDepth` along the normal. Those padded columns sit behind the frame
WALL, not behind the opening. Nothing masked them, so every one of the 336
default-config positions was sent to anyone within `activationRange`,
regardless of whether they could see through the portal at all. The 336-block
budget analysis above is the count of positions that were being sent; it was
never the count that should have been visible.

A portal is a hole, and you can only see through a hole along a line that goes
through it.

- [x] `ProjectionVolume.seesThroughOpening(eye, block, normalAxis, planeCoord,
  interior, scratch)` — the segment from the player's eye to the block's centre
  must cross the portal's mid-plane at a point inside the opening
- [x] The crossing point is floored to a block position and looked up in the
  **interior set itself, never its bounding box**, so an irregular flood-filled
  frame (an arch, an L, a notch) masks per cell — the same discipline
  `EntityPassthrough`'s swept path already uses
- [x] Evaluated **per player, on every send**, in `PlayerProjectionState.send`.
  The mask is a property of where the viewer is standing, not of the zone, so
  it cannot be computed once and cached on the volume
- [x] **Masked-out positions that were previously sent are restored** (real
  block state, from `lastSent`) and dropped from the baseline on the same pass.
  This is the load-bearing part: without it, walking around a portal leaves a
  trail of stuck fake blocks — the same defect class as a missed teardown path,
  but continuous rather than occasional
- [x] The removal is conditional on the correction actually going out. An
  unloaded source chunk keeps the position in `lastSent` so a later pass (or
  the teardown) retries, instead of forgetting a block that is still faked
- [x] The 4c movement test measures the **eye**, not the feet, because the eye
  is what the mask is computed from. A moving player is on the configured
  interval, so the cone follows them and vacated positions are restored on the
  same pass; only a genuinely stationary viewer gets the stretched interval
- [x] `previewRadius` keeps its config field, defaults and clamping, but is now
  only a **bound on how far the visible cone may widen** behind the opening —
  it no longer describes what is shown

What this produces is a view frustum: at the layer against the plane exactly
the opening's own footprint is visible, widening with depth, and sliding
sideways as the player walks (which is also where the parallax comes from).
Measured on the default fixture (2x3 doorway, depth 8, radius 2), pinned in
`ProjectionVolumeTest`:

| eye | visible of 336 |
|---|---|
| 6 blocks out, centred | 149 (6, 8, 15, 20, 20, 24, 28, 28 by depth layer) |
| 6 blocks out, 3 to the side | 90 |
| 6 blocks out, 9 to the side | 13 |

Cost is one division, two multiply-floors and one `Set` lookup
per candidate, with a reused `BlockPos.Mutable` so the mask allocates nothing
per position.

**The mask keys off eye POSITION and must never key off camera angle.** What
is geometrically visible through a hole is a function of where your eye is,
not which way it points — turn your head and the same blocks are still on the
far side of the same opening. Keying off yaw/pitch would pop real blocks in
and out every time a player turned around, a far worse artefact than the one
being fixed. So the projection legitimately changes as a player WALKS and
legitimately does not as they LOOK; if that asymmetry gets reported as a bug,
the bug is something else wrongly keyed to the mask — as 4a's light layer once
was (see PHASE-4 §4a).

Two deliberate non-changes:

- **`resolveDepth`'s 4e sample is NOT masked.** "Is the far side empty?" is a
  question about the destination, not about one viewer's angle, and masking it
  would let an oblique approach decide a sticky, per-projection depth from a
  handful of positions (or none). The 4f measurements below still hold.
- **The candidate slab is unchanged.** Tapering it into a pyramid would save
  perhaps a third of the (very cheap) mask evaluations and would couple the
  zone's geometry to a viewer distance it does not know.

Known trade-off at close range: `previewRadius` bounds the cone, so a player
standing 2-3 blocks from the frame can see the outer corners of the aperture
clip back to real blocks at the deepest layers (at 4+ blocks out, radius 2
covers the whole cone). Raising `DEFAULT_PREVIEW_RADIUS` to 3 or 4 in
`ImmersiveSettings` would fill those corners at the cost of more candidate
positions; the mask means the extra candidates cost evaluations, not packets.

## Verification Checklist

### Automated (Carpet bot + RCON + log grep)

- [x] Boot with `"immersive": true` on a test dimension
- [x] Spawn Carpet bot near the portal — log shows "immersive projection
  activated" (or equivalent)
- [x] Move bot away — log shows "immersive projection deactivated"
- [x] Break portal frame while bot is near — log shows cleanup
- [x] No errors in server log throughout lifecycle
- [x] Non-immersive portal: no projection logs at all
- [x] Unit tests pass

### Manual (human-in-game) — REQUIRED for this phase

> **NOT VERIFIED.** These need eyes in-game and were not performed. Headless
> verification proves the MECHANISM — packets sent, geometry correct, cleanup
> reliable on every path, real blocks never mutated — but it cannot prove the
> preview *looks* right. This is the outstanding sign-off for Phase 1.

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
