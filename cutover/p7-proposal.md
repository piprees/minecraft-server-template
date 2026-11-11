# P7 — Gameplay settings for 26.3: what the owner chooses

Each item: what 1.21.1 does today, what 26.3 offers, the recommendation, and the exact config it becomes. Sources are named per item; nothing here is a guessed key.

## 1. Game rules — one place

**1.21.1 today.** Two places that must match. `config/boring_default_game_rules/config.json` seeds new worlds with `doInsomnia false`, `playersSleepingPercentage 50`, `spawnRadius 0`, `forgiveDeadPlayers true`, `universalAnger false`, `mobGriefing true`, `playersNetherPortalDefaultDelay 0`. `restore_game_rules` in `scripts/deploy.sh` (lines 234–247) sets those seven plus `doMobSpawning true`, `randomTickSpeed 3`, `doDaylightCycle true`, `doWeatherCycle true` and `doFireTick true` on every deploy. Step 12's quiet boot (lines 416–420) turns the first five off until step 14.

**26.3.** boring-default-game-rules has no 26.x build (`mod-register.csv`: "replaced … deploy.sh already enforces every game rule over RCON"); the cutover deletes its config and seed `COPY` line. `mc_game_rule` (`scripts/lib.sh`) maps each 1.21.1 name to its 26.3 id from `scripts/game-rules.tsv`, which carries all twelve plus `logAdminCommands`, taken from the 26.3 server jar's registry report. `doFireTick` is a radius on 26.3 (`fire_spread_radius_around_player`): `true` becomes 128, `false` becomes 0.

**What the kit does with them.** `mc_game_rules` in `scripts/lib.sh` is the one list: the twelve values and `logAdminCommands true`. `restore_game_rules` in `deploy.sh` and a bounded block in `dev-up.sh` (after the op grant) apply it, so a local world matches production; a new 26.3 world runs vanilla defaults only until that block has run.

**Options.** (a) Keep the twelve values as they are. (b) Also run the block from `dev-up.sh` after RCON answers, so local worlds match production. (c) Change values.

**Recommendation.** (a) + (b). The values are tuned for a small friends' server and nothing in 26.3 changes their reason. Add `logAdminCommands true` to `restore_game_rules` so the audit trail discord-sync relies on is enforced, not assumed.

**Becomes.**
- `scripts/deploy.sh` `restore_game_rules`: the twelve lines stay; add `rcon "gamerule $(mc_game_rule logAdminCommands true)"`.
- AGENTS.md § Config sync states one place (the cutover's AGENTS.md says it).
- Optional (b): `scripts/dev-up.sh` calls the same twelve over `docker exec … rcon-cli` once RCON answers; a shared function in `lib.sh` keeps one list.
- Per-dimension game rules are available (decisions.md RB-38): a definition's `gameRules`, or `settings.json`'s `defaults.gameRules` for every dimension, holds a dimension's own values over the server-wide store. The twelve stay server-wide in `mc_game_rules`, so `/gamerule` and the deploy keep one place for them; a dimension that needs another value (a trial with `keep_inventory`, a gallery with `mob_griefing` off) says so in its definition. `advance_time` alone is refused there (the clocks are server-wide; `atmosphere.fixedTime` or `atmosphere.dayLength` set a dimension's time). Check in the rehearsal: `./ops rcon "execute in adventure:the_aviary run gamerule spawn_phantoms"` answers the server-wide value unless the definition sets its own.

## 2. Elytra and armour together — elytra-armor

**1.21.1 today.** `elytra-slot` (a Trinkets slot) lets a player wear an elytra and a chestplate at once; it needs its client mod.

**26.3.** `elytra-armor:Oe6DS1BQ` in `config/modrinth-mods-26.3.txt` is Armored Elytra 1.16.0 (Modrinth project `AuFCCYMx`, version `Oe6DS1BQ`, game version 26.3). From its Modrinth page: a chestplate and an elytra combine in the anvil into an armoured elytra; the grindstone splits it back; all logic is server-side, the client mod only draws the combined icon and model. Its jar (`armored-elytra-1.16.0.jar`, read entry by entry) holds no config file and no config class: there is nothing to set.

**Options.** (a) Ship it as released. (b) Add the anvil recipe to the starter guide or the MOTD so players know it exists.

**Recommendation.** (a), with one line in `config/messages.json`'s welcome text (b). Behaviour differs from 1.21.1: the chestplate's enchantments and the elytra's durability now live on one item.

