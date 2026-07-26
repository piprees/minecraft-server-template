---
title: Command surface
description: The full dev + ops command table, derived from commands.json and cross-checked against the ops dispatch table for which bundle script each command runs.
tags: [dev, ops, commands.json, dispatch, production]
---

# Command surface

This table is **derived from `examples/consumer/commands.json`**, which is the source of truth for the `production` flag per command. It's cross-checked here against the actual dispatch logic in `examples/consumer/ops` (the `script_file` remap and the `ALLOWED_COMMANDS` allowlist) so you can see which bundle script a command really runs. When `commands.json` and the `ops`/`dev` scripts disagree (see the note at the bottom), the scripts win — `commands.json` is a description index, not the implementation.

## `./dev` — always local

Every `dev` command operates only on the local Docker stack. None of these touch production.

| Command | Description (commands.json) |
| --- | --- |
| `up` | Start the local dev stack |
| `down` | Stop the local dev stack |
| `logs` | Tail the Minecraft server logs |
| `rcon` | Run an RCON command locally |
| `pack` | Build the client modpack into `./modpack-dist/` |
| `pin` | Re-pin `overlay/mods-extra.txt` to latest mod builds |
| `pull` | Fetch the stack bundle only |
| `update` | Pull the latest stack bundle + Docker images |
| `rollback` | Revert to a previous stack bundle version |
| `doctor` | Local health check (containers, RCON, seed exit) |
| `seed-roll` | Parallel-roll seeds for every dimension, auto-pick winners |
| `seed-rescore` | Recompute candidate scores vs current configs (no re-rolling) |
| `seed-status` | Candidate-bank status: counts, winners, score freshness |
| `cache` | Snapshot Docker images, mod JARs, offline client bundles |
| `start` | Start a stopped local service |
| `stop` | Stop a running local service |
| `restart` | Force-recreate a local service |
| `status` | Show local container status |

Two `dev` commands exist in the actual script but not in `commands.json`: `refresh-config` (force-refresh platform config defaults, backing up `data/config`; overlay still wins) and `seed-viewer` (launches the interactive seed viewer). Both are documented in `./dev help` output, just missing from the JSON index.

`./dev sync` also exists but is a deprecated shim — see the main SKILL.md's update-commands table. It isn't in `commands.json` either.

## `./ops` — production, with two 1Password exceptions

`commands.json`'s `production` flag distinguishes commands that touch the live server (`true`) from the two that only move `.env` to/from 1Password (`false`: `op-env`, `op-sync-env`). Every other `ops` command is `production: true`.

| Command | Description (commands.json) | Dispatches to |
| --- | --- | --- |
| `setup` | First-time interactive setup | `setup.sh` |
| `preflight` | Validate credentials before provisioning | `preflight-check.sh` |
| `provision` | Create the cloud server | `provision.sh` |
| `harden` | SSH + firewall hardening | `harden.sh` |
| `prepare` | Deploy key, .env on server, GitHub env sync | `prepare-droplet.sh` |
| `cloudflare` | Tunnel + DNS records + R2 bucket | `cloudflare-setup.sh` |
| `update` | Pull latest bundle + images on server, restart | `remote-update.sh` |
| `sync` | Full alignment: local + GitHub + server | handled inline in `ops` itself (not a bundle script) — see below |
| `doctor` | Full production health triage | `doctor.sh` |
| `logs` | Log snapshot (never streams) | `game-log.sh` |
| `live-logs` | Stream all container logs (humans only) | `live-logs.sh` |
| `stats` | System + container stats (`--once` for snapshot) | `live-stats.sh` |
| `live-stats` | Stream system + container stats | `live-stats.sh` |
| `status` | Container status (all, or one service) | `service.sh` |
| `start` | Start a stopped production service | `service.sh` |
| `stop` | Stop a running production service | `service.sh` |
| `restart` | Force-recreate a production service | `service.sh` |
| `rcon` | Run an RCON command (auto local/production) | `rcon.sh` |
| `ssh` | Shell or one-shot command on the server | `ssh.sh` |
| `chunky` | Pre-generation progress | `chunky.sh` |
| `map` | Map sidecar: status, force re-render, threads | `map-render.sh` |
| `wipe-chunk` | Regenerate a corrupted chunk | `wipe-chunk.sh` |
| `reset-seed` | Reset the world (triple-confirmed, backup first) | `reset-seed.sh` |
| `backup` | Run a restic backup now | `backup-now.sh` |
| `github-env-sync` | Push .env to the GitHub environment | `github-env-sync.sh` |
| `op-env` | Restore .env from 1Password | `op-env.sh` |
| `op-sync-env` | Push .env to 1Password | `op-sync-env.sh` |
| `reauth-kuma` | Mint a fresh Kuma session token (browser login) | `kuma-token.sh --browser` |
| `kuma-token` | Paste or fetch a Kuma session token | `kuma-token.sh` |
| `discord-notify` | Send a message via the webhook | `discord-notify.sh` |
| `discord-cleanup` | Prune old bot messages | `discord-cleanup.sh` |
| `discord-pin-sync` | Re-sync pinned info messages | `discord-pin-sync.sh` |
| `teardown` | Destroy cloud resources (DESTRUCTIVE) | `teardown.sh` |

`ops sync` is orchestration, not a single script — it runs, in order: `dev-up.sh down` (ignore failure) → `./dev update` → `github-env-sync.sh --allow-missing` → `./ops update --quick` → `dev-up.sh up`.

## Gap: `commands.json` is missing three real `ops` commands

`shutdown`, `startup`, and `reboot` (power the cloud VPS off / on / restart it) are in the `ops` script's `ALLOWED_COMMANDS` allowlist, dispatch to `server-power.sh`, and are documented in `./ops help`'s "Server control" section — but they have **no entry in `commands.json`**. If you're building a command list from `commands.json` alone (as this reference does, per the house rule that it must be derived from that file), you will miss these three. Verify against `./ops help` or the `ops` script itself if completeness matters for your task.

| Command (not in commands.json) | What it does | Dispatches to |
| --- | --- | --- |
| `shutdown` | Power off the cloud VPS (saves costs) | `server-power.sh shutdown` |
| `startup` | Power on a stopped cloud VPS | `server-power.sh startup` |
| `reboot` | Reboot the cloud VPS | `server-power.sh reboot` |
