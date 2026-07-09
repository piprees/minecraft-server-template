# Integrated per-dimension seeds

Replace the external `seeded-dimensions` mod by handling per-dimension seeds natively in custom-dimensions.

## Motivation

The `seeded-dimensions` mod is a separate dependency that reads `config/seeded-dimensions.json`. Since we already manage dimension definitions in `multiverse_config.json` via `DimensionDefinition`, the seed should be a field on that definition — one config, one mod, no external dependency.

## Behaviour

- Add a `seed` field to `DimensionDefinition` (type `Long`, nullable — null means use the server seed)
- When `DimensionManager.createDimensionOptions()` builds a new dimension, apply the custom seed to the chunk generator if set
- The seed needs to be injected into the `ChunkGenerator` — this likely requires a mixin on `NoiseChunkGenerator` or `ChunkGenerator` to override the seed at creation time, OR passing the seed through the `DimensionOptions` constructor
- Expose in `/dimension create` as an optional `seed` argument: `/dimension create <name> <type> [seed]`
- Persist in `multiverse_config.json` alongside existing dimension fields
- Migrate: on first load, if `config/seeded-dimensions.json` exists, read seeds from it and merge into `multiverse_config.json`, then log a deprecation notice

## Implementation notes

- In 1.21.1, the chunk generator seed is set at world creation and stored in `level.dat` per dimension. The `NoiseChunkGenerator` constructor takes a `BiomeSource` and `ChunkGeneratorSettings` but NOT a seed directly — the seed comes from the `WorldGenSettings` in `level.dat`
- The `seeded-dimensions` mod likely uses a mixin on the chunk generator or world creation to intercept the seed. Decompile it (same approach as custom-dimensions) to understand the exact injection point
- Alternative: set the seed in the `GeneratorOptions` when creating the `ServerWorld` in `getOrCreateDimension()` — this might be simpler than a mixin

## After integration

- Remove `seeded-dimensions` from `config/modrinth-mods.txt`
- Remove `config/seeded-dimensions.json` (migrated into `multiverse_config.json`)
- Update `config/dimensions.txt` to be the sole source of truth — the setup script writes seeds into the dimension definitions via `/dimension create <name> <type> <seed>`
