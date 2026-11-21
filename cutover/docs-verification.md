# Docs verification

What was checked for every file in `files/` and for the kit's own notes, and how. The claim table (file, line, claim, what it was checked against, verdict) is `claims.tsv` in the evidence (`/tmp/claude-0/d1/`).

## Paths

Every markdown link and every backticked repository path in the replacement docs was resolved against a rehearsal tree (a clone of the branch after `apply.sh`), by script (`paths.txt` in the evidence). A path the switch deletes, quoted as what 1.21.1 does, is read from the branch instead.

## Fields, commands and flags (read in the branch)

- Definition fields: the top-level `properties` of `mods/custom-dimensions-26/schema/definition.schema.json` and each one's sub-properties and enums, by script. `terrain.base`: fourteen library terrains, `roofed` the only one on `caves` and `nether`, `end` the only one on `end` (`TerrainLibrary`); `CLIMATE_ONLY` is `nether`, `caves`, `end`. `liquids.aquifers`: `on`, `off`, `dry`, `flooded`. `roll.family`, `spawnRadius`, `locateCap` and `allowEndgameNearSpawn` are `false` in the schema. `crust` is a parameter of `continents` and `islands`. `atmosphere.dayLength` is 1–600 minutes each (`Atmosphere.DayLength`), 45 and 15 in `settings.json`'s defaults.
- `/customdim` subcommands: every `literal(…)` attached in `server/.../command/` (`list`, `info`, `tp`, `activate`, `unload`, `delete`, `create`, `audit`, `catalogue`, `render`, `roll`, `pick`, `survey`, and `e2e-state` and `use` under `-Dcustomdimensions.e2e=true`); `survey` defaults and limits from `SurveyPlan` (24 patches of 3, at most 96 of 8).
- Self-test sections: `SelfTest.java`'s `-Dcustomdimensions.selftest.only` values (`exits`, `commands`, `materials`, `biomes`, `render`, `firewall`, `terrain`, `zones`, `structures`, `roll`, `rollperf`, `generators`, `difficulty`, `survey`, `seeds`, `gamerules`, `mixins`). Gradle properties: every `hasProperty`/`findProperty` in the mod's and the companion's `build.gradle` files.
- Mixins: `server/src/main/resources/customdimensions.mixins.json`, 17 entries, `defaultRequire: 1`, `JAVA_25`; each named by `MixinSelfTest`, `LevelSeedSelfTest`, `StructureSelfTest` or `ZoneSelfTest`.
- Jar name `server/build/libs/customdimensions-<version>.jar` (`archivesName`); `local-mods.sh` passes `-Pmod_version` and `verify` gates classes and the refmap column.
- The consumer overlay route: `files/scripts/stage-dimensions.py`'s header and its forms.
- Game rules: `mc_game_rules` in `files/scripts/lib.sh` (twelve and `logAdminCommands`), `restore_game_rules` in `files/scripts/deploy.sh`, the block in `files/scripts/dev-up.sh`, `scripts/game-rules.tsv`; per dimension, `GameRuleValues` (`advance_time` alone refused) and RB-38. The 26.3 rule ids and bounds from the 26.3 server jar's `reports/commands.json`, defaults from its `GameRules` static initialiser (`gamerule-defaults.tsv`).
- T, D, K and RB ids cited in the docs against `TROUBLESHOOTING.md` and `decisions.md` (RB-01 to RB-38, each once).

## Behaviour (run, not read)

- `apply.sh --dry-run` on a clean clone of the branch: no `!` line.
- `apply.sh` on a clean clone, run from the clone's own `cutover/`: exit 0, `cutover/` gone afterwards; the rehearsal tree for the path check.

## Measured in a server (V2's results)

- The starter kit's bundle lines: `/starterkit set` on 26.3 writes P7's line with its keys in another order (`not-automated.md` § Judgement calls).
- Chunky and lazy levels: an unload waits on Chunky's tickets and fails after 2,400 ticks; Chunky completes and the level closes after (`p8-pregen.md`).
- Game rules on lazy levels: one server-wide store, which a dimension's own `gameRules` overrides (`migration-notes.md` step 13).

## Not verified

Each needs a server or a client:

1. Every `-PselfTestOnly` value runs its section alone (`generators`, `difficulty`, `survey`, `seeds` and `gamerules` are read from the code, not run).
2. A compiled pack holds the day-length files the mod README names (`world_clock/day_length/<day>_<night>.json`, `timeline/day_length/<timeline>_<day>_<night>.json`, in ticks) and the dimension type's `default_clock`.
3. `customdim survey <slug> 1 1` writes `dossier.json` and `dossier.md` with every key placeness.md lists; `e2e-state` and `use` answer only under `-Dcustomdimensions.e2e=true`.
4. Each P7 § 6 profile on a scratch definition: `execute in <dim> run gamerule <rule>` answers its value; `advance_weather false` holds that level's weather after `weather clear` there while the overworld's runs; `fire_spread_radius_around_player 0` stops fire spreading there; `spawn_patrols false` and `spawn_wandering_traders false` stop both there.
5. Sleeping with one day length shared by several dimensions: whether sleeping in one skips the night in all (P7 § 6 proposes no sleeping rule until this is known).
6. The three client-held rules on a real client (`scripts/e2e/README.md` § On 26.x).
7. D6 (c2me's density-function compiler) and D9 (`level.dat` growth) on 26.3 (`troubleshooting-retire.md`).
8. The rig's Iris and Distant Horizons variants (`mods/custom-dimensions-client-26/README.md` § Compatibility).
