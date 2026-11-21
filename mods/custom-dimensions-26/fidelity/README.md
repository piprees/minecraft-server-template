# Fidelity: intent, targets and drafts

What each dimension is meant to be, how that is measured, and the definition drafts waiting for a server run.

| Path | Holds |
| --- | --- |
| `intent/<slug>.md` | The dimension's 1.21.1 description, biomes and the owner's notes, as an intent card |
| `targets/<set>/<slug>.json` | Measurable targets against a survey dossier; check with `mods/custom-dimensions-client-26/scripts/check-targets.py` |
| `drafts/<set>/<slug>.json` | A definition draft written against kit code, not yet surveyed in a server; applying one means copying it over `definitions/<slug>.json`, surveying, and deleting the draft |
| `batches.md`, `gaps.md` | The order the dimensions are taken in, and the kit gap each depends on |

## Target sets

| Set | Dimensions | From |
| --- | --- | --- |
| `w10t` | the 61 outside the terrain picks | each 1.21.1 description and the owner's notes |
| `w10c` | the dimensions taken by the fidelity pass | placeness from each description, and `w10t` targets restated where they measure the wrong thing (shares over land columns on sky worlds, islands as places) |
| `w9c` | nine reworked terrain picks | their 1.21.1 descriptions |
| `w10a` | twisted_groves, basalt_spires, blossom_gardens, crimson_nexus, highland_crossing | descriptions and the owner's notes |
| `w9h` | the terrain picks | placeness, biome regions measured over land |
| `w9e`, `w9f` | the terrain picks | placeness over all columns; `w9h` restates their biome-region targets, so a miss here that `w9h` passes is a coast taken by a sea biome |

A draft set is named after the kit work it relies on: `k1` roofed worlds, `k2` End and sky worlds, `k3` land share, dry seas, void and grid worlds.
