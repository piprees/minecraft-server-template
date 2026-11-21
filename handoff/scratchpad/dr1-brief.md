DR1 — Bring the 26.3 mod list up to date (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md; DEPENDENCIES.md in full, especially § Adding a mod (the mandatory checklist), § Looking up versions, § Versioning contract and § Holds; AGENTS.md § Mods; the mod register docs/design/rebuild-26/mod-register.csv; decisions RB-13 (clean room), RB-22, RB-45 and RB-46; docs/design/rebuild-26/modrinth-check.md (MR1's findings; the lead spot-checked the pin mismatch). Package dr1: log /tmp/claude-0/dr1/progress.log, one line per step.

## Scope: the 26.3 lists only
Work in config/modrinth-mods-26.3.txt, modpack/adventure-26.3.mrpack.json and the 26.3 client pack. The 1.21.1 lists and config/custom-dimensions/ are frozen.

1. **Add the owner's mods through the checklist, every item of it:**
   - **The roadmap's ten:** Friends & Foes with its Polymer patch (pin the F&F build the patch was built against, 5.0.4, unless you prove 5.0.5 works), Explorify, DeCubed Dungeons (the slug dungeons-plus is renamed `decubed-dungeons`), Repurposed Structures (with midnightlib; most groups off by default and curated per dimension later), Trek, Hopo Better Mineshaft, Server-Side Waystones, Sit! (with otterlib), Ender Dragon Destroys End Island, and Chunky Border.
   - **RB-46's mods:** cinchs-abandoned-houses, cinchs-villager-statues, lush-cave-ruins (its add-on mob pack stays out), piglin-outposts, mmv-moogs-missing-villages, dungeons-and-taverns-ancient-city-overhaul, wits, and structure-layout-optimizer with resourceful-config.
   - **The other waiting rows now on 26.3:** no-chat-reports, krypton.
   - For every mod, check against the checklist:
     - its 26.3 Fabric build;
     - server side, and no client mod required (anything adding blocks, items or entities needs Polymer, or stays out);
     - libraries in required;
     - the licence;
     - its config, in config/<mod>/ plus a COPY line in docker/defaults-seed/Dockerfile;
     - conflicts with our structure control.
   - Structure mods start with their structures OFF in every dimension except where curated. They must not change any dimension's structures today: check that the compiled goldens don't change.
   - Update each register row to its new decision.
2. **Updates:** the 25 newer 26.3 builds in modrinth-check.md § 1.
   - Before the v6.0.0 switch, nothing on 26.3 has shipped, so worldgen and structure updates may land now. Still list them separately in the report.
   - Betas and alphas stay on their current stable pin unless the stable build is broken.
   - healing-campfire and collective go up together.
3. **Client-server parity (T80):** simple-voice-chat and puzzles-lib must pin one build on both sides.
4. **Fix the dep-review policy mismatch:** the 26.3 list's section headers don't match `never_automerge_sections` in .github/dep-review/policy.json. Ask the lead before changing policy.json.
5. **Prove it:** one server boot with the full 26.3 list. It must:
   - boot clean, with no missing dependencies and no mixin errors;
   - pass the vanilla-client join gate (scripts/e2e/vanilla-join.sh; write white-list=false first, T314);
   - keep `/customdim starts` for the_end and the overworld unchanged against today (End cities 57; the mansion at -7344,-672);
   - give a full fresh-world self-test of 0 FAIL.

## Rules
- As handoff/scratchpad/sp2-brief.md:
  - one server or one Gradle build at a time, only with the lane from the lead;
  - explicit-path commits with the trailers;
  - never stage HANDOFF.md;
  - no push.
- Look every version up live; never take one from memory.
- Clean room (RB-13): never open Terralith, Incendium, Nullscape, Structory, Tectonic, Regions Unexplored, Nature's Spirit, WilderWild or Stardust Labs files.
- Modrinth: bulk endpoints, at most about 100 requests in total, 1 s apart, a generic User-Agent.
- Trap drafts numbered from T390.
- Budget: about 220 tool steps.

## Report
- a table per group (added, updated, held), with versions, register decisions and checklist notes;
- the boot, join, starts and self-test results;
- commits;
- trap drafts.
