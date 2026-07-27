---
title: Tectonic Dials
description: Every Tectonic 3.x config key, its default, its effect, and the platform's shipped value — verified against Tectonic 3.0.26 source/wiki
tags: [tectonic, terrain, worldgen, erosion, mountains, oceans]
---

# Tectonic 3.x dial reference

Source of truth: [config/tectonic.json](../../../../config/tectonic.json) (read the comments — every CHANGED value has rationale). Verified against the pinned jar (tectonic 3.0.26-fabric-21.1): the mod reads `config/tectonic.json` (flat, NOT `config/tectonic/tectonic.json`) via `ApollibConfigHolder`; every key and default was extracted from `ConfigState` bytecode. A parse failure is SILENT — grep boot logs for `"Couldn't load tectonic config file"`.

## Dial table

| Section.key | Factory default | Shipped value | Effect |
| --- | --- | --- | --- |
| `continents.erosion_scale` | 0.25 | **0.12** | **The main "wider mountains" dial.** Lower ⇒ thicker mountain ranges, wider terrain between them. Halving ≈ doubles the mountain/valley wavelength. |
| `continents.ridge_scale` | 0.25 | **0.18** | Lower ⇒ wider rivers, valleys and plateau systems |
| `continents.continents_scale` | 0.13 | **0.1** | Lower ⇒ larger continents and oceans |
| `continents.flat_terrain_skew` | 0.1 | **0.3** | Higher favours flat/rolling terrain over stepped plateaus |
| `continents.ocean_offset` | -0.8 | -0.8 | Land/ocean skew (above -0.45: no deep oceans; above -0.2: no oceans) |
| `continents.rolling_hills` | true | true | Smooth hilly plains |
| `global_terrain.vertical_scale` | 1.125 | **1.0** | Height multiplier above sea level; 1.0 ⇒ gentler relief |
| `global_terrain.elevation_boost` | 0 | **0.3** | Extra vertical scale applied to mountains faster than lowlands |
| `global_terrain.min_y` | -64 | -64 | Build/gen floor |
| `global_terrain.max_y` | 320 | **448** | Build/gen ceiling — multiples of 16. Raising vertical_scale without raising max_y causes generation issues. |
| `global_terrain.ultrasmooth` | false | **true** | Removes staircase/terracing artifacts. Caveat: odd generation in deep oceans + windswept biomes. |
| `biomes.temperature_scale` | 0.25 | **0.15** | Lower ⇒ larger climate regions (biome layout, not shape) |
| `biomes.vegetation_scale` | 0.25 | **0.15** | Same as temperature but for the vegetation axis |
| `experimental.alternate_erosion_scaling` | false | false | Companions to low scale values; kept false — unverified with c2me's DFC disabled |
| `experimental.alternate_continents_scaling` | false | false | Same — ship false until verified in the local loop |
| `oceans.ocean_depth` | -0.22 | -0.22 | Ocean depth |
| `oceans.deep_ocean_depth` | -0.45 | -0.45 | Deep ocean depth |

## Interaction notes

- Halving `erosion_scale`/`ridge_scale` doubles the wavelength of the mountain/valley rhythm; keeping `vertical_scale` ≈ 1 with a mild `elevation_boost` spreads the same heights over wider slopes — gentler average gradient is most of what "realistic proportions" reads as.
- Widen the climate scales (`temperature_scale`/`vegetation_scale`) alongside the terrain scales or biomes stripe across the now-larger landforms.
- `max_y` interacts with the two jar-baked noise presets: `adventure:wide` assumes the 448 height; dropping global `max_y` back to 320 trims pinned-wide dimensions at 320.
- The consumer override (`overlay/config/tectonic.json`) is a **full file replacement** — always copy the complete platform file as a starting point, never a partial config.
