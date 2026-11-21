W10c1q — finish Batch 1 with places (server lane), continuing the stopped package W10c1p

Read, in order, and treat as binding: /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/common-rules.md; the brief you continue, /tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad/w10c1p-brief.md (all of it; its method, standard, done bar and final-report format are yours); its inherited batch brief w10c-brief.md beside it; then W10c1p's exact state, /home/user/minecraft-server-template/handoff/evidence/w10c1p/STATE.md (also at /tmp/claude-0/w10c1p/ after restore), and the last 40 lines of /tmp/claude-0/w10c1p/progress.log. Package name: w10c1q (log /tmp/claude-0/w10c1q/progress.log, done.txt beside it; you may use W10c1p's tools and runs under /tmp/claude-0/w10c1p/ and write new runs there or under w10c1q/). You own the server lane and the main checkout (branch claude/minecraft-dependency-automation-n1w79q at fea2bfe3 or later; no worktree).

## Already done — do not redo
- Step 0 (the self-test FAIL, b66da114) and W10c1p's step 1 (T217 renders, gallery surveys) — see STATE.md.
- The_nether (de74c763) and the_abyssal_shrine (a2d714c0) are applied and measured; the lead regenerated their goldens (bdc2f81b) and the build is green, so STATE.md's "Next 1" is done.

## Do (STATE.md's "Next, in order" 2–5, then the brief's steps 6–8)
1. Refresh `runs/r2defs` from `definitions/` and run the r3 boot STATE.md gives (aviary b/c, violet a/b/c, sunken a/b, gallery renders, overworld r2048).
2. Decide and apply the_aviary, the_violet_spire, the_sunken_temple with minimal edits; final surveys; both target sets. Where a survey measure reads a group's members as separate biomes (STATE.md T260 draft), restate that target per place with `tools/placeregions.py` as the measure and say so — never weaken a target.
3. Shots, reviews, renders for the five changed dimensions (nether, abyssal, aviary, violet, sunken); the overworld's two variants side by side as the owner's choice (do not change `definitions/overworld.json` to the places variant).
4. Batch 1's final folder for all eight (/tmp/claude-0/w10c1q/{page.json, terrain-shots.json, targets-all.md, renders/}, every jpg path existing), goldens where definitions changed, build green, one full fresh-world self-test with CI's server.properties at 0 FAIL, `server/run/world` deleted, no server running.

## Running beside you (do not touch their files)
- C1, a worktree agent, is fixing CI: the overworld's `terrain.landShare` compiles to `customdimensions:land_share`, which a vanilla server cannot parse, so `scripts/vanilla-boot-check.sh` fails. The lead merges its fix into the main checkout when it reports (only between your commits; the lead tells you first). Do not edit DatapackCompiler's land-share path, TerrainLibrary.withLandShare or the vanilla boot check.
- K8, a worktree agent, finishes pinned place sites (a small place held at the centre; `PlaceField`, `PlaceLayout`). Its last change makes a single `at` pin become a heart, which is what the_sunken_temple's 50/50 K6 draft (`at` highlands [0,0]) needs once K8 merges. Until the lead tells you K8 is merged, measure the sunken temple's variants on the head's kit; after it merges, re-measure the variant you choose once before applying it.
- Both run Gradle with --max-workers=1: keep the 5 GB free-memory rule before each boot; never run your own Gradle build and a server at once.

## Rules
Trap ids: STATE.md's drafts are T259–T261; number yours from T262 in your report. Explicit-path commits with `[skip ci]`; do not push; never stage HANDOFF.md, TROUBLESHOOTING.md or AGENTS.md. Budget ~220 tool steps; if it will not cover everything, finish 1, 2 and 4 to the full standard first and list the rest.
