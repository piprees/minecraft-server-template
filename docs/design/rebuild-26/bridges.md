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

## Writing a first bridge

```bash
./gradlew :core:bridge -Pjar=<structure mod jar or datapack zip> -Pout=<dir> [-Pmod=<id>]
```

It writes `<dir>/<mod>.json` from the archive: its structure sets, each
structure with the set that places it, and its placed features outside
`minecraft`. Sizes and tags are empty, `mixins` and `registries` are never
filled, and `reviewed` is `false`. A human tags it, drops what should not
place, sets `reviewed`, and copies it into `config/custom-dimensions/bridges/`.
