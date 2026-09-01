# AGENTS.md

> **Read `README.md` first** — quick start, customisation, directory structure. This file is the agent contract. This repo owns the overlay (extra mods, config overrides, branding) and `.env`; the template — [minecraft-server-template](https://github.com/piprees/minecraft-server-template) — owns the images, compose files, default configs, and scripts. Its [AGENTS.md](https://github.com/piprees/minecraft-server-template/blob/main/AGENTS.md) applies here in full, and every known trap lives in its [TROUBLESHOOTING.md](https://github.com/piprees/minecraft-server-template/blob/main/TROUBLESHOOTING.md), cited by id (`#t14`).

## What lives where

| This repo (consumer) | Template repo (platform) |
| --- | --- |
| `overlay/` — `mods-extra.txt`, `mods-remove.txt`, `config/`, `assets/` | `config/` — default configs + `modrinth-mods.txt`; `docker/` — GHCR images |
| `.env` — secrets and settings | `docker-compose.yml`, `scripts/` — the stack and its operational scripts |
| `.github/workflows/deploy.yml` — CI caller | `.github/workflows/deploy-reusable.yml` — CI implementation |
| `ops` / `dev` — thin dispatchers | The scripts they dispatch to |

**There is no template checkout here.** Platform scripts arrive as a versioned bundle:

```bash
cat .stack/current/stack/scripts/deploy.sh           # local bundle cache
curl -sL https://raw.githubusercontent.com/piprees/minecraft-server-template/main/scripts/deploy.sh
```

## Keeping up to date

`STACK_VERSION` in `.env` (usually `v5`) resolves to the latest matching release.

| Command | Does |
| --- | --- |
| `./dev update` | local: pull bundle + images, refresh platform-owned scaffold files |
| `./ops sync` | local down → update → env sync to GitHub → server update → local up |
| `./ops update` | production only: pull bundle + images, full redeploy |

`./dev update` overwrites `dev`, `ops`, `.env.example`, `.gitignore`, this `AGENTS.md`, `commands.json`, and `.github/workflows/{deploy,update,server-power}.yml` — platform-owned, don't customise them. `README.md` (copied only when missing) and `overlay/` are yours. The puller ships in the bundle (`.stack/current/stack/scripts/stack-pull.sh`); a top-level `stack-pull.sh` is old scaffold, and `./dev update` deletes it.

## Config changes need `./dev refresh-config`

`data/config/` is a bind mount seeded **skip-if-exists**. A file that already
exists is never overwritten, so a changed `overlay/config/` file — or a config
that arrived in a new bundle — does NOT reach the server on `./dev up` or
`./dev update` alone. The container boots green on the old file.

```bash
./dev refresh-config   # copies config in, backs up to data/config.bak.<stamp>
./dev up               # restart so the mod re-reads it
```

Verify the served file, never the source: `docker exec mc cat
/data/config/<path>`. This is the single most common way a local test passes
against config that is not the config under test.

## What you change here

- **Server mods:** `overlay/mods-extra.txt` (`slug:versionId` per line), removals in `overlay/mods-remove.txt`. Run the mandatory dependency checklist in the [template AGENTS.md § Mods](https://github.com/piprees/minecraft-server-template/blob/main/AGENTS.md#mods) first. All worldgen/dimension mods must be present from chunk zero. `./dev up` or push.
- **Client mods:** `overlay/modpack/manifest.json` (`add.required` / `add.optional` / `remove`), existing catalogue slugs only — a mod new to the ecosystem needs a template PR first. Patch schema: [`overlay/modpack/README.md`](overlay/modpack/README.md).
- **Never hand-place or delete anything in `data/mods/`.** `./dev up` and the deploy install the bundle's `stack/local-mods/` jars and prune what they can't account for.
- **Config overrides:** `overlay/config/<path>` mirrors the platform's `config/` path and replaces that file. **Branding:** `.env` (`BRAND_NAME`, `MOTD`, …) plus `overlay/assets/`.
- **Custom dimensions** are created at boot from `config/custom-dimensions/dimensions/` — no RCON. Override per file in `overlay/config/custom-dimensions/dimensions/<slug>.json`: a top-level `"overrides"` object deep-merges over the default, a file without one replaces it, `{}` disables that dimension, a new filename adds one (namespaced by `BRAND_SLUG`).
- **World reset:** `./ops reset-seed <seed>` (triple-confirmed, backs up first), after setting `SEED` in `.env`.
- **Not here — PR to the template repo:** game rules, permissions, platform dimension defaults.
- **Platform mod development:** `./dev link ../minecraft-server-template` once, then rebuild and `./dev up` — the link symlinks the checkout's built jars. `./dev unlink` restores the release bundle.

## Production access

```bash
./ops doctor                          # full health triage — START HERE
./ops ssh                             # shell; ./ops ssh '<command>' for one-shot
./ops rcon "list"                     # RCON (auto local/production)
./ops status                          # container statuses; ./ops chunky for pre-generation
```

**Snapshot, never stream**: `docker logs --tail N` yes; `docker logs -f` and `live-logs.sh` no. Never use an unbounded wait loop over SSH — a crashing container never becomes healthy. **RCON silence usually means autopause**, not an outage: the JVM freezes when the server is empty for 10 minutes.

**Crash-loop triage:** boot failures (mod downloads, mixin apply) die before `data/logs/latest.log` exists, so read the container:

```bash
./ops ssh 'docker inspect mc --format "RestartCount={{.RestartCount}} Health={{if .State.Health}}{{.State.Health.Status}}{{end}}"'
./ops ssh 'docker logs mc --tail 80'      # init + mixin errors live here
```

A RestartCount above 0 is an unexplained crash; local tick-loop crashes land in `data/crash-reports/`. `Mixin apply ... failed` means a broken mod jar — fix it with `./ops update`, not by removing mods.

## Environment and CI

- `.env` is git-ignored. Source of truth: the GitHub `production` environment + 1Password. Every full deploy regenerates the server's `.env`; hand-edits don't survive. A new secret goes in four places: `.env.example`, 1Password, the GitHub environment, the reusable workflow's secrets list.
- Pushing to `main` picks a tier: `STACK_VERSION` resolving to a release newer than the running bundle → full; `overlay/config/` or mod lists → full; other `overlay/` → infra; everything else → pull. **Any push after a platform release lands rolls that release out**, even a docs-only one. [Tier table](https://github.com/piprees/minecraft-server-template#deploy-to-production).
- Before pushing: check no CI run is in progress (`gh run list --limit 3`), check players online if it's a full deploy, batch related changes.

## Safety rules

1. Never commit `.env`, `data/`, or `cache/`.
2. Never disable `ONLINE_MODE` or `ENFORCE_WHITELIST` on production.
3. Back up before mod changes: `./ops backup`.
4. Test locally (`./dev up`) before pushing.
5. `RESTIC_PASSWORD` is unrecoverable — it's in 1Password.
6. Never restart mc directly (`docker restart mc`) — use the deploy, or `/mc restart` in Discord.
7. Never use unbounded wait loops over SSH.
