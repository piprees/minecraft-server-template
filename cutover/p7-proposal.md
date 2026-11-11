# P7 — Gameplay settings for 26.3: what the owner chooses

Each item: what 1.21.1 does today, what 26.3 offers, the recommendation, and the exact config it becomes. Sources are named per item; nothing here is a guessed key.

## 1. Game rules — one place

**1.21.1 today.** Two places that must match. `config/boring_default_game_rules/config.json` seeds new worlds with `doInsomnia false`, `playersSleepingPercentage 50`, `spawnRadius 0`, `forgiveDeadPlayers true`, `universalAnger false`, `mobGriefing true`, `playersNetherPortalDefaultDelay 0`. `restore_game_rules` in `scripts/deploy.sh` (lines 234–247) sets those seven plus `doMobSpawning true`, `randomTickSpeed 3`, `doDaylightCycle true`, `doWeatherCycle true` and `doFireTick true` on every deploy. Step 12's quiet boot (lines 416–420) turns the first five off until step 14.

**26.3.** boring-default-game-rules has no 26.x build (`mod-register.csv`: "replaced … deploy.sh already enforces every game rule over RCON"); the cutover deletes its config and seed `COPY` line. `mc_game_rule` (`scripts/lib.sh`) maps each 1.21.1 name to its 26.3 id from `scripts/game-rules.tsv`, which carries all twelve plus `logAdminCommands`, taken from the 26.3 server jar's registry report. `doFireTick` is a radius on 26.3 (`fire_spread_radius_around_player`): `true` becomes 128, `false` becomes 0.

**Gaps the switch opens.**
- A new 26.3 world runs vanilla defaults (insomnia on, 100 % sleeping, spawn radius 10) until the first deploy finishes step 14. Production always deploys after a reset, so this is minutes; a local world under `./dev up` never runs the block at all.
- `logAdminCommands` is in the table but not in `restore_game_rules`; only `admin-parity-check.sh` sets it.

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

and slot 11 (the loose arrows) emptied. The component name and the coloured bundle ids are 1.21.4+ vanilla data; verify the line in the rehearsal with `/starterkit set` from a kit built in game, which writes the exact 26.3 text (the `consumer-customisation` skill's workflow), rather than trusting the hand-written one. Everything else in the kit is vanilla and stays.

## 5. What cut mods used to provide

| Setting | 1.21.1 today | 26.3 | Recommendation | Becomes |
| --- | --- | --- | --- | --- |
| Longer days | Better Days: `speedMethod = "MINUTES"`, `daySpeedMinutes = 45.0`, `nightSpeedMinutes = 15.0`, random ticks and weather synced to the speed (`config/betterdays/betterdays-common.toml`) | The register says "vanilla 26.x world clocks, set per dimension by our compiler", but schema v2 has no day-length field: `atmosphere` carries `skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects` (`schema/definition.schema.json`); the compiler writes a clock only for `fixedTime` (`FixedTime.java`) | Owner's choice: (a) vanilla 20-minute days at launch, or (b) a `atmosphere.dayLength` field (minutes of day, minutes of night) compiled to the dimension's clock before launch | (a) nothing; (b) a schema field, compiler work and a self-test line: a mod change, not config |
| Seasons | Fabric Seasons, overworld only, 672,000-tick seasons (`config/seasons.json`) | Cut: no build since 2024 | Accept the loss; zones and per-dimension biomes carry the variety | Nothing |
| Configurable difficulty | Absorbed into custom-dimensions on 1.21.1 (`config/configurable-difficulty/` is unread) | Per-dimension `difficulty` (`hostileSpawning`, `mobMultiplier`, `playerLuck`, `attributes`, `depthScaling`) in each definition; defaults in `config/custom-dimensions/dimensions/settings.json` (`mobMultiplier 1.0`, health/damage/armour scaled) | Keep the defaults; tune per dimension during W8 | The definitions |
| Per-player loot | Lootr (`config/lootr/lootr.json`: `randomize_seed true`, elytras in End ships converted to item frames, mineshaft minecarts converted) | slashloot 0.5.2: per-player copies of every naturally generated container, server-side. Its `SlashLootConfig` (jar strings) reads `config/slashloot.json` with `enabled`, `dimensionBlocklist`, `lootTableBlocklist`, `handleUnknownContainers`, `delegateContainerAnimation`, `playOpenCloseSounds`, `cleanupOnBreak`, `pruneIntervalTicks`, `pruneBatchSize`, `debugLogging` | Ship its defaults (the cutover ships no `slashloot.json`). Lootr's End-ship elytra conversion has no counterpart: one elytra per ship, first come. Decide whether that is acceptable or whether `lootTableBlocklist` should leave `minecraft:chests/end_city_treasure` shared | None, or `config/slashloot.json` with the keys above and a seed `COPY` line |
| Sign markers on the map | bluemapsignmarkers | Cut | Accept | Nothing |

## Owner's choices, in order

1. Day length: vanilla at launch, or a `dayLength` field before launch.
2. Game rules: keep the twelve values; add `logAdminCommands`; enforce them on local worlds too.
3. Starter kit: one kit with bundles, or several.
4. End-ship elytra: first come, or shared.
5. A welcome line for armoured elytra.
