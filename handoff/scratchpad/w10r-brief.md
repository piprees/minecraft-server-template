W10r — The owner's terrain and atmosphere notes on Batches 1 and 2 (server lane, main checkout)

Read, in order, and treat as binding: /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/common-rules.md; the batch brief /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/w10c-brief.md (its standard, done bar, method and outputs are yours); the owner's notes and rulings `mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md` (all of it). Package name: w10r (log /tmp/claude-0/w10r/progress.log, done.txt beside it). You own the server lane and the main checkout (branch claude/minecraft-dependency-automation-n1w79q, head or later; no worktree). Batch logs to reuse: /tmp/claude-0/w10c2/progress.log, /tmp/claude-0/w10c3/progress.log, their tools/ folders; current page entries: /tmp/claude-0/w10c2/page.json (Batch 2), /tmp/claude-0/w10c2/batch1-revisit/page.json (Batch 1; the aviary's newer entry is in /tmp/claude-0/w10c3/page.json).

## Scope: terrain, biomes, atmosphere only
Structures and named places are NOT yours: a places plan and a set-pieces kit are being built beside you (P1, then kit packages); write each structure note into page.json's `ownersEye` as "waiting on the places kit". Fog held over biomes becomes the kit's default (K13, merged between your commits after the lead asks you); do not set `overBiomes` yourself.

## Do, in this order (each: change the definition, measure, shots that show the change, review, page.json entry with targets/misses)
1. the_violet_spire: night-only (fixed night); crystals that show (amethyst or the library's crystal blocks — no crystal End biome, owner's earlier ruling) and growth on some spire tops.
2. the_sunken_temple: the water level raised to touch the bottoms of the skyland islands.
3. the_abyssal_shrine: the ring lake's rim must not stand in void: the lake's edge joins the plateau ring; no perfect circle.
4. the_molten_flats: flatter, less debris on the fields; magma in pockets boxed in by blackstone and obsidian, with lava bubbling in the centres of the larger pools, as if cooling.
5. the_forged_depths: deader — no trees — and much craggier; a slog to cross.
6. the_wailing_narrows: de-emphasise or remove the quartz biome; a vile, zombie-haunted crevice hell-hole, not a place anyone would settle.
7. the_gilded_pit: the ledge overlooking the pit engineered so it makes sense on any seed (spawn on it, the pit clearly visible below); a dangerous climb down.
8. the_bloodroot_wastes: the biome map reads as one colour — find out whether its biomes are too alike on the ground or only on the map, and fix the cause.
9. the_end: the central island stays classic; the outer islands carry the variety (which biomes, how they show).
10. overworld: the mountain in the middle of its first review shot looks broken — find the shot (Batch 1 page), go there, say whether it is the terrain doing what it should; fix only if it is a fault.
11. the_nether: quartz flats present but rare — check its share and keep it rare.
A kit bug or gap: fix generically with a test if small, else write it in /tmp/claude-0/w10r/kit-gaps.md and carry on. Never code naming a dimension.

## Rules
Trap drafts in your report numbered from T303 (lead renumbers). Explicit-path commits with `[skip ci]`; do not push; never stage HANDOFF.md, TROUBLESHOOTING.md or AGENTS.md. 5 GB free memory, 3 GB disk before every boot (disk is tight: delete your own old run folders); no boot while a Gradle build runs (`pgrep -fa GradleDaemon` busy), no Gradle build while a server is up. Commit evidence (`handoff/evidence/w10r/`) after each dimension. Full fresh-world self-test before the last dimension and at the end. When the lead asks for a merge window, give it at the next clean stop. Budget ~280 tool steps.

## End
The batch brief's final report for these eleven: before/after per note, review, targets, misses, shots, owner's-eye questions, commits, trap drafts.
