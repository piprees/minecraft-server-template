SP3 — set-pieces kit, part 2 (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md; handoff/scratchpad/sp2-brief.md (its facts, rules and report format all apply to you); docs/design/rebuild-26/places/set-pieces.md (all of it, as SP2 left it); docs/design/rebuild-26/lore/placement.md and stories.md; decisions RB-39 to RB-45. SP2 is merged on the branch head (b1dc4fd2): read its code first (set pieces, `structures.sites`, `age`, VanillaForms, NamedPlaces) and extend it, never replace it. Package name sp3: log /tmp/claude-0/sp3/progress.log, one line per step.

## Scope
1. SP3 exactly as docs/design/rebuild-26/places/set-pieces.md § SP3 states it, every acceptance item: shapes `crater`, `pit`, `fall`, `wall`, `line`, `column`, `field`, `crown`, `platform`; seats `roof`, `wall`, `water`; liquid ticks; `terrainAdaptation` default per piece.
2. From docs/design/rebuild-26/lore/placement.md § Gaps: G6 the lived-in layer (tiny pieces in a group with density per km² by distance band and biome, siting on a path line, view edge, by water, in a hollow; a `line` style `path` following the terrain), G7 layering (an element seated on another element, each with its own age), G8 approach (an `approach` any place can carry, ours or a mod's: path to the nearest path or site, steps, bridge over a gap, ladder or chain down a face, debris line), G9 footings (fill under a footprint down to the dimension's own stone, cut terraces, decline when the footprint's spread exceeds its slope limit; a terrain-adaptation override per structure). Also banding `age` by distance (the radial default SP2 left out).
Out of scope: assemblies and things inside (SP4).

## Rules
As sp2-brief.md, with these changes:
- Trap drafts are numbered from T330.
- Another kit agent (SP3 or SP4) works beside you in its own worktree. Keep to your own files where you can. Where you must touch a shared file (the schema, SetPieceSpec, the compiler), keep the change small, and say so in your report.
- Budget: about 220 tool steps.
- Before any build or boot, message the lead (to: main) and wait for "the lane is yours".
