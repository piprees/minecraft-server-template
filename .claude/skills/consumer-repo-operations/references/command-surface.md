---
title: Command surface
description: The full dev + ops command table, derived from commands.json and cross-checked against the ops dispatch table for which bundle script each command runs.
tags: [dev, ops, commands.json, dispatch, production]
---

# Command surface

This table is **derived from `examples/consumer/commands.json`**, which is the source of truth for the `production` flag per command. It's cross-checked here against the actual dispatch logic in `examples/consumer/ops` (the `script_file` remap and the `ALLOWED_COMMANDS` allowlist) so you can see which bundle script a command really runs. `commands.json` is a description index, not the implementation: if it ever disagrees with the `case` labels in `dev` or the allowlist in `ops`, the scripts win and the JSON is the thing to fix.

## `./dev` — always local

Every `dev` command operates only on the local Docker stack; none touches production. This table is the `dev` block of `commands.json`, which now matches the `case` labels in `examples/consumer/dev` exactly.

| Command        | Description (commands.json)                                   |
| -------------- | ------------------------------------------------------------- |
| `up`           | Start the local dev stack                                     |
| `down`         | Stop the local dev stack                                      |
| `logs`         | Tail the Minecraft server logs                                |
| `rcon`         | Run an RCON command locally                                   |
| `seeds`        | Open the seed viewer — roll, look, try a candidate out, pick one |
| `pack`         | Build the client modpack into ./modpack-dist/                 |
| `pin`          | Re-pin overlay/mods-extra.txt to latest mod builds            |
| `pull`         | Fetch the stack bundle only                                   |
| `update`       | Pull the latest stack bundle + Docker images                  |
| `refresh-config` | Force-refresh platform config defaults (backs up data/config; overlay wins) |
| `clean`        | Delete regenerable state by target (stack, pack, cache, mods, config by default; world/seeds/backups opt-in). `--list`, `--dry-run` |
| `rollback`     | Revert to a previous stack bundle version                     |
| `verify`       | Show where dimension/portal verification lives now (mod tests + /customdim lint) |
| `link`         | Point .stack/current at a platform checkout (default ../minecraft-server-template) |
| `unlink`       | Restore the newest pulled release bundle                      |
| `reset-world`  | Delete the LOCAL world + player data (same set ./ops reset-seed deletes on production) |
| `cache`        | Snapshot Docker images, mod JARs, offline client bundles      |
| `start`        | Start a stopped local service                                 |
| `stop`         | Stop a running local service                                  |
| `restart`      | Force-recreate a local service                                |
| `status`       | Show local container status                                   |
| `doctor`       | Local health check (containers, RCON, seed exit)              |

`start`/`stop`/`restart` accept `mc` locally — `service.sh` skips its production-only refusal when `SERVICE_LOCAL=1` (`service.sh`, `validate_targets`), which `dev` always exports (`dev`, the `start|stop|restart|status)` case).

`link`/`unlink` are the local platform-development path: full workflow in the template repo's `.claude/skills/local-stack-testing/SKILL.md` § Linked local development.

`./ops sync` is the full alignment flow — see the main SKILL.md's update-commands table. It is an `ops` command, not a `dev` one.

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
| `shutdown` | Power off the cloud VPS (saves costs) | `server-power.sh shutdown` |
| `startup` | Power on a stopped cloud VPS | `server-power.sh startup` |
| `reboot` | Reboot the cloud VPS | `server-power.sh reboot` |
| `teardown` | Destroy cloud resources (DESTRUCTIVE) | `teardown.sh` |

`ops sync` is orchestration, not a single script — it runs, in order: `dev-up.sh down` (ignore failure) → `./dev update` → `github-env-sync.sh --allow-missing` → `./ops update --quick` → `dev-up.sh up`.
