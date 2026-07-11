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

## Keeping up to date

The platform ships as versioned releases; `STACK_VERSION` in `.env` (usually `v2`) resolves to the latest matching release.

```bash
./dev update    # pull the latest stack bundle + Docker images (local only)
./dev sync      # everything: local down → update → env sync to GitHub → server update → local up
./ops update    # update the production server only (pull bundle, images, full redeploy)
```

`./dev update` also refreshes this repo's `dev`, `ops`, `.env.example`, `.gitignore`, this `AGENTS.md`, and the CI workflows from the bundle — those files are platform-owned and will be overwritten; don't customise them. `README.md` and `overlay/` are yours and are never touched.

The bundle puller lives in the bundle itself (`.stack/current/stack/scripts/stack-pull.sh`); `./dev pull` invokes it (with a minimal bootstrap inside `dev` for the first-ever pull), and `ops update` ships it to the production server. If you still have a top-level `stack-pull.sh`, it's from an older scaffold — `./dev update` removes it.

## In-house platform mods & custom dimensions

The bundle ships platform-built Fabric mods in `stack/local-mods/` (e.g. `customdimensions.jar`). They're installed into `data/mods/` automatically — by `./dev up` locally and by the deploy on production. **Never hand-edit `data/mods/`**: it's managed (Modrinth sync + bundle installs) and your changes will be overwritten.

The custom-dimensions mod creates the platform's `adventure:*` dimensions at deploy time from the bundle's `config/dimensions.txt`, links their portals, and persists everything to `data/config/multiverse_config.json`. That file is state, not config — the deploy recreates it idempotently; don't hand-edit it. Ops commands (RCON): `dimension create|load|delete`, `portal link|delete`. Full grammar and architecture: [mods/AGENTS.md in the template](https://github.com/piprees/minecraft-server-template/blob/main/mods/AGENTS.md).

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

**Crash-loop triage:** boot failures (mod downloads, mixin apply errors) die before the game log exists, so `data/logs/latest.log` will look fine while the container loops. Check the container itself:

```bash
./ops ssh 'docker inspect mc --format "RestartCount={{.RestartCount}} Health={{if .State.Health}}{{.State.Health.Status}}{{end}}"'
./ops ssh 'docker logs mc --tail 80'      # init + mixin errors live here, not in data/logs/
ls data/crash-reports/ 2>/dev/null        # tick-loop crashes land here (local)
```

A RestartCount above 0 is a crash you haven't explained yet. `Mixin apply ... failed` in the docker log means a broken mod jar — usually fixed by `./ops update` pulling the current bundle, not by removing mods.

## The environment model

- `.env` is git-ignored. Source of truth: **GitHub `production` environment** + **1Password**.
- Every full CI deploy regenerates the server's `.env` from GitHub secrets. Hand-edits don't survive.
- Adding a secret means updating: `.env.example`, 1Password, the GitHub environment, and the reusable workflow's secrets list.

## CI discipline

Pushing to `main` triggers the caller workflow → reusable workflow. Three deploy tiers (full/infra/pull). The tier is picked by (1) resolving your `STACK_VERSION` pin to a concrete platform release and comparing it against the bundle the server is running — a new release means a full deploy — and (2) diffing your changed files (`overlay/config/` and mod lists → full; other `overlay/` → infra; everything else → pull). **Any push after a platform release lands rolls that release out**, even if the push itself only touches docs. See the [template README](https://github.com/piprees/minecraft-server-template#deploy-to-production) for the tier table.

Before pushing: check no CI run is in progress (`gh run list --limit 3`), check players online if it's a full deploy, batch related changes.

## What you can change here

| Task | Edit | Run |
| --- | --- | --- |
| Add a server mod | `overlay/mods-extra.txt` (+ deps, pinned) | `./dev up` or push |
| Remove a default mod | `overlay/mods-remove.txt` | `./dev up` or push |
| Override a config | `overlay/config/<modname>/file` | Push |
| Change branding | `.env` (BRAND_NAME, MOTD, etc.) + `overlay/assets/` | Push |
| Reset the world / new seed | `.env` (`SEED`) | `./ops reset-seed <seed>` (triple-confirmed, backs up first) |
| Update to the latest platform | — | `./dev sync` |
| Add a client mod | Not here — PR to the template repo | — |
| Change game rules | Not here — PR to the template repo | — |
| Change permissions | Not here — PR to the template repo | — |
| Add/change custom dimensions | Not here — PR to the template repo (`config/dimensions.txt` + [mods/AGENTS.md](https://github.com/piprees/minecraft-server-template/blob/main/mods/AGENTS.md)) | — |

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
