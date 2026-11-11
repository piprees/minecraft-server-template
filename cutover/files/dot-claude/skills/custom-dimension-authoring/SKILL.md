---
name: custom-dimension-authoring
description: Author or edit a custom-dimensions definition (schema v2, config/custom-dimensions/dimensions/<slug>.json) from a short theme prompt or a review note. Use whenever asked to create a dimension, re-author or rebalance one, change its terrain, scales, layout, biomes, biome regions and roles, zones, carvers, liquids, materials, atmosphere, structures, portals, difficulty or roll, or to find out why a definition will not compile or does not read as the place it describes. Covers the field set, the survey → targets → review loop, placeness, and the traps that fail silently. Consult it before hand-writing a definition.
---

# Custom dimension authoring

A dimension is one JSON file, `config/custom-dimensions/dimensions/<slug>.json`, schema v2. The mod compiles every definition into the world's datapack at start-up ([mod README § The pack at start-up](../../../mods/custom-dimensions-26/README.md#the-pack-at-start-up)), so a change reaches a server on its next boot, but worldgen fields reach only chunks generated after it ([D2](../../../TROUBLESHOOTING.md#d2)): on a world with chunks, see the `dimension-lifecycle-operations` skill first.

Sources of truth, in order: `mods/custom-dimensions-26/schema/definition.schema.json` (every field, type and range), [schema-v2.md](../../../docs/design/rebuild-26/schema-v2.md) (shape and meaning), [worldgen.md](../../../docs/design/rebuild-26/worldgen.md) (layers, terrain library, zones, biome library, structure control), [placeness.md](../../../docs/design/rebuild-26/placeness.md), [roller.md](../../../docs/design/rebuild-26/roller.md), [portals.md](../../../docs/design/rebuild-26/portals.md), [bridges.md](../../../docs/design/rebuild-26/bridges.md). The schema wins any disagreement.

## Principles

