# the_fractured_halls — noise:end, no central island; place kind: meeting (broken crystal ridges with sheltered groves)
> "Fractured halls — endermen gate fortresses and enderkeeps in broken crystal country. No cities survived whatever happened here; only the military architecture endured, and moonlight groves have taken the sheltered ground between the ridges. The keeps are still building. What stands beside the portal becomes part of the wall."

- **First:** an end gate fortress at spawn (wants end_gate_fortress, astral_hideaway near_spawn).
- **Mostly:** broken crystal ridges (1.21.1 crystal_peaks, amethyst_canyon, shadowlands). **Rarely:** moonlight groves in the sheltered ground between ridges (moonlight_valley, moonlight_grove); a crashed mega-ship near the border.
- **Layers:** End islands; ridges as relief; groves in low ground.
- **Atmosphere:** no 1.21.1 environment (vanilla End).
- **Place kind:** a meeting of two: crystal ridges and groves between.
- **Baseline wrong (W9g b1 full):** no ridges: `terrain.surfaceHeight.p95` 64, reliefMed 21, mountains 0.0; no crystal on the surface (end_stone 43.8% of ground; amethyst absent); the groves are pale_garden 10.0 and old_growth_birch_forest 1.1, both "features name trees, none" and mostly void (no trees: `vegetation.treesPerHectareByLayer.surface` 0.0); ice_starfield listed but not placed; land regions tiny (largest 0.7).
- **v2 responsible:** end preset; groves mapped to overworld forests whose trees do not grow on end stone; crystal_hollows (a cave biome) at weight 2.9 for amethyst_canyon.
