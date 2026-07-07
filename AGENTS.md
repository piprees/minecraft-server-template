# AGENTS.md

> **Read `README.md` before any task** - it has the architecture, config model, and how-tos. This file is the agent contract: constraints, traps, and access. If you're running this on a production server with player data, mistakes have real consequences: the world and player progress can't be replaced.

## Fixed decisions (template defaults)

These are the defaults this platform ships with. Consumer repos can override some via the overlay, but understand the consequences first. Each one is load-bearing.

- **Minecraft 1.21.1** (not 1.26.1). Most mods only target it.
- **Fabric** loader. Not Forge, not NeoForge, not Quilt.
- **`ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE`** in production. Both stay on.
- **Cloudflare tunnels HTTP only.** The game port uses a plain DNS A record. Don't tunnel it - the free tier is HTTP-only and fails silently.
- **One Nether overhaul.** Incendium owns the Nether. No competing Nether worldgen mods.
- **Conventional networking.** No VPN, no Tailscale. Friends connect directly.
- **`itzg/minecraft-server` owns the mc container lifecycle.**
- **discord-sync owns all Discord slash commands** (guild-scoped). The dcintegration mod is chat-bridge only; its command feature stays disabled.
- **`.env` on the server is CI-generated**, never the source of truth. See below.

## Production access

Host: `DROPLET_HOST` in `.env`. Server directory: `~/server`. User: `deploy` (passwordless sudo, docker group).

```bash
./ops doctor                                            # full health triage - START HERE when anything seems wrong
./ops rcon "list"                                       # any RCON command (auto local/production)
./ops logs mc --tail 200 --grep ERROR                   # log snapshot (returns immediately)
./ops stats --once                                      # system + container + TPS snapshot
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST '<command>'   # anything else
```

**Snapshot, never stream.** `docker logs --tail N`, `gh run view` - yes. `docker logs -f`, `live-logs.sh`, `gh run watch` in the foreground - no; they block forever.

**RCON silence usually means autopause**, not an outage. The JVM freezes when the server is empty for 10 minutes; `docker ps` still shows healthy. Don't add anything that touches the game port on an interval - it defeats autopause (this killed several Kuma monitor designs; see README).

## The environment model (critical)

- All settings and secrets live in `.env` (git-ignored, 1Password-backed). Source of record: **GitHub `production` environment** (vars + secrets, pushed by `github-env-sync.sh`) + **1Password** (`Dev` vault, `Minecraft Server` item).
- **Every full CI deploy regenerates the server's `.env`** from the GitHub environment secrets. Hand-edits to the server's `.env` are wiped on the next full deploy. Change the source of truth; hand-edit only as a stop-gap and say so.
- Adding a secret means updating **four places**: `.env.example`, 1Password (`op-sync-env.sh` + `config/1password.env`), the GitHub environment (`gh secret set X --env production`), and the secrets list in the reusable workflow if the server needs it at runtime.
- Never commit secrets, world data (`data/`), or `cache/`.

## CI discipline

Pushing to `main` in a consumer repo triggers the caller workflow, which invokes the reusable `deploy-reusable.yml`. Three tiers picked by diffing against the server's deployed commit — see the [deploy modes table](README.md#deploy-to-production). Know which tier your change triggers: `MC_PATTERNS` in the reusable workflow lists everything that causes a full restart (compose file, mod list, synced config dirs, deploy scripts).

**Before pushing:**

1. `gh run list --limit 3` - if a run is in progress, **wait**. Concurrent deploys race (SSH timeouts, broken healthchecks).
2. Check players online if the change triggers a full deploy: `ssh ... 'docker exec -i mc rcon-cli "list"'`. The countdown handles them, but don't restart mid-event.
3. Batch related changes into one commit - each push is a deploy.

**After pushing:**

