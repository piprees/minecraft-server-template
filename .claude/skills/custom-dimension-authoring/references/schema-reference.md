# Dimension Config — Full Schema Reference

Ground truth: `/Users/pip/Projects/minecraft-server-template/mods/custom-dimensions/src/main/java/com/customdimensions/config/DimensionConfig.java` (+ `PortalDefinition.java`, `DimensionStructures.java`, `StructureThemes.java`, `MultiverseConfig.java`) and the mod's own `README.md`. Every field below is **optional** — a two-line `{"seed": 12345, "spawn": [0, 64, 0]}` is a valid file. `settings.json` (platform default at `/Users/pip/Projects/minecraft-server-template/config/custom-dimensions/settings.json`) deep-merges its `defaults` block under every dimension; elfydd has no overlay `settings.json` of its own, so those platform defaults apply as-is:

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
    "frameBlock": "minecraft:mossy_stone_bricks",
    "borders": { "player": 8192, "generation": 8192 },
    "difficulty": {
      "mobMultiplier": 1.0,
      "attributes": { "health": true, "damage": true, "armor": true, "speed": false, "knockback": false },
      "playerLuck": 1.0
    }
  }
}
```

`defaults.frameBlock` only merges in when the dimension declares a `portal` block at all — it doesn't invent a portal for portal-less dimensions.

## Top-level fields

| Key | Type | Timing | Notes |
| --- | --- | --- | --- |
| `type` | string | creation-time | See [Valid types](#valid-type-values) below. Required for any new (non-base-world) dimension. On a **base world** it selects nothing — it is the opt-in to structure management; see [Base worlds](#base-worlds). |
| `description` | string | — | Documentation only; never parsed by the mod. Still worth writing well — good practice, and useful for humans skimming files. |
| `seed` | number \| `"env"` | creation-time | Per-dimension world seed. `"env"` reads the `SEED` env var. Changing this after the world exists does nothing. |
| `spawn` | `[x, y, z]` | boot-re-read | Spawn point. The seed roller overwrites this when it finalises a winner. |
| `noiseSettings` | string (registry id) | creation-time | `ChunkGeneratorSettings` id. The mod ships `adventure:wide` (broad realistic relief, tall build height) and `adventure:compressed` (tighter climate bands, taller vertical scale, more relief per horizontal distance — used by ~23/84 shipped dims, mostly harder/pocket ones). Any datapack-registered id works. Ignored for void/superflat. |
| `biomes` | array | creation-time | List of biome id strings, or `{"id": "...", "parameters": {...}}` objects for explicit multi-noise climate overrides (rare — 0 uses in shipped dims). Missing/unregistered ids are filtered with a log warning; if the list is empty after filtering, falls back to `minecraft:plains`. |
| `checkerboardScale` | integer 0-62 | creation-time | `type: "checkerboard"` only. Cell size = `2^(scale+4)` blocks. Default 2. |
| `layers` | array of `{"block": id, "height": n}` | creation-time | `type: "superflat"` only, bottom-up layer stack. |
| `flatBiome` | string | creation-time | `type: "superflat"` only. Default `minecraft:plains`. |
| `settingsOverrides` | object | creation-time | `{"seaLevel": int, "defaultBlock": id, "defaultFluid": id, "disableMobGeneration": bool}`. Applied after `noiseSettings` resolves. |
| `biomePatches` | array | creation-time | Fixed circular biome patches over the generated layout — precision placement. Rare; 0 uses in shipped dims. |
| `scale` | number | — | Base-world (overworld/nether/end/paradise_lost overrides) travel-scale metadata only; custom dimensions use `portal.scale` instead. |
| `borders` | object | see below | `{"player": int, "generation": int}` — both default 8192. `player`: vanilla world border radius, set at boot, boot-re-read (safe to tune anytime). `generation`: tooling metadata for Chunky/render bounds + the seed-group fingerprint key (creation/rolling relevant, not enforced by the mod at runtime). |
| `difficulty` | object | boot-re-read | See [Difficulty](#difficulty-object) below. |
| `structureDensity` | `"dense"` \| `"normal"` \| `"sparse"` \| `"none"` | boot-re-read (new chunks only) | Scales dungeon/loot structure frequency. `dense` ≈2x, `sparse` ≈half. Peaceful dims drop dungeon-theme sets regardless of density. |
| `structures` | object | mixed | Wants/shuns for the roller, plus runtime `spacing`/`mode`/`list`/`force`. See [Structures](#structures-object) below. |
| `portal` | object | boot-re-read | See [Portal](#portal-object) below. Omit entirely for a dimension with no portal (still reachable via commands/dimension links). |
| `exitPortal` | object | boot-re-read | Mod-built exit frame near spawn. `{"enabled": true, "pos": "spawn", "target": "bed"}`. |
| `exits` | object (map) | boot-re-read | Exit condition rules — void/death/enderPearl/fallFrom triggers. See [Exits](#exits-object) below. |
| `exitShrines` | object | worldgen creation-time; beacon detection boot-re-read | `{"enabled": true, "target": "bed"}` — scattered jigsaw exit ruins. |
| `environment` | object | mostly boot-re-read (a few creation-time) | Custom `DimensionType` registration (`{ns}:{slug}_type`). See [Environment](#environment-object) below. |
| `seedRoll` | object | ignored by the mod at runtime | Scoring config for seed rolling only. See [seedRoll](#seedroll-object) below — this is the block that actually determines what "good" means for this dimension. |
| ~~`dimensionId`~~ | string | — | **Legacy — omit.** The id is always derived from `{namespace}:{filename}`. |
| ~~`hostileSpawning`~~ (top-level) | bool | — | **Legacy — use `difficulty.hostileSpawning` instead**, which wins when both are present. |

## Valid `type` values

| Type | Description | Roller family |
| --- | --- | --- |
| `overworld` | Standard overworld terrain, all biomes | overworld |
| `multi_biome` | Overworld noise, curated biome subset — **the most common type for themed dims** | overworld |
| `nether` | Nether cave terrain | nether |
| `end` | End terrain (islands over void) | end |
| `void` | No terrain; biome layout still drives mob spawns/sounds/fog. Only rollable with a `biomes` list. | none |
| `superflat` | Flat, configurable layers. Never rollable. | — |
| `cave` | Cave world, overworld climate, cave ceiling | overworld |
| `checkerboard` | Deterministic biome grid over overworld noise terrain | overworld |
| `sky_islands` | Sky island terrain | overworld (islands) |
| `nether_islands` | End-island-style bones with nether biomes | nether |
| `amplified` | Amplified terrain | overworld |
| `large_biomes` | Large biomes | overworld |
| `single_biome` | One biome only — `biomes` must have exactly one entry | overworld |
| `ns:path` (e.g. `paradise_lost:paradise_lost`) | Clone of any registered dimension type id | inferred from id |

## `difficulty` object

```json
{
  "difficulty": {
    "hostileSpawning": true,
    "mobMultiplier": 1.5,
    "attributes": { "health": true, "damage": true, "armor": false, "speed": false, "knockback": false },
    "playerLuck": 0.5,
    "depthScaling": { "enabled": true, "startY": 0, "endY": -64, "minMultiplier": 1.0, "maxMultiplier": 2.0 }
  }
}
```

- `hostileSpawning` (bool, default `true`) — `false` ≈ peaceful; also auto-drops dungeon-theme structure sets.
- `mobMultiplier` (double, default `1.0`) — scales hostile mob health/damage/armor via attribute modifiers at spawn (persisted in NBT). `0` is effectively peaceful even with `hostileSpawning: true`.
- `attributes` — which stats the multiplier touches. Platform default: health+damage+armor true, speed/knockback false.
- `playerLuck` (double, default `1.0`) — flat luck bonus on join/world-change. Higher = better loot rolls.
- `depthScaling` — ramps the multiplier from `minMultiplier` at `startY` to `maxMultiplier` at `endY` (deeper = harder). Used by `overworld.json` (`64→-64`, `1.0→1.5`).

Observed shipped patterns: peaceful `mobMultiplier: 0.0` + `hostileSpawning: false` + `playerLuck: 2.0-3.0`; standard `1.0`; hard `1.5-2.0`; brutal pocket dims `3.0` + `playerLuck: 2.0` (e.g. `the_gauntlet.json`).

## `structures` object

```json
{
  "structures": {
    "wants": { "guide_post_warm": { "min": 256, "max": 512 } },
    "shuns": { "village": {}, "ruined_portal": { "minDistance": 2000 } },
    "endgame": { "allow": true, "safeRadius": 1500 },
    "spacing": { "minecraft:villages": { "spacing": 32, "separation": 8 } },
    "mode": "allow",
    "list": ["minecraft:villages", "adventure:exit_shrines"],
    "force": [{ "structure": "minecraft:ancient_city", "x": 1200, "z": -800 }],
    "clearSpawnRadius": 64
  }
}
```

- `wants` — map of structure short-name → `{"min": N, "max": M}` **block distances**. Roller-only (the mod doesn't read this for placement, only the roller scores against it). Short names resolve via `references/structure-names.md`.
- `shuns` — map of short-name → `{}` (must not exist anywhere in the playable radius) or `{"minDistance": N}` (must be at least N blocks away). **Map form only** — a bare list crashes Gson.
- `endgame` — `{"allow": bool, "safeRadius": N}`. Overrides the roller's automatic endgame-near-spawn penalty.
- `spacing` — map of structure **set** id → `{"spacing": N, "separation": M}`. This one IS read by the mod at runtime (new chunks only) — actually rescales placement, not just scoring.
- `mode` + `list` — `"allow"`/`"reject"`/`"none"` filter on organic structure sets. Runtime.
- `force` — exact placements: `{"structure": "<full id>", "x": N, "z": N}` (structure ids here, not short names, not set ids). Runtime.
- `clearSpawnRadius` — overrides the mood-derived default (see `references/scoring-internals.md`) for how close a structure is allowed to spawn before it penalises the roll.

## `portal` object

```json
{
  "portal": {
    "frameBlock": "minecraft:cherry_planks",
    "frameMaterials": { "top": "minecraft:oak_planks", "sides": "#minecraft:logs", "bottom": "minecraft:stone" },
    "framePlaceBlock": "minecraft:oak_log",
    "igniterItem": "minecraft:cherry_sapling",
    "color": "FFB7C5",
    "lightLevel": 11,
    "scale": 1.0,
    "cooldown": 40,
    "particleType": "minecraft:cherry_leaves",
    "orientation": "vertical_x",
    "shape": "door",
    "centreBlock": "minecraft:dragon_egg",
    "sounds": { "ignite": "block.portal.trigger", "enter": "block.portal.travel", "exit": "block.portal.travel" },
    "anchor": { "pos": "spawn", "exit": "bed" },
    "singleUse": { "enabled": true, "delaySeconds": 10, "breakMode": "decay", "decayMap": {} },
    "aura": { "enabled": false },
    "immersive": true
  }
}
```

| Field | Notes |
| --- | --- |
| `frameBlock` | Four accepted forms: plain id `"minecraft:obsidian"`; tag `"#minecraft:logs"`; union list `["id", "#tag"]`; colour group `{"colorGroup": "red"}` (16 valid names: white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black). |
| `frameMaterials` | Per-part override: `top`/`sides`/`bottom`, each accepting any `frameBlock` form. Mutually exclusive with `frameBlock` (this wins if both present). Vertical portals only. |
| `framePlaceBlock` | Concrete block the mod actually places for mod-built frames. **Required if `frameBlock` is tag-only** — otherwise silently falls back to obsidian. |
| `igniterItem` | Item id that ignites the portal. Multiple dimensions can validly share one igniter — the mod tries all matching candidates. |
| `color` | 6-digit hex, **no `#`**. Ignored if `particleType` is set. |
| `lightLevel` | 0-15. Most shipped dims: `11`. |
| `scale` | Coordinate scale factor, `0.001`-`1000`. `0.125` = classic nether 1:8. Smaller playable-world-via-scale ties into the difficulty philosophy (see main SKILL.md). |
| `cooldown` | Teleport cooldown in ticks, `0`-`200`. Default `40` (2s). |
| `particleType` | Any particle id, overrides `color`. |
| `orientation` | `"any"` (default) \| `"vertical"` (X/Z) \| `"horizontal"` (Y) \| `"vertical_x"` \| `"vertical_z"`. |
| `shape` | `"standard"` \| `"door"` (1×2) \| `"doorway"` (2×3) \| `"end_exit"` (horizontal ring) \| `"end_gateway"` (frameless single block) \| a custom pattern object. |
| `centreBlock` | `end_exit` shape only — pedestal block at centre. |
| `sounds` | Any Minecraft sound event id (dot-separated, e.g. `entity.enderman.teleport`), not a file path. Legacy flat keys `igniteSound`/`enterSound`/`exitSound` also work; `sounds` wins if both present. Defaults: ignite `block.portal.trigger`, enter/exit `block.portal.travel`. |
| `anchor` | Fixed landing point. `pos`: `[x,y,z]` or `"spawn"`. `exit`: `"origin"` (default) \| `"bed"` \| `"worldSpawn"` \| `{"dimension": "ns:slug", "arrival": "anchor"\|"spawn"\|[x,y,z]}`. |
| `singleUse` | Self-destructing portal. `delaySeconds` (default 10) from first traversal to frame break. `breakMode`: `"destroy"` \| `"decay"` (default, swaps via `decayMap`) \| `"partial"` (1-2 blocks decay, repairable). |
| `aura` | Environmental particle/sound spread near the portal. `{"enabled": false}` to disable; default is a derived bi-directional leak. |
| `immersive` | See through the portal into the destination, hear its biome ambience, and throw items/projectiles through. Server-side only — no client mod. `true` for all defaults, absent/`false` for off, or an object to tune: `previewDepth` (1-16, default 8), `previewRadius` (0-4, default 2), `refreshInterval` ticks (min 2, default 4), `activationRange` blocks (1-64, default 24), `audio` (default true), `entityPassthrough` (default true). An explicit `"enabled": false` inside the object means off. Boot-re-read like the rest of `portal`, so changes apply without a world wipe. Excluded for `end_gateway` shapes (no projection plane). |

