# AGENTS.md

> **Read `README.md` before any task** - it has the architecture, config model, and how-tos. This file is the agent contract: constraints, traps, and access. If you're running this on a production server with player data, mistakes have real consequences: the world and player progress can't be replaced.

## Operating contract

Requests are tasks. Tasks go on a list. Work each task to completion before starting the next. When a task is done, pick up the next open one. When the list is empty, check if anything was deferred and do that.

If a new request arrives mid-task: add it to the list, finish the current task, then pick it up. If the request is urgent and the user says to switch: park the current task with its state noted in the log, switch, come back when the urgent one is done.

Do not ask "should I continue." Continue. Do not ask "what next." Check the list. Do not stop working while there are open tasks.

**Completion standard:** a task is complete when the code is written AND run AND tested. The user could use the output right now without touching it.

**Testing:** every piece of work gets tested in the turn it's built. Read the output. Check the values. If the output is wrong, fix it before reporting it done. For long-running work: run one item, verify the output, then start the batch. Check the first batch result before walking away.

**Logging:** one line to the log after every finding, decision, or error. Before the next action. Format: `HH:MM — <what happened>`. This is how work survives between sessions.

**Bug response:** read the error. Fix the specific failure. Re-run. Verify. Move on. Do not theorise. Do not redesign. Do not guess. Read the error.

**Building:** build on what exists. Extend, fix, and improve existing tools. If something needs replacing, confirm with the user first. Test what you build in the same turn. For anything >50 lines, delegate to a subagent with a requirements checklist and verify every item when it returns.

**Dependencies:** if the plan requires a tool, library, or credential that isn't installed — ask NOW. Not after working around it.

**Feedback:** when corrected, act. In the same turn. When the same thing is asked twice, the first acknowledgement didn't result in it happening. Make it happen.

**Context budget:** every token costs. Work until context is exhausted. Produce deliverables, not infrastructure. Do not idle. Do not ask for permission to continue. Do not pad turns with summaries of what you're about to do. Do the work.

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

Pushing to `main` in a consumer repo triggers the caller workflow, which invokes the reusable `deploy-reusable.yml`. Tier detection is two-stage: (1) the symbolic `STACK_VERSION` pin is **resolved to a concrete release tag** and compared against the bundle the server actually runs (`readlink .stack/current`) — any difference forces a full deploy, and this is what rolls platform releases out; (2) consumer files are diffed against the server's deployed commit — `FULL_PATTERNS` in the reusable workflow lists the consumer paths that cause a full restart (`overlay/config/`, mod lists). See the [deploy modes table](README.md#deploy-to-production).

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

See the `platform-release-management` skill for the full procedure, compatibility promise, and consumer impact.

**Key constraints for agents:**

- **Never use `gh release create` directly** — use `gh workflow run release.yml -f version=vX.Y.Z`.
- **Never push to `main` while release.yml is in progress.** The image builds (publish.yml) use concurrency group `publish-${{ github.ref }}` with `cancel-in-progress: true`, and a push-triggered build shares the group with the release's builds — the push CANCELS the release's in-flight image builds mid-push, leaving the release published but its version-tagged images missing (2026-07-13: a docs push cancelled the discord-sync 2.14.0 image; production pulls version tags via `IMAGE_TAG="${STACK_VERSION#v}"`). Recovery: `gh run rerun <release-run-id> --failed` rebuilds only the cancelled jobs — the release/tag themselves are unaffected if the "Build bundle and publish release" job succeeded.
- Releases are **immutable**: assets can't be attached after publish, so `gh release create` produces a broken release with no bundle.
- A published immutable release tag cannot be reused, even after deleting the release. Fix the cause and cut the **NEXT patch version**.
- If a published release ships without a bundle, treat it as broken and cut the next patch version with complete assets. Do not delete and re-cut the same tag. Draft releases are mutable until publication, so validate all assets before publishing.

## Problems, traps, and known issues

**All of it lives in [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)** — architecture traps (T1–T16), macOS local-dev quirks (P1–P4), dimension lifecycle (D1–D8), and the open-issue watch list (K1–K2). Every entry has a permanent anchor, so cite them precisely: `TROUBLESHOOTING.md#t14`.

