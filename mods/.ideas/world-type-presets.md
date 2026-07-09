# World type presets

Support additional world generation types beyond overworld/nether/end/void.

## Motivation

A pocket cherry grove dimension for peaceful building, or an amplified world for dramatic terrain. The current `/dimension create <name> <type>` only supports overworld, nether, end, and void — but Minecraft has more generator types we can expose.

## World types to support

| Type | Generator | Use case |
| --- | --- | --- |
| `overworld` | NoiseChunkGenerator + overworld settings | Standard (already supported) |
| `nether` | NoiseChunkGenerator + nether settings | Standard (already supported) |
| `end` | NoiseChunkGenerator + end settings | Standard (already supported) |
| `void` | FlatChunkGenerator with empty layers | Hub dimensions (already supported) |
| `superflat` | FlatChunkGenerator with configurable layers | Building, redstone, farms |
| `amplified` | NoiseChunkGenerator + amplified settings | Dramatic terrain |
| `large_biomes` | NoiseChunkGenerator + large biome settings | Exploration |
| `single_biome` | NoiseChunkGenerator + FixedBiomeSource | Pocket biome dimensions (cherry grove, mushroom island, etc.) |

## Implementation

- `DimensionManager.createDimensionOptions()` already switches on the type string — extend the switch
- `superflat`: use `FlatChunkGenerator` with default preset (or configurable layers later)
- `amplified`: use overworld `ChunkGeneratorSettings` with `amplified` noise settings — check if `ChunkGeneratorSettings.AMPLIFIED` exists in 1.21.1
- `large_biomes`: use `MultiNoiseBiomeSource` with the large biomes preset
- `single_biome`: use `FixedBiomeSource` wrapping a specific biome. Need a `biome` parameter on `/dimension create`: `/dimension create cherry_pocket single_biome minecraft:cherry_grove`
- Update command tab-completion to include new types
- Update `DimensionDefinition` to store optional extra params (biome ID for single_biome, layer config for superflat)

## Cherry grove pocket dimension example

```
/dimension create cherry_pocket single_biome minecraft:cherry_grove
/portal link cherry minecraft:cherry_blossom minecraft:cherry_blossom_petals minecraft:cherry_pocket FF9EC6 8
```
