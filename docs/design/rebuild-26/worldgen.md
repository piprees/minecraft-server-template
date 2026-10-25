# Worldgen

One owner: us. Vanilla's noise generator is fast, maintained by Mojang, and on 26.3 almost entirely data; we supply the data. Each dimension picks and tunes pieces from our library and the compiler writes the result ([architecture.md](architecture.md#the-compiler)).

## Layers

| Layer | What a definition chooses | 26.x mechanism |
| --- | --- | --- |
| Terrain | Continents, erosion, ridges and peaks, plateaus and terraces, canyons, rivers, islands and archipelagos, floating islands, voids with rims, shattered land, spires, and scale and height multipliers | Our named density functions; 26.3's `distance_to_point`, `gradient`, `lerp` and `interval_select` cover islands, rims and bands in data |
| Caves | Cheese, spaghetti and noodle mixes; cave networks; a solid or hollow underground | Density functions and carvers |
| Biomes | A list of biomes with climate weights, or a hand-drawn layout; stamps by coordinates and radius; swaps | Multi-noise partitions the compiler builds without overlaps (26.4's rule); one biome source type of ours for stamps and swaps |
| Materials | `materials`: `top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings`, `defaultBlock`, and `biomes` (a palette per biome id or `#tag`) | The dimension's own `worldgen/material_rule`: the preset's bedrock and veins, each biome's palette under a `minecraft:biome` condition, the dimension's palette, then the preset's rule |
| Liquids and aquifers | `liquids`: `seaLevel`, `lavaLevel`, `defaultFluid`, `aquifers` (`on`, `off`, `flooded`, `dry`) | Noise settings' `sea_level`, `default_fluid` and top-level `aquifers` object; `lavaLevel` has no data field (the generator's -54 is code), so the runtime replaces that level's fluid rule |
| Ores | `ores`: `veins` (`true`, `false` or a list `like` copper or iron), `add` (ores as placed features), `remove` (placed feature ids) | Veins are `minecraft:ore_vein` material rules; added and removed placed features reach one dimension through the runtime's per-level biome feature lists, so no biome is copied |
| Zones | Any layer above, replaced inside a shape | A mask density function per zone; biomes, materials and structures follow the mask through our biome source |
| Atmosphere | Fog, sky, clouds, light, particles, music, day length, beds and anchors | Environment attributes, timelines and world clocks |
| Structures | Wants and shuns, density, forced starts, replacements | Our structure control over every bridged structure set |

Generator variants (M3) are starting points any definition tunes: normal, large biomes, amplified, caves, floating islands, Nether and End, plus flat with layers, checkerboard, void and single-biome. Heights are linted to Bedrock's limits.

### Terrain library

A definition picks one terrain in `terrain.base` and tunes it: `setting` (`gentle`, near vanilla; `default`; `extreme`) gives starting values, `params` overrides any of them, and `scale` (0.25–4, horizontal stretch of every terrain noise and of the biome climate) and `heightMultiplier` (0.25–3, multiplies heights about sea level) apply to all. An unknown terrain or parameter, or a value outside its range, stops the compile with the definition's slug in the message; `schema/definition.schema.json` carries each terrain's parameters with `minimum`, `maximum` and `default`.

Each terrain is built from vanilla's overworld router (`compiler/.../terrain/`). It shapes a surface height in blocks and how closely the ground follows it, and replaces vanilla's `offset` (height / 128 − 1), `factor`, `jaggedness` and `sloped_cheese`; a terrain may add a 3D term that removes ground (voids, cracks) or adds it (sky islands). Caves, aquifers, ore veins and the slides stay vanilla's, so every surface lies between y −64 and 256, inside Bedrock's overworld range. The router's continentalness, erosion, ridges, temperature and vegetation are the preset's own, stretched by `scale`, and the shoreline sits where vanilla's biomes expect the coast, so biomes follow the land. The compiler writes `<namespace>:terrain/<slug>/` with `height`, `offset`, `factor`, `jaggedness`, `depth`, `sloped_cheese`, `final_density`, `preliminary_surface_level` and `chunk_surface_level`, the ids a zone blends between, and the library's noises as `<namespace>:terrain/<noise>`.

