# Phase 0 — Instant Transition

> **Depends on:** Nothing (first phase)
> **Unlocks:** Phase 1 (Portal Preview), Phase 3 (Entity Pass-Through)
> **Status:** Complete

## Goal

Eliminate the visible pause when a player steps through a portal to a custom
> **Scope correction (2026-07-25, from in-game testing).** This phase removes
> the *stall* — the wait while the target world spins up and its arrival chunks
> generate. It does NOT remove the dimension-change **screen**. That screen is
> client-side: vanilla shows it on any cross-dimension teleport regardless of
> how ready the server is, and suppressing it needs a client mod, which this
> feature explicitly rules out. The tester saw the screen and reasonably read
> it as the phase not working. Read "instant" below as "no generation stall",
> not "no screen".

dimension. After this phase ships, entering an immersive portal feels instant —
the target world and its arrival chunks are pre-loaded before the player crosses.

This phase also introduces the `"immersive"` config field and its parsing into
`PortalDefinition`, which every subsequent phase depends on.

## Connection to Overall Plan

Phase 0 is the foundation. It delivers two things:

1. **Config schema** — the `"immersive": true` / `"immersive": { ... }` field
   that gates all immersive features. Every later phase reads this config.
2. **Pre-loading infrastructure** — Phase 1 (fake block projection) samples
   blocks from the target dimension. Those blocks can only be sampled if the
   target world is loaded and its arrival chunks are generated. Phase 0
   guarantees both.

Without Phase 0, Phase 1's projector would hit null worlds and ungenerated
chunks on first approach, degrading to a partial or empty preview.

## Implementation Checklist

### 0a. Config parsing (`ImmersiveSettings`)

- [x] Add `"immersive"` field to `DimensionConfig.Portal` as `JsonElement`
  (supports both `boolean` and object form — same pattern as `shape`)
- [x] Create `ImmersiveSettings` record in `com.customdimensions.config`:
  ```java
  public record ImmersiveSettings(
      boolean enabled,
      int previewDepth,     // default 8, max 16
      int previewRadius,    // default 2, max 4
      int refreshInterval,  // default 4 ticks (5 Hz), min 2
      int activationRange,  // default 24 blocks, max 64
      boolean audio,        // default true
      boolean entityPassthrough // default true
  ) {}
  ```
- [x] Parse in `DimensionConfig.Portal`: `boolean true` → all defaults; object
  → read each field with defaults for absent keys; absent/false → null
- [x] Wire through `DimensionConfig.toPortalDefinition()` → store as
  `PortalDefinition.getImmersive()` (nullable — null = not immersive)
- [x] The `ImmersiveSettings` is NOT serialised into `portal_links.json` zone
  records — it's a presentation feature, re-read from config every boot

**Files:** `DimensionConfig.java` (~30 lines), `PortalDefinition.java` (~20 lines),
new `ImmersiveSettings.java` (~40 lines)

### 0b. Proximity-triggered world pre-loading

- [x] In `ServerWorldMixin.onTick()`, after the existing zone-validity check
  and before the player-loop, add a proximity scan:
  ```java
  for (ServerPlayerEntity player : players) {
      for (PortalHelper.PortalZone zone : zones) {
          ImmersiveSettings imm = zone.definition.getImmersive();
          if (imm == null) continue;
          BlockPos centre = PortalShape.centreOf(zone.interior);
          double dist = player.getBlockPos().getSquaredDistance(centre);
          if (dist <= imm.activationRange() * imm.activationRange()) {
              RegistryKey<World> targetKey = zone.targetWorld;
              if (world.getServer().getWorld(targetKey) == null) {
                  DimensionManager.getInstance().requestWorldLoad(
                      targetKey.getValue().getPath());
              }
          }
      }
  }
  ```
- [x] This triggers `requestWorldLoad()` which queues for `END_SERVER_TICK` —
  the player typically approaches over several seconds, so the world is ready
  well before they enter the zone
- [x] Only runs for zones whose definition has `immersive != null`

**File:** `ServerWorldMixin.java` (~15 lines added to `onTick`)

### 0c. Arrival chunk pre-generation

