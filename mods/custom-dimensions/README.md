# Custom Dimensions

Runtime dimension creation with custom portal frames, configurable igniters, coordinate scaling, coloured particles, and bidirectional travel for Minecraft 1.21.1 Fabric.

## Features

- **8 world types** -- overworld, nether, end, void, superflat, amplified, large_biomes, single_biome
- **Custom portal frames** -- any block as the frame, any item as the igniter
- **Horizontal portals** -- floor and ceiling portals (Y-axis) alongside vertical X/Z portals
- **Per-dimension seeds** -- each dimension can use its own world seed
- **Coordinate scaling** -- configurable scale factor per portal (e.g., 0.125 for nether-style 1:8)
- **Coloured particles** -- hex colour per portal, rendered on both source and target sides
- **Per-portal cooldown** -- configurable teleport cooldown (0-200 ticks) per portal link
- **Portal sound effects** -- configurable ignition, entry, and exit sounds per portal (JSON config only)
- **Bidirectional travel** -- target-side portals are built automatically; stepping in returns you
- **Idle dimension unloading** -- empty dimensions are saved and unloaded after a configurable idle period (default 5 min), re-created on demand
- **Per-dimension mob control** -- disable hostile mob spawning per dimension for peaceful pocket worlds
- **Persistent config** -- dimensions, portals, and settings saved to `multiverse_config.json`; portal link state saved to `portal_links.json`

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16+
- Fabric API
- Java 21

## Commands

### `/dimension create`

```
/dimension create <name> <type> [seed] [biome] [peaceful]
```

| Argument | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | string | yes | Lowercase, alphanumeric with `_`, `-`, `/` |
| `type` | word | yes | `overworld`, `nether`, `end`, `void`, `superflat`, `amplified`, `large_biomes`, `single_biome` |
| `seed` | long | no | World seed (null = server seed) |
| `biome` | identifier | no | Biome ID for `single_biome` type (required for that type) |
| `peaceful` | boolean | no | `true` to disable hostile mob spawning |

### `/dimension delete`

```
/dimension delete <name>
```

Removes a dimension definition from the config. Does not delete world files.

### `/portal link`

```
/portal link <id> <frame> <igniter> <target> <color> <light> [scale] [cooldown]
```

| Argument   | Type       | Required | Description                                                                  |
| ---------- | ---------- | -------- | ---------------------------------------------------------------------------- |
| `id`       | string     | yes      | Unique portal identifier                                                     |
| `frame`    | identifier | yes      | Block ID for the portal frame (e.g., `minecraft:obsidian`)                   |
| `igniter`  | identifier | yes      | Item ID to ignite the portal (e.g., `minecraft:flint_and_steel`)             |
| `target`   | identifier | yes      | Target dimension (e.g., `minecraft:the_nether` or `minecraft:cherry_pocket`) |
| `color`    | string     | yes      | 6-digit hex colour for particles (e.g., `FF0000`)                            |
| `light`    | integer    | yes      | Light level 0-15                                                             |
| `scale`    | double     | no       | Coordinate scale factor, default 1.0 (0.001-1000)                            |
| `cooldown` | integer    | no       | Teleport cooldown in ticks, default 40 (0-200)                               |

### `/portal delete`

```
/portal delete <id>
```

## Examples

**Standard overworld dimension:**

```
/dimension create adventure overworld
```

**Cherry grove pocket dimension (peaceful, custom seed):**

```
/dimension create cherry_pocket single_biome 98765 minecraft:cherry_grove true
/portal link cherry minecraft:cherry_blossom minecraft:cherry_blossom_petals minecraft:cherry_pocket FF9EC6 8
```

**Nether hub with 1:8 coordinate scaling:**

```
/dimension create nether_hub void
/portal link nether_gate minecraft:obsidian minecraft:flint_and_steel minecraft:nether_hub AA0000 11 0.125
```

**Superflat redstone world:**

```
/dimension create redstone_lab superflat
/portal link lab minecraft:iron_block minecraft:redstone minecraft:redstone_lab FF0000 15
```

**Amplified terrain with custom seed:**

```
/dimension create epic_terrain amplified 42
```

