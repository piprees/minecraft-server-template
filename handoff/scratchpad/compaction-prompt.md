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

## State (log time in HANDOFF.md)
- **Batches:**
  - Batch 1 is under rework from the owner's notes.
  - Batch 2 is done to the bar.
  - Batch 3 (End and sky worlds, plus the Aviary) is done: 11 at 4/5; the Crumbling Reaches is at 3/5 as a named exception.
  - Batches 4–6 have not started.
- **Review pages:**
  - Batch 1: https://claude.ai/artifact/HLfMWRYiUxoMMZMMryZCuq
  - Batch 2: https://claude.ai/artifact/26a27z8QwNWYegwYwFAGn2
  - Batch 3: https://claude.ai/artifact/VoCuffQGcwQN7rknN1wSdB
  - Owner feedback is read with ArtifactData from collections `batch1`, `dimension-review-batch2` and `dimension-review-batch3`.
- **Running agents (verify from their artefacts, never from idle signals):**
  - K13 (worktree): `/locate` for our placements, structures in the void, fog default, traps T296–T302. When it lands, run its lane script with `MODS=/tmp/claude-0/k12-mods` (Moog's End Structures plus its lib) to measure the End city count and the elytra rolls inside cities.
  - P1 (worktree, docs): the places plan in `docs/design/rebuild-26/places/`. The owner reviews it, then the kit packages start.
  - W10r (lane, main checkout): the owner's terrain and atmosphere notes on Batches 1–2.
  - A1 (read-only): parity audit. Outputs go to `/tmp/claude-0/a1/`, and become the roadmap artifact for the owner.
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
  - Write British English, in plain-text formats.
  - Keep messages to the owner short.
