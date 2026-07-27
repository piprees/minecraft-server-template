---
title: World reset and chunk surgery
description: Exactly what reset-seed.sh and wipe-chunk.sh destroy, what survives, and how to undo each
tags: [reset-seed, wipe-chunk, world-reset, region-files, undo]
---

# World reset and chunk surgery

Both operations here are covered by `AGENTS.md § Confirm before proceeding` — get explicit human confirmation before running either for real, not after.

## `./ops reset-seed` — full world reset for a new seed

`scripts/reset-seed.sh` runs from your Mac and SSHes to the droplet for every remote step. It is triple-confirmed by default: retype the new seed, then type `RESET` at a second prompt. `--force` skips both prompts — **treat `--force` as requiring the same human go-ahead as the operation itself**, since it removes the script's only safety net.

```bash
./scripts/reset-seed.sh                                  # interactive, prompts for the seed
./scripts/reset-seed.sh <seed>                            # pre-fill the seed, still confirms
./scripts/reset-seed.sh --same-seed                       # reset the world, keep the current seed
./scripts/reset-seed.sh --force                           # skip all confirmation prompts
./scripts/reset-seed.sh --wipe-backups                    # also purge restic snapshots in R2
./scripts/reset-seed.sh --force --same-seed --wipe-backups
```

### Sequence

1. **Backup — restic.** Runs `backup-now.sh` on the droplet. This is best-effort: if it fails, the script prints a warning and continues (relying on the tar backup in the next step). Do not treat a restic failure here as blocking, but do not ignore it either — it means the only surviving copy for this reset is the local tar.
2. **Backup — tar.gz.** Creates `backups/pre-reset-<old-seed>-<timestamp>.tar.gz` on the droplet from `data/`, with the same regenerable-data excludes as the restic sidecar (`unmined-web`, `mods`, `libraries`, `versions`, `logs`, `crash-reports`, `DistantHorizons`/`.sqlite`, `poi`, `ledger.sqlite`, `dynamic-data-pack-cache`, `kuma`). This one is not best-effort — if it fails the script still doesn't abort, but the announced backup path won't exist, so check for it before trusting the "undo" instructions printed at the end.
3. **Stop all containers** on the droplet (`docker compose --profile cloud down`).
4. **Delete world, player, and regenerable data:**
   - `data/world/`, `data/world_the_nether/`, `data/world_the_end/`, `data/dimensions/` — every dimension's terrain
   - `data/playerdata/`, `data/stats/`, `data/advancements/` — every player's progress
   - `data/unmined-web/maps/` and `data/unmined-web/index.html` — rendered map tiles (re-rendered on the next pass)
   - `data/.chunky-complete`, `data/.chunky-nether-complete`, `data/.chunky-end-complete`, `data/.chunky-paradise-lost-complete`, `data/.skip-pause`, `data/config/chunky/tasks/` — pre-generation markers and task state
   - `data/DistantHorizons/`, `data/DistantHorizons.sqlite`, `data/poi/`, `data/ledger.sqlite`, `data/dynamic-data-pack-cache/` — regenerable caches
5. **Update the seed** in `.env` (local, backed up first to `.env.bak.<timestamp>`) and on the droplet (same backup pattern) — unless `--same-seed`, which keeps the current value.
6. **Wipe restic backups** (`--wipe-backups` only): lists every snapshot in the repo and `restic forget`s all of them with `--prune`. **No undo.** This is independent of the tar/restic backup taken in steps 1–2 — those still exist on disk/in R2 history up to the point of the forget; it's _future_ recoverability from R2 that's gone.
7. **Restart via `deploy.sh --pull --non-interactive`** — this re-applies deploy.sh's normal post-boot configuration (world borders, game rules, LuckPerms permissions, spawn coordinates from `SPAWN_X/Y/Z`), the same as any full deploy.
8. **Summary + undo instructions** are printed, including the exact backup path and the commands to restore it. **Commit `.env` afterwards** if deploying via CI — otherwise the next CI deploy regenerates `.env` from GitHub secrets and may put the old seed back.

### Undo (only works if the backup step succeeded)

The script prints this at the end, but it's worth knowing without re-reading the output:

```bash
# 1. Stop the server
ssh -i <key> <user>@<host> 'cd ~/server/.stack/current/stack && docker compose --project-directory ~/server --profile cloud down'
# 2. Restore the tar backup
ssh -i <key> <user>@<host> 'cd ~/server && tar xzf backups/pre-reset-<old-seed>-<timestamp>.tar.gz'
# 3. Revert the seed in .env (local and droplet) — or restore .env.bak.<timestamp>
# 4. Restart
ssh -i <key> <user>@<host> 'cd ~/server/.stack/current/stack && bash scripts/deploy.sh --pull --non-interactive'
```

This restores from the **tar.gz**, not restic — it's the faster, locally-available copy. Fall back to the restic restore procedure (`references/restore-procedure.md`) only if the tar backup is missing or corrupt.

## `./ops wipe-chunk` — regenerate a single corrupted region

`scripts/wipe-chunk.sh` operates on one 32×32-chunk region file at a time. It never deletes data outright: it moves the region file aside with a `.bak.<timestamp>` suffix, so undo is a plain `mv` back.

```bash
./scripts/wipe-chunk.sh --block -1808 -2832           # F3 block coordinates
./scripts/wipe-chunk.sh --chunk -113 -177             # Chunky-style chunk coordinates
./scripts/wipe-chunk.sh --region -4 -6                # region file directly
./scripts/wipe-chunk.sh --block -1808 -2832 --nether  # --nether or --end for other dimensions (default: overworld)
./scripts/wipe-chunk.sh --block -1808 -2832 --force   # skip the running-server check
./scripts/wipe-chunk.sh --block -1808 -2832 --dry-run # print the resolved path, change nothing
./scripts/wipe-chunk.sh --block -1808 -2832 --local   # operate on the local checkout instead of the droplet
```

Coordinate conversion (all via floor division, so negative coordinates resolve correctly): block → chunk (÷16) → region (÷32). Dimension maps to a region directory: overworld → `data/world/region`, nether → `data/world/DIM-1/region`, end → `data/world/DIM1/region`.

**Refuses to run while `mc` is up, unless `--force`.** The server holds region files open; writing to (moving) one out from under a live process risks corrupting whatever chunks in that region are currently loaded. Only pass `--force` after independently confirming the affected chunks are unloaded — e.g. the coordinates are far from spawn on an autopaused, empty server — and say so before running it.

**Undo:**

```bash
mv data/world/region/r.-1.-1.mca.bak.20260724-153000 data/world/region/r.-1.-1.mca
```

(the script prints the exact `mv` command with real paths after a successful wipe). If the region file didn't exist in the first place, the script exits cleanly with "Nothing to wipe" — the chunks were already going to generate fresh, no action taken.
