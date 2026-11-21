K14 — Why curated structures have zero starts (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md, handoff/scratchpad/sp2-brief.md (facts, rules, report format), decisions RB-39 to RB-45. Package k14: log /tmp/claude-0/k14/progress.log, one line per step.

## The problem (verified by the lead from logs and blind reviews)
Blind reviews of the ten Batch 1–2 dimensions keep finding curated structures with 0 starts. The ones below are listed, wanted or forced in mods/custom-dimensions-26/definitions/<slug>.json, but never start (reviews: /tmp/claude-0/blind/r3/*.md and r2/*.md):
- **the_sunken_temple:** `mtr:ocean_temple` (forced and wanted), `minecraft:monument`, `ati_structures:underwater_dungeon`, `mss:large_tower`. No refusal line in the boot log (/tmp/claude-0/w10u/runs/b4/server.log).
- **the_abyssal_shrine:** `ati_structures:warden_monument` and `ati_structures:underwater_dungeon` (wanted). The log says "underground ... did not start: no ground under it". The want's chunk chooser picked a chunk that UndergroundGround then refuses. `mss:castle_ruin` (wanted) has 0 starts and no log line.
- **the_violet_spire:** `mes:enderwatch_tower` (listed), `mes:mystical_archway` (forced), 0 starts.
- **the_forged_depths:** the mns houses, walls and towers (listed), 0 starts.
`/customdim starts <dim>` counts confirmed starts per structure (StartsCommand.java).

## Do
1. For every structure above, find the cause with evidence: code, census, the structure's own jigsaw or generation rules, biome tags, group membership, set-id-versus-structure-id confusion in `list`, and site filters. One line each: cause, fix, generic or curation.
2. **Generic fixes, each with a test that fails without it:**
   - The site chooser for forced starts and wants (StructureSites.choose / choosePlaced) must apply every refusal generation applies: UndergroundGround, the site filters, and the roof seating that SP3 is adding. That way a want lands where it can stand, or logs a WARN that nothing fits. Coordinate through the lead with SP3 (it is changing StructureRuntime.refusal for roofed worlds).
   - Make a silent failure loud. Any listed, wanted or forced structure with 0 starts inside the border after the survey gets a WARN line in the boot log, with the reason when it is known.
3. Curation causes (wrong id, a structure that can never start in that terrain) go in your report as a table for W10u: slug, structure, why, a suggested replacement that would fit RB-45.
4. Prove it in one boot: `/customdim starts` for the four slugs before and after, with the pack's structure mods.

## Rules
- As sp2-brief.md.
- Trap drafts numbered from T370.
- Budget: about 160 tool steps.
- Message the lead (to: main) before any Gradle build or boot.
