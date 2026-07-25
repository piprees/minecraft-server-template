# Phase 4 — Polish and Optimisation

> **Depends on:** Phases 1, 2, and 3 (refines output from all three)
> **Unlocks:** Nothing (final MVP phase)
> **Status:** Complete

## Goal

Refine the immersive portal experience so it feels finished, not prototyped.
No new features — this phase improves the visual quality, performance
characteristics, and edge case handling of Phases 1–3.

## Connection to Overall Plan

Phase 4 is the quality pass. It addresses the known limitations documented in
each earlier phase and adds the small touches that make the difference between
"technically working" and "just works." Each sub-task is independent and can
ship individually.

## Implementation Checklist

### 4a. Lighting approximation for projected blocks

**Problem:** Projected blocks inherit the source dimension's lighting, which
may be completely wrong (a sunny overworld projecting into a dark nether cave
shows cave blocks in full daylight).

- [x] When sending the initial full projection, place invisible LIGHT blocks
  (level 15) at the portal plane boundary — the first layer of projected blocks:
  ```java
  // In PlayerProjectionState.sendFull(), for each portal-plane-adjacent position:
  if (isFirstDepthLayer(pos, zone)) {
      player.networkHandler.sendPacket(
          new BlockUpdateS2CPacket(pos, Blocks.LIGHT.getDefaultState()));
  }
  ```
- [x] These LIGHT blocks are fake (only sent to the client, not placed in the
  world). They illuminate the projected blocks behind them, making the preview
  visible even when the source-side lighting is dark.
- [x] On cleanup, these positions are restored like any other projected position.
- [x] **Trade-off:** This makes ALL projected blocks fully lit, regardless of the
  target dimension's actual lighting. It's better than darkness but not accurate.
  Acceptable for MVP — accurate lighting would require client-side rendering.

**File:** Modified `immersive/PlayerProjectionState.java` (~15 lines)

### 4b. Portal edge particles

**Problem:** The boundary between real blocks and projected blocks is abrupt.
Without a visual edge, it's hard to tell where the "real world" ends and the
"preview" begins.

- [x] Spawn dimension-coloured `DustParticleEffect` particles along the portal
  frame edges every tick, using the portal's configured `color`:
  ```java
  // In ImmersiveProjector.tick(), for active immersive zones:
  Direction[] planeDirs = PortalHelper.planeDirections(zone.axis);
  for (BlockPos p : zone.interior) {
      for (Direction dir : planeDirs) {
          BlockPos edge = p.offset(dir);
          if (!zone.interior.contains(edge)) {
              // This is a frame-adjacent position — spawn edge particles
              world.spawnParticles(
                  new DustParticleEffect(toDustColor(parseColor(def.getColor())), 1.0f),
                  edge.getX() + 0.5, edge.getY() + 0.5, edge.getZ() + 0.5,
                  1, 0.1, 0.1, 0.1, 0.0);
          }
      }
  }
  ```
- [x] These particles sit on the FRAME blocks, creating a coloured border that
  frames the preview. They complement the existing zone interior particles
  (which are already coloured per the portal config).
