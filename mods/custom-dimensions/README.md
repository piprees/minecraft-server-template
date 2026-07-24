# Custom Dimensions

Runtime dimension creation with custom portal frames, configurable igniters, coordinate scaling, coloured particles, and bidirectional travel for Minecraft 1.21.1 Fabric.

## Features

- **12 world types** -- overworld/multi_biome, nether, end, void, superflat, cave, checkerboard, sky_islands, nether_islands, amplified, large_biomes, single_biome — plus `ns:path` clone types for any registered dimension
- **Checkerboard dimensions** -- `type: "checkerboard"` tiles the `biomes` list in a fixed grid (seed-independent layout, seeded terrain); `checkerboardScale` 0-62 (default 2) sets the cell size (`2^(scale+4)` blocks)
- **Custom superflat layers** -- `layers` (bottom-up `{block, height}` list) and `flatBiome` on `type: "superflat"`; invalid config falls back to the whole default bedrock/dirt/grass stack
- **Generator settings overrides** -- `settingsOverrides` swaps `seaLevel`, `defaultBlock`, `defaultFluid`, `disableMobGeneration` on the type's (or noiseSettings preset's) generator settings; per-field warn-and-keep-base on invalid values
- **Per-biome placement parameters** -- `biomes` entries may be `{id, parameters}` objects with explicit multi-noise intervals (number or `[min,max]` per axis, -2..2); the biome claims exactly that region, unset axes span everything
- **Per-set structure spacing** -- `structures.spacing` maps structure SET ids to exact `{spacing, separation}` values, overriding the theme-based `structureDensity` factors for those sets (boot-re-read; new chunks only)
- **Biome patches** -- `biomePatches` overrides the generated layout in three modes: stamp (`{biome, x, z, radius}` claims the area), clipped swap (`replace` recolours only the matching biome inside the area, organic shape kept), and global swap (`scope: "global"` replaces a biome dimension-wide, or uses the area as a selector for every biome touching it). `shape: circle|square`, `blend` edge jitter (default 8 blocks). A stamp at (0,0) guarantees the spawn biome. Backed by a codec-registered `PatchedBiomeSource` wrapper so the generator persists cleanly
- **Custom portal frames** -- any block as the frame, any item as the igniter; `frameBlock` accepts a plain id, `#ns:tag`, a list, or `{"colorGroup": "<dye>"}`, with `framePlaceBlock` naming the concrete block mod-built frames use
- **Portal shape presets** -- optional `shape`: `door` (1x2), `doorway` (2x3), `end_exit` (horizontal ring, optional `centreBlock` pedestal); absent = free-form flood-fill. Shapes imply orientation; mod-built exit portals follow the dimension's shape
- **Per-part frame materials** -- `frameMaterials` {top, sides, bottom} each accepting any frame form ("stone base, log pillars, plank lintel"); flood-fill accepts the union, validation checks each ring position's part; mod-built frames place per part (vertical portals only)
- **Portal auras** -- portals affect their surroundings: by default each linked pair leaks the other side's sampled nature through (terrain, flora, trees, fluids), bounded by per-side budgets; `portal.aura` overrides palettes, adds explicit conversions (obsidian→crying) and fire, or switches it off
- **Horizontal portals** -- floor and ceiling portals (Y-axis) alongside vertical X/Z portals
- **Per-dimension seeds** -- each dimension can use its own world seed
- **Coordinate scaling** -- configurable scale factor per portal (e.g., 0.125 for nether-style 1:8)
- **Coloured particles** -- hex colour per portal, rendered on both source and target sides
- **Per-portal cooldown** -- configurable teleport cooldown (0-200 ticks) per portal link
- **Portal sound effects** -- configurable ignition, entry, and exit sounds per portal (JSON config only)
- **Bidirectional travel** -- target-side portals are built automatically; stepping in returns you
- **Anchor portals** -- `portal.anchor` gives a dimension one fixed landing (End-gateway style): every source portal arrives at the anchor, no per-source target portal is ever built, and the exit mode (`origin` | `bed` | `worldSpawn`) decides where leaving takes you
- **Single-use portals** -- `portal.singleUse` starts a countdown at first traversal, then the frame breaks (`destroy` | `decay` | `partial`); the countdown persists in `portal_links.json` and survives restarts
- **Exit portals** -- `exitPortal` builds a mod-maintained frame near dimension spawn as a guaranteed way home (rebuilt if broken); config validation WARNs at boot when a strandable dimension (singleUse or anchor) lacks one
- **Exit shrines** -- `exitShrines` scatters `adventure:exit_shrine` jigsaw ruins (jar datapack; templates from `scripts/gen-exit-shrine.py`) whose beacon-marked frames self-register as exit zones on chunk load; the structure set ships at frequency 0.001 and is raised to full only for opted-in dims. Shrine frames are rebuilt in the dimension's own `framePlaceBlock` at registration (one template, any material), and spacing derives from `borders.player` (clamp(radius/32, 12..48) chunks — a 256-radius pocket gets 1-2 shrines, not a grid) unless `structures.spacing` sets it explicitly
- **Dimension links** -- every exit target (`exitPortal.target`, `portal.anchor.exit`, `exits` rules) accepts `{"dimension": "ns:slug", "arrival": "anchor"|"spawn"|[x,y,z]}` alongside the `bed`/`worldSpawn`/`origin` shorthands — dimensions compose into chains and hubs
- **Exit conditions** -- a per-dimension `exits` block maps triggers to targets: `void` (fires before vanilla void damage), `death` / `death:<cause>` / `death:mob:<id>` (cancel-and-teleport or respawn-redirect — death is not always final), `enderPearl`, `fallFrom`; safe arrivals with slow falling, per-player anti-loop cooldown, boot validation for death-only exits and dangling links
- **Idle dimension unloading** -- empty dimensions are saved and unloaded after a configurable idle period (default 5 min), re-created on demand
- **Per-dimension mob control** -- disable hostile mob spawning per dimension for peaceful pocket worlds
- **Per-dimension difficulty** -- `difficulty.mobMultiplier` scales hostile mob health/damage/armor at spawn (attribute modifiers, persisted in NBT); optional `depthScaling` makes mobs harder underground; `playerLuck` boosts loot quality while inside the dimension (absorbed from the configurable-difficulty mod)
- **Per-dimension world borders** -- `borders.player` sets each world's vanilla border at boot (replaces the deploy-time ChunkyBorder dance); `borders.generation` is tooling metadata for Chunky/BlueMap bounds
- **Custom dimension types** -- an `environment` block (fixedTime, ceiling/skylight, ultraWarm, natural, bedWorks, respawnAnchorWorks, piglinSafe, hasRaids, minY/height/logicalHeight, ambientLight) registers a per-dimension `DimensionType` as `{ns}:{slug}_type`; unset fields inherit the base type (skyColor/fogColor are client-side and configurator-only)
- **Per-dimension config files** -- one self-contained JSON per dimension under `config/custom-dimensions/dimensions/` (portal, difficulty, borders, seedRoll included); global defaults in `settings.json`; consumer overlays merge/replace/skip per file. The monolithic `multiverse_config.json` still loads as a deprecated fallback. Portal link state saved to `portal_links.json`

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

