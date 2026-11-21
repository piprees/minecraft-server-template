# P9 — Archive the 1.21.1 world

The last state of the 1.21.1 world, kept three ways before the `v6.0.0` deploy wipes it: a restic snapshot, a final map render, and a world download that lives off the server. Retention (3 daily, 1 weekly, 1 monthly, and `BACKUP_SIZE_CAP_GIB`) prunes the restic snapshot within weeks of the new world's backups, so the download is the copy that lasts.

## Before

1. Announce the date in Discord; on the day, `./ops rcon "list"` shows nobody online.
2. `gh run list --limit 3`: no deploy in progress.
3. `./ops doctor` green.
4. Free disk for the tar: `./ops ssh 'df -h / && du -sh ~/server/data'`; the cold tar needs about the size of `data/world` free.

## 1. Final restic snapshot

```bash
./ops backup
./ops ssh 'docker logs mc-backup --tail 50'        # wait for "snapshot ... saved"; repeat by hand
./ops ssh 'docker exec -u 1000 mc-backup restic snapshots --latest 3'
```

Write the snapshot id into the release notes' draft and 1Password (`Minecraft Server` item, a `1.21.1 archive snapshot` field). Check it restores its files: `./ops ssh 'docker exec -u 1000 mc-backup restic ls <id> | grep -c "/region/"'` is not zero.

## 2. Last map render

```bash
./ops map render
./ops map status                                   # snapshot until the pass is idle
./ops ssh 'cd ~/server && tar czf backups/map-1.21.1-$(date +%Y%m%d).tar.gz data/map-render'
```

The path under `data/` is where the `unmined-render` service writes (check with `./ops ssh 'ls ~/server/data'` before the tar; use the directory the service's volume names).

## 3. World download

`./ops reset-seed` makes the cold tar as part of the wipe, but the archive is taken first and kept off the server:

```bash
./ops ssh 'cd ~/server && docker compose --profile cloud stop mc'   # not docker restart: no countdown is needed with nobody online, and stop leaves it stopped
./ops ssh 'cd ~/server && tar czf backups/world-1.21.1-$(date +%Y%m%d).tar.gz data/world data/config && sha256sum backups/world-1.21.1-*.tar.gz'
./ops ssh 'cd ~/server && docker compose --profile cloud start mc'
```

Then on the owner's machine (`DROPLET_HOST` and the deploy key from `.env`; `./ops ssh` resolves the same key):

```bash
scp -i ~/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key deploy@"$DROPLET_HOST":server/backups/world-1.21.1-*.tar.gz ./
scp -i ~/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key deploy@"$DROPLET_HOST":server/backups/map-1.21.1-*.tar.gz ./
sha256sum world-1.21.1-*.tar.gz                     # matches the server's line
```

Keep it in two places (the owner's disk and one cloud drive). Optionally hand players a single-player copy: `tar xzf` and open `data/world` in a 1.21.1 client with the 1.21.1 pack (the last v5 `.mrpack`).

## 4. The pack and the bundle

The last v5 release's assets stay on GitHub (releases are immutable): note its tag beside the snapshot id. A `release/v5` branch is cut from it only if a 1.21.1 fix is ever needed (decisions.md RB-05).

## Then

The world wipe is `./ops reset-seed <seed>` after the `v6.0.0` deploy (checklist.md § Production), which takes its own restic snapshot and cold tar first. Remove the archive tars from the droplet once the download is verified: `./ops ssh 'rm ~/server/backups/world-1.21.1-*.tar.gz ~/server/backups/map-1.21.1-*.tar.gz'` (disk the new world needs, P8).
