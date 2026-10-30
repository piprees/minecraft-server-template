# Bridges

A bridge is one JSON file per mod in `config/custom-dimensions/bridges/<mod>.json`,
named after the mod's Fabric id. It says what of that mod's worldgen this pack
adopts. The firewall ([worldgen.md](worldgen.md#firewall-and-bridges)) reads every
bridge at start-up; only a bridge with `"reviewed": true` adopts anything.
Schema: [`schema/bridge.schema.json`](../../../mods/custom-dimensions-26/schema/bridge.schema.json);
reader: `core/.../firewall/BridgeReader`.

| Field | What it holds |
| --- | --- |
| `mod` | The mod id; must match the file name |
| `reviewed` | `true` once a human has tagged it. A generated bridge is `false` and adopts nothing; the start-up log warns about it |
| `structureSets` | Structure sets that keep placing. Every other set outside `minecraft`, `adventure`, `customdimensions`, `fantasy` and the definitions' namespace is switched off |
| `structures` | One entry per structure: `id`, the `set` that places it, `size` `{x, y, z}` in blocks (null until measured, [T54](../../../TROUBLESHOOTING.md#t54)), and our tags `mood`, `climate`, `depth` |
| `features` | Placed features the mod adds to vanilla biomes that stay; any other addition is removed |
| `spawns` | Entity types the mod adds to vanilla biome spawns that stay |
| `mixins` | The mod's mixin configs reviewed and allowed into worldgen classes |
| `registries` | The mod's dimensions, dimension types, noise settings, biome source or chunk generator types that are allowed |

Tags:

| Tag | Values |
| --- | --- |
| `mood` | `hard`, `adventurous`, `dramatic`, `scenic`, `pastoral`, `serene`, `desolate`, `standard` (the definitions' `roll.mood`) |
| `climate` | `frozen`, `cold`, `temperate`, `warm`, `hot`, `arid`, `wet` |
| `depth` | `sky`, `surface`, `underground`, `deep`, `underwater` |

## The committed bridges

`mods/custom-dimensions-26/bridges/` holds one reviewed bridge for every mod and
datapack on the 26.3 list whose structure sets, placed features or biome spawns
the firewall would otherwise switch off or remove. Each is the generator's output
with `"reviewed": true`, sizes null and tags empty: everything it lists is adopted
as-is and places exactly as it does without the firewall. W4 fills sizes and tags,
and may drop sets per dimension. A bridge for a mod that is not installed adopts
ids nobody registers and changes nothing.

| Bridge | Archive | Hand-added |
| --- | --- | --- |
| `mr_ati_structuresfabricforge` | ATi Structures | |
| `mr_epic_structuresdungeons` | Epic Structures: Dungeons | |
| `mr_deadly_deadlydungeon` | Deadly Deadly Dungeon | |
| `mr_dungeons_andtaverns` | Dungeons and Taverns | `nova_structures:deepslate_camp`, `villages_birch`, `villages_jungle`, `villages_swamp`: sets the pack's `config/datapacks/structures/` adds in the mod's namespace |
| `t_and_t` | Towns and Towers | |
| `mes`, `mns`, `mss`, `mtr`, `mvs` | Moog's End, Nether, Soaring, Temples and Voyager structures | |
| `farmersdelight` | Farmer's Delight Refabricated | |
| `clucking-ducks` | the clucking-ducks datapack (its Modrinth slug) | `minecraft:chicken` in `spawns`: the duck is a chicken variant its `minecraft:river` override spawns |

`core`'s `ShippedBridgesTest` checks every file against the schema and the reader,
and that together they adopt every id in `firewall/unbridged-26.3.txt`, what the
firewall takes from the 26.3 list's smoke stack with no bridge installed.
`runServer` reads this folder; `-Pbridges=<dir>` reads another.

## Writing a first bridge

```bash
./gradlew :core:bridge -Pjar=<structure mod jar or datapack zip> -Pout=<dir> [-Pmod=<id>]
```

It writes `<dir>/<mod>.json` from the archive: its structure sets, each
structure with the set that places it, and its placed features outside
`minecraft`. Sizes and tags are empty, `mixins` and `registries` are never
filled, and `reviewed` is `false`. A human tags it, drops what should not
place, sets `reviewed`, and copies it into `config/custom-dimensions/bridges/`.
