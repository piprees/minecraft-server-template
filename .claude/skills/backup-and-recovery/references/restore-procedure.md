---
title: Restore procedure
description: Step-by-step restic restore from Cloudflare R2 (or the local MinIO stand-in), with a verification point after every step
tags: [restic, restore, r2, minio, rcon, backup]
---

# Restore procedure

This expands `README.md § Backups → Restore` into a checked sequence. The README's own version of this block has no verification steps between actions — that's exactly where a restore goes wrong unnoticed. Do not skip a check to save time; each one is cheap and catches a different failure mode.

**Pause for explicit human confirmation before step 5 (`rsync`).** Everything before it is read-only or reversible (stopping containers, restoring to a scratch directory). `rsync` into `data/` is the point where whatever is currently on disk gets overwritten.

This procedure assumes production (R2-backed `mc-backup`), run over SSH on the server. For a local restore, swap the exported variables for the MinIO equivalents shown at the end.

## 1. Stop the stack

```bash
docker compose --profile cloud down
```

**Verify:** `docker ps` shows no `mc`, `mc-backup`, or other stack containers running. If anything is still up, the restore below will race a live process — don't proceed until this is clean.

## 2. Export the repository and credentials

```bash
export RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}"
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export RESTIC_PASSWORD
```

Run this from `~/server` with `.env` already sourced (`set -a && source .env && set +a`) so the `R2_*` and `RESTIC_PASSWORD` variables resolve. `RESTIC_PASSWORD` unset or wrong fails every restic command below with a decrypt error, not a helpful message — check it's actually exported (`echo ${RESTIC_PASSWORD:+set}`) before assuming the repository itself is broken.

**Verify:** `restic snapshots --latest 1` returns a snapshot, not an error. If it says `"repository does not exist"`, the bucket/account id/credentials are wrong, or the repo was genuinely never initialised (the credentials reference in the `server-provisioning` skill has the R2 credential table; `TROUBLESHOOTING.md` § Common symptoms → Backups is the shorter triage version).

## 3. List snapshots and pick one

```bash
restic snapshots --latest 5
```

Note: `restic snapshots` has no `--last` flag — it's `--latest n`. If you're troubleshooting off an older doc or note that says `--last`, that's wrong; use `--latest`.

**Verify:** the timestamp of the snapshot you intend to restore is the one you expect, and its host matches the current `hostname:` in `docker-compose.yml`'s `mc-backup` service (`${BRAND_SLUG:-adventure}-mc-backup`) — a snapshot from a stale/dead hostname group (see the retention trap in `SKILL.md`) is still valid data, just check you're not restoring something unexpectedly old because the wrong host's snapshots are listed. Restoring `latest` restores the most recent snapshot from *any* host in that grouping; pin a specific snapshot ID if you need a particular one.

## 4. Restore to a scratch directory — never straight into `data/`

```bash
restic restore latest --target /tmp/mc-restore
# or restic restore <snapshot-id> --target /tmp/mc-restore for a specific one
```

**Verify:** `ls /tmp/mc-restore/data/` shows `world/`, `world_the_nether/`, `world_the_end/`, `playerdata`-bearing directories, and `config/`. If any of these are missing, you restored a snapshot with a broken excludes/paths config, or grabbed the wrong snapshot — stop here, don't proceed to rsync.

## 5. Inspect before rsync — then pause for confirmation

Look at what you're about to overwrite:

```bash
ls -la data/world/ 2>/dev/null | head
du -sh /tmp/mc-restore/data/* 2>/dev/null
```

**This is the point of no return for the current `data/` contents.** Confirm with the human before continuing — say what's currently in `data/` (if anything), what the restored snapshot contains, and get an explicit go-ahead. Do not rsync and then report it.

```bash
rsync -av /tmp/mc-restore/data/ ./data/
```

**Verify:** `diff -rq /tmp/mc-restore/data/world /data/world | head` (adjust path — rsync target is `./data/world` relative to `~/server`) shows no differences, confirming the copy landed intact.

## 6. Clean up the scratch directory

```bash
rm -rf /tmp/mc-restore
```

**Verify:** `df -h /tmp` — this matters on a droplet with limited disk; a stray restore left in `/tmp` after every future restore attempt eats space silently.

## 7. Bring the stack back up

```bash
docker compose --profile cloud up -d
```

**Verify:** `docker ps` shows `mc` reaching `(healthy)` (allow the `start_period: 5m` healthcheck window), then:

```bash
docker exec -i mc rcon-cli "list"
docker exec -i mc rcon-cli "seed"
```

`list` confirms RCON is answering (server actually booted, not autopaused mid-restore-verification — silence right after a fresh `up -d` before the healthcheck passes is expected, not a failure). `seed` confirms the world that came up is the one you restored, not a fresh generation from a missing `world/` directory.

## 8. Remember what did not come back

Map tiles, mods in `data/mods/`, Kuma history, Distant Horizons/POI/ledger caches are excluded from every backup by design (`EXCLUDES` in `docker-compose.yml`'s `mc-backup` service) and regenerate on their own — a blank map or empty mods directory immediately after a restore is expected, not evidence the restore failed. Give it time (the next render pass, `sync-mods.sh` re-downloading jars) before treating it as a problem.

## Local restore (MinIO instead of R2)

Same procedure, different environment block for step 2 — read the actual values from `mc-backup-local`'s environment in `docker-compose.yml` rather than assuming they match production:

```bash
export RESTIC_REPOSITORY="s3:http://minio:9000/mc-backups"
export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin123
export RESTIC_PASSWORD=local-dev-backups
```

These only work from inside the Docker network (the `minio` hostname resolves there) — run restic commands via `docker exec mc-backup-local restic ...` rather than from the host shell, or exec into a container on the same network.
