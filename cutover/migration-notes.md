# Migration notes: a 1.21.1 server and its consumer repo to 26.3

Everything a running 1.21.1 server (`~/server` on the host) and its consumer repo (Elfydd) do to reach the `v6.0.0` stack that the repo switch does not do by itself. `scripts/migrate-to-26.sh` (in the v6 bundle; `files/docs/migrate-to-26.md` says how a v5 consumer runs it) runs them in this order as the phases `preflight` (1), `pin` (2), `archive` (4–6), `host` (7), `clean` (8), `consumer` (10), `verify` (11–13) and `first-backup` (15); it prints the owner steps when they are due and points at pre-generation (14). Each step is marked:

- **safe** — safe to automate: read-only, or reversible without loss;
- **confirm** — needs confirmation: irreversible, touches production `data/`, or stops the server;
- **owner** — owner-only: a release, a secret, a cloud-console or account action.

The one ordering rule: the 1.21.1 world never boots on the 26.3 stack. A full v6 deploy recreates `mc` on 26.3, so `data/world` is gone (step 8) before the first v6 deploy starts (step 10), not after it.

## A. Before the release

1. **safe** Preflight: `gh run list --limit 3 --json status,conclusion` (nothing in progress), `./ops doctor`, `./ops rcon "list"` (nobody online on the day).
2. **confirm** Pin the consumer to its running release so nothing deploys v6 early: in the consumer repo, `readlink` the server's bundle (`./ops ssh 'readlink ~/server/.stack/current'`), commit that `vX.Y.Z` as `.stack-version`, push; delete the `STACK_VERSION` repository variable (`gh variable delete STACK_VERSION --repo <owner>/<consumer>`). The push deploys the same release (a pull-tier deploy). `elfydd-patch.md` step 1.
3. **owner** Merge the switch PR, then `gh workflow run release.yml -f version=v6.0.0`, then `git fetch origin '+refs/tags/v6:refs/tags/v6'`. The breaking-change note names the new world and the Bedrock port (`checklist.md` § Owner only).

## B. Archive the 1.21.1 world (`p9-archive.md`)

