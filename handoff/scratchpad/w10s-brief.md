W10s — Finish and verify the Batch 1–2 rework (server lane, main checkout)

Read and treat as binding: /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/common-rules.md; the batch brief /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/w10c-brief.md (standard, done bar, method, outputs); the W10r brief beside it (w10r-brief.md, for the eleven notes); the owner's notes mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md; decisions RB-39 to RB-45 in docs/design/rebuild-26/decisions.md (RB-45: every structure fits where it stands). Package name w10s (log /tmp/claude-0/w10s/progress.log, one line per step, done.txt beside it).

## Where W10r left it (verified by the lead)
- Commits on the branch: 756264c3 (ten definitions, curated structures, reviews), a6c48169 (goldens; ApplyIdMappingTest 77 -> 78 because the_end has zones; NOT yet built), 8a563922 (self-test log). W10r's full report: handoff/evidence/w10r/ (page.json, terrain-shots.json, cards/, kit-gaps.md, progress.txt, selftest-final.txt); shots and renders in /tmp/claude-0/w10r/.
- Fresh-world self-test: 280 PASS, 2 FAIL, 1 SKIP. Both FAILs are K13's structure self-test lines (StructureCheckBypassMixin, ChunkGeneratorLocateMixin). K13 is fixing them in its worktree; not yours.
- Structure shots: 14 of 17 dropped. In roofed and sky dimensions a rig shot at surface+N puts the camera inside the roof mass (W10r's trap draft). So "fits its setting" rests on 3 frames: not enough.
- Placeness fell in five dimensions: violet_spire 7/7 -> 4/7 (cliffs 3.6% vs 15), abyssal_shrine 6/6 -> 5/6, forged_depths 4/4 -> 2/4 (named exception), gilded_pit 4/4 -> 3/4 (named exception), and description targets fell in six. the_end's twelve outer-island zones fall in no survey patch or shot.
- The overworld mansion is K13's, not yours.

## Do, in order
1. Wait for the lead to give you the lane (K13 has it first). Then build: `./gradlew --max-workers=1 --offline build` in mods/custom-dimensions-26 with JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8. It must be green; fix only what a6c48169 broke.
2. Fix the roofed and sky structure-shot camera in the rig generically (camera below the roof and above the floor at the structure, or a cut-away), with a test or a rig self-check. Never code naming a dimension.
3. Re-shoot every curated structure in the ten dimensions so each has at least one frame that shows it standing in its setting (approach, ground, surroundings), plus the_end's outer-island zones. Use the pack's structure mods (STRUCTURE_MODS=/tmp/claude-0/pack-structure-mods).
4. For each placeness or description target that fell: either recover it with a definition change that keeps the owner's note, or restate it with its reason as a named exception. Never weaken a target to pass it.
5. Write each dimension's page entry (the batch brief's format) with the new shots. Add an `rb45` block per structure: on its ground, site, materials and age, scale, approach (yes/no and one line). A structure that fails and can't be fixed by curation is removed from that dimension and listed as a re-skin candidate.
6. Do NOT score the dimensions yourself. Put everything a blind reviewer needs in /tmp/claude-0/w10s/review-pack/<slug>/: the description, the owner's note for it, the design card, the shots, and the targets table. No scores, no conclusions. The lead runs separate blind reviewers on those packs.
7. Final fresh-world self-test (CI props, seed 20261022). Commit evidence to handoff/evidence/w10s/.

## Rules
- One server or one Gradle build at a time, and only while the lead has given you the lane. Before every boot: 5 GB memory and 3 GB disk free. Delete your own old run folders. Kill by pid.
- Commits: explicit paths, `[skip ci]`, the trailers in common-rules.md. Never stage HANDOFF.md, TROUBLESHOOTING.md or AGENTS.md. Don't push.
- Trap drafts go in your report, numbered from T306; the lead renumbers.
- Budget: about 220 tool steps.

## Report
- build result;
- the rig fix;
- per dimension: shots added, targets recovered or restated, structures kept or removed (with RB-45 lines);
- review-pack paths;
- self-test counts;
- commits;
- trap drafts.
