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
| `type` | string | creation-time | See [Valid types](#valid-type-values) below. Required for any dimension whose filename is not one of the four reserved ones. On `overworld`/`the_nether`/`the_end`/`paradise_lost` it selects nothing — `getType()` already supplies the family, so writing one moves that dimension onto another family's group set. See [the section on those four](#overworld-the_nether-the_end-paradise_lost). |
| `description` | string | — | The dimension's one description, read by the mod and shown in the viewer. Write it well; it is what a human reads first. |
| `seed` | number \| `"env"` | creation-time | Per-dimension world seed. `"env"` reads the `SEED` env var. Changing this after the world exists does nothing. |
| `spawn` | `[x, y, z]` | boot-re-read | Spawn point. The seed roller overwrites this when it finalises a winner. |
| `noiseSettings` | string (registry id) | creation-time | `ChunkGeneratorSettings` id. The mod ships `adventure:wide` (broad realistic relief, tall build height) and `adventure:compressed` (tighter climate bands, taller vertical scale, more relief per horizontal distance — used by ~23/84 shipped dims, mostly harder/pocket ones). Any datapack-registered id works. Ignored for void/superflat. |
| `biomes` | array | creation-time | List of biome id strings, or `{"id": "...", "parameters": {...}}` objects for explicit multi-noise climate overrides (rare — 0 uses in shipped dims). Missing/unregistered ids are filtered with a log warning; if the list is empty after filtering, falls back to `minecraft:plains`. |
| `checkerboardScale` | integer 0-62 | creation-time | `type: "checkerboard"` only. Cell size = `2^(scale+4)` blocks. Default 2. |
| `layers` | array of `{"block": id, "height": n}` | creation-time | `type: "superflat"` only, bottom-up layer stack. |
| `flatBiome` | string | creation-time | `type: "superflat"` only. Default `minecraft:plains`. |
| `settingsOverrides` | object | creation-time | `{"seaLevel": int, "defaultBlock": id, "defaultFluid": id, "disableMobGeneration": bool}`. Applied after `noiseSettings` resolves. |
| `biomePatches` | array | creation-time | Fixed circular biome patches over the generated layout — precision placement. Rare; 0 uses in shipped dims. |
| `scale` | number | — | Top-level travel-scale metadata, only read on the four reserved filenames; every dimension including those four uses `portal.scale` for real portal scaling. |
| `borders` | object | see below | `{"player": int, "generation": int}` — both default 8192. `player`: vanilla world border radius, set at boot, boot-re-read (safe to tune anytime). `generation`: tooling metadata for Chunky pre-generation, the map renderer's clamp and `getLocateCap`, never applied to a world. `DimensionLint` WARNs when it reaches more than the server's view distance past `player`, or falls below 512. |
| `difficulty` | object | boot-re-read | See [Difficulty](#difficulty-object) below. |
| `structureDensity` | `"dense"` \| `"normal"` \| `"sparse"` \| `"none"` | boot-re-read (new chunks only) | Scales dungeon/loot structure frequency. `dense` ≈2x, `sparse` ≈half. Peaceful dims drop dungeon-theme sets regardless of density. |
| `structures` | object | mixed | Wants/shuns — scored by the roller AND tilting the noise-pool weight — plus runtime `spacing`/`mode`/`list`/`force`. See [Structures](#structures-object) below. |
| `portal` | object | boot-re-read | See [Portal](#portal-object) below. Omit entirely for a dimension with no portal (still reachable via commands/dimension links). |
| `exitPortal` | object | boot-re-read | Mod-built exit frame near spawn. `{"enabled": true, "pos": "spawn", "target": "bed"}`. |
| `exits` | object (map) | boot-re-read | Exit condition rules — void/death/enderPearl/fallFrom triggers. See [Exits](#exits-object) below. |
| `exitShrines` | object | worldgen creation-time; beacon detection boot-re-read | `{"enabled": true, "target": "bed"}` — scattered jigsaw exit ruins. |
| `environment` | object | mostly boot-re-read (a few creation-time) | Custom `DimensionType` registration (`{ns}:{slug}_type`). See [Environment](#environment-object) below. |
| `seedRoll` | object | scoring, plus `wants`/`shuns` as pool-weight fallbacks | Scoring config for seed rolling. `wants`/`shuns` here also tilt the noise-pool weight when the `structures` block names none; everything else is scoring-only. See [seedRoll](#seedroll-object) below — this is the block that determines what "good" means for this dimension. |
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
- `depthScaling` — ramps a FACTOR from `minMultiplier` at `startY` to `maxMultiplier` at `endY` (deeper = harder). Used by `overworld.json` (`64→-64`, `1.0→1.5`).
  **These are factors on `mobMultiplier`, not effective values.** `DifficultyManager.effectiveMultiplier` returns `mobMultiplier * depthFactor(y)`, so `the_forged_depths` at `mobMultiplier` 2.5 with `1.5→3.5` runs 3.75 to 8.75, not 1.5 to 3.5. Writing the numbers you want as if they were absolute silently doubles the dimension's difficulty.
  The `>= 2.0` / `<= 0.5` structure-group shift reads the STATIC `mobMultiplier` only (`NoiseGroupPlan`), never the depth-scaled value — so a ramp may exceed 2.0 at depth without changing which structures generate.

Observed shipped patterns: peaceful `mobMultiplier: 0.0` + `hostileSpawning: false` + `playerLuck: 2.0-3.0`; standard `1.0`; hard `1.5-2.0`; brutal pocket dims `3.0` + `playerLuck: 2.0` (e.g. `the_gauntlet.json`).

## `structures` object

```json
{
  "structures": {
    "wants": { "guide_post_warm": { "min": 256, "max": 512 } },
    "shuns": { "village": {}, "ruined_portal": {} },
    "spacing": { "minecraft:villages": { "spacing": 32, "separation": 8 } },
    "mode": "allow",
    "list": ["minecraft:villages", "adventure:exit_shrines"],
    "force": [{ "structure": "minecraft:ancient_city", "x": 1200, "z": -800 }],
    "clearSpawnRadius": 64
  }
}
```

- `wants` — map of structure short-name → `{"min": N, "max": M}` **block distances**. Scored by the roller AND read for placement: a want multiplies the structure's noise-pool weight by 1.2 and bypasses the biome-affinity filter. Short names resolve via `references/structure-names.md`; a name resolving to a `#tag` is dropped.
- `shuns` — map of short-name → `{}`. **Map form only** — a bare list crashes Gson. Divides the pool weight by 1.5 — a shun discourages, `exclude` removes, and a shun can never reach zero.
  **`{"minDistance": N}` is PARSED, INERT.** `StructureWants.shunNames` returns `block.shuns.keySet()`, so the values are discarded and every shun behaves as `{}` — the distance is never enforced at placement nor scored. Write `{}` and mean it; a distance in the file reads as a constraint nobody applies.
- Both factors are exact at every weight (the pool is carried at 15 units per weight), and naming a structure in both cancels. When the `structures` block names neither, `seedRoll.wants`/`seedRoll.shuns` supply the list.
- `endgame` — **PARSED, INERT.** `{"allow": bool, "safeRadius": N}` is declared at `DimensionConfig.java:915` and the whole object is read by nothing; there is no automatic endgame-near-spawn penalty for it to override. The live lever is `clearSpawnRadius` below. (The string `"endgame"` elsewhere is a structure GROUP name and is unrelated.)
- `spacing` — map of structure **set** id → `{"spacing": N, "separation": M}`. This one IS read by the mod at runtime (new chunks only) — actually rescales placement, not just scoring.
- `mode` + `list` — `"allow"`/`"reject"`/`"none"` filter on organic structure sets. Runtime.
- `force` — exact placements: `{"structure": "<full id>", "x": N, "z": N}` (structure ids here, not short names, not set ids). Runtime.
- `clearSpawnRadius` — overrides the mood-derived default (see `references/scoring-internals.md`) for how close a structure is allowed to spawn before it penalises the roll.

## `portal` object

`portal` takes one object, or an array of them when a dimension has several
ways in. Every field below is per portal: frame, igniter, colour, scale,
cooldown, sounds, shape and aura are independent for each entry.

```json
{
  "portal": [
    { "frameBlock": "minecraft:copper_block", "igniterItem": "minecraft:diamond", "scale": 4.0 },
    { "frameBlock": "minecraft:mud_bricks", "igniterItem": "minecraft:diamond", "scale": 4.0 }
  ]
}
```

**Config order decides everything ambiguous.** The first entry is the
dimension's PRIMARY portal. It supplies the look and sounds of the return
portal built inside the dimension, the `aura.subsume` and `immersive` policy
for both ends of every link, the `exitPortal`/`exitShrines` presentation, the
anchor, and the dimension's travel scale. When two entries share a frame block
or an igniter, ignition tries them in config order, entries whose frame matches
the block that was clicked first.

**Ids.** The first portal is identified by the dimension slug, the rest by
`<slug>#2`, `<slug>#3`. Ids appear in the boot log and in the portal-adoption
line.

**Give every entry the same `scale`.** `borders.player` must be
`overworldBorder / scale`, and each portal applies its OWN scale on entry — a
second portal with a different scale lands players outside the border, where
vanilla forbids breaking and placing every block.

**A consumer overlay `"overrides"` deep-merge merges an object `portal`
key-by-key, but replaces an array wholesale.** To change one entry of an array,
restate the whole array.

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
| `vanillaManaged` | `true` leaves the portal entirely to vanilla: no ignition claim, no adoption, no zone, no destination override, no immersive projection, no `scale`. The entry documents the classic route in the list and nothing more. `the_nether` and `the_end` ship with it; `overworld` does not (its `mossy_stone_bricks` portal is a mod portal leading TO the overworld). |
| `color` | 6-digit hex, **no `#`**. Ignored if `particleType` is set. |
| `lightLevel` | 0-15. Most shipped dims: `11`. |
| `scale` | Coordinate scale factor, `0.001`-`1000`. `8.0` = classic nether 1:8 — one block here is 8 in the overworld. Shipped values are 1, 2, 4, 8, 12 and 16; 34 dimensions use 8. Ties into the difficulty philosophy (see main SKILL.md). |
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
    "effects": "minecraft:the_end",
    "infiniburn": "#minecraft:infiniburn_overworld",
    "monsterSpawnLightLevel": 7,
    "monsterSpawnBlockLightLimit": 0
  }
}
```

All fields optional; anything unset inherits from the base dimension type. Registers a new `DimensionType` (`{ns}:{slug}_type`). Invalid heights fall back to the base type rather than crashing. `skyColor`/`fogColor` are client-visible-only with client mods (no effect on a plain vanilla client). `effects` picks sky rendering: `minecraft:overworld` / `minecraft:the_nether` / `minecraft:the_end`. **Creation-time**: `minY`, `height`, `logicalHeight`. Travel scale is `portal.scale` — there is no `environment` scale field. Everything else here is boot-re-read.

## `seedRoll` object

The mod ignores this block entirely at runtime — it exists purely for seed rolling.
The worked examples show only fields that DO something; the tables below also
cover the parsed-but-inert ones, which are marked as such. This is the block that actually defines "what does a good seed for this dimension look like":

```json
{
  "seedRoll": {
    "skip": false,
    "mood": "adventurous",
    "spawnFilter": ["minecraft:desert", "minecraft:savanna"],
    "water": "none",
    "terrain": "islands",
    "heightRange": [-60, 440],
    "family": "overworld",
    "allowHazardousSpawn": false,
    "wants": { "village": "near_spawn", "ancient_city": "spread" },
    "shuns": ["village", "tavern"]
  }
}
```

| Field | Notes |
| --- | --- |
| `skip` | `true` excludes this dimension from rolling entirely. |
| `mood` | One of the 8 valid moods (`hard`/`adventurous`/`dramatic`/`scenic`/`pastoral`/`serene`/`desolate`/`standard`) — see `references/scoring-internals.md` for exact weights/behaviour. |
| `spawnFilter` | Biome ids the dimension is named after. GRADED, not a gate: a spawn already in one is full marks, otherwise the mark is those biomes' combined share of the world, because picking a candidate writes the position you were standing in as the spawn. A filter naming a biome that cannot occur in this family scores every seed zero — still the first thing to check when a board stays empty. |
| `spawnRadius` | **PARSED, INERT.** Declared at `DimensionConfig.java:1673` and read by nothing. Spawn biome checks use the roller's own sampling. Setting it changes no behaviour. |
| `water` | `"none"` (≤0.20) / `"high"` (0.25–0.80) / `"sea"` (≥0.50) — the fraction of columns WITH GROUND that sit at or below the generator's sea level. Nothing in the generator reads this; a dimension that needs a genuinely dry or drowned world sets `settingsOverrides.seaLevel`, which the generator does read. |
| `locateCap` | **PARSED, INERT.** `getLocateCap()` reads it and falls back to generation border + 1000, but nothing calls that getter: `LocateManager` hard-codes a 6400-block search. Setting it changes no behaviour. |
| `terrain` | Two vocabularies. Relief words — `flat`, `gently_rolling`, `rolling`, `hilly`, `mountainous`, `extreme` — score against the terrain's interquartile height spread. Ground-shape words — `islands` (5–70% of the disc carries ground) and `void` (≤10%) — score against `terrain.groundFraction`. A dimension setting neither is asked instead whether it has a floor at all. `"solid"` is NOT a recognised word. |
| `heightRange` | `[minY, maxY]` envelope the terrain should live INSIDE. Scored as the share of the terrain that exists which falls within it, so a narrow world inside a wide envelope is full marks — an envelope is a permission, not a quota. A range wide enough to contain anything (all six shipped uses declare `[-60, 440]`) discriminates nothing; make it fit the dimension. |
| `family` | `"overworld"`/`"nether"`/`"end"`/`"paradise_lost"` override of auto-detection from `type`. |
| `allowEndgameNearSpawn` | **PARSED, INERT.** Declared at `DimensionConfig.java:1685` and read by nothing. There is no endgame-near-spawn penalty to lift. To keep structures off spawn use `structures.clearSpawnRadius`, which is live. |
| `allowHazardousSpawn` | `true` withdraws BOTH spawn-safety gates — `nothing_is_immediately_lethal` (a sheer drop at the spawn column) and `spawn_is_safe_to_build_on` (lava, or nothing to stand on). For a dimension entered through a portal the mod builds the arrival itself — `PortalSite` finds an open site or carves one, lays a floor, and refuses the traversal rather than dropping somebody somewhere unopenable — so a player never steps out onto the column these measure, and for a dimension whose proposition IS danger a cliff there is scenery. Opt-out and never derived: every dimension has a portal, so deriving it would switch the gates off pack-wide in one silent step. Shipped on the 19 dimensions with `difficulty.mobMultiplier >= 2.0`, the same threshold the hard structure shift uses. |
| `wants` | **Band-name** form: short-name → `"near_spawn"` (0–15% of `borders.player`) / `"spread"` (10–75%) / `"near_border"` (55–100%). One criterion per entry, scored on the nearest instance's distance as a fraction of this dimension's own border. Different format from `structures.wants` — see main SKILL.md traps. |
| `shuns` | Bare list of short names (or map form), used when `structures.shuns` names none. Absent is full marks; present is scored by how much of the world separates a player from it, and the structure's pool weight is divided by 1.5. |

## `overworld`, `the_nether`, `the_end`, `paradise_lost`

Four dimensions among 82, managed like the rest. Every field in this document
applies to them: `seed`, `spawn`, `biomes`, `borders`, `difficulty`, `portal`,
`structures`, `structureDensity`, `settingsOverrides`, `environment` and
`seedRoll`. They are rolled, scored, bordered, portalled, scaled, shrunk and
retyped like any other. Leaving one out of a change needs a reason specific to
that dimension — a progression gate, a forced coordinate, a border invariant
([AGENTS.md § Dimensions](../../../../AGENTS.md#dimensions)).

Their filenames are **reserved**: they resolve to existing dimension ids
(`minecraft:overworld` and so on) rather than creating a new dimension, so reuse
one only when that is what you mean. The mod resolves them by **exact dimension
id**, never by namespace — `minecraft:` and `paradise_lost:` hold other mods'
dimensions too.

Their generators come from live registry entries this mod reads and rebuilds:
`minecraft:end` carries Nullscape's surface rule, `minecraft:nether` carries
Incendium's, the overworld carries Terralith's and Tectonic's. Every one of
those is composable and overridable here.

**These four name no `type`** because `DimensionConfig.getType()` already
supplies the family, and their structure groups resolve against it:

| File | Family |
| --- | --- |
| `overworld.json` | `overworld` |
| `the_nether.json` | `nether` |
| `the_end.json` | `end` |
| `paradise_lost.json` | `paradise_lost:paradise_lost` |

Writing an explicit `type` overrides that, which is how you move one onto another family's group set.

**Their `portal` block is live**: `the_nether.json`'s obsidian/flint-and-steel portal is the way to the Nether, at its configured scale, colour and sounds. `portal.scale` and `borders.player` must agree — a portal built at the source world's border divides by the scale on arrival and must land inside the destination's border. `ShippedDimensionReachabilityTest` pins that for every shipped dimension, these four included.

## `structures` — noise placement fields

Noise placement is the default for every managed dimension; these fields override it. All are creation-time-affecting (they change the world a seed generates) and all are fingerprinted by the seed roller.

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `structureDensity` | `"none"` / `"sparse"` / `"normal"` / `"dense"` | `"normal"` | Global profile for every group. `"normal"` means "use the world type's defaults", not a profile. `"none"` suppresses noise entirely; `force` still applies. |
| `structures.noise` | string, object, or `false` | type defaults | String = every group uses that profile. Object = `{group: profile}`, per group, `"none"` drops that group, unmentioned groups keep the type default. `false` = fall back to vanilla grid placement (escape hatch). |
| `structures.radial` | `{group: number[10]}` | type defaults | 10-point piecewise-linear weight curve, spawn (index 0) to border (index 9). Values 0.0–3.0. Multiplied into the noise before the threshold test, so `0.0` suppresses a band absolutely. Wrong length or out-of-range warns and falls back. **Cannot enable a group**: only `structures.noise` does that, so a curve naming a group the world type does not list is silently dead — name it under `noise` too. |
| `structures.rarity` | `{set_id: tier}` | derived from spacing | `common` / `uncommon` / `rare` / `endgame`. Changes a set's share of its group's placements, and can move it between groups — the `endgame` group requires a rare-or-rarer tier. Uses structure SET ids. |
| `structures.exclude` | `string[]` | `[]` | Structure SET ids removed from the noise pool entirely. |
| `structures.include` | `string[]` | `[]` | Structure SET ids forced into the pool, bypassing the biome filter. The escape hatch for a filter that is too aggressive. |
| `structures.force[].y` | int | (none) | Pins the placement to this height, so it needs no ground and hangs where you put it. Absent, the structure finds its own ground and declines when there is none. Required in a `void` dimension — `customdim lint` reports `force_needs_y`. See [TROUBLESHOOTING.md#t33](../../../../TROUBLESHOOTING.md#t33). |
| `structures.force[].exclusive` | boolean | `true` | Whether forcing a structure also removes it from the noise pool. Absent = true. |

**Groups:** `deco`, `settlements`, `dungeons`, `landmarks`, `maritime`, `endgame`, `loot`. Which are enabled comes from the world type — see `config/custom-dimensions/structure-type-defaults.json`, and `config/custom-dimensions/structure-groups.json` for every set's classification.

**Profiles:** `natural`, `dense`, `sparse`, `cluster`, `none`.

**Difficulty shifts apply automatically and outrank `structureDensity`:** `difficulty.mobMultiplier >= 2.0` puts `dungeons` on an even curve and `endgame` on a mid curve; `<= 0.5` suppresses both. Only an explicit per-group `structures.noise` entry overrides a shift.

**Two fields changed meaning:** `borders.player` and `difficulty.mobMultiplier` are now generation-affecting (the border sets the scanned radius AND the noise frequency scale; the multiplier drives the shifts). Editing either re-rolls the dimension.
