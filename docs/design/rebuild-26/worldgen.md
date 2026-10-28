# Worldgen

One owner: us. Vanilla's noise generator is fast, maintained by Mojang, and on 26.3 almost entirely data; we supply the data. Each dimension picks and tunes pieces from our library and the compiler writes the result ([architecture.md](architecture.md#the-compiler)).

## Layers

| Layer | What a definition chooses | 26.x mechanism |
| --- | --- | --- |
| Terrain | Continents, erosion, ridges and peaks, plateaus and terraces, canyons, rivers, islands and archipelagos, floating islands, voids with rims, shattered land, spires, and scale and height multipliers | Our named density functions; 26.3's `distance_to_point`, `gradient`, `lerp` and `interval_select` cover islands, rims and bands in data |
| Caves | Cheese, spaghetti and noodle mixes; cave networks; a solid or hollow underground | Density functions and carvers |
| Biomes | A list of biomes with climate boxes; stamps by shape; swaps inside a zone or everywhere | Multi-noise layouts the compiler builds without overlaps (26.4's rule); our biome source `customdimensions:zoned` for zones and swaps |
| Materials | `materials`: `top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings`, `defaultBlock`, and `biomes` (a palette per biome id or `#tag`) | The dimension's own `worldgen/material_rule`: the preset's bedrock and veins, each biome's palette under a `minecraft:biome` condition, the dimension's palette, then the preset's rule |
| Liquids and aquifers | `liquids`: `seaLevel`, `lavaLevel`, `defaultFluid`, `aquifers` (`on`, `off`, `flooded`, `dry`) | Noise settings' `sea_level`, `default_fluid` and top-level `aquifers` object; `lavaLevel` has no data field (the generator's -54 is code), so the runtime replaces that level's fluid rule |
| Ores | `ores`: `veins` (`true`, `false` or a list `like` copper or iron), `add` (ores as placed features), `remove` (placed feature ids) | Veins are `minecraft:ore_vein` material rules; added and removed placed features reach one dimension through the runtime's per-level biome feature lists, so no biome is copied |
| Zones | Terrain, biomes, aquifers and structure wants replaced inside a shape | A mask density function per zone; terrain and aquifers lerp in the router; biomes, materials and ores follow it through our biome source |
| Atmosphere | Fog, sky, clouds, light, particles, music, day length, beds and anchors | Environment attributes, timelines and world clocks |
| Structures | `structures`: density, noise groups, wants and shuns, forced starts, replacements, spacing, mode, terrain adaptation | The compiler's `structure_control/<slug>.json`, applied to the level's structure state over every set the firewall keeps ([below](#structure-control)) |

Generator variants (M3) are starting points any definition tunes: normal, large biomes, amplified, caves, floating islands, Nether and End, plus flat with layers, checkerboard, void and single-biome. Heights are linted to Bedrock's limits.

### Terrain library

A definition picks one terrain in `terrain.base` and tunes it: `setting` (`gentle`, near vanilla; `default`; `extreme`) gives starting values, `params` overrides any of them, and `scale` (0.25–4, horizontal stretch of every terrain noise and of the biome climate) and `heightMultiplier` (0.25–3, multiplies heights about sea level) apply to all. An unknown terrain or parameter, or a value outside its range, stops the compile with the definition's slug in the message; `schema/definition.schema.json` carries each terrain's parameters with `minimum`, `maximum` and `default`.

Each terrain is built from vanilla's overworld router (`compiler/.../terrain/`). It shapes a surface height in blocks and how closely the ground follows it, and replaces vanilla's `offset` (height / 128 − 1), `factor`, `jaggedness` and `sloped_cheese`; a terrain may add a 3D term that removes ground (voids, cracks) or adds it (sky islands). Caves, aquifers, ore veins and the slides stay vanilla's, so every surface lies between y −64 and 256, inside Bedrock's overworld range. The router's continentalness, erosion, ridges, temperature and vegetation are the preset's own, stretched by `scale`, and the shoreline sits where vanilla's biomes expect the coast, so biomes follow the land. The compiler writes `<namespace>:terrain/<slug>/` with `height`, `offset`, `factor`, `jaggedness`, `depth`, `sloped_cheese`, `final_density`, `preliminary_surface_level` and `chunk_surface_level`, the ids a zone blends between, and the library's noises as `<namespace>:terrain/<noise>`.

`overworld`, `large_biomes`, `amplified` and `floating_islands` take a terrain; `checkerboard` and `single_biome` take one on overworld noise. `caves` and `nether` refuse it (their terrain is a cavern under a ceiling, not a surface), and so does `end` (`minecraft:the_end` reads the router's erosion as its island shape, which a terrain replaces). An airborne terrain (`floating_islands`, `voids`) has no aquifers, no solid floor, and its sea level at the bottom of the world unless `liquids.seaLevel` sets one.

| Terrain | What it shapes | Its parameters (range, default) | Settings: what they look like, and what they set |
| --- | --- | --- | --- |
| `continents` | Oceans and land masses from continentalness, with erosion hills. | the shared land parameters only | gentle: low land and soft hills; default: rolling land with oceans; extreme: high, hilly land masses. Gentle sets `coast` -0.3, `landHeight` 72, `relief` 8, `roughness` 0.3; extreme sets `coast` 0, `landHeight` 130, `relief` 100, `roughness` 0.75 |
| `erosion` | Land worn down to flat basin floors at landHeight, with the hills that remain between them. | `basinShare` 0.02–0.9, 0.4; `hillHeight` 0–160, 40; `steepness` 0–1, 0.55; shared land at `landHeight` 70, `relief` 0, `roughness` 0.3 | gentle: soft rolling land with few flat floors; default: broad flat valleys between rounded hills; extreme: wide flat basins with steep-walled, flat-topped mesas. Gentle sets `basinShare` 0.1, `hillHeight` 16, `relief` 8, `steepness` 0; extreme sets `basinShare` 0.7, `hillHeight` 80, `steepness` 0.95 |
| `ridges` | Mountain ranges along the ridges noise, with jagged peaks. | `peakHeight` 0–200, 70; `jaggedness` 0–1.5, 0.35; `rangeWidth` 0.05–1, 0.4 | gentle: low ridges a viewer can trace; default: mountain ranges; extreme: high jagged ranges. Gentle sets `jaggedness` 0.15, `peakHeight` 45, `rangeWidth` 0.35; extreme sets `jaggedness` 1.2, `peakHeight` 150, `rangeWidth` 0.55 |
| `terraces` | Land stepped into flat terraces, capped by a plateau height. | `stepHeight` 3–48, 8; `riser` 0.05–0.95, 0.3; `plateauHeight` 64–256, 256; shared land at `landHeight` 96, `relief` 64, `roughness` 0.15 | gentle: fine 4-block steps on gentle hills; default: clear 8-block terraces with short risers; extreme: 24-block benches with near-vertical risers, capped flat at y 190. Gentle sets `relief` 40, `riser` 0.5, `stepHeight` 4; extreme sets `plateauHeight` 190, `relief` 110, `riser` 0.1, `stepHeight` 24 |
| `canyons` | Dry steep-walled canyons whose flat floors step down in falls toward sea level, like old river beds. | `depth` 8–120, 24; `width` 0.01–0.3, 0.06; `frequency` 0.25–4, 1; `grade` 0–1, 1; `cascadeStep` 2–32, 8; `pools` 0–0.3, 0.05; shared land at `landHeight` 112, `roughness` 0.35 | gentle: shallow gullies with small cascades; default: dry canyons whose floors fall in 8-block steps from 24 below the land to sea level; extreme: deep gorges in high land stepping down 14 blocks at a time from over 130 to the sea. Gentle sets `cascadeStep` 4, `depth` 14, `grade` 0.25, `landHeight` 96, `relief` 16, `width` 0.04; extreme sets `cascadeStep` 14, `depth` 36, `landHeight` 170, `relief` 30, `width` 0.1 |
| `rivers` | Rivers cut to just below sea level across the land, with banks. | `width` 0.01–0.25, 0.05; `depth` 1–16, 4; `bankWidth` 0.01–0.3, 0.08; `frequency` 0.25–4, 1 | gentle: narrow streams; default: rivers with banks; extreme: wide, frequent, deep channels. Gentle sets `bankWidth` 0.04, `depth` 2, `width` 0.025; extreme sets `bankWidth` 0.12, `depth` 12, `frequency` 1.4, `width` 0.08 |
| `floating_islands` | Islands in the sky over a void: domed tops, tapering undersides. | `altitude` 40–240, 150; `thickness` 4–64, 18; `underside` 4–120, 40; `coverage` 0.02–0.8, 0.3; `size` 0.25–4, 1; `spread` 0–80, 24 | gentle: scattered small islands; default: a sky of islands; extreme: large thick masses with deep undersides. Gentle sets `coverage` 0.15, `spread` 10, `thickness` 8, `underside` 14; extreme sets `coverage` 0.5, `size` 2, `spread` 60, `thickness` 40, `underside` 100 |
| `islands` | An ocean of islands and archipelagos. | `coverage` 0.02–0.7, 0.2; `size` 0.25–4, 1; `islandHeight` 4–120, 28; `archipelago` 0–1, 0.4; shared land at `seaFloor` 30 | gentle: low, broad islands; default: an archipelago; extreme: few tall islands. Gentle sets `archipelago` 0.1, `coverage` 0.35, `islandHeight` 10; extreme sets `archipelago` 0.5, `coverage` 0.12, `islandHeight` 90, `size` 1.5 |
| `voids` | Land broken by bottomless voids, each ringed by a raised rim. | `coverage` 0.02–0.5, 0.1; `size` 0.25–4, 1; `rimHeight` 0–60, 14; `rimWidth` 0.01–0.3, 0.06; shared land at `coast` -0.6, `seaFloor` 56 | gentle: small rare voids with low rims; default: voids ringed by rims; extreme: large voids with high, wide rims. Gentle sets `coverage` 0.04, `rimHeight` 4, `rimWidth` 0.03; extreme sets `coverage` 0.3, `rimHeight` 50, `rimWidth` 0.15, `size` 2 |
| `shattered` | Land broken into tilted blocks by fault cliffs and split by deep near-vertical chasms. | `faultHeight` 0–48, 16; `faultSpacing` 0.25–4, 1; `crackWidth` 0.01–0.3, 0.1; `crackScale` 0.25–4, 1.3; `chasmDepth` 4–60, 24; `lift` 0–1, 0.3; shared land at `landHeight` 88, `relief` 36 | gentle: hairline cracks and low fault steps; default: tilted blocks with fault cliffs and a network of chasms; extreme: tall fault cliffs, wide water-floored chasms and loose shards. Gentle sets `chasmDepth` 8, `crackWidth` 0.04, `faultHeight` 5, `lift` 0.1; extreme sets `crackWidth` 0.16, `faultHeight` 32, `faultSpacing` 0.7, `lift` 0.8, `relief` 50 |
| `spires` | Tall stone columns rising from the land. | `spireHeight` 16–200, 70; `coverage` 0.005–0.3, 0.04; `thickness` 0.25–4, 1; `taper` 0–1, 0.5; shared land at `roughness` 0.4 | gentle: short, sparse cones; default: stone columns; extreme: dense towers to 180 blocks. Gentle sets `coverage` 0.03, `spireHeight` 36, `taper` 0.6, `thickness` 1.4; extreme sets `coverage` 0.08, `spireHeight` 180, `taper` 0.1, `thickness` 0.7 |

A canyon floor lies `depth` below the land at its upper reaches. A low-frequency grade noise, and the coast, lower it toward sea level by `grade` of the way; the floor is stepped `cascadeStep` apart into flat reaches with a dry fall between each, so floors reach sea level only at their low ends and where canyons meet the coast. Where the grade noise lies in its lowest `pools` share the floor sinks 4 blocks into a hollow, which holds water where the floor is at sea level. Inside the channel the ground follows the height closely, so floors stay flat and walls steep.

Every terrain starts from the shared land, which each terrain may start at its own defaults:

| Shared parameter | Range, default | What it does |
| --- | --- | --- |
| `coast` | -0.6–0.4, -0.15 | Continentalness at the shoreline; higher is more ocean |
| `seaFloor` | -40–60, 36 | Height of the open ocean floor |
| `landHeight` | 64–200, 80 | Height of the land far inland |
| `relief` | 0–120, 24 | Blocks of hills where erosion is low |
| `roughness` | 0–1, 0.5 | How far 3D noise breaks the surface; 0 smooth, 1 overhangs |

v1's `adventure:wide` is `continents` with `scale` 2.5, `relief` 16 and `roughness` 0.3 (`terrain_continents_wide`); `adventure:compressed` is `continents` with `scale` 0.6, `relief` 90, `roughness` 0.7 and `heightMultiplier` 1.3 (`terrain_continents_compressed`). The gallery fixtures in `mods/custom-dimensions-26/server/src/selftest/gallery/` show every terrain at its three settings and those two; `-PterrainGallery=<dir>` loads them all, and `/customdim render <slug>` draws each.

## Zones

A zone replaces layers inside a shape centred on spawn or a point, with a blend and a jittered edge so borders read as landscape, not compass lines. Zones apply in order, each over the ones before it; outside them the definition's own layers hold. A zone converted from a 1.21.1 biome patch is a circle that sets `biomes` only.

| Field | Meaning |
| --- | --- |
| `shape` | `circle` (`radius`), `ring` (`inner`, `outer`), `square` (`radius`, half its side), `box` (`halfSize: [x, z]`), `sector` (`radius` or `outer`, `inner`, `angles: [from, to]` in degrees from east towards south), `polygon` (`points`, `[x, z]` corners relative to the centre), or `all`, the whole dimension, for swaps. Default `circle` |
| `centre` | `[x, z]`, or `spawn`: the definition's `spawn` ([T31](../../../TROUBLESHOOTING.md#t31)), else 0, 0 |
| `blend` | Blocks beyond the edge across which the zone fades out, linearly; 0 is a one-block hard edge. Default 32 |
| `edgeJitter` | Blocks the edge wanders: noise added to the distance from the edge, two standard deviations of which are `edgeJitter`, in features of 128 blocks and finer. Default 0 |
| `terrain` | A library terrain (its name, or an object as the definition's `terrain`) |
| `biomes` | The zone's biome list, laid out as the definition's are |
| `swap` | Biome to biome inside the zone; on shape `all`, everywhere and over every zone |
| `liquids.aquifers` | `on` (the dimension's) or `dry`: no aquifer water or lava and no sea in the open cells of the zone and its blend |
| `structures` | `wants` and `shuns` scoped to the zone, for structure control: a top-level want for a structure the zone wants lands where the zone's weight is at least 0.5, and a structure the zone shuns does not start there |

An error names the slug and the zone (`the_x: zones[2]: outer is required for this shape`).

- **Mask:** `<namespace>:zones/<slug>/<index>/mask` is `clamp(1 − (outside + jitter) / blend, 0, 1)`, where `outside` is the distance beyond the shape's edge. Circles, rings, boxes and `all` are vanilla functions (`gradient`, `square`, `sqrt`, `abs`, `max`); sectors and polygons use `customdimensions:zone_distance`, our one density-function type, which stays on the server. The jitter is the 2D noise `<namespace>:zone_jitter`.
- **Terrain:** the zone's terrain compiles under `<namespace>:terrain/<slug>/zone_<index>/`. Across the blend its `sloped_cheese` is lerped to from the layers beneath, inside a copy of their final density, so the ground moves between the two surface heights before the densities are squeezed; over the mask's last quarter, where the surfaces agree, that is lerped to the zone's own final density, which carries its floor and slides. The router's `depth` and `chunk_surface_level` and the aquifers' `surface_level` lerp the same way, so materials and aquifers read the zone's surface.
- **Aquifers:** `dry` sets the aquifers' `exclusion` to 1 and their `surface_level` 64 blocks above the world wherever the mask is above zero, so no aquifer and no sea fills the zone or its blend. The generator places lava below its lava level (-54, or `lavaLevel`) before it consults any aquifer, and an aquifer cell takes the sea from the cells round it, so the runtime's fluid rule places no sea and no lava wherever a dry zone's jittered edge may reach (`ZoneMask.mayCover`); a base sea that meets that line stands at it. Sea level stays one number per dimension.
- **Biomes:** our biome source, `customdimensions:zoned`, wraps the dimension's own. The router's temperature carries the zone whose biomes hold: where zone *c*'s mask reaches 0.5 it is the base temperature plus 16 *c*. The source reads the channel back, takes that zone's layout at the base temperature (or the base's, for a zone that only swaps), applies the zone's swaps, then the global ones. Materials and ores follow the biomes.
- **Biome lists:** a biome with `climate` takes that box first; the others keep vanilla's entries in the preset's parameter list when every one of them has some, and otherwise share temperature by humidity as an even grid. Overlaps are carved away so earlier entries keep their space, which also meets 26.4's rule. The End keeps its own biome source unless it lists biomes; a list there takes the overworld's temperature and vegetation so the layout spreads across the islands.
- **Query:** `core/.../zone/ZoneQuery` answers which zones cover a column, with each mask's still-edge weight. At server start, before any level generates, `server/.../zone/LevelZones` reads every level's zones from the definitions; structure control scopes wants and shuns with their weights, and the fluid rule reads the dry zones' masks.

Normal worldgen everywhere, except a ring of sky islands between 2,000 and 3,000 blocks from spawn (the self-test's `selftest_sky_ring`):

```json
"zones": [
  { "shape": "ring", "centre": "spawn", "inner": 2000, "outer": 3000,
    "blend": 200, "edgeJitter": 64,
    "terrain": { "base": "floating_islands" },
    "biomes": ["minecraft:cherry_grove", "minecraft:meadow"],
    "liquids": { "aquifers": "dry" },
    "structures": { "wants": ["minecraft:ruined_portal"] } }
]
```

## Structure control

The compiler resolves each definition's `structures` block into `data/customdimensions/structure_control/<slug>.json`; every dimension gets one, because noise placement is the default. The runtime applies it to the level's structure state as the chunk map builds it, on top of the firewall's switch-off: base and Fantasy levels alike, never touching the shared registry. Fields: [schema-v2.md § Structures](schema-v2.md#structures).

| Step | What happens |
| --- | --- |
| Filter | `density: none` and `mode: none` drop every organic set; `mode: allow`/`reject` keep or drop the sets in `list`; `exclude` drops sets |
| Remove and swap | `shuns` (ids, `#tags` or short names) leave every set, so a shunned structure has no candidate chunk anywhere; an `exclusive` forced structure (the default) leaves too; `replace` puts one structure in another's place with its weight |
| Spacing | A set named in `spacing` keeps its own random-spread grid with those values, salt and frequency kept |
| Noise groups | Every other exact random-spread set joins its group (`deco`, `loot`, `settlements`, `maritime`, `dungeons`, `landmarks`, `endgame`; `structure_themes.json`, else inferred from spacing). A group places at the sites of one noise field (the profile's threshold, an exclusion radius scaled by the 10-point `radial` curve, a cleared spawn disc for `dungeons` and `endgame`), its exclusion widened until each structure stays under the repetition ceiling; at each site vanilla picks from the group's structures by weight (times the rarity share) among those whose biome is there. `density` sets every group's profile; `noise` sets one or each. The generator type enables the groups; a peaceful `mobMultiplier` (≤ 0.5) turns `dungeons` and `endgame` off, a hostile one (≥ 2) spreads them evenly. A set whose group the type does not place, concentric rings and other placement types stay as they are |
| Vanilla grids | With `noise: false`, or a type with no groups, `density` scales random-spread sets by theme: dense tightens dungeon and loot spacing to 0.7 and landmark and maritime to 0.85, sparse halves dungeon, loot and landmark frequency and cuts settlement and maritime to 0.7 |
| Forced starts | `force` adds a one-chunk set: the chunk of `x`, `z`, or with `radius` the first of up to 96 chunks within it, in a seeded order, whose biome near sea level suits the structure (else the first). The structure skips the biome check in that chunk only (T53); the structure's own terrain rules still apply, so a structure that needs dry land or a height declines a site without them |
| Wants | Each want resolves to a structure (the first of a tag whose biome the dimension has) and lands as a forced start between `min` and `max` blocks of spawn |
| Included sets | `include` re-admits a set no biome here suits, on its own grid with the biome check skipped |
| Terrain adaptation | `terrainAdaptation` (a structure, a `#tag` or a group) replaces the adjustment the Beardifier uses round that structure in this dimension: `none`, `beard_thin`, `beard_box`, `bury`, `encapsulate`; the 1.21.1 kernels `pedestal` and `platform_skirt` apply as `beard_box`, `ground_blend` as `beard_thin`; `moat` and `drain` have no counterpart and are reported |

Unknown structure and set ids, ids neither vanilla nor a bridge provides, are compile-report warnings; a malformed value stops the compile with the slug. Sizes are measured on 26.3 (`-PmeasureStructures`, [mod README](../../../mods/custom-dimensions-26/README.md#structure-control)): `structure-sizes.json` in the mod's resources for every structure, and each bridge's `size` slots.

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
