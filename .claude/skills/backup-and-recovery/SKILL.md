---
name: backup-and-recovery
description: |
  Guides taking, verifying, and restoring backups of the Minecraft server's world, player data, and config, resetting the world with a new seed, and repairing a single corrupted chunk region. Covers the mc-backup restic-to-R2 sidecar (and its local MinIO stand-in via mc-backup-local), the default 12h schedule, 3-daily/1-weekly/1-monthly retention, the BACKUP_SIZE_CAP_GIB trim behaviour that really bounds the repo, the restic hostname retention trap, and the excluded-paths list so a restore isn't mistaken for a failure.

  Use when: taking or verifying a manual backup, writing a restore plan, running ./ops reset-seed or ./ops wipe-chunk, diagnosing "repository does not exist" or ballooning snapshot counts, or confirming a backup with "snapshot ... saved" in the mc-backup logs.
---

# Backup and recovery

## STOP — destructive operations require human confirmation first

`./ops reset-seed`, deleting anything under `data/`, `./ops reset-seed --wipe-backups`, and `./ops teardown` are all listed in `AGENTS.md § Confirm before proceeding` as allowed-but-irreversible. **Never run one of these and then report the outcome — pause and get explicit human confirmation before executing.** Producing a plan and waiting for a "go" is the job; "I'll run it and tell you" is not acceptable for any of these four.

**`RESTIC_PASSWORD` cannot be recovered if it's lost.** Every backup — every snapshot, in every retention tier — is unreadable without it. It lives in 1Password. Read this before touching anything else in this skill: if you are ever asked to rotate or regenerate it casually, stop and say so, per the credentials reference (§ Rotation quick reference, in the `server-provisioning` skill) ("Avoid rotating — existing backups are unreadable without the original. Start a new restic repo if you must").

## How backups actually happen

The `mc-backup` sidecar (`docker-compose.yml`) runs restic against Cloudflare R2 on a schedule (`BACKUP_INTERVAL`, default `12h`), wrapping each snapshot in RCON `save-off`/`save-on` so the world files are consistent while restic reads them. Locally, `mc-backup-local` talks to a MinIO container instead (console at `localhost:9001`, `minioadmin`/`minioadmin123`) — same restic code path, same excludes, same retention, so what you verify locally is what runs in production.

Retention: **3 daily, 1 weekly, 1 monthly** — `--keep-daily 3 --keep-weekly 1 --keep-monthly 1`, from `RESTIC_RETENTION_DAILY`/`_WEEKLY`/`_MONTHLY` in `.env`, with those figures as the compose fallbacks so a lean `.env` gets the documented policy. Sized to fit R2's free 10GB tier.

**Before diagnosing a snapshot count as wrong, read the policy off the running server rather than assuming the default:**

```bash
docker exec mc-backup printenv PRUNE_RESTIC_RETENTION   # ground truth
grep RESTIC_RETENTION .env                              # set only if this server overrides
```

**Retention is not what ultimately bounds the repo — `BACKUP_SIZE_CAP_GIB` is.** Whatever retention keeps, the size cap trims below it once the repo exceeds the cap (next section). Reason about the cap first and retention second.

**Never run restic directly to take a backup.** `scripts/backup-now.sh` (→ `./ops backup`) deliberately restarts the `mc-backup` container rather than invoking restic itself — a hand-run restic snapshot would use different paths/tags/excludes than the sidecar's, and retention pruning groups by exactly those fields, so a hand-run snapshot quietly stops being cleaned up. One backup system, one set of excludes, one retention policy.

## The size cap — it deletes data if the world outgrows the cap

After every backup, `POST_BACKUP_SCRIPT` (the `x-backup-post` block in `docker-compose.yml`) checks the repo's raw size against `BACKUP_SIZE_CAP_GIB` (default `10`, i.e. R2's free tier). If it's still over the cap after normal retention pruning, it forgets and prunes the **oldest** snapshot, repeats, up to 10 times — but it will never drop below **one** snapshot. A genuinely oversized world keeps exactly one copy and the Discord notification carries a `:warning:` telling you to raise `BACKUP_SIZE_CAP_GIB` or shrink the world, rather than silently deleting the only backup. Every run posts to `DISCORD_WEBHOOK_URL` (skipped when unset, e.g. local dev): backup OK with size/count, or backup FAILED with the exit code.

