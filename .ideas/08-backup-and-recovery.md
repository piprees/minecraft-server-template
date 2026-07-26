# Skill brief: `backup-and-recovery`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

Every operation in this area is irreversible in one direction or the other, and the repo's own safety rule is blunt: *"Never delete `data/` on production. That's the world, and it can't be replaced."* The restore procedure exists as a seven-line code block in `README.md § Backups` with no verification step, no dry-run, and no guidance on what to do when it goes wrong. The world-reset ritual (`reset-seed.sh`) is triple-confirmed but its *undo* is only printed at runtime.

There is also a genuinely non-obvious correctness bug class that already bit: **restic groups retention by hostname**, and the `mc-backup` container's hostname defaulted to its container id. Every full deploy recreated the sidecar, so each deploy era became its own retention group whose last snapshots were stranded forever — 23 snapshots / ~50 GiB by 2026-07-24 despite a 3-daily/1-weekly/1-monthly policy. The fix (`hostname: "${BRAND_SLUG:-adventure}-mc-backup"`) is a load-bearing line in `docker-compose.yml` that looks cosmetic.

## Scope

**In:** taking backups, verifying them, restoring, the retention and size-cap model, what is deliberately *not* backed up, resetting the world with a new seed, and regenerating a corrupted chunk.

**Out:** provisioning R2 and its credentials → brief 12. Diagnosing why a backup failed as part of general triage → brief 03 (cross-reference).

## Source material

| File | What to mine |
| --- | --- |
| `README.md § Backups` | Schedule, retention, size cap + Discord notify, the exclude list, the restore block, the `RESTIC_PASSWORD` warning |
| `AGENTS.md` trap 14 | The hostname retention trap, the one-off cleanup command, `restic stats --mode raw-data` |
| `AGENTS.md § Confirm before proceeding`, § Safety rules 3/5/7 | `reset-seed` and `data/` deletion require a human; back up before version/mod/world changes |
| `docker-compose.yml` — `mc-backup`, `mc-backup-local`, `minio`, `minio-init` | The sidecar config, the `hostname:` line and its comment, the local MinIO stand-in |
| `scripts/backup-now.sh` (header) | **Why it restarts the sidecar rather than running restic directly** — one backup system, one set of excludes, one retention policy |
| `scripts/reset-seed.sh` (header) | Exactly what it deletes; `--same-seed`, `--force`, `--wipe-backups`; the post-restart re-run of deploy.sh's post-boot config |
| `scripts/wipe-chunk.sh` (header) | Region-file surgery; `--block`/`--chunk`/`--region`/`--nether`/`--dry-run`; the running-server refusal |
| `docs/deployment.md § Backup alternatives` | Swapping `RESTIC_REPOSITORY` for B2, local path, sftp, MinIO, Wasabi |
| `docs/troubleshooting.md` | The backup-fails row |
| `docs/credentials.md` | `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY` vs `CLOUDFLARE_API_TOKEN` — the most common paste mistake |
| `scripts/doctor.sh` (header) | The snapshot-age check: FAIL >48h, WARN >26h |

## Required structure

```
backup-and-recovery/
├── SKILL.md
└── references/
    ├── restore-procedure.md   # the full restore, step by step, with verification at each step
    └── world-reset.md         # reset-seed and wipe-chunk: what they destroy, what survives, how to undo
```

### SKILL.md must contain

1. **A destructive-operations banner at the top.** `./ops reset-seed`, deleting `data/`, `--wipe-backups`, and `./ops teardown` all require explicit human confirmation before execution — not "I'll run it and tell you". Cite `AGENTS.md § Confirm before proceeding`.
2. **`RESTIC_PASSWORD` is unrecoverable.** All backups die with it. It lives in 1Password. State this before anything else operational.
3. **How backups actually happen**: `mc-backup` sidecar, restic → Cloudflare R2, every 6h by default (`BACKUP_INTERVAL`), RCON `save-off`/`save-on` for consistency. Retention 3 daily / 1 weekly / 1 monthly. Locally, MinIO stands in so the same code path runs (console `localhost:9001`).
4. **The size cap behaviour**, because it deletes data: if the repo still exceeds `BACKUP_SIZE_CAP_GIB` (default 10) after retention, the oldest snapshot is forgotten and pruned, repeatedly — **but never below one snapshot**. A genuinely oversized world keeps one copy and the Discord notification carries a warning.
5. **What is excluded** — and why it matters when restoring: `bluemap`, `unmined-web`, `mods`, `libraries`, `versions`, `logs`, `crash-reports`, `kuma`, `DistantHorizons.sqlite`, `poi`, `ledger.sqlite`, `dynamic-data-pack-cache`. Only world, player data and config are backed up. An agent restoring must know the map and mods regenerate rather than assuming the restore failed.
6. **Taking a backup**: `./ops backup` (which restarts the sidecar deliberately — do not run restic by hand, it would create snapshots with different paths/tags/excludes), then verify with `docker logs mc-backup --tail 50` looking for `snapshot … saved`.
7. **The restore procedure**, expanded from `README.md` into steps with verification between them: stop the stack, export the repository and credentials, `restic snapshots --last 5`, restore to `/tmp/mc-restore`, **inspect before rsync**, rsync into `data/`, remove the temp dir, bring the stack up, verify via RCON. Add the missing checks — the README block has none.
8. **World reset** (`./ops reset-seed`), with its full destruction list and the fact that it re-runs deploy.sh's post-boot configuration afterwards. Note that `.env` must be committed after.
9. **Chunk surgery** (`./ops wipe-chunk`), including the coordinate modes and the hard requirement that the server is stopped or the chunks unloaded — writing to region files the server holds causes corruption.

### Traps to capture

1. **Restic retention groups by `(host, paths)`.** The hostname is pinned in `docker-compose.yml` and is brand-scoped so multiple servers sharing one bucket keep separate groups. If snapshot counts look wrong, check for dead hostname groups: `docker exec mc-backup restic forget --group-by paths --keep-last N --prune` (plain `--keep-last` keeps N **per dead host**). Real usage is `restic stats --mode raw-data`.
2. **Never run restic directly to take a backup** — different paths/tags/excludes than the sidecar, so retention stops working.
3. **`RESTIC_PASSWORD` rotation destroys access to existing backups.** Start a new repo if you must.
4. **The R2 credential mix-up.** The R2 token page shows three values; only Access Key ID and Secret go in `.env`. Pasting the Token value into `CLOUDFLARE_API_TOKEN` yields `Invalid API Token` on every DNS and tunnel call.
5. **A restore does not bring back mods, the map, or Kuma** — they are excluded by design and regenerate.
6. **`wipe-chunk` refuses while mc runs** unless `--force`; forcing it on a running server risks corruption.
7. **`reset-seed --wipe-backups` purges restic snapshots.** There is no undo.

### Validation section

```bash
./ops backup && docker logs mc-backup --tail 50   # expect "snapshot ... saved"
docker exec mc-backup restic snapshots --last 5
docker exec mc-backup restic stats --mode raw-data
./ops doctor                                       # snapshot-age check
```

## Done when

- An agent asked to restore produces a step-by-step plan with verification points and pauses for confirmation before touching `data/`.
- The skill explains the excluded-paths list well enough that an agent does not report a successful restore as a failure because the map is blank.
