# AGENTS.md

> **Read `README.md` first** — it has the quick start, customisation guide, and directory structure. This file covers agent-specific constraints for working in a consumer repo.

This is a **consumer repo** powered by [minecraft-server-template](https://github.com/piprees/minecraft-server-template). The template owns the Docker images, compose files, default configs, and operational scripts — this repo owns only the overlay (custom mods, config overrides, branding) and the `.env` secrets.

For the full platform architecture, fixed decisions, architecture traps, and scripting conventions, see the [template's AGENTS.md](https://github.com/piprees/minecraft-server-template/blob/main/AGENTS.md). Everything there applies here.

## What lives where

| This repo (consumer) | Template repo (platform) |
| --- | --- |
| `overlay/mods-extra.txt` — extra server mods | `config/modrinth-mods.txt` — default mod list |
| `overlay/mods-remove.txt` — mods to exclude | `docker/` — all GHCR image Dockerfiles |
| `overlay/config/` — config overrides | `config/` — default configs |
| `overlay/assets/` — branding | `scripts/` — all operational scripts |
| `.env` — secrets and settings | `docker-compose.yml` — the stack definition |
| `.github/workflows/deploy.yml` — CI caller | `.github/workflows/deploy-reusable.yml` — CI implementation |
| `ops` / `dev` — thin dispatchers | The actual scripts they dispatch to |

**You don't have the template repo checked out.** The operational scripts are pulled as a versioned bundle into `.stack/current/stack/` by `stack-pull.sh`. To read a platform script, either check the bundle cache or fetch from GitHub:

```bash
cat .stack/current/stack/scripts/deploy.sh           # local bundle cache
curl -sL https://raw.githubusercontent.com/piprees/minecraft-server-template/main/scripts/deploy.sh
```

## Production access

```bash
./ops doctor                   # full health triage — START HERE
./ops ssh                      # drop into a shell on the server
./ops ssh 'docker logs mc --tail 50'  # one-shot command
./ops rcon "list"              # RCON command (auto local/production)
./ops chunky                   # Chunky pre-generation status
./ops status                   # all container statuses
```

**Snapshot, never stream.** `docker logs --tail N` — yes. `docker logs -f`, `live-logs.sh` — no; they block forever. Never use unbounded wait loops over SSH.

**RCON silence usually means autopause**, not an outage. The JVM freezes when the server is empty for 10 minutes.

## The environment model

- `.env` is git-ignored. Source of truth: **GitHub `production` environment** + **1Password**.
- Every full CI deploy regenerates the server's `.env` from GitHub secrets. Hand-edits don't survive.
- Adding a secret means updating: `.env.example`, 1Password, the GitHub environment, and the reusable workflow's secrets list.

## CI discipline

Pushing to `main` triggers the caller workflow → reusable workflow. Three deploy tiers (full/infra/pull) picked by diffing changed files. See the [template README](https://github.com/piprees/minecraft-server-template#deploy-to-production) for the tier table.

Before pushing: check no CI run is in progress (`gh run list --limit 3`), check players online if it's a full deploy, batch related changes.

## What you can change here

| Task | Edit | Run |
| --- | --- | --- |
| Add a server mod | `overlay/mods-extra.txt` (+ deps, pinned) | `./dev up` or push |
| Remove a default mod | `overlay/mods-remove.txt` | `./dev up` or push |
| Override a config | `overlay/config/<modname>/file` | Push |
| Change branding | `.env` (BRAND_NAME, MOTD, etc.) + `overlay/assets/` | Push |
| Add a client mod | Not here — PR to the template repo | — |
| Change game rules | Not here — PR to the template repo | — |
| Change permissions | Not here — PR to the template repo | — |

## Safety rules

1. Never commit `.env`, `data/`, or `cache/`.
2. Never disable `ONLINE_MODE` or `ENFORCE_WHITELIST` on production.
3. Back up before mod changes: `./ops backup`.
4. Test locally (`./dev up`) before pushing.
5. `RESTIC_PASSWORD` is unrecoverable — it's in 1Password.
6. Never restart mc directly (`docker restart mc`) — use deploy or `/mc restart` in Discord.
7. Never use unbounded wait loops over SSH — a crashing container will never become healthy.

## Mods

Server mods go in `overlay/mods-extra.txt` (`slug:versionId` per line). Run the dependency checklist from the [template AGENTS.md](https://github.com/piprees/minecraft-server-template/blob/main/AGENTS.md#mods) before adding any mod. All worldgen/dimension mods must be present from chunk zero.