## What is excluded — know this before calling a restore "failed"

Only the world, player data, and config are irreplaceable, so only those are backed up. `EXCLUDES` in the `mc-backup` service:

```
unmined-web, mods, libraries, versions, logs, crash-reports,
kuma, DistantHorizons.sqlite, poi, ledger.sqlite, dynamic-data-pack-cache, .fabric
```

Restic's `EXCLUDES` patterns match by name at any depth, not just at the top of `/data` — a bare name like `DistantHorizons.sqlite` excludes it in `data/world/data/`, `data/world/DIM1/data/`, and every custom dimension's `dimensions/<ns>/<slug>/data/` copy in one entry. Verify with `docker exec mc-backup restic ls latest --long | grep -i distanthorizons` — it should return nothing even with Distant Horizons enabled across multiple dimensions.

After a restore, the map is blank, mods are missing from `data/mods/`, Kuma history is gone, and Distant Horizons/POI/ledger caches are empty. **This is correct, not a failed restore** — the map re-renders, mods re-download via `sync-mods.sh`, DH/POI/ledger regenerate from the restored world on next boot. Don't restart triage on a blank map after a restore; confirm the world/playerdata/config are actually present first.

## Taking a manual backup

```bash
./ops backup                                    # restarts mc-backup; backup runs after a 2m INITIAL_DELAY
docker logs mc-backup --tail 50                 # verify — look for "snapshot ... saved"
```

`INITIAL_DELAY` is `2m`, so nothing appears immediately — that is not a failure. Take another `--tail` snapshot after a few minutes.

**Never `docker logs -f`.** It streams forever and traps a non-interactive session with no way out (`AGENTS.md` safety rule 10). Repeated bounded `--tail` snapshots are the only correct way to watch this, and a single `sleep 120` between them is allowed; a wait loop is not.

## Restoring from backup

Full step-by-step procedure with a verification point after each step: **`references/restore-procedure.md`**. Read it before touching `data/` on the server. It is the only copy of the procedure, and its verification points are where a restore otherwise goes silently wrong.

Summary of the shape (do not skip the reference file's detail): stop the stack → export `RESTIC_REPOSITORY`/credentials → `restic snapshots --latest 5` (confirm you're targeting the right snapshot) → `restic restore <id> --target /tmp/mc-restore` → **inspect the restored tree before touching `data/`** → `rsync -av /tmp/mc-restore/data/ ./data/` → remove the temp dir → bring the stack back up → verify via RCON. Pause for human confirmation before the `rsync` step — it's the point of no return for whatever is currently in `data/`.

## World reset (`./ops reset-seed`)

Full destruction list, what survives, and undo instructions: **`references/world-reset.md`**.