4. **safe** Restic snapshot: `./ops ssh 'cd ~/server && bash .stack/current/stack/scripts/backup-now.sh'` (`./ops backup` runs `backup-now.sh` against the operator's own Docker, not the server's); `./ops ssh 'docker logs mc-backup --since <the trigger time> --tail 200'` in a bounded number of snapshots until `snapshot <id> saved`; record the id and check `restic ls <id>` lists region files.
5. **safe** Last map render and its tar: `./ops map render`, then, on a later run once `./ops map status` shows the pass idle (the operator's judgement, confirmed as `map`), `./ops ssh 'cd ~/server && tar czf backups/map-1.21.1-$(date +%Y%m%d).tar.gz data/unmined-web'`.
6. **confirm** Stop `mc` for a cold world tar, and leave it stopped: `./ops ssh 'cd ~/server && docker compose --profile cloud stop mc'`, then `./ops ssh 'cd ~/server && tar czf backups/world-1.21.1-$(date +%Y%m%d).tar.gz data/world data/config && sha256sum backups/world-1.21.1-*.tar.gz'`, then download it off the host (`scp`) and check the sum: step 8 takes that sum back (`--offhost-sha`) and refuses unless it equals the recorded one. (P9 restarts `mc` here; for the migration it stays down until step 10.)

## C. The host

7. **confirm** Bedrock firewall (`BEDROCK_PORT`, default 19132, TCP and UDP): `sudo ufw allow 19132/tcp comment "Geyser Bedrock signaling"`, `sudo ufw allow 19132/udp comment "Geyser Bedrock"`; back up `/etc/ufw/before.rules` and `/etc/ufw/before6.rules` to `.bak.$(date +%Y%m%d-%H%M%S)`, copy `harden.sh`'s `mc-bedrock` and `mc-bedrock6` lines into their `RATE-LIMIT` blocks, `sudo ufw reload`. **owner** On Hetzner, the cloud firewall: `hcloud firewall add-rule <SERVER_NAME>-fw --direction in --protocol udp --port 19132 --source-ips 0.0.0.0/0 --source-ips ::/0 --description "Bedrock (Geyser)"` and the same with `--protocol tcp`.
8. **confirm** Remove what the 26.3 stack would misread from production `data/`, only while, checked on the server at that moment: `mc` is stopped, step 6's tar exists and matches its recorded sha256, the off-host copy's sum equals it, step 4's snapshot is in the restic repository, and `readlink .stack/current` is still v5:
   - the world and its derived state, as `reset-seed.sh` § 5 deletes them: `sudo rm -rf data/world/ data/unmined-web/maps/ data/unmined-web/index.html data/unmined-web/manifest.json data/config/chunky/tasks/ data/dynamic-data-pack-cache/ data/.dimension-setup/`; `sudo rm -f data/.chunky-*complete data/.dhpregen-*complete data/.skip-pause data/config/custom-dimensions-fingerprints.json`;
   - `data/config/portal_links.json`: the 26.3 mod imports a 1.21.1 links file into a world that has none (`PortalRegistry`), so the new world would start with portal links into coordinates that meant something else;
   - the flat Serilum configs: Collective 26.3 moves `config/<modid>.json5` and `config/<modid>/` over `config/serilum/<modid>.json` on every boot, so the 1.21.1 copies would replace the 26.3 settings once: `sudo rm -rf data/config/{collective,doubledoors,firespreadtweaks,healingcampfire,nametagtweaks,nutritiousmilk,youritemsaresafe}/ data/config/{collective,starterkit}.json5`;
   - `data/config/custom-dimensions/`: the 1.21.1 definitions, thumbnails, `settings.json` at the folder root, `display-names.json`, `climate-axes.json`, the structure files and the staged `overlay/`. The v6 deploy clears `dimensions/` and `bridges/` itself and `overlay/` is removed every deploy; the rest is unread, so `sudo rm -rf data/config/custom-dimensions/` is a tidy-up, not a need;
   - configs of cut mods (`betterdays/`, `lootr/`, `boring_default_game_rules/`, `tectonic.json`, `bountiful/`, `seasons.json`, `configurable-difficulty/`, `bluemapsignmarkers/`): unread on 26.3; remove for tidiness.
   `data/mods/` needs nothing: the deploy prunes every jar the 26.3 seed manifest does not list.

## D. Secrets and the consumer repo

9. **owner** `FLOODGATE_KEY_B64` in the consumer's `production` environment (`base64 < key.pem | tr -d '\n' | gh secret set FLOODGATE_KEY_B64 --env production --repo <owner>/<consumer>`), and `STACK_READ_TOKEN` while the template is private. Without the key Floodgate generates one at first boot into `data/config/floodgate/key.pem`; copy that off the server and set the secret from it before the next full deploy regenerates `.env`.
10. **confirm** The consumer patch (`elfydd-patch.md` steps 2–6): `.stack-version` to `v6.0.0` first (a v6 `dev` reads the file before an exported `STACK_VERSION`, so a v5 pin left there pulls v5 back), then `STACK_VERSION=v6.0.0 ./dev update --scaffold-only` (a v5 `dev` takes the exported variable, unless `.env` carries `STACK_VERSION`, which goes first), `deploy.yml` on `deploy-reusable.yml@v6` with the two secrets, the 1.21.1 seed-viewer files (`*_thumb.json`, `*_low.png`, `*_high.png`) out of `overlay/config/custom-dimensions/dimensions/`; commit and push. The push runs a full deploy: the seed resolves the 26.3 list, `deploy.sh` seeds `data/config/`, stages the overlay, and starts `mc` on an empty world, which generates the 26.3 world from the definitions. Resolve the run by commit (`gh run list --workflow deploy.yml --commit <sha> --json databaseId,status`) and check it once; the first boot compiles the pack and pre-fetches every mod (allow 15 minutes and more).

## E. The new world

11. **safe** Health: `./ops doctor`, `./ops rcon "customdim list"` (82 dimensions: 4 base, 78 lazy), `./ops rcon "customdim audit"` (no stale, missing or orphan entries), `./ops logs mc --tail 200 --grep ERROR`.
12. **confirm** The world seed. `level.dat` takes `SEED` from `.env` (`reset-seed.sh` writes it); the overworld definition carries its own `"seed"`. Which one the 26.3 overworld generates from is to be confirmed in the rehearsal (`./ops rcon "seed"` against the definition's value); if they differ, `./ops reset-seed <the definition's seed>` once, before anyone joins.
13. **safe** Game rules and spawn: the deploy set them (`mc_game_rules`, the overworld definition's `spawn`); check with `./ops rcon "gamerule spawn_phantoms"` and `./ops rcon "gamerule log_admin_commands"`. Whether the lazy levels follow the overworld's game rules is to be checked once: `./ops rcon "customdim activate <slug>"` on a lazy dimension, then `./ops rcon "execute in adventure:<slug> run gamerule spawn_phantoms"`.
14. **confirm** Pre-generation (`p8-pregen.md`): stop `idle-tasks`, one dimension at a time, base worlds first, markers written after each; measure the per-1,000-chunk figures on the first dimension and stop if the projected disk exceeds what `./ops doctor` reports free less `BACKUP_SIZE_CAP_GIB`.
15. **safe** First 26.3 backup: as step 4, on the v6 bundle.
16. **owner** Launch: a vanilla 26.3 client, the pack and a Bedrock device each join and cross into three dimensions including The Aviary; five players hold 19+ TPS (`checklist.md` § Owner only).

## What the script must not assume

- That `reset-seed.sh` alone migrates: it restarts `mc` on whatever stack the server runs, so run on v5 it regenerates a 1.21.1 world, and run on v6 it comes after a boot of the old world. The deletions of step 8 are its § 5 list plus the four groups above, done with `mc` stopped.
- That a v6 deploy can be undone: once the 26.3 world exists, rolling `.stack-version` back to v5 boots a 1.21.1 server on a 26.3 world. A rollback is the step 6 tar restored with `mc` stopped, on v5.
- That autopause or RCON silence means failure (AGENTS.md § Production access), or that any wait may loop: every wait in the script is a single bounded snapshot.
