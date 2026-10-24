# Cutover removals

What the `v6.0.0` switch ([decisions.md](decisions.md), RB-05) deletes or moves, for P10. Everything here still serves 1.21.1 and stays until that release; `.minecraft-version` reads `1.21.1` until then.

## Configs and seed `COPY` lines of cut and replaced mods

Each path is a file or directory under `config/`; the `COPY` line is in `docker/defaults-seed/Dockerfile`.

| Path | `COPY` line | Mod | Register decision | Taken over by |
| --- | --- | --- | --- | --- |
| `betterdays/` | yes | betterdays | replaced | Vanilla 26.x world clocks, set per dimension by our compiler |
| `lootr/` | yes | lootr | replaced | slashloot (no config shipped) |
| `boring_default_game_rules/` | yes | boring-default-game-rules | replaced | The RCON game-rule block in `scripts/deploy.sh` (P7 moves the "two places that must match" rule in AGENTS.md to one) |
| `tectonic.json` | yes | tectonic | rebuilt by us | Our worldgen kit |
| `bountiful/` | yes | bountiful | rebuilt by us | Our mod |
| `seasons.json` | no (reaches `data/config/` through `platform_config_files`) | fabric-seasons | cut | Nothing |
| `configurable-difficulty/` | no (reaches `data/config/` through `platform_config_files`) | configurable-difficulty | cut | Per-dimension `difficulty` in custom-dimensions |
| `bluemapsignmarkers/` | no (reaches `data/config/` through `platform_config_files`) | bluemapsignmarkers | cut | Nothing |

Configs of `waiting` mods stay: `dcintegration/`, `groundclear/` and `groundclear.json`, `expanded_bow_enchanting/`, and the seven YUNG's `better*-fabric-1_21.toml` files with their `COPY` lines. Each returns with its mod; its 26.3 format is checked when the build appears.

## Kept configs with a 26.3 format difference

Method: every key in our file looked up as a string in the 26.3 jar the list pins and in the 1.21.1 jar; a key present in the 1.21.1 jar and absent from the 26.3 one is a difference. Evidence: `config-key-check.txt` in the P1/P2 evidence.

| File | Difference | Action at the switch |
| --- | --- | --- |
| `collective/collective.json5` and `collective.json5` | `loopsAmountUsedToGetAllEntityDrops` has no counterpart in Collective `26.3.0-8.64`, which generates entity drops from the loot table over time | `config/versions/26.3/collective/collective.json5` and `config/versions/26.3/collective.json5`, the same files without that key, replace the base files |
| Serilum configs: `collective.json5`, `doubledoors/`, `firespreadtweaks/`, `healingcampfire/`, `nametagtweaks/`, `nutritiousmilk/`, `youritemsaresafe/`, `starterkit.json5` | Collective `26.3.0-8.64` reads every Serilum mod's settings from `config/serilum/<modid>.json` | `config/versions/26.3/serilum/<modid>.json` replaces each base file; the base files and subdirectories go |
| `firespreadtweaks/firespreadtweaks.json5` | The jar reads `MinRandomExtraBurnTicks` and `MaxRandomExtraBurnTicks`; the base spells them with a lower-case first letter | The 26.3 overlay carries the jar's spelling |
| `healingcampfire/healingcampfire.json5` | `regenerationDuration`, `requireLitCampfire` and `enableSoulCampfireHealing` are not keys of healing-campfire `26.3.0-6.4`; its `effectDurationSeconds` (60), `campfireMustBeLit` (true) and `enableEffectForSoulCampfires` (true) default to what they set | The 26.3 overlay leaves them out |

`config/versions/26.3/` then goes.

On every load, Collective `26.3.0-8.64` moves `config/<modid>.json5`, `<modid>.json`, `<modid>-common.toml`, `<modid>.json.unreadable` and the `config/<modid>/` directory into `config/serilum/`, replacing what is there, then moves `config/serilum/<modid>.json5` onto `config/serilum/<modid>.json`. A flat base file therefore arrives at the read path with the overlay's values on every boot; a subdirectory base file lands unread at `config/serilum/<modid>/`, and starter-kit's `kits/` and `descriptions/` land at `config/serilum/starterkit/`, where it reads them.

No other kept config differs: fallingtree, essential-commands, servercore (both files), spark, simple-voice-chat, open-parties-and-claims and c2me.

## Version plumbing

- `.minecraft-version` reads `26.3`.
- `config/modrinth-mods-26.3.txt` becomes `config/modrinth-mods.txt`, replacing the 1.21.1 list; `config/modrinth-resolve-cache.json` is regenerated from it (`scripts/gen-resolve-cache.py`), or the seed calls the Modrinth API for every 26.3 pin at first boot.
- `modpack/adventure.mrpack.json` is rebuilt for the 26.3 client pack (P4) with its own `_holds`. `pin-mod-versions.sh --version 26.3` skips the client manifest and its holds until a 26.3 manifest exists.
- The 1.21.1 fallbacks that sit where the version file cannot be read move to 26.3, the `2026.10.0-java25` image and Fabric Loader `0.20.1` (or the table's newer values):
  - the `${MC_VERSION:-1.21.1}`, `${MC_IMAGE_TAG:-…-java21}` and `${FABRIC_LOADER_VERSION:-0.19.5}` defaults in `docker-compose.yml`
  - `emit MC_VERSION "${V_MC_VERSION:-1.21.1}"` in `deploy-reusable.yml`; deploy.sh, dev-up.sh and infra-deploy.sh export the bundle's version over it, but kuma-init's direct compose call in that workflow reads `.env`
  - the old-bundle fallbacks in `examples/consumer/dev` and in the PACK_NAME line of `deploy-reusable.yml`
- The 1.21.1 row of `scripts/minecraft-versions.tsv` goes with the 1.21.1 mod list. `platform_updates.py` then bumps the 26.3 row's image tag and loader.
- `mirror-images.yml` mirrors every image tag in the table, so the java25 image reaches the GHCR mirror on its next run, before any 26.3 stack pulls it.
- Comments in `config/modrinth-mods.txt` that only explain 1.21.1 pins go with that file. The carpet mixin strip in `scripts/patch-mod-data.py` exists because of Supplementaries, which 26.3 does not run.