**Near-instant hub portal (5-tick cooldown):**

```
/portal link hub_gate minecraft:gold_block minecraft:ender_pearl minecraft:hub FFD700 12 1.0 5
```

**Horizontal floor portal:** Build a frame flat on the ground (e.g., a ring of obsidian), then right-click the top face with the igniter item. The portal detects the horizontal plane and creates a Y-axis portal you walk onto.

## Configuration

All configuration is stored inside the server's data directory under `config/`.

### `multiverse_config.json`

```json
{
  "dimensions": [
    {
      "name": "cherry_pocket",
      "type": "single_biome",
      "dimensionId": "minecraft:cherry_pocket",
      "seed": 98765,
      "biome": "minecraft:cherry_grove",
      "hostileSpawning": false
    }
  ],
  "portals": [
    {
      "id": "cherry",
      "frameBlock": "minecraft:cherry_blossom",
      "igniterItem": "minecraft:cherry_blossom_petals",
      "targetDimension": "minecraft:cherry_pocket",
      "color": "FF9EC6",
      "lightLevel": 8,
      "scale": 1.0,
      "cooldown": 40,
      "igniteSound": "block.portal.trigger",
      "enterSound": "block.portal.travel",
      "exitSound": "block.portal.travel"
    }
  ],
  "frameOverworld": "minecraft:crying_obsidian",
  "frameNether": "minecraft:obsidian",
  "frameEnd": "minecraft:iron_block",
  "idleUnloadMinutes": 5
}
```

### `portal_links.json`

Persists the position and metadata of target-side portal blocks. Managed automatically; do not edit by hand.

### Sound effects

Sound fields (`igniteSound`, `enterSound`, `exitSound`) are config-file-only -- not exposed in commands. Accept any Minecraft sound ID (e.g., `entity.enderman.teleport`, `block.amethyst_block.chime`).

### Idle unloading

`idleUnloadMinutes` (default 5) controls how long a dimension with no players stays loaded before being saved and removed from memory. Vanilla dimensions (overworld, nether, end) and paradise_lost are never unloaded. Dimensions with forceloaded chunks are never unloaded. Re-created automatically when a player teleports in.

### BlueMap integration (auto-unfreeze on first visit)

BlueMap runs as a standalone CLI sidecar container (since v2.14.0), so the mod has no map integration at all. Unvisited dimensions cost the renderer nothing via `min-inhabited-time: 1` in each map's conf — the old freeze/unfreeze dance (deploy froze each map once; the mod unfroze on first visit) is gone along with the in-process BlueMap mod that required it.

## Building

```bash
mise install                         # ensure Java 21
gradle wrapper --gradle-version 8.13 # one-time, generates gradlew
./gradlew build                      # output: build/libs/customdimensions-1.0.5-fork.jar
```

## Testing

```bash
./gradlew test
```

Tests cover config serialisation round-trips, definition defaults, colour parsing, direction arrays, and dimension manager state. Minecraft-dependent tests (registry lookups, block state checks) require the game test harness and are not included.

## Installation

Copy the built JAR to the server's `mods/` directory, or to `overlay/mods/` in a consumer repo for automatic deployment.

```bash
cp build/libs/customdimensions-1.0.5-fork.jar ../../overlay/mods/
```

## Fork notes

This is a fixed and extended fork of the Custom Dimensions mod (MIT licensed). The original had three bugs preventing it from working on 1.21.1:

1. **NetherPortalBlockMixin** targeted methods that don't exist on `NetherPortalBlock` in 1.21.1 (they live on `AbstractBlock`), causing a crash on startup. Removed entirely.
2. **MinecraftServerAccessor** and **SimpleRegistryAccessor** were not listed in the mixin config, causing `ClassCastException` at runtime. Registered.
3. **RefMap** was missing from the JAR. Proper Fabric Loom build generates it automatically.

All intermediary names (`class_XXXX`, `method_XXXX`, `field_XXXX`) have been translated to Yarn 1.21.1+build.3 human-readable names. New features (horizontal portals, per-dimension seeds, world type presets, sound effects, cooldown config, idle unloading, mob spawning control) were added on top of the fixed base.

## Licence

MIT
