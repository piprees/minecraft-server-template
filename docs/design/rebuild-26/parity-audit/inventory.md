# Feature inventory: the 1.21.1 pack against 26.3

Status as of 14 November 2026 on branch `claude/minecraft-dependency-automation-n1w79q` (head abcb99b9). Paths are repository-relative unless they start with `/tmp`. "R" in the evidence column means `docs/design/rebuild-26/mod-register.csv`. Modrinth facts are from the API on 14 November (`/tmp/claude-0/a1/modrinth-26.3.tsv`, `modrinth-sides.tsv`).

Score: delivered 1, mostly 0.75, partial 0.5, a start 0.25, missing 0; dropped by the owner is left out. Weight: 3 signature, 2 notable, 1 minor. The source data and the scoring are `/tmp/claude-0/a1/inv.py`.


## Worldgen

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Overworld terrain: continents, ranges, rivers, canyons, varied relief | Tectonic, Terralith | Delivered: our terrain library (11 terrains, scales, layout, places) | delivered | 3 | mods/custom-dimensions-26/README.md § Generators (`terrain`, `RoofedTerrain`, `EndTerrain`); HANDOFF 10-25 W1b; 21 terrain picks | server |
| Overworld biome variety | Terralith (~95), Regions Unexplored, Nature's Spirit (51), Wilder Wild (30), WWOO | Partial: 38 overworld library biomes + 52 vanilla 26.3 surface and ocean biomes, against ~250 modded overworld biomes | partial | 3 | mods/custom-dimensions-26/compiler/src/main/resources/customdimensions/biomes/ (82 files: 38 overworld, 12 caves, 13 nether, 13 end, 6 sky); vanilla 26.3 has 67 biomes | server |
| Caves and underground biomes | YUNG's Better Caves, YUNG's Cave Biomes, Galosphere, Underground Worlds, RU caves | Partial: 12 cave library biomes, `carvers` field, vanilla caves (incl. sulfur_caves); no cave-network style of our own | partial | 2 | mods/custom-dimensions-26/README.md biome table (realm caves); fidelity/gaps.md G6 | server |
| Nether terrain and biomes | Incendium, BetterNether (22 biomes), RU Nether | Mostly: roofed terrain family, 13 Nether library biomes, places; Batch 2 (11 Nether dims) at 4/5 | mostly | 3 | HANDOFF 04:31 W10c2c final (Batch 2 all 4/5); README § Generators `roofed` | server |
| End terrain and biomes | Nullscape, BetterEnd (25 biomes) | Partial: End terrain family, 13 End library biomes; owner wants variety on the outer islands (the_end marked change) | partial | 3 | mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md (the_end); fidelity/gaps.md G3-G5 | server |
| Sky world | Paradise Lost (Skylands, 10 biomes) | Mostly: The Aviary with 6 sky library biomes + vanilla, 9/9 7/7 13/13 targets; Paradise Lost's blocks and mobs not rebuilt | mostly | 2 | HANDOFF 14:59 W10c3 final (aviary 9/9 7/7 13/13); RB-17 | server |
| Oceans and coasts | Terralith coasts, vanilla | Delivered at terrain level (shore handling, landShare, basins) | delivered | 1 | HANDOFF 11-07 W8l (Ctx.shore), K9 landShare | server |
| Ores, liquids, aquifers, materials per dimension | custom-dimensions settingsOverrides | Delivered and extended (palettes, ore sets, dry/flooded, lava level, basins) | delivered | 2 | HANDOFF 10-25 W3 merged; README § Runtime levels `WorldgenRuntime` | server |
| Biome stamps, swaps, rings and zones | custom-dimensions biomePatches | Delivered and extended (zones: circle, ring, box, sector, polygon) | delivered | 2 | requirements.md R7/R8; RB-27; README § Generators `zones` | server |
| All 82 dimensions true to their 1.21.1 description | custom-dimensions + all worldgen mods | Partial: 51 of 82 at the terrain/biome bar (21 picks + Batches 1-3); Batches 4-6 (31) not started; no dimension has its places/structures pass; seeds not rolled and picked for all 82 | partial | 3 | mods/custom-dimensions-26/fidelity/batches.md; mods/custom-dimensions-26/fidelity/reviews/ (30 dimensions); HANDOFF State 'Next' item 5 | server |

