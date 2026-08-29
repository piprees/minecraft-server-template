# Adventure Server

A published platform for running a modded Minecraft **1.21.1** server as infrastructure-as-code. Pre-built Docker images, a versioned stack bundle, and a reusable CI/CD workflow — so your server repo stays thin and upgrades are a one-line version bump. It runs Fabric on Docker (`itzg/minecraft-server`) with ~150 pinned server mods (Terralith, Incendium, Nullscape, seasons, YUNG's structures, and more) plus a 100+ mod client pack. Invite-only via online-mode + whitelist, driven by Discord roles. Cloudflare tunnel for web services, restic backups to R2, Uptime Kuma for monitoring.

[![Deploy Minecraft Server](../../actions/workflows/deploy-reusable.yml/badge.svg)](../../actions/workflows/deploy-reusable.yml)

> **AI agents:** read [`AGENTS.md`](AGENTS.md) first — constraints, traps, and production access. **Commands:** [`COMMANDS.md`](COMMANDS.md). **Problems:** [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md). **Customising a server:** [`CUSTOMISATION.md`](CUSTOMISATION.md). **Everything else:** [`docs/README.md`](docs/README.md).

## Run your own server

Copy the consumer scaffold and run the setup wizard. Full consumer documentation: [`examples/consumer/README.md`](examples/consumer/README.md).

```bash
# Option A: degit (no git history)
npx degit piprees/minecraft-server-template/examples/consumer my-server
# Option B: curl + tar
mkdir my-server && curl -sL https://github.com/piprees/minecraft-server-template/archive/refs/heads/main.tar.gz \
  | tar -xz --strip-components=3 -C my-server 'minecraft-server-template-main/examples/consumer'

cd my-server && ./ops setup      # guided wizard: credentials -> .env -> local test -> production
```

### Try it locally in 10 minutes

```bash
cp .env.example .env             # every variable documented in comments
./dev up                         # pulls the stack bundle + starts everything
./dev logs                       # tail the Minecraft server logs
./dev rcon "list"                # run an RCON command
./dev down                       # stop everything
```

Connect at `mc.<LOCAL_DOMAIN>:<SERVER_PORT>` (default `mc.myserver.local:25577`), after adding the `/etc/hosts` entries `./dev up` prints. The local profile disables `ONLINE_MODE`, whitelist, and autopause, and backs up to MinIO (console at `localhost:9001`, minioadmin/minioadmin123). `./dev seeds` opens the [seed roller](mods/custom-dimensions/README.md#seed-roller--a-browser-tool-not-a-command) — roll candidate seeds for a dimension, compare maps and scores, fly the best two, and the winner is written into the dimension's overlay config. **Local profile only by design:** no authentication, it can teleport a player, and it writes into your committed overlay.

### Going to production

```bash
./ops setup                      # interactive wizard: credentials → .env
./ops preflight                  # validate everything before provisioning
./ops provision                  # create the cloud server (Hetzner by default)
./ops harden                     # lock down SSH, firewall, fail2ban
./ops prepare                    # deploy key, .env on server, GitHub env sync
./ops cloudflare                 # tunnel + DNS records + R2 bucket
```

Then push to `main` — `.github/workflows/deploy.yml` calls the reusable workflow. Walkthrough and credential guide: the `server-provisioning` skill.

## Upgrading

Bump `STACK_VERSION` in `.env` (or leave it as `v5` to track the latest v5.x.y), then `./dev update && ./dev up`. Each release `vX.Y.Z` tags every GHCR image (`X.Y.Z`, `X.Y`, `X`, `latest`) and attaches a **stack bundle** tarball: compose files, all host-side operational scripts, default configs, and the in-house mod JARs (`local-mods/`, CI-built and remap-verified, installed into `data/mods/` by `deploy.sh` and `./dev up`). **Compatibility:** a major bump is breaking (`.env` keys, overlay contract, compose structure) and ships a migration guide; `v5.1` → `v5.2` adds features, default mods, and config, backwards-compatible; `v5.1.0` → `v5.1.1` is drop-in. Pinning `STACK_VERSION=v5` picks up minors and patches automatically. Cutting a release: the `platform-release-management` skill.

## Architecture

```plaintext
                          ┌────────────────────────────────────────────────┐
                          │ Linux VPS (Ubuntu 24.04, hardened)             │
  Friends (Java) ─────────┼─ DNS A: mc.example.com ─► :25577 ─────────────►│ mc (Fabric 1.21.1, ~150 mods)
   mc.example.com:25577   │   (+ SRV record hides the port)                │  ├ autopause when empty
                          │                                                │  └ RCON :25575 (internal only)
  Friends (browser) ──────┼─ Cloudflare Tunnel (HTTP only):                │
   map.example.com        │    map/status/mods ─► nav-proxy ─► static/Kuma │ sidecars:
   pack.example.com       │    pack             ─► pack-web (nginx)        │  unmined-render (static maps)
   status.example.com     │                                                │  mc-backup (restic ► R2, 12h)
   mods.example.com       │                                                │  idle-tasks (Chunky pre-gen, GC)
                          │                                                │  mod-checker (daily update page)
                          │                                                │  uptime-kuma + kuma-init
  GitHub Actions ─────────┼─ SSH (deploy key) ─► deploy user               │  cloudflared, nav-proxy, pack-web
   (auto-deploy)          │                                                │  discord-sync (bot, RCON bridge)
                          └────────────────────────────────────────────────┘
   Discord ◄── dcintegration (chat bridge) + discord-sync (/mc, /register, role sync)
   Voice   ◄── Simple Voice Chat UDP 24454 ──► friends
```

### Services (docker-compose.yml)

| Service | Image | Profiles | Purpose |
| --- | --- | --- | --- |
| `mc` | `itzg/minecraft-server` | local, cloud | The game server. Fabric, autopause, RCON, healthcheck |
| `defaults-seed` | `ghcr.io/.../defaults-seed` | local, cloud | Seeds default configs, mods, and datapacks into shared volumes; applies consumer overlay |
| `mc-backup` | `itzg/mc-backup` | cloud | restic snapshots to R2 every 12h by default, `save-off` consistency |
| `minio` + `minio-init` + `mc-backup-local` | minio / itzg | local | Local S3 stand-in so backups work identically in dev |
| `uptime-kuma` + `kuma-init` | louislam / ghcr.io/.../kuma-init | both | Monitoring + one-shot idempotent provisioning from `config/uptime-kuma/kuma-config.json` |
| `nav-proxy` | nginx | both | Injects the server nav bar into every web page via `sub_filter` |
| `cloudflared` | cloudflare | cloud | HTTPS tunnel for web services (never the game port) |
| `pack-web` | nginx | both | Serves the `.mrpack`, download page, and the mirrored mod JARs (`/mods/`, Cloudflare edge-cached) from `modpack/dist/` |
| `idle-tasks` | ghcr.io/.../idle-tasks | cloud | When empty: save, GC, Chunky pre-generation |
| `mod-checker` | ghcr.io/.../mod-checker | both | Daily (06:00 UTC) mod update check, HTML page at mods.DOMAIN |
| `unmined-render` | ghcr.io/.../unmined-render | both | Scheduled static uNmINeD map renders into `data/unmined-web/`, served at map.DOMAIN/unmined/ (off until `UNMINED_INTERVAL` is set) |
| `discord-sync` | ghcr.io/.../discord-sync | both | Discord bot: `/register`, `/mc` admin commands, role→whitelist sync |

**Ports:** game `25577/tcp` (host) → `25565` (container), voice `24454/udp`, RCON `25575` (Docker network only), Kuma `3001` and pack-web `8080` bound to localhost only. **Autopause:** the JVM freezes when the server has been empty for 10 minutes and RCON stops responding, so scripts and monitors treat "no RCON" as paused, not down — never add anything that pokes the game port on an interval, it wakes the server.

## Configuration

Four layers, one direction of truth:

1. **Platform defaults** — baked into the `defaults-seed` image (configs, mod list, datapacks). The starting point.
2. **Consumer overlay** — `overlay/` in your consumer repo (extra mods, config overrides, branding). Applied on top of defaults by the seed container.
3. **`.env`** (git-ignored) — all settings and secrets for local use. Every variable is documented in `.env.example`; `./ops op-env > .env` rebuilds it from 1Password and `./ops op-sync` pushes local changes back.
4. **GitHub `production` environment** (Settings → Environments) — secrets and variables, pushed from your local `.env` by `./ops github-env-sync`. **Every full CI deploy regenerates the server's `.env`** from these, so hand-edits on the server don't survive; change the source of truth instead.

## Repository layout

This is the **platform repo** — it builds and publishes the images, the stack bundle, and the reusable workflows. Consumers don't clone it; they copy `examples/consumer/`.

```
.
├── docker/                # Dockerfiles for all published GHCR images
├── examples/consumer/     # Consumer scaffold — copy this to start your server
├── mods/                  # In-house Fabric mods (Gradle projects; see mods/AGENTS.md)
├── scripts/               # Operational + build scripts (catalogued below)
├── config/                # Default server configs, mod list, messages, nginx, etc.
├── modpack/               # Client pack manifest + overrides + built .mrpack (dist/)
├── assets/                # Placeholder brand assets (SVG icon, logo, cover, favicon)
├── docs/                  # Reference documentation and the docs index
├── docker-compose.yml     # Full stack, local/cloud profiles (`.env.example` alongside)
└── .github/workflows/     # deploy.yml, deploy-reusable.yml, lint.yml, publish.yml, etc.
```

## Scripts

Three categories, by where a script ends up and who runs it. Every script has a header comment with usage, context, and gotchas — **read the header before running it**. Authoring rules: the `bundle-script-authoring` skill.

### Bundle scripts (shipped in the stack tarball, run by consumers via `./ops`)

| Script | Where | What it does |
| --- | --- | --- |
| `setup.sh` | Mac | Interactive wizard: credentials → .env → preflight → deploy |
| `teardown.sh` | Mac | Reverse of setup: delete resources with double-confirmation |
| `op-env.sh` / `op-sync-env.sh` | Mac | Restore `.env` from 1Password / push `.env` back to 1Password |
| `preflight-check.sh` | Mac | Validate .env values, tools, and credentials before anything else |
| `provision.sh` (+ `-hetzner`, `-droplet`) | Mac | Create the cloud server (idempotent, provider-routed) |
| `harden.sh` | Mac→server | One-time lockdown: deploy user, SSH keys only, UFW, fail2ban, Docker, swap |
| `prepare-droplet.sh` | Mac | Deploy key, .env on server, GitHub env sync |
| `initial-setup.sh` | server | First boot: restic init, config seed, image pull |
| `deploy.sh` | server (CI) | The deploy: countdown → kick → restart → config sync → rules → whitelist |
| `setup-permissions.sh` | server | LuckPerms groups/permissions via RCON (called by deploy.sh) |
| `cloudflare-setup.sh` | Mac | Tunnel + A/SRV/CNAME records + R2 bucket + maintenance Worker |
| `infra-deploy.sh` | server (CI) | Infra-tier deploy: pull + recreate sidecars without touching mc |
| `github-env-sync.sh` | Mac | Create GitHub production environment, push secrets/vars from .env |
| `backup-now.sh` | server | Trigger an immediate backup via the mc-backup sidecar |
| `rcon.sh` | Mac | RCON without the ssh dance: `./ops rcon "list"` (auto local/production) |
| `doctor.sh` | Mac (CI) | One-shot production triage: drift, stashes, disk, containers, backups, Discord registry, errors |
| `live-logs.sh` / `live-stats.sh` | Mac | Log tailing / container stats |
| `game-log.sh` | Mac | Log snapshot with grep/tail filters (never streams) |
| `reset-seed.sh` | Mac | World reset with a new seed (backs up first, triple-confirmed; wipes the old world's bot/webhook messages from the Discord channels) |
| `discord-notify.sh` | any | Send templated messages to the Discord webhook |
| `discord-cleanup.sh` | Mac | Delete all bot/webhook messages from a Discord channel |
| `discord-pin-sync.sh` | Mac | Sync the #general welcome pin from messages.json |
| `ddns-update.sh` | local host | Cloudflare dynamic DNS for home hosting (cron-installable) |
| `cache-assets.sh` | Mac | Snapshot Docker images, mod JARs, offline client bundles |
| `clean-dev-state.sh` | Mac | `./dev clean`: delete regenerable local state by named target (`--list`, `--dry-run`); world, seed bank and backups are opt-in |
| `service.sh` | Mac | Start, stop, restart, or check status of a service. Production refuses every `mc` action but `status`; the local profile (`./dev`) allows all of them |
| `map-render.sh` | Mac | Drive the unmined-render sidecar: status, force a render pass |
| `force-toml-key.py` | server, Mac | Force one key in a mod's TOML to a value, re-applied every deploy because mods rewrite their own config |
| `lib.sh` | (sourced) | Shared utilities: env loading, bounded RCON, the deploy lock, provider detection |

### Image scripts (baked into GHCR images, not run directly)

| Script | Image | What it does |
| --- | --- | --- |
| `build-modpack.sh` | modpack-builder | Build versioned `.mrpack` + download page from the manifest |
| `check-pack-coherence.py` | modpack-builder | Validate pack manifest consistency |
| `modrinth-api.py` | modpack-builder | Bulk Modrinth resolution with connection reuse and rate-limit handling |
| `discord-sync.py` | discord-sync | Discord bot: `/register`, `/mc` commands, role sync |
| `kuma-provision.py` | kuma-init | One-shot Kuma provisioning from kuma-config.json |
| `idle-tasks.sh` | idle-tasks | Save/GC/Chunky when the server is empty |
| `check-updates.sh` | mod-checker | Mod update check, HTML status page generation |

### Template-only scripts (platform development, not shipped)

| Script | What it does |
| --- | --- |
| `pin-mod-versions.sh` | Re-pin every mod to its latest build (used by mod-updates.yml) |
| `check-modrinth-compat.sh` | Check the mod list against a target MC version/loader |
| `build-mod-update-report.py` | Build the mod-update PR body with changelogs |
| `client-defaults.sh` | Diff/sync shipped client defaults against the source Prism instance |
| `test-scripts.sh` | shellcheck + py_compile + compose validation |
| `build-stack-bundle.sh` | Assemble the release tarball |
| `sync-mod-cache.sh` | Reconcile `mods-cache/` against the pinned mod lists (`--apply`) |
| `export-seed-winners.py` | Copy rolled winner seeds/spawns from a consumer overlay into the platform dimension configs (`--dry-run` diffs) |
| `extract-biomes.py` / `extract-blocks.py` / `extract-entities.py` | Catalogue every biome, block and entity in the installed mod JARs, for authoring and lint. Static scans — they cannot resolve a tag |
| `extract-registries.py` | Trigger `/customdim catalogue` on a running consumer and copy the dump in: biomes, fully-resolved biome tags (`c:*` included), structures with their step, terrain adaptation and valid biomes, and structure sets with their placement. The only source for a tag question |
| `check-content-coverage.py` | Which installed biomes any dimension actually names — an installed mod nobody names never appears in game |
| `check-biome-bands.py` | Explicit biome bands within a dimension must partition its climate axis, not overlap (exits 1 on any overlap) |
| `gen-unmined-blocktags.py` | Regenerate the map renderer's modded block tags from the block catalogue (`--check` in CI) |
| `gen-open-water-guard.py` | Regenerate `config/datapacks/open-water/` — the Lithostitched conditions that keep land structures out of the sea (`--check` for CI) |
| `scan-structure-placements.py` | What generated in a world and whether it is standing in water (needs `requirements-dev.txt`) |
| `analyse-site-validity.py` | Clark-Evans dispersion per group and cross-class nearest-neighbour distance from a `/customdim site-validity` artefact — whether structures form places or noise |
| `extract-registries.py` | Pull the live registry catalogue via `/customdim catalogue` |
| `mcjson.py` | (imported) Parses the lenient JSON mods actually ship: `//` comments and trailing commas |

## How to do things

Task → file → command lookup: [`docs/common-tasks.md`](docs/common-tasks.md).

### Add or remove mods

Server mods go in `overlay/mods-extra.txt` (consumer) or `config/modrinth-mods.txt` (platform), removals in `overlay/mods-remove.txt`, client mods in `modpack/adventure.mrpack.json`. Everything must target **Fabric for 1.21.1**, and resolving a mod's dependencies before adding it is mandatory — that checklist, version holds, and the offline delivery model are in the `server-mod-management` skill. Clients auto-update via **packwiz**: the build generates `dist/packwiz/` (pack.toml + per-mod metafiles pointing at the mirror) and the one-click Prism instance zip runs `packwiz-installer` as a pre-launch task, so every launch hash-syncs mods and pack configs from the CDN.

### Update Minecraft version

A big job — all ~150 server mods and ~110 client mods must support the target first. Procedure: [`docs/minecraft-version-upgrade.md`](docs/minecraft-version-upgrade.md).

### Deploy to production

Pushing to `main` in a consumer repo triggers the caller workflow, which invokes the reusable `deploy-reusable.yml` from this platform repo. It resolves the symbolic `STACK_VERSION` pin (`v5`, `latest`) to a concrete release tag, compares it against the bundle the server is actually running (`readlink .stack/current`), then diffs consumer files against the server's deployed commit and picks a tier:

| Mode | Trigger | What happens |
| --- | --- | --- |
| **Full** | A new platform release matching the pin (resolved tag ≠ running bundle), `overlay/config/`, `overlay/mods-extra.txt`, `overlay/mods-remove.txt`, manual dispatch, releases | Secrets uploaded → stack bundle pinned to the resolved tag → deploy.sh: countdown → kick → whitelist-block → save → restart → regenerate .env → config sync → permissions → whitelist restore → Discord notify |
| **Infra** | Other `overlay/` changes (assets, branding) | Image pull + compose up (mc untouched) + force-recreate sidecars |
| **Pull** | Docs, CI, everything else — and no stack change | Nothing touches the server |

Consumer repos have almost no deployable files of their own, so **most full deploys are driven by the resolved-tag comparison**, not by consumer file diffs: a consumer push made after a platform release lands is what rolls it out. CI rebuilds the `.mrpack` + download page after every full deploy. Monitoring and recovery: the `deploy-pipeline-operations` skill.

### Backups

Automatic every **12h** by default via `mc-backup` (restic → Cloudflare R2), with RCON `save-off`/`save-on` for consistency and `BACKUP_INTERVAL` to override. Retention is 3 daily, 1 weekly, 1 monthly, and `BACKUP_SIZE_CAP_GIB` (default 80) trims below that if the repo outgrows it — a budget rather than a technical limit, costing about $1.14/month on R2 Standard at a full 80 GiB. Only world, player data, and config are backed up — the map, mods, and caches are excluded and regenerate. Take one now with `./ops backup`, and verify it with `docker logs mc-backup --tail 50` (look for `snapshot ... saved`).

**`RESTIC_PASSWORD` can't be recovered.** Store it somewhere safe (1Password); every backup is unreadable without it. The restore procedure, the full excludes list, and chunk surgery are in the `backup-and-recovery` skill.

### Discord integration

Two clients share **one bot token**: the **dcintegration** Fabric mod in the `mc` container owns the chat bridge only, and **discord-sync** owns every slash command (`/register`, `/unregister`, `/mc ...`), role→whitelist sync, and the audit log. The mod's command feature must stay off (`[commands] enabled = false` in the live `data/config/Discord-Integration.toml`) or it wipes the command registry on every mc boot; `deploy.sh` enforces this on every full deploy. Players self-serve: `/register <minecraft_username>` in Discord, an admin grants the `@Player` role, and the bot whitelists them via RCON within 60s (`@Admin` also grants op; removing a role reverses both). Manual RCON is the immediate fallback — [COMMANDS.md](COMMANDS.md#useful-admin-recipes). Missing commands, registry checks, and shipping a bot change: the `discord-integration-ops` skill.

### Server access

Production host is `DROPLET_HOST` in `.env` (also a GitHub Actions variable) and the server directory is `~/server`. RCON is never exposed publicly — it exists only inside the Docker network, reached via `docker exec`. Start with `./ops doctor` (full health triage) and `./ops stats` (system + container summary); `./ops ssh` gets a shell, or `./ops ssh '<command>'` runs a command directly. The `./ops` log and RCON equivalents are in [AGENTS.md § Production access](AGENTS.md#production-access).

### Reset the world (launch events)

`./ops reset-seed <seed>` backs up (restic + tar), stops the stack, deletes world/map/Chunky/DH data, updates `SEED` in `.env`, and restarts. Triple-confirmed, prints undo instructions; commit `.env` afterwards. But `.env`'s `SEED` only reaches terrain when the overworld's dimension config has `"seed": "env"` — every dimension generates from its own file under `config/custom-dimensions/dimensions/`, `overworld` and the other three reserved names included. Put the seed there (or in the consumer overlay) and deploy it BEFORE running the reset, or the world regenerates with the old terrain under a new seed value that changed nothing. See [TROUBLESHOOTING.md#t31](TROUBLESHOOTING.md#t31).

## Troubleshooting

[TROUBLESHOOTING.md](TROUBLESHOOTING.md) is the single source of truth for traps, platform quirks, and open issues, with a symptom index and permanent per-entry anchors. Start any diagnosis with `./ops doctor`.

## Contributing

Contributors work here to improve the images, bundle scripts, default configs, and workflows that every consumer inherits. Platform changes are tested through a consumer repo linked to this checkout (`./dev link`), so an edited script, compose file, or rebuilt mod jar goes live on the next `./dev up` with no release. Quality gate before pushing: `./scripts/test-scripts.sh --quick`. Commit conventions, the mod change checklist, and PR expectations: [CONTRIBUTING.md](CONTRIBUTING.md). Security policy: [SECURITY.md](SECURITY.md). Full history: [CHANGELOG.md](CHANGELOG.md) and the [release notes](../../releases).

**Fixed decisions:** Minecraft **1.21.1**, Fabric, Docker, conventional networking, Cloudflare HTTP tunnel only, Incendium-only Nether, restic to R2, guild-scoped Discord commands owned by discord-sync — each is load-bearing. Full list and rationale: [AGENTS.md](AGENTS.md#fixed-decisions-template-defaults).

Built on [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server), and released under the [MIT Licence](LICENSE).