### `custom-dimensions/` (v4 — preferred)

One file per dimension; the slug comes from the filename. Base-world filenames (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json`) override existing worlds (seed/spawn) instead of creating new ones — `"seed": "env"` reads the `SEED` environment variable.

```
config/custom-dimensions/
├── settings.json              # namespace, idleUnloadMinutes, frames, defaults
├── dimensions/
│   ├── cherry_pocket.json     # one self-contained file per dimension
│   ├── overworld.json         # base-world override (seed, spawn)
│   └── ...
└── overlay/dimensions/        # consumer overrides (staged by deploy.sh/dev-up.sh
                               # from overlay/config/custom-dimensions/)
```

`dimensions/cherry_pocket.json`:

```json
{
  "type": "single_biome",
  "seed": 98765,
  "biomes": ["minecraft:cherry_grove"],
  "difficulty": { "hostileSpawning": false },
  "portal": {
    "frameBlock": "minecraft:cherry_blossom",
    "igniterItem": "minecraft:cherry_blossom_petals",
    "color": "FF9EC6",
    "lightLevel": 8,
    "scale": 1.0,
    "cooldown": 40,
    "sounds": { "ignite": "block.portal.trigger", "enter": "block.portal.travel", "exit": "block.portal.travel" }
  }
}
```

`settings.json`:

```json
{
  "namespace": "adventure",
  "idleUnloadMinutes": 5,
  "frames": {
    "overworld": "minecraft:mossy_stone_bricks",
    "nether": "minecraft:obsidian",
    "end": "minecraft:end_stone_bricks"
  },
  "defaults": {
    "frameBlock": "minecraft:crying_obsidian",
    "borders": { "player": 8192, "generation": 8192 },
    "difficulty": { "mobMultiplier": 1.0 }
  }
}
```

Consumer overlay resolution (files in `overlay/dimensions/`): a file with a top-level `"overrides"` object deep-merges over the platform default; a file without one replaces the platform default entirely; an empty `{}` skips the dimension; overlay-only files are consumer-added dimensions namespaced by the `BRAND_SLUG` environment variable.

### Frame materials and orientation

`frameBlock` accepts four forms — what the frame ACCEPTS at ignition and
zone validation:

```jsonc
"portal": {
  "frameBlock": "minecraft:cherry_planks",            // single block id (classic)
  "frameBlock": "#minecraft:logs",                    // any block in a tag
  "frameBlock": ["minecraft:oak_planks", "#minecraft:logs"],  // union list (ids + tags)
  "frameBlock": { "colorGroup": "red" },              // "any red block" — sugar for
                                                      // #adventure:red_blocks (16 dye-colour
                                                      // tags ship in the jar datapack: wool,
                                                      // concrete, concrete powder, terracotta,
                                                      // glazed terracotta, stained glass)

  // Accepting is NOT placing: when the mod BUILDS a frame (arrival portals,
  // exitPortal), it needs one concrete block. Defaults: the plain frameBlock,
  // a list's first plain id, "<colour>_wool" for colour groups; tag-only
  // configs without it fall back to obsidian (boot WARN).
  "framePlaceBlock": "minecraft:oak_log",

  // Which axes ignition may consider. Absent = "any" (all three — the
  // pre-existing behaviour). "vertical" = X or Z, "horizontal" = Y
  // (end-portal style), "vertical_x" / "vertical_z" lock one axis.
  "orientation": "vertical_x"
}
```

Mixed frames are legal: any combination of accepted blocks bounds a valid
portal, and single-use decay resolves each frame block individually through
the decay map. Zones persist the accept forms they were ignited with —
changing a dimension's `frameBlock` later never invalidates existing portals
retroactively (immutable-snapshot rule, same as anchor/singleUse). Invalid
tag ids, unknown colour names, and unknown orientations WARN at boot and
never crash; a tag frame without `framePlaceBlock` WARNs that mod-built
frames fall back to obsidian.

Two hard-won rules from live verification (2026-07-23):

- **Persisted zone records always store a plain block id in `frameBlock`**
  (the placement block; accept forms ride in `frameAccepts`). Older mod
  builds `Identifier.of()` that field in an uncaught world-tick path — a
  `#tag` there crash-loops any server that downgrades.
- **Registered portal blocks are immune to neighbour-update popping**
  (`NetherPortalProtectionMixin`): vanilla re-validates portal frames as
  obsidian-only on ANY adjacent block change, and netherportalspread's
  corruption spread was silently deleting custom-framed arrival portals
  seconds after creation. Player-built vanilla portals are untouched.

### Per-part frame materials

`frameMaterials` gives different frame segments different requirements —
"stone base, log pillars, plank lintel". Mutually exclusive with
`frameBlock` (both present WARNs; frameMaterials wins):

```jsonc
"portal": {
  "frameMaterials": {
    "top": "minecraft:oak_planks",   // each part takes ANY accept form:
    "sides": "#minecraft:logs",      // id, #tag, list, {"colorGroup": ...}
    "bottom": "minecraft:stone"
  },
  // sides is tag-only, so mod-built frames need a concrete block for it
  "framePlaceBlock": "minecraft:oak_log"
}
```

The flood-fill accepts the UNION of all parts (any listed material bounds
the fill); validation then classifies each ring position — below the
interior's lowest row = `bottom`, above its highest = `top`, everything
else = `sides` — and checks that part's matcher. Parts left out accept
the union. **Vertical portals only (v1)**: horizontal (Y-axis) fills and
`end_exit`/`horizontal` configs validate against the union and WARN at
boot (top/bottom has no meaning on a flat ring).

Mod-built frames are built in kind: arrival portals and `exitPortal`
place each part's first plain id (else `framePlaceBlock`, else obsidian).
Zone records persist `framePartAccepts` (plain strings); older jars
ignore the field and validate against the union — graceful downgrade.

### Portal shapes

An optional `"shape"` constrains the geometry a player must build. Absent
(or `"standard"`) keeps free-form flood-fill — any frame-bounded shape up
to 128 interior blocks, today's behaviour:

```jsonc
"portal": {
  "shape": "door",       // exactly 1x2 interior (a single door), vertical
  "shape": "doorway",    // exactly 2x3 interior (the vanilla Nether opening), vertical
  "shape": "end_exit",   // horizontal ring (any footprint), end-portal style
  "shape": "end_gateway",// frameless 1-block teleporter (see below)

  // Explicit template: legend roles are "frame" (must match the frame
  // material), "interior" (must exactly cover the ignited opening), and
  // anything else = don't care. Row-major; for vertical portals the top
  // row is the highest Y and the template auto-tries both X and Z axes;
  // for horizontal portals rows map to +Z.
  "shape": {
    "type": "pattern",
    "template": ["FFFFF", "FF.FF", "F...F", "FF.FF", "FFFFF"],
    "legend": { "F": "frame", ".": "interior" }
  },

  // end_exit only: a pedestal block placed at the interior's centre cell
  // on ignition (dragon egg, trophy). Source-side scenery — arrival pads
  // and mod-built exit portals never get one (the exit-portal intact
  // check requires every interior cell to be a portal block).
  "centreBlock": "minecraft:dragon_egg"
}
```

**`end_gateway`** is fundamentally different: no frame, no flood-fill —
the igniter is used ON a block face (like placing a torch) and a real
`END_GATEWAY` block appears there, beam and all. `frameBlock` is not
required. Vanilla gateway travel is suppressed for mod-owned gateway
positions (`EndGatewaySuppressionMixin` cancels
`EndGatewayBlock.onEntityCollision`; player-placed vanilla gateways
elsewhere keep vanilla rules) — traversal and return trips run through
the same zone tick and return-target machinery as every other custom
portal (`isPortalBlock`/`collectPortalArea` recognise gateways). Zone
validity is simply "the gateway block still exists"; breaking it clears
the zone. Arrivals and `exitPortal`s for gateway dimensions are single
floating gateway blocks.

Shapes imply an orientation default (`door`/`doorway` → `"vertical"`,
`end_exit` → `"horizontal"`); an explicit `"orientation"` always wins, and
a contradictory combination (e.g. `door` + `horizontal`) WARNs at boot as
never-ignitable. Unknown shape names WARN at boot and reject every
ignition until fixed — never a crash, never auto-fixed. Validation runs
after the flood-fill (`PortalShape`, pure geometry): wrong-size interiors
simply don't ignite.

Mod-built frames follow the dimension's shape: arrival portals reuse the
source interior as always, and `exitPortal` builds a 1x2 frame for `door`
dims, the classic 2x3 for `doorway`/`standard`, and a horizontal 3x3
`END_PORTAL` pad ringed in the placement block for `end_exit`. Zone
records persist `shape`/`centreBlock` as plain strings — older jars ignore
the unknown fields (downgrade-safe), and pre-shape records restore as
`standard`.

### Portal auras

Portals affect their surroundings. **By default** (no config) every
linked pair leaks the OTHER side's nature through: at link time (arrival
creation, the only moment both ends are loaded) each side's terrain is
sampled — a solid-block histogram (top 5 = terrain palette), small
plants, logs mapped to tree features, still surface fluids — and slow
bounded passes then convert each side's surroundings using the far
side's palette. Sampling the real loaded terrain (not biome registries)
is deliberate: surface rules live in worldgen noise settings and aren't
practically queryable, and sampling captures modded terrain for free.

