# Non-Overworld Render Quality — Phase 4 Investigation

## Root Cause

`biome_renderer.py:188-189` restricts the spline evaluator to overworld only. Non-overworld families fall back to `h = cont * 40.0 - ero * 20.0 + 63.0` — a linear formula that ignores weirdness/ridges_folded entirely and produces flat, blobby terrain.

For nether, continentalness noise doesn't even exist, so `cont` defaults to 0.0, collapsing the formula to just `-ero * 20 + 63` — one variable driving all terrain.

## Available Noise Per Family

| Family | temperature | humidity | continentalness | erosion | weirdness |
|---|---|---|---|---|---|
| overworld | yes | yes | yes | yes | yes |
| nether | yes | yes | NO | yes | yes |
| end | yes | yes | yes | yes | yes |
| paradise_lost | yes | yes | NO | yes | yes |

## Fix: Per-Family Height Functions

### Nether (y=0..128, bedrock ceiling)
```python
rf = ridges_folded(weirdness)
h = 64.0 + erosion * 25.0 + rf * 15.0
h = clamp(8.0, 120.0)
```

### End (floating islands, void gaps)
```python
rf = ridges_folded(weirdness)
if continentalness < -0.1:
    h = 0  # void
else:
    land = min(1.0, (cont + 0.1) / 0.3)
    h = (50 + cont * 40 + rf * 20 - ero * 10) * land
    h = clamp(5, 120)
```

### Paradise Lost (elevated highland terrain)
```python
rf = ridges_folded(weirdness)
h = 80.0 + erosion * 25.0 + rf * 20.0
h = clamp(10, 140)
```

## Future: Mod Spline Extraction

For full fidelity, extract offset splines from Incendium, Nullscape, and Paradise Lost JARs — same technique used for Terralith. The per-family formulas above are a massive improvement over the current single linear formula.
