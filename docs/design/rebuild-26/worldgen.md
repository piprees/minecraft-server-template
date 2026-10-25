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

Any mod may be installed; another worldgen owner may not. At start-up the mod audits the loaded game and refuses to start, listing what it found, if any of these appear without a bridge:

- a dimension, noise setting or biome source from another namespace;
- a mixin into worldgen classes from a mod not on the allow list (C2ME, Lithium and ServerCore are on it);
- TerraBlender, Biolith or Lithostitched.

Features or spawns added to biomes by another mod are removed and logged.

A bridge is one JSON file per mod in `config/custom-dimensions/bridges/`. It adopts that mod's structures (with measured sizes and our tags for mood, climate and depth), features and, once content starts, its blocks' Polymer and Bedrock mappings. Structure sets without a bridge are switched off. W5 generates a first bridge from each structure mod's jar for a human to tag.