## `exitPortal` object

`{"enabled": true, "pos": "spawn", "target": "bed"}` — builds and maintains a frame near spawn. `target` accepts the same values as `anchor.exit`. Boot validation WARNs (never crashes) if a `singleUse`/`anchor` dimension lacks an `exitPortal`.

## `exits` object

```json
{
  "exits": {
    "void": { "target": "origin", "action": "teleport" },
    "death": { "target": "worldSpawn", "action": "respawnAt" },
    "death:lava": { "action": "teleport", "target": { "dimension": "adventure:the_furnace_halls" } },
    "enderPearl": { "target": "worldSpawn", "action": "teleport" },
    "fallFrom": { "minHeight": 100, "target": "origin", "action": "teleport" }
  }
}
```

Trigger keys: `"void"`, `"death"`, `"death:<cause>"` (e.g. `death:lava`, `death:mob:minecraft:zombie`), `"enderPearl"`, `"fallFrom"`. Rule fields: `action` (`"teleport"` intercepts/cancels the event, `"respawnAt"` lets it happen then respawns at target, `"kill"` = explicit vanilla void death — void trigger only), `target` (same values as `anchor.exit`), `minHeight` (`fallFrom` only, default 100).

## `environment` object

```json
{
  "environment": {
    "skyColor": "#4A2C6B",
    "fogColor": "#2A1A3E",
    "ambientLight": 0.3,
    "fixedTime": 18000,
    "hasSkylight": true,
    "hasCeiling": false,
    "ultraWarm": false,
    "natural": false,
    "bedWorks": false,
    "respawnAnchorWorks": true,
    "piglinSafe": false,
    "hasRaids": false,
    "minY": -64,
    "height": 512,
    "logicalHeight": 512,
    "coordinateScale": 1.0,
    "effects": "minecraft:the_end",
    "infiniburn": "#minecraft:infiniburn_overworld",
    "monsterSpawnLightLevel": 7,
    "monsterSpawnBlockLightLimit": 0
  }
}
```