```jsonc
"portal": {
  "aura": {
    "enabled": false,        // explicit off switch (absent = on, derived)
    "radius": 12,            // blocks from portal centre (default 8, max 32)
    "interval": 40,          // ticks between passes (default 40, min 10)
    "blocksPerPass": 2,      // conversion attempts per pass (max 16)
    "budget": 300,           // lifetime conversions per side; -1 = endless
    "sides": "both",         // "source" | "target" | "both"

    // Emission override: replaces the SAMPLED palette this dimension
    // leaks into the other side. Empty list = emit nothing.
    "palette": ["minecraft:netherrack", "minecraft:blackstone"],
    "flora": ["minecraft:crimson_fungus"],
    "trees": ["minecraft:crimson_fungus"],   // ConfiguredFeature ids
    "fluids": ["minecraft:lava"],

    // Extras on top of either mode:
    "conversions": { "minecraft:obsidian": "minecraft:crying_obsidian" },
    "fireChance": 0.08       // per-pass ignition on exposed surfaces
  }
}
```

Guard rails (all enforced): the exclusion set (interior + frame ring +
registered portal positions) is never converted; passes are chunk-loaded
guarded and never load terrain; containers/block entities and bedrock are
never touched; fluids form only in depressions (solid floor + ≥3
enclosing walls) and count double against the budget; feature-placement
failures are silent no-ops. Palettes and budgets persist (zone records
and `aura-site-v1` records in `portal_links.json` — plain ids only;
older jars log unknown records as malformed and drop them, so a
downgrade quietly stops auras without crashing). Anchor arrivals sample
once — the first link wins.