- [x] Create `ImmersivePreloader` utility class in
  `com.customdimensions.immersive` package:
  ```java
  public final class ImmersivePreloader {
      // Chunk radius to pre-generate around arrival (2 = 5×5 = 25 chunks)
      private static final int PRELOAD_RADIUS = 2;
      // Track which portal zones have already been preloaded this session
      private static final Set<String> preloaded = ConcurrentHashMap.newKeySet();

      public static void preloadIfNeeded(ServerWorld targetWorld,
              PortalHelper.PortalZone zone, PortalDefinition def) {
          String key = zone.sourceWorld.getValue() + "|" +
              PortalShape.centreOf(zone.interior).toShortString();
          if (!preloaded.add(key)) return;

          int[] anchor = def.hasAnchor() ? def.getAnchorPos() : null;
          int cx, cz;
          if (anchor != null) {
              cx = anchor[0] >> 4;
              cz = anchor[2] >> 4;
          } else {
              BlockPos centre = PortalShape.centreOf(zone.interior);
              double scale = def.getScale();
              cx = (int) Math.round(centre.getX() * scale) >> 4;
              cz = (int) Math.round(centre.getZ() * scale) >> 4;
          }
          for (int dx = -PRELOAD_RADIUS; dx <= PRELOAD_RADIUS; dx++) {
              for (int dz = -PRELOAD_RADIUS; dz <= PRELOAD_RADIUS; dz++) {
                  targetWorld.getChunk(cx + dx, cz + dz);
              }
          }
      }

      public static void clear() { preloaded.clear(); }
  }
  ```
- [x] Call from the proximity scan in `ServerWorldMixin` — after confirming
  the target world is loaded:
  ```java
  ServerWorld targetWorld = world.getServer().getWorld(targetKey);
  if (targetWorld != null) {
      ImmersivePreloader.preloadIfNeeded(targetWorld, zone, zone.definition);
  }
  ```
- [x] Call `ImmersivePreloader.clear()` from `WorldLoaderMixin.onShutdown()`
  to reset session state

**Files:** New `immersive/ImmersivePreloader.java` (~50 lines),
`ServerWorldMixin.java` (~5 lines), `WorldLoaderMixin.java` (~1 line)

### 0d. Unit test for ImmersiveSettings parsing

- [x] Create `ImmersiveSettingsTest.java` in `src/test/java/com/customdimensions/config/`:
  - `testBooleanTrue` — `"immersive": true` → all defaults
  - `testBooleanFalse` — `"immersive": false` → null (not immersive)
  - `testAbsent` — no `"immersive"` key → null
  - `testObjectWithDefaults` — `"immersive": {}` → enabled, all defaults
  - `testObjectWithOverrides` — `"immersive": {"previewDepth": 4, "audio": false}`
    → depth=4, audio=false, rest defaults
  - `testClampedValues` — `"immersive": {"previewDepth": 100}` → clamped to 16

**File:** New `ImmersiveSettingsTest.java` (~80 lines)

### 0e. Restore-path re-stamp (found during implementation, not in the plan)

`ImmersiveSettings` being transient (0a) has a consequence the plan did not
spell out: `StoredPortalZone.toPortalZone()` builds restored zones from the
**Gson-deserialised** definition, so `zone.definition.getImmersive()` is
always null for anything restored from `portal_links.json`. Left alone,
every already-ignited immersive portal silently stops being immersive on
the next restart — which would have killed Phase 1's hero feature after the
first reboot, and contradicts PLAN.md's "boot-re-read … changes apply
without a wipe".

- [x] `MultiverseConfig.getImmersiveFor(RegistryKey<World> targetWorld)` —
  resolves live settings from the already-parsed portal definitions; never
  throws (a malformed `targetDimension` on an unrelated portal must not
  break the lookup for every other zone)
- [x] `PortalHelper.restoreZones()` re-stamps each pending zone's definition
  before the validity check. Stamping **null** is correct and required: it
  is how turning `"immersive"` off in config takes effect for existing zones
- [x] Regression test pins the transient contract: a `PortalDefinition`
  Gson round-trip emits no `immersive` key and comes back with
  `getImmersive() == null`, so nobody "fixes" this by un-transient-ing the
  field (which would break the downgrade-parseability rule)

**Files:** `MultiverseConfig.java` (~29 lines), `PortalHelper.java` (~12 lines)

## Verification Checklist

### Automated (Carpet bot + RCON)

- [x] Boot with a dimension that has `"immersive": true` in its portal config
- [x] `docker logs mc | grep "immersive"` — no errors, config parsed
- [x] Spawn a Carpet bot near a portal (within `activationRange`):
  ```bash
  docker exec -i mc rcon-cli 'player Bot spawn'
  docker exec -i mc rcon-cli 'tp Bot <portal_x> <portal_y> <portal_z>'
  ```
- [x] Check target world loaded:
  ```bash
  docker exec mc cat /data/logs/latest.log | grep "Created runtime world.*<target_dim>"
  ```
- [x] Verify arrival chunks generated. `forceload query` is NOT a generation
  oracle (it only reports forceload marking) — use a block probe instead,
  which answers "not loaded" for an ungenerated/unloaded column:
  ```bash
  # scaled arrival column; "not a block entity" = loaded, "That position is
  # not loaded" = not
  docker exec -i mc rcon-cli 'execute in <ns>:<dim> run data get block <ax> 0 <az>'
  ```
  Verified 2026-07-25: before approach the dimension did not exist
  ("Unknown dimension"); after approach the world existed and the arrival
  column was loaded. Note the OUTER ring of the 5×5 grid is generated but
  not retained — with no ticket or nearby player those chunks unload again
  within seconds. Phase 1 must therefore never assume a projected position's
  chunk is loaded (see its checklist).