All fields optional; anything unset inherits from the base dimension type. Registers a new `DimensionType` (`{ns}:{slug}_type`). Invalid heights fall back to the base type rather than crashing. `skyColor`/`fogColor` are client-visible-only with client mods (no effect on a plain vanilla client). `effects` picks sky rendering: `minecraft:overworld` / `minecraft:the_nether` / `minecraft:the_end`. **Creation-time**: `minY`, `height`, `logicalHeight`, `coordinateScale`. Everything else here is boot-re-read.

## `seedRoll` object

The mod ignores this block entirely at runtime — it exists purely for seed rolling. This is the block that actually defines "what does a good seed for this dimension look like":

```json
{
  "seedRoll": {
    "skip": false,
    "mood": "adventurous",
    "spawnFilter": ["minecraft:desert", "minecraft:savanna"],
    "spawnRadius": 128,
    "water": "none",
    "locateCap": 9000,
    "terrain": "islands",
    "heightRange": [-60, 440],
    "family": "overworld",
    "allowEndgameNearSpawn": false,
    "description": "Human-readable dimension philosophy.",
    "wants": { "village": "near_spawn", "ancient_city": "spread" },
    "shuns": ["village", "tavern"]
  }
}
```

| Field | Notes |
| --- | --- |
| `skip` | `true` excludes this dimension from rolling entirely. |
| `mood` | One of the 8 valid moods (`hard`/`adventurous`/`dramatic`/`scenic`/`pastoral`/`serene`/`desolate`/`standard`) — see `references/scoring-internals.md` for exact weights/behaviour. |
| `spawnFilter` | Biome ids. Candidates whose spawn biome isn't in this list are rejected outright — this is the #1 cause of zero candidates if it lists a biome that doesn't actually exist in the biome table for this family. |
| `spawnRadius` | Sampling radius for spawn biome checks. |
| `water` | `"none"` / `"high"` / `"sea"` water-fraction preference. |
| `locateCap` | Max locate distance; defaults to generation border + 1000. |
| `terrain` | `"solid"` / `"islands"` / `"void"` override of the auto-detected terrain kind. |
| `heightRange` | `[minY, maxY]` for terrain height computation (mostly matters for clone types). |
| `family` | `"overworld"`/`"nether"`/`"end"`/`"paradise_lost"` override of auto-detection from `type`. |
| `allowEndgameNearSpawn` | Lets endgame/boss structures sit near spawn without penalty. |
| `description` | Human-readable philosophy — shown in the viewer UI. Reuse the theme prompt here. |
| `wants` | **Band-name** form: short-name → `"near_spawn"`/`"spread"`/`"near_border"`. Different format from `structures.wants` — see main SKILL.md traps. |
| `shuns` | Bare list of short names (or map form) to penalise. |

