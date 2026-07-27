# Improved Renderer Research — Surface Blocks, Colours, Vegetation

## Key Finding

**Even Chunkbase and AMIDST use flat biome colours.** Our current biome_renderer.py with hillshade already surpasses them visually. Applying surface-block colours, water depth shading, and vegetation density would produce renders significantly better than any existing seed-evaluation tool — without needing .mca files or unmined-cli.

## 1. Biome → Surface Block Mapping

MC 1.18+ uses data-driven surface rules (nested conditional tree in `noise_settings/overworld.json`). No single published table, but the practical mapping for a renderer:

| Surface Block | Biomes |
|---|---|
| `grass_block` | Plains, Forest, Birch Forest, Dark Forest, Meadow, Cherry Grove, Jungle variants, Swamp, River (banks), Pale Garden |
| `sand` | Desert, Beach, Warm Ocean floor |
| `red_sand` | Badlands, Eroded Badlands, Wooded Badlands |
| `terracotta` | Badlands variants (sub-surface) |
| `mycelium` | Mushroom Fields |
| `podzol` | Old Growth Pine/Spruce Taiga |
| `snow_block` | Snowy Plains, Ice Spikes, Frozen Peaks, Snowy Slopes, Grove |
| `stone` | Stony Peaks, Stony Shore, Windswept Hills (above Y), Windswept Gravelly Hills |
| `gravel` | Windswept Gravelly Hills, Cold/Frozen Ocean floor |
| `mud` | Mangrove Swamp |
| `netherrack` | Nether Wastes |
| `soul_sand`/`soul_soil` | Soul Sand Valley |
| `crimson_nylium` | Crimson Forest |
| `warped_nylium` | Warped Forest |
| `basalt` | Basalt Deltas |
| `end_stone` | End biomes |

For modded biomes (Terralith, Incendium, Nullscape): most use vanilla surface blocks, just with custom terrain shapes and biome placement. Terralith biomes like `yellowstone` use vanilla grass_block with custom features.

## 2. Block → Colour (MC Map Colours)

MC's official map system uses 62 base colours × 4 shade variants = 248 colours. Key surface colours:

| Map Colour | RGB | Surface Block |
|---|---|---|
| GRASS | (127, 178, 56) | grass_block |
| SAND | (247, 233, 163) | sand, sandstone |
| DIRT | (151, 109, 77) | dirt, coarse_dirt |
| STONE | (112, 112, 112) | stone |
| WATER | (64, 64, 255) | water |
| SNOW | (255, 255, 255) | snow_block |
| PODZOL | (129, 86, 49) | podzol |
| CLAY | (164, 168, 184) | clay |
| COLOR_PURPLE | (127, 63, 178) | mycelium |
| NETHER | (112, 2, 0) | netherrack |
| COLOR_CYAN | (76, 127, 153) | warped nylium |
| COLOR_RED | (153, 51, 51) | crimson nylium |

### Biome-Specific Grass/Foliage Tinting

MC uses temperature (T) and downfall (D) per biome to tint grass/foliage:
```
adjD = D * clamp(T, 0, 1)
x = (1 - clamp(T, 0, 1)) * 255
y = (1 - adjD) * 255
sample from grass.png / foliage.png colormaps
```

Key tints:
- Plains (T=0.8, D=0.4): warm green
- Taiga (T=0.25, D=0.8): blue-green
- Desert (T=2.0, D=0.0): brown-green
- Jungle (T=0.95, D=0.9): vivid green
- Swamp: hardcoded dark olive (biome_color_modifier)
- Dark Forest: averages with (40, 52, 10)

## 3. Vegetation Density Approximation

| Density | Factor | Biomes |
|---|---|---|
| Very Dense | 0.60–0.70 | Dark Forest, Old Growth Taiga, Jungle, Bamboo Jungle, Mangrove Swamp |
| Dense | 0.75–0.85 | Forest, Flower Forest, Birch Forest, Taiga, Cherry Grove |
| Moderate | 0.90–0.95 | Sparse Jungle, Swamp, Pale Garden, Savanna |
| None | 1.00 | Plains, Meadow, Desert, Badlands, Snowy Plains, Beach, Ocean |

Apply as multiplier to surface colour. Use spatial noise (weirdness) for variation.

## 4. Water Rendering

### Continentalness thresholds:
- Deep Ocean: C < -0.455
- Ocean: -0.455 ≤ C < -0.19
- Coast: -0.19 ≤ C < -0.11
- Inland: C ≥ -0.11

### River detection:
- Biome identity: `river`, `frozen_river`
- Noise: PV < -0.85 where PV = `1 - |3 * |weirdness| - 2|`

### Depth shading:
Map continentalness from -1.05 (deep ocean) to -0.19 (coast) onto a blue gradient (dark navy → light blue) for bathymetric effect. Much more natural than flat blue.

## 5. Chunkbase / AMIDST — What They Actually Do

**Chunkbase**: cubiomes compiled to WASM, flat colours per biome from the cubiomes colour table. Separate height layer toggle (gradient, not overlaid). No hillshading, no terrain blending.

**AMIDST**: Flat colours per biome from a JSON profile. No elevation, no hillshading, no terrain detail. Only queries biome IDs, never computes height.

Both are the "gold standard" tools and both are **simpler** than our current renderer.

## 6. Recommended Implementation Priority

| Priority | Improvement | Impact | Effort |
|---|---|---|---|
| 1 | Surface-block colouring | HIGH | LOW |
| 2 | Water depth shading (continentalness) | HIGH | LOW |
| 3 | Vegetation density overlay | MEDIUM | LOW |
| 4 | Biome-specific grass/foliage tinting | MEDIUM | MEDIUM |
| 5 | River thinning (higher-res sampling) | MEDIUM | LOW |
| 6 | Badlands terracotta banding | LOW | HIGH |

Items 1-3 alone would transform the render from "developer debug view" to "recognisable terrain at world-map scale."