1. Resolve the run id **by commit sha**, not `--limit 1` (a fresh push races run creation and you'll grab the previous run): `gh run list --workflow deploy.yml --commit <sha> --json databaseId,status` (retry if empty).
2. Poll `gh run view <id> --json status,conclusion` every 30-60s. Never `gh run watch` - it streams and blocks, same rule as log tailing. Full deploys take 3-15 min (longer when the mod list changed - Modrinth re-syncs ~150 JARs).
3. Snapshot server logs for boot errors: `ssh ... 'docker logs mc --tail 50'`.
3. If it fails, **fix it immediately** - a failed deploy can leave containers stopped or configs half-applied. Verify: `docker exec -i mc rcon-cli "list"`.
4. No manual changes on the server while CI runs. Never run `harden.sh` or `deploy.sh` manually while CI is deploying.

**Collision symptom:** `client_loop: send disconnect: Broken pipe` - a concurrent Docker restart delayed the healthcheck. Wait for CI, verify health, re-run.

Note: an in-flight deploy executes the **pre-pull** `deploy.sh` - changes to deploy.sh itself take effect on the *next* deploy after merging.

## Architecture traps (each of these has caused a real incident)

1. **Shared Discord bot token.** dcintegration (in mc) and discord-sync both log in as the same bot. If the mod's `[commands] enabled` flips true in the live `data/config/Discord-Integration.toml`, it bulk-overwrites the command registry on every mc boot and silently deletes `/mc` + `/register`. deploy.sh enforces `enabled = false`; discord-sync purges the global registry at boot as a second line of defence. The repo's `config/dcintegration/config.toml` is an intent doc, **not** the live schema.
2. **Seed container must re-run on deploy.** The `defaults-seed` container lays platform defaults + consumer overlay into shared volumes at boot. On a full deploy the seed container must be recreated so updated defaults/overlay take effect before mc starts. Config/overlay volumes still need the seed to run — this replaces the old bind-mount recreate trap. Nginx configs are still bind-mounted, so nav-proxy and pack-web still need force-recreate to pick up config changes.
3. **mcrcon + threads.** `mcrcon` arms SIGALRM, which raises `signal only works in main thread` under `asyncio.to_thread`. discord-sync.py's `ThreadSafeRcon` replaces it with socket timeouts - use that for any new RCON code in the bot.
4. **Modrinth sync is hash-gated.** The itzg image only re-syncs server mods when the merged mods file changes (tracked in `data/.modrinth-hash`). The seed container's merged mods file must be byte-deterministic (stable ordering) so unchanged inputs produce an unchanged file and fast boots. Editing mod JARs in `data/mods/` directly will be overwritten on the next mod-list change.
5. **Whitelist as a door lock.** deploy.sh clears the whitelist to block joins during restart and restores it after. If a deploy dies mid-way, players may be locked out - the itzg image restores from env on next boot, or restore manually via RCON.
6. **Kuma is config-driven.** `config/uptime-kuma/kuma-config.json` is authoritative; kuma-init re-syncs every deploy and resurrects monitors deleted only via the UI. `KUMA_API_KEY` is a socket.io **session token** (`kuma-token.sh --remote`), not the Prometheus API key.
7. **Chunky markers.** Pre-generation completion is tracked by `data/.chunky-*-complete` marker files. Delete them to force re-generation (e.g. after a border change).
8. **Infra deploys must never recreate mc - `--no-recreate` is load-bearing.** Full deploys create mc WITH the temporary Modrinth override (`MODRINTH_PROJECTS`), then delete the override file - so a plain `docker compose up -d` afterwards sees mc as config-drifted and recreates it with **no countdown, players dropped mid-session** (happened 2026-07-01). The infra step's first `up -d` carries `--no-recreate`; sidecars are updated by the explicit `--force-recreate --no-deps` list. Only deploy.sh (full deploy, after the countdown) may recreate mc.
9. **Raw pack overrides clobber player settings.** Prism re-applies everything in `modpack/overrides/` root on every pack update - a raw `options.txt`/`config/*` there wiped players' keybinds and voice chat settings repeatedly (fixed 2026-07-02). Only `servers.dat` ships raw. All client defaults go under `modpack/overrides/configureddefaults/` (merge/copy-if-missing) and are sourced from a reference Prism instance via `scripts/client-defaults.sh --diff`/`--sync`. New defaults are curated by hand - never bulk-copy the instance config dir (e.g. `NCR-Encryption.json` contains a secret).
10. **The `.deployed` state file can lie.** The deploy tier is picked by diffing against `consumer_sha` in `~/server/.deployed`. If that file records a sha whose deploy never actually completed, every following push downgrades to pull tier and CI goes green while the server runs nothing — the historical "fix" was nuking the server, which only worked because it deleted the stale file. Guards (both in deploy-reusable.yml): state is only written on success, and a state file with no mc container forces a full deploy. If a server ever has `.deployed` but no containers, delete the file or dispatch the workflow manually (manual dispatch always deploys full).
11. **The mod mirror and packwiz index are build output.** `modpack/dist/mods/` and `modpack/dist/packwiz/` are generated and pruned by `build-modpack.sh` - never hand-edit them. Mod downloads route via `mods.DOMAIN/mods/` (a tunnel path-rule straight to pack-web, bypassing nav-proxy) with Modrinth's CDN as the `.mrpack` fallback; launchers hash-verify either source. The packwiz index drives auto-updates for one-click instances on every launch - its `.toml` files must never become edge-cached (they're the update signal; `.toml` isn't in Cloudflare's default cache list, keep it that way). Invariants: `/mods/` in `pack-web.conf` must return a clean 404 (not the site-wide 301-to-homepage) or launchers download HTML instead of falling back, and don't publish this pack ON Modrinth (their upload validation rejects our non-whitelisted mirror URLs).

