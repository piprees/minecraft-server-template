# scripts/data — build inputs and generated audits

| File | Direction | Owner script |
| --- | --- | --- |
| `structure-dials.csv` | INPUT (hand-curated) | `gen-structure-presets.py` reads it to build the structure datapack presets AND the mod's `structure_themes.json` resource |
| `structure-sets-extracted.csv` | OUTPUT (regenerated) | `extract-structure-sets.py` — audits every structure set in the pinned jars/datapacks/vanilla; also writes `config/custom-dimensions/extractors/structures.json` |

`structure-dials.csv` columns: `mod`, `structure_set`, `structures`,
`theme` (dungeon/settlement/maritime/landmark/deco/loot), `current`
(mod-default `spacing/separation` + `f=frequency`), `dims`, `rec_global`
(`keep default` or `CONFIGURE: ...`), `rec_peaceful_dims`,
`rec_hard_dims`, `notes` (vanilla-set overrides, custom placement types,
by-design ultra-rares).

Workflow: after a structure-mod pin bump (weekly mod-updates PR), re-run
`extract-structure-sets.py` to refresh the audit, review baseline drift
against `structure-dials.csv` `current` values (the preset generator warns
on drift), then re-run `gen-structure-presets.py`. Never hand-edit
`structure-sets-extracted.csv` — it is regenerated wholesale.
