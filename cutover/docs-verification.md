# Docs verification

What was checked for every file in `files/` and for `checklist.md`, `p7-proposal.md`, `p8-pregen.md` and `p9-archive.md`, and how.

## Paths

Every markdown link and every backticked repository path in the replacement docs was resolved against the rehearsal tree (a clone of the branch after `apply.sh`, `/tmp/claude-0/p10a/rehearsal`), by script (`docs-check.txt` in the evidence):

| File | Paths checked | Missing |
| --- | ---: | --- |
| `AGENTS.md` | 42 | none |
| `mods/AGENTS.md` | 14 | none (`custom-dimensions-client-26/scripts/rig.sh` is relative to `mods/`) |
| `custom-dimension-authoring/SKILL.md` | 11 | none |
| `worldgen-tuning/SKILL.md` | 9 | none |
| `dimension-lifecycle-operations/SKILL.md` | 3 | none |
| `fabric-mod-development/SKILL.md` | 11 | none |
| `checklist.md` | 6 | none after the fix (`cutover/apply.sh` lives outside the repo) |
| `p7-proposal.md` | 9 | the ten "today" paths it quotes exist before the switch (read from the branch) and are deleted by it; `config/slashloot.json` is a proposal |

## Fields, commands and flags (grepped in the branch)

- Definition fields: the top-level `properties` of `mods/custom-dimensions-26/schema/definition.schema.json` and each one's sub-properties, printed by script; `roll.family` is `false` in the schema, so the skill leaves it out. `structures.density` enum: `dense`, `normal`, `sparse`, `none`. `terrain.layout.type`: `radial`, `ring`, `linear`, `none`.
- Terrain library names and parameters: `docs/design/rebuild-26/worldgen.md` § Terrain library.
- `/customdim` subcommands (`list`, `info`, `tp`, `activate`, `unload`, `delete`, `create`, `audit`, `catalogue`, `render`, `roll`, `pick`): the mod README's Commands table; `survey`: `server/.../command/SurveyCommand.java` (`literal("survey")`, `patches`, `size`).
- Self-test sections: every `-PselfTestOnly=` value in the mod README (`biomes commands exits firewall materials mixins render roll rollperf structures terrain zones`). `-PselfTestPersist`, `-Pdefinitions`, `-Pbridges`, `-PterrainGallery`, `-PmeasureStructures`: `server/build.gradle` lines 80–108.
- Mixin config: `server/src/main/resources/customdimensions.mixins.json` (10 mixins, `defaultRequire: 1`, `JAVA_25`).
- Jar name: `server/build/libs/customdimensions-0.1.0.jar`; `local-mods.sh` passes `-Pmod_version` (line 118) and `verify` checks classes and refmap (lines 136–152).
- Workflows: `mod26.yml`, `mod26-canary.yml`, `smoke-test-26.yml`, `release.yml` (calls `smoke-test.yml`), `dep-review.yml` (`join26`).
- Scripts and ops: `./ops backup|chunky|doctor|map|rcon|reset-seed|ssh|stats` in `examples/consumer/commands.json`; `idle-tasks.sh` markers `.chunky-<id>-complete`, `.dhpregen-<id>-complete`; the `.skip-pause` aggregate and owner files in `deploy.sh` lines 110–121; `reset-seed.sh`'s cold tar under `backups/`; `./dev reset-world` in `examples/consumer/dev`.
- P7 keys: Starter Kit 26.3.0-9.0 `ConfigHandler` strings; slashloot 0.5.2 `SlashLootConfig` strings and its `slashloot.json` file name; Armored Elytra 1.16.0 and Inventory Totem 26.3.0-3.6 jars listed entry by entry (no config); behaviour from each project's Modrinth page (`api.modrinth.com/v2/project/<id>`). Jars from the pins' version ids in `config/modrinth-mods-26.3.txt`.
- Game rules: `scripts/deploy.sh` lines 234–247 and 416–420, `scripts/game-rules.tsv`, `config/boring_default_game_rules/config.json`.

## Behaviour (run, not read)

- `apply.sh` on a fresh clone: exit 0; a second `--dry-run` reports only "already done" (161 lines). `--dry-run` on the working branch: `dry-run.txt`.
- After it: `./scripts/test-scripts.sh --quick` (Docker replaced by a failing shim, ShellCheck from `/tmp/claude-0/sc`) reports the same warnings as before the switch except the unit tests; `pytest scripts/tests mods/custom-dimensions-client-26/scripts`: 481 pass, 33 fail, every failure in `not-automated.md` § Blocking.
- Not run (the brief forbids it): the Gradle build and self-test of the switched tree, the smoke test, the rig.

## Not verified

- The 26.3 SNBT of the bundle lines in `p7-proposal.md` (verify with `/starterkit set` in game).
- Whether Chunky's tickets keep a lazy level open (`p8-pregen.md` says to confirm in the rehearsal).
- Whether lazy levels follow the overworld's game rules (`p7-proposal.md` § 1).
