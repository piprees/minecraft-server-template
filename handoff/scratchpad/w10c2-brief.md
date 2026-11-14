W10c2 — Batch 2 (Nether presets, 11 dimensions) to the full bar (server lane, main checkout)

Read, in order, and treat as binding: /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/common-rules.md; the batch brief /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/w10c-brief.md (all of it, including "Added since this brief was written": its standard, done bar, method, outputs and final report are yours); `mods/custom-dimensions-26/fidelity/batches.md` § Batch 2; `fidelity/drafts/k7/README.md` rows for your slugs. Package name: w10c2 (log /tmp/claude-0/w10c2/progress.log, done.txt beside it). You own the server lane and the main checkout (branch claude/minecraft-dependency-automation-n1w79q at 0a38f364 or later; no worktree).

## Your batch, in this order
Low difficulty first, then the places dimensions after K9 merges (below): the_blackstone_keep, the_bloodroot_wastes, the_obsidian_sanctum, the_furnace_halls, the_buried_age, the_boneyard, the_ember_fields, the_wailing_narrows, the_molten_flats, the_gilded_pit, the_forged_depths. Prove the whole loop on the first before batching the rest.

## What Batch 1 learnt (use it)
- Batch 1's two logs are the best record of commands and timings: /tmp/claude-0/w10c1p/progress.log and /tmp/claude-0/w10c1q/progress.log (surveys of `<slug>_<tag>` variants in one boot, shots, renders, the review packet, page.json with `targets` and `misses`). W10c1q's tools: /tmp/claude-0/w10c1q/tools/ (placeregions.py --merge, biomeshare.py, f2-measure.sh).
- **Renders:** use `/customdim fastrender <slug> [stride] [threads]` (merged; README § The fast renderer): border to border, exact to the block against today's render on roofed worlds, with a roof-off layer (the floor under the first ceiling) and a cut-at-Y layer — the right views for Nether presets. Use its roof-off map for the biome and landform judgement and `/customdim render` only where the brief's file names need it. Look at every render.
- **Places on a roofed world:** a place holds its share of the border; judge by its share and regions on the roof-off render until K9's survey lands.
- Never write code naming a dimension; a kit bug is fixed generically with a test, or written up in /tmp/claude-0/w10c2/kit-gaps.md at once and you carry on.

## Running beside you
K9, a worktree agent, is building: the survey stratified over the area places are laid on; survey patches drawn from the seed, not the slug; `placeness.places` in the dossier (per-place shares and regions, `islands.dominantPlacePercent`) and in check-targets; `terrain.layout.warp` and wandering heart edges; and recording traps T259–T265 in TROUBLESHOOTING.md and AGENTS.md (do not edit those two files). The lead merges it into the main checkout only between your commits, after asking you. After it merges:
- (a) for every places dimension in your batch, measure with the new survey keys rather than the stand-in;
- (b) revisit Batch 1, one boot: re-survey the_aviary and the_sunken_temple with the new survey and say whether the aviary's forest, birch forest and cloud reef are expressed (their misses in /tmp/claude-0/w10c1q/page.json); apply `terrain.layout.warp` to the_abyssal_shrine's ring and let the_sunken_temple's heart edge wander, re-measure both, update their page.json entries (a copy at /tmp/claude-0/w10c2/batch1-revisit/page.json), goldens.
Until it merges, keep to the low-difficulty dimensions.

## Rules
Trap drafts in your report, numbered from T266 (K9 may take some of those; the lead renumbers). Explicit-path commits with `[skip ci]`; do not push; never stage HANDOFF.md, TROUBLESHOOTING.md or AGENTS.md. 5 GB free memory and 3 GB disk before every boot; never your own Gradle build and a server at once. Budget ~280 tool steps; if it will not cover the batch, finish the dimensions you started to the full standard and list the rest untouched.