## Structures

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Kept structure packs placed in every fitting dimension | Moog's x5, Towns and Towers, Dungeons and Taverns, ATi, Epic, Deadly Deadly Dungeon | Mostly: 10 mods kept, 12 reviewed bridges adopting 534 structure entries; owner sees too few places in shots | mostly | 3 | mods/custom-dimensions-26/bridges/*.json; owner-review-1.md rulings | server |
| YUNG's overhauls of vanilla structures | YUNG's x12 (dungeons, mineshafts, strongholds, fortresses, monuments, temples, witch huts, bridges, extras) | Missing: vanilla versions only; no 26.3 builds (Modrinth 14 Nov) | missing | 3 | /tmp/claude-0/a1/modrinth-26.3.tsv; config/modrinth-mods-26.3.txt waiting block | server |
| Mega-dungeons and sea structures | When Dungeons Arise, WDA Seven Seas | Missing: no 26.3 builds | missing | 3 | /tmp/claude-0/a1/modrinth-26.3.tsv | server |
| Other structure packs | Explorify, Dungeons+ (now DeCubed), Stoneholm, Sky Villages, Underground Bunkers, Simply Houses, Hellish Trials, Medieval End, Adventure Dungeons, Subsurface, True Ending | Missing; Explorify v1.6.6 and DeCubed Dungeons 1.13.1 now have 26.3 builds and can join | missing | 2 | /tmp/claude-0/a1/modrinth-26.3.tsv; modrinth-sides.tsv (both server-required, client optional) | server |
| Structures that came with worldgen mods | BetterNether (Nether City), Incendium, Nullscape, BetterEnd, Paradise Lost dungeons, Underground Worlds (9 dungeons), Terralith (mage towers, underground dungeons), Philips Ruins (29 sets), Structory, Galosphere | Missing: 74 theme ids lost, each mapped to a stand-in from a kept mod (id-mapping.csv); no set pieces of our own | missing | 3 | mods/custom-dimensions-26/core/src/main/resources/structure_themes.json by namespace; mods/custom-dimensions-26/definitions/id-mapping.csv (106 structure rows, 0 removed) | server |
| Structure control: wants, shuns, force, mode, spacing, density, endgame, loot, repetition | custom-dimensions | Delivered and extended | delivered | 2 | mods/custom-dimensions-26/README.md § Structure control; HANDOFF 10:38 K12 | server |
| /locate, explorer and treasure maps, eyes of ender for our placements | vanilla + custom-dimensions | Missing: /locate answers 'Could not find' for noise-group placements (K13 in progress) | missing | 2 | HANDOFF 14:59 (K12 lane c1); /tmp/claude-0/k13/progress.log | server |
| Structures sitting on ground (never floating in void) | custom-dimensions | Missing on sky worlds (owner: snowbound isle, abyssal shrine); K13 in progress | missing | 1 | owner-review-1.md rulings | server |
| Exit shrines (adventure:exit_shrine ruins) | custom-dimensions jar datapack | Partial: registration of a hand-built shrine works; the structure, its templates and pool are not shipped on 26.3, so none generate | a start | 1 | mods/custom-dimensions-26/server/.../portal/ExitShrines.java; no exit_shrine in mods/custom-dimensions-26/server/src/main/resources/data; 1.21.1 mods/custom-dimensions/src/main/resources/data/adventure/structure/exit_shrine | server |

