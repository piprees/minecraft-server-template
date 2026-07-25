# Phase 2 — Cross-Portal Audio

> **Depends on:** Phase 1 (reuses `ImmersiveProjector` tick loop and activation tracking)
> **Unlocks:** Phase 4 (Polish)
> **Independent of:** Phase 3 (Entity Pass-Through)
> **Status:** Not started

## Goal

Ambient sounds from the destination dimension leak through immersive portals.
Players hear the other side before they step through — biome ambience, nearby
mob sounds, and weather effects bleed through the portal plane.

## Connection to Overall Plan

Phase 2 adds the auditory dimension to the visual preview from Phase 1. It
reuses Phase 1's `ImmersiveProjector` tick loop and per-player activation
tracking — no new activation/deactivation logic needed.

Audio is gated by `ImmersiveSettings.audio()` (default true, set in Phase 0's
config parsing). When `"audio": false`, this phase is a no-op.

## Implementation Checklist

### 2a. Biome ambient sound relay

- [ ] In `ImmersiveProjector.tick()`, add an audio pass after the block
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
- [ ] The `Biome.getLoopSound()` returns an `Optional<RegistryEntry<SoundEvent>>`
  — nether biomes have ambient loops (`ambient.basalt_deltas.loop`, etc.),
  many overworld biomes do not (they use the generic ambient system instead).
  This means nether/end portals get ambient sound; many overworld portals are
  silent — which is actually correct (you hear the exotic, not the mundane).
- [ ] Volume 0.3 at the portal position: vanilla distance attenuation handles
  the rest (falls off by 1/distance, inaudible past ~16 blocks)
- [ ] Import: `net.minecraft.sound.SoundCategory`

**Files:** Modified `immersive/ImmersiveProjector.java` (~20 lines)

### 2b. Nearby mob sound relay

- [ ] Every 60 ticks (3 seconds), scan for living entities within 16 blocks
  of the arrival position in the target world:
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
- [ ] **Important:** `LivingEntity.getAmbientSound()` is `protected` — not
  accessible from outside the class hierarchy. Alternative approaches:
  - Use `EntityType` to infer a sound (e.g. `entity.type.lootTableId` → known
    mob → known sound) — fragile, not worth it
  - **Recommended:** Skip individual mob sounds. Instead, use the biome's
    `MoodSound` (`Biome.getMoodSound()`) which vanilla already plays in dark
    areas (cave ambience). Play it at the portal position at 0.2 volume.
  - This is simpler, less jarring, and avoids the protected-method issue

**Revised approach:**
```java
targetWorld.getBiome(arrivalPos).value().getMoodSound().ifPresent(mood -> {
    if (world.random.nextFloat() < 0.15f) { // ~1 in 7 cycles
        world.playSound(null, portalCentre, mood.getSound().value(),
            SoundCategory.AMBIENT, 0.2f, 1.0f);
    }
});
```

**Files:** Same `ImmersiveProjector.java` (~10 lines)

### 2c. Weather sound relay

- [ ] If the target dimension is raining and the source is not, play rain
  ambience at the portal:
  ```java
  if (targetWorld.isRaining() && !world.isRaining() && tick % 80 == 0) {
      world.playSound(null, portalCentre,
          net.minecraft.sound.SoundEvents.WEATHER_RAIN,
          SoundCategory.WEATHER, 0.15f, 1.0f);
  }
  ```
- [ ] Similarly for thunder:
  ```java
  if (targetWorld.isThundering() && !world.isThundering() && tick % 200 == 0) {
      world.playSound(null, portalCentre,
          net.minecraft.sound.SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
          SoundCategory.WEATHER, 0.1f, 0.8f + world.random.nextFloat() * 0.4f);
  }
  ```
- [ ] The low volume + infrequent interval prevents the portal from becoming
  a noise machine. The pitch variation on thunder makes it sound distant.

**Files:** Same `ImmersiveProjector.java` (~15 lines)

### 2d. Encapsulate audio logic

- [ ] Extract audio into a static helper method for readability:
  ```java
  // In ImmersiveProjector or a new ImmersiveAudio helper
  static void tickAudio(ServerWorld world, ServerWorld targetWorld,
          BlockPos portalCentre, BlockPos arrivalPos, long tick) {
      // biome loop (every 40 ticks)
      // mood sound (every 60 ticks, 15% chance)
      // weather (every 80/200 ticks)
  }
  ```
- [ ] Call from `ImmersiveProjector.tick()` for each active immersive zone

**Files:** Modified `immersive/ImmersiveProjector.java` (~45 lines total for audio)

## Verification Checklist

### Automated (RCON + log grep)

- [ ] Boot with `"immersive": true` on a nether-type dimension
- [ ] Carpet bot near the portal: no errors in log
- [ ] `"audio": false` portal: grep logs for sound-related output — none
- [ ] Non-immersive portal: no audio relay

### Manual (human-in-game) — REQUIRED

- [ ] **Nether-type portal:** stand near it, hear basalt deltas / crimson forest
  ambient loop bleeding through (distinctive, unmistakable)
- [ ] **Overworld portal from a nether dimension:** hear... mostly nothing
  (overworld biomes rarely have loop sounds). Mood sounds may occasionally
  trigger if the arrival is in a dark area. This is correct behaviour.
- [ ] **Weather:** create a portal to a dimension where it's raining (use
  `/weather rain` via RCON in the target dimension). Stand near the source
  portal — hear faint rain.
- [ ] **Walk away:** sounds stop (vanilla distance attenuation)
- [ ] **Volume:** sounds should be subtle background, not loud or annoying.
  If they're too loud, reduce the volume constants.

## Shipping Criteria

Phase 2 ships independently when:

1. Biome ambient sounds play at immersive portal positions
2. Volume is subtle (0.15–0.3) and distance-attenuated
3. `"audio": false` completely disables all cross-portal sound
4. No sound relay for non-immersive portals
5. No errors or performance impact from the audio tick
6. All existing tests pass unchanged
7. `./gradlew build` produces a valid remapped jar

## Research Notes

### Vanilla sound API

`ServerWorld.playSound(PlayerEntity excludePlayer, BlockPos pos, SoundEvent sound,
SoundCategory category, float volume, float pitch)` — the standard server-side
sound emission. When `excludePlayer` is null, all nearby players hear it. The
server sends a `PlaySoundS2CPacket` to each player within hearing range
(default 16 blocks × volume factor).

Key sound events (1.21.1 Yarn names):
- `SoundEvents.WEATHER_RAIN` — rain ambient loop
- `SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER` — thunder crack
- Biome sounds: accessed via `Biome.getLoopSound()` and `Biome.getMoodSound()`

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
At the cadences above (biome loop every 2s, mood every 3s with 15% chance,
weather every 4-10s): ~1 packet every 2 seconds per active portal. Negligible.

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

All audio logic fits inside `ImmersiveProjector.tick()` — it's ~45 lines
added to the existing file. No new classes, no new mixins, no new packets.
This is the smallest phase by implementation size.

### Dependencies from Phase 1

Phase 2 uses:
- `ImmersiveProjector.tick()` — the per-world tick entry point
- Per-player activation state — to know which portals are "active" for audio
- `resolveArrivalPos()` — shared utility to get the target-dimension position
  (accounts for anchor vs scaled coordinates)

If Phase 2 were built before Phase 1, it would need its own activation
tracking — duplicating work. Building it after Phase 1 means it's purely
additive: ~45 lines in one file.
