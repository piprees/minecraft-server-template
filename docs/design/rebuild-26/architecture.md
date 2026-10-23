# Architecture

The rebuilt mod lives in `mods/custom-dimensions-26/` as Gradle subprojects `core`, `compiler`, `server` and `client`. Decisions behind it: [decisions.md](decisions.md).

## Four parts

Dependencies run one way. Only the runtime touches Minecraft's internals, and it is the part kept small.

| Part | Minecraft? | What it holds |
| --- | --- | --- |
| 1 · Definitions and core | No | The config model, validation, zone geometry, scoring, structure-noise maths, portal geometry and difficulty rules. Most of the 1.21.1 mod's tests live here and carry over nearly as they are. A lint rule fails any Minecraft import |
| 2 · The compiler | No | Turns definitions into a datapack: dimension types with environment attributes, timelines and clocks, world borders, noise settings, material rules, biome layouts and structure-set overrides |
| 3 · The runtime | Fabric, Mojang names | Only what data can't do: the roller's sampler, lazy levels through Fantasy, portals on vanilla's `Portal` interface, one biome source type for stamps and swaps, structure control, mob difficulty, deaths and exits, the firewall and the companion feed |
| 4 · The companion client | Optional | Renders previews through portals from the feed ([portals.md](portals.md)) |

## Packages

| 1.21.1 package | Lines | In the rebuild |
| --- | --- | --- |
| config, score and the structure-noise maths | ≈7,200 | Carried over into core; scoring feeds the new roller |
| dimension | 11,744 | Split: the static half becomes compiler output; lazy levels move onto Fantasy; biome stamps and structure control are ported |
| portal | 6,014 | Ported onto a Polymer portal block implementing vanilla `Portal`; three portal mixins go |
| command | 7,448 | Trimmed to about 2,000 |
| companion (server side of previews) | 2,125 | Ported; it feeds the companion client, the only preview path. The client mod itself (12,400 lines, `mods/custom-dimensions-client`) is ported in M8 |
| immersive | 4,840 | Cut: the fake-block projection painted into each player's world. Its geometry feeds the companion |
| roll, facts, web, tryout | 12,342 | Cut: the new roller samples the real generator ([roller.md](roller.md)) |
| compat and 4 compat mixins | 565 | Cut with BetterX, BetterCaves and Snow Blanket |
| mixin: 34 plus 4 compat | 2,469 | 12 or fewer, each with a game test |

About 17,700 lines are cut. Target: about 32,000 lines and 12 or fewer mixins, against 54,500 lines and 38 mixins in the 1.21.1 mod.

Commands: `/customdim create | list | info | tp | render | roll | pick | audit`.

## Mixins

**Stay (the budget):** mob attribute scaling at spawn; respawn redirect; forced structure starts; noise structure selection; terrain adaptation around structures; End gateway, End portal and Nether portal suppression where a definition asks for it; frame-break detection for portal decay.

**Go:**

| Mixin | Replaced by |
| --- | --- |
| `CreateWorldsMixin`, `WorldLoaderMixin` | Generated base-world entries and Fantasy |
| The registry accessor | Fantasy |
| The three portal tick and ignition mixins | Vanilla `Portal` and Fabric's use-block event |
| The per-level seed mixin | Fantasy's `setSeed` |
| The chunk-sampler and forced-ground mixins | Our density functions |
| The chunk-ticket and unload mixins | Fantasy's unload |

## Dependencies

Fabric API, Fantasy (LGPL, bundled inside our jar) and Polymer, all kept current by active maintainers. Polymer carries the portal block (M6) from launch and, from C1, our content. Nothing else.

## The compiler

- **Input:** definitions validated against the schema v2 JSON Schema (F3).
- **Output:** `world/datapacks/customdim-generated/` — dimension types with attributes, timelines and a clock; per-dimension borders; noise settings; material rules; biome layouts; structure-set overrides.
- **When:** before the server starts, from `deploy.sh` and `dev-up.sh`, and again inside the mod at start-up to confirm the pack on disk matches.
- **Version table:** each 26.x release's field names, so a renamed field is a table entry. It covers the 26.3 and 26.4-snapshot differences: `default_block` gone, no overlapping multi-noise entries.
- **Tests:** golden files per version; the generated pack loads on a vanilla 26.3 server with no mods.

## Levels

- **Base four, eager:** `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` and `adventure:the_aviary` come from generated entries and load at start. The Sift, when a snapshot adds it, becomes a fifth base world the same way.
- **The other 78, lazy:** Fantasy's persistent levels open on first entry, one first-time generation at a time ([K6](../../../TROUBLESHOOTING.md#k6)), and unload when idle.
- **Deletion:** Fantasy's delete unregisters the level stem and removes `dimensions/<ns>/<slug>/`. Fantasy never writes runtime levels to `data/minecraft/world_gen_settings.dat`, so that file needs no edit.