- [x] Only spawn when the immersive preview is active for at least one player
  (don't waste particles when nobody can see the projection).

**File:** Modified `immersive/ImmersiveProjector.java` (~15 lines)

#### Intensity correction (human testing, 2026-07-25)

Reported in-game: *"The particle effects are very strong too, we might want to
tone that back a bit."* The sketch above spawns **every tick**, on top of
`PortalHelper.spawnParticles`' pre-existing 2-per-interior-block-per-tick. A
dust particle lives ~20-30 ticks, so an every-tick spawn keeps 20+ alive per
frame block permanently — a cloud pouring out of the portal, not a border
around it.

- [x] `EDGE_PARTICLE_INTERVAL = 10` ticks (twice a second). ~2 live particles
  per frame block instead of ~20: a tenth of the particles, and a tenth of the
  `ParticleS2CPacket`s (one per ring block per emission). The border still
  reads as continuous because the cadence is well inside a particle's lifetime
- [x] The cadence gate is the first statement in `spawnEdgeParticles`, so the
  nine ticks in ten that emit nothing also build no ring set
- [x] Emission is **phase-staggered per zone** (`particlePhase`, derived from
  the zone centre) so a hub with several immersive portals spreads its packets
  across the interval rather than spiking them onto one tick
- [x] The DEBUG heartbeat is phased with the emission, and both intervals
  divide `PARTICLE_LOG_INTERVAL` exactly — otherwise the heartbeat would never
  land on an emitting tick for a staggered zone and 4b would look dead in the
  log while working perfectly
- [x] No new config keys: this is taste, and a knob for it is one more thing to
  get wrong. The interior particle spawn in `PortalHelper` is untouched —
  pre-existing behaviour, not an immersive concern

### 4c. Smart refresh throttling

**Problem:** The `refreshInterval` is constant regardless of whether anything
has changed. A stationary player wastes packet budget on identical delta updates.

- [x] Track player position at last refresh. Skip the delta update if the
  player hasn't moved more than 0.5 blocks since last refresh AND no blocks
  changed in the target dimension:
  ```java
  // In PlayerProjectionState:
  private Vec3d lastRefreshPos;

  public boolean needsRefresh(ServerPlayerEntity player) {
      if (lastRefreshPos == null) return true;
      return player.getPos().squaredDistanceTo(lastRefreshPos) > 0.25; // 0.5^2
  }
  ```
- [x] When the player IS moving, use the configured `refreshInterval`. When
  stationary, use `refreshInterval * 4` (reduced packet rate for static view).
- [x] This saves ~75% of packets for AFK players near portals — relevant for
  servers with portals in hub/spawn areas.
- [x] **Measures the EYE, not the feet** (changed with Phase 1h's sightline
  mask). The mask is computed from the eye position, so this throttle is also
  the mask's update rate; stretching the interval is only safe for a viewer
  whose sightlines have not moved. A moving player is back on the configured
  interval immediately, so the view cone follows them and the positions it
  vacates are restored on the same pass.

**File:** Modified `immersive/PlayerProjectionState.java` (~10 lines)

### 4d. Gateway portal particle preview

**Problem:** Gateway portals (`"shape": "end_gateway"`) are excluded from the
block projection because they're single-block portals with no frame plane to
project behind. They get no immersive treatment at all.

- [x] For immersive gateway portals, spawn a tight cloud of particles that
  pulse between the portal's colour and the target biome's fog colour:
  ```java
  if (PortalShape.END_GATEWAY.equals(def.getShape()) && imm != null) {
      ServerWorld targetWorld = world.getServer().getWorld(zone.targetWorld);
      if (targetWorld != null) {
          // Sample the arrival biome's fog color
          BlockPos arrivalPos = resolveArrivalPos(zone);
          // Spawn 3-5 particles in a 1-block radius around the gateway
          world.spawnParticles(
              new DustParticleEffect(toDustColor(parseColor(def.getColor())), 1.5f),
              gatewayPos.getX() + 0.5, gatewayPos.getY() + 0.5, gatewayPos.getZ() + 0.5,
              4, 0.3, 0.3, 0.3, 0.02);
      }
  }
  ```
- [x] This replaces the existing particle spawn for gateway zones when
  immersive is enabled, giving them a denser, more atmospheric effect.

**File:** Modified `immersive/ImmersiveProjector.java` (~15 lines)

#### Intensity correction (human testing, 2026-07-25)

Same defect as 4b: 4 particles **every tick** is ~80 alive at all times.

- [x] `GATEWAY_PARTICLE_INTERVAL = 5` ticks with `GATEWAY_PARTICLE_COUNT` cut
  from 4 to 2 — net about a tenth of what it was, matching 4b
- [x] Emits twice as often as the frame border but at half the count, because
  for a gateway zone this cloud is the ENTIRE immersive treatment: no frame,
  no projection, nothing else to look at. "Denser" is relative to the interior
  particles it replaces, not to the original firehose
- [x] 5 divides `GATEWAY_PULSE_PERIOD` (40), so both halves of the scale pulse
  are still sampled four times per cycle — the breathing survives the cut
- [x] Phase-staggered and heartbeat-phased exactly like 4b

### 4e. Projection depth auto-scaling

**Problem:** A portal to a void dimension projects... void (air blocks). The
projection looks empty and confusing. Similarly, a portal to a dimension with
a floor at y=200 projects underground blocks when the arrival is at surface.

- [x] After computing the projection volume, scan the first depth layer. If
  >80% of blocks are air, reduce depth to 2 (just show the boundary):
  ```java
  int airCount = 0;
  for (BlockPos pos : firstLayer) {
      if (targetWorld.getBlockState(toTarget(pos)).isAir()) airCount++;
  }
  if (airCount > firstLayer.size() * 0.8) {
      effectiveDepth = Math.min(2, settings.previewDepth());
  }
  ```
- [x] This is a one-time check at activation (stored in `PlayerProjectionState`).
  Subsequent refreshes use the effective depth.

**File:** Modified `immersive/PlayerProjectionState.java` (~10 lines)

### 4f. Arrival resolution (found by live testing, not in the plan)

4e's air/solid counting exposed a **Phase 1 correctness bug** that had been
invisible until something started reporting on the destination's contents.

The preview resolved its arrival Y from the heightmap. The player path does
not: once an arrival portal exists it lands the player AT that portal
(`landY = existing.getY()`) and only consults the surface when it has to build
a new one. And building the arrival portal *changes the heightmap it was
derived from* — `createTargetPortal` places solid frame blocks above the top
interior row, so `MOTION_BLOCKING_NO_LEAVES` at that column afterwards reports
the top of our own frame rather than the ground.

Measured (2026-07-25, overworld → `adventure:the_blossom_gardens`, scale 1):
ground at y=62, arrival portal interior y=63–65 with frame at 62 and 66. A
traversing bot landed at **y=63**; the heightmap answered **67**. So the
preview was built ~4 blocks above the destination and showed the empty sky
over it — which 4e then correctly diagnosed as "all air" and shrank to depth
2. Every portal in the game would have degraded that way after its first use,
silently.

- [x] `ArrivalResolver` — one shared answer to "where is the other side",
  used by both `ImmersiveProjector` and `EntityPassthrough` (three private
  copies is how this drifts again)
- [x] Registered arrival portal first, heightmap only as the fallback — the
  same order the player path uses, with the same `(5, 16)` search box and the
  same `(x, z, y)` scan order, so it resolves the portal the player path would
- [x] Lookup is a pure in-memory read of `PortalHelper`'s registered targets —
  no block states, no chunk access. `findExistingPortal` is deliberately NOT
  used despite being the player path's tool: it scans real blocks and would
  touch unloaded chunks (Rule 1)
- [x] Stale registrations are re-checked against the world only when the chunk
  happens to be loaded — an unloaded chunk is not evidence against the registry

Result, same fixture, before and after:

| | air | solid | decision | projected |
|---|---|---|---|---|
| before | 42 | 0 | SHALLOW | 84 |
| after | 30 | 12 | FULL | 336 |

The 12 solid is exactly the two `previewRadius` padding rows that map below
the arrival surface (2 rows × 6 wide) — the geometry behaving as designed.

## Verification Checklist

### Automated

- [x] Unit tests for smart refresh logic (mock player position, verify
  refresh skip when stationary)
- [x] No errors in server log for any Phase 4 feature
- [x] All existing tests pass unchanged

### Manual (human-in-game)

> Not verified — needs eyes in-game. Headless verification proves the
> mechanism (LIGHT states routed through `lastSent` and restored on cleanup,
> depth decisions logged, real blocks provably untouched), not how any of it
> looks.

- [ ] **4a (lighting):** projected cave blocks are visible, not black
- [ ] **4b (edge particles):** coloured particle border visible around the
  portal frame, consistent with portal's configured colour
- [ ] **4c (throttling):** stand still near portal — no visible difference
  (but log shows reduced refresh rate if debug logging enabled)
- [ ] **4d (gateway particles):** gateway portal has a dense, atmospheric
  particle cloud when immersive is enabled
- [ ] **4e (void handling):** portal to a void dimension shows a shallow
  preview (2 blocks) rather than a confusing deep void

## Shipping Criteria

Each sub-task can ship independently. The phase ships as a whole when:

1. All five sub-tasks are implemented and verified
2. No regression in Phase 1/2/3 behaviour
3. All existing portal tests pass unchanged
4. `./gradlew build` produces a valid remapped jar

## Research Notes

### LIGHT blocks in 1.21.1

`Blocks.LIGHT` is a vanilla block (added in 1.17) that emits a configurable
light level (0-15) and is invisible. Its default state is level 15. Sending it
as a fake block via `BlockUpdateS2CPacket` makes the client render it as an
invisible light source — perfect for illuminating the projection volume.

`Blocks.LIGHT.getDefaultState()` includes the `level` property at 15. No
property manipulation needed.

### DustParticleEffect colour parameter

`DustParticleEffect(Vector3f color, float scale)` — the colour is RGB in
0.0-1.0 range. `PortalHelper.toDustColor(int color)` already converts hex
int → Vector3f. Reuse that utility (it's package-private in PortalHelper —
may need to be made public or duplicated).

### Existing particle spawning

`PortalHelper.spawnParticles(world, zone)` (line 438) already spawns particles
for source-zone interiors. `spawnTargetPortalParticles(world)` (line 448) spawns
for target-side portal blocks. Both use `world.spawnParticles()` with the
portal's colour/particle config. Phase 4b adds EDGE particles (on frame blocks,
not interior blocks) — complementary, not overlapping.

