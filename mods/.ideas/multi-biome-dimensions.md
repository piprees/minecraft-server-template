# Multi-biome dimensions (filtered MultiNoiseBiomeSource)

Support curated multi-biome dimensions using a filtered `MultiNoiseBiomeSource` that only generates from a specified list of biomes.

## Motivation

`single_biome` creates monotonous worlds. A cherry grove dimension is nice but gets repetitive — mixing in meadows, lavender valleys, and birch forests makes it feel like a real place. `CheckerboardBiomeSource` creates artificial patchwork grids. `MultiNoiseBiomeSource` with a filtered biome set creates natural-looking terrain transitions between the allowed biomes.

## Implementation

New world type: `multi_biome` in `DimensionManager.createDimensionOptions()`.

### How MultiNoiseBiomeSource works in 1.21.1

The overworld's `MultiNoiseBiomeSource` maps 5 noise parameters (temperature, humidity, continentalness, erosion, weirdness) to biomes via `MultiNoiseBiomeSourceParameterList`. Each biome has a noise point — the biome source picks the biome whose point is closest to the sampled noise values.

### Filtering approach

1. Get the overworld's `MultiNoiseBiomeSource` (which has all biome→noise mappings including Terralith's)
2. Extract its parameter entries (list of `Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>`)
3. Filter to only entries whose biome is in the allowed set
4. Create a new `MultiNoiseBiomeSource` from the filtered entries

This preserves natural noise-based biome placement — the allowed biomes fill the parameter space organically rather than in a grid. Biomes that are noise-neighbors in the filtered set will blend together naturally.

### Command syntax

```
/dimension create <name> multi_biome [seed] cherry_grove,meadow,flower_forest,lavender_valley,blooming_valley
```

The biome field in `DimensionDefinition` stores the comma-separated list. For `single_biome` it's one ID, for `multi_biome` it's multiple.

### Key classes

- `MultiNoiseBiomeSource` — the biome source
- `MultiNoiseUtil.Entries<RegistryEntry<Biome>>` — the biome→noise mapping
- `MultiNoiseBiomeSource.Preset` — OVERWORLD preset (includes Terralith additions)

### Considerations

- Terralith adds its biomes to the overworld's `MultiNoiseBiomeSource` at startup. Extracting from the live overworld source (not the preset) ensures modded biomes are included.
- If all specified biomes are close in noise space, the dimension may feel samey. Good curated lists mix different noise regions.
- The noise generator settings should come from the overworld to get proper terrain shapes.
