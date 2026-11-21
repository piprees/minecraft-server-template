BL1 — Blend competing structure overrides by weight (worktree; the server lane only when the lead gives it)

Read and treat as binding: handoff/scratchpad/common-rules.md; handoff/scratchpad/sp2-brief.md (facts, rules, report format); decisions RB-13, RB-18, RB-48, RB-50 and RB-51 (docs/design/rebuild-26/decisions.md); mods/AGENTS.md. Read the code around the mod's datapack and structure control first: the firewall that turns mod structure sets off by namespace, the bridges (mods/custom-dimensions-26/bridges/ and their test), the override packs under config/datapacks/ with ownership.json, and StructureRuntime and StructureSites. Package bl1: log /tmp/claude-0/bl1/progress.log, one line per step.

## The owner's ask, verbatim
"If we can find a way to blend some of treks strongholds in that'd be great, remember one goal of our mod is to act as a bridge for others. It may be prudent to make a mechanism for these situations- where two mods override a structure, add a way to list them and reliably distribute them to taste. I would say in this case, make trek's a 1/12 or something so that the biome based ones have much more of a chance of being selected as they are likely to be more interesting"

## Facts (from DR1, the dependency agent, which read the jars)
- Trek (beta, 26.3) overrides minecraft:strongholds and the minecraft:stronghold structure and templates. It also overrides end_cities (templates only; vanilla placement kept), pillager_outposts (vanilla placement plus two outposts of its own), villages (spacing 48, separation 20, plus its own villages) and monster_room (count 0).
- Biome Strongholds overrides minecraft:strongholds and minecraft:stronghold, with 15 biome structures on vanilla's concentric rings. Its eye_of_ender_located tag adds #bistro:biome_strongholds.
- DR1's current branch resolves the stronghold conflict with our own override pack, which outranks mod data: `minecraft:strongholds` names Biome Strongholds' 15 structures, and Trek's stronghold is lost. You replace that with the blend.
- D&T Ancient City Overhaul overrides minecraft:ancient_cities (separation 20).

## Build
1. **Detection.** At startup, find every registry entry (structure, structure set, template pool, processor list, template) that more than one data source defines: vanilla and each mod jar's data, by namespace and file. "Mod A replaced vanilla" is one case; "mods A and B both replaced vanilla" is the contested case.
2. **Keep every version.** Register each mod's version of a contested structure (and the pools and templates it needs) under its own id, e.g. `customdimensions:blend/<modid>/<path>`. Do it without copying any vanilla or Mojang data into the repo: read the mod's own data at runtime.
3. **Weights in config.** Settings for each contested set: the versions, and the weight of each. Biome Strongholds' 15 structures share 11 in 12; Trek's stronghold gets 1 in 12. That's the pack default for minecraft:strongholds. Where the owner set nothing, the default is equal weight per mod.
4. **The set draws from all of them** on the set's own placement (for strongholds, vanilla's rings, unchanged).
5. **Progression must hold (RB-48).** Every version of a stronghold must keep a lightable End portal (12 frames, 12 eyes). Eyes of ender must find whichever version starts (the eye_of_ender_located tag includes the blend ids). /locate and maps must work through K13's PlanLocate.
6. **Listing.** A `/customdim blends` command, and one boot-log line per contested entry, naming its versions, their mods and their weights.
7. **Generic.** No code names a mod or a dimension; the stronghold weights live in config. Bridges and the firewall keep working.

## Prove it
- **Unit tests:** detection on a fixture of two fake mods overriding one structure; a weighted draw over many seeds within 2% of 11/12 and 1/12.
- **One boot with the full 26.3 list.** DR1's branch carries it; merge or rebase onto it once DR1 has landed, or coordinate through the lead.
  - The `/customdim blends` output.
  - Stronghold starts by version across the overworld's 128 rings: expect about 117 and 11.
  - Eyes of ender find the nearest stronghold, and its portal lights with 12 eyes.
  - The End cities (`/customdim starts the_end` about 57) and the rim mansion are unchanged.

## Rules
- As handoff/scratchpad/sp2-brief.md.
- Trap drafts are numbered from T410.
- Budget: about 200 tool steps.
- Message the lead (to: main) before any Gradle build or boot.
