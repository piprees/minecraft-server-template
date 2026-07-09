# Dimension unloading

Unload empty custom dimensions from memory after a configurable idle period.

## Motivation

59 loaded ServerWorlds is a lot of RAM. Most custom dimensions will be empty most of the time — only the one or two a player is actively exploring need to be ticking.

## Behaviour

- Track the last time a player was present in each custom dimension
- After N minutes (configurable, default 5) with no players, unload the ServerWorld
- Remove from the server's worlds map (MinecraftServerAccessor.getWorlds())
- Re-create on demand when a player teleports in (getOrCreateDimension already handles this)
- Vanilla dimensions (overworld, nether, end, paradise_lost) are never unloaded

## Considerations

- Chunky pre-generation must pause/skip unloaded dimensions — check if ChunkyBorder handles missing worlds gracefully
- BlueMap render tasks for unloaded dimensions should be skipped, not errored
- Saving: call `ServerWorld.save()` before unloading to flush any pending changes
- Forceloaded chunks: if a dimension has forceloaded chunks, don't unload it
- Entities in transit: ensure no entities (mobs, items) are mid-tick when the world is removed
