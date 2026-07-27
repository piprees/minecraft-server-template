# Missing Dimensions Investigation

## Summary

All 5 dimensions have config files AND candidate stores. 100 seeds were attempted per dimension, but **every candidate was rejected** — "spawn filter: no matching biome found".

## Per-Dimension Findings

| Dimension | Type | Spawn Filter | Candidates | Rejected | Cause |
| --- | --- | --- | --- | --- | --- |
| paradise_lost | base-world (paradise_lost:paradise_lost) | highlands, highlands_forest, wisteria_woods, tradewinds | 0 | 100 | Skylands biomes at Y>100, grid samples at Y=64 (void) |
| the_crimson_nexus | multi_biome | crimson_forest, warped_forest, inverted_forest | 0 | 100 | Nether biomes don't map to overworld noise correctly |
| the_pillared_void | end | void_barrens, small_end_islands, end_barrens, the_end | 0 | 100 | End biome layout: central island only, grid misses |
| the_red_monument | end (custom biomes) | scarlet_mountains, red_oasis, crimson_forest | 0 | 100 | Non-end biomes in end noise → unmappable |
| the_souldrift | multi_biome | soul_sand_valley, basalt_deltas, ash_barrens | 0 | 100 | Same as crimson_nexus + possible typo in biomes list |

## Three Failure Patterns

### 1. Paradise Lost clone type

The `paradise_lost:paradise_lost` clone creates skylands floating at Y=100-300+. The server-side `sample-biome-grid` command samples at a fixed Y (likely 64), where there's void — no biomes found.

### 2. `multi_biome` with nether biomes (the_crimson_nexus, the_souldrift)

`multi_biome` places listed biomes on overworld noise via MultiNoiseBiomeSource. Nether-namespaced biomes may lack overworld noise point mappings in biome_params.json. The pure-Python BiomeSampler also can't find them because the biome parameter table doesn't map overworld noise coordinates to nether biomes.

### 3. `end` type dimensions (the_pillared_void, the_red_monument)

End uses a unique biome source — not multinoise. `minecraft:the_end` is placed at (0,0), other biomes in concentric rings far from origin. The grid sampler at step=64 within radius=768 may miss the specific biome layout. Custom biome lists for end-type clones may be ignored by the end biome source.

## Config Issues Found

- `the_souldrift`: `minecraft:quartz_bricks_valley` in biomes list is likely a typo — should be `minecraft:soul_sand_valley`
- `the_souldrift`: `soul_sand_valley` is in spawn filter but NOT in biomes list

## Required Fixes

### Immediate (no Docker needed)

1. Fix the_souldrift biomes typo
2. For the renderer batch mode: use the pure-Python BiomeSampler to generate synthetic candidates so these dimensions get renders

### Requires Docker (future)

3. Debug `sample-biome-grid` with Paradise Lost and multi_biome dims
4. Consider adding Y-level parameter to the grid sampler for skylands
5. Consider relaxing spawn filter for exotic dimension types
