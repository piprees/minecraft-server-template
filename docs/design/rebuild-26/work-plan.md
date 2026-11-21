# Work plan

Sizes are in days of work. "Owner" is the human who reviews, looks and chooses; agents do the typing. Requirements each package meets: [requirements.md](requirements.md).

## F · Foundations

12 days · weeks 1–3 · everything depends on it.
Done when: the new project builds for 26.3 and the latest 26.4 snapshot in CI, the carried-over tests pass, and all 82 definitions convert to schema v2 with every third-party id listed.

### F0 · The spike — 3 days · agent; owner looks at the renders

Three throwaway proofs on 26.3; nothing is kept except the findings, which go into this plan before F1 starts.

- **Zone blend:** a datapack with one ring zone around spawn, blended into vanilla overworld terrain with `distance_to_point` and `lerp`, rendered from above. Shows whether edges can look natural and whether 26.3's single precision leaves seams.
- **Lazy levels:** Fantasy opening a persistent level from a generated dimension type and noise settings, unloading it when idle, and reopening it after a restart.
- **Portal block:** a Polymer block implementing vanilla `Portal`, seen by an unmodded 26.3 client as a Nether portal upright and an End portal flat, carrying a player across.

Check: a render of the ring, a restart log showing the level reopened, and screenshots of both portals from an unmodded client.

### F1 · Project skeleton and toolchain — 3 days · agent