**Nether-corruption preset** (the netherportalspread replacement — that
mod was retired in v3.7.0 in favour of auras; two spread engines fought
around the same portals and it converted custom arrival frames). Opt a
nether-y dimension in with:

```jsonc
"portal": {
  "aura": {
    "palette": ["minecraft:netherrack", "minecraft:blackstone",
                "minecraft:magma_block", "minecraft:crimson_nylium"],
    "flora": ["minecraft:crimson_fungus", "minecraft:crimson_roots"],
    "trees": ["minecraft:crimson_fungus"],
    "fluids": ["minecraft:lava"],
    "conversions": { "minecraft:obsidian": "minecraft:crying_obsidian" },
    "fireChance": 0.08,
    "budget": -1              // endless creep, netherportalspread-style
  }
}
```

### Anchor, single-use, and exit portals

Unlike worldgen config (creation-time-only, baked into `level.dat`), the whole portal block — anchor, singleUse, exitPortal included — is re-read every boot, so these features apply to existing dimensions without a world wipe.

```json
{
  "portal": {
    "frameBlock": "minecraft:crying_obsidian",
    "igniterItem": "minecraft:ender_eye",
    "anchor": { "pos": "spawn", "exit": "bed" },
    "singleUse": {
      "enabled": true,
      "delaySeconds": 10,
      "breakMode": "decay",
      "decayMap": { "minecraft:obsidian": "minecraft:crying_obsidian" }
    }
  },
  "exitPortal": { "enabled": true, "pos": "spawn", "target": "bed" }
}
```

