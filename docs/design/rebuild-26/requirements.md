# Requirements

The acceptance test for the rebuild. A requirement is met when its server-side mechanism works for an unmodded 26.3 Java client; the optional client column is never needed to play. Work package ids refer to [work-plan.md](work-plan.md).

| # | Requirement | Server-side, for every client | Optional client adds | Bedrock | Work |
| --- | --- | --- | --- | --- | --- |
| R1 | Dimensions consumers can design | One JSON definition per dimension, validated against a published schema and compiled into an ordinary datapack. Raw vanilla JSON (a density function, a biome) can sit beside it in an overlay datapack and be referenced by id | — | Same | F3, M1 |
| R2 | Control of the base dimensions and every generator variant | Overworld, Nether, End and The Aviary are definitions like the other 78. Variants: normal, large biomes, amplified, caves, floating islands, Nether, End, flat, checkerboard, void and single-biome | — | Same (heights linted to Bedrock's limits) | M2, M3 |
| R3 | Difficulty per dimension | Mob multiplier, depth scaling, player luck, peaceful dimensions; spawn tables through the 26.3 `natural_mob_spawns` attribute | — | Same | M4 |
| R4 | Live previews through portals | A coloured portal, destination particles and the destination's name on approach | The far side rendered in the portal, live: colour, sky, fog, blocks, mobs and projectiles | Coloured portal and particles | M6, M7, M8 |
| R5 | Portals of any orientation, shape and material | Vertical on either axis, horizontal (End-portal style) and single-cell gateways; `door`, `doorway`, `end_exit`, `end_gateway` and drawn templates; frames from a block, a tag, a list, a colour group, or different materials per part (base, sides, lintel), mixed freely | — | Same shapes and frames; the interior shows as a Nether portal, End portal or gateway | M6 |
| R6 | Auras: worlds bleeding into each other around portals | Each end slowly converts its surroundings to the far side's nature, sampled where the portal opened: terrain, plants, trees, fluids, fire. `subsume` sets what it may eat (`none`, `natural`, `everything`); claimed land is never touched | — | Same (real blocks) | M6b |
| R7 | Structures and biomes by coordinates, radius or replacement | Forced structure starts, biome stamps, clipped and global swaps, per dimension | — | Same (custom biomes show as their nearest vanilla biome) | W2, W4 |
| R8 | Different worldgen in different places: rings, areas, distances from spawn | Zones: each a shape around spawn or a point, with its own terrain, biomes, materials, liquids, ores and structures, blended into the rest in the noise itself | — | Same | W2b |
| R9 | Rolling and scoring seeds | `customdim roll` samples the real generator for each seed, scores it against the definition and renders the best; `customdim render` draws any seed on demand; `customdim pick` writes the chosen seed | — | — | W6, W6b |
| R10 | Death, exit and win conditions | Respawn redirects, keep-inventory rules, exit portals and shrines, win triggers | — | Same | M5 |
| R11 | Our own worldgen: noise, biomes, voids, islands, liquids, aquifers, ores, materials | A terrain library of our own density functions, per-dimension biome layouts, material palettes, liquid and ore settings, all as generated datapack JSON | — | Terrain and blocks identical; custom biome tints fall back | W1–W8 |
| R12 | Server-side except where impossible | Everything above | Previews only | Through Geyser and Floodgate | P5 |
| R13 | Custom blocks and biomes inspired by the mods we cut | Biomes at launch (vanilla blocks, our palettes and atmosphere); blocks and creatures after, through Polymer | Pack players see our textures without the stand-in step | Custom blocks through Geyser mappings we generate | W7, C1–C3 |
| R14 | Other mods allowed; other worldgen blocked | Worldgen firewall; bridge files adopt a mod's structures and features | — | — | W5 |
| R15 | One-click join for friends | The `.mrpack` through Prism | Shaders, Distant Horizons, maps, voice, animations and the companion | Nothing to install | P4 |
| R16 | Admin and Discord | discord-sync, dcintegration, LuckPerms, Ledger, spark and Essential Commands | — | Bedrock players register through `/register` with their gamertag | P6 |
| R17 | No client mod necessary | An unmodded 26.3 client joins the CI smoke-test server; Fabric's registry-sync disconnect fails the job, so no mod that needs a client reaches production. dep-review runs the gate on every update | Effects, shaders and UI only | Joins through Geyser and Floodgate with nothing installed | P3, P5 |

Design detail: [worldgen.md](worldgen.md) (R7, R8, R11, R14), [roller.md](roller.md) (R9), [portals.md](portals.md) (R4–R6), [pack.md](pack.md) (R13, R15–R17).