In short: `./ops reset-seed <seed>` backs up (restic, best-effort, plus a local `tar.gz` on the droplet that isn't best-effort) _before_ deleting anything, then wipes world/player/map-render/Chunky/Distant-Horizons/POI/ledger data, updates `SEED` in `.env` (local and droplet), and re-runs `deploy.sh`'s post-boot configuration (world borders, game rules, permissions, spawn coordinates). It is triple-confirmed at the prompt (retype the seed, then type `RESET`) unless `--force` is passed — **never pass `--force` without the human having already given explicit go-ahead for this specific run**, since it exists to skip the only safety net the script has. `--wipe-backups` additionally purges every restic snapshot in R2 with no undo. Commit `.env` afterwards so CI doesn't overwrite the new seed on the next deploy.

## Chunk surgery (`./ops wipe-chunk`)

`scripts/wipe-chunk.sh` moves a single 32×32 region file aside so Minecraft regenerates it from the seed on next load. It never deletes — it renames to `<region>.mca.bak.<timestamp>`, so undo is `mv` back.

```bash
./ops wipe-chunk --block -1808 -2832            # F3 block coordinates
./ops wipe-chunk --chunk -113 -177              # Chunky-style chunk coordinates
./ops wipe-chunk --region -4 -6                 # region file directly
./ops wipe-chunk --block -1808 -2832 --nether   # --nether / --end for other dimensions
./ops wipe-chunk --block -1808 -2832 --dry-run  # show the resolved path, change nothing
```

**The server must be stopped, or refuse to run.** The script checks for a running `mc` container and exits with an error unless `--force` is passed. This is not a formality: the region file is memory-mapped by a live server, and writing to it out from under that (moving it) risks corruption of whatever chunks are still loaded from it. Only pass `--force` when you've independently confirmed the affected chunks are unloaded (e.g. far from spawn on an empty, autopaused server) — and say so before doing it.

## Traps

1. **Restic retention groups by `(host, paths)` — a container id is not a stable host.** `mc-backup`'s hostname used to default to its container id; every full deploy recreates the sidecar, so each deploy era became its own retention group whose last snapshot was retained forever and never cleaned up (23 snapshots / ~50GiB by 2026-07-24). Fixed by the load-bearing `hostname: "${BRAND_SLUG:-adventure}-mc-backup"` line in `docker-compose.yml` — brand-scoped so multiple servers sharing one bucket keep separate retention groups. If snapshot counts or R2 usage look wrong, check for dead-hostname groups: `docker exec mc-backup restic forget --group-by paths --keep-last N --prune` (plain `--keep-last` keeps N snapshots **per dead host**, not N total — that's the whole point of the fix). Check real usage with `docker exec mc-backup restic stats --mode raw-data`, not the snapshot count.
2. **Never run restic directly to take a backup.** Different paths/tags/excludes than the sidecar breaks retention grouping for that snapshot permanently. Use `./ops backup`.
3. **`RESTIC_PASSWORD` rotation destroys access to every existing backup.** There is no re-encrypt. If a rotation is genuinely required, start a new repository instead of rotating the password on the existing one.
4. **The R2 credential mix-up.** R2's "Manage API Tokens" page shows three values (Token value, Access Key ID, Secret Access Key) but only the Access Key ID and Secret go into `.env` as `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY`. Pasting the Token value into `CLOUDFLARE_API_TOKEN` produces `Invalid API Token` on every DNS and tunnel call — it's scoped to R2 only. See the credentials reference § Cloudflare (in the `server-provisioning` skill).
5. **A restore does not bring back the map, mods, or Kuma history — this is by design, not a failure.** See the excludes list above before reporting a restore as broken.
6. **`wipe-chunk` refuses while `mc` is running unless `--force`.** Forcing it against a live server risks corrupting the region file the server still holds open.
7. **`reset-seed --wipe-backups` purges every restic snapshot in R2, with no undo.** Only the local `tar.gz` backup on the droplet and any off-server copy survive that flag.
8. **A repo stuck at exactly one snapshot doesn't always mean the excludes are broken.** `BACKUP_SIZE_CAP_GIB` bounds the whole repo, not one snapshot — if a single full snapshot of the non-excluded data (world region files, mainly) is already bigger than the cap, the post-backup trim forgets every older snapshot on every run and the repo never holds more than one. Check `docker exec mc-backup restic stats --mode raw-data` (repo size, what the cap compares against) against `restic stats latest --mode restore-size` (size of one snapshot's actual files) before assuming the excludes are wrong — an actively-explored world with Terralith/Incendium/custom-dimension terrain can legitimately need a `BACKUP_SIZE_CAP_GIB` well above the 10 GiB R2-free-tier default; raising it is the fix, not inventing new excludes.

## Validation — run these, don't assume

```bash
./ops backup && docker logs mc-backup --tail 50   # expect "snapshot ... saved"
docker exec mc-backup restic snapshots --latest 5
docker exec mc-backup restic stats --mode raw-data
./ops doctor                                       # snapshot-age check: FAIL >48h, WARN >26h (schedule is 12h)
```

`"repository does not exist"` from any restic command means either the R2 bucket/credentials in `.env` are wrong or the repo was never initialised — `restic -r <repo> init`. To swap the backend: set `RESTIC_REPOSITORY` in `.env` to a different target (Backblaze B2 `s3:https://s3.REGION.backblazeb2.com/BUCKET`, local path, `sftp:user@host:/path`, MinIO, Wasabi), init the new repo, restart `mc-backup`, and test with `./ops backup`. `TROUBLESHOOTING.md` § Common symptoms → Backups covers the same triage: verify R2 credentials, check `restic snapshots`, check disk (`df -h`), check `docker logs mc-backup --tail 50`.

## References

- `references/restore-procedure.md` — the full restore, step by step, with a verification point after each step
- `references/world-reset.md` — exactly what `reset-seed` and `wipe-chunk` destroy, what survives, and how to undo each
