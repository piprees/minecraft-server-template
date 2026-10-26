# Definition schema v2

What a dimension definition looks like in the rebuild, how each v1 field maps
across, and what the F3 converter does. Field names are fixed here; later work
packages add fields inside the layer objects (W1 terrain, W3 materials, liquids
and ores) without renaming these.

## Where definitions live

Until the switch: `mods/custom-dimensions-26/definitions/<slug>.json`, plus
`settings.json` beside them. The 1.21.1 files in `config/custom-dimensions/`
stay untouched and keep running production; the converter reads them and
writes the v2 set. At the switch the v2 set moves to
`config/custom-dimensions/dimensions/`. The roller's `<slug>_thumb.json` and
`.png` files are not definitions and are not converted.

## Shape

```jsonc
{
  "schemaVersion": 2,
  "description": "…",
  "seed": 12345,                       // or "env"
  "spawn": [0, 64, 0],
  "generator": { "type": "noise", "preset": "overworld" },
  "terrain": { "base": "canyons", "setting": "extreme", "params": { "depth": 80 }, "scale": 1.5 },  // null base = the preset's own
  "biomes": ["minecraft:plains", { "id": "…", "climate": { … } }],
  "liquids": { "seaLevel": 63, "lavaLevel": -54, "defaultFluid": "minecraft:water", "aquifers": "on" },
  "materials": { "defaultBlock": "minecraft:stone", "top": "minecraft:moss_block", "biomes": { "#minecraft:is_badlands": { "top": "minecraft:red_sand" } } },
  "ores": { "veins": true, "add": [ { "id": "gilded", "block": "minecraft:gold_ore", "count": 8, "minY": -32, "maxY": 32 } ], "remove": ["minecraft:ore_diamond"] },
  "zones": [ … ],
  "dimensionType": { "minY": -64, "height": 384, "hasCeiling": false, … },
  "atmosphere": { "skyColor": "#4A2C6B", "fogColor": "#2A1A3E", "ambientLight": 0.3, "fixedTime": 18000, "effects": "minecraft:the_end" },
  "rules": { "bedsWork": false, "respawnAnchorsWork": true, "piglinsZombify": true, "ultraWarm": false, "natural": false },
  "borders": { "player": 8192, "generation": 8192 },
  "difficulty": { … },                 // unchanged from v1
  "structures": { "density": "normal", … },
  "portals": [ { … } ],                // always an array
  "exitPortal": { … }, "exits": { … }, "exitShrines": { … },
  "deaths": { "inventory": "keep", "experience": "drop" },
  "wins": [ { "id": "summit", "trigger": { "type": "reach", "pos": [0, 120, 0], "radius": 3 }, "title": "Summit", … } ],
  "roll": { … }
}
```

## Field map

