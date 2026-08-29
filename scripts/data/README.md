# scripts/data — build inputs and generated audits

| File | Direction | Owner script |
| --- | --- | --- |
| `structure-dials.json` | INPUT (hand-curated) | `gen-structure-presets.py` reads it to build the structure datapack presets; `gen-structure-groups.py` reads it for the authoritative `theme` and `rarity` |
| `structure-sets-extracted.json` | OUTPUT (regenerated) | `extract-structure-sets.py` — audits every structure set in the pinned jars/datapacks/vanilla; also writes `config/custom-dimensions/extractors/structures.json`. `gen-structure-groups.py` reads it as the set census + spacing source |

Both files are JSON arrays of objects, one object per structure set, with the same field names the two files have always carried.

**Neither file alone is a complete picture.** `structure-dials.json` is
hand-curated and only ever covered sets the preset generator might retune, so
it skips vanilla (bar villages) and several mods — including `minecraft:igloos`,
`desert_pyramids`, `jungle_temples`, `ocean_monuments` and `mineshafts`.
`structure-sets-extracted.json` is the machine census of every set that exists,
but its `theme` field is a first-match-wins regex with `landmark` as the
catch-all default (163 of 377 land there), and the two disagree on theme for
186 of the 354 sets they share. `gen-structure-groups.py` joins them: dials
theme wins, extracted supplies the census and spacing, and a hand-reviewed
`CURATED` table in that script covers the 23 sets dials never carried.

Rarity is a judgement about the content, not about the grid it sits on, so
dials carries it directly for the 221 Moog's sets (`mvs`/`mns`/`mss`/`mes`/
`mtr`), banded from the measured footprint spans in
`.handoff/world-fixes/sizes/`: under 40 blocks common, 40-69 uncommon, 70-109
rare, 110+ endgame, with a `dungeon` theme one tier rarer. Their extracted
spacing is inflated 1.65x by Moog's own placement constructor
(`TROUBLESHOOTING.md#t48`), which made the spacing thresholds read most of the
pack as endgame.

`structure-dials.json` fields: `mod`, `structure_set`, `structures`,
`theme` (dungeon/settlement/maritime/landmark/deco/loot), `rarity`
(common/uncommon/rare/endgame, optional — a row without one falls back to
`gen-structure-groups.py`'s spacing thresholds), `current`
(mod-default `spacing/separation` + `f=frequency`), `dims`, `rec_global`
(`keep default` or `CONFIGURE: ...`), `rec_peaceful_dims`,
`rec_hard_dims`, `notes` (vanilla-set overrides, custom placement types,
by-design ultra-rares).

Workflow: after a structure-mod pin bump (weekly mod-updates PR), re-run
`extract-structure-sets.py` to refresh the audit, review baseline drift
against `structure-dials.json` `current` values (the preset generator warns
on drift), then re-run `gen-structure-presets.py` **and**
`gen-structure-groups.py`. Never hand-edit `structure-sets-extracted.json` —
it is regenerated wholesale.

`gen-structure-groups.py --check` exits 1 when its outputs are stale, so it
can gate a release without rewriting anything.
