# Surface Rules Module — Implementation Report

## File Created

`scripts/seed/surface_rules.py`

## What It Does

Maps biome IDs to rendering parameters: surface block colour, vegetation density, and grass temperature tinting. Replaces the flat biome colours in `biome_renderer.py` with colours representing what the terrain actually looks like from above.

## API

| Function | Returns | Purpose |
|---|---|---|
| `biome_surface(biome_id)` | `str` | Surface type key ("grass", "sand", "stone", etc.) |
| `vegetation_density(biome_id)` | `float` | Canopy darkness multiplier (0.62 = very dense, 1.0 = open) |
| `grass_tint(biome_id)` | `(float, float, float)` | RGB multiplier for grass biomes based on climate |
| `surface_colour(biome_id)` | `(int, int, int)` | Final RGB after base colour + grass tinting |
| `surface_and_density(biome_id)` | `((int,int,int), float)` | Combined tuple for the renderer |

## Coverage

- All 123 biomes from `biome_renderer.py` BIOME_COLOURS dict
- Vanilla, Terralith, Incendium, Nullscape, Nature's Spirit, Paradise Lost
- Keyword-based fallback for unknown modded biomes

## Colour System

20 surface types based on MC's official map colour system (base colour × 0.86 shade variant):

- **Grass** — (109, 153, 48) base, then tinted per-biome by temperature
- **Sand** — (212, 200, 140) for deserts and beaches
- **Red Sand** — (168, 88, 35) for badlands
- **Stone** — (96, 96, 96) for stony peaks, shores
- **Snow** — (230, 230, 230) for frozen peaks, ice spikes
- **Water** — 3 variants: standard (52, 52, 219), warm (67, 213, 238), cold/frozen
- **Nether** — netherrack, crimson_nylium, warped_nylium, soul_sand, basalt
- **End** — end_stone (200, 200, 140)

## Vegetation Density Scale

| Level | Multiplier | Example Biomes |
|---|---|---|
| Very Dense | 0.62 | dark_forest, jungle, bamboo_jungle, old_growth_taiga |
| Dense | 0.78 | forest, birch_forest, taiga, cherry_grove |
| Moderate | 0.90 | sparse_jungle, swamp, savanna, grove |
| Open | 1.00 | plains, desert, beach, ocean, snowy_plains |

## Verification

```bash
python3 scripts/seed/surface_rules.py              # formatted table
python3 scripts/seed/surface_rules.py --format csv  # CSV output
```

Verified: all 123 biomes produce correct surface types and density values.
