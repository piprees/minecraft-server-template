# AGENTS.md

> **Read `README.md` before any task** - it has the architecture, config model, and how-tos. This file is the agent contract: constraints, traps, and access. If you're running this on a production server with player data, mistakes have real consequences: the world and player progress can't be replaced.

## Quick reference (read the full file for anything non-trivial)

- Run `./scripts/test-scripts.sh --quick` before pushing.
- Mod changes: dependency checklist is MANDATORY (see [§ Mods](#mods)).
- Never `gh release create` — use `gh workflow run release.yml -f version=vX.Y.Z`.
- Never stream logs or use unbounded loops — snapshot only (`--tail N`, `gh run view --json`).
- `.env` on the server is CI-generated. Change GitHub secrets, not the server file.
- See [§ Common tasks](#common-tasks) for the task → file → command lookup table.

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

Pushing to `main` in a consumer repo triggers the caller workflow, which invokes the reusable `deploy-reusable.yml`. Tier detection is two-stage: (1) the symbolic `STACK_VERSION` pin is **resolved to a concrete release tag** and compared against the bundle the server actually runs (`readlink .stack/current`) — any difference forces a full deploy (this is what rolls platform releases out; symbolic-vs-symbolic comparison historically never fired); (2) consumer files are diffed against the server's deployed commit — `FULL_PATTERNS` in the reusable workflow lists the consumer paths that cause a full restart (`overlay/config/`, mod lists). See the [deploy modes table](README.md#deploy-to-production).

**Before pushing:**

1. `gh run list --limit 3` - if a run is in progress, **wait**. Concurrent deploys race (SSH timeouts, broken healthchecks).
2. Check players online if the change triggers a full deploy: `ssh ... 'docker exec -i mc rcon-cli "list"'`. The countdown handles them, but don't restart mid-event.
3. Batch related changes into one commit - each push is a deploy.

**After pushing:**

1. Resolve the run id **by commit sha**, not `--limit 1` (a fresh push races run creation and you'll grab the previous run): `gh run list --workflow deploy.yml --commit <sha> --json databaseId,status` (retry if empty).
2. Poll `gh run view <id> --json status,conclusion` every 30-60s. Never `gh run watch` - it streams and blocks, same rule as log tailing. Full deploys take 3-15 min (longer when the mod list changed - Modrinth re-syncs ~150 JARs).
3. Snapshot server logs for boot errors: `ssh ... 'docker logs mc --tail 50'`.
4. If it fails, **fix it immediately** - a failed deploy can leave containers stopped or configs half-applied. Verify: `docker exec -i mc rcon-cli "list"`.
5. No manual changes on the server while CI runs. Never run `harden.sh` or `deploy.sh` manually while CI is deploying.

**Collision symptom:** `client_loop: send disconnect: Broken pipe` - a concurrent Docker restart delayed the healthcheck. Wait for CI, verify health, re-run.

Note: an in-flight deploy executes the **pre-pull** `deploy.sh` - changes to deploy.sh itself take effect on the _next_ deploy after merging.

## Cutting a release (platform repo only)

See [docs/releasing.md](docs/releasing.md) for the full procedure, compatibility promise, and consumer impact.

**Key constraints for agents:**

- **Never use `gh release create` directly** — use `gh workflow run release.yml -f version=vX.Y.Z`.
- **Never push to `main` while release.yml is in progress.** The image builds (publish.yml) use concurrency group `publish-${{ github.ref }}` with `cancel-in-progress: true`, and a push-triggered build shares the group with the release's builds — the push CANCELS the release's in-flight image builds mid-push, leaving the release published but its version-tagged images missing (2026-07-13: a docs push cancelled the discord-sync 2.14.0 image; production pulls version tags via `IMAGE_TAG="${STACK_VERSION#v}"`). Recovery: `gh run rerun <release-run-id> --failed` rebuilds only the cancelled jobs — the release/tag themselves are unaffected if the "Build bundle and publish release" job succeeded.
- Releases are **immutable**: assets can't be attached after publish, so `gh release create` produces a broken release with no bundle.
- A failed or deleted release **burns its tag forever** (immutable releases reserve the tag name permanently). Fix the cause and cut the NEXT patch version.
- If a release ships without a bundle: `gh release delete vX.Y.Z --yes && git push origin :refs/tags/vX.Y.Z`, then re-cut.

## Architecture traps (each of these has caused a real incident)

1. **Shared Discord bot token.**
   - Symptom: Discord slash commands (`/mc`, `/register`) disappear after mc restart.
   - Cause: dcintegration (in mc) and discord-sync both log in as the same bot. If the mod's `[commands] enabled` flips true in the live `data/config/Discord-Integration.toml`, it bulk-overwrites the command registry on every mc boot and silently deletes `/mc` + `/register`. deploy.sh enforces `enabled = false`; discord-sync purges the global registry at boot as a second line of defence. The repo's `config/dcintegration/config.toml` is an intent doc, **not** the live schema.
2. **Seed container must re-run on deploy.**
   - Symptom: Config or mod changes don't take effect after deploy; mc boots with stale files.
   - Cause: The `defaults-seed` container lays platform defaults + consumer overlay into shared volumes at boot. On a full deploy the seed container must be recreated so updated defaults/overlay take effect before mc starts. Config/overlay volumes still need the seed to run — this replaces the old bind-mount recreate trap. Nginx configs are still bind-mounted, so nav-proxy and pack-web still need force-recreate to pick up config changes.
3. **mcrcon + threads.**
   - Symptom: `signal only works in main thread` exception in discord-sync logs.
   - Cause: `mcrcon` arms SIGALRM, which raises under `asyncio.to_thread`. discord-sync.py's `ThreadSafeRcon` replaces it with socket timeouts - use that for any new RCON code in the bot.
4. **Mod sync is seed-resolved, never API-at-boot.**
   - Symptom: mc crash-loops with Modrinth `429 Too Many Requests` on every restart.
   - Cause: `MODRINTH_PROJECTS` is gone: it made itzg re-resolve ~160 versions through api.modrinth.com on every sync boot and 429-crash-looped mc whenever the mod list changed. The seed container's `resolve-mods.py` resolves pins to direct URLs (cached forever in the stack-mods volume — version IDs are immutable) and mc uses `MODS_FILE`/`DATAPACKS_FILE`, downloading only files missing from `data/mods/`. Stale jars are pruned by deploy.sh/dev-up.sh against `mods-manifest.txt` — in-house `local-mods/` jars are exempt. Hand-added jars in `data/mods/` will be pruned; ship them via `overlay/mods-extra.txt` or `local-mods/` instead. A failed required resolution fails the seed and blocks the boot loudly.
5. **Whitelist as a door lock.**
   - Symptom: Players can't join after a failed deploy; "You are not whitelisted" despite having the role.
   - Cause: deploy.sh clears the whitelist to block joins during restart and restores it after. If a deploy dies mid-way, players may be locked out - the itzg image restores from env on next boot, or restore manually via RCON.
6. **Kuma is config-driven.**
   - Symptom: Monitors deleted in the Kuma UI reappear after deploy.
   - Cause: `config/uptime-kuma/kuma-config.json` is authoritative; kuma-init re-syncs every deploy and resurrects monitors deleted only via the UI. `KUMA_API_KEY` is a socket.io **session token** (`kuma-token.sh --remote`), not the Prometheus API key.
7. **Kuma auth is human-gated — do NOT debug it by retrying logins.**
   - Symptom: `authIncorrectCreds` errors in kuma-init/kuma-provision logs. The admin account has 2FA, so password-only logins ALWAYS fail `authIncorrectCreds` regardless of the password; the session token is the only headless path, and minting one requires a TOTP code (`./ops reauth-kuma`, interactive). Repeated login attempts risk Kuma's rate limit and the host's fail2ban — a locked account means rebuilding Kuma. If automated Kuma calls start failing `authIncorrectCreds`: (1) STOP calling; (2) the token has been lost/expired — the usual cause is a CI full deploy regenerating the server `.env` from GitHub secrets while the `KUMA_API_KEY` secret is empty (audited 2026-07-11: the consumer `.env` had no `KUMA_API_KEY` line, so `github-env-sync.sh` never pushed one); (3) recovery is human-only: `./ops reauth-kuma`, add the token to the consumer `.env`, `./ops github-env-sync` so CI deploys stop wiping it. A maintenance window stuck open can be cleared without auth: `docker exec uptime-kuma sqlite3 /app/data/kuma.db "DELETE FROM maintenance WHERE title='Deploy in progress';"` then restart uptime-kuma. **Kuma has 2FA: password-only logins always fail `authIncorrectCreds`** — the password isn't wrong, it's missing a TOTP; the session token is the only headless auth path. **The token must live in the GitHub `production` secret `KUMA_API_KEY`**, not just `.env` — every full CI deploy regenerates the server `.env` from GitHub secrets, and an empty secret wipes a hand-installed token (2026-07-11: this left a deploy maintenance window open for hours). Never retry Kuma logins in a loop — it rate-limits auth and the host runs fail2ban. A stuck "Deploy in progress" window can be cleared without creds: `docker exec uptime-kuma sqlite3 /app/data/kuma.db "DELETE FROM maintenance WHERE title='Deploy in progress';"` then restart uptime-kuma.
8. **Chunky markers.**
   - Symptom: Chunky re-generates chunks that were already done.
   - Cause: Pre-generation completion is tracked by `data/.chunky-*-complete` marker files. Delete them to force re-generation (e.g. after a border change).
9. **Infra deploys must never recreate mc - `--no-recreate` is load-bearing.**
   - Symptom: Players dropped mid-session with no countdown or warning after a sidecar-only change.
   - Cause: Full deploys create mc WITH the temporary Modrinth override (`MODRINTH_PROJECTS`), then delete the override file - so a plain `docker compose up -d` afterwards sees mc as config-drifted and recreates it with **no countdown, players dropped mid-session** (happened 2026-07-01). The infra step's first `up -d` carries `--no-recreate`; sidecars are updated by the explicit `--force-recreate --no-deps` list. Only deploy.sh (full deploy, after the countdown) may recreate mc.
10. **Raw pack overrides clobber player settings.**
    - Symptom: Players' keybinds, voice chat settings, or video options reset after a pack update.
    - Cause: Prism re-applies everything in `modpack/overrides/` root on every pack update - a raw `options.txt`/`config/*` there wiped players' keybinds and voice chat settings repeatedly (fixed 2026-07-02). Only `servers.dat` ships raw. All client defaults go under `modpack/overrides/configureddefaults/` (merge/copy-if-missing) and are sourced from a reference Prism instance via `scripts/client-defaults.sh --diff`/`--sync`. New defaults are curated by hand - never bulk-copy the instance config dir (e.g. `NCR-Encryption.json` contains a secret).
11. **The `.deployed` state file can lie.**
    - Symptom: CI deploys go green but the server isn't actually running the new code. The consumer-diff half of tier detection uses `consumer_sha` in `~/server/.deployed`. If that file records a sha whose deploy never actually completed, every following push downgrades to pull tier and CI goes green while the server runs nothing — the historical "fix" was nuking the server, which only worked because it deleted the stale file. Guards (both in deploy-reusable.yml): state is only written on success, a state file with no mc container forces a full deploy, and the stack comparison never trusts the state file at all — it reads `readlink .stack/current` on the server. If a server ever has `.deployed` but no containers, delete the file or dispatch the workflow manually (manual dispatch always deploys full).
12. **Hand-patched shared volumes are reverted by every seed run.**
    - Symptom: a local test of unreleased seed-image content (mod list, nginx templates) passes, but the change was never actually live — the "pass" came through the old config.
    - Cause: `stack-config`/`stack-mods` volume contents come from the **defaults-seed image** (rebuilt only by CI on push), and the one-shot `seed` re-runs on any `compose up` without `--no-deps`, silently restoring image defaults over hand-patches. To test pre-release seed content locally: patch the volume, then recreate ONLY the consuming service with `--force-recreate --no-deps` (or plain `docker restart mc`), and verify the **rendered** state (`docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf`), never just the patch (2026-07-13: a nav-proxy upstream change "passed" while proxying to the old upstream).
13. **BlueMap is a standalone sidecar (since v2.14.0) — there is no `bluemap` RCON.**
    - Symptom: `bluemap ...` RCON commands return "Unknown command"; agents look for map problems in mc.
    - Cause: the BlueMap mod was removed; the `bluemap` container renders and serves the map (watches region files, stays up during autopause). Diagnose with `docker logs bluemap`, restart with `docker restart bluemap`, force re-renders with `./ops map render`. Map configs use the v5.11+ `render-mask` format (bounds inside `render-mask: [ { ... } ]`); any future BlueMap version bump must check the release changelogs for config-format changes (`gh api repos/BlueMap-Minecraft/BlueMap/releases`). deploy.sh deletes `data/bluemap/web/index.html` on every deploy so the sidecar regenerates the webapp.
    - Verification timing: the sidecar takes ~5 min after (re)start to load all ~79 maps before update checks run — a quiet log or no new tiles inside that window is NOT a failure; poll for tile mtimes with a generous cap. On **macOS Docker**, bind-mount file events are unreliable, so the `-u` watcher may never fire locally — validate change pickup with `docker restart bluemap` (the boot `-r` pass detects changed regions), and trust the watcher on Linux production.
14. **The mod mirror and packwiz index are build output.**
    - Symptom: Launchers download HTML instead of mod JARs; packwiz auto-update serves stale mods.
    - Cause: `modpack/dist/mods/` and `modpack/dist/packwiz/` are generated and pruned by `build-modpack.sh` - never hand-edit them. Mod downloads route via `mods.DOMAIN/mods/` (a tunnel path-rule straight to pack-web, bypassing nav-proxy) with Modrinth's CDN as the `.mrpack` fallback; launchers hash-verify either source. The packwiz index drives auto-updates for one-click instances on every launch - its `.toml` files must never become edge-cached (they're the update signal; `.toml` isn't in Cloudflare's default cache list, keep it that way). Invariants: `/mods/` in `pack-web.conf` must return a clean 404 (not the site-wide 301-to-homepage) or launchers download HTML instead of falling back, and don't publish this pack ON Modrinth (their upload validation rejects our non-whitelisted mirror URLs).

## Known issues (watch list)

- **c2me `TheChunkSystem` ConcurrentModificationException** (`Error executing task on Chunk source main thread executor for <dim>` … `TheChunkSystem.lambda$onItemUpgrade$0`): c2me 0.4.0-alpha's chunk-system rewrite races vanilla's entity manager during heavy multi-dimension chunk activity (boot world creation, BlueMap initial loads, forceloads). Non-fatal — the executor catches it — but bursts correlate with degraded TPS during boots. Do NOT filter it from logs (real error, upstream candidate). If it starts crashing servers, the module can be bypassed only by removing c2me; revisit on c2me updates.

## Script map

Scripts live in three places. Know which category a script belongs to before editing:

| Category | Where they end up | Examples |
| --- | --- | --- |
| **Bundle** | Stack tarball, run by consumers via `./ops` | `deploy.sh`, `harden.sh`, `provision.sh`, `setup.sh`, `rcon.sh`, `doctor.sh`, `lib.sh` |
| **Image** | Baked into a GHCR image, not run directly | `discord-sync.py` (discord-sync), `idle-tasks.sh` (idle-tasks), `kuma-provision.py` (kuma-init), `build-modpack.sh` (modpack-builder) |
| **Template** | Stays in this repo for platform development | `test-scripts.sh`, `pin-mod-versions.sh`, `build-stack-bundle.sh`, `client-defaults.sh` |

See the [full scripts table in README.md](README.md#scripts) for the complete list.

**Bundle manifest trap:** new bundle scripts must be added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` or they won't be shipped to consumers. CI validates this — `lint.yml` checks that every `.sh` file referenced by `ops` or imported by other bundle scripts exists in the manifest.

**Consumer scaffold sync trap:** files in `examples/consumer/` are copied to consumer repos by `./dev update`. The sync list lives in the `update)` case of `examples/consumer/dev` — executable entry points (`dev`, `ops`), non-executable files (`.env.example`, `.gitignore`, `AGENTS.md`), and workflows (`.github/workflows/*.yml`). **When adding a new file to `examples/consumer/`, add it to the sync list too** or existing consumers will never receive it. `README.md` and `overlay/` are deliberately excluded — those are consumer-owned content. The bundle puller is NOT a scaffold file: it ships in the bundle (`scripts/stack-pull.sh`); `dev` carries only a minimal inline bootstrap for the first-ever pull.

## Conventions

**Scripting:** `#!/usr/bin/env bash` + `set -euo pipefail`. Must run on **macOS bash 3.2** (no `declare -A`, no `${var,,}`, no `|&`, no `mapfile`). **No `grep -P`** — macOS BSD grep doesn't support Perl-compatible regexes. Use `grep -oE` (extended regex) or `sed` instead. This has caused multiple CI and runtime failures. Idempotent - safe to run twice. Back up before overwriting (`backup()` in lib.sh → `file.bak.TIMESTAMP`). Support `--non-interactive` for CI. Every script carries a header comment with purpose, context, usage, and gotchas - **keep headers current when changing behaviour**; they're the authoritative reference.

**.env writing:** every value is written single-quoted with embedded `'` mapped to `’`, via `set_env_var`/`env_quote` in lib.sh (the reusable workflow's generator applies the same rule). User-pasted values arriving pre-wrapped in quotes are stripped on input. Never write a raw `KEY=$value` line - an unquoted MOTD once executed itself as a command on a production server.

**Quality gates (run before pushing):** `./scripts/test-scripts.sh --quick` (shellcheck `--severity=warning`, `py_compile`, compose validation). CI's lint.yml runs the same plus yamllint and blocks on failure.

**User-facing strings:** every player/Discord message lives in `config/messages.json` - never hard-code them. British English in docs and strings.

**Docker Compose:** two profiles, `local` and `cloud`. There is exactly ONE env file: `.env`. Every `${VAR}` in `docker-compose.yml` carries an inline fallback (`${VAR:-default}`) so a lean consumer `.env` never interpolates to blank — platform defaults live in the compose file, overrides live in `.env`. New services need: profiles, `mem_limit`, `logging: *default-logging`, and a healthcheck if others depend on it.

**Git:** conventional-commit style, imperative mood (`fix:`, `feat:`, `chore:`).

**Versions (images, actions, tools):** never rely on training data for version numbers — it will be outdated. Before adding or updating any Docker image tag, GitHub Actions step, CLI tool, or library version, look up the latest from a live source: `gh release list --repo <owner/repo> --limit 5`, Context7 (`npx ctx7@latest docs`), or the project's GitHub releases page. This applies to every `image:` tag in `docker-compose.yml`, every `uses:` reference in `.github/workflows/`, and every pinned version in scripts. **Traps:** (1) `gh release list --limit 1` returns the most _recently published_ release, not the highest version — backported patch releases (e.g. v5.1.0 published after v6.0.0) will appear first. Always use `--limit 5` and check `isLatest` or sort by semver: `gh release list --limit 5 --json tagName,isLatest --jq '.[] | select(.isLatest) | .tagName'`. (2) GitHub release tags don't always exist on Docker Hub — some projects (e.g. MinIO) publish GitHub releases but stop pushing to Docker Hub. Always verify Docker image availability with `docker pull` or the Docker Hub API before pinning a new tag.

## In-house mods

`mods/` contains Fabric mod projects built and maintained as part of this platform. Each subdirectory is a standalone Gradle project targeting MC 1.21.1 + Java 21 (pinned via `mods/mise.toml`). See `mods/AGENTS.md` for the full mod development contract — mixin conventions, the verification loop, and the custom-dimensions architecture.

| Mod | Dir | Purpose |
| --- | --- | --- |
| custom-dimensions | `mods/custom-dimensions/` | Runtime dimension creation, custom portal frames, coordinate scaling, bidirectional travel |

**Delivery pipeline (never hand-copy jars into consumer repos, never publish to Modrinth):** `release.yml` builds each mod and stages the **remapped** jar from `build/libs/` (never the `-dev` jar from `build/devlibs/`) as `dist/local-mods/<mod>.jar`; `build-stack-bundle.sh` packs it into the bundle as `stack/local-mods/`; from there `deploy.sh` (production, step 8b — while mc is stopped, before it starts) and `dev-up.sh` (local, every `./dev up`) copy `stack/local-mods/*.jar` into `data/mods/`. Both `mod-build.yml` and `release.yml` verify the jar contains compiled classes and the Loom-generated refmap — an unremapped or empty jar boots as a production crash loop, and Gradle will happily report BUILD SUCCESSFUL while producing one.

**Iterate locally before releasing.** A release→deploy→sync cycle costs ~10–15 minutes per attempt and restarts production; the local loop costs ~1 minute and catches almost everything. Follow the [verification loop in mods/AGENTS.md](mods/AGENTS.md#verification-loop): build → install into the local consumer's `data/mods/` → restart local mc → exercise via RCON → soak time-based paths. Only cut a release once the local loop passes end to end.

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

**Version holds:** `_clientMods.holds` in `modpack/adventure.mrpack.json` maps slug → reason for mods that must NOT be re-pinned (e.g. Xaero's World Map held at 1.41.2 because 1.42.0 removed the `Waypoint` fields maplink's mixin targets — newer crashes every client on map open). `pin-mod-versions.sh` and the weekly `mod-updates.yml` respect holds. **Never bump a held slug manually**; remove the hold only when its stated blocker clears, and hold era-pairs together (minimap + world map).

**Resource/shader packs**: `_resourcePacks.packs` / `_shaderPacks.packs` in the manifest - plain slug (primary file) or `{slug, files: [...]}` to also fetch named companion micropacks from the same version. `build-modpack.sh` resolves each to the newest `MC_VERSION`-tagged Modrinth version (falling back to newest upload if untagged). Resource packs are **enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt` (`resourcePacks:` array, last entry = highest priority); the build fails on an enabled filename that wasn't downloaded, so refresh `options.txt` whenever a pack's version bumps. See the consumer README § Resource packs or docs/customisation.md for the full reference.

## Config sync

Mod configs in `config/<modname>/` are copied to `data/config/` by **deploy.sh step 8** (every full deploy) — skip-if-exists for bundle defaults, then force-overwrite for consumer overlay. This runs **before mc starts** so mods that auto-generate config on first boot don't create defaults that block the bundle's version. Adding a mod with config means touching **two places**:

1. Config files in `config/<modname>/`
2. The dir added to `MC_PATTERNS` in `.github/workflows/deploy.yml` so changes trigger a full deploy

**Game rules** live in two places that must match: `config/boring_default_game_rules/config.json` (new-world defaults) AND the RCON enforcement block in `scripts/deploy.sh` (existing world). Each has a comment pointing at the other.

**World spawn** is enforced the same way: `deploy.sh` runs `setworldspawn` from `SPAWN_X/Y/Z` in `config/.env` on every deploy (and centres the BlueMap webapp on it), so an in-game `/setworldspawn` doesn't stick - change the env vars instead.

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

**Footer version string** (`<PackName> · <pack>-<MC_VERSION>-v<git sha>`): `PACK_NAME` is computed by `build-modpack.sh` from `git rev-parse --short HEAD` and baked into the pack page. The mods page (`check-updates.sh`) and status page (`kuma-provision.py`) run in containers with no git checkout, so they read the _served_ pack build instead - `modpack/dist/packwiz/pack.toml` (`version = "<sha>"`), via the dist mount and `http://pack-web/packwiz/pack.toml` respectively. If the status footer shows `vunknown`, pack-web wasn't reachable and no git checkout existed.

**Fonts**: the placeholder uses system fonts throughout. To add a custom display font, place the woff2 in `modpack/template/fonts/` (copied to `dist/fonts/` by the build), update the CSS in each surface, and add a CORS header on `/fonts/` in `pack-web.conf` if Kuma's customCSS loads it cross-origin from pack.DOMAIN.

OG/meta tags are also injected per-domain by `nav-proxy.conf` (`sub_filter '<title>'`). Kuma's own markup can only be restyled via the `customCSS` in `kuma-config.json` - you can't edit its HTML.

## Confirm before proceeding

These actions are allowed but carry irreversible consequences — pause and ask a human before executing:

- **Cutting a release** — a burnt tag can't be reused; a broken release breaks all consumer updates.
- **Running `./ops reset-seed`** — deletes the world, BlueMap, Chunky, and DH data.
- **Changing `FULL_PATTERNS`** in the reusable workflow — alters deploy behaviour for all consumers.
- **Modifying `config/multiverse_config.json`** — worldgen changes can't be undone on existing chunks.
- **Deleting or modifying `data/` on production** — the world can't be replaced.
- **Running `./ops teardown`** — destroys cloud resources.

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
10. **Never use unbounded wait loops over SSH.** A `while true; sleep; done` loop waiting for a container, healthcheck, or log message that may never arrive will trap you indefinitely with no way to break out. Allowed: a single `sleep N` outside a loop for a known duration. Forbidden: `docker logs -f` (streams forever), any interactive shell, `gh run watch` (streams), and any loop that exits on a condition you cannot guarantee will occur (a crashing container will never become healthy). Use `./ops` commands, `docker logs --tail N` snapshots, or `gh run view --json` polls with a finite iteration cap instead.
11. **Don't repeatedly poll CI runs.** After dispatching a workflow or pushing, check status once. If it's in progress, give the user the Actions URL and stop. Smoke tests boot ~150 mods and take 5-10 minutes on GitHub runners — repeatedly running `gh run view` every 60s wastes context and achieves nothing. One background check with a generous timeout is fine; five manual polls in a row is not.

## Common tasks

| Task | Edit | Run |
| --- | --- | --- |
| Add a server mod (consumer) | `overlay/mods-extra.txt` (+ deps, pinned) | `./dev up` or push to `main` |
| Add a default server mod (platform) | `config/modrinth-mods.txt` (+ deps, pinned) | Push, cut release |
| Build an in-house mod | `mods/<name>/` (Fabric project) | `cd mods/<name> && ./gradlew build` → local verification loop ([mods/AGENTS.md](mods/AGENTS.md#verification-loop)) → cut a release to ship |
| Cut a platform release | - | `gh workflow run release.yml -f version=vX.Y.Z` (**never** `gh release create`) |
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
| Manual deploy | - | `ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive'` (deploy.sh ships in the bundle — there is no `~/server/scripts/`) |
| Validate scripts | - | `./scripts/test-scripts.sh --quick` |