## Dimensions and portals

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Dimensions from JSON, schema, consumer overlays | custom-dimensions | Delivered (schema v2, compiler at start-up, stage-dimensions in the cutover kit) | delivered | 3 | mods/custom-dimensions-26/schema/definition.schema.json; RB-33; cutover-removals.md (stage-dimensions.py) | server |
| Generator types and clones | custom-dimensions (12 types + ns:path clones) | Delivered (noise presets, checkerboard, single_biome, flat, void, clone) | delivered | 2 | mods/custom-dimensions-26/README.md § Generators | server |
| Lazy levels and idle unload | custom-dimensions | Delivered (Fantasy, one first-time generation at a time) | delivered | 2 | mods/custom-dimensions-26/README.md § Runtime levels `LevelManager` | server |
| Portals: frames, igniters, shapes, orientations, per-part materials, scaling | custom-dimensions | Delivered (matrix 79 of 79 bays) | delivered | 3 | HANDOFF State (portal round on a copy of Elfydd); README § Portals | server |
| Anchor, single-use, exit and vanilla-managed portals | custom-dimensions | Delivered | delivered | 2 | mods/custom-dimensions-26/README.md § Portals (`ExitPortals`, `Portals`) | server |
| Portal auras and subsume | custom-dimensions | Delivered | delivered | 2 | mods/custom-dimensions-26/README.md § Auras; AuraSelfTest | server |
| Entity pass-through | custom-dimensions immersive | Delivered (every entity crosses, RB-29) | delivered | 1 | RB-29; README `PortalTravel` | server |
| Portal previews for every client (fake-block projection) | custom-dimensions immersive | Dropped by the owner (RB-03): companion previews, coloured portal, particles, title instead | dropped (owner) | 2 | decisions.md RB-03 | client optional |
| Far-side biome ambience at the portal | custom-dimensions immersive (audio) | Missing: went with the immersive package; no ambience in portal/ or companion/ | missing | 1 | 1.21.1 README § Immersive portals ('audio'); no ambient sound in server/.../portal | server |
| Difficulty per dimension (multiplier, attributes, depth, luck, peaceful) | custom-dimensions (absorbed configurable-difficulty) | Delivered | delivered | 2 | mods/custom-dimensions-26/README.md § Difficulty | server |
| Exits, dimension links, deaths, wins | custom-dimensions | Delivered and extended (deaths, wins as advancements) | delivered | 2 | mods/custom-dimensions-26/README.md § Exits, deaths and wins | server |
| Atmosphere: sky, fog, fixed time, day length, dimension type fields | custom-dimensions environment, Better Days | Delivered (fog over biomes by default in K13) | delivered | 2 | mods/custom-dimensions-26/schema (atmosphere.dayLength, overBiomes); compiler DayLength.java | server |
| Per-dimension seeds and game rules, borders | custom-dimensions, Chunky Border | Delivered and extended (RB-37, RB-38; vanilla per-dimension borders) | delivered | 2 | decisions.md RB-37, RB-38; README § Runtime levels (borders) | server |
| Admin commands and diagnostics | custom-dimensions (create, list, locate, lint, census, facts, score, roller with tryout) | Mostly: list/info/tp/activate/unload/delete/create/audit/catalogue/render/fastrender/map/roll/pick/survey; async locate missing (K13); tryout dropped (RB-15) | mostly | 2 | mods/custom-dimensions-26/README.md § Commands; decisions.md RB-15 | server |
| Seed roller and viewer | custom-dimensions web roller | Delivered differently: roll contact sheets, survey dossiers, map.DOMAIN | delivered | 1 | roller.md; README § Rolling, § The web map | server |

## Mobs and creatures

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Mob-vote mobs and their 4 structures | Friends & Foes | Missing; its 26.3 build (5.0.4) and the Polymer patch (5.0.4-0+26.3, 4 Nov) both exist now | missing | 3 | /tmp/claude-0/a1/modrinth-26.3.tsv; modrinth-sides.tsv (patch: client unsupported) | server (with Polymer patch) |
| Small creatures (otters, ferrets, koi, red pandas, dragonflies...) | Critters and Companions | Missing: 'rebuilt by us' in C3, not started | missing | 2 | work-plan.md C3; no entity code in the server module | needs Polymer virtual entities |
| Biome creepers | Creeper Overhaul | Missing: cut, no replacement | missing | 1 | docs/design/rebuild-26/mod-register.csv | needs Polymer virtual entities |
| Ducks and geese | Untitled Duck Mod | Mostly: clucking-ducks (ducks as chicken variants) | mostly | 1 | config/modrinth-mods-26.3.txt datapack:clucking-ducks | server |
| Worldgen mods' creatures (End, Nether, sky, Wilder Wild, Galosphere) | BetterEnd, BetterNether, Paradise Lost, Wilder Wild, Galosphere | Missing (vanilla 26.x adds happy ghast, copper golem, creaking, nautilus, sulfur cube) | a start | 2 | vanilla 26.3 reports/registries.json entity_type (161) | needs Polymer virtual entities |
| Persistent-hostile sweep (adventure-mob-sweep datapack) | our datapack | Unverified on 26.3 (pack_format 48) | partial | 1 | config/datapacks/adventure-mob-sweep/pack.mcmeta | server |
| Mob tweaks (cave spiders, grabby mobs, tempt delay, name tags) | Serilum mods | Delivered (kept) | delivered | 1 | docs/design/rebuild-26/mod-register.csv | server |

