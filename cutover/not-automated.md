# What apply.sh does not do

Measured by running `apply.sh` on a clone of the branch (`/tmp/claude-0/p10a/rehearsal`) and then the quick gate and `pytest scripts/tests`: 33 unit tests fail after the switch, all in tooling written against the 1.21.1 list; the quick gate is otherwise the same as before it.

## Blocking: code ports that must land before the switch

| Item | Why it is not mechanical | Evidence |
| --- | --- | --- |
| `scripts/deploy.sh` § "Custom dimensions (from config/custom-dimensions/)" (the `customdim load` loop) and the reserved-world activation list | The loop calls `customdim load`, which the 26.3 mod does not register, reads `settings.json` from the folder root and `portal.scale` (v1), and treats `settings.json` in `dimensions/` as a dimension; every deploy would spend ~2 RCON calls on each of 82 lazy dimensions and write no markers. The activation list names `paradise_lost:paradise_lost`, not `adventure:the_aviary`. The 26.3 levels open lazily, so the loop goes; `scripts/tests/test_deploy_activation.py` (503 lines) tests both and is rewritten with them. `reset-seed.sh`'s `data/.dimension-setup/` lines go with it | `deploy.sh` lines 946–1066 and 906; no 26.3 test runs deploy.sh |
| `scripts/platform_updates.py` | Pins on the 1.21.1 mod (`mods/custom-dimensions/build-viewer-css.sh` Tailwind, `mods/*/gradle.properties` yarn and fabric-api) and treats the 26.x projects as out of scope; 9 tests in `test_platform_updates.py` and one in `test_minecraft_version.py` fail | `ScanTest`, `PlanApplyTest`, `TagShapeTest`, `StablePickTest` |
| `scripts/dep_review/` partition and collect, and their tests | `test_dep_review_partition.py` (9) reads the real list's 1.21.1 sections (`terrain & biomes`); `test_dep_review_collect.py` (6) and `test_dep_review_apply.py` holds (3) use fixtures pinned to 1.21.1 builds and flag them `not-target-mc` under 26.3 | `RealFilesTest`, `ModrinthFlags`, `HoldTests` |
| `scripts/tests/test_pack_files.py` (5) | Its fixtures are the 1.21.1 pack's downloaded resource-pack filenames; the folded 26.3 `options.txt` enables `Os' Colorful Grasses (Tall).zip` and `HEVI FreshAni Activator Fix 2.zip` | `ResourcePackTests`, `CliTests` |
| `.github/workflows/mod-updates.yml` | Regenerates jar-baked data of the 1.21.1 mod (`gen-structure-presets.py`, `gen-terrain-presets.py`, `extract-structure-sets.py`, `gen-structure-groups.py`) into `mods/custom-dimensions/src/main/resources` and builds with Java 21 | lines 150–235 |

## Judgement calls left for review

| Item | Why |
| --- | --- |
| `gen-structure-presets.py`, `gen-terrain-presets.py`, `gen-structure-groups.py`, `gen-structure-sizes.py`, `extract-structure-sets.py`, `extract-structure-sizes.py`, `extract-biomes.py`, `extract-blocks.py`, `extract-entities.py`, `extract-registries.py`, `gen-open-water-guard.py`, `gen-unmined-blocktags.py`, `gen-suppress-catalogue.py`, `gen-exit-shrine.py`, `scan-structure-placements.py`, `analyse-site-validity.py`, `force-toml-key.py`, and `config/custom-dimensions/extractors/`, `config/datapacks/open-water`, `config/datapack-presets/` | Each served the 1.21.1 worldgen model but has a caller that stays (`mod-updates.yml`, the bundle `MANIFEST`, `test-scripts.sh`, `mise.toml` tasks, the 26.3 mod's `CatalogueCommand` and `ExitShrines`). `extractors/` stays because `gen-unmined-blocktags.py` and `gen-open-water-guard.py` read it and the quick gate runs both. Retire with their callers in one review |
| `docs/mod-internals/` (six files) and `scripts/e2e/` 1.21.1 cases (`e2e-c1-render.sh`, `e2e-c3-portal-light.sh` name `custom-dimensions-client`) | Describe the 1.21.1 mod and companion; some lessons carry to the 26.3 companion |
| The compiler's `convertDefinitions` and `applyIdMapping` tasks | Their input (the 1.21.1 set) moves to `mods/custom-dimensions-26/definitions/v1/`; applyIdMapping is repointed, convertDefinitions still reads `config/custom-dimensions/` and has nothing to convert. A mod change |
| `.claude/skills/local-stack-testing`, `server-mod-management`, `fabric-mod-development/references/carpet-bot-harness.md`, `consumer-customisation`, `discord-integration-ops`, `production-triage` | Lines that name 1.21.1 paths (the seed viewer, `game_versions=1.21.1` recipes, `spark health`); listed in `docs-verification.md`, not rewritten |
| `DEPENDENCIES.md` beyond the lines `edits.py` changes | Its update-tooling sections describe `platform_updates.py` and `mod-updates.yml` as they are; rewrite them with those ports |
| `COMMANDS.md`, `CUSTOMISATION.md`, `docs/web-surfaces.md`, `modpack/README.md` § The 26.3 pack, `CHANGELOG.md` | Name `chunky border`, `spark health`, `versions/26.3/` or the 1.21.1 mod; CHANGELOG is history and stays |
| A consumer route for `overlay/config/custom-dimensions/` | The 26.3 mod reads `dimensions/` and `bridges/` only (cutover-removals.md says so for bridges); overlays are staged where nothing reads them |
| Required status checks | `mod-build.yml` ("Build & test in-house mods") is deleted; if branch protection requires it, swap in `Mod build`'s job |
| `farmers-delight-refabricated` parity exemption | Added to the 26.3 manifest's `_parityExempt` because the quick gate fails without it; it rests on farmers-delight-polymer serving vanilla clients, which the smoke test's vanilla join gate must keep proving |
