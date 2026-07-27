# Biome Params Data Gap — Phase 3 Follow-Up

## Issue

The current `biome_params.json` in the elfydd consumer's `.seedtest/base/config/custom-dimensions/` contains **only 10 Paradise Lost biomes** and **no family tags**:

```
Total entries: 70 (with duplicates)
Unique biomes: 10 — ALL paradise_lost:*
  paradise_lost:autumnal_tundra
  paradise_lost:calcite_craglands
  paradise_lost:continental_plateau
  paradise_lost:highlands
  paradise_lost:highlands_forest
  paradise_lost:highlands_shield
  paradise_lost:wisteria_woods
  (+ 3 more)
```

## Impact

1. **All dimension renders look the same** — the BiomeSampler finds only Paradise Lost biomes regardless of the `family` filter, because no family tags exist in the data
2. **The old renders looked correct** because they were generated during a different warmup run that produced a complete biome_params.json with all 4 families (overworld/nether/end/paradise_lost) and family tags
3. **Overworld biomes** (plains, forest, desert, ocean, rivers, mountains, etc.) are entirely missing — the renderer can't distinguish terrain types it can't sample
4. **Nether biomes** (nether_wastes, crimson_forest, basalt_deltas, incendium:*, etc.) are missing
5. **End biomes** (end_highlands, nullscape:*, etc.) are missing

## Root Cause

The warmup step (`scripts/seed/roll-all.sh` → server boot → `/customdim dump-biome-params`) likely only ran for one dimension family (paradise_lost), or the dump was truncated. The brief states the warmup dumps "biome params for all 4 dimension families" but the actual file only contains one.

## Required Fix (Phase 3)

1. **Re-run the warmup** to regenerate `biome_params.json` with all 4 families:
   ```bash
   ./dev seed-roll --warmup-only
   ```
   This boots the MC server in Docker, creates one dimension per family, and runs `/customdim dump-biome-params` which extracts the full modded biome parameter table including TerraBlender entries.

2. **Verify the output** contains entries for all families:
   ```python
   import json
   params = json.load(open('biome_params.json'))
   families = set(e.get('family', 'none') for e in params)
   print(f'{len(params)} entries, families: {families}')
   # Should show: overworld, nether, end, paradise_lost
   ```

3. **The family tag** on each entry should match the dimension family it belongs to, enabling the BiomeSampler to filter correctly when rendering per-dimension maps.

## Workaround (Current State)

The renderer code is correct — it uses whatever biomes the sampler returns. The blended colour approach (55% surface-block + 45% biome-identity) produces good results when the biome_params has proper coverage. The spline-based terrain heights, vegetation density, and water depth shading are all working correctly.

When biome_params.json is regenerated with full coverage, the renderer will immediately produce varied, terrain-like renders for all dimension families.

## Dependencies

- Requires Docker Desktop running
- Requires the full mod stack (~129 JARs) installed
- Server boot takes ~90s for warmup
- One-time operation, cached forever after

## No Code Changes Needed

The renderer, surface_rules, and terrain_height modules are complete and tested. Only the input data (biome_params.json) needs regeneration.
