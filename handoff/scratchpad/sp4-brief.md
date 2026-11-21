SP4 — set-pieces kit, part 3 (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md; handoff/scratchpad/sp2-brief.md (its facts, rules and report format all apply to you); docs/design/rebuild-26/places/set-pieces.md (all of it, as SP2 left it); docs/design/rebuild-26/lore/placement.md and stories.md; decisions RB-39 to RB-45. SP2 is merged on the branch head (b1dc4fd2): read its code first (set pieces, `structures.sites`, `age`, VanillaForms, NamedPlaces) and extend it, never replace it. Package name sp4: log /tmp/claude-0/sp4/progress.log, one line per step.

## Scope
1. SP4 exactly as docs/design/rebuild-26/places/set-pieces.md § SP4 states it, every acceptance item: `wreck` (with `tilt`), `skeleton`, `camp`, `hovel`, `farm`, `works`, `cell`, `gate` (with `marker` through `ExitShrines`, and `state: attempt` with a `study` per § Deep-time links and RB-41); `mob` with name, attributes, equipment; `archaeology` (and the age archaeology share SP2 left out).
2. From docs/design/rebuild-26/lore/placement.md § Gaps: G10 things (`banner`, `stand`, `frame`, `lectern` with a written book of a few fragmentary lines, `pot`, and a chest `arrangement` of fixed items in fixed slots beside its loot table), G11 items with components (trim pattern and material, dye, banner patterns, map decorations; checked at compile), G12 remains (a preset: bones, a skull, a stand in gear), G13 re-skin (a per-dimension block-swap processor applied to mod structures by id or group through structure control, drawing on `materials`, plus the same `age` weathering). RB-43 binds every text: lectern lines never name a faction, dimension, epoch or person; the compiler refuses a line containing a dimension slug or display name or a people's name (piglin, enderman, villager, human, illager, witch) — that check is G20, build it here since lecterns land here.
Out of scope: shapes and seats (SP3), loot scaling (DL1), study placement and unlabelled maps (LR1).

## Rules
As sp2-brief.md, with these changes:
- Trap drafts are numbered from T350.
- Another kit agent (SP3 or SP4) works beside you in its own worktree. Keep to your own files where you can. Where you must touch a shared file (the schema, SetPieceSpec, the compiler), keep the change small, and say so in your report.
- Budget: about 220 tool steps.
- Before any build or boot, message the lead (to: main) and wait for "the lane is yours".