- [x] Existing non-immersive portals: bot traversal unchanged (regression)
- [x] Unit tests pass: `./gradlew test` (178 tests, 0 failures)

### Manual (human-in-game)

> Not verified — these two require a human in-game and were not performed by
> the implementing agent. Everything above was verified headlessly.

- [ ] Walk toward an immersive portal — no visible change yet (Phase 0 is
  invisible to the player, it just makes the transition faster)
- [ ] Step through: transition noticeably faster than a non-immersive portal
  to the same dimension type

## Shipping Criteria

Phase 0 ships independently when:

1. The `ImmersiveSettings` parses from config without errors for both boolean
   and object forms
2. Non-immersive portals are completely unaffected (zero behavioural change)
3. Pre-loading triggers correctly at `activationRange` distance
4. Pre-loading is idempotent (no double-load on re-approach)
5. All existing portal tests pass unchanged
6. Unit test for `ImmersiveSettings` parsing passes
7. `./gradlew build` produces a valid remapped jar

## Research Notes

### Existing pre-warming in the codebase

`PortalIgnitionMixin.prewarmTarget()` (line 174) already calls
`DimensionManager.requestWorldLoad()` at ignition time. Phase 0 extends this
pattern to PROXIMITY rather than just ignition — the world may have been
idle-unloaded since ignition (`DimensionManager.unloadIdleDimensions` runs
every 1200 ticks / 1 minute, default 5-minute idle timeout).

### Chunk generation cost

`targetWorld.getChunk(cx, cz)` synchronously generates the chunk if it doesn't
exist. For a pre-generated world (production, post-Chunky) this is a cache hit.
For a fresh world, the 5×5 grid (25 chunks) takes ~1-3 seconds depending on
worldgen complexity. This is acceptable because it runs when the player
APPROACHES the portal, not when they step through.

### Thread safety

`requestWorldLoad()` uses `ConcurrentHashMap.newKeySet()` (line 97 of
`DimensionManager.java`). `ImmersivePreloader.preloaded` should use the same
pattern. The chunk generation itself runs on the server thread (called from
`ServerWorldMixin.onTick()` which is already on the server thread).

### Config parsing pattern

The `JsonElement` dual-form pattern (boolean vs object) is already used for:
- `DimensionConfig.Portal.shape` (string vs `{"type": "pattern", ...}`)
- `DimensionConfig.Portal.frameBlock` (string vs list vs `{"colorGroup": ...}`)
- `DimensionConfig.Anchor.pos` (array vs `"spawn"` string)

So `"immersive"` follows an established pattern. The parsing code in
`DimensionConfig.Portal` should handle: `JsonPrimitive(true)` → defaults,
`JsonPrimitive(false)` / absent → null, `JsonObject` → read fields.

### Gotchas (see PLAN.md § Agent Gotchas for full list)

- **Gotcha #3 (c2me threading):** `getChunk()` in `ImmersivePreloader` must
  only be called from the server thread (inside `ServerWorldMixin.onTick()`).
  c2me's multi-threaded chunk system behaves unpredictably from other threads.
- **Gotcha #4 (DH per-world state):** Pre-loading fires `ServerWorldEvents.LOAD`
  via `DimensionManager.requestWorldLoad()`. This is correct — DH expects it.
  Don't suppress or delay it.
- **Gotcha #9 (`portal_links.json` boundary):** `ImmersiveSettings` is NOT
  serialised into zone records. It's transient on `PortalDefinition`, re-read
  from config every boot.
- **Gotcha #11 (idle unloader):** A pre-loaded but unvisited world will be
  closed after 5 minutes. `ImmersivePreloader.preloaded` must be invalidated
  when this happens, or next approach won't re-trigger pre-loading.
- **Gotcha #13 (build system):** New files in `com.customdimensions.immersive`
  need no changes to `build.gradle`, `fabric.mod.json`, or the mixin config.

### Impact on existing code

This phase touches:
- `DimensionConfig.java` — add field + parsing (additive)
- `PortalDefinition.java` — add getter (additive)
- `ServerWorldMixin.java` — add proximity check in `onTick` (additive, inside
  the existing `if (!sourceZones.isEmpty())` block)
- `WorldLoaderMixin.java` — add one `clear()` call at shutdown (trivial)

No existing behaviour is modified. The proximity scan only runs for zones with
`immersive != null`, so non-immersive portals skip it entirely.