**Becomes.** No config. If (b): a sentence in the welcome message, e.g. "Combine a chestplate and an elytra in an anvil to wear both."

## 3. Totems from the inventory — inventory-totem

**1.21.1 today.** `charm-of-undying` gives the totem its own Trinkets slot; it needs its client.

**26.3.** `inventory-totem:w3KKAn4J` is Serilum's Inventory Totem 26.3.0-3.6 (project `yQj7xqEM`). Its Modrinth page: a totem of undying works from anywhere in the inventory, server-side; a client copy only changes the animation's texture. Its jar holds no config class (no `config/` package, no Collective config handler), so Collective writes no `config/serilum/inventorytotem.json` for it.

**Recommendation.** Ship as released.

**Becomes.** No config.

## 4. The starter kit, rebuilt with bundles

**1.21.1 today.** `config/starterkit/kits/Default.txt` gives damaged diamond armour, a diamond sword, an iron axe, an Infinity bow, a water bucket, 28 rockets, 21 baked potatoes, an elytra (slot 9), `supplementaries:quiver` (slot 10) with 16 arrows, `inmis:frayed_backpack` (slot 12), an oak boat, 17 coal and 15 torches in the off hand. `config/starterkit.json5` sets the options.

**26.3.** Supplementaries and Inmis are replaced (`mod-register.csv`: Inmis → "Vanilla bundles, native on Bedrock too"; Supplementaries → polydecorations and vanilla shelves), so slots 10 and 12 name items that do not exist and the kit loses them. Starter Kit 26.3.0-9.0 (`starterkit-26.3.0-9.0.jar`, `ConfigHandler` strings) reads `addExistingItemsAfterKitSet`, `chooseKitText`, `formatKitNames`, `giveStarterKitOnFirstJoin`, `giveStarterKitOnRespawn`, `lockKitChoiceScreen`, `randomKitWeights`, `randomizeMultipleKitsToggle` and `usePotionEffectsInStarterKit` from `config/serilum/starterkit.json` (T136); the kit files land at `config/serilum/starterkit/kits/` through Collective's move (cutover-removals.md).

**Options.** (a) One kit: replace the quiver and backpack with two bundles. (b) Several kits players choose from (`randomizeMultipleKitsToggle false`), e.g. explorer, builder, farmer, each packed in bundles. (c) Keep the kit lean and let structures (slashloot) do the rest.

**Recommendation.** (a) now, (b) after launch if players ask. A bundle is vanilla and Bedrock shows it natively.

**Becomes.** Slot 10 and slot 12 of `config/starterkit/kits/Default.txt`:

```
10 : '{count:1,id:"minecraft:brown_bundle",components:{"minecraft:bundle_contents":[{count:16,id:"minecraft:arrow"},{count:15,id:"minecraft:torch"}]}}',
12 : '{count:1,id:"minecraft:bundle"}',
```

and slot 11 (the loose arrows) emptied. `/starterkit set` on 26.3, from a kit built in game, writes the same two lines with their keys in another order (`not-automated.md` § Judgement calls), so the hand-written line is the game's. Everything else in the kit is vanilla and stays.

## 5. What cut mods used to provide

