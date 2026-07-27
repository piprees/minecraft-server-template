---
title: Generator Types and Advanced Worldgen Fields
description: Checkerboard, superflat, settingsOverrides, per-biome parameters, biomePatches, and per-dimension structure control — with copyable examples
tags: [worldgen, checkerboard, superflat, biomePatches, structures, noiseSettings, settingsOverrides]
---

# Generator types and advanced worldgen fields

All fields documented here go in a dimension config file at `config/custom-dimensions/dimensions/<slug>.json`. Unless noted otherwise, they are **creation-time-only** — baked into `level.dat` at world creation; changes need a world wipe ([TROUBLESHOOTING.md#d2](../../../TROUBLESHOOTING.md#d2)).

## noiseSettings and structureDensity

```json
{
  "noiseSettings": "adventure:wide",
  "structureDensity": "sparse"
}
```

- `noiseSettings`: a `worldgen/noise_settings` registry id. The mod ships `adventure:wide` (broad realistic relief) and `adventure:compressed` (tight dramatic relief); any datapack-registered id works. Unset keeps the dimension type's default generator. Ignored for void/superflat.
- `structureDensity`: `dense` | `normal` | `sparse` | `none`. Theme-aware (dungeon/loot/settlement/landmark/deco): dense boosts dungeons+loot ~2×, sparse halves them. Dimensions with `"hostileSpawning": false` also drop all dungeon-theme structure sets and rarify settlements/ships to ~0.3× automatically.

## Checkerboard generator

```json
{
  "type": "checkerboard",
  "biomes": ["minecraft:plains", "minecraft:desert", "minecraft:taiga"],
  "checkerboardScale": 2
}
```

Tiles the `biomes` list in a fixed grid (vanilla checkerboard biome source) over overworld terrain noise — the layout is seed-independent, terrain and structures still follow the seed. `checkerboardScale` (0–62, default 2) sets the cell size: one cell is `2^(scale+4)` blocks per side (scale 2 = 64 blocks). Invalid biome entries are skipped with a warning; an empty list falls back to a plain overworld generator.

## Superflat generator

```json
{
  "type": "superflat",
  "flatBiome": "minecraft:desert",
  "layers": [
    { "block": "minecraft:bedrock", "height": 1 },
    { "block": "minecraft:sandstone", "height": 10 },
    { "block": "minecraft:sand", "height": 3 }
  ]
}
```

Layers are bottom-up; `height` = thickness. Any invalid layer (unknown block, bad height) falls back to the whole default bedrock/dirt/grass stack — never a half-built world. Biome features and structures still generate on superflat terrain (desert wells, dungeons), exactly as vanilla superflat presets behave.

A superflat dimension is always skipped by the seed roller (`dimension_profiles.rollable()` returns false).

## settingsOverrides

Whitelisted generator-settings swaps applied on top of the type's (or `noiseSettings` preset's) settings. Creation-time worldgen.

```json
{ "settingsOverrides": { "seaLevel": 100, "defaultFluid": "minecraft:lava" } }
```

Fields: `seaLevel` (int), `defaultBlock` / `defaultFluid` (block ids — think netherrack body, lava seas), and `disableMobGeneration` (bool). Invalid values warn and keep the base value per field. Arbitrary inline noise settings remain unsupported by design — author a jar preset instead.

## Per-biome placement parameters

A `biomes` entry may be an object instead of a plain id string. Parameters are vanilla multi-noise intervals. Creation-time worldgen.

```json
"biomes": [
  { "id": "minecraft:plains", "parameters": { "temperature": [-2.0, 0.0] } },
  { "id": "minecraft:cherry_grove", "parameters": { "temperature": [0.0, 2.0] } }
]
```

Supported parameter axes: `temperature`, `humidity`, `continentalness`, `erosion`, `depth`, `weirdness` (number or `[min, max]` within -2..2; `offset` 0..1). An overridden biome gets exactly that region and is withdrawn from the natural/round-robin mixing; unset axes span everything. Invalid parameters warn and the entry behaves as a plain listed biome.

## biomePatches

Fixed biome patches over the generated layout, three modes per patch. Creation-time worldgen.

```json
"biomePatches": [
  { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
  { "biome": "terralith:moonlight_grove", "x": 800, "z": -200, "radius": 400,
    "replace": "minecraft:dark_forest", "shape": "square", "blend": 16 },
  { "biome": "minecraft:cherry_grove", "replace": "minecraft:badlands", "scope": "global" },
  { "biome": "minecraft:river", "x": 500, "z": 500, "radius": 48, "scope": "global" }
]
```

### Three modes

1. **Stamp** (no `replace`): the listed biome claims every column in the area.
2. **Clipped swap** (`replace` set): within the area, only columns resolving to the `replace` biome are substituted — the natural blob keeps its organic shape, recoloured. `"*"` matches any biome ≈ a stamp.
3. **Global swap** (`"scope": "global"`): dimension-wide wholesale replacement — an explicit `replace` id swaps that biome everywhere (no area needed); without one the area becomes a *selector*: every distinct biome touching it swaps globally (selector sampling sweeps up to 256 blocks of the radius).

### Shared knobs

- `"shape"`: `"circle"` (default) or `"square"` (Chebyshev — tiles cleanly against chunk grids)
- `"blend"`: edge jitter in blocks (0–64, default 8, `0` = razor edge) — smooth deterministic noise wobbles stamp/clip borders so they don't read as compass shapes

### Key properties

- Precedence: local patches in config order (a non-matching swap falls through), then global rules.
- The killer app is a **guaranteed spawn biome at (0, 0)** — no more rolling seeds against a spawn filter.
- Terrain SHAPE is density-function-driven and mostly biome-independent: a desert patch on a mountain is a sandy mountain — pick sites with the terrain mood in mind.
- Invalid patches are skipped with a warning.

## Per-dimension structure control

### Spacing overrides (boot-re-read)

```json
"structures": {
  "spacing": {
    "minecraft:villages": { "spacing": 20, "separation": 6 }
  }
}
```

Exact placement values for one structure SET (registry set id, e.g. `minecraft:villages`, NOT a structure id), overriding the theme-based `structureDensity` factors for that set. Invariants: `2 <= spacing <= 4096`, `0 <= separation < spacing`; violations (and custom placement types) warn and fall back to the theme path. The peaceful overlay's dungeon-set drops always win over a spacing entry. Boot-re-read, but placements only affect newly generated chunks.

### Mode filter (boot-re-read)

```json
"structures": {
  "mode": "reject",
  "list": ["minecraft:strongholds", "minecraft:woodland_mansions"]
}
```

Filter whole ORGANIC structure sets per dimension: `allow` keeps only sets in `list`, `reject` drops the listed sets, `none` drops everything organic. Set ids, boot-re-read, new chunks only. An exit-shrines opt-in survives the filter.

### Forced placements (boot-re-read)

```json
"structures": {
  "force": [
    { "structure": "minecraft:ancient_city", "x": 1200, "z": -800 }
  ]
}
```

An exact structure at an exact spot (STRUCTURE id, block coordinates; the start lands in that chunk). Forced placements are additive after `mode` (`"mode": "none"` + `force` = only the forced structures), exempt from density rescaling, and visible to `/locate`. The structure's biome predicate still applies — pick a spot whose biome the structure accepts, or it silently won't place. Unknown structure ids (removed mods) warn and skip.

Note: `/locate` returns the first find in ring order across sets. With the organic set still enabled it may name a farther organic instance even when your forced one exists.

### Combining structure fields

All three (`spacing`, `mode`, `force`) can coexist in one `structures` block:

```json
"structures": {
  "mode": "none",
  "force": [
    { "structure": "minecraft:ancient_city", "x": 0, "z": 0 }
  ],
  "spacing": {
    "minecraft:villages": { "spacing": 16, "separation": 4 }
  }
}
```