**`portal.anchor`** — every source portal for this dimension lands at one fixed position; no per-source target portal or `portal_links.json` return entry is written. `pos` is `[x, y, z]` or `"spawn"` (the dimension's `spawn`, falling back to the border centre); Y is surface-resolved on arrival. `exit` controls the anchor arrival portal: `"origin"` (default — back where you came from, fast travel preserved), `"bed"` (your respawn point, obstruction-checked, never consumes respawn-anchor charges), or `"worldSpawn"`. `"bed"` is still a fast-travel primitive (enter anywhere, exit at your bed) — use `"origin"` when denying travel advantage matters.

**`portal.singleUse`** — the countdown starts at the source portal's first traversal and persists with the zone, so a restart resumes it. On expiry the interior clears and the frame breaks per `breakMode`: `"destroy"` (blocks removed, no drops), `"decay"` (each frame block swapped via the decay map — defaults cover obsidian→crying_obsidian, the cracked-brick families, `*_log`→stripped, `*_planks`→air; `decayMap` entries override), or `"partial"` (1–2 deterministically-picked frame blocks decay; the frame looks — and is — repairable and re-ignitable; note the pick doesn't check reachability, so a frame partly buried in terrain can decay a buried block). The igniter is not refunded.

**`exitPortal`** — the mod builds a small frame (the dimension's own `frameBlock`) at a deterministic offset from `pos` (`"spawn"` or `[x, y, z]`), registered as a permanent exit targeting the overworld with `target` semantics (`"bed"` default | `"worldSpawn"` | `"origin"`), and rebuilds it whenever it's found broken. Boot validation logs a WARN (never a crash, never an auto-fix) for any dimension with `singleUse.enabled` or an `anchor` but no exit portal — stranding by config is a bug, not a feature.

### `multiverse_config.json` (deprecated fallback)

The pre-v4 monolithic format (top-level `dimensions[]` + `portals[]` + `worlds[]` arrays) still loads when `config/custom-dimensions/` does not exist, with a deprecation warning. Migrate with `scripts/migrate-to-v4-config.sh`.

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