| Setting | 1.21.1 today | 26.3 | Recommendation | Becomes |
| --- | --- | --- | --- | --- |
| Longer days | Better Days: `speedMethod = "MINUTES"`, `daySpeedMinutes = 45.0`, `nightSpeedMinutes = 15.0`, random ticks and weather synced to the speed (`config/betterdays/betterdays-common.toml`) | `atmosphere.dayLength` (`dayMinutes`, `nightMinutes`) per definition, and `settings.json`'s `defaults.atmosphere.dayLength` for every dimension with vanilla's day: the compiler warps the day's timelines onto a world clock of that length (worldgen.md § Layers). Random ticks, weather and crop growth keep vanilla's pacing | The kit ships 45 and 15, Better Days' values; the owner confirms or names others | `mods/custom-dimensions-26/definitions/settings.json` `defaults.atmosphere.dayLength` (at the switch, `config/custom-dimensions/dimensions/settings.json`) |
| Seasons | Fabric Seasons, overworld only, 672,000-tick seasons (`config/seasons.json`) | Cut: no build since 2024 | Accept the loss; zones and per-dimension biomes carry the variety | Nothing |
| Configurable difficulty | Absorbed into custom-dimensions on 1.21.1 (`config/configurable-difficulty/` is unread) | Per-dimension `difficulty` (`hostileSpawning`, `mobMultiplier`, `playerLuck`, `attributes`, `depthScaling`) in each definition; defaults in `config/custom-dimensions/dimensions/settings.json` (`mobMultiplier 1.0`, health/damage/armour scaled) | Keep the defaults; tune per dimension during W8 | The definitions |
| Per-player loot | Lootr (`config/lootr/lootr.json`: `randomize_seed true`, elytras in End ships converted to item frames, mineshaft minecarts converted) | slashloot 0.5.2: per-player copies of every naturally generated container, server-side. Its `SlashLootConfig` (jar strings) reads `config/slashloot.json` with `enabled`, `dimensionBlocklist`, `lootTableBlocklist`, `handleUnknownContainers`, `delegateContainerAnimation`, `playOpenCloseSounds`, `cleanupOnBreak`, `pruneIntervalTicks`, `pruneBatchSize`, `debugLogging` | Ship its defaults (the cutover ships no `slashloot.json`). Lootr's End-ship elytra conversion has no counterpart: one elytra per ship, first come. Decide whether that is acceptable or whether `lootTableBlocklist` should leave `minecraft:chests/end_city_treasure` shared | None, or `config/slashloot.json` with the keys above and a seed `COPY` line |
| Sign markers on the map | bluemapsignmarkers | Cut | Accept | Nothing |

## Owner's choices, in order

