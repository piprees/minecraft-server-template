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
  "terrain": { "base": null },         // W1 fills it; null = the preset's own terrain
  "biomes": ["minecraft:plains", { "id": "…", "climate": { … } }],
  "liquids": { "seaLevel": 63, "defaultFluid": "minecraft:water" },
  "materials": { "defaultBlock": "minecraft:stone" },   // W3 adds palettes
  "zones": [ … ],
  "dimensionType": { "minY": -64, "height": 384, "hasCeiling": false, … },
  "atmosphere": { "skyColor": "#4A2C6B", "fogColor": "#2A1A3E", "ambientLight": 0.3, "fixedTime": 18000, "effects": "minecraft:the_end" },
  "rules": { "bedsWork": false, "respawnAnchorsWork": true, "piglinsZombify": true, "ultraWarm": false, "natural": false },
  "borders": { "player": 8192, "generation": 8192 },
  "difficulty": { … },                 // unchanged from v1
  "structures": { "density": "normal", … },
  "portals": [ { … } ],                // always an array
  "exitPortal": { … }, "exits": { … }, "exitShrines": { … },
  "roll": { … }
}
```

## Field map

| v1 | v2 | Notes |
| --- | --- | --- |
| *(none)* | `schemaVersion: 2` | Required. A file without it is v1 and is refused by the 26.3 mod. |
| `description`, `seed`, `spawn`, `borders`, `difficulty` | same | Unchanged. |
| `type` | `generator` | See the type table below. |
| `noiseSettings` | `terrain.base` | `adventure:wide` and `adventure:compressed` are built from Terralith and Tectonic data, so they convert to `null` and are reported for W8. `adventure:void` converts to `generator.type: "void"`. |
| `biomes` | `biomes` | Object entries' `parameters` become `climate`. Ids from cut mods are kept and reported; W8 replaces them. |
| `checkerboardScale` | `generator.cellScale` | `checkerboard` only. |
| `layers`, `flatBiome` | `generator.layers`, `generator.biome` | `flat` only. |
| `settingsOverrides.seaLevel`, `.defaultFluid` | `liquids.seaLevel`, `liquids.defaultFluid` | |
| `settingsOverrides.defaultBlock` | `materials.defaultBlock` | 26.4 removes `default_block` from noise settings; the compiler emits it as the material rules' fallback. |
| `settingsOverrides.endIsland` | `generator.endIsland` | `end` preset only. |
| `biomePatches` | `zones` | Each patch becomes a circle zone that sets `biomes` only: `x`/`z` → `centre`, `radius`, `blend`, `shape` kept. |
| `environment` creation-time fields (`minY`, `height`, `logicalHeight`, `hasSkylight`, `hasCeiling`, `infiniburn`, `monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit`) | `dimensionType` | 26.x dimension-type fields. |
| `environment.skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects` | `atmosphere` | The compiler writes environment attributes and a timeline. |
| `environment.bedWorks`, `respawnAnchorWorks`, `piglinSafe`, `ultraWarm`, `natural`, `hasRaids` | `rules` | Renamed to read as rules: `bedsWork`, `respawnAnchorsWork`, `piglinsZombify` (= not `piglinSafe`), `ultraWarm`, `natural`, `raids`. |
| `structureDensity` | `structures.density` | |
| `structures` | `structures` | Carried whole for W4: `wants`, `shuns`, `spacing`, `mode`, `list`, `force`, `clearSpawnRadius`, `terrainAdaptation`, `noise`, `radial`, `rarity`, `exclude`. `shuns` values are always `{}`. |
| `portal` (object or array) | `portals` (array) | Each entry unchanged except `immersive` → `preview` (companion tuning; the vanilla slab is gone). |
| `exitPortal`, `exits`, `exitShrines` | same | Unchanged. |
| `seedRoll` | `roll` | `mood`, `spawnFilter`, `water`, `terrain`, `heightRange`, `wants`, `shuns`, `allowHazardousSpawn`, `skip`. `family` is dropped: the generator decides it. |

**Dropped** (parsed and inert in v1, or legacy): `dimensionId`, top-level
`hostileSpawning`, top-level `scale`, `structures.endgame`, `shuns` distances,
`seedRoll.spawnRadius`, `seedRoll.locateCap`, `seedRoll.allowEndgameNearSpawn`,
`inputHash`. The converter reports each one it drops.

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
| `ns:path` (a clone) | `clone`, `of: "ns:path"`; `paradise_lost:paradise_lost` is reported, since that mod is cut |
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

## Validation

`schema/definition.schema.json` (JSON Schema 2020-12) describes v2. Editors
autocomplete from it, and a core test validates every converted file against
it. The loader rejects a v1 file with a message naming the converter.
