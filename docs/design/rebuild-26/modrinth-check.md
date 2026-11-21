# MR1: Modrinth check of the 26.3 list (2026-11-21)

Method: one bulk `GET /v2/versions?ids=` for all 108 pinned version ids (77 server entries in `config/modrinth-mods-26.3.txt`, 49 in `_clientMods` of `modpack/adventure-26.3.mrpack.json`), one `POST /v2/version_files/update` by sha1 (fabric, 26.3) plus one for the two datapacks, bulk `GET /v2/projects?ids=` for waiting rows and candidates, four `/v2/search` facet queries, and per-project version queries only to confirm a Fabric 26.3 build. About 40 requests in total, 1.2 s apart, no 429s. Every pinned version already targets 26.3. "Latest" means the newest 26.3 Fabric build of any channel: alpha and beta builds are marked.

## 1. Updates

### Server list (25 of 77 out of date; clucking-ducks, gm4-double-doors, floodgate and the other 49 are current)

| slug | pinned | latest 26.3 Fabric | date | worldgen/structure | highlights |
|---|---|---|---|---|---|
| chunky | 1.5.3 | 1.5.66 | 2026-11-04 | no | no changelog given |
| servercore | 1.5.20+26.3 | 1.5.21+26.3 | 2026-11-19 | no | adds `/mobcaps global` |
| towns-and-towers | 1.13.12 | 1.13.13 | 2026-10-27 | **structure** (next-major) | fixes duped armour stands, missing loot tables, a broken villager connector; drops chunk-wide grass spreading from village decorations (out-of-range worldgen writes) |
| fire-arrows-ignite-fire | fabric-26.3-13.4 | fabric-26.3-13.5 | 2026-10-31 | no | metadata only |
| deimos | 26.3-fabric-2.7.1 | 26.3-fabric-2.8 | 2026-10-30 | no | metadata only |
| carry-on | 2.12.0 | 2.12.2 | 2026-11-09 | no | NeoForge fix only |
| lithium | mc26.3-0.26.2-fabric | mc26.3-0.26.4-fabric | 2026-11-19 | no | OrderedTickQueue null entries; explosions not lighting fire |
| dungeons-and-taverns | 6.0.2+mod | 6.2.0+mod | 2026-11-18 | **structure** (next-major) | Toxic Lair: trial spawner fixed, mob gear tiers, slime miniboss gives keys, generation step size 6 to 9 |
| healing-campfire | 26.3.0-6.4-fabric+forge+neo | 26.3.0-7.1-fabric+forge+neo | 2026-10-30 | no | campfire detection moved into Collective (needs collective 8.87) |
| easy-elytra-takeoff | 26.3.0-4.6-fabric+forge+neo | 26.3.0-5.0-fabric+forge+neo | 2026-10-28 | no | smoother takeoff; modded-elytra compat |
| grabby-mobs | 26.3.0-1.8-fabric+forge+neo | 26.3.0-2.0-fabric+forge+neo | 2026-11-03 | no | witch item loss fixed; illagers no longer take armour; new `mobsThatCanGrab`, `mobsGrabWhenMobGriefingIsOff` keys |
| slashloot | 0.5.2+mc26.3-fabric | 0.6.0+mc26.3-fabric | 2026-11-10 | no | per-player decorated pots in trial chambers (`instancePots`); broken pot menu fixed |
| architectury-api | 22.0.5+fabric | 22.0.6+fabric | 2026-11-16 | no | one upstream port |
| polymer | 0.18.4+26.3 | 0.18.7+26.3 | 2026-11-03 | no | patch |
| puzzles-lib | 26.3.15 | 26.3.18 | 2026-11-12 | no | BlockEntityHelper added; datagen fixes |
| almanac | 1.26.10.2 | 1.26.11.1 | 2026-11-03 | no | bundles Dungeon Config 1.26.11.1 (client screens) |
| lmd | 1.26.10.2 | 1.26.11.1 | 2026-11-03 | no | crash with mods that persist mobs mid-creation; NPCs no longer dropped on save |
| c2me-fabric | 0.4.2-alpha.0.106+26.3 | 0.4.2-alpha.0.121+26.3 (alpha) | 2026-11-19 | **worldgen_slugs** (next-major) | arm64 syscall and Android fixes |
| farmers-delight-refabricated | 26.3-3.6.29 | 26.3-3.6.30 | 2026-10-24 | no | early tag load, instant cooking on reload, stove render |
| fallingtree | 26.3-26.3.0.4 | 26.3-26.3.0.5 | 2026-11-15 | no | translation key fix |
| villager-names-serilum | 26.3.0-8.9-fabric+forge+neo | 26.3.0-9.0-fabric+forge+neo | 2026-11-08 | no | custom names editable in config |
| collective | 26.3.0-8.64-fabric+forge+neo | 26.3.0-8.87-fabric+forge+neo | 2026-11-15 | no | item-destroyed event fix (Fabric) |
| simple-voice-chat | fabric-2.6.25+26.3 | fabric-2.6.27+26.3 (beta) | 2026-10-28 | no | no disconnect on invalid data; oversize-packet logging |
| fabric-api | 0.165.0+26.3 | 0.166.2+26.3 | 2026-11-11 | no | removed attachments reappearing after restart; ValueInput.contains fix |
| geyser | 2.12.0-b1270 | 2.12.1-b1306 (beta) | 2026-11-20 | no | custom block collision mirroring for Bedrock 26.60+ |