1. Day length: keep 45 minutes of day and 15 of night (the kit's default), or name others.
2. Game rules: keep the twelve server-wide values (`logAdminCommands` and the local block are in the kit); then § 6, the per-dimension profiles.
3. Starter kit: one kit with bundles, or several.
4. End-ship elytra: first come, or shared.
5. A welcome line for armoured elytra.

## 6. Game rules per dimension (a proposal; nothing is set)

**1.21.1 today.** One set of game rules for every dimension: the twelve in § 1, server-wide. A dimension's character came from its definition's other fields (`difficulty`, `peaceful`, `environment.fixedTime`, exits).

**26.3.** A definition's `gameRules` gives its level values of its own over the server-wide store, and `settings.json`'s `defaults.gameRules` gives every dimension one (decisions.md RB-38; mod README § Game rules). Every 26.3 rule is accepted except `advance_time`: the clocks are one per day length, so `atmosphere.fixedTime` and `atmosphere.dayLength` set a dimension's time. The compile refuses an unknown rule, a value of the wrong type and one out of bounds. `immediate_respawn`, `limited_crafting` and `reduced_debug_info` are held by the client and sent per level; a dimension that sets `advance_weather` keeps weather of its own. No definition sets any rule yet.

The 59 rules of 26.3, with type and default (the 26.3 server jar: `reports/commands.json` for ids and bounds, `GameRules`' static initialiser for defaults; evidence `gamerule-defaults.tsv`). **Bold** is a rule the deploy holds server-wide (`mc_game_rules`: the twelve and `log_admin_commands`), with the value it sets:

| Rule | Type | Default |
| --- | --- | --- |
| `advance_time` | bool | **true** (refused per dimension) |
| `advance_weather` | bool | **true** |
| `allow_entering_nether_using_portals`, `block_drops`, `block_explosion_drop_decay`, `command_blocks_work`, `command_block_output`, `drowning_damage`, `elytra_movement_check`, `ender_pearls_vanish_on_death`, `entity_drops`, `fall_damage`, `fire_damage`, `freeze_damage`, `global_sound_events`, `locator_bar`, `mob_drops`, `mob_explosion_drop_decay`, `natural_health_regeneration`, `player_movement_check`, `projectiles_can_break_blocks`, `pvp`, `raids`, `send_command_feedback`, `show_advancement_messages`, `show_death_messages`, `spawner_blocks_work`, `spawn_monsters`, `spawn_patrols`, `spawn_wandering_traders`, `spawn_wardens`, `spectators_generate_chunks`, `spread_vines`, `tnt_explodes`, `water_source_conversion` | bool | true |
| `immediate_respawn`, `keep_inventory`, `lava_source_conversion`, `limited_crafting`, `reduced_debug_info`, `tnt_explosion_drop_decay` | bool | false |
| `fire_spread_radius_around_player` | int ≥ −1 | **128** (`doFireTick true`) |
| `forgive_dead_players` | bool | **true** |
| `log_admin_commands` | bool | **true** |
| `max_block_modifications` | int ≥ 1 | 32768 |
| `max_command_forks`, `max_command_sequence_length` | int ≥ 0 | 65536 |
| `max_entity_cramming` | int ≥ 0 | 24 |
| `max_minecart_speed` | int 1–1000 | 8 |
| `max_snow_accumulation_height` | int 0–8 | 1 |
| `mob_griefing` | bool | **true** |
| `players_nether_portal_creative_delay` | int ≥ 0 | 0 |
| `players_nether_portal_default_delay` | int ≥ 0 | **0** (default 80) |
| `players_sleeping_percentage` | int ≥ 0 | **50** (default 100) |
| `random_tick_speed` | int ≥ 0 | **3** |
| `respawn_radius` | int ≥ 0 | **0** (default 10) |
| `spawn_mobs` | bool | **true** |
| `spawn_phantoms` | bool | **false** (default true) |
| `universal_anger` | bool | **false** |

**What other fields already do.** A wish a field meets stays in that field, never doubled by a rule: peace (`difficulty.hostileSpawning: false` turns off the monster category and discards any monster that enters, so `spawn_monsters`, `spawn_patrols` and `spawn_phantoms` add nothing there); keeping or dropping on death (`deaths.inventory` and `deaths.experience` win over `keep_inventory`); still time (`atmosphere.fixedTime`); raids (`rules.raids`); "the deeper, the harder" (`difficulty.depthScaling`); "what the portal reaches it takes" (the portal's `aura` with `subsume`); falling or dying sends you on (`exits`). Sleeping is left alone per dimension: one day-length clock serves every dimension on it, so a per-dimension `players_sleeping_percentage` would skip the night for all of them (unmeasured; not proposed).

**Options.** (a) Set nothing per dimension; the twelve stay the only rules. (b) Five small profiles, below, each a handful of rules tied to what a dimension is for. (c) (b), and change `defaults.gameRules` for every dimension.

**Recommendation.** (b). Each profile is two or three rules, each rule has an id in the 26.3 report and RB-38 accepts it, and 55 dimensions stay on the server-wide set. Nothing in (c) has a reason yet.

| Profile | For | Rules | Why |
| --- | --- | --- | --- |
| Server-wide | A world whose description another field already carries | none | The twelve hold |
| Sanctuary | A peaceful showcase ("peaceful by decree", "too beautiful to ruin") | `fire_spread_radius_around_player: 0` | Lava and lightning do not burn the gardens down; monsters are already off (`hostileSpawning: false`) |
| Canvas | The building world | `fire_spread_radius_around_player: 0`, `advance_weather: false`, `spawn_wandering_traders: false` | A blank sheet stays blank: no fire, no rain on the build, "nothing else, forever" |
| Still sky | A fixed-time world whose picture is its sky ("always at midnight", "stars close enough to touch", "white silence") | `advance_weather: false` | The weather holds with the time. The level's own weather starts as a copy of the server's when first read; set it once with `execute in adventure:<slug> run weather clear` |
| Trial | A gauntlet that tests the player ("testing themselves", "nothing here waits for dark", "greed pays and costs") | `spawn_phantoms: true`, `forgive_dead_players: false` | Sleeplessness is punished here as vanilla punishes it, and a neutral mob angered stays angry after a death |
| Untouched | A world nobody settled ("nobody settles here", "nothing was ever built", "the animals have always had it") | `spawn_patrols: false`, `spawn_wandering_traders: false` | No pillager patrol or trader walks in from a civilisation the description says is absent |

Per dimension (82; every row a proposal):

| Dimension | Profile | Reason |
| --- | --- | --- |
| `overworld` | Server-wide | "Where it all begins": the reference world |
| `the_abyssal_shrine` | Server-wide | Guardians and drowned hollows are structures and spawns |
| `the_amplified_reaches` | Server-wide | "The deeper valleys hide what they left behind": `depthScaling` |
| `the_ashgrove` | Untouched | "Abandoned camps and scorched tombs are all that remain" |
| `the_aviary` | Server-wide | "It just grew": nothing a rule changes |
| `the_basalt_spires` | Server-wide | "Never a sky over it": `rules.ultraWarm` and the terrain |
| `the_blackstone_keep` | Server-wide | "The deeper you dig, the harder it fights back": `depthScaling` |
| `the_blighted_maw` | Server-wide | "Fall off and the blight rejects you": `exits.void`; "a build beside the portal": aura `everything` |
| `the_bloodroot_wastes` | Server-wide | Survivors' forges: structures |
| `the_blossom_gardens` | Sanctuary | "Peaceful by decree": `hostileSpawning: false`; fire spread off keeps the petals |
| `the_boneyard` | Server-wide | "The sculk that crept in": aura `everything` |
| `the_buried_age` | Server-wide | "It takes through the portal too": aura `everything` |
| `the_burning_archipelago` | Server-wide | "Still smoulder": `rules.ultraWarm` and fire spread as server-wide |
| `the_canvas` | Canvas | "A flat blank canvas for building … nothing else, forever" |
| `the_catalyst_maw` | Server-wide | "What it reaches near the portal it takes": aura; death sends you on: `exits.death` |
| `the_chalk_meadows` | Server-wide | "Villager country": ordinary life |
| `the_claymarsh` | Untouched | "Nobody settles here" |
| `the_crimson_nexus` | Server-wide | "What the portal reaches it converts": aura; anchors work: `rules` |
| `the_crucible` | Trial | "A compressed jungle gauntlet … terrain that fights back" |
| `the_crumbling_reaches` | Server-wide | "Only wreckage tells the story": structures |
| `the_crystal_vale` | Sanctuary, Still sky | "Too beautiful to ruin": `hostileSpawning: false`, fixed night 18000; rain would hide it |
| `the_darkpine_depths` | Still sky | "Moonlit spruce dark": fixed 18000 |
| `the_dripping_pines` | Untouched | "Passed through but never stayed". "Rain-soaked" asks for rain no rule can force: hold it with `advance_weather: false` after `weather rain` there (open question 3) |
| `the_dustbowl` | Untouched | "The wind owns everything else" |
| `the_ember_fields` | Server-wide | "Farmers and traders, not soldiers": beds work (`rules.bedsWork`) |
| `the_emberglass_foundry` | Server-wide | "A proving ground": structures |
| `the_end` | Server-wide | Vanilla's End |
| `the_end_citadel` | Server-wide | "Build beside the portal and the citadel absorbs it": aura; `exits.death` |
| `the_endless_expanse` | Server-wide | "The scale is the point": terrain |
| `the_forged_depths` | Server-wide | "Hotter and harder" downward: `depthScaling`; "anything built beside the portal is fuel": aura |
| `the_fractured_halls` | Server-wide | "What stands beside the portal becomes part of the wall": aura |
| `the_frozen_hearth` | Server-wide | Villages in the cold: ordinary life |
| `the_frozen_strait` | Server-wide | "Freezes into the sheet": aura |
| `the_fungal_lanterns` | Server-wide | "The lava here does not kill you": `exits` on lava and fire deaths |
| `the_furnace_halls` | Server-wide | "Brick and fire all the way down": structures |
| `the_gauntlet` | Trial | "The humans who built here were testing themselves"; "nothing here waits for dark" |
| `the_gilded_pit` | Trial | "Greed pays and costs" |
| `the_glacial_drift` | Untouched | "Nothing was ever built; nothing needs to be"; falling: `exits.void` |
| `the_greenreach` | Server-wide | "A place to live, not to fight" |
| `the_greywoods` | Server-wide | The witches' territory: structures and spawns |
| `the_gritlands` | Untouched | "Nobody could live here. Nothing organised survives"; beds fail: `rules.bedsWork` |
| `the_highland_crossing` | Server-wide | A trade route: ordinary life |
| `the_icebound_rift` | Server-wide | A void of memory: `exits.void`, `rules` |
| `the_lantern_pools` | Untouched | "Nobody built a town". "Glowing at dusk" is a `fixedTime` choice the card leaves to the owner |
| `the_lost_outpost` | Server-wide | "The soldiers … became the threat": patrols stay on |
| `the_luminous_caverns` | Sanctuary | "No armies ever found this place": `hostileSpawning: false` |
| `the_miredeep` | Untouched | "Too quiet for company" |
| `the_molten_flats` | Server-wide | "The machines don't care": structures |
| `the_needlefall` | Server-wide | "Punishes a careless step": terrain and `depthScaling` |
| `the_nether` | Server-wide | Vanilla's Nether |
| `the_obsidian_sanctum` | Server-wide | "The sanctum is the point": structures |
| `the_overgrowth` | Server-wide | "The jungle doesn't negotiate": terrain |
| `the_pale_reach` | Still sky, Untouched | "White silence", fixed noon; "even the animal kingdoms thin out" |
| `the_pillared_void` | Server-wide | "They rise through the frame": aura; `exits` |
| `the_red_monument` | Still sky | Fixed night over the void; "nothing here hunts you": `hostileSpawning: false`; "it declines them": `exits.void` |
| `the_roothold` | Server-wide | "The roots go a long way down": `depthScaling` |
| `the_rosebluff` | Server-wide | "Built for beauty": ordinary life |
| `the_ruined_timberland` | Untouched | "No living towns" |
| `the_scorched_mesa` | Server-wide | "The living moved on": structures |
| `the_sculked_beyond` | Server-wide | "What stands near the frame is what it eats next": aura; `exits.death` |
| `the_shallows` | Server-wide | Wrecks under clear water: structures |
| `the_shattered_skies` | Untouched | "The animals' paradise, where nothing was ever built to last" |
| `the_slatemouth` | Server-wide | "Puts it down in the capital": `exits.void` |
| `the_snowbound_isle` | Untouched | "Animal country" |
| `the_souldrift` | Still sky | "Drifting into shadow", fixed dusk 13000; the drift takes builds: aura |
| `the_soulfields` | Still sky | "Always at midnight"; "nothing living is welcome" (open question 4) |
| `the_starwell` | Still sky | "The stars are close enough to touch". "Nothing hunts" is not met: `mobMultiplier` 0.5 leaves monsters on; `difficulty.hostileSpawning: false` meets it (open question 5) |
| `the_stonemantle` | Server-wide | "The deeper shafts are harder": `depthScaling` |
| `the_sun_kingdoms` | Server-wide | "The bandits at its edges are the only threat": patrols stay on |
| `the_sunken_temple` | Server-wide | Drowned temples: structures |
| `the_teal_corruption` | Server-wide | "The tide comes through the portal": aura |
| `the_tidepools` | Sanctuary, Untouched | "No civilisation ever claimed this; the animals have always had it": `hostileSpawning: false` |
| `the_tiled_expanse` | Server-wide | "Geometric silence": `exits`, `rules.natural` |
| `the_twisted_groves` | Server-wide | "Dry to the bedrock": terrain and liquids |
| `the_underdark` | Server-wide | The deep dark: `depthScaling`, aura |
| `the_verdant_hollow` | Server-wide | "Pastoral, generous": ordinary life |
| `the_violet_spire` | Server-wide | "Arcane but not hostile": aura `natural` |
| `the_wailing_narrows` | Server-wide | "The deeper you go, the worse it gets": `depthScaling`; soul sand takes: aura |
| `the_weeping_vault` | Server-wide | "Crying obsidian creeps out through the frame": aura |
| `the_whispering_wilds` | Untouched | "The forest was here before anyone arrived" |
| `the_whitestone_ford` | Server-wide | Trade routes converged: ordinary life |
| `the_wuthering_wisteria` | Sanctuary, Still sky | "Someone sealed this place deliberately": `hostileSpawning: false`, fixed 12000 |

Counts: Server-wide 55, Untouched 14, Still sky 8, Sanctuary 5, Trial 3, Canvas 1; four dimensions carry two profiles (`the_crystal_vale`, `the_pale_reach`, `the_tidepools`, `the_wuthering_wisteria`).

**Becomes.** For each dimension the owner accepts, its profile's rules in the definition's `gameRules`, for example `"gameRules": {"spawn_patrols": false, "spawn_wandering_traders": false}`; for `the_starwell`, `"hostileSpawning": false` in its `difficulty`. A server proves each: `./ops rcon "execute in adventure:<slug> run gamerule <rule>"` answers the dimension's value and `customdim info <slug>` lists it.

**Open questions for the owner.**
1. Profiles at all, or the server-wide twelve only (option a)?
2. Trial: should a death in a trial keep the inventory (`deaths.inventory: keep`), or drop it as vanilla does? `the_gauntlet`'s `exits.death` respawns at the origin, so a drop leaves the loot inside the gauntlet.
3. `the_dripping_pines`: hold rain for ever (`advance_weather: false` after `weather rain` in that dimension), or let its weather run?
4. `the_soulfields`: "nothing living is welcome" — `spawn_mobs: false` stops natural spawning of every mob, monsters included, leaving only spawners; is that the memorial's silence, or too empty?
5. `the_starwell`: "a pocket of void where nothing hunts" — turn monsters off (`difficulty.hostileSpawning: false`)?
6. `the_canvas`: should a builder also be spared falls (`fall_damage: false`) or TNT (`tnt_explodes: false`)?
