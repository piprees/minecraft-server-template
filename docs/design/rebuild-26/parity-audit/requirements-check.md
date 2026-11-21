# Plan of record against reality

Each requirement in `docs/design/rebuild-26/requirements.md` and each row of `parity.md`, judged on the branch head (abcb99b9, 14 November 2026). "Met" means the server-side mechanism exists and is tested; "partly" says what is missing.

## requirements.md

| # | Requirement | Verdict | Evidence | What is missing |
| --- | --- | --- | --- | --- |
| R1 | Dimensions consumers can design | Met | `mods/custom-dimensions-26/schema/definition.schema.json`; compiler at start-up (RB-33); `stage-dimensions.py` in the cutover kit (cutover-removals.md) | Consumer overlay staging lands only at the switch |
| R2 | Control of the base dimensions and every generator variant | Met | README § Generators (noise presets, checkerboard, single_biome, flat, void, clone); RB-37 base-world seeds | — |
| R3 | Difficulty per dimension, spawn tables through `natural_mob_spawns` | Partly | README § Difficulty (multiplier, attributes, depth, luck, peaceful; RB-32) | Per-dimension spawn tables: README § Difficulty says none are written and no field asks for one. The owner's boneyard note asks for skeleton, ghast and bat spawners |
| R4 | Live previews through portals | Met | `PortalPresentation` (title on approach), `PortalTintStates`, companion feed and client (portals.md § Previews); portal round with both clients | — |
| R5 | Portals of any orientation, shape and material | Met | Matrix 79 of 79 bays (HANDOFF State) | — |
| R6 | Auras | Met | README § Auras, `AuraSelfTest` | — |
| R7 | Structures and biomes by coordinates, radius or replacement | Partly | Forced starts with `y` (K12), stamps and swaps (zones) | `/locate` and maps find nothing our placements place (K13 in progress); structures float in the void on sky worlds (owner, K13) |
| R8 | Zones | Met | RB-27; README § Generators `zones`; self-test zone lines | — |
| R9 | Rolling and scoring seeds | Met as a tool | README § Rolling (`roll`, `pick`, `render`, `survey`, `fastrender`) | Not yet used: no dimension has a rolled-and-picked seed at the owner's bar (HANDOFF Next, item 5) |
| R10 | Death, exit and win conditions | Met | README § Exits, deaths and wins | Exit shrines generate nowhere: the `adventure:exit_shrine` structure is not shipped on 26.3 |
| R11 | Our own worldgen | Met as a kit; partly as content | Terrain library, roofed and End families, palettes, ores, liquids, 82 library biomes | 31 of 82 dimensions not yet re-authored at the bar (Batches 4–6); biome breadth about a third of 1.21.1's |
| R12 | Server-side except where impossible | Met for what exists | P3 join gate, P5 Bedrock | Every content gap below must also meet it |
| R13 | Custom blocks and biomes inspired by the mods we cut | Partly | Biomes: 82 library biomes | Blocks, items and creatures (C1–C3) not started; the plan puts them after launch, which the owner's bar does not allow |
| R14 | Other mods allowed; other worldgen blocked | Met | README § Firewall; 12 reviewed bridges | Bridges for the structure mods that now have 26.3 builds |
| R15 | One-click join for friends | Mostly | `modpack/adventure-26.3.mrpack.json` (49 mods), pack join test (parity.md) | Shaders on a GPU (owner); 17 client rows wait |
| R16 | Admin and Discord | Partly | parity.md | Chat bridge (dcintegration has no 26.3 build); `chunky border add` fails; Discord-side checks are the owner's |
| R17 | No client mod necessary | Met | CI vanilla join gate (parity.md "Vanilla client join") | — |

Tally: 9 met, 3 met as tools or kit with content outstanding (R9, R11, R12), 5 partly (R3, R7, R13, R15, R16).

## parity.md

| Row | parity.md result | Verdict today | Note |
| --- | --- | --- | --- |
| Role sync: whitelist, op, deop | Pass | Met | — |
| `/mc status` list, chunky, position | Pass | Met | — |
| `/mc status` TPS, MSPT, memory | Fallback to `tick query`; memory `?` | Partly | Memory figure lost; a `/proc`-based memory read in discord-sync would restore it |
| say, whisper, weather, tempban, pardon, kill, give, effect, gamemode, xp | Pass | Met | — |
| heal, feed, fly, invuln, extinguish | Pass | Met | — |
| enchant | Pass | Met | — |
| claims | Pass (accepted) | Met | — |
| border get/center/set | Pass | Met | — |
| border `chunky border add` | Fails: "chunkyborder has no 26.x build" | Not met; premise stale | Chunky Border 1.2.75 has a 26.3 build (14 Nov); add it or drop the lines (owner) |
| restart | Pass | Met | — |
| Audit relay of in-game admin actions | Pass | Met | — |
| Audit log of `/mc` to Discord | Needs the owner | Open | Owner's token test |
| Slash command registration | Needs the owner | Open | Owner's token test |
| Chat bridge | Waiting for the mod | Not met | No 26.3 build on 14 Nov; "waiting" is not delivered (owner) |
| deploy.sh game rules | Pass | Met | — |
| New-world game-rule defaults | Deploy enforcement | Met | — |
| LuckPerms groups | Pass | Met | — |
| Ledger, spark, Essential Commands, OPAC | Pass | Met | — |
| Autopause | Needs the owner | Open | — |
| Vanilla client join | Pass | Met | — |
| Pack join | Pass, shaders off | Mostly | Shaders on a GPU (owner) |
| Geyser and Floodgate load; Geyser answers | Pass | Met | — |
| Bedrock whitelist by `/register` | Pass | Met | K17 open: `fwhitelist` probe on a repeated run |
| A phone joins | Needs the owner | Open | — |

Tally of 24 rows: 16 met, 2 partly or mostly, 2 not met (chat bridge, chunky border), 4 open on the owner.

## Where the plan of record is stale or wrong

- **The register's build column is from 27 September.** On 14 November these "waiting" rows have 26.3 builds: friends-and-foes (and its Polymer patch), sit! (and otterlib), explorify, ender-dragon-destroys-end-island, no-chat-reports, krypton, and eight client rows; `dungeons+` is now DeCubed Dungeons (1.13.1 for 26.3). Chunky Border, a "replaced" row, also has one. (`/tmp/claude-0/a1/modrinth-26.3.tsv`.)
- **pack.md § Additions is not in the list.** Server-Side Waystones, Repurposed Structures, Trek, Hopo Better Mineshaft and DeCubed Dungeons all have 26.3 builds and none is in `config/modrinth-mods-26.3.txt`.
- **pack.md counts disagree with the register** for libraries (pack.md: 13 kept, 14 cut; register: 12 kept, 15 cut).
- **comforts "replaced by vanilla 26.x"** has no basis: 26.3's item registry has no sleeping bag.
- **cutover-removals.md's betterdays row** says there is no day-length field; `atmosphere.dayLength` exists (`compiler/.../DayLength.java`, `definitions/settings.json`).
- **id-mapping.csv** points 48 structure ids from *waiting* mods at stand-ins; explorify's and dungeons_plus's can go back to their own ids now.
- **work-plan.md puts C1–C3 after launch** and "waiting" mods join "when their authors ship". The owner's bar (every 1.21.1 feature on 26.3, kept or rebuilt; waiting is not delivered) contradicts both. This is the main reason "80%" overstated progress: it measured the plan's launch scope, not parity.
