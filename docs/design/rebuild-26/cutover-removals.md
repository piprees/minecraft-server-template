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

| `servercore/config.yml` | Three mob categories (`GLARES`, `RASCALS`, `WATERFOWL`) belong to mods the 26.3 list does not carry; ServerCore rejects the first unknown category and discards the whole file | `config/versions/26.3/servercore/config.yml`, the base without those three blocks, replaces the base file |

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
- The in-house mod manifests: `mods/local-mods-26.3.manifest` replaces `mods/local-mods.manifest`, and `mods/client-mods-26.3.manifest` (no rows) replaces `mods/client-mods.manifest` until a 26.3 companion ships. `release.yml`, `mod-build.yml` and `build-stack-bundle.sh` already read them through `mc_local_mods_manifest` and `mc_client_mods_manifest`, and the release JDK through the table's java column.
- The v2 definitions in `mods/custom-dimensions-26/definitions/` replace `config/custom-dimensions/dimensions/` (schema-v2.md). `scripts/smoke-26.sh` makes the same replacement in `data/config/` for every 26.3 boot until then; it goes with that step.
- `scripts/game-rules.tsv` keeps its 26.3 rows; `mc_game_rule` in deploy.sh's enforcement block then reads the 26.3 ids. The 1.21.1 names in the block stay as its keys until the block is rewritten with the ids.
- `smoke-test-26.yml` becomes the smoke test: its boot gate, vanilla join gate and admin parity check move into `smoke-test.yml` (or replace it), `MC_VERSION_OVERRIDE` goes, and dep-review's `join26` job folds into `smoke`.
- discord-sync's `/mc border` runs `chunky border add`, which needs chunkyborder; chunkyborder has no 26.x build and the register replaces it with vanilla per-dimension borders our compiler sets. The `chunky border add` lines go.
- discord-sync's `/mc status` sends `spark health`; on 26.3 spark answers by uploading a report to spark.lucko.me and RCON gets nothing back, so the status falls back to vanilla `tick query`. `spark health` leaves the batch, and with it the memory figure, which `tick query` does not give.

## At the switch, also

Not removals: steps an existing server needs once its list carries Geyser and Floodgate (RB-21). A server provisioned from this template after P5 has the firewall rules already.

- Open `BEDROCK_PORT` (default `19132`) over TCP (NetherNet signaling) and UDP (gameplay). `harden.sh` runs only at provisioning and skips its rate-limit block when one is present, so on the host:
  - `sudo ufw allow 19132/tcp comment "Geyser Bedrock signaling"` and `sudo ufw allow 19132/udp comment "Geyser Bedrock"`
  - copy harden.sh's `mc-bedrock` line into the `RATE-LIMIT` block of `/etc/ufw/before.rules` and its `mc-bedrock6` line into that of `/etc/ufw/before6.rules` (back up both first), then `sudo ufw reload`
  - on Hetzner, the cloud firewall too: `hcloud firewall add-rule <SERVER_NAME>-fw --direction in --protocol udp --port 19132 --source-ips 0.0.0.0/0 --source-ips ::/0 --description "Bedrock (Geyser)"`, and the same with `--protocol tcp`
- Give the server a stable Floodgate key: `FLOODGATE_KEY_B64` in the GitHub `production` environment, `base64 < key.pem | tr -d '\n' | gh secret set FLOODGATE_KEY_B64 --env production` (README § Bedrock). Without it Floodgate generates its own key at first boot and keeps it in `data/config/floodgate/key.pem`.
- The `v6.0.0` breaking-change note names the UDP port ([decisions.md](decisions.md), RB-05).
