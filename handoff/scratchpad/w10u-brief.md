W10u — Batch 1–2 rework, gated by blind review (server lane, main checkout)

Read and treat as binding: handoff/scratchpad/common-rules.md, handoff/scratchpad/w10c-brief.md (method and outputs), handoff/scratchpad/w10s-brief.md (what was asked last round), the owner's notes mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md, decisions RB-39 to RB-45 (docs/design/rebuild-26/decisions.md), and docs/design/rebuild-26/lore/placement.md § Fitting the setting. Package w10u: log /tmp/claude-0/w10u/progress.log, one line per step.

## Why this round exists
Two independent blind reviewers scored W10s's ten dimensions 2–3/5 from the frames. The lead viewed frames and confirmed the main points. The reviews are your work list: /tmp/claude-0/blind/<slug>.md, one per dimension. Read every one in full. W10s's own report overclaimed. **You do not judge your own result: a dimension is done only when a fresh blind reviewer, run by the lead, scores it 4 or more.**

## Findings the lead verified
- **RB-45 removals were incomplete.** Dropping a want or force does not stop a structure: if it stays in `structures.list` it still spreads through the groups. the_violet_spire still lists `mss:large_tower` (it floats over void, with scaffold rings in air). the_nether still lists `towns_and_towers:exclusives/village_piglin` as "spread" (it stood on the roof). Remove a failing structure from the list too, or keep it only with a site filter that stops the failure. SP2 is building site filters in a worktree; not there yet.
- **the_sunken_temple:** the only temple stands dry on a sky meadow (frame the_sunken_temple-structure-sinking_temple.jpg). The description is a temple sinking into water. Temples must be at or in the water. The forced `mtr:ocean_temple` never starts on this seed.
- **the_forged_depths:** still crimson nylium, fungi, a canopy and quartz-striped cliffs (frame fortress-r2-nv). The owner asked for deader, no trees, craggier. The definition still lists crimson_pinewood, crimson_forest and quartz_flats.

## Scope
For each of the ten slugs, fix what the review names, in this order of priority:
1. **Terrain, biomes, surface and atmosphere.** Examples:
   - narrow chasms in the_wailing_narrows;
   - a real ledge over a visible pit in the_gilded_pit, with gold that reads;
   - surface crystals in the_violet_spire, plus its End-ward half visible;
   - a lake that is not machine-made in the_abyssal_shrine, with no void holes in the rim;
   - dead ground in the_forged_depths;
   - industry-scarred ground in the_molten_flats as far as terrain can carry it.
2. **Structure curation** with pack-mod structures and vanilla templates, against RB-45:
   - every listed structure must fit where it stands;
   - every place a review says is missing gets a stand-in if a pack structure fits.
3. **What needs our own set pieces** (industry works and pipelines, a shrine, spans over a chasm, a pinned landing): write it into the page entry as "waiting on the set-pieces kit (SP2–SP4)", with the exact place needed. Do not fake it with misfitting mod structures.
4. **Frames for every claim:**
   - every structure that stays gets a frame that shows it in its setting;
   - the_end's central island and gateways get frames;
   - the_gilded_pit's landing view must show the pit.

For each dimension, build the review pack as W10s did: /tmp/claude-0/w10u/review-pack/<slug>/ with no scores, plus each review's file so the next reviewer sees what was asked. Then message the lead "pack ready: <slugs>". The lead runs a fresh blind reviewer and sends you its verdict. Iterate on anything under 4. Stop on a dimension after three rounds and report its blockers.

## Rules
- The lane (one server or one Gradle build at a time) is shared with SP2. Message the lead before every boot or build and wait for "the lane is yours".
- 5 GB of memory and 3 GB of disk free before a boot. Delete your own old run folders. Kill by pid.
- Commits: explicit paths, `[skip ci]`, the trailers in common-rules.md. Never stage HANDOFF.md, TROUBLESHOOTING.md or AGENTS.md. Don't push.
- Before your final report: regenerate goldens, run the full build green and a fresh-world self-test.
- Trap drafts are numbered from T320; the lead renumbers them.
- Budget: about 250 tool steps.

## Report
- Per slug:
  - the changes made;
  - blind scores, round by round;
  - what waits on the kit;
  - named exceptions.
- Build and self-test counts.
- Commits.
- Trap drafts.