`overworld`, `large_biomes`, `amplified` and `floating_islands` take a terrain; `checkerboard` and `single_biome` take one on overworld noise. `caves` and `nether` refuse it (their terrain is a cavern under a ceiling, not a surface), and so does `end` (its biome source reads the router's erosion as its island shape, so terrain there waits for our biome source, W2). An airborne terrain (`floating_islands`, `voids`) has no aquifers, no solid floor, and its sea level at the bottom of the world unless `liquids.seaLevel` sets one.

| Terrain | What it shapes | Its parameters (range, default) |
| --- | --- | --- |
| `continents` | Oceans and land masses from continentalness, with erosion hills. | the shared land parameters only |
| `erosion` | Broad worn basins where erosion is high, flat-floored and smooth. | `basinDepth` 0–80, 24; `basinWidth` 0.05–1, 0.4 |
| `ridges` | Mountain ranges along the ridges noise, with jagged peaks. | `peakHeight` 0–200, 70; `jaggedness` 0–1.5, 0.35; `rangeWidth` 0.05–1, 0.4 |
| `terraces` | Land stepped into flat terraces, capped by a plateau height. | `stepHeight` 3–48, 10; `riser` 0.05–0.95, 0.35; `plateauHeight` 64–256, 256 |
| `canyons` | Deep meandering cuts through the land, flat-floored and steep-walled. | `depth` 8–120, 40; `width` 0.01–0.3, 0.06; `frequency` 0.25–4, 1 |
| `rivers` | Rivers cut to just below sea level across the land, with banks. | `width` 0.01–0.25, 0.05; `depth` 1–16, 4; `bankWidth` 0.01–0.3, 0.08; `frequency` 0.25–4, 1 |
| `floating_islands` | Islands in the sky over a void: domed tops, tapering undersides. | `altitude` 40–240, 150; `thickness` 4–64, 18; `underside` 4–120, 40; `coverage` 0.02–0.8, 0.3; `size` 0.25–4, 1; `spread` 0–80, 24 |
| `islands` | An ocean of islands and archipelagos. | `coverage` 0.02–0.7, 0.2; `size` 0.25–4, 1; `islandHeight` 4–120, 28; `archipelago` 0–1, 0.4 |
| `voids` | Land broken by bottomless voids, each ringed by a raised rim. | `coverage` 0.02–0.5, 0.1; `size` 0.25–4, 1; `rimHeight` 0–60, 14; `rimWidth` 0.01–0.3, 0.06 |
| `shattered` | Land split by 3D cracks, with overhangs and loose floating shards. | `crackWidth` 0.01–0.3, 0.06; `crackScale` 0.25–4, 1; `lift` 0–1, 0.5 |
| `spires` | Tall stone columns rising from the land. | `spireHeight` 16–200, 70; `coverage` 0.005–0.3, 0.04; `thickness` 0.25–4, 1; `taper` 0–1, 0.5 |

Every terrain starts from the shared land, which each terrain may start at its own defaults:

| Shared parameter | Range, default | What it does |
| --- | --- | --- |
| `coast` | -0.6–0.4, -0.15 | Continentalness at the shoreline; higher is more ocean |
| `seaFloor` | -40–60, 36 | Height of the open ocean floor |
| `landHeight` | 64–200, 80 | Height of the land far inland |
| `relief` | 0–120, 24 | Blocks of hills where erosion is low |
| `roughness` | 0–1, 0.5 | How far 3D noise breaks the surface; 0 smooth, 1 overhangs |

v1's `adventure:wide` can be written as `continents` with `scale` 2.5, `relief` 16 and `roughness` 0.3; `adventure:compressed` as `continents` with `scale` 0.6, `relief` 90, `roughness` 0.7 and `heightMultiplier` 1.3. The gallery fixtures in `mods/custom-dimensions-26/server/src/selftest/gallery/` show every terrain at its three settings.

## Zones

A zone replaces any layer inside a shape — circle, ring, box, sector or polygon — centred on spawn or a point, with a blend width and a jittered edge so borders read as landscape, not compass lines. Zones stack in order; outside them the base applies. They generalise the 1.21.1 `biomePatches`, which stamp biomes by shape with a blend, to every layer.

- **Mask:** circles and rings use `distance_to_point` and `lerp`; the other shapes use one density-function type of ours, so the mask is exact and cheap.
- **Blending:** terrain and aquifers through the noise router; biomes in our biome source; materials following biomes; ores through the zone's biomes; structure wants and shuns scoped to the zone.
- **Liquids:** sea level stays one number per dimension; a zone goes dry or flooded through its aquifer settings and terrain height.
- **Spawn-relative zones** resolve after spawn is chosen, and the definition chooses spawn ([T31](../../../TROUBLESHOOTING.md#t31)), so the ring is always where the definition says.

Normal worldgen everywhere, except a ring of shattered sky islands between 2,000 and 3,000 blocks from spawn. Field names are illustrative; F3 fixes the schema.

```json
"worldgen": { "terrain": "adventure:continents", "biomes": "adventure:temperate" },
"zones": [
  { "shape": { "ring": { "centre": "spawn", "inner": 2000, "outer": 3000 } },
    "blend": 200, "edgeJitter": 64,
    "terrain": "adventure:floating_islands",
    "biomes": ["adventure:cloud_meadow", "minecraft:cherry_grove"],
    "liquids": { "aquifers": false },
    "structures": { "wants": ["#adventure:sky_ruins"] } }
]
```

If the F0 spike shows the blend cannot look natural in data alone, every shape uses our density-function type with a wider blend.

## Lessons the kit is built around

- Jigsaw sizes are not footprints: structure sizes are measured, never read from configs; `structure-sizes.json` is re-measured on 26.3 (W4, [T54](../../../TROUBLESHOOTING.md#t54)).
- Dense terrain features vanish into the structure pool, so terrain is shaped by noise, not features ([T55](../../../TROUBLESHOOTING.md#t55)).
- End-style dimensions sample climate at depth; the compiler records the sampling window with each layout ([T76](../../../TROUBLESHOOTING.md#t76)).
- First-time generation runs one dimension at a time; the level manager enforces it ([K6](../../../TROUBLESHOOTING.md#k6)).
- All worldgen is creation-time: a definition's worldgen fields are fingerprinted, and a change on a live world is refused unless the dimension is being reset ([D2](../../../TROUBLESHOOTING.md#d2)).

## Clean-room rule

Nobody, human or agent, reads Terralith, Incendium, Nullscape, Structory or any other Stardust Labs files while building the kit. Their licence forbids copying parts into another mod and forbids using their work with AI systems. We describe what we want in our own words and build it from vanilla's functions. The inspiration list for our biomes (W7) is Regions Unexplored, Nature's Spirit, Wilder Wild, BetterEnd, BetterNether, Galosphere, YUNG's caves, Underground Worlds and Paradise Lost.

## Firewall and bridges

Any mod may be installed; another worldgen owner may not. At SERVER_STARTING, before any level exists, the mod audits the loaded game and refuses to start, listing each finding as `mod: what where`, if any of these appear without a reviewed bridge:

- a dimension, dimension type or noise setting in a namespace other than `minecraft`, `adventure`, `customdimensions`, `fantasy` and the definitions' own, or a biome source or chunk generator type registered outside them;
- a mixin into worldgen classes from a mod not on the allow list;
- TerraBlender, Biolith or Lithostitched.

Worldgen classes are `net.minecraft.world.level.levelgen..`, `..level.biome..`, `..level.chunk.ChunkGenerator*`, `..level.chunk.status..`, `..level.dimension..` and `net.minecraft.data.worldgen..`, except the runtime classes `PatrolSpawner`, `PhantomSpawner`, `Heightmap`, `Biome`, `MobSpawnSettings` and `EnderDragonFight` (`core/.../firewall/WorldgenClasses`). Mixin targets are read from each mod's `fabric.mod.json`, its mixin configs and each mixin's `@Mixin` annotation.

The allow list (`core/.../firewall/AllowList`):

| Mod | Why |
| --- | --- |
| `c2me*`, `lithium`, `servercore` | Performance; same output |
| `fabric-*` | Fabric API; its hooks act only for mods that call them |
| `customdimensions`, `fantasy` | This mod and its runtime-level library |
| `distanthorizons` | Its LOD hook reads terrain; it does not shape it |
| `moogs_structures` | The library the Moog structure packs need; replaces vanilla structures its config names and adapts terrain around structures |
| `carpet` | Admin tool and the self-tests' fake players; its worldgen hooks sit behind rules that are off by default |

A structure set outside those namespaces and in no reviewed bridge is switched off: it never enters a level's structure state, so it places nothing and `/locate` cannot find it. Placed features and spawns that another mod adds to a vanilla biome, compared with vanilla's own biome file, are removed; each removal is logged once with its biome count. `/customdim audit` lists the allowed mods, unreviewed bridges, switched-off sets and removals.

A bridge ([bridges.md](bridges.md)) is one JSON file per mod in `config/custom-dimensions/bridges/`. It adopts that mod's structure sets and structures (with measured sizes and our tags for mood, climate and depth), features, spawns, reviewed mixin configs and registry entries, and once content starts, its blocks' Polymer and Bedrock mappings. `:core:bridge` writes a first, unreviewed bridge from a structure mod's jar for a human to tag.
