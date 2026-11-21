# Gaps and how to close them, server-side

A package is the project's unit: about 180 agent steps. Sizes are estimates from comparable finished packages (a dimension batch of 11 took one lane package, W10c2/W10c2b/W10c2c; a kit feature such as K12 took one). Server-only route for each: kept or replacement mod (checked against Modrinth on 14 November), data in our compiler, our mod's runtime, or Polymer (blocks, items and entities a vanilla client sees as vanilla ones, textures through the server resource pack, Bedrock through Geyser mappings we generate).

## Structures (area at 23%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| `/locate`, maps, eyes of ender find nothing our placements place; structures floating in the void | Our mod (K13, running) | 1 (in flight) | — |
| Structure mods with 26.3 builds today: Explorify, DeCubed Dungeons (was Dungeons+), Repurposed Structures, Trek, Hopo Better Mineshaft, plus Friends & Foes' 4 structures | Mod list through the dependency checklist and join gate; a reviewed bridge each (`:core:bridge`), sizes measured (`-PmeasureStructures`), id-mapping stand-ins for explorify and dungeons_plus pointed back at the real ids | 1.5 | K13 for checking placements |
| Exit shrines never generate | Port `adventure:exit_shrine` (templates from `scripts/gen-exit-shrine.py`, pool, structure, set) into the compiler's output; `ExitShrines` already registers them | 0.25 | — |
| No structures of our own; the descriptions' stories have nothing to tell them (P1 counts 36 set-piece gaps so far) | **Set-piece kit**: declarative set pieces (rooms, walls, palettes per dimension, decay, loot tables, spawners, jigsaw pools) generated to `.nbt` templates and compiled with the definitions, placed by our structure control, shown in the survey and map | 2 | K13 |
| The set pieces themselves | Kit data, about six per package, owner art direction | 6 | Set-piece kit, P1 lists |
| Structures that came with the rebuilt worldgen mods (Nether city, End ruins and ships, sky dungeons, underground dungeons, ancient ruin sets, mage towers; 74 theme ids today on stand-ins) | Clean-room families in the kit, designed from descriptions, never from the originals | 4 | Set-piece kit |
| YUNG's twelve (better dungeons, mineshafts, strongholds, fortresses, monuments, temples, witch huts, bridges, extras): no 26.3 builds | Either wait (not delivered, by the owner's bar) or our own overhauls of vanilla's structures as kit families that replace vanilla's sets per dimension; Hopo's mineshaft covers one | 4 (if built) | Set-piece kit; owner decision |
| When Dungeons Arise and Seven Seas: no 26.3 builds | As above: wait, or our own mega-dungeon families | 3 (if built) | Set-piece kit; owner decision |
| Stoneholm, Sky Villages, Underground Bunkers, Simply Houses, Hellish Trials, Medieval End, Adventure Dungeons, Subsurface, True Ending: no 26.3 builds | dep-review adds each when it ships; the kit families above cover their roles meanwhile | 0 now | — |
| The places pass on all 82 dimensions (structures in every dimension, censuses, shots, reviews) | Definitions + survey; P1 supplies the per-dimension lists | 4 | All of the above that the owner wants at launch |

## Worldgen (area at 72%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Batches 4–6: 31 dimensions not re-authored at the bar | Lane packages as for Batches 1–3 | 3 | — |
| W10b: 13 gallery notes and 14 new library biomes | Already briefed | 1 | — |
| Biome breadth: 82 library biomes + 67 vanilla, against roughly 350 modded biomes in 1.21.1 | About 60 more library biomes (overworld 30, caves 10, Nether 8, End 8, sky 4), authored in a non-server lane | 5 | Owner's target number |
| The End's outer islands carry the variety (owner) | Definitions and End family | 0.5 | — |
| Rolled and picked seed, spawn, Bedrock height lint, contact sheet and walk-through for all 82 | `roll`, `pick`, `survey`, map | 2 | Batches 4–6, places pass |
| Biome blocks the cut mods brought (woods, stones, plants) | See Content: C1 and woods-and-stones | — | — |

## Mobs and creatures (area at 25%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Friends & Foes (mob-vote mobs) | Add with its Polymer patch (both 26.3 since 26 Oct and 4 Nov); join gate | in the 1.5 above | — |
| Creature pipeline | Polymer virtual entities: a vanilla entity base for AI and hitbox, display-entity models textured from the server resource pack, Geyser custom-entity mappings for Bedrock | 2 | C1 resource-pack pipeline |
| Critters (otters, ferrets, koi, red pandas, dragonflies…) | Pipeline, two creatures a package | 4 | Creature pipeline |
| Worldgen creatures (End, Nether, sky, Wilder Wild and Galosphere moods) | Pipeline | 4 | Creature pipeline |
| Biome creepers, armed guards | Variants of vanilla mobs (attributes, equipment, loot, spawn rules) where a new model is not needed; Polymer where it is | 1.5 | Creature pipeline for models |
| Mob sweep datapack unverified on 26.3 | Re-pack for format 121 and test | 0.1 | — |
| Per-dimension spawn tables (R3) | A `spawns` field compiled into the dimension's biomes' `natural_mob_spawns` or a runtime spawner rule | 0.5 | — |

