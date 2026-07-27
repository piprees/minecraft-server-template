# Non-Overworld Render Quality — Investigation & Fix Brief

## Problem

The biome map renderer produces excellent results for **overworld** dimensions — clear terrain features, rivers, coastlines, mountains, biome boundaries, ocean depth. But **nether**, **end**, and **paradise_lost** dimensions produce noisy, fuzzy renders that look like TV static with biome-coloured tinting. Even at hi-res 16km (1024×1024), the non-overworld images are unusable for seed evaluation.

### What good looks like (overworld)

Clear terrain at world-map scale: recognisable coastlines, river systems cutting through valleys, mountain ridges with hillshade, distinct biome boundaries (desert/forest/snow transitions), ocean depth gradient. You can evaluate a seed's geography at a glance.

### What bad looks like (nether / paradise_lost / end)

- **Paradise Lost**: uniform green noise with grey speckles. No discernible terrain features — looks like a carpet texture, not a landscape. Should show floating sky islands with valleys between them.
- **Nether**: red/crimson/teal noise with no structure. Should show distinct biome regions (crimson forest, soul sand valley, basalt deltas) with terrain variation (lava lakes, pillars, valleys).
- **End**: slightly better — shows island shapes against void — but the islands themselves are blobby and lack internal terrain detail.

## Root Cause Analysis

The overworld renders look good because **Terralith's offset spline** (extracted from the Terralith JAR, stored in `terrain_splines.json`) produces accurate terrain heights with 265-block range and complex non-linear shaping. The spline maps (continentalness, erosion, ridges_folded) → surface Y with cubic Hermite interpolation.

For nether/end, we extracted splines from Incendium and Nullscape, and added the missing `continentalness` noise config for the nether. The splines now produce large height ranges (nether: 264, end: 563). **But the height variation is too high-frequency** — the spline outputs change rapidly between adjacent sample points, creating the noisy/static appearance instead of smooth terrain features.

### Likely causes

1. **The extracted splines may not be the right density functions** — the nether/end terrain generation pipeline differs fundamentally from the overworld. The overworld uses `offset → depth → sloped_cheese → final_density` for a 2D heightmap. The nether uses a 3D noise field between floor (y=0) and ceiling (y=128) bedrock. The "offset" spline in the nether may control something other than surface height.

2. **Noise coordinate mismatch** — the BiomeSampler's noise generators use the configs from `noise_configs.json` which were extracted from the mod's biome source. But the terrain density functions may use DIFFERENT noise generators (different noise IDs, different octave configs) than the biome source. We're evaluating terrain splines with biome noise values.

3. **The hillshade amplification** — the high-frequency spline output gets amplified by the hillshade algorithm (`shade_k = 0.20-0.25` for nether/end vs 0.12 for overworld), creating even noisier contrast.

4. **Missing smoothing** — the overworld spline naturally produces smooth output because Terralith's spline is designed for visual terrain. The nether/end splines may be designed for 3D carving (yes/no solid at each Y level) rather than 2D surface height.

## Architecture Context

### Relevant files

| File | Purpose |
|---|---|
| `scripts/seed/biome_renderer.py` | The renderer — `render_biome_map()` computes heights + hillshade per pixel |
| `scripts/seed/terrain_height.py` | `TerrainEvaluator` — loads and evaluates per-family splines |
| `scripts/seed/terrain_splines.json` | Extracted spline data: overworld (Terralith), nether (Incendium), end (Nullscape) |
| `scripts/seed/biome_sampler.py` | Multinoise biome sampling — produces climate parameters per (x, z) |
| `scripts/seed/noise_configs.json` | Per-family noise generator configs (octaves, amplitudes, xz_scale) |
| `scripts/seed/surface_rules.py` | Biome → surface block colour + vegetation density |

### Research notes

Extensive R&D documentation is at `scripts/seed/spike/`:
- `01-unmined-cli-research.md` — why we can't use unmined-cli (needs full block data)
- `02-cubiomes-and-mca-research.md` — why biome_sampler.py IS the modded cubiomes
- `05-terrain-height-research.md` — how MC terrain heights work (density functions, splines)
- `10-non-overworld-quality-investigation.md` — earlier investigation into this problem
- `13-mod-spline-extraction.md` — extraction of Incendium/Nullscape/Paradise Lost splines

### Mod stack

- **Terralith** (overworld terrain) — completely replaces the overworld density function tree
- **Incendium** (nether terrain) — replaces the nether noise_settings with custom density functions
- **Nullscape** (end terrain) — replaces the end noise_settings with custom density functions
- **Paradise Lost** — a separate dimension mod; delegates terrain to Terralith's overworld spline

The mod JARs are at `/Users/pip/Projects/elfydd/data/mods/`:
- `Terralith_1.21.x_v2.6.2.jar`
- `Incendium_1.21.x_v5.4.4.jar`
- `Nullscape_1.21.x_v1.2.14.jar`
- `paradise-lost-2.4.6-beta+1.21.1.jar`

### How the renderer works

1. `BiomeSampler` samples biome + 6 climate parameters at each grid point
2. `TerrainEvaluator.surface_height(C, E, W, family=...)` evaluates the family's spline
3. Hillshade is computed from height gradients between adjacent grid points
4. Biome colour is blended (55% surface-block colour + 45% biome-identity colour)
5. Vegetation density, water depth shading, and weirdness micro-texture are applied

The overworld path works because step 2 produces **smooth, terrain-like** height values. The nether/end path produces **noisy, high-frequency** height values that create the static/noise appearance.

## Approaches to Fix

### A. Investigate and fix the spline evaluation (preferred)

1. Extract the FULL density function tree from each mod's `noise_settings` JSON (not just the offset spline)
2. Trace which density function actually controls surface height vs 3D carving
3. For the nether: the terrain is 3D (caves between floor and ceiling). The "surface" for a top-down map might need to be the highest solid Y in each column, which requires evaluating the full 3D density function — not a 2D offset spline
4. Check whether the noise IDs in the spline coordinates match the noise IDs in `noise_configs.json`

### B. Smooth the spline output (interim)

Apply a spatial low-pass filter (Gaussian blur / box filter) to the height grid before computing hillshade. This would smooth the noise while preserving large-scale terrain features. The overworld doesn't need this because its spline is already smooth.

### C. Fall back to simplified height for non-overworld

Use a simpler formula that avoids the noisy spline entirely — e.g., derive height only from continentalness (broad-scale) and ignore the high-frequency erosion/weirdness components. Reduces noise at the cost of terrain detail.

### D. Reduce hillshade intensity for non-overworld

The high `shade_k` values (0.20-0.25) amplify the noise. Reducing to 0.05-0.08 would mute the noise but also reduce terrain visibility. This is a band-aid, not a fix.

## Success Criteria

A fixed render should show:
- **Nether**: distinct biome regions (crimson reds, warped cyan, basalt grey, soul sand brown) with smooth terrain variation — lava lakes as dark areas, high terrain as lighter areas, biome boundaries clear
- **Paradise Lost**: sky island shapes visible, with highland/forest/cragland terrain variation within islands, void/sky between islands
- **End**: floating islands against void (already partially working), with better internal terrain detail

The renders should be as useful for seed evaluation as the overworld renders are — a player should be able to look at the map and say "this nether has a crimson forest near spawn with a soul sand valley to the east."