## Items, gear and loot

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Exploration artefacts with passive powers | Artifacts | Missing: C2, not started | missing | 3 | work-plan.md C2 | needs Polymer items |
| Themed armour sets | Immersive Armors | Missing: C2, not started | missing | 2 | work-plan.md C2 | needs Polymer items |
| Dungeons weapons and artefacts | Dungeons Reborn | Missing: cut | missing | 1 | docs/design/rebuild-26/mod-register.csv | needs Polymer items |
| Weapon swing animations | Better Combat | Cannot be server-only (client animations); cut | missing | 1 | docs/design/rebuild-26/mod-register.csv | client required |
| Explorer's and Nature's compasses | Explorer's Compass, Nature's Compass | Missing: C2, not started (both need a client on 26.3) | missing | 2 | work-plan.md C2; modrinth-sides.tsv | needs Polymer item + locate |
| Elytra with a chestplate | Elytra Slot | Delivered: elytra-armor | delivered | 1 | config/modrinth-mods-26.3.txt | server |
| Totem from anywhere | Charm of Undying | Delivered: inventory-totem | delivered | 1 | config/modrinth-mods-26.3.txt | server |
| Backpacks | Inmis | Mostly: vanilla bundles | mostly | 1 | vanilla 26.3 items (bundle and 16 colours) | server |
| Per-player structure loot | Lootr | Delivered: slashloot | delivered | 2 | config/modrinth-mods-26.3.txt | server |
| Elytra from End cities | vanilla + Lootr conversion | Delivered: denser End dungeons, elytra 8% a chest (K12) | delivered | 1 | HANDOFF 10:38 K12 | server |
| Enchanting and archery extras | Overstacked Enchantments, Expanded Bow Enchanting, Borrow Their Arrows, Enchanting Commands | Partial: enchanting-commands kept; the other three wait (no 26.3) | partial | 1 | docs/design/rebuild-26/mod-register.csv | server |

## Building and decoration

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Decorative block variants (~7,000) | Chipped | Missing: cut | missing | 2 | docs/design/rebuild-26/mod-register.csv | needs Polymer blocks + resource pack |
| Furniture you can sit on | Handcrafted, Macaw's Furniture | Partial: polydecorations | partial | 2 | config/modrinth-mods-26.3.txt | server |
| Jars, cages, wall lanterns and the rest | Supplementaries, Amendments | A start: polydecorations, vanilla shelves | a start | 2 | docs/design/rebuild-26/mod-register.csv | server |
| Tall and extra doors | Dramatic Doors, Macaw's Doors | Partial: gm4-double-doors (tall doors from trapdoors), double-doors | partial | 1 | Modrinth gm4-double-doors description | server |
| Roof shapes | Macaw's Roofs | Missing: cut | missing | 1 | docs/design/rebuild-26/mod-register.csv | needs Polymer blocks |
| Bridges | Macaw's Bridges | Missing: abridged (worldgen bridges, not blocks) has no 26.3 build | missing | 1 | /tmp/claude-0/a1/modrinth-26.3.tsv | server |
| One-station woodcutting | Universal Sawmill | Missing: cut | missing | 1 | docs/design/rebuild-26/mod-register.csv | server (recipes) |
| New woods, stones and plants | Nature's Spirit, Regions Unexplored, BetterEnd (686 blocks), Wilder Wild, Galosphere | Missing: C1/C2 not started (vanilla 26.x adds pale oak, poplar, sulfur, leaf litter, bushes) | a start | 2 | work-plan.md C1-C2; vanilla 26.3 blocks (1,286) | needs Polymer blocks + resource pack |
| Pick up chests and furnaces | Carry On | Delivered (kept) | delivered | 1 | docs/design/rebuild-26/mod-register.csv | server |

## Farming and food

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Cooking pot and skillet meals | Farmer's Delight | Delivered (kept with its Polymer patch) | delivered | 3 | config/modrinth-mods-26.3.txt | server (Polymer) |
| Wine chain | Let's Do Vinery | Missing: C2 | missing | 1 | docs/design/rebuild-26/mod-register.csv | needs Polymer |
| Feeding trough | Animal Feeding Trough | Missing: C2 (its 26.3 build needs a client) | missing | 1 | modrinth-sides.tsv | needs Polymer |
| Placeable food | 3D Placeable Food | Missing: cut (26.3 build needs a client) | missing | 1 | modrinth-sides.tsv | needs Polymer |
| Seasons | Fabric Seasons (+ extras, Delight compat) | Missing: cut; P7 recommends accepting the loss, owner not yet asked | missing | 2 | cutover/p7-proposal.md § 5 | server (crop growth, snow); tints are client-side |
| Farmers replant; food tweaks | Smarter Farmers, AppleSkin, Nutritious Milk, Healing Campfire | Delivered (vanilla replanting, kept mods) | delivered | 1 | docs/design/rebuild-26/mod-register.csv | server |

