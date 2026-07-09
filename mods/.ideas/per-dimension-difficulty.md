# Per-dimension difficulty and mob spawning

Configure difficulty, mob spawning, and game rules per custom dimension.

## Motivation

A peaceful cherry grove pocket dimension for building, a hardcore nether wasteland, a mob-free creative sandbox — dimensions are more useful when they can have their own rules.

## Behaviour

- Add optional fields to `DimensionDefinition`:
  - `difficulty` (peaceful/easy/normal/hard, null = server default)
  - `mobSpawning` (boolean, null = server default)
  - `pvp` (boolean, null = server default)
  - `gameRules` (map of gamerule overrides, e.g. `{"doDaylightCycle": false, "doWeatherCycle": false}`)

- When a player enters a custom dimension, apply the dimension's settings:
  - Difficulty: `server.setDifficulty(difficulty, false)` is server-wide — can't do per-dimension. Alternative: use gamerules or a mixin on `ServerWorld.getDifficulty()` to return per-world values
  - Mob spawning: `doMobSpawning` gamerule is server-wide. Alternative: cancel mob spawns via a `LivingEntity.checkDespawn` mixin checking the world key
  - PvP: intercept `PlayerEntity.attack` for the source world
  - Game rules: some are per-world in 1.21.1, some are global — need to check which

## Implementation notes

- Per-dimension difficulty is NOT natively supported — `MinecraftServer.setDifficulty()` is global. Two approaches:
  1. Mixin on `ServerWorld` to override `getDifficulty()` per world key (cleaner but more invasive)
  2. Track per-player and switch difficulty as they teleport (simpler but affects all players briefly)
- Option 1 is better — a `@Mixin(ServerWorld.class)` with `@Inject` on `getDifficulty` checking a per-world config map
- Mob spawning can be controlled by cancelling spawns in worlds marked no-spawn, without touching the gamerule
- Peaceful mode specifically prevents hostile mob spawning AND despawns existing hostiles — replicating this per-dimension requires the difficulty mixin approach
- `/dimension create` could accept optional flags: `/dimension create cherry_pocket single_biome minecraft:cherry_grove --peaceful --no-mobs`

## Scope

Start with just `mobSpawning: false` support (cancel hostile spawns per dimension). Difficulty and gamerule overrides are more complex and can follow as separate work.
