# Terrain Height Generation — Density Functions and Splines

## Key Finding

**Surface height CAN be approximated from climate parameters alone** — within ±5-15 blocks for most terrain, which is more than sufficient for top-down map rendering. The approach: sample continentalness, erosion, weirdness → fold weirdness → evaluate offset spline → derive surface Y.

**Terralith completely replaces the terrain generation pipeline** — not just biomes. Any height computation must use Terralith's spline data, not vanilla's.

## The Density Function Pipeline

MC 1.18+ terrain is determined by evaluating a tree of density functions per-block:

```
final_density > 0 → stone (solid)
final_density ≤ 0 → air

final_density = squeeze(sloped_cheese - cave_carving)

sloped_cheese = 4.0 * quarter_negative(
    (depth + jaggedness * half_negative(jagged_noise)) * factor
  ) + base_3d_noise

depth = y_clamped_gradient(-64, 320, 1.5, -1.5) + offset

offset = spline(continentalness, erosion, ridges_folded)  ← THIS IS THE KEY
factor = spline(continentalness, erosion, ridges_folded)
jaggedness = spline(continentalness, erosion, ridges_folded)
```

### Height Approximation Formula

```python
ridges_folded = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)
offset = evaluate_spline(continentalness, erosion, ridges_folded)

# Simple approximation:
surface_Y ≈ 128 * (offset + 0.5)

# Better approximation (needs factor spline):
surface_Y ≈ 128 * (offset + 0.5 - 0.2734375 / factor) + 8
```

### What the Approximation Misses

| Component | Effect | Impact on Top-Down Map |
|---|---|---|
| base_3d_noise | ±10-20 block variation | Averages out at map scale |
| jaggedness | Mountain peaks sharper | Off by 10-30 blocks in peaks only |
| cave openings | Surface caves lower visible Y | Not significant for map rendering |
| aquifer system | Underwater caves | Invisible from above |

**Accuracy: ±5-15 blocks for most terrain.** Mountain peaks may be off by more. Oceans and plains very accurate. **Good enough for hillshade on a top-down map.**

## The Spline System

Cubic Hermite splines, nested 3 levels deep:

```
offset = spline(continentalness →
           spline(erosion →
             spline(ridges_folded → scalar_value)))
```

Each spline has:
- **coordinate**: which density function to sample
- **points**: array of {location, value (scalar or nested spline), derivative}

### Vanilla Continentalness Breakpoints (in offset.json)

| Continentalness | Terrain Type |
|---|---|
| -1.2 to -1.05 | Mushroom Fields |
| -1.05 to -0.455 | Deep Ocean |
| -0.455 to -0.19 | Ocean |
| -0.19 to -0.11 | Coast |
| -0.11 to 0.03 | Near-inland |
| 0.03 to 0.3 | Mid-inland |
| 0.3 to 1.0 | Far-inland (mountains) |

## Terralith's Modifications

**Terralith replaces ALL of these files** under `data/minecraft/worldgen/density_function/overworld/`:

- `offset.json` — **completely custom** spline with different height curves, canyons, floating-island offsets, deep trenches (~2000 lines)
- `factor.json` — custom factor spline (more variation: ranges up to 20 vs vanilla's ~10 max)
- `jaggedness.json` — custom jaggedness distribution
- `sloped_cheese.json` — same formula, different parameters
- All noise router files: `continents.json`, `erosion.json`, `ridges.json`, etc.
- `noise_settings/overworld.json` — 7,418 lines including complete surface rule tree

Also adds/modifies:
- `base_erosion.json`, `erosion.json` — modified climate noise parameters
- `caves/entrances.json`, `caves/pillars.json` — custom cave shapes
- 294 biome JSON files

**To implement height for our modded server:** extract and parse Terralith's offset.json (and optionally factor.json) spline data. The splines are pure data — JSON objects with cubic Hermite control points.

## Implementation Path

### Step 1: Parse Terralith's Splines

Extract `offset.json` from the Terralith JAR under `data/minecraft/worldgen/density_function/overworld/`. Parse the nested spline tree into a Python data structure.

### Step 2: Implement Cubic Hermite Interpolation

```python
def hermite_interp(t, p0, m0, p1, m1):
    """Cubic Hermite interpolation between two control points."""
    t2 = t * t
    t3 = t2 * t
    h00 = 2*t3 - 3*t2 + 1
    h10 = t3 - 2*t2 + t
    h01 = -2*t3 + 3*t2
    h11 = t3 - t2
    return h00*p0 + h10*m0 + h01*p1 + h11*m1
```

### Step 3: Evaluate the Spline Tree

```python
def evaluate_spline(spline, continentalness, erosion, ridges_folded):
    """Evaluate nested spline: cont → erosion → ridges → scalar."""
    # Find continentalness interval, interpolate
    # At each level, the value may be a nested spline or a scalar
    # Recurse into nested splines with the next parameter
    pass
```

### Step 4: Compute Surface Y

```python
ridges_folded = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)
offset = evaluate_spline(offset_spline, continentalness, erosion, ridges_folded)
surface_y = int(128 * (offset + 0.5))
```

### Step 5: Use in Renderer

Replace the current hillshade (from continentalness/erosion) with actual computed surface heights. This gives:
- Realistic coastlines (height drops below sea level at the right continentalness)
- Mountain profiles that match the actual game
- Accurate valley depths

## Cubiomes Height Support

cubiomes does **NOT** currently compute terrain height for 1.18+. It has `approxSurfaceHeight` in the viewer but it's described as "very generous" (inaccurate). The core library explicitly states "does not provide block-level world generation."

## From Density to Surface Blocks

After determining height, surface rules determine the actual block:

| Biome(s) | Top Block | Below (3-4 layers) |
|---|---|---|
| Most biomes | grass_block | dirt |
| Desert, Beach | sand | sandstone |
| Badlands | red_sand | terracotta bands |
| Mushroom Fields | mycelium | dirt |
| Old Growth Taiga | podzol | dirt |
| Ice Spikes | snow_block | dirt |
| Mangrove Swamp | mud | mud |
| Stony Shore / steep mountains | stone | stone |
| Nether Wastes | netherrack | netherrack |
| Crimson Forest | crimson_nylium | netherrack |
| Warped Forest | warped_nylium | netherrack |
| End biomes | end_stone | end_stone |

Surface depth: `floor(surface_noise(X,Z) * 2.75 + 3.0 + positional_noise * 0.25)`
