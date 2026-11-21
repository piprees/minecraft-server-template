# Parity audit: the 26.3 rebuild against the 1.21.1 pack

14 November 2026, branch head abcb99b9. Read-only audit; the evidence is in `inventory.md` (92 features), `requirements-check.md`, `gaps.md` and `questions.md` beside this file.

## How far along

**Method.** Every feature a player or admin got from the 1.21.1 pack (mods in `config/modrinth-mods.txt`, the register's `gave_us`, the 1.21.1 custom-dimensions README) is one row, weighted 3 signature, 2 notable or 1 minor, and scored delivered 1, mostly 0.75, partial 0.5, a start 0.25 or missing 0. "Waiting for the author" and "rebuilt by us, not started" both score 0. The one feature the owner dropped (fake-block portal previews, RB-03) is left out. An area's percentage is its weighted score over its weighted total (`/tmp/claude-0/a1/inv.py`).

| Area | Done | Features |
| --- | ---: | ---: |
| Dimensions and portals (the mod itself) | 94% | 14 |
| Clients and joining (vanilla, Bedrock, pack) | 88% | 4 |
| Worldgen (terrain, biomes, Nether, End, sky, the 82 dimensions) | 72% | 10 |
| Admin and community | 64% | 8 |
| Quality of life and gameplay | 60% | 9 |
| Farming and food | 44% | 6 |
| Items, gear and loot | 39% | 11 |
| Building and decoration | 27% | 9 |
| Mobs and creatures | 25% | 7 |
| **Structures** | **23%** | 9 |
| Villagers and trading | 17% | 4 |
| **Overall** | **55%** | 91 |

The "80%" measured the plan's launch scope, which puts blocks, items and creatures after launch and counts waiting mods as arriving. Against the owner's bar it is about 55% overall: the engine (dimensions, portals, worldgen kit, joining) is largely built, while structures and content are a quarter to a third done. By remaining effort it is about 74 packages (gaps.md), 67 if YUNG's and When Dungeons Arise are waited for rather than rebuilt.

## The ten biggest gaps

1. **Structures of our own: none.** No set pieces, no structure kit; the 74 structure ids from rebuilt worldgen mods (Nether city, ruins, sky and underground dungeons) all sit on stand-ins (id-mapping.csv). Set-piece kit 2 packages, then about 10 of content.
2. **YUNG's twelve and When Dungeons Arise** have no 26.3 builds: vanilla's own dungeons, mineshafts, strongholds, fortresses and monuments only, and no mega-dungeons.
3. **`/locate`, maps and eyes of ender find nothing our placements place**, and structures float in the void on sky worlds (K13, running).
4. **31 of 82 dimensions** (Batches 4–6) not re-authored; **no dimension** has had its places pass or a rolled-and-picked seed.
5. **No custom blocks or items** (C1 not started): the woods and stones of the cut biome mods, furniture breadth, Chipped, roofs.
6. **No creatures** beyond vanilla and clucking-ducks: Critters, the worldgen mods' mobs, biome creepers, guards.
7. **Exploration loot and gear**: Artifacts, armour sets, compasses (C2 not started).
8. **Biome breadth** about a third of 1.21.1's (82 of ours + 67 vanilla against roughly 350 modded).
9. **Villagers**: new professions and bounty boards not built.
10. **Discord chat bridge** (dcintegration has no 26.3 build) and **seasons** (cut, not yet put to the owner).

Available today and not yet taken: Friends & Foes and its Polymer patch, Explorify, DeCubed Dungeons (formerly Dungeons+), Repurposed Structures, Trek, Hopo Better Mineshaft, Server-Side Waystones, Sit!, Ender Dragon Destroys End Island and Chunky Border all have 26.3 builds that run without a client mod (Modrinth, 14 November). The register's build check dates from 27 September.

## What is solid

- The mod: 82 definitions compile at start-up; lazy levels; every portal shape, frame and orientation (79 of 79 bays); auras; difficulty, deaths, exits, wins; per-dimension seeds and game rules; 1,541 tests and a 279-PASS self-test.
- The worldgen kit: terrain library, roofed and End families, zones, places, palettes, ores, liquids, 82 library biomes; 51 dimensions at the terrain and biome bar.
- Structure control and the 10 kept structure mods (534 bridged entries); End cities and elytra (K12).
- Joining: unmodded Java in CI, Bedrock through Geyser and Floodgate, the optional pack; admin parity 16 of 24 rows passing, 4 open on the owner.
- Tools the 1.21.1 pack never had: rolling, survey, fast render, our own web map, Distant Horizons generation.

## Roadmap in one line each

- **Phase 0, now (3.5 packages):** K13; add the now-available mods with bridges; ship exit shrines; check the mob sweep.
- **Phase 1, structures (16, +7 if YUNG's and WDA are rebuilt):** set-piece kit, set pieces, lost structure families, then the places pass on all 82.
- **Phase 2, worldgen (11.5, beside phase 1):** Batches 4–6, more library biomes, the End's outer islands, seeds rolled and picked for all 82.
- **Phase 3, content through Polymer (33):** C1 data pipeline, then woods and stones, decoration, items, food, villagers, creatures; the owner decides whether it blocks the switch.
- **Phase 4, platform (3.5):** chat bridge, full-stack rehearsal and cutover.
