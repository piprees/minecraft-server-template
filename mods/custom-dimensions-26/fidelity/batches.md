# W10t — server-lane batches

Order: base worlds first (and the four clones, which compile the_aviary's generator, so any change to it moves them); then by generator kind so a batch shares its tools and gaps; inside a group, worst first by the W9b scoreboard's S:b (share of 1.21.1 biomes expressed; `/tmp/claude-0/w9b/scoreboard.json`). Each batch starts from `intent/`, `targets/` and `route/` for its slugs; baseline pass/total per slug is in `summary.md`.

Gap ids are in `gaps.md`. Difficulty: **low** — definition edits the kit already supports (weights, palettes, liquids, layout); **medium** — kit edits plus a terrain re-tune and re-cutting regions; **high** — waits on a gap that needs a new terrain family or compiler change.

## Batch 1 — base worlds and the aviary's clones (8)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_aviary | — (no dossier) | G9, G10, G11 | medium: survey first; give it a terrain |
| overworld | — | G8 | low |
| the_nether | — | G1, G2 | low for the biome list; high for relief |
| the_end | — | survey metric "End-stone ground" | low (re-survey only) |
| the_snowbound_isle | 55.6 | G9, G10 | medium (un-clone) |
| the_abyssal_shrine | 55.6 | G9, G10, G11 | high (ring of basins) |
| the_violet_spire | 81.8 | G9, G12, End crystal biome | high |
| the_sunken_temple | 88.9 | G9, G11 | medium (basin_islands exists) |

Shared tools: floating_islands and basin_islands terrains, `liquids.basins`, the clone/un-clone decision (owner), the aviary's survey. Survey the_aviary before touching the clones.

## Batch 2 — Nether presets (11)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_forged_depths | 37.5 | G1 (sunken deep), G2, G15 | high |
| the_buried_age | 44.4 | G1 (corridor) | low (raise lava sea), medium to hit two thirds |
| the_blackstone_keep | 44.4 | G2 | low |
| the_gilded_pit | 50.0 | G1 (pit and ledge), G2 | high |
| the_molten_flats | 50.0 | G1 (flat floor) | high |
| the_bloodroot_wastes | 50.0 | G2 | low |
| the_obsidian_sanctum | 50.0 | — | low (obsidian cliffs, lower lava) |
| the_boneyard | 54.5 | G2, bone reef biome | medium |
| the_furnace_halls | 60.0 | survey: structure census | low |
| the_wailing_narrows | 66.7 | G1 (chasms) | high |
| the_ember_fields | 69.2 | G1 (rolling floor), G2 | medium for biomes, high for relief |

Shared tools: weights, `liquids.seaLevel` (every Nether preset's lava cover is 18–45%), `materials.cliffs`. Run the low ones first; G1 and G2 are one compiler build for all eleven plus the_nether.

## Batch 3 — End presets and floating islands (11)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_blighted_maw | 0.0 | G5, G10 (or move to floating_islands) | medium |
| the_catalyst_maw | 0.0 | G3, G5, sculk biome | high |
| the_fractured_halls | 11.1 | G3, G4, End crystal biome | high |
| the_crumbling_reaches | 11.1 | G3, G4, End crystal biome | high |
| the_end_citadel | 14.3 | G3, G4 | medium (G3 alone fixes most) |
| the_pillared_void | 16.7 | G12, G4 | high |
| the_burning_archipelago | 25.0 | G5 (or move to floating_islands) | medium |
| the_pale_reach | 44.4 | — | low |
| the_starwell | 50.0 | mirage isles biome (optional) | low |
| the_shattered_skies | 65.0 | — | low |
| the_glacial_drift | 83.3 | — | low |

Shared tools: island-aware End layout (G3), End materials (G5), floating_islands terrain coverage. The four floating-islands dimensions are low and show what open-sky coverage does before the End work.

## Batch 4 — caves and void or special generators (11)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_emberglass_foundry | 16.7 | — | low |
| the_underdark | 21.4 | G6, sculk biome | medium |
| the_weeping_vault | 40.0 | G6 | low with palettes |
| the_sculked_beyond | 50.0 | G6, sculk spread | medium |
| the_luminous_caverns | 64.3 | G6, frosted and salt caves (optional) | low |
| the_fungal_lanterns | 85.7 | G6, G1 (ledges) | low for floors, high for ledges |
| the_slatemouth | 0.0 | G13 | low (keep) |
| the_icebound_rift | 0.0 | G13, survey: biomes over void | medium |
| the_red_monument | 0.0 | survey: structure census | low |
| the_tiled_expanse | 90.0 | G14 | medium |
| the_canvas | — | — | none (owner: forced structures) |

Shared tools: `liquids.aquifers` (four of the six cave floors are 27–34% water), Nether palettes on the caves preset (G6), the void generator. The cave half shares G6 with Batch 6's Nether-biome overworlds.

## Batch 5 — overworld presets, worst half (10)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_tidepools | 0.0 | rock pools biome (optional) | medium (islands shelf) |
| the_lost_outpost | 25.0 | G8 | low |
| the_greywoods | 27.3 | G8, cedar biome | low |
| the_wuthering_wisteria | 33.3 | G8 | low |
| the_souldrift | 36.4 | G6, G8, sculk spread | medium |
| the_dustbowl | 42.9 | G7 | low |
| the_roothold | 50.0 | old-growth biome (optional) | low |
| the_ruined_timberland | 50.0 | G7 | low |
| the_whispering_wilds | 52.0 | G8, moonlight grove | medium (18 biomes to halve) |
| the_rosebluff | 53.8 | G8 | low |

## Batch 6 — overworld presets, better half and the two vanilla presets (10)

| Slug | S:b | Depends on | Difficulty |
| --- | --- | --- | --- |
| the_lantern_pools | 53.8 | G8 | low |
| the_chalk_meadows | 58.3 | G8 | low |
| the_miredeep | 58.3 | G8, bayou biome (optional) | low |
| the_teal_corruption | 58.3 | G6, G7 | medium |
| the_claymarsh | 60.0 | — | low |
| the_soulfields | 60.0 | G6, G7, survey: structures by distance | medium |
| the_dripping_pines | 60.0 | temperate rainforest biome | low |
| the_ashgrove | 62.5 | G8, ashen savanna biome | low |
| the_amplified_reaches | — | G8 | low |
| the_endless_expanse | — | — | low (survey placeness) |

Shared tools for Batches 5 and 6: `terrain.layout` and `terrain.scales.continents` against the seas (G8 automates it), erosion and terraces bases for flat wetlands and mesas, per-biome palettes.

## Build order for the gaps

G6 and G7 (compiler defaults, no new terrain) unblock 12 dimensions across Batches 4–6; G3 and G5 unblock most of Batch 3; G1 and G2 are the one big terrain build, for 14 roofed dimensions; G9–G11 decide Batch 1's clones. Library biomes can be authored in parallel by a non-server lane.