## Base worlds

`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json` are **reserved filenames** — they override vanilla/existing worlds (map to `minecraft:overworld` etc.) rather than creating a new dimension. Don't create a new file with one of these names unless you deliberately mean to override that base world.

They are managed like any other dimension: `seed`, `spawn`, `scale`, `borders`, `difficulty`, `seedRoll`, `structureDensity`, `structures` and `portal` all apply. The mod resolves them by **exact dimension id**, never by namespace — `minecraft:` and `paradise_lost:` hold other mods' dimensions too.

**A base world names no `type`** — vanilla owns its generator — and its structure groups resolve against its family instead:

| File | Family |
| --- | --- |
| `overworld.json` | `overworld` |
| `the_nether.json` | `nether` |
| `the_end.json` | `end` |
| `paradise_lost.json` | `paradise_lost:paradise_lost` |

Writing an explicit `type` overrides that, which is how you move a base world onto another family's group set.

**A base world's `portal` block is live**: `the_nether.json`'s obsidian/flint-and-steel portal is the way to the Nether, at its configured scale, colour and sounds. `portal.scale` and `borders.player` must agree — a portal built at the source world's border divides by the scale on arrival and must land inside the destination's border. `ShippedDimensionReachabilityTest` pins that for every shipped dimension, base worlds included.