## Items, gear and loot (area at 39%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Blocks and items from data | C1: Filament 1.8.12 (26.3) on Polymer, the server resource pack, a Geyser mapping generator, polydex recipes | 2 | — |
| Artefacts with passive powers | Polymer items with 26.x components; effects from our runtime (equipped or carried) | 2 | C1 |
| Themed armour sets with set bonuses | Equippable components, trims and models through the pack; set bonus from our runtime | 2 | C1 |
| Dungeon weapons | Polymer items, data-driven enchantment effects | 1 | C1 |
| Explorer's and Nature's compasses | Lodestone tracker + a 26.x dialog to choose the target | 1 | K13, C1 |
| Sleeping bag | Polymer item: sleep without setting spawn | 0.25 | C1 |
| Overstacked and bow enchantments, Borrow Their Arrows | dep-review when they ship, or data-driven enchantments of ours | 0.5 | — |
| Weapon animations (Better Combat) | None server-side: client animations | — | Owner accepts the loss |

## Building and decoration (area at 27%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Woods, stones and plants for our biomes | Polymer blocks through C1 | 3 | C1 |
| Block variants (Chipped's role), roofs, one-station woodcutting | Polymer blocks + stonecutter-style recipes | 2 | C1 |
| Jars, cages, wall lanterns and Supplementaries' other pieces | Polymer blocks with block entities where needed | 1 | C1 |
| Bridges | abridged when it ships (worldgen bridges); bridge blocks in the variants package | 0 | — |

## Farming and food (area at 44%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Wine chain, feeding trough, placeable food | Polymer blocks and items | 2 | C1 |
| Seasons | Our runtime: a season clock, crop growth and snow by season, biome temperature through 26.x environment attributes; colour tints stay client-side | 1.5 | Owner decision |

## Villagers and trading (area at 17%)

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| New professions | 26.x data-driven trades + Polymer job-site blocks | 1.5 | C1 |
| Bounty boards | A 26.x dialog on a Polymer block | 1 | C1 |
| Village guards | Under mobs (variants) | — | — |

## Quality of life, admin and community

| Gap | Server-side route | Size | Depends on |
| --- | --- | ---: | --- |
| Sit!, Ender Dragon Destroys End Island, Chunky Border, No Chat Reports, Krypton, Server-Side Waystones | Add now (26.3 builds; join gate) | 0.5 | — |
| Pause day cycle, ground clear | Wait, or a small rule in our mod | 0.25 | — |
| Chat bridge (dcintegration has no 26.3 build) | Wait, or a Discord⇄game bridge in discord-sync (log tail out, `tellraw` over RCON in) | 1 | Owner decision |
| `/mc status` memory | Read the container's memory in discord-sync | 0.1 | — |
| Far-side biome ambience at a portal | Our mod plays the destination biome's loop near a portal | 0.25 | Owner decision |
| Full-stack rehearsal, migrate-to-26, restore rehearsal on a 26.3 world | Already planned (HANDOFF Next, item 6) | 2 | — |

## Totals

| Phase | Packages |
| --- | ---: |
| 0 · Quick wins (K13, now-available mods and bridges, exit shrines, mob sweep, memory) | 3.5 |
| 1 · Structures (kit, set pieces, lost families, places pass) | 16 |
| 1b · YUNG's and WDA rebuilt instead of waiting (owner's choice) | 7 |
| 2 · Worldgen completion (batches, biome breadth, End, seeds) | 11.5 |
| 3 · Content through Polymer (C1, blocks, items, food, villagers, creatures, spawn tables, seasons) | 33 |
| 4 · Platform (chat bridge, rehearsal, pause and ground clear, portal ambience) | 3.5 |
| **All** | **about 74** (67 without phase 1b) |

## Roadmap

```
Phase 0 (now, parallel)      K13 ─┐   now-available mods + bridges ─┐   exit shrines   mob sweep   memory
                                  │                                 │
Phase 1 (structures)         set-piece kit ──► set pieces (6) ──► places pass on 82 (4)
                                  └──► lost families (4) ──┘        ▲
                                  └──► [owner] YUNG's/WDA families (7) ┘
Phase 2 (worldgen, beside 1) Batches 4–6 (3, server lane) ──► seeds rolled and picked for 82 (2)
                             W10b (1) ─► biome breadth (5, non-server lane) ─► End outer islands (0.5)
Phase 3 (content, beside 1–2) C1 data pipeline (2) ──► woods/stones (3), variants (3), items (6.75),
                                                    food (2), villagers (2.5), creature pipeline (2) ──► creatures (9.5)
                             [owner] seasons (1.5)
Phase 4 (platform)           [owner] chat bridge (1) ; rehearsal and cutover (2) last
```

- **Can run in parallel:** the server lane takes Batches 4–6 and then the places pass; worktree lanes take the set-piece kit, library biomes and C1 at the same time; set pieces, items and creatures fan out once their kit exists. Three lanes is the most this machine has carried (one server, two builds with `--max-workers=1`).
- **Serial constraints:** K13 before any structure package is measured; the set-piece kit before set pieces and families; C1 before every Polymer package; the places pass after the set pieces the owner wants at launch; seeds are picked last for each dimension (a structure change re-rolls).
- **Needs the owner first:** wait-or-build for YUNG's and WDA (phase 1b), whether content (phase 3) blocks the `v6.0.0` launch, the biome-breadth target, seasons, the chat bridge, portal ambience, and the cut mods to rebuild or let go (questions.md).
- **Calendar at three lanes:** phases 0–2 alone are about 31 packages, about 10 package-times with the server lane as the critical path (Batches 4–6, the places pass, seeds); phase 3 adds about 33 spread over two lanes.
