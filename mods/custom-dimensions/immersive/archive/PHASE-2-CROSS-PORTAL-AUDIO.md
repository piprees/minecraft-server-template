# Phase 2 — Cross-Portal Audio

> **Depends on:** Phase 1 (reuses `ImmersiveProjector` tick loop and activation tracking)
> **Unlocks:** Phase 4 (Polish)
> **Independent of:** Phase 3 (Entity Pass-Through)
> **Status:** Complete
> firing at the designed cadence, biome loop correctly silent for an
> overworld-family target). The weather relay planned in §2c was found to be
> permanently dead code and removed — see that section.

## Goal

Ambient sounds from the destination dimension leak through immersive portals.
Players hear the other side before they step through — biome ambience and
nearby mob sounds (via the biome's mood sound) bleed through the portal
plane. A weather relay was planned but turned out to be impossible in
vanilla 1.21.1 — see §2c.

## Connection to Overall Plan

Phase 2 adds the auditory dimension to the visual preview from Phase 1. It
reuses Phase 1's `ImmersiveProjector` tick loop and per-player activation
tracking — no new activation/deactivation logic needed.

Audio is gated by `ImmersiveSettings.audio()` (default true, set in Phase 0's
config parsing). When `"audio": false`, this phase is a no-op.

## Implementation Checklist

### 2a. Biome ambient sound relay

- [x] In `ImmersiveProjector.tick()`, add an audio pass after the block
  projection pass:
  ```java
  // Every 40 ticks (2 seconds) per active portal per player
  if (tick % 40 != 0) return;

  ImmersiveSettings imm = zone.definition.getImmersive();
  if (imm == null || !imm.audio()) return;

  ServerWorld targetWorld = world.getServer().getWorld(zone.targetWorld);
  if (targetWorld == null) return;

  // Arrival position: anchor or scaled centre
  BlockPos arrivalPos = resolveArrivalPos(zone);

  // Biome ambient loop
  targetWorld.getBiome(arrivalPos).value().getLoopSound().ifPresent(sound -> {
      world.playSound(null, portalCentre, sound.value(),
          SoundCategory.AMBIENT, 0.3f, 1.0f);
  });
  ```
- [x] The `Biome.getLoopSound()` returns an `Optional<RegistryEntry<SoundEvent>>`
  — nether biomes have ambient loops (`ambient.basalt_deltas.loop`, etc.),
  many overworld biomes do not (they use the generic ambient system instead).
  This means nether/end portals get ambient sound; many overworld portals are
  silent — which is actually correct (you hear the exotic, not the mundane).
- [x] Volume 0.3 at the portal position: vanilla distance attenuation handles
  the rest (falls off by 1/distance, inaudible past ~16 blocks)
- [x] Import: `net.minecraft.sound.SoundCategory`

**Files:** Modified `immersive/ImmersiveProjector.java` (~20 lines)

### 2b. Nearby mob sound relay

- [x] ~~Every 60 ticks (3 seconds), scan for living entities within 16 blocks
  of the arrival position in the target world~~ — not implemented; went
  straight to the "Revised approach" below per this doc's own recommendation
  (mood sound instead of a per-mob scan):
  ```java
  if (tick % 60 != 0) return;

  List<net.minecraft.entity.LivingEntity> nearbyMobs =
      targetWorld.getEntitiesByClass(
          net.minecraft.entity.LivingEntity.class,
          net.minecraft.util.math.Box.of(arrivalPos, 16, 16, 16),
          e -> !e.isSilent() && !(e instanceof ServerPlayerEntity));

  if (!nearbyMobs.isEmpty()) {
      // Pick one mob at random to relay (avoid sound spam)
      LivingEntity mob = nearbyMobs.get(world.random.nextInt(nearbyMobs.size()));
      SoundEvent ambient = mob.getType().getAmbientSound();
      // getAmbientSound() may be null (not all mobs have one)
      // Actually, this is on EntityType — need to get it from the entity instance
  }
  ```
- [x] **Important:** `LivingEntity.getAmbientSound()` is `protected` — not
  accessible from outside the class hierarchy. Alternative approaches:
  - Use `EntityType` to infer a sound (e.g. `entity.type.lootTableId` → known
    mob → known sound) — fragile, not worth it
  - **Recommended:** Skip individual mob sounds. Instead, use the biome's
    `MoodSound` (`Biome.getMoodSound()`) which vanilla already plays in dark
    areas (cave ambience). Play it at the portal position at 0.2 volume.
  - This is simpler, less jarring, and avoids the protected-method issue — the
    recommended path is what was built.

**Revised approach (implemented):**
```java
targetWorld.getBiome(arrivalPos).value().getMoodSound().ifPresent(mood -> {
    if (world.random.nextFloat() < 0.15f) { // ~1 in 7 cycles
        world.playSound(null, portalCentre, mood.getSound().value(),
            SoundCategory.AMBIENT, 0.2f, 1.0f);
    }
});
```

**Files:** Same `ImmersiveProjector.java` (~10 lines)

### 2c. Weather sound relay — REMOVED, per-dimension weather does not exist

- [x] ~~If the target dimension is raining and the source is not, play rain
  ambience at the portal~~ — **built, then deleted.** It shipped initially
  (rain every 80 ticks, thunder every 200, exactly as drafted below) but a
  live soak found `immersive: rain relay` never fired even with rain forced
  in the target and clear in the source. Root cause, verified against the
  decompiled 1.21.1 sources (not assumed): every non-overworld `ServerWorld`
  — vanilla's own and every dimension this mod creates — is constructed over
  an `UnmodifiableLevelProperties` that wraps `saveProperties
  .getMainWorldProperties()`, the SAME `ServerWorldProperties` instance the
  overworld itself was built with:
  - `MinecraftServer.createWorlds` (decompiled bytecode): builds the
    overworld directly from `SaveProperties.getMainWorldProperties()`, then
    for every other dimension does `new UnmodifiableLevelProperties(
    saveProperties, saveProperties.getMainWorldProperties())` — the exact
    same properties object, just read-only wrapped.
  - `UnmodifiableLevelProperties.isRaining()`/`isThundering()` (bytecode):
    both are one-line delegates straight to the wrapped
    `ServerWorldProperties` field — no independent state at all.
  - This mod's own `DimensionManager.getOrCreateDimension()` and
    `getOrCreateDimensionDirect()` (`src/main/java/com/customdimensions/
    dimension/DimensionManager.java`) construct every runtime dimension the
    identical way: `new UnmodifiableLevelProperties(saveProperties,
    saveProperties.getMainWorldProperties())`.
  - Net effect: `targetWorld.isRaining()`/`isThundering()` and
    `world.isRaining()`/`isThundering()` always read one shared, save-wide
    flag, for ANY two dimensions in this server, custom or vanilla. The
    guard `targetWorld.isRaining() && !world.isRaining()` can never be true
    — 2c was unreachable code from the moment it shipped. (Separately,
    `/weather rain` doesn't even target `execute in <dim>` — it resolves to
    `getOverworld()` regardless — so the manual reproduction step below was
    never going to work either, but that's a secondary issue next to the
    shared-properties one.)
  - Removed entirely rather than left "just in case": the biome ambience is
    the feature that carries this phase, and shipping code that silently
    never runs is worse than not having it. `ImmersiveProjector.tickAudio`
    now carries a permanent doc comment recording this so nobody re-adds a
    weather relay without first giving dimensions independent weather state
    (which nothing in this codebase, or vanilla itself, currently provides).
  - Draft that was deleted (kept here for history only — do not re-add
    without addressing the above):
    ```java
    if (targetWorld.isRaining() && !world.isRaining() && tick % 80 == 0) {
        world.playSound(null, portalCentre,
            net.minecraft.sound.SoundEvents.WEATHER_RAIN,
            SoundCategory.WEATHER, 0.15f, 1.0f);
    }
    if (targetWorld.isThundering() && !world.isThundering() && tick % 200 == 0) {
        world.playSound(null, portalCentre,
            net.minecraft.sound.SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
            SoundCategory.WEATHER, 0.1f, 0.8f + world.random.nextFloat() * 0.4f);
    }
    ```

**Files:** `ImmersiveProjector.java` — net change after removal: 0 lines (the
weather blocks and the now-unused `SoundEvents` import were deleted).

### 2d. Encapsulate audio logic

- [x] Extract audio into a static helper method for readability:
  ```java
  // In ImmersiveProjector or a new ImmersiveAudio helper
  static void tickAudio(ServerWorld world, ServerWorld targetWorld,
          BlockPos portalCentre, BlockPos arrivalPos, long tick) {
      // biome loop (every 40 ticks)
      // mood sound (every 60 ticks, 15% chance)
      // (weather relay planned here — removed, see 2c: dimensions share
      // one weather flag in vanilla 1.21.1, so it could never fire)
  }
  ```
- [x] Call from `ImmersiveProjector.tick()` for each active immersive zone

**Files:** Modified `immersive/ImmersiveProjector.java` (~45 lines total for audio)

## Verification Checklist

Field-verified live by team-lead (DEBUG enabled for the `customdimensions`
logger): `immersive: mood sound` fired 3 times in 60s (matches the designed
20 windows × 15%), `immersive: biome loop sound` correctly silent for an
overworld-family target. That same soak is what caught §2c's weather relay
never firing, leading to its removal. `./gradlew build`/`test` passed
throughout (198 tests, 101 classes, refmap present) and the jar was
inspected for intermediary names. Remaining boxes below are the ones that
still need a dedicated pass (nether-loop-sound and non-immersive-portal
checks specifically) rather than ones that were incidentally covered.

### Automated (RCON + log grep)

- [x] Boot with `"immersive": true` — confirmed live (mood sound + biome
  loop firing on cadence, no errors)
- [x] Carpet bot / live player near the portal: no errors in log
- [x] `"audio": false` portal: grep logs for sound-related output — none
- [ ] Non-immersive portal: no audio relay

### Manual (human-in-game) — REQUIRED

> Not verified — needs a human listening in-game. Headless
> verification proves emission (DEBUG lines, correct cadence) and that
> `"audio": false` silences it, but not how it SOUNDS.

- [ ] **Nether-type portal:** stand near it, hear basalt deltas / crimson forest
  ambient loop bleeding through (distinctive, unmistakable)
- [x] **Overworld portal from a nether dimension:** hear... mostly nothing
  (overworld biomes rarely have loop sounds). Mood sounds may occasionally
  trigger if the arrival is in a dark area. This is correct behaviour —
  confirmed live (biome loop silent, mood sound firing at ~15%/window).
- [ ] **Walk away:** sounds stop (vanilla distance attenuation)
- [ ] **Volume:** sounds should be subtle background, not loud or annoying.
  If they're too loud, reduce the volume constants.

(The former "Weather" verification step is removed along with §2c — there is
no per-dimension weather to verify against.)

## Shipping Criteria

Phase 2 ships independently when:

1. Biome ambient sounds play at immersive portal positions
2. Volume is subtle (0.2–0.3) and distance-attenuated
3. `"audio": false` completely disables all cross-portal sound
4. No sound relay for non-immersive portals
5. No errors or performance impact from the audio tick
6. All existing tests pass unchanged
7. `./gradlew build` produces a valid remapped jar
8. No dead code: every code path in `tickAudio` must be reachable and was
   observed to fire live (the reason §2c was cut rather than shipped inert)

## Research Notes

### Vanilla sound API

`ServerWorld.playSound(PlayerEntity excludePlayer, BlockPos pos, SoundEvent sound,
SoundCategory category, float volume, float pitch)` — the standard server-side
sound emission. When `excludePlayer` is null, all nearby players hear it. The
server sends a `PlaySoundS2CPacket` to each player within hearing range
(default 16 blocks × volume factor).

Key sound events (1.21.1 Yarn names):
- Biome sounds: accessed via `Biome.getLoopSound()` and `Biome.getMoodSound()`
- `SoundEvents.WEATHER_RAIN` / `SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER`
  exist and resolve fine, but are unused — see §2c: there is no per-dimension
  weather state to gate them on in vanilla 1.21.1.

### Biome sound availability

| Biome type | Loop sound? | Mood sound? |
|---|---|---|
| Nether biomes (basalt deltas, crimson forest, etc.) | Yes (distinctive) | Yes |
| Deep dark / sculk areas | No | Yes (sculk) |
| Lush caves | No | Yes |
| Most overworld surface biomes | No | Yes (cave mood) |
| End biomes | No | No |

This means the audio feature is most impactful for portals to nether-themed
dimensions — which are exactly the dimensions where "hearing the other side"
is most atmospheric. Overworld-to-overworld portals will be mostly silent,
which is fine.

### Performance impact

One `playSound` call generates one `PlaySoundS2CPacket` per nearby player.
At the cadences above (biome loop every 2s, mood every 3s with 15% chance):
~1 packet every 2 seconds per active portal. Negligible.

### Gotchas (see PLAN.md § Agent Gotchas for full list)

- **Gotcha #7 (GameEventSuppressionMixin):** The target world may have zero
  players (the approaching player is still in the source world). Our
  `GameEventSuppressionMixin` silently drops ALL game events in managed worlds
  with no players. Use `world.playSound()` (sends network packets directly)
  for all audio — NEVER game events. `playSound` bypasses this suppression.
- **Gotcha #11 (idle unloader):** The target world can be unloaded mid-session.
  Always null-check `world.getServer().getWorld(zone.targetWorld)` before
  sampling biome data. A null target world means the audio pass is a no-op
  that tick — the world will be re-loaded on next player approach.

### No new files needed

All audio logic fits inside `ImmersiveProjector` — a `tickAudio()` static
helper plus one call site in `tick()`, net ~50 lines after the weather
relay was cut. No new classes, no new mixins, no new packets. This is the
smallest phase by implementation size.

### Dependencies from Phase 1

Phase 2 uses:
- `ImmersiveProjector.tick()` — the per-world tick entry point
- The per-zone `anyoneNear` flag Phase 1 already computes — audio is gated
  on it directly rather than reimplementing its own activation tracking
- The per-zone `mapping`/`arrivalY` Phase 1 already resolves for the block
  projection (via `mappingFor()` and `arrivalSurfaceY()`) — audio reuses
  those exact values (`new BlockPos(mapping.arrivalX(), arrivalY,
  mapping.arrivalZ())`) instead of a second, potentially divergent
  arrival-position calculation. There is no separate `resolveArrivalPos()`
  helper — that would have been the second copy this avoids.

If Phase 2 were built before Phase 1, it would need its own activation
tracking and arrival-position resolution — duplicating work. Building it
after Phase 1 means it's purely additive.