### Client pack only (12 of 49 out of date)

| slug | pinned | latest 26.3 Fabric | date | worldgen/structure | highlights |
|---|---|---|---|---|---|
| euphoria-patches | 1.10.5-r5.9.3-fabric | 1.11.2-r5.10-fabric | 2026-11-20 | no | Complementary r5.10 base |
| presence-footsteps | 1.14.0+26.3 | 1.14.1+26.3 | 2026-11-20 | no | see GitHub |
| particle-effects | 1.6.0+26.3+fabric | 1.7.0+26.3+fabric | 2026-11-20 | no | Item Particles compat |
| modmenu | 21.0.0 | 21.0.1 | 2026-11-12 | no | malformed config reset |
| entityculling | 1.11.3 | 1.12.1 | 2026-11-13 | no | culling API |
| entitytexturefeatures | 7.2.6-fabric-26.3 | 7.2.7-fabric-26.3 | 2026-11-03 | no | enchanted skin fix, memory |
| sodium | mc26.3-0.9.3-beta.1-fabric | mc26.3-0.9.3-beta.5-fabric (beta) | 2026-11-12 | no | bug fixes (beta.5) |
| gui-effect-timer | 26.3.0-1.3-fabric+forge+neo | 26.3.0-1.4-fabric+forge+neo | 2026-10-28 | no | timer fix |
| xaeros-minimap | fabric-26.3-26.6.1 | fabric-26.3-26.6.2 | 2026-10-25 | no | see author site |
| xaeros-world-map | fabric-26.3-1.47.1 | fabric-26.3-1.47.2 | 2026-10-25 | no | see author site |
| sound-physics-remastered | fabric-1.5.2+26.3 | fabric-1.5.4+26.3 (beta) | 2026-11-18 | no | no changelog given |
| creativecore | 2.14.24 | 2.14.25 | 2026-11-10 | no | GUI legitimacy checks (security) |

Notes:
- **Parity drift (T80):** simple-voice-chat is pinned at `fabric-2.6.25+26.3` on the server and `fabric-2.6.26+26.3` in `_clientMods`, and puzzles-lib at `26.3.15` on the server and `26.3.16` in the client pack. Both move to one build (2.6.27, 26.3.18) when they are bumped.
- healing-campfire 7.1 needs collective 8.87, so the two go up together.
- The 26.3 list's `=== structures ===` and `=== mobs & animals ===` headers are not in `policy.json` `never_automerge_sections`, which names the 1.21.1 headers (`structures (need yungs-api)`). partition.py only reads `config/modrinth-mods.txt` today, so nothing is misrouted yet. Once the 26.3 list is re-pinned the same way, only the jar diff (rule 5) would route towns-and-towers and dungeons-and-taverns to the next major.

## 2. Waiting rows that now have a 26.3 Fabric build

| slug | 26.3 build | date | note |
|---|---|---|---|
| dungeons+ | 1.13.1+mod | 2026-09-19 | **Renamed to `decubed-dungeons`** (same project, nHORcEHd). The slug `dungeons+` no longer resolves, so the list comment needs the new slug. CC-BY-NC-SA-4.0. Structure mod, so a major. |
| explorify | v1.6.6 | 2026-11-08 | Structure mod, so a major. Optional config through cristel-lib, which is already in the pack. |
| friends-and-foes + friends-and-foes-polymer | fabric-5.0.5+mc26.3 / 5.0.4-0+26.3 | 2026-11-16 / 11-04 | Both now exist, which is the register's condition. The patch requires polymer and friends-and-foes; F&F requires resourceful-lib and fabric-api, all in the pack. The patch is built against 5.0.4, so pin F&F 5.0.4 or check that it works with 5.0.5. F&F adds 4 structures, so a major. |
| ender-dragon-destroys-end-island | 2.2.2+mc26.3-fabric | 2026-10-04 | Server only. |
| no-chat-reports | Fabric-MC26.3-v2.21.3 | 2026-11-20 | Server and client optional (`_clientMods.required` waiting). |
| krypton | 0.3.2 | 2026-09-30 | Server perf. |
| sit! + otterlib | 1.2.6.4+26.3 / 0.4.0.2+26.3 (beta) | 2026-09-30 | otterlib's only 26.3 build is a beta. |
| client: betterf3 20.0.0, better-advancements 0.6.0.81 (beta), complete-shield-fixes 2.1.0+26.3, consumableoptimizer 2.2.3+26.3, cubes-without-borders 5.0.0+26.3, mindful-darkness 26.3.0, particle-rain v4.0.4+26.3, walksylib 2.0.5+26.3 | | Sep to Nov | Each goes into `_clientMods` at the version shown. |
| resource packs: better-leaves, fancy-crops | 26.3 listed | | `minecraft` loader. |