- **Fidelity.** A dimension keeps its 1.21.1 description and intent; `mods/custom-dimensions-26/definitions/v1/dimensions/<slug>.json` is the 1.21.1 definition, `id-mapping.csv` maps each of its biome and structure ids, and `mods/custom-dimensions-26/fidelity/intent/<slug>.md` is its intent card (the 1.21.1 description, biomes and the owner's notes).
- **Places, not perlin.** Inside the border the world reads as one coherent place or a deliberate meeting of a few: wide continents, broad ranges, big biome regions. A deliberately broken world (a gauntlet, a crucible, a wasteland) stays a noise sample.
- **Biomes are expressed, not just placed.** A listed biome that holds 1% of the map, or whose blocks never show on the surface, is not in the dimension.
- **Judge every layer.** Sky, surface, underground and roof each read as the place; show the ordinary ground and the remarkable both.

## The fields

`schemaVersion` (2) and `generator` are required.

| Field | What it sets |
| --- | --- |
| `description` | The intent every review is checked against |
| `seed`, `spawn` | A number or `"env"` (the world seed), base worlds included; `[x, y, z]`. `roll` and `/customdim pick` write `seed`. A level with chunks keeps the seed they generated from |
| `gameRules` | 26.3 game rule ids (`keep_inventory`, `mob_griefing`, a mod's `ns:rule`) to a boolean or a whole number, held by this dimension over `settings.json`'s `defaults.gameRules` and the server-wide `/gamerule`. `advance_time` is refused (the clocks are server-wide): `atmosphere.fixedTime` holds time still, `atmosphere.dayLength` sets its pace. A dimension that sets `advance_weather` keeps weather of its own |
| `generator` | `type`: `noise` (with `preset`: `overworld`, `large_biomes`, `amplified`, `caves`, `floating_islands`, `nether`, `end`; `endIsland` for the End), `checkerboard` (`cellScale`, `voidBiomes`: biomes whose cells carry no terrain), `single_biome` (`biome`, `preset`), `flat` (`layers`, `biome`), `void`, `clone` (`of`) |
| `terrain` | `base` (a library terrain: the land terrains `continents`, `erosion`, `ridges`, `terraces`, `canyons`, `rivers`, `islands`, `voids`, `shattered`, `spires`; over a void `floating_islands`, `basin_islands`; `roofed`, the only one the `caves` and `nether` presets take; `end`, the only one the `end` preset takes), `setting` (`gentle`, `default`, `extreme`), `params` (per terrain in the schema's `$defs/terrain_<base>`; `crust` on `continents` and `islands` keeps that many blocks under the ground free of noise caves), `scale` (0.25–4), `heightMultiplier` (0.25–3), `scales` (`continents`, `erosion`, `ridges`, `climate`; each 0.25–4; without a `base`, `caves`, `nether` and `end` take `climate` alone), `layout` (`radial`, `ring`, `linear`: a shape added to continentalness; on `roofed` it places the pits, on `end` it gathers the islands), `landShare` (0–1: that share of the player border is land for any seed, measured when the level loads), `floor` (`spires` only: `land`, `sea`, `void`, `islands`) |
| `biomes` | Vanilla 26.3 ids and our library's (`<namespace>:<id>`); an entry may carry a `climate` box |
| `biomeLayout` | `mode` (`regions` lays the list out as large regions), `axes`, `weights`, `roles`, `over` (`land`: rank the regions over the islands, not the void), `sea` |
| `liquids` | `seaLevel`, `lavaLevel`, `defaultFluid`, `aquifers` (`on`, `off`, `flooded`, `dry`), `basins` (`basin_islands` only) |
| `materials` | `defaultBlock`, `top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings`, per-biome `biomes` overrides, and `bands`: palettes by depth, each holding below its `below` y, deepest first, over the per-biome palettes |
| `carvers` | Vanilla carvers by name (`canyon`, `cave`, `cave_extra_underground`, `nether_cave`): `false` removes one from every biome here, `true` adds it |
| `ores` | `veins`, `add`, `remove` |
| `zones` | Shapes that replace layers inside them: `shape`, `centre`, `blend`, `edgeJitter`, `terrain`, `biomes`, `swap`, `mass`, `liquids.aquifers`, `structures` ([worldgen.md § Zones](../../../docs/design/rebuild-26/worldgen.md#zones)) |
| `dimensionType` | `minY`, `height`, `logicalHeight`, `hasSkylight`, `hasCeiling`, `infiniburn`, monster light levels |
| `atmosphere` | `skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects`, `dayLength` (`dayMinutes`, `nightMinutes`, 1–600 real minutes; not beside `fixedTime`). `settings.json`'s `defaults.atmosphere.dayLength` (45 and 15) covers every dimension with vanilla's day; every dimension on one day length shares its sun |
| `rules` | `bedsWork`, `respawnAnchorsWork`, `piglinsZombify`, `ultraWarm`, `natural`, `raids` |
| `borders` | `player` (the world border, half-width) and `generation` (pre-generation extent) |
| `difficulty` | `hostileSpawning`, `mobMultiplier`, `playerLuck`, `attributes`, `depthScaling`; defaults in `settings.json` |
| `structures` | `density`, `wants`, `shuns`, `spacing`, `mode`, `list`, `force`, `clearSpawnRadius`, `terrainAdaptation`, `noise`, `radial`, `rarity`, `exclude`, `include`, `replace` ([worldgen.md § Structure control](../../../docs/design/rebuild-26/worldgen.md#structure-control)) |
| `portals`, `exitPortal`, `exits`, `exitShrines` | Frames, igniters, colours, links and the way home ([portals.md](../../../docs/design/rebuild-26/portals.md)) |
| `deaths`, `wins` | `inventory` and `experience` keep or drop; advancements the mod grants |
| `roll` | `mood`, `spawnFilter`, `water`, `terrain`, `heightRange`, `wants`, `shuns`, `allowHazardousSpawn`, `skip` ([roller.md](../../../docs/design/rebuild-26/roller.md)); the schema refuses v1's `family`, `spawnRadius`, `locateCap` and `allowEndgameNearSpawn` |

`settings.json` beside the definitions sets `namespace`, `idleUnloadMinutes`, the frame blocks and the `defaults` merged under every definition.

## Placeness

`terrain.scales` stretches one router noise each; `terrain.layout` adds a shape to continentalness; `biomeLayout: {"mode": "regions"}` turns a biome list into regions with a share each by `weights`. A listed biome with a `climate` is bound to the land and wins over every region; one without is regional. Every biome on a coastline terrain has a role (`ocean`, `river`, `shore`, `peak`, `cave`, `land`, `wetland`) from its tags or library entry, and regional biomes are laid out per role; over a void `underside` takes the islands' undersides and `basin` the open water of `basin_islands`; `biomeLayout.roles` overrides any. `terrain.landShare` holds the land's share of the border whatever the seed. Neighbours in the list share a border: put a biome between the two it should sit between. Rules and the choice for each dimension: [placeness.md](../../../docs/design/rebuild-26/placeness.md).

A place reads as **place** when one connected region holds at least 50% of the border, **meeting** when the largest three hold at least 60% with at most eight major regions, **noise** otherwise.

## The loop: survey → targets → review

1. **Write the intent as numbers.** Start from the intent card; the target sets already written are `mods/custom-dimensions-26/fidelity/targets/<set>/<slug>.json` (the sets in `fidelity/README.md`). A targets file turns the description into dossier keys: `{"slug": "<slug>", "targets": [{"key": "surface.biomesPercent.minecraft:snowy_plains", "op": ">=", "value": 30, "from": "the sentence it comes from"}]}` (`check-targets.py`'s header lists the operators and paths).
2. **Survey.** On a dev server (`mods/custom-dimensions-26`, `./gradlew :server:runServer`): `/customdim survey <slug>` writes `customdim-survey/<slug>/dossier.json` and `dossier.md`; `/customdim survey <slug> 1 1` is the 15-second placeness-only form.
3. **Check.** `mods/custom-dimensions-client-26/scripts/check-targets.py <dossier.json> <targets.json>`: one PASS or FAIL per target; an unknown key is an error, never a pass.
4. **Look.** `pick-shots.py <dossier.json>` writes a shot list from the dossier (far view, each layer's place types, landmarks); `scripts/rig.sh biomes <list>` shoots it from a real 26.3 client. View every image.
5. **Review.** `review-packet.py` writes `review.md`: the 1.21.1 description, the v2 definition, the dossier's numbers and the shots side by side, with a computed facts table. Every claim in a review cites a dossier key or a facts row.
6. **Seed.** `/customdim render <slug> <seed> <radius> <step>` draws one seed top-down without generating chunks; `/customdim roll <slug> [count]` scores seeds against `roll` and writes a contact sheet; `/customdim pick <slug> rank <n>` writes the choice. Refused on a dimension with chunks.

## Traps

- **A climate box keeps its bounds while `scale` or `scales.climate` stretches the climate**, so the rarest bands vanish inside the border ([T174](../../../TROUBLESHOOTING.md#t174)). After any change, check every listed biome in the dossier's `placeness.biomesPercent`; re-cut boxes at the seed's quantiles ([T168](../../../TROUBLESHOOTING.md#t168)).
- **Bound boxes can shadow a regional biome** ([T213](../../../TROUBLESHOOTING.md#t213)); a region's share holds only through `customdimensions:zoned`'s regions ([T205](../../../TROUBLESHOOTING.md#t205)).
- **A layout that lowers the centre of a land terrain floods it** ([T203](../../../TROUBLESHOOTING.md#t203)); library terrains put sea level at continentalness −0.11 ([T209](../../../TROUBLESHOOTING.md#t209)).
- **A scale on a climate axis no box names changes no biome** ([T202](../../../TROUBLESHOOTING.md#t202)).
- **The survey's coarse map covers the player border** ([T204](../../../TROUBLESHOOTING.md#t204)); a biome it lists as not found may still generate ([T186](../../../TROUBLESHOOTING.md#t186)).
- **Cave biomes show at the surface in valley lakes** ([T210](../../../TROUBLESHOOTING.md#t210)); a plain-preset dimension keeps vanilla's depth ([T212](../../../TROUBLESHOOTING.md#t212)).
- **An unknown structure or biome id is a compile-report warning, not an error**; read `/customdim audit`'s `audit.md` after every change.
- **Never name a biome or structure from a mod the register cuts**; `id-mapping.csv` gives its replacement.

## Where the definitions go

Platform definitions live in `config/custom-dimensions/dimensions/` and ship in the bundle; `deploy.sh` copies them into `data/config/` on every full deploy. `/customdim create <slug> [template]` writes a new definition beside the others from a template and applies after the next restart (RB-34). The mod reads `dimensions/` and `bridges/` and resolves no overlay: a consumer's `overlay/config/custom-dimensions/` is resolved into `data/config/custom-dimensions/` by `scripts/stage-dimensions.py` (a v2 file replaces or adds, `{"overrides": {...}}` merges, `{}` removes a lazy dimension; the `consumer-repo-operations` skill's overlay contract).
