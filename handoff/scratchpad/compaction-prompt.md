# Compaction prompt: the custom-dimensions 26.3 rebuild

Paste this as the summary when the session is compacted or handed off. Before acting, read `HANDOFF.md`, from its latest log lines upwards.

## Role and branch
- You are the lead orchestrator of Opus subagents. The work is the 26.3 rebuild of the Minecraft adventure pack (`piprees/minecraft-server-template`).
- Work on branch `claude/minecraft-dependency-automation-n1w79q` (PR #29) and push only there.
- Never create a PR, merge to main, cut a release, or touch production without asking.
- Never edit or push `/home/user/elfydd`.
- `config/custom-dimensions/` is frozen until the cutover; ask before touching it.

## Owner's goal (hard requirements)
- Every feature of the 1.21.1 pack and its custom-dimensions mod must exist on 26.3. That covers its ambitious world generation, 82 dimensions, structures, portals and dimension control.
- Mods with no 26.3 build are recreated on our side, server-side.
- No client mod may be required. Unmodded Java, Bedrock and mobile (Geyser/Floodgate) must see the full game. Our optional companion mod is the only client exception.
- "Waiting for the author" does not count as delivered.

## Owner's done bar (each dimension)
- Its review is 4/5 or better.
- Every listed biome is placed and expressed.
- Its placeness targets pass, or each miss is restated with its reason or written as a named exception.
- Never make a claim the measurements do not back.

## Owner's rulings (`mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md`)
- A **place** is a named location: a fortress, a crashed ship, a camp, a pit of bones. Terrain is only the setting.
- Structures are essential in every dimension. They come from the pack's structure mods, from Explorify (through dep-review) and from our own set-pieces kit.
- A dimension's fog holds across the whole dimension by default.
- No structures float in the void.
- the_violet_spire is night-only.
- End: a denser dungeons group (about 57 cities), and elytra at 8% a chest in End dungeon chests.
- **Danger and reward (RB-39):** base difficulty per dimension, plus mobs harder the nearer a high-value structure, hardest in endgame dungeons. Loot follows the same curve: buried treasure and chests scale with the dimension's difficulty and size. Easy and peaceful dimensions are safe but poor (weak loot, fewer ores), as in 1.21.1. Loot from exploring is how players get ahead. Package DL1 (not yet dispatched).
- **Portal lore (RB-40):** portal recipes are never explained outright. Some buried treasure and endgame dungeon chests carry books about a portal and what lies beyond, or items arranged in the chest to show the frame's shape, block and igniter, generated from each dimension's `portal` definition. Package LR1, after DL1's loot work.
- **Broken portals (RB-41):** some treasure maps lead to half-finished portals: a partial frame, an igniter precursor, a study (enchanting table, bookshelves, notes that stop short), ground part-transformed when the target's `portal.aura` is on; placed where the target dimension would plausibly reach through (biome, height, depth). Never the exact recipe.
- **Deep time (RB-42):** structures and set pieces are placed as linked stories (shared faction, materials, banners, loot, placed near one another), with restraint: most terrain carries nothing, so a find means something. Feeds the set-pieces kit (SP2–SP7) and LR1.
- **Never named (RB-43):** in-game text never names a faction, dimension, epoch or person; it only alludes. Prefer no words at all (banners, materials, item arrangements, remains, part-built frames); where words are needed, few and fragmentary.

## Owner's roadmap decisions
Before asking the owner anything, look for the answer in `docs/design/rebuild-26/decisions.md`, the mod register, `HANDOFF.md` and the owner's notes. Never re-litigate a settled decision.
1. **Content:** settled by RB-22. Our own blocks, items and creatures come after launch, through Polymer, in shippable slices. The switch follows phases 0–2.
2. **Waiting mods:** build our own YUNG's-style overhauls, mega-dungeons and the Discord chat bridge now. If an author ships later, raise it then.
3. **Cut mods stay cut,** as the register decided: Chipped, Macaw's Roofs, Universal Sawmill, Amendments, Creeper Overhaul, Guard Villagers, Dungeons Reborn, 3D Placeable Food, BlueMap sign markers. The owner never meant to replace mods like Chipped; do not rebuild them.
4. **Seasons:** server-side seasons (crops, snow, temperature).
5. **Better Combat:** accept losing it.
6. **Biomes:** about 60 more of our own.
7. **Resource pack:** the server resource pack is required on join.
8. **Mods:** add the ten mods now available, through the dependency checklist (Friends & Foes with its Polymer patch, Explorify, DeCubed Dungeons, Repurposed Structures, Trek, Hopo Better Mineshaft, Server-Side Waystones, Sit!, Ender Dragon Destroys End Island, Chunky Border).
9. **Portal ambience:** bring it back.

## State (log time in HANDOFF.md)
- **Roadmap doc:** "Adventure pack on 26.3: parity roadmap", https://claude.ai/artifact/Ezk5Wt4Wr1tApDUqsRhG8A (Claude Docs project 7156bec1-4a57-4666-b2d9-0b149b0ec53b).
  - The owner's decisions are a checklist in it. Read the doc, with its ticks and comments, before planning.
  - Parity is about 55% overall (method and 91 features in `docs/design/rebuild-26/parity-audit/`).
  - About 74 packages remain, in phases 0–4.
- **Places plan:** `docs/design/rebuild-26/places/` (P1, merged), waiting for the owner's review.
  - Packages in order: PL0 (wire kept structures and stand-ins, server lane, no code), SP1 (places in the tools), EX (explorify dep-review), SP2–SP4 (set-pieces kit), SP5–SP7 (places per batch).
  - PL0 and SP1 can start before the kit.
  - Eight owner decisions are listed in `places/README.md`.
- **The new standard for every dimension** (the owner said the configs lacked care):
  1. A design card per dimension, from its description and 1.21.1 source, read by the lead before any change.
  2. Anything that contradicts the description goes.
  3. Structures are curated per dimension; no generic scatter.
  4. The lane boots with `-PstructureMods=/tmp/claude-0/pack-structure-mods` (the ten pinned 26.3 structure mods, Moog's lib and cristel-lib).
  5. A separate blind reviewer scores each dimension from its description and shots, and the lead checks before any page reaches the owner.
- **Owner's notes:** `mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md` (Batches 1–2) and `owner-review-2.md` (Batch 3: all 12 marked "change").
- **Batches:**
  - Batches 1–3 are all to be reworked under the new standard.
  - Batches 4–6 have not started.
- **Review pages** (feedback is read with ArtifactData from collections `batch1`, `dimension-review-batch2` and `dimension-review-batch3`):
  - Batch 1: https://claude.ai/artifact/HLfMWRYiUxoMMZMMryZCuq
  - Batch 2: https://claude.ai/artifact/26a27z8QwNWYegwYwFAGn2
  - Batch 3: https://claude.ai/artifact/VoCuffQGcwQN7rknN1wSdB
- **Running agents (verify from their artefacts, never from idle signals):**
  - K13 (worktree): `/locate` for our placements, structures in the void, fog default, traps T296–T302. When it lands, merge it between W10r's commits, then run its lane script with `MODS=/tmp/claude-0/k12-mods` or `/tmp/claude-0/pack-structure-mods`.
  - W10r (lane, main checkout): the owner's Batch 1–2 terrain and atmosphere notes, now under the new standard. It sends the lead a design card path per dimension; the lead reads each card.
- **Next ids:** T296 and K18.
- **Briefs:** in `handoff/scratchpad/`; `common-rules.md` binds every agent.

## Standing rules
- **Commits:** explicit paths only, never `git add -A` or `git stash`. Mid-round pushes carry `[skip ci]`. Use these trailers:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01YLnzTHFe7j88jnTnken5rK`
- **Lane:**
  - One server or one Gradle build at a time.
  - A boot needs 5 GB of memory and 3 GB of disk free. Disk is tight, about 6 GB free.
  - Kill processes by pid, never with `pkill -f`.
  - Before removing a worktree, check no process uses it.
- **Merging:** worktree agents merge only between the lane agent's commits, after asking it. The lead builds (`./gradlew --max-workers=1 --offline build`) and runs the quick gate (`PATH=/tmp/claude-0/pybin:/root/.local/bin:/tmp/claude-0/sc/bin:$PATH ./scripts/test-scripts.sh --quick`) after every merge.
- **Clean room (RB-13):** never open Terralith, Incendium, Nullscape, Structory, Tectonic, Regions Unexplored, Nature's Spirit, WilderWild or Stardust Labs files. SeedGen jars and decompiled code are closed to builders.
- **Privacy and content:** never commit secrets or Mojang data. The owner's email is private. No model identifier goes in the repo except the commit trailers.
- **Owner contact:**
  - Ask the owner for reviews and decisions with the question tool.
  - Ask before any look-and-feel choice.
  - The owner cannot open repository files: anything they must read goes in the conversation or in an artifact.
  - Write British English, in plain-text formats.
  - Keep messages to the owner short.