- `mods/custom-dimensions-26/` beside the 1.21.1 mod, which keeps running production until the switch. Gradle subprojects `core`, `compiler`, `server`, `client`; Java 25 pinned in `mods/mise.toml`; Loom 1.19.3, Gradle 9.8.2, Fabric Loader 0.19.5 and Fabric API 0.164.0+26.3 (looked up 22 October 2026). One target, 26.3; Stonecutter joins when a second target exists (26.4's release). The canary builds the same source against the newest snapshot.
- CI: `mod-build.yml` gains the new project (build, unit tests, Fabric game tests on a headless server); a weekly canary builds against the newest snapshot and opens an issue when it breaks.
- The mod id stays `customdimensions` and is never published.

Check: an empty mod boots a 26.3 server in CI and the canary runs once, green or with an issue filed.

### F2 · Carry over the core — 3 days · agent

- Move the config model, validation, scoring, structure-noise maths, portal geometry and difficulty rules into `core` with their tests; drop the roller, facts, web and tryout packages and their tests. Nothing in `core` imports Minecraft; a lint rule enforces it.

Check: every carried test passes; the lint rule fails a deliberate Minecraft import.

### F3 · Definition schema v2 and conversion — 3 days · agent, human review

- A JSON Schema file (editors autocomplete from it), a `schemaVersion` field, the worldgen fields regrouped into the layers of [worldgen.md](worldgen.md), and zones. `seed` stays; `seedRoll` is reshaped around what the new roller scores.
- A converter for the 82 definitions, portal blocks included (26 use a shape, 7 an orientation, every one a frame). It reports every id from a cut mod: 301 Terralith references across 58 files, plus Regions Unexplored in 51, Nature's Spirit in 27, Wilder Wild in 20 and Paradise Lost in 9. W8 works from that report.

Check: all 82 validate; the report's counts match a grep of the originals.

## M · The mod

46 days, with W5 · weeks 4–13 · beside W.
Done when: on a local 26.3 stack, every definition creates its dimension; portals of every shape, orientation and frame link, scale and decay; auras spread under each `subsume` policy; difficulty, deaths and exits behave as configured; and the companion shows a live preview.

### M1 · The compiler — 6 days · agent

- Definitions in, a datapack out (`world/datapacks/customdim-generated/`): dimension types with attributes, timelines and a clock; per-dimension borders; noise settings; material rules; biome layouts; structure-set overrides. Runs before the server starts from `deploy.sh` and `dev-up.sh`, and again inside the mod at start-up to confirm the pack on disk matches.
- The version table covers 26.3 and 26.4-snapshot differences (`default_block` gone, no overlapping multi-noise entries).

Check: golden-file tests per version; the generated pack loads on a vanilla 26.3 server with no mods.

### M2 · Levels — 4 days · agent

- The four base worlds come from generated entries for `minecraft:overworld`, `the_nether`, `the_end` and `adventure:the_aviary`, loaded at start. The other 78 open lazily through Fantasy's persistent levels, one first-time generation at a time (K6), and unload when idle.
- Deleting a dimension uses Fantasy's delete, which removes the folder and the stem; `world_gen_settings.dat` never holds runtime levels.

Check: 82 dimensions visited in turn by a Carpet fake player; memory returns to baseline after idle unload.

### M3 · Generator variants — 2 days · agent

- Normal, large biomes, amplified, caves, floating islands, Nether and End presets as starting points any definition can tune; plus flat with layers, checkerboard, void and single-biome.

Check: one test dimension per variant renders as expected with `customdim render`.

### M4 · Difficulty per dimension — 2 days · agent

- Mob multiplier, the attributes it scales, depth scaling, player luck and peaceful dimensions, applied once at spawn; spawn tables through `natural_mob_spawns`.

Check: game tests for a hard, a deep and a peaceful dimension.

### M5 · Deaths, exits and wins — 3 days · agent

- Respawn redirects, inventory rules, exit portals and shrines, win triggers that fire advancements and messages from `config/messages.json`.

Check: a fake player dies, exits and wins in three test dimensions.

### M6 · Portals: frames, shapes, orientations, travel — 6 days · agent

- Frames, orientations, shapes and travel ported as they are; one Polymer portal block implementing vanilla `Portal`, with a state per plane; ignition through Fabric's use-block event; vanilla clients get the coloured portal, destination particles and a title on approach. Detail: [portals.md](portals.md).

Check: the portal matrix (`scripts/e2e/`) passes on 26.3, with a horizontal bay for every dimension that allows one and extra bays for any unused frame form or shape; a zone saved by the 1.21.1 mod restores; suppressed vanilla portals stay dark.

### M6b · Auras and subsume — 2 days · agent

- Ported as they are, with guard rails and the claim veto unchanged; tree ids rewritten to the `worldgen/feature` registry; `#adventure:aura_protected` extended for 26.3; the destination's palette as fallback when the far side isn't loaded. Detail: [portals.md](portals.md#auras-and-subsume).

Check: three portals into `none`, `natural` and `everything` dimensions beside a cobblestone wall: untouched, untouched, converted; the same wall in a claimed chunk untouched under all three; the budget stops each pass at its limit.

### M7 · The companion feed — 3 days · agent

- Destination blocks, atmosphere, mobs and projectiles near the far portal, streamed only to clients that announce the companion, within a per-player budget.

Check: a vanilla client and a companion client stand at the same portal; only the companion receives the feed.

### M8 · The companion client on 26.x — 8 days · agent; owner looks at it

- 12,400 lines ported to 26.3's renderer: the far side drawn in the portal with its sky, fog and colour, and mobs and arrows passing through it. Iris and Distant Horizons are tested alongside.

Check: a screenshot set from the rig, with shaders on and off.

### M9 · Commands — 2 days · agent

- `/customdim create | list | info | tp | render | roll | pick | audit`; the other 1.21.1 commands go with the old roller.

### M10 · Tests and the rig — 5 days · agent

- A game test for every mixin, so the canary shows which one a snapshot broke; the e2e rig (`scripts/e2e/`) rebuilt for 26.3 and the companion.

Check: the smoke test and rig pass on the linked local consumer.

## W · Worldgen

63 days · weeks 4–16 · the critical path. W5 is built in the M stream and counted in M's 46.
Done when: the four base worlds and all 78 other dimensions generate from our kit alone, each with a rolled and picked seed, reviewed from its renders and a walk-through, with no id from a cut mod anywhere.

### W1 · The terrain library — 10 days · agent, human art direction

- Our own named density functions for every terrain row in [worldgen.md](worldgen.md#layers), each with parameters a definition can tune, written for 26.3's single-precision evaluation. Built clean-room from vanilla's functions and the lessons.

Check: a gallery of renders, one per function at three settings, reviewed by the owner.

### W2 · Biome layouts and insertion — 5 days · agent

- The compiler partitions climate space from a definition's biome list with no overlaps; stamps by coordinates and radius, clipped and global swaps, and the spawn-biome filter are ported onto our biome source type.

Check: a stamped biome appears at its coordinates; a swapped biome never appears.

### W2b · Zones — 6 days · agent; owner looks at the renders

- Shapes (circle, ring, box, sector, polygon) around spawn or a point, ordered, each with a blend width and edge jitter; one mask per zone blending every layer. Detail: [worldgen.md](worldgen.md#zones).

Check: the example ring renders cleanly at 2,000 to 3,000 blocks with a natural edge; walking across the edge shows no seam or cliff; a zone's wanted structure appears inside it and nowhere else.

### W3 · Materials, liquids and ores — 5 days · agent

- Palettes as material rules per dimension and biome; sea and lava levels, default fluid and aquifer modes; ore sets and vein settings.

Check: a dry dimension has no water below sea level; a custom ore set shows in a column scan.

### W4 · Structure control — 6 days · agent

- Noise-managed placement, wants and shuns, density presets, forced starts by coordinates and radius, replacements and terrain adaptation, ported; `structure-sizes.json` re-measured on 26.3 for every kept structure (T54).

Check: a forced start lands at its coordinates; a shunned structure never appears in 10,000 chunks.

### W5 · Firewall and bridges — 3 days · agent · built in the M stream

- The audit, the allow list, the bridge format, and a generator that writes a first bridge from a structure mod's jar for a human to tag. Detail: [worldgen.md](worldgen.md#firewall-and-bridges).

Check: adding Terralith to the local stack stops the server with a clear message; removing it starts it again.

### W6 · customdim render — 3 days · agent

- `/customdim render <dimension> [seed] [radius]` from the roller's sampler. Detail: [roller.md](roller.md).

Check: a render matches the same area walked in-game, zone edges included.

### W6b · Roll and score — 6 days · agent

- The sampler, the carried-over scoring, `/customdim roll` with its contact sheet, and `/customdim pick`. Detail: [roller.md](roller.md).

Check: for three reference definitions, the sampler's biome and height at 50 random points match a generated world exactly; 100 seeds score in under ten minutes on the server, in the background, without dropping TPS below 19; the contact sheet opens from disk.

### W7 · Our first biome set — 12 days · agents author, owner reviews

- About 30 datapack biomes with vanilla blocks and our own palettes, features and atmosphere: overworld, caves, Nether, End and sky. Each names what inspired it and what makes it different in look and behaviour; any that is only a remix is dropped.
- Inspiration from the cut mods: Regions Unexplored, Nature's Spirit, Wilder Wild, BetterEnd, BetterNether, Galosphere, YUNG's caves, Underground Worlds and Paradise Lost. Never Stardust Labs' work.

Check: each biome has a render, three in-game screenshots and the owner's yes.

### W8 · Re-author all 82 dimensions — 10 days · agents with the authoring skill, owner chooses

- Every definition moved onto the kit from F3's report: terrain chosen, zones drawn where a dimension wants something guaranteed, cut biomes replaced by vanilla 26.x biomes (pale garden, sulfur caves, dappled forest) or ours, palettes set, structures re-pointed to bridged sets, and a seed rolled and picked. The fourth base world is The Aviary.
- Heights linted to Bedrock's limits.

Check: 82 contact sheets with a picked seed, walk-throughs signed off; the authoring lint finds no id from a cut mod.

## P · Platform and players

17 days · beside M and W.
Done when: a push to a test consumer deploys the 26.3 stack; a vanilla client, the pack and a Bedrock phone all join. P packages have no checks of their own beyond this.

| Id | Title | Days | Who | Work |
| --- | --- | --- | --- | --- |
| P1 | One Minecraft version, set in one place | 2 | agent | `.minecraft-version` selects the version, mod list and image across about 45 files: compose defaults, `pin-mod-versions.sh`, `build-modpack.sh`, `check-updates.sh`, `deploy.sh`, `dev-up.sh`, the pack manifest, dep-review, the smoke test and lint |
| P2 | Java 25 image and the mod list | 2½ | agent, human review | The `-java25` itzg image, looked up live; `config/modrinth-mods.txt` rewritten from the register; configs and seed `COPY` lines for cut mods removed; kept configs re-checked against their 26.3 versions |
| P3 | The vanilla-client join gate | 2 | agent | A real, unmodded 26.3 client joins the smoke-test server in CI; Fabric's registry-sync disconnect fails the job; dep-review runs it on every update |
| P4 | The optional client pack | 1 | agent | The kept client mods, Distant Horizons, shaders and the companion; nothing in it is needed to join |
| P5 | Bedrock | 3 | agent, human for the phone test | Geyser and Floodgate; 19132/udp in compose and `harden.sh`; Floodgate's `key.pem` as a secret in all four places; `/register` accepts a gamertag. Geyser's 26.3 support is an open pull request; launch waits for its release |
| P6 | Admin and Discord parity | 1 | agent | discord-sync's commands, role sync and audit log, dcintegration's chat bridge, LuckPerms, Ledger and spark checked on 26.3 against a written list |
| P7 | Gameplay settings | 1 | agent, owner's choices | Game rules in both places; elytra-armor and inventory-totem configured; the starter kit rebuilt with bundles |
| P8 | Pre-generation and Distant Horizons | 1 plus machine time | — | Chunky and `/dh pregen`, one dimension at a time, base worlds first |
| P9 | Archive the 1.21.1 world | ½ | agent | A final restic snapshot, a last map render and a world download |
| P10 | Docs and skills | 3 | agent | Written in weeks 12–13, landed with the switch: the 1.21.1 mod deleted with its jar-baked Terralith and Tectonic data in the `v6.0.0` cutover; AGENTS.md's fixed decisions (26.3, our worldgen, no Incendium or BetterX sections); TROUBLESHOOTING.md entries that stop applying retired under the id rules; the worldgen-tuning and dimension-authoring skills rewritten for the kit |

## Launch

2 days · everyone; not counted in the 138.
Done when: on production, a vanilla client, the pack and a Bedrock phone each join and cross into at least three dimensions including The Aviary; a zone boundary is walked across without a seam; a bridged structure is found; five players hold 19+ TPS for an evening.

- A local playtest evening first, three kinds of client, fixes before the release.
- Then release `v6.0.0` ([decisions.md](decisions.md), RB-05); its breaking-change note says: new world, Minecraft 26.3, Bedrock needs its UDP port.

## C · Content after launch

About 35 days · in slices, each shippable; a slice starts only if the last one was worth it.

| Id | Title | Days | Who | Work |
| --- | --- | --- | --- | --- |
| C1 | Blocks from data | 5 | agent | Filament's released jar for JSON blocks and items on Polymer, and a generator that writes Geyser mappings and the Bedrock pack from the same JSON |
| C2 | First content set | 15 | agents, owner's art direction | Woods, stones and plants for our biomes; then items that replace cut mods: an explorer's compass using a 26.x dialog, themed armour sets through equippable components, bounty boards, villager professions through 26.1's data-driven trades |
| C3 | Creatures | 15 | agents, owner's art direction | Two creatures through the asset pipeline (concept, model, rig, animation, behaviour) on BIL, with Bedrock models through Geyser's entity API |

## Calendar

Three streams run side by side. The owner's review time is the real limit.

| Weeks | M · The mod | W · Worldgen | P · Platform |
| --- | --- | --- | --- |
| 1 | F0 spike, then F1 skeleton | | |
| 2–3 | F1 skeleton, F2 core, F3 schema and conversion | | |
| 4–5 | M1 compiler, M2 levels | W1 terrain library | P1 version, P2 image and list |
| 6–7 | M3 variants, M4 difficulty, M5 deaths and exits | W1 gallery review, W2 layouts, W2b zones | P3 join gate |
| 8–9 | M6 portals, M6b auras, W5 firewall | W2b zones, W3 materials, W4 structures | P5 Bedrock, P6 admin |
| 10–11 | M7 feed, M8 companion starts | W4, W6 render, W6b roll and score | P4 pack, P7 settings |
| 12–13 | M8 companion, M9 commands | W7 biomes | P10 docs |
| 14–15 | M10 tests and rig | W7 review, W8 re-author and roll the 82 | — |
| 16 | Fixes | W8 sign-off | P8 pre-generation, P9 archive |
| 17 | Playtest evening, then launch | | |

## Cut order

If it runs long, cut in this order:

1. The companion client (M8) ships after launch; pack players see what vanilla players see.
2. W7 launches with 15 biomes, not 30.
3. Dimensions nobody has visited are re-authored after launch, since each is created on first entry.

## Totals

| Stream | Days |
| --- | ---: |
| F | 12 |
| M (incl. W5) | 46 |
| W (excl. W5) | 63 |
| P | 17 |
| **To launch** | **138** |

About 17 weeks with three streams; about 28 weeks as a single stream. Launch (2 days) and C (about 35 days) are outside the total.