These are still on 26.2 or older: dcintegration, borrow-their-arrows, expanded-bow-enchanting, ground-clear, overstacked-enchantments, pause-day-cycle, modernfix, yungs-api and every YUNG structure mod (newest 26.1.2), adventure-dungeons, hellish-trials, medieval-buildings-end-edition, simply-houses, sky-villages, stoneholm (26.2), subsurface (26.2), true-ending, underground-bunkers, both when-dungeons-arise, abridged (26.2), and the client mods cave-dust, custom-splash-screen, dark-graph, dark-window-bar, satin-free-wakes, travelers-titles, simple-grass-flowers and bibliophilia.

## 3. New candidates (12)

Every candidate has a Fabric 26.3 release, and none adds a block, item or entity unless its row says so. The clean-room list was excluded from the searches by slug and author (Starmute and Apollo, which covers ibo, amplified-nether and continents), and nothing marked cut in the register is proposed.

| slug | title | downloads | updated | client / server | licence | adds | fit (RB-42 to RB-45) | risks | verdict |
|---|---|---|---|---|---|---|---|---|---|
| structure-layout-optimizer | Structure Layout Optimizer | 8.76M | 2026-11-14 (1.1.5+26.3) | unsupported / required | MIT | Faster jigsaw and NBT placement. Layouts stay the same unless `deduplicateShuffledTemplatePoolElementList` is turned on. | Neutral. Helps with K6-style first-generation stalls given how many structures the pack places. | **Requires resourceful-config, which the register marks cut** (a library). Leave the dedup option off. It runs on the generation path, so treat it as worldgen (major). | **dep-review** |
| wits | WITS | 2.12M | 2026-09-15 (1.4.3+26.3) | unsupported / required | MIT | The `/wits` command: structure ids at the player's position. | An admin aid for place reviews and the census (SP1/SP5). | None. Command only. | **dep-review** |
| cinchs-abandoned-houses | Cinch's Abandoned Houses | 24k | 2026-11-19 (2.1.0) | unsupported / required | MIT | Abandoned houses in varying states of ruin, with starter loot. | Strong fit for RB-44 (lived in, worn down, a little everywhere). Poor loot suits RB-39 near spawn. | Small and new. Biome coverage is partial. Needs our palette re-skin per dimension. | **dep-review** |
| cinchs-villager-statues | Cinch's Villager Statues | 6.6k | 2026-11-11 (3.8.0) | unsupported / required | MIT | 7 small statues built from biome blocks, with a hidden pot or suspicious block. | RB-43 (wordless) and RB-45 (local materials). | Small author. The statues could read as twee if dense, so keep them rare. | **dep-review** |
| lush-cave-ruins | Braytonk's Lush Cave Ruins | 8.6k | 2026-11-14 (2.3.2+mod) | optional / required | ARR | A buried, archaeology-led ruin in lush caves, with flooded drain pipes, sherd and trim loot, and a small mud-brick surface marker. | RB-42/44 (remnant with a surface tell). Matches SP4 `archaeology`. | ARR. A companion pack adds a custom mob, so leave it out. | **dep-review** |
| piglin-outposts | Piglin Outposts | 351 | 2026-11-18 (0.1.1) | unsupported / required | MIT | 3 Nether jigsaw camps (scout post, outpost, treasury) that scale with risk. | RB-39 (risk and reward tiers) and RB-44 (piglin lore). Structures only, so the Incendium rule is unaffected. | Very new (0.1.x, Fabric only). Check that its biome list matches our Nether generator. | **dep-review**, low priority |
| mmv-moogs-missing-villages | MMV - Moog's Missing Villages | 1.37M | 2026-09-21 (2.2.0) | unsupported / required | AGPL-3.0 | Vanilla-style jungle and swamp villages. | RB-44/45 (villages where vanilla has none). Uses moogs-structure-lib, which we already ship. | Its page says "incompatible with Terralith". Ours is rebuilt, but check its biome tags against our biomes. AGPL is fine for an unmodified jar. | **dep-review** |
| repurposed-structures-fabric | Repurposed Structures | 15.0M | 2026-11-20 (7.8.5+26.3) | unsupported / required | LGPL-3.0 | Biome variants of vanilla structures (fortresses, mineshafts, villages, wells and more). The author confirms there are no new blocks or mobs. | RB-45 (biome-matched variants) but against RB-42's restraint: a very large set. | Requires midnightlib (new library). Volume competes with our structure control. Many groups would be disabled through its config datapack. | **dep-review**, with most groups off |
| dungeons-and-taverns-ancient-city-overhaul | D&T Ancient City Overhaul | 4.65M | 2026-10-31 (4+mod) | optional / required | ARR | Replaces the ancient city. Not included in the main D&T mod. | RB-44 (the ancient builders), from the same author as our kept D&T. | Replaces a vanilla structure, so check it against our wants and overrides for `minecraft:ancient_city`. | **dep-review** |
| formations (+ formations-overworld, formations-nether) | Formations | 7.3M / 6.4M / 6.8M | 2026-09 | unsupported / required | ARR (modpack use allowed) | A structure-type library (`formations:simple` and `formations:pieced`) plus two content packs. | Small natural-scale finds fit RB-44, but the content is unseen. | Version names say `1.0.5b-mc1.21+`, so the jar's real target must be checked (DEPENDENCIES § Looking up versions). Its own placement types may sidestep our seating rules. | **inspiration only** (see §4) |
| biome-strongholds | Biome Strongholds | 31k | 2026-09-15 | optional / required | custom (NIT) | Strongholds re-laid and re-paletted per biome. | RB-45. | **Moves the End-portal frame arrangement**, a progression gate. Custom licence. | **inspiration only** |
| naturally-trimmed | Naturally Trimmed | 2.25M | 2026-09-27 | unsupported / required | LGPL-3.0 | Random armour trims on mob, loot and trade gear. | RB-43 wants trims as deliberate, wordless faction marks. Random trims dilute that. | It would undercut our own trim links. | **inspiration only** |

