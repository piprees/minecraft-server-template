# Terrain Height Evaluator — Implementation Report

## Files Created

- `scripts/seed/terrain_height.py` (294 lines) — spline evaluator + height computation
- `scripts/seed/terrain_splines.json` (11KB) — pre-extracted spline data from Terralith

## What It Does

Evaluates Terralith's nested cubic Hermite spline tree to compute approximate surface height from three climate parameters: continentalness, erosion, and weirdness.

## Formula

```python
ridges_folded = -(abs(abs(weirdness) - 0.6666667) - 0.3333334)
offset = -0.5037500262260437 + spline(continentalness, erosion, ridges_folded)
surface_Y = int(128 * (1 + offset))
```

The formula was corrected from the initial estimate (`128 * (offset + 0.5)`) by deriving from MC's Y-clamped gradient: `depth(Y) = 1.5 - 3*(Y+64)/384`. At the surface where `offset + depth = 0`, solving gives `Y = 128 * (1 + offset)`.

## API

```python
evaluator = TerrainEvaluator()  # loads terrain_splines.json

evaluator.surface_height(continentalness, erosion, weirdness)  # → int (Y level)
evaluator.factor(continentalness, erosion, weirdness)           # → float (vertical stretch)
evaluator.offset_raw(continentalness, erosion, weirdness)       # → float (raw offset)
```

## Performance

- 2.1 µs/evaluation (~475K evals/second)
- Splines are "compiled" from JSON dicts into nested tuples on load for fast evaluation
- Binary search for spline interval lookup

## Height Distribution (Synthetic Grid Test)

| Metric | Value |
|---|---|
| Min height | -37 (deep ocean) |
| Max height | 228 (mountain peaks) |
| Mean height | 69.4 (just above sea level 63) |

Peak of the distribution is at Y=60-70 (sea level area), with a long tail to Y=228 for mountain peaks. This matches what Terralith terrain actually looks like.

## Spline Data

Extracted from Terralith's `offset.json` and `factor.json`:
- Offset spline: 3-4 levels deep, 12 top-level continentalness breakpoints
- Factor spline: similar structure, controls vertical stretch
- Self-referencing coordinates at lower levels (a ridges spline can reference continentalness again)
- All four coordinate types handled: continentalness, erosion, ridges_folded, weirdness

## Extraction

```bash
python3 scripts/seed/terrain_height.py --extract
```

Reads raw JSON from the scratchpad (extracted from Terralith JAR) and saves compiled spline data to `terrain_splines.json`. Only needs to run once; the JSON is committed.

## Caveats

- ±5-15 blocks accuracy for most terrain (misses base_3d_noise, jaggedness)
- Mountain peaks may be off by more (jaggedness adds significant height there)
- Oceans and plains are very accurate
- Only applies to overworld-family dimensions (nether/end have different height models)
