# Per-dimension game rules (PvP, daylight cycle, etc.)

**Difficulty is fully covered** by two existing systems:
- `configurable-difficulty` mod — scales mob health/damage/armour per dimension
  (0.0 for peaceful dims up to 3.0 for the gauntlet) plus depth scaling and
  per-dimension player luck multipliers. Config: `config/configurable-difficulty/`
- `peaceful` flag on `DimensionDefinition` — blocks hostile mob spawning entirely
  via `PeacefulDimensionSpawnMixin`

## What's actually left (nice-to-haves)

- **PvP toggle per dimension** — intercept `PlayerEntity.attack` by world key; useful for safe-zone creative dims
- **Per-dimension game rule overrides** (`doDaylightCycle`, `doWeatherCycle`, `randomTickSpeed`) — vanilla gamerules are server-wide; per-dimension would need mixin interception, not RCON commands
- **Hostile despawn on entry** — when a player enters a peaceful dimension, existing hostiles that somehow got in (e.g. from a portal) should despawn; current impl only prevents new spawns