### Performance of particle spawning

`world.spawnParticles()` sends one `ParticleS2CPacket` per call. For a 2×3
portal with 10 frame-edge positions, that's 10 packets per tick — comparable
to existing interior particle spawning. Acceptable.

### Smart refresh: player position tracking

`ServerPlayerEntity.getPos()` returns the entity's current position as `Vec3d`.
`Vec3d.squaredDistanceTo()` avoids the sqrt cost of distance calculation. A
threshold of 0.5 blocks (0.25 squared distance) means: if the player moved
less than half a block since the last refresh, skip the delta update. This is
generous enough to catch walking players but filters out micro-jitter from
standing still.

### Gotchas (see PLAN.md § Agent Gotchas for full list)

- **Gotcha #2 (Supplementaries):** Phase 4a sends fake LIGHT blocks as
  `BlockUpdateS2CPacket` — these are client-only, never placed in the world,
  so the piston crash is not a concern. But be aware of the pattern.
- **Gotcha #8 (cleanup restores real blocks):** Phase 4a's LIGHT block positions
  must be included in `PlayerProjectionState.lastSent` and cleaned up the same
  way as projected blocks — `world.getBlockState(pos)` returns the real state.
- **Gotcha #12 (tick ordering):** Phase 4b's edge particles should be part of
  the `ImmersiveProjector.tick()` pass (step 8), not a separate injection point.
  Keep all immersive logic in one place.

### What Phase 4 does NOT include

- **Biome-dependent block colours:** Water colour, foliage colour, and grass
  colour are computed client-side from the biome the client thinks it's in
  (the source biome, not the target). Fixing this would require sending fake
  biome data, which risks breaking other mods that depend on biome consistency.
  Out of scope.
- **Entity rendering through portals:** Showing mobs/players on the other side
  would require sending entity-spawn packets for entities in the target
  dimension, positioned in the source dimension. This is Phase 5 territory
  (client mod required for proper handling).
- **Animated blocks:** Blocks with animation (water flow, campfire smoke,
  torch flame) render with their animation in the source dimension. The
  animation itself is client-side and correct — it just plays at the source
  position, which happens to show a fake block from the target dimension.
  This looks fine.
