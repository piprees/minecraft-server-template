# Spike 13: Mod Terrain Spline Extraction

Extracted terrain height splines from Incendium (nether) and Nullscape (end)
mod JARs, matching the existing Terralith overworld extraction. Paradise Lost
investigated but found to use a different terrain system.

## Findings per mod

### Incendium (nether) — splines extracted

**Source JAR:** `Incendium_1.21.x_v5.4.4.jar`

**Density function files:**
- Offset: `data/incendium/worldgen/density_function/climate/depth.json` (9.9KB)
- Factor: `data/incendium/worldgen/density_function/climate/factor.json` (6.8KB)

**Structure:** Identical to the overworld pattern:
```
initial_density = 4 * quarter_negative(depth * factor)
depth = y_gradient(-64→320, 1.5→-1.5) + flat_cache(cache_2d(add(blend, mul(clamp(add(-0.5037, SPLINE)), blend_alpha))))
```

**Spline coordinates:** `continentalness`, `erosion`, `weirdness` (raw, NOT
ridges_folded). The noise_router maps `incendium:climate/*` references to
these. Nether biome placement uses the same multinoise system as the overworld.

**Extracted splines:**
- Offset: 38 nested points, 3 coordinates (continentalness → erosion → weirdness)
- Factor: 29 nested points, 3 coordinates

**Surface height formula:** `Y = 128 * (1 + offset)` — same as overworld.
The y_clamped_gradient parameters produce the same ratio despite different Y
bounds (nether: min_y=0, height=192, sea_level=32).

**Height range (synthetic grid):** -2784 to 146, mean -458. The extreme
negatives come from offset values like -22.25 at certain erosion/weirdness
combinations — these represent areas that should be lava/void in the nether
(no solid terrain at any Y level).

### Nullscape (end) — splines extracted

**Source JAR:** `Nullscape_1.21.x_v1.2.14.jar`

**Density function files:**
- Offset: `data/nullscape/worldgen/density_function/depth.json` (14KB)
- Factor: `data/nullscape/worldgen/density_function/base/factor.json` (1.4KB)

Note: there's also `base/depth.json` (16.7KB) which is referenced by
`initial_density_without_jaggedness` — this is a *different* density function
from the noise_router `depth` entry. The noise_router `depth` references
`nullscape:depth` (the root-level file), not `nullscape:base/depth`.

**Structure:** Same overworld pattern:
```
depth.json = add(y_gradient(0→384, 1→-2), flat_cache(cache_2d(add(blend, mul(clamp(-20,20, add(-0.5037, SPLINE)), blend_alpha)))))
```

**Spline coordinates:** `continentalness` (mapped from `nullscape:base/continents`),
`erosion` (from `minecraft:overworld/erosion`), `weirdness` (from
`minecraft:overworld/ridges`). Uses raw weirdness, not ridges_folded.

**Extracted splines:**
- Offset: 46 nested points, 3 coordinates
- Factor: 6 nested points, 2 coordinates (continentalness, erosion only)

**Surface height formula:** `Y = 128 * (1 + offset)` — same as overworld.

**Height range (synthetic grid):** -448 to 193, mean -22. Negative heights
represent void between end islands.

**Additional terrain features:** The end's `final_density` also incorporates:
- `nullscape:island/island` — creates the characteristic end island ring structure
- `nullscape:sloped_cheese` — contains its own spline with 22 points
- Various porosity/void/brittleness functions for caves and shatter effects

These are NOT captured by the offset spline — they shape the 3D terrain
differently from the 2D heightmap approximation. The offset spline alone gives
a reasonable height estimate for areas where terrain exists.

### Paradise Lost — no custom splines

**Source JAR:** `paradise-lost-2.4.6-beta+1.21.1.jar`

**Density function files:**
- `data/paradise_lost/worldgen/noise_settings/noise.json` (20.6KB)
- `data/paradise_lost/worldgen/density_function/generator/depth.json` (230B)

**Finding:** Paradise Lost does NOT use custom splines. Its terrain system:

1. `generator/depth.json` references `minecraft:overworld/offset` — it delegates
   to the vanilla/Terralith overworld offset spline.
2. `initial_density_without_jaggedness` is a simple `y_clamped_gradient(60→-64)`
   — no spline, just a linear Y gradient.
3. Terrain shape comes from noise-based functions: `hills.json` (elevation noise),
   `ridges.json` (ridge noise), `bulk.json` (erosion-based clamp), `spackle.json`.

**Conclusion:** Paradise Lost can reuse the overworld spline for height
estimation (since it references `minecraft:overworld/offset`). The evaluator
maps `paradise_lost` → `overworld` internally.

## Changes made

### `terrain_splines.json`

Changed from flat format to per-family:
```json
{
  "overworld": {"offset": <348 pts>, "factor": <207 pts>},
  "nether":    {"offset": <38 pts>,  "factor": <29 pts>},
  "end":       {"offset": <46 pts>,  "factor": <6 pts>}
}
```

Size: 13,957 bytes (was 11,814 for overworld-only).

### `terrain_height.py`

- `TerrainEvaluator` loads all families from JSON, compiles per-family spline trees
- `surface_height()` accepts `family="overworld"` parameter
- `has_family(family)` method for the renderer to check availability
- `paradise_lost` transparently maps to `overworld`
- `--extract <mods_dir>` mode extracts from Terralith, Incendium, and Nullscape
  JARs in one pass using `extract_all_from_jars()`
- Added `COORD_MAPS` dict with per-mod coordinate name mappings
- Added `_find_spline_node()` for generic spline discovery in density function trees
- Backwards compatible: still loads legacy flat-format JSON

### `biome_renderer.py`

- Uses spline-based heights for ALL families that have splines (not just overworld)
- `_evaluator_for_family()` helper checks if the evaluator has splines for a family
- Removed the amplified formula fallback for nether/end/paradise_lost — the spline
  heights are now the primary source

### `test_biome_pipeline.py`

- Fixed `test_loads_from_json` to use `has_family()` instead of `_offset`
- Updated `test_per_family_height_functions` to use the evaluator for all families
- Regenerated nether and end snapshot renders

## Coordinate handling

All three families use the same params tuple `(continentalness, erosion,
ridges_folded, weirdness)` with `_COORD_IDX` mapping. The key difference:

| Family    | Spline uses     | Index |
|-----------|-----------------|-------|
| overworld | `ridges_folded` | 2     |
| overworld | `weirdness`     | 3     |
| nether    | `weirdness`     | 3     |
| end       | `weirdness`     | 3     |

The overworld spline references `ridges_folded` (a derived coordinate), while
nether and end splines reference raw `weirdness` directly. Both map to the
correct index in the params tuple since `_params()` always computes both.