## Script map

Scripts live in three places. Know which category a script belongs to before editing:

| Category | Where they end up | Examples |
| --- | --- | --- |
| **Bundle** | Stack tarball, run by consumers via `./ops` | `deploy.sh`, `harden.sh`, `provision.sh`, `setup.sh`, `rcon.sh`, `doctor.sh`, `lib.sh` |
| **Image** | Baked into a GHCR image, not run directly | `discord-sync.py` (discord-sync), `idle-tasks.sh` (idle-tasks), `kuma-provision.py` (kuma-init), `build-modpack.sh` (modpack-builder) |
| **Template** | Stays in this repo for platform development | `test-scripts.sh`, `pin-mod-versions.sh`, `build-stack-bundle.sh`, `client-defaults.sh` |

See the [full scripts table in README.md](README.md#scripts) for the complete list.

**Bundle manifest trap:** new bundle scripts must be added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` or they won't be shipped to consumers. CI validates this — `lint.yml` checks that every `.sh` file referenced by `ops` or imported by other bundle scripts exists in the manifest.

## Conventions

**Scripting:** `#!/usr/bin/env bash` + `set -euo pipefail`. Must run on **macOS bash 3.2** (no `declare -A`, no `${var,,}`, no `|&`, no `mapfile`). **No `grep -P`** — macOS BSD grep doesn't support Perl-compatible regexes. Use `grep -oE` (extended regex) or `sed` instead. This has caused multiple CI and runtime failures. Idempotent - safe to run twice. Back up before overwriting (`backup()` in lib.sh → `file.bak.TIMESTAMP`). Support `--non-interactive` for CI. Every script carries a header comment with purpose, context, usage, and gotchas - **keep headers current when changing behaviour**; they're the authoritative reference.

**.env writing:** every value is written single-quoted with embedded `'` mapped to `’`, via `set_env_var`/`env_quote` in lib.sh (the reusable workflow's generator applies the same rule). User-pasted values arriving pre-wrapped in quotes are stripped on input. Never write a raw `KEY=$value` line - an unquoted MOTD once executed itself as a command on a production server.

**Quality gates (run before pushing):** `./scripts/test-scripts.sh --quick` (shellcheck `--severity=warning`, `py_compile`, compose validation). CI's lint.yml runs the same plus yamllint and blocks on failure.

**User-facing strings:** every player/Discord message lives in `config/messages.json` - never hard-code them. British English in docs and strings.

**Docker Compose:** two profiles, `local` and `cloud`. There is exactly ONE env file: `.env`. Every `${VAR}` in `docker-compose.yml` carries an inline fallback (`${VAR:-default}`) so a lean consumer `.env` never interpolates to blank — platform defaults live in the compose file, overrides live in `.env`. New services need: profiles, `mem_limit`, `logging: *default-logging`, and a healthcheck if others depend on it.

**Git:** conventional-commit style, imperative mood (`fix:`, `feat:`, `chore:`).

**Versions (images, actions, tools):** never rely on training data for version numbers — it will be outdated. Before adding or updating any Docker image tag, GitHub Actions step, CLI tool, or library version, look up the latest from a live source: `gh release list --repo <owner/repo> --limit 5`, Context7 (`npx ctx7@latest docs`), or the project's GitHub releases page. This applies to every `image:` tag in `docker-compose.yml`, every `uses:` reference in `.github/workflows/`, and every pinned version in scripts. **Traps:** (1) `gh release list --limit 1` returns the most *recently published* release, not the highest version — backported patch releases (e.g. v5.1.0 published after v6.0.0) will appear first. Always use `--limit 5` and check `isLatest` or sort by semver: `gh release list --limit 5 --json tagName,isLatest --jq '.[] | select(.isLatest) | .tagName'`. (2) GitHub release tags don't always exist on Docker Hub — some projects (e.g. MinIO) publish GitHub releases but stop pushing to Docker Hub. Always verify Docker image availability with `docker pull` or the Docker Hub API before pinning a new tag.

## Mods

Server list: `config/modrinth-mods.txt` (`slug:versionId`, `?` = optional, `datapack:` prefix for datapacks). Client list: `modpack/adventure.mrpack.json` `_clientMods`. All worldgen/dimension mods must be present from chunk zero. Check mod docs on Modrinth or the mod's wiki before editing configs or using commands - **never guess config keys or command syntax**; fetch current docs (`npx ctx7@latest docs`).

**Dependency checklist (mandatory before adding any mod):**

```bash
# 1. List the mod's dependencies for 1.21.1 Fabric
curl -s "https://api.modrinth.com/v2/project/{slug}/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22fabric%22%5D" \
  | python3 -c "import sys,json; [print(f'  {d[\"project_id\"]} ({d[\"dependency_type\"]})') for v in json.load(sys.stdin)[:1] for d in v.get('dependencies',[])]"
# 2. Resolve each project_id to a slug
curl -s "https://api.modrinth.com/v2/project/{project_id}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['slug'], d['title'])"
```

Every required dependency must already be in the pack or added alongside. Libraries (`fabric-api`, `yungs-api`, `moonlight`, `balm`, `lithostitched`, `fabric-language-kotlin`) go in required, never optional. Verify the resolved version actually targets 1.21.1 - Modrinth metadata lies sometimes (e.g. `extra_enchantments` claimed 1.21.1 but shipped 1.21.2 registry keys). Then pin: `./scripts/pin-mod-versions.sh --apply`.

## Config sync

Mod configs in `config/<modname>/` are copied to `data/config/` by **deploy.sh step 8** (every full deploy) — skip-if-exists for bundle defaults, then force-overwrite for consumer overlay. This runs **before mc starts** so mods that auto-generate config on first boot don't create defaults that block the bundle's version. Adding a mod with config means touching **two places**:

1. Config files in `config/<modname>/`
2. The dir added to `MC_PATTERNS` in `.github/workflows/deploy.yml` so changes trigger a full deploy

**Game rules** live in two places that must match: `config/boring_default_game_rules/config.json` (new-world defaults) AND the RCON enforcement block in `scripts/deploy.sh` (existing world). Each has a comment pointing at the other.

## Web surfaces (styles & markup)

The four public pages share one design system but have **no shared stylesheet** - each surface carries its own copy of the styles. `DESIGN.md` (repo root) is the source of truth for tokens (the Quarry palette, type scale, spacing); keep every copy in step with it by hand.

| Surface | Markup + styles live in | Regenerated by | Deploy tier |
| --- | --- | --- | --- |
| pack.DOMAIN | `modpack/template/index.html` (full page, CSS custom properties) | `build-modpack.sh` → `modpack/dist/index.html` (CI after full deploys) | Pull (CI rebuilds pack) |
| mods.DOMAIN | HTML/CSS heredocs in `scripts/check-updates.sh` (`--html`) | mod-checker container on boot + daily 06:00 UTC → `modpack/dist/status.html` (nav-proxy rewrites `/` → `/status.html`) | Infra (force-recreates mod-checker) |
| status.DOMAIN | Uptime Kuma + `customCSS`/`footerText` in `config/uptime-kuma/kuma-config.json`, applied by `scripts/kuma-provision.py` (kuma-init, every deploy) | kuma-init container | Infra (force-recreates kuma-init) |
| map.DOMAIN | BlueMap webapp (upstream); "map sleeping" fallback page is inline HTML in `config/nginx/nav-proxy.conf` | - | Infra (force-recreates nav-proxy) |
| 404 page | Heredoc in `scripts/build-modpack.sh` → `modpack/dist/404.html` | `build-modpack.sh` | Pull |

**The nav bar is injected, not authored per page**: `config/nginx/nav-proxy.conf` `sub_filter`s the nav HTML + CSS into every page - **four near-identical copies** (one per `server` block) plus a fifth in the map-sleeping page. Changing the nav means changing all five.

**Footer version string** (`<PackName> · <pack>-<MC_VERSION>-v<git sha>`): `PACK_NAME` is computed by `build-modpack.sh` from `git rev-parse --short HEAD` and baked into the pack page. The mods page (`check-updates.sh`) and status page (`kuma-provision.py`) run in containers with no git checkout, so they read the *served* pack build instead - `modpack/dist/packwiz/pack.toml` (`version = "<sha>"`), via the dist mount and `http://pack-web/packwiz/pack.toml` respectively. If the status footer shows `vunknown`, pack-web wasn't reachable and no git checkout existed.

**Fonts**: the placeholder uses system fonts throughout. To add a custom display font, place the woff2 in `modpack/template/fonts/` (copied to `dist/fonts/` by the build), update the CSS in each surface, and add a CORS header on `/fonts/` in `pack-web.conf` if Kuma's customCSS loads it cross-origin from pack.DOMAIN.

OG/meta tags are also injected per-domain by `nav-proxy.conf` (`sub_filter '<title>'`). Kuma's own markup can only be restyled via the `customCSS` in `kuma-config.json` - you can't edit its HTML.

## Safety rules

1. Never disable `ONLINE_MODE` or `ENFORCE_WHITELIST` on production.
2. Never tunnel the game port through Cloudflare.
3. Back up before version changes, mod changes, or world migrations: `./scripts/backup-now.sh`.
4. Never overwrite a file without a `file.bak.TIMESTAMP` backup.
5. Never delete `data/` on production. That's the world, and it can't be replaced.
6. Test locally (`local` profile) before deploying.
7. `RESTIC_PASSWORD` is unrecoverable if lost - all backups die with it. It's in 1Password.
8. Never restart `mc` directly on production (`docker restart mc` skips the countdown, kick, save, and whitelist dance) - use `deploy.sh`, or `/mc restart` in Discord which does it properly.
9. `harden.sh` restarts Docker - run at provision time only, never during or near a CI deploy.

## Common tasks

| Task | Edit | Run |
| --- | --- | --- |
| Add a server mod (consumer) | `overlay/mods-extra.txt` (+ deps, pinned) | `./dev up` or push to `main` |
| Add a default server mod (platform) | `config/modrinth-mods.txt` (+ deps, pinned) | Push, cut release |
| Add a client mod | `modpack/adventure.mrpack.json` | Push (CI rebuilds `.mrpack`) |
| Change a game rule | `config/boring_default_game_rules/config.json` + `scripts/deploy.sh` | Push (full deploy) |
| Change claim settings | `config/openpartiesandclaims/openpartiesandclaims-server.toml` | Push (full deploy) |
| Change a player/Discord message | `config/messages.json` | Push |
| Change a web page's look | See [Web surfaces](#web-surfaces-styles--markup); tokens in `DESIGN.md` | Push (tier varies by file) |
| Add/remove a player | - | Discord `/register` + role, or `docker exec -i mc rcon-cli "whitelist add NAME"` |
| Grant extra claims | - | `docker exec -i mc rcon-cli "lp user NAME permission set xaero.pac_max_claims N"` |
| Trigger a backup | - | `./ops backup` |
| Restore from backup | - | [README → Backups](README.md#backups) |
| Restart a sidecar | - | `./ops restart <name>` (force-recreates; refuses mc) |
| Check mod updates | - | `./scripts/check-updates.sh` (weekly PR: `gh workflow run mod-updates.yml`) |
| Update MC version | `.env` + re-pin | Big job — [README → Update Minecraft version](README.md#update-minecraft-version) |
| Manual deploy | - | `ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'cd ~/server && ./scripts/deploy.sh --pull --non-interactive'` |
| Validate scripts | - | `./scripts/test-scripts.sh --quick` |