## Villagers and trading

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| New professions | More Villagers Re-employed | Missing: C2 (data-driven trades in 26.x) | missing | 2 | work-plan.md C2 | server (data) + Polymer POI blocks |
| Bounty boards | Bountiful | Missing: C2 (dialog on a Polymer block) | missing | 2 | work-plan.md C2 | needs Polymer + 26.x dialog |
| Armed village guards | Guard Villagers | Missing: cut | missing | 1 | docs/design/rebuild-26/mod-register.csv | needs Polymer virtual entity or vanilla-mob stand-in |
| Villager names | Villager Names (Serilum) | Delivered (kept) | delivered | 1 | docs/design/rebuild-26/mod-register.csv | server |

## Quality of life and gameplay

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Server-side QoL kept: elytra take-off, falling trees, healing campfire, double doors, stack refill, starter kit, safe items, bouncy beds | Serilum and others | Delivered (26 gameplay mods kept) | delivered | 2 | pack.md counts; config/modrinth-mods-26.3.txt | server |
| Sitting on stairs and slabs | Sit! | Missing; its 26.3 build (1.2.6.4, server-only) exists now | missing | 1 | modrinth-26.3.tsv, modrinth-sides.tsv | server |
| Longer days | Better Days | Delivered: atmosphere.dayLength (45/15 default) | delivered | 1 | mods/custom-dimensions-26/compiler/.../DayLength.java; definitions/settings.json | server |
| Pause the day cycle when empty | Pause Day Cycle | Partial: autopause freezes the JVM; the mod waits (no 26.3) | partial | 1 | docs/design/rebuild-26/mod-register.csv | server |
| Item clearing | Ground Clear | Partial: Clumps kept; Ground Clear waits | partial | 1 | docs/design/rebuild-26/mod-register.csv | server |
| Dragon fight damages the End island | Ender Dragon Destroys End Island | Missing; its 26.3 build exists now (server-only) | missing | 1 | modrinth-26.3.tsv | server |
| Sleeping bags | Comforts | Missing: the register says 'vanilla 26.x', but 26.3 has no sleeping-bag item | missing | 1 | vanilla 26.3 items (no 'sleep' item) | needs Polymer item |
| Game-rule defaults | Boring Default Game Rules | Delivered: deploy.sh enforcement, per-dimension gameRules | delivered | 1 | parity.md; RB-38 | server |
| Claims, voice chat | Open Parties and Claims, Simple Voice Chat | Delivered (kept) | delivered | 1 | docs/design/rebuild-26/mod-register.csv | server; voice optional client |

## Admin and community

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Discord slash commands, role sync, audit relay | discord-sync | Delivered (RCON half passes; Discord side needs the owner's token check) | mostly | 3 | parity.md | server |
| Discord chat bridge | dcintegration | Missing: no 26.3 build (newest 26.2) | missing | 2 | parity.md; modrinth-26.3.tsv (absent) | server |
| Permissions, rollback, profiling, admin commands | LuckPerms, Ledger, spark, Essential Commands, Carpet | Delivered (spark health memory figure falls back) | mostly | 2 | parity.md | server |
| /mc border via chunky border | Chunky Border | Fails today; Chunky Border 1.2.75 now has a 26.3 build | partial | 1 | parity.md; modrinth-26.3.tsv | server |
| Web map with markers | uNmINeD, BlueMap sign markers | Mostly: our map export with structure markers (M12); sign markers cut | mostly | 2 | mods/custom-dimensions-26/README.md § The web map | server |
| Backups, restore, reset-seed | mc-backup sidecar | Delivered (independent of the game version; restore not rehearsed on a 26.3 world) | mostly | 2 | HANDOFF Next item 6 (rehearsal not run) | server |
| Pre-generation and Distant Horizons LODs | Chunky, Distant Horizons | Delivered and extended (our DH world generator) | delivered | 1 | mods/custom-dimensions-26/server/.../lod/DhLodGenerator.java | server |
| Autopause | itzg image | Needs the owner's check on 26.3 | mostly | 1 | parity.md | server |

## Clients and joining

| Feature | 1.21.1 source | 26.3 status | Score | Wt | Evidence | Server-only |
| --- | --- | --- | --- | ---: | --- | --- |
| Unmodded Java client joins (hard requirement) | - | Delivered: CI join gate | delivered | 3 | parity.md 'Vanilla client join' | server |
| Bedrock and mobile | - (new) | Mostly: Geyser and Floodgate pass; the phone join is the owner's test | mostly | 3 | parity.md Bedrock rows | server |
| Optional pack: shaders, Distant Horizons, maps, sounds | client mods | Mostly: 49 mods; 17 client rows wait; shaders on a GPU untested | mostly | 1 | pack.md; parity.md 'Pack join' | client optional |
| Live portal previews (companion) | custom-dimensions client | Delivered (optional) | delivered | 1 | portals.md § Previews; HANDOFF portal round | client optional |