Also seen and skipped: trek (MIT, 12.8M, beta, 150 structures including floating islands; against RB-45 and the restraint), grim-kingdoms vanilla edition (ARR, about 100 forts and castles; grand ruins are meant to be rare), roguelikedungeons (generic box dungeons, not fitted to a setting), the-lost-cities (a modern ruined-city world, off-theme), reliable-replacer, sparsestructures, biome-dither and climate-rivers (each duplicates what custom-dimensions owns), and lost-sky-islands (ARR; for its idea see §4).

## 4. Inspiration for SP3 to SP5 (our own words)

1. **Seats for every surface** (SP3): beyond `ground`, `floor`, `roof`, `wall` and `water`, add a `lava` seat (a camp on a basalt raft at a lava lake's edge), a fully buried seat that touches no open air (pure archaeology), and floor and ceiling seats inside an open cave.
2. **One age dial, three effects** (SP3/SP4 `age`): swap materials to the local biome's equivalents, decay by step (full block to stair or slab to air, plain to mossy or cracked), and grow plants between a minimum and a maximum. Each band of `age` sets all three together.
3. **The tell above the dig** (SP4 `archaeology` plus RB-42 anchors and satellites): a buried ruin gives itself away only through a small, half-sunk surface marker of the same material as the ruin, a few blocks off-centre. A player who learns the material learns to read the ground.
4. **A faction's footprint by tier** (SP4 `camp` `state`, RB-39): a run of camps from a lone lookout to a garrison to a guarded store, placed further out and in harder ground as the tier rises. Banners and gear match within a run, and the store holds loot carried from somewhere a valley away.
5. **Gates that smell of where they lead** (SP4 `gate`, RB-40/41): beyond the `portal.aura` ground change, the study chest holds the destination's common debris (charred bone for a soul plain, dried fungus for a crimson wood). It is a clue made of objects, with no words.
6. **Remnant houses in states of ruin** (SP4 `hovel`, SP5): a single hovel definition at three integrity bands (roofless, one wall standing, footings only), weighted by distance from settlements. Ruin increases away from the living towns.
7. **Wayside figures** (SP3 `column` and `crown`): a small figure carved from the local stone, its face worn, with a pot or a suspicious block tucked behind it. Placed at forks and passes, it marks routes (RB-45, "reached by something").
8. **Trims as a bloodline** (SP4 `mob` equipment, RB-43): mobs that spawn within a ruin's ring wear that ruin's trim pattern and material, the same one shown on its banners. This is the deliberate version of what naturally-trimmed does at random.
9. **Partly flooded works** (SP3 `fall` and liquid ticks): drains and culverts in a buried ruin, some dry and some brimming, so water flows after generation and the ruin feels like it once had plumbing.
10. **Sky remnants** (SP5 sky worlds): small island clusters where only the footings of a building survive on the largest island, and the smaller islands carry rubble that seems to have fallen from it. They are seated on island ground only, never over the void.