## `structures` — noise placement fields

Noise placement is the default for every managed dimension; these fields override it. All are creation-time-affecting (they change the world a seed generates) and all are fingerprinted by the seed roller.

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `structureDensity` | `"none"` / `"sparse"` / `"normal"` / `"dense"` | `"normal"` | Global profile for every group. `"normal"` means "use the world type's defaults", not a profile. `"none"` suppresses noise entirely; `force` still applies. |
| `structures.noise` | string, object, or `false` | type defaults | String = every group uses that profile. Object = `{group: profile}`, per group, `"none"` drops that group, unmentioned groups keep the type default. `false` = fall back to vanilla grid placement (escape hatch). |
| `structures.radial` | `{group: number[10]}` | type defaults | 10-point piecewise-linear weight curve, spawn (index 0) to border (index 9). Values 0.0–3.0. Multiplied into the noise before the threshold test, so `0.0` suppresses a band absolutely. Wrong length or out-of-range warns and falls back. |
| `structures.rarity` | `{set_id: tier}` | derived from spacing | `common` / `uncommon` / `rare` / `endgame`. Changes a set's share of its group's placements, and can move it between groups — the `endgame` group requires a rare-or-rarer tier. Uses structure SET ids. |
| `structures.exclude` | `string[]` | `[]` | Structure SET ids removed from the noise pool entirely. |
| `structures.include` | `string[]` | `[]` | Structure SET ids forced into the pool, bypassing the biome filter. The escape hatch for a filter that is too aggressive. |
| `structures.force[].exclusive` | boolean | `true` | Whether forcing a structure also removes it from the noise pool. Absent = true. |

**Groups:** `deco`, `settlements`, `dungeons`, `landmarks`, `maritime`, `endgame`, `loot`. Which are enabled comes from the world type — see `config/custom-dimensions/structure-type-defaults.json`, and `config/custom-dimensions/structure-groups.json` for every set's classification.

**Profiles:** `natural`, `dense`, `sparse`, `cluster`, `none`.

**Difficulty shifts apply automatically and outrank `structureDensity`:** `difficulty.mobMultiplier >= 2.0` puts `dungeons` on an even curve and `endgame` on a mid curve; `<= 0.5` suppresses both. Only an explicit per-group `structures.noise` entry overrides a shift.

**Two fields changed meaning:** `borders.player` and `difficulty.mobMultiplier` are now generation-affecting (the border sets the scanned radius AND the noise frequency scale; the multiplier drives the shifts). Editing either re-rolls the dimension.