| v1 | v2 | Notes |
| --- | --- | --- |
| *(none)* | `schemaVersion: 2` | Required. A file without it is v1 and is refused by the 26.3 mod. |
| `description`, `seed`, `spawn`, `borders`, `difficulty` | same | Unchanged. |
| `type` | `generator` | See the type table below. |
| `noiseSettings` | `terrain.base` | `adventure:wide` and `adventure:compressed` are built from Terralith and Tectonic data, so they convert to `null` and are reported for W8. `adventure:void` converts to `generator.type: "void"`. `terrain` holds `base` (a library terrain, or null for the preset's own), `setting` (`gentle`, `default` or `extreme`), `params` (that terrain's parameters, each with a range) and `scale` and `heightMultiplier`; the library and its parameters are in [worldgen.md](worldgen.md#terrain-library). |
| `biomes` | `biomes` | Object entries' `parameters` become `climate`. Ids from cut mods are kept and reported; W8 replaces them. |
| `checkerboardScale` | `generator.cellScale` | `checkerboard` only. |
| `layers`, `flatBiome` | `generator.layers`, `generator.biome` | `flat` only. |
| `settingsOverrides.seaLevel`, `.defaultFluid` | `liquids.seaLevel`, `liquids.defaultFluid` | `liquids.lavaLevel` and `liquids.aquifers` have no v1 source; see [Materials, liquids and ores](#materials-liquids-and-ores). |
| `settingsOverrides.defaultBlock` | `materials.defaultBlock` | 26.4 removes `default_block` from noise settings; the compiler emits it as the material rules' fallback. |
| `settingsOverrides.endIsland` | `generator.endIsland` | `end` preset only. |
| `biomePatches` | `zones` | Each patch becomes a circle zone that sets `biomes` only: `x`/`z` → `centre`, `radius`, `blend`, `shape` kept. |
| `environment` creation-time fields (`minY`, `height`, `logicalHeight`, `hasSkylight`, `hasCeiling`, `infiniburn`, `monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit`) | `dimensionType` | 26.x dimension-type fields. |
| `environment.skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects` | `atmosphere` | The compiler writes environment attributes and a timeline. |
| `environment.bedWorks`, `respawnAnchorWorks`, `piglinSafe`, `ultraWarm`, `natural`, `hasRaids` | `rules` | Renamed to read as rules: `bedsWork`, `respawnAnchorsWork`, `piglinsZombify` (= not `piglinSafe`), `ultraWarm`, `natural`, `raids`. |
| `structureDensity` | `structures.density` | |
| `structures` | `structures` | Carried whole for W4: `wants`, `shuns`, `spacing`, `mode`, `list`, `force`, `clearSpawnRadius`, `terrainAdaptation`, `noise`, `radial`, `rarity`, `exclude`, `include`. `shuns` values are always `{}`. |
| `portal` (object or array) | `portals` (array) | Each entry unchanged except `immersive` → `preview` (companion tuning; the vanilla slab is gone). |
| `exitPortal`, `exits`, `exitShrines` | same | Unchanged. |
| *(none)* | `deaths` | `inventory` and `experience`, each `keep` or `drop`; absent, the `keepInventory` game rule decides (RB-31). |
| *(none)* | `wins` | Advancements the mod grants (RB-30); see below. |
| `seedRoll` | `roll` | `mood`, `spawnFilter`, `water`, `terrain`, `heightRange`, `wants`, `shuns`, `allowHazardousSpawn`, `skip`. `family` is dropped: the generator decides it. |

**Dropped** (parsed and inert in v1, or legacy): `dimensionId`, top-level
`hostileSpawning`, top-level `scale`, `structures.endgame`, `shuns` distances,
`seedRoll.spawnRadius`, `seedRoll.locateCap`, `seedRoll.allowEndgameNearSpawn`,
`inputHash`. The converter reports each one it drops.

## Materials, liquids and ores

| Field | Meaning |
| --- | --- |
| `liquids.seaLevel` | `sea_level`: open cells below it hold `defaultFluid`. Above the dimension type's top is a compile error; below its floor is reported (the dimension has no sea). |
| `liquids.lavaLevel` | Open cells below the lower of it and sea level are lava; the generator's own level is -54. Outside the dimension type's range is a compile error. The runtime applies it. |
| `liquids.defaultFluid` | `default_fluid`. |
| `liquids.aquifers` | `on`: the preset's `aquifers` object (overworld, large biomes and amplified have one; any other preset is a compile error). `off`: no `aquifers` object, so every open cell below sea level holds `defaultFluid`. `flooded`: the same, and air as the fluid is an error. `dry`: no `aquifers` object, `default_fluid` air, and the runtime removes `minecraft:spring_water` and `minecraft:dripstone_cluster`. Absent: the preset's own. |
| `materials.defaultBlock` | `default_block` on 26.3; the material rule's last entry on 26.4. |
| `materials.top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings` | Blocks for the floor block not under water, the surface depth below a floor, every other solid block, steep floors, floors from sea level − 4 to + 2, and the blocks over an open space. Floor fields are gated on `minecraft:above_preliminary_surface` where the preset's rule is, so cave floors keep vanilla's. |
| `materials.biomes` | A palette per biome id or `#tag`, checked before the dimension's palette; a field it leaves unset falls through to the dimension's palette, then vanilla. |
| `ores.veins` | `true` or absent keeps the preset's copper and iron veins, `false` removes them, a list replaces them: `like` (`copper` or `iron`, whose shape and heights it takes), `ore`, `rawOre` (default `ore`), `filler`, `rawOreChance`. |
| `ores.add` | `id`, `block`, `deepBlock` (on deepslate; default `block`), `size` (default 8), `count` per chunk, `minY`, `maxY`, `shape` (`uniform` or `trapezoid`), `airExposure` (default 0). Each becomes `<ns>:ores/<slug>/<id>`, replacing stone and deepslate ore replaceables and the palette's own blocks, added to every biome's underground-ores step in that dimension only. |
| `ores.remove` | Placed feature ids taken out of every biome in that dimension only; an unknown `minecraft:` id is a compile error. |

Errors name the slug and the field (`the_x: ores.add[1].id: ...`). A flat or
void generator reports palettes and ores as unsupported.

## Deaths and wins

`deaths` decides what a player who dies in the dimension keeps (RB-31).
Kept items and levels return on respawn; dropped ones fall where vanilla
drops them, whatever the game rule says.

Each `wins` entry is one advancement, `<namespace>:wins/<slug>/<id>`, in a
single `<namespace>:wins/root` tab (RB-30). Its criteria are
`minecraft:impossible`; the mod grants them, so a vanilla client shows the
toast and the tab, and the advancement is the once-per-player record.

| Field | Meaning |
| --- | --- |
| `id` | `[a-z0-9_]+`, unique in the definition. Required. |
| `trigger.type` | `enter` the dimension; `reach` within `radius` (default 3) of `pos`; `kill` `entity` (id or `#tag`) `count` times; `obtain` `count` of `item` (id or `#tag`) at once; `survive` `ticks` in the dimension without dying or leaving; earn the vanilla `advancement` while there. |
| `title`, `description` | The advancement's text. `title` is required; `description` defaults to the title. |
| `icon` | An item id; default `minecraft:nether_star`. |
| `frame` | `task`, `goal` (default) or `challenge`. |
| `message` | A key in `config/messages.json`, broadcast when the win fires with `{player}`, `{dimension}` (the slug) and `{win}` (the title); default `win.earned`. |
| `exit` | Where the player goes next, in the exit-target grammar (`bed`, `worldSpawn`, `origin`, `{"dimension": …, "arrival": …}`). |

A `kill` win with a `count` above 1 gets one criterion per kill, so the
advancement's own progress counts the kills. The converter has no v1 source
for either field, so no shipped definition carries them; the self-test's
fixtures in `server/src/selftest/definitions/` do.

## Generator types

| v1 `type` | v2 `generator` |
| --- | --- |
| `overworld`, `multi_biome` | `noise`, preset `overworld` |
| `large_biomes` | `noise`, preset `large_biomes` |
| `amplified` | `noise`, preset `amplified` |
| `cave` | `noise`, preset `caves` |
| `sky_islands` | `noise`, preset `floating_islands` |
| `nether` | `noise`, preset `nether` |
| `end` | `noise`, preset `end` |
| `nether_islands` | `noise`, preset `end`, Nether biomes |
| `void` | `void` |
| `superflat` | `flat` |
| `checkerboard` | `checkerboard` |
| `single_biome` | `single_biome` (`generator.biome`) |
| `ns:path` (a clone) | `clone`, `of: "ns:path"`; `paradise_lost:paradise_lost` becomes `of: "adventure:the_aviary"` (a rewrite in the report), since The Aviary replaces it |
| absent (a base world) | the base world's own preset |

## Base worlds

`overworld`, `the_nether` and `the_end` keep their slugs. `paradise_lost.json`
converts to `the_aviary.json` (`adventure:the_aviary`), preset
`floating_islands`, and its Paradise Lost biome ids are reported.

## The converter

A Gradle task in the compiler module, `./gradlew :compiler:convertDefinitions`:
it reads every v1 definition through the carried v1 loader and writes the v2
set and `conversion-report.md` beside it. It is repeatable: the 1.21.1 files
keep changing until the switch, and each run rewrites the v2 set from them.

The report lists, per file: every id whose namespace belongs to a mod the
register cuts, waits on or rebuilds (from `mod-register.csv` and the jar
namespaces in `namespaces.csv`; a namespace is flagged when none of the mods owning it is kept), every dropped field, and every field that
needs a decision in W8 (`terrain.base: null` where v1 named a preset, clones
of cut dimensions). Totals at the top. Its counts are checked against a grep of
the v1 files.

## Converter rules

- It converts each file's own JSON, so `settings.json` defaults are not baked into every file, and it refuses a file the v1 loader can't load.
- An unknown v1 field stops the conversion with an error naming it (top level, `settingsOverrides`, `environment`, biome patches and biome objects); `structures` and `seedRoll` pass through.
- Flagging scans object keys as well as string values (`structures.rarity` and `terrainAdaptation` key on ids).
- `settingsOverrides.endIsland` is kept only with the `end` preset; elsewhere it is a no-op and is dropped and reported.
- Shun distances are dropped and reported; list-form `roll.shuns` stays a list.
- A zone converted from a biome patch is `{shape (default "circle"), centre: [x, z], radius, blend, biomes: [id]}`; every zone field is in [worldgen.md § Zones](worldgen.md#zones).
- Output is deterministic: two-space indent, number and boolean arrays on one line, numbers keep their source text, stale files are deleted.

## Clones

A clone takes the generator, biome list and terrain of the definition its
`of` names, and keeps its own seed, spawn, dimension type, atmosphere, rules
and everything else. Its own `liquids` and `materials` apply over the
source's, field by field; its own `biomes` are not used, and the compile
report says so. The compiler resolves `of` to a slug: `paradise_lost:paradise_lost`
to `the_aviary`, a base world's level stem (`minecraft:overworld`,
`minecraft:the_nether`, `minecraft:the_end`) to its slug, and
`<namespace>:<slug>` to that definition. A clone of a clone follows the chain;
a chain that returns to a slug is a compile error naming it
(`clone cycle: a -> b -> a`). A clone whose `of` no definition provides (a
dimension of a cut mod) is reported and not compiled.

## Validation

`schema/definition.schema.json` (JSON Schema 2020-12) describes v2. Editors
autocomplete from it, and a core test validates every converted file against
it. The loader rejects a v1 file with a message naming the converter.

The compile report lints heights to Bedrock's limits (requirement R2). Geyser
shows a dimension type outside the `minecraft` namespace as the Bedrock
dimension its `skybox` names (`none` the Nether, `end` the End, anything else
the Overworld), and Bedrock builds from y -64 to 320 in the Overworld, 0 to 128
in the Nether and 0 to 256 in the End ([`JavaDimension`](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/level/JavaDimension.java),
[`BedrockDimension`](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/level/BedrockDimension.java)).
A type whose `min_y` is below its range, or whose top is above it (not
checked for the Nether, as Geyser does not), is listed under "Bedrock" with a
warning; the compile does not fail.