Start any diagnosis with `./ops doctor`. The forbidden-actions list (never stream logs, never retry a Kuma login, never `docker restart mc` on production) is at the top of that file.

The ones worth knowing before you touch anything:

| | |
| --- | --- |
| [T4](TROUBLESHOOTING.md#t4) | Mods resolve in the seed, never via the API at boot. Don't reintroduce `MODRINTH_PROJECTS`. |
| [T7](TROUBLESHOOTING.md#t7) | Kuma auth is human-gated. On `authIncorrectCreds`, **stop** — do not retry. |
| [T9](TROUBLESHOOTING.md#t9) | `--no-recreate` on infra deploys is load-bearing. Only deploy.sh may recreate mc. |
| [T11](TROUBLESHOOTING.md#t11) | `.deployed` can lie: CI green while the server runs nothing. |
| [T12](TROUBLESHOOTING.md#t12) | Hand-patched volumes are reverted by every seed run — verify the rendered state, not the patch. |
| [D2](TROUBLESHOOTING.md#d2) | All worldgen config is creation-time-only. Changing it needs a world wipe. |

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

**Git:** conventional-commit style, imperative mood (`fix:`, `feat:`, `chore:`). Small-team habits: commit straight to main (no PRs/worktrees); park unfinished work as a WIP commit, never `git stash` (the stash stack is shared across agent sessions); after any release, refresh the major tag with `git fetch origin '+refs/tags/v3:refs/tags/v3'` — releases force-move it and a stale local copy makes fetch complain forever. The local consumer server (`~/Projects/elfydd`) is shared — check nobody is mid-test before restarting its containers.

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

**Version holds:** `_clientMods.holds` in `modpack/adventure.mrpack.json` maps slug → reason for mods that must NOT be re-pinned (e.g. `c2me-fabric`, whose newer alpha wedges fresh-world creation). **Never bump a held slug manually**; remove a hold only when its stated blocker clears. Hold era-pairs together — Xaero's minimap and world map share code and must move as a pair.

**Holds only bind the client manifest.** `pin-mod-versions.sh` reads `_clientMods.holds` inside its client loop; the loop that rewrites `config/modrinth-mods.txt` does not consult it. A hold on a server-only slug is advisory — the weekly `mod-updates.yml` PR will still propose the bump, and only review catches it.

**Resource/shader packs**: `_resourcePacks.packs` / `_shaderPacks.packs` in the manifest - plain slug (primary file) or `{slug, files: [...]}` to also fetch named companion micropacks from the same version. `build-modpack.sh` resolves each to the newest `MC_VERSION`-tagged Modrinth version (falling back to newest upload if untagged). Resource packs are **enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt` (`resourcePacks:` array, last entry = highest priority); the build fails on an enabled filename that wasn't downloaded, so refresh `options.txt` whenever a pack's version bumps. See the consumer README § Resource packs or docs/customisation.md for the full reference.

## Config sync

Mod configs in `config/<modname>/` (or flat `config/<file>` when the mod reads a bare path — verify against the jar, e.g. Tectonic reads `config/tectonic.json`) are copied to `data/config/` by **deploy.sh step 8** (every full deploy) and by `dev-up.sh` locally (skip-if-exists). This runs **before mc starts** so mods that auto-generate config on first boot don't create defaults that block the bundle's version. Adding a mod with config means touching **two places**:

1. Config files in `config/<modname>/` (shipped automatically in the stack bundle — platform config changes reach consumers via the next release, which forces a full deploy through the resolved-tag comparison)
2. A `COPY` line in `docker/defaults-seed/Dockerfile` so the seed volume carries the default and the consumer overlay can merge over it

**Game rules** live in two places that must match: `config/boring_default_game_rules/config.json` (new-world defaults) AND the RCON enforcement block in `scripts/deploy.sh` (existing world). Each has a comment pointing at the other.

**World spawn** is enforced the same way: `deploy.sh` runs `setworldspawn` from `SPAWN_X/Y/Z` in `config/.env` on every deploy, so an in-game `/setworldspawn` doesn't stick - change the env vars instead.

## Web surfaces (styles & markup)

The four public pages share one design system but have **no shared stylesheet** - each surface carries its own copy of the styles. `DESIGN.md` (repo root) is the source of truth for tokens (the Quarry palette, type scale, spacing); keep every copy in step with it by hand.

| Surface | Markup + styles live in | Regenerated by | Deploy tier |
| --- | --- | --- | --- |
| pack.DOMAIN | `modpack/template/index.html` (full page, CSS custom properties) | `build-modpack.sh` → `modpack/dist/index.html` (CI after full deploys) | Pull (CI rebuilds pack) |
| mods.DOMAIN | HTML/CSS heredocs in `scripts/check-updates.sh` (`--html`) | mod-checker container on boot + daily 06:00 UTC → `modpack/dist/status.html` (nav-proxy rewrites `/` → `/status.html`) | Infra (force-recreates mod-checker) |
| status.DOMAIN | Uptime Kuma + `customCSS`/`footerText` in `config/uptime-kuma/kuma-config.json`, applied by `scripts/kuma-provision.py` (kuma-init, every deploy) | kuma-init container | Infra (force-recreates kuma-init) |
| map.DOMAIN | uNmINeD viewer shell in `docker/unmined-render/webshell/`; nav + OG injected by `nav-proxy.conf.template` | `unmined-render` sidecar → `data/unmined-web/` | Infra (force-recreates nav-proxy) |
| 404 page | Heredoc in `scripts/build-modpack.sh` → `modpack/dist/404.html` | `build-modpack.sh` | Pull |

**The nav bar is injected, not authored per page**: `config/nginx/nav-proxy.conf` `sub_filter`s the nav HTML + CSS into every page - **four near-identical copies**, one per `server` block. Changing the nav means changing all four — count them with `grep -c '<nav' config/nginx/nav-proxy.conf.template`.

**Footer version string** (`<PackName> · <pack>-<MC_VERSION>-v<git sha>`): `PACK_NAME` is computed by `build-modpack.sh` from `git rev-parse --short HEAD` and baked into the pack page. The mods page (`check-updates.sh`) and status page (`kuma-provision.py`) run in containers with no git checkout, so they read the _served_ pack build instead - `modpack/dist/packwiz/pack.toml` (`version = "<sha>"`), via the dist mount and `http://pack-web/packwiz/pack.toml` respectively. If the status footer shows `vunknown`, pack-web wasn't reachable and no git checkout existed.

**Fonts**: the placeholder uses system fonts throughout. To add a custom display font, place the woff2 in `modpack/template/fonts/` (copied to `dist/fonts/` by the build), update the CSS in each surface, and add a CORS header on `/fonts/` in `pack-web.conf` if Kuma's customCSS loads it cross-origin from pack.DOMAIN.

OG/meta tags are also injected per-domain by `nav-proxy.conf` (`sub_filter '<title>'`). Kuma's own markup can only be restyled via the `customCSS` in `kuma-config.json` - you can't edit its HTML.

## Network dependency model

Every external network call is either eliminated, cached, or made failure-tolerant. Nothing should break due to an API timeout or CDN outage at runtime.

### Operations that need network

| When | What calls out | Mitigated by |
| --- | --- | --- |
| **CI image build** | Docker Hub (base images), PyPI (`pip install`), APK repos (`apk add`) | GHCR mirrors for Docker Hub images (`mirror-images.yml`, weekly sync); PyPI/APK pins make builds deterministic; network is always available in CI runners |
| **CI deploy** | GitHub API (stack version resolution), GHCR (image pulls), SSH to server | `stack-pull.sh` retries 3x with exponential backoff + falls back to `.stack/.resolved-cache` then local `.stack/` dirs; GHCR mirrors avoid Docker Hub rate limits |
| **CI release** | GitHub API (release creation), GHCR (image push), git-cliff (installed via `orhun/git-cliff-action`) | `git-cliff-action` handles download/caching internally; release creation is inherently online |
| **CI smoke test** | Modrinth CDN (mod JARs on cache miss), Carpet mod JAR | Mod JARs cached by `actions/cache` keyed on `modrinth-mods.txt` hash; Carpet JAR cached by `actions/cache` keyed on version |
| **First consumer boot** | GitHub API (version resolution), GitHub Releases (bundle download), Modrinth CDN (mod JARs on first seed) | `stack-pull.sh` retry + cache fallback; mod JARs cached in `stack-mods` volume after first resolution (version IDs are immutable) |
| **Modpack build** | Modrinth API (version resolution for resource/shader packs) | `modrinth-resolve-cache.json` committed in repo — zero API calls on cache hit for known pins |
| **Discord webhooks** | Discord API | All webhook calls use `\|\| true` (fire and forget) — a Discord outage never blocks a deploy or boot |

### Operations that are fully offline

| When | Why |
| --- | --- |
| **Server boot** | Zero network requests. `MODS_FILE`/`DATAPACKS_FILE` are empty by default (itzg would otherwise HEAD-check every URL for freshness each boot — a CDN outage could fail a boot despite a warm cache, seen 2026-07-22). Missing jars are fetched host-side by `scripts/sync-mods.sh` between the seed and mc's start (dev-up.sh, deploy.sh step 10b, CI) — network is touched only when the mod list actually changed. `./scripts/cache-assets.sh --mods` keeps an extra jar snapshot. |
| **Server runtime** | All gameplay, RCON, autopause, idle-tasks, map rendering, Discord bot commands — everything runs locally |
| **Seed rolling** | Uses a warm Docker image (`defaults-seed`); no itzg entrypoint, no network calls |
| **Config changes** | Edit `.env` or `overlay/` files, `./dev up` — no downloads needed (seed container uses baked-in defaults from the image) |
| **Local dev after first boot** | All images, mods, and configs are cached locally |
| **Stack bundle re-use** | `stack-pull.sh` is idempotent — if the resolved version is already in `.stack/<version>/`, no download occurs |

### How each dependency is mitigated

- **Docker Hub images:** mirrored to `ghcr.io/piprees/mirrors/` weekly by `mirror-images.yml`; compose references use `${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}` so consumers can override with `docker.io` if needed
- **Modrinth API:** eliminated from boot path ([T4](TROUBLESHOOTING.md#t4)); seed container resolves pins to direct CDN URLs cached in the `stack-mods` volume; `modrinth-resolve-cache.json` committed for the modpack build
- **Modrinth CDN (mod JARs):** cached in `data/mods/` after first download; CI caches via `actions/cache` keyed on `modrinth-mods.txt` hash
- **GitHub API (version resolution):** `stack-pull.sh` retries 3x with exponential backoff (2s/4s/8s), then falls back to `.stack/.resolved-cache` (last successful resolution), then scans `.stack/` directories
- **GitHub Releases (bundle download):** `stack-pull.sh` retries downloads 3x; bundles are cached in `.stack/<version>/` and never re-downloaded
- **git-cliff binary:** installed via `orhun/git-cliff-action` (handles download, caching, and platform detection internally)
- **Carpet mod (smoke test):** cached via `actions/cache` keyed on version string
- **PyPI packages:** pinned to exact versions (`==`) so builds are deterministic and only needed at image build time
- **Discord webhooks:** all `|| true` — fire and forget, never block on failure
- **Stack bundle (`scripts/`, `config/`):** cached in `.stack/`; only downloaded on version change

## Confirm before proceeding

These actions are allowed but carry irreversible consequences — pause and ask a human before executing:

- **Cutting a release** — a burnt tag can't be reused; a broken release breaks all consumer updates.
- **Running `./ops reset-seed`** — deletes the world, map renders, Chunky, and DH data.
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
| Restart a sidecar | - | `./ops restart <name>` (force-recreates; `mc` is prohibited) |
| Check mod updates | - | `./scripts/check-updates.sh` (weekly PR: `gh workflow run mod-updates.yml`) |
| Update MC version | `.env` + re-pin | Big job — [README → Update Minecraft version](README.md#update-minecraft-version) |
| Manual deploy | - | `ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST 'cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive'` (deploy.sh ships in the bundle — there is no `~/server/scripts/`) |
| Validate scripts | - | `./scripts/test-scripts.sh --quick` |
