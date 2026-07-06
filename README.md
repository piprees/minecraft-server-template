# Adventure Server

A published platform for running a modded Minecraft **1.21.1** server as infrastructure-as-code. Pre-built Docker images, a versioned stack bundle, and a reusable CI/CD workflow — so your server repo stays thin and upgrades are a one-line version bump.

Runs Fabric on Docker (`itzg/minecraft-server`) with ~150 pinned server mods (Terralith, Incendium, Nullscape, seasons, YUNG's structures, and more) plus a 100+ mod client pack. Invite-only via online-mode + whitelist, driven by Discord roles. Cloudflare tunnel for web services, restic backups to R2, Uptime Kuma for monitoring.

[![Deploy Minecraft Server](../../actions/workflows/deploy-reusable.yml/badge.svg)](../../actions/workflows/deploy-reusable.yml)

> **AI agents:** read [`AGENTS.md`](AGENTS.md) before making any changes. It has the constraints, architecture traps, and access details that apply to every task.
>
> **Commands:** see [`COMMANDS.md`](COMMANDS.md) for in-game commands, RCON recipes, and Discord `/mc` commands.

## Run your own server

Copy the consumer scaffold, run the setup wizard, and you're up in 10 minutes:

```bash
# Option A: degit (no git history)
npx degit piprees/minecraft-server-template/examples/consumer my-server

# Option B: curl + tar
mkdir my-server && curl -sL https://github.com/piprees/minecraft-server-template/archive/refs/heads/main.tar.gz \
  | tar -xz --strip-components=3 -C my-server 'minecraft-server-template-main/examples/consumer'

cd my-server
./ops setup                      # guided wizard: credentials -> .env -> local test -> production
```

Prefer to do it by hand? For local dev only:

```bash
cp .env.example .env             # every variable documented in comments
./dev up                          # pulls the stack bundle + starts everything
```

Connect at `mc.<LOCAL_DOMAIN>:<SERVER_PORT>` (default `mc.myserver.local:25577`). Add the `/etc/hosts` entries printed by `./dev up` for subdomain routing.

```bash
./dev logs                       # tail the Minecraft server logs
./dev rcon "list"                # run an RCON command
./dev down                       # stop everything
```

Local profile disables `ONLINE_MODE`, whitelist, and autopause. Backups go to MinIO (console at `localhost:9001`, minioadmin/minioadmin123).

For the full consumer README, see [`examples/consumer/README.md`](examples/consumer/README.md).

### Going to production

The `ops` script delegates to the bundle's operational scripts:

```bash
./ops setup                      # interactive wizard: credentials → .env
./ops preflight                  # validate everything before provisioning
./ops provision                  # create the cloud server (Hetzner by default)
./ops harden                     # lock down SSH, firewall, fail2ban
./ops prepare                    # deploy key, .env on server, GitHub env sync
./ops cloudflare                 # tunnel + DNS records + R2 bucket
```

Then push to `main` — the caller workflow in `.github/workflows/deploy.yml` handles CI/CD via the reusable workflow. See [docs/setup-guide.md](docs/setup-guide.md) for the full step-by-step walkthrough, and [docs/credentials.md](docs/credentials.md) for exactly which API tokens/keys each provider needs (and the Cloudflare traps).

### Roll seeds locally (Mac)

Find a great world seed by testing against the real modded server:

```bash
./dev seed-roll                  # batch-test seeds (resumable)
./dev seed-report                # generate the top-N markdown report
```

Pick your favourite from the generated report, explore it in spectator mode, then set `SEED=<your_seed>` in `.env`.

## Upgrading

Bump `STACK_VERSION` in `.env` (or leave it as `v1` to track the latest v1.x.y):

```bash
./dev update                     # re-pulls the bundle + Docker images
./dev up                         # restart with the new version
```

### What a release contains

Each GitHub release `vX.Y.Z` on this repo:

- Tags every GHCR image (`defaults-seed`, `modpack-builder`, sidecars) with `X.Y.Z`, `X.Y`, `X`, `latest`
- Attaches a **stack bundle** tarball: compose files, all host-side operational scripts, default configs

### Compatibility promise

- **Major** (`v1` → `v2`): breaking changes to `.env` keys, overlay contract, or compose structure. Migration guide provided.
- **Minor** (`v1.1` → `v1.2`): new features, new default mods, config additions. Backwards-compatible.
- **Patch** (`v1.2.0` → `v1.2.1`): bug fixes, mod pin updates. Drop-in safe.

Consumers pinning `STACK_VERSION=v1` automatically receive minor and patch updates.

## Architecture

```plaintext
                          ┌────────────────────────────────────────────────┐
                          │ Linux VPS (Ubuntu 24.04, hardened)             │
  Friends (Java) ─────────┼─ DNS A: mc.example.com ─► :25577 ─────────────►│ mc (Fabric 1.21.1, ~150 mods)
   mc.example.com:25577   │   (+ SRV record hides the port)                │  ├ autopause when empty
                          │                                                │  └ RCON :25575 (internal only)
  Friends (browser) ──────┼─ Cloudflare Tunnel (HTTP only):                │
   map.example.com        │    map/status/mods ─► nav-proxy ─► BlueMap/Kuma│ sidecars:
   pack.example.com       │    pack             ─► pack-web (nginx)        │  mc-backup (restic ► R2, 12h)
   status.example.com     │                                                │  idle-tasks (Chunky pre-gen, GC)
   mods.example.com       │                                                │  mod-checker (daily update page)
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
| `mc-backup` | `itzg/mc-backup` | cloud | restic snapshots to R2 every 12h, `save-off` consistency |
| `minio` + `minio-init` + `mc-backup-local` | minio / itzg | local | Local S3 stand-in so backups work identically in dev |
| `uptime-kuma` + `kuma-init` | louislam / ghcr.io/.../kuma-init | both | Monitoring + one-shot idempotent provisioning from `config/uptime-kuma/kuma-config.json` |
| `nav-proxy` | nginx | both | Injects the server nav bar into every web page via `sub_filter` |
| `cloudflared` | cloudflare | cloud | HTTPS tunnel for web services (never the game port) |
| `pack-web` | nginx | both | Serves the `.mrpack`, download page, and the mirrored mod JARs (`/mods/`, Cloudflare edge-cached) from `modpack/dist/` |
| `idle-tasks` | ghcr.io/.../idle-tasks | cloud | When empty: save, BlueMap render, GC, Chunky pre-generation |
| `mod-checker` | ghcr.io/.../mod-checker | both | Daily (06:00 UTC) mod update check, HTML page at mods.DOMAIN |
| `discord-sync` | ghcr.io/.../discord-sync | both | Discord bot: `/register`, `/mc` admin commands, role→whitelist sync |

**Ports:** game `25577/tcp` (host) → `25565` (container), voice `24454/udp`, RCON `25575` (Docker network only), Kuma `3001` and pack-web `8080` bound to localhost only.

**Autopause:** the JVM freezes when the server has been empty for 10 minutes. RCON stops responding while paused — scripts and monitors treat "no RCON" as paused, not down. Never add anything that pokes the game port on an interval (it wakes the server); `idle-tasks` does this deliberately, but only to keep Chunky running.

## Configuration

Three layers, one direction of truth:

1. **Platform defaults** — baked into the `defaults-seed` image (configs, mod list, datapacks). These are the starting point.
2. **Consumer overlay** — `overlay/` in your consumer repo (extra mods, config overrides, branding). Applied on top of defaults by the seed container.
3. **`.env`** (git-ignored) — all settings and secrets for local use. Recoverable from 1Password (`./ops op-env > .env`).
4. **GitHub `production` environment** (Settings → Environments): secrets and variables. CI generates the server `.env` entirely from these — `./ops github-env-sync` pushes them from your local `.env`.

**On every full CI deploy, the server's `.env` is regenerated** from the GitHub environment secrets. Hand-edits to `.env` on the server don't survive the next full deploy — change the source of truth instead.

**1Password** (optional) can serve as a recovery store for secrets. `./ops op-env > .env` rebuilds `.env` from 1Password references; `./ops op-sync` pushes local changes back.

### Example `.env` settings

```bash
STACK_VERSION=v1
BRAND_NAME="My Server"
MC_VERSION=1.21.1
SEED=your_seed
SPAWN_X=0
SPAWN_Y=64
SPAWN_Z=0
MEMORY=6G
DOMAIN=example.com
SERVER_PORT=25577
VIEW_DISTANCE=12
SIMULATION_DISTANCE=8
DISCORD_ADMIN_ROLE_ID=000000000000000000
DISCORD_PLAYER_ROLE_ID=000000000000000000
```

## Repository layout

This is the **platform repo** — it builds and publishes images, the stack bundle, and reusable workflows. Consumers don't clone this; they copy `examples/consumer/`.

```
.
├── AGENTS.md                        # AI agent constraints, traps, and access — read first
├── COMMANDS.md                      # Command reference (player, admin, RCON, Discord)
├── README.md                        # This file
├── docker/                          # Dockerfiles for all published GHCR images
│   ├── defaults-seed/               #   platform defaults seeder
│   ├── modpack-builder/             #   client pack builder
│   ├── discord-sync/                #   Discord bot
│   ├── idle-tasks/                  #   idle maintenance runner
│   ├── kuma-init/                   #   Uptime Kuma provisioner
│   └── mod-checker/                 #   mod update checker
├── examples/consumer/               # Consumer scaffold — copy this to start your server
├── scripts/                         # Operational + build scripts (see table below)
├── config/                          # Default server configs, mod list, messages, nginx, etc.
├── modpack/                         # Client pack manifest + overrides + built .mrpack (dist/)
├── assets/                          # Placeholder brand assets (SVG icon, logo, cover, favicon)
├── docs/                            # Setup guide, customisation, releasing
├── docker-compose.yml               # Full stack, local/cloud profiles
├── .env.example                     # Secrets template
└── .github/workflows/               # deploy.yml, deploy-reusable.yml, lint.yml, publish.yml, etc.
```

## Scripts

Scripts fall into three categories depending on where they live and who runs them.

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
| `restart-service.sh` | Mac | Force-recreate a sidecar on production (never mc — use deploy.sh) |
| `reset-seed.sh` | Mac | World reset with a new seed (backs up first, triple-confirmed) |
| `discord-notify.sh` | any | Send templated messages to the Discord webhook |
| `discord-cleanup.sh` | Mac | Delete all bot/webhook messages from a Discord channel |
| `discord-pin-sync.sh` | Mac | Sync the #general welcome pin from messages.json |
| `ddns-update.sh` | local host | Cloudflare dynamic DNS for home hosting (cron-installable) |
| `cache-assets.sh` | Mac | Snapshot Docker images, mod JARs, offline client bundles |
| `seed/*` | Mac | Batch seed testing, scoring, reports |
| `lib.sh` | (sourced) | Shared utilities: env loading, RCON, `sync_mod_configs`, provider detection |

### Image scripts (baked into GHCR images, not run directly)

| Script | Image | What it does |
| --- | --- | --- |
| `build-modpack.sh` | modpack-builder | Build versioned `.mrpack` + download page from the manifest |
| `check-pack-coherence.py` | modpack-builder | Validate pack manifest consistency |
| `modrinth-api.py` | modpack-builder | Bulk Modrinth resolution with connection reuse and rate-limit handling |
| `discord-sync.py` | discord-sync | Discord bot: `/register`, `/mc` commands, role sync |
| `kuma-provision.py` | kuma-init | One-shot Kuma provisioning from kuma-config.json |
| `idle-tasks.sh` | idle-tasks | Save/render/GC/Chunky when the server is empty |
| `check-updates.sh` | mod-checker | Mod update check, HTML status page generation |

### Template-only scripts (for platform development, not shipped)

| Script | What it does |
| --- | --- |
| `pin-mod-versions.sh` | Re-pin every mod to its latest build (used by mod-updates.yml) |
| `check-modrinth-compat.sh` | Check the mod list against a target MC version/loader |
| `build-mod-update-report.py` | Build the mod-update PR body with changelogs |
| `client-defaults.sh` | Diff/sync shipped client defaults against the source Prism instance |
| `test-scripts.sh` | shellcheck + py_compile + compose validation |
| `build-stack-bundle.sh` | Assemble the release tarball |

Every script has a header comment with usage, context, and gotchas — **read the header before running it**.

## How to do things

### Add or remove mods

| What | Edit | Then |
| --- | --- | --- |
| Add a server mod | `overlay/mods-extra.txt` in your consumer repo | `./dev up` (locally) or push to `main` (production) |
| Remove a default mod | `overlay/mods-remove.txt` in your consumer repo | Same |
| Client mod | `modpack/adventure.mrpack.json` (`_clientMods.required` / `.optional`) | Push — CI rebuilds `.mrpack` |
| Datapack | `overlay/config/datapacks/` or `overlay/mods-extra.txt` with `datapack:` prefix | Push (full deploy) |

Mods must target **Fabric for 1.21.1**. Before adding anything, check its dependencies via the Modrinth API and add them too (see the mandatory checklist in [AGENTS.md](AGENTS.md#mods)). Dependency libraries (`fabric-api`, `yungs-api`, `moonlight`, `balm`, `lithostitched`, `fabric-language-kotlin`) are never optional.

Modrinth downloads are **hash-gated**: the itzg image only re-syncs mods when the merged mods file changes; unchanged inputs → unchanged file → fast boots.

**Auto-updates** come via **packwiz**: the build generates `dist/packwiz/` (pack.toml + per-mod metafiles pointing at the mirror), and the one-click Prism instance zip runs `packwiz-installer` as a pre-launch task — every launch hash-syncs mods and pack configs from the CDN.

**Weekly update PRs:** `mod-updates.yml` runs every Monday (or `gh workflow run mod-updates.yml`), re-pins everything via `pin-mod-versions.sh --apply`, and opens/refreshes a PR on `mod-updates/auto` with per-mod changelogs.

### Update Minecraft version

**Big job:** all ~150 server mods and ~110 client mods must support the target version.

1. Back up: `./ops backup`
2. Check compatibility: `./scripts/check-modrinth-compat.sh --version <target>`
3. Update `MC_VERSION` in `.env`, re-pin: `./scripts/pin-mod-versions.sh --version <target> --apply`
4. Test locally: `./dev up` — watch for mod load errors
5. Deploy: push to `main`; then re-render BlueMap: `docker exec -i mc rcon-cli "bluemap update"`

Terralith, Incendium, and Nullscape generate custom terrain — version changes can cause visible chunk borders. Test on a copy first.

### Manage players

Players self-serve through Discord — this is the normal path:

1. Player joins the Discord and runs `/register <minecraft_username>` (verified against Mojang).
2. An admin gives them the `@Player` role.
3. Within 60s the bot whitelists them via RCON. `@Admin` role additionally grants op. Role removal de-whitelists/de-ops on the same cycle.

Manual RCON still works and takes effect immediately:

```bash
docker exec -i mc rcon-cli "whitelist add Alex"
docker exec -i mc rcon-cli "lp user Alex parent add admin"     # LuckPerms admin group
docker exec -i mc rcon-cli "op Alex"                            # full operator
```

See [COMMANDS.md](COMMANDS.md) for the LuckPerms permission model and the full `/mc` Discord command set.

### Discord integration

Two clients share **one bot token** — understand this before touching anything Discord-side:

| Client | Runs in | Owns |
| --- | --- | --- |
| **dcintegration** (Fabric mod) | `mc` container | Chat bridge: game↔Discord chat, join/leave/death/advancement posts (via webhook) |
| **discord-sync** (`scripts/discord-sync.py`) | `discord-sync` container | **All slash commands** (`/register`, `/unregister`, `/mc ...`), role→whitelist sync, audit log, command relay |

Slash commands are **guild-scoped and owned by discord-sync**, which purges the global command registry at every boot. The mod's command feature must stay off (`[commands] enabled = false` in the live `data/config/Discord-Integration.toml`) or it bulk-overwrites the registry on every mc boot and wipes `/mc` and `/register` — `deploy.sh` enforces this on every full deploy.

Troubleshooting:

| Symptom | Check |
| --- | --- |
| Slash commands missing from the client | `docker logs discord-sync` for "Slash commands synced"; restart discord-sync to re-sync; Ctrl+R the Discord client |
| Commands present but failing | `docker logs discord-sync` — RCON errors mean mc is paused or the password drifted |
| No chat relay | `docker logs mc \| grep -i discord`; check `botToken` in the live TOML |
| Registry state (ground truth) | `GET /applications/<app_id>/guilds/<guild_id>/commands` with the bot token — guild should list `register`, `unregister`, `mc`; global should be `[]` |

### Backups

Automatic every **12h** via `mc-backup` (restic → Cloudflare R2), with RCON `save-off`/`save-on` for consistency. Retention: 3 daily, 1 weekly, 1 monthly (fits R2's free 10GB).

**Excludes** (regenerable data): `bluemap`, `mods`, `libraries`, `versions`, `logs`, `crash-reports`, `kuma`, `DistantHorizons.sqlite`, `poi`, `ledger.sqlite`, `dynamic-data-pack-cache`. Only world, player data, and config are backed up.

```bash
./ops backup                                     # manual backup
docker logs mc-backup --tail 50                      # verify (look for "snapshot ... saved")
```

**Restore** (on the server):

```bash
docker compose --profile cloud down
export RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}"
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" RESTIC_PASSWORD
restic snapshots --last 5
restic restore latest --target /tmp/mc-restore       # or a specific snapshot ID
rsync -av /tmp/mc-restore/data/ ./data/ && rm -rf /tmp/mc-restore
docker compose --profile cloud up -d
```

**`RESTIC_PASSWORD` can't be recovered.** Store it somewhere safe (1Password, a password manager, etc.). All backups are unreadable without it.

### Deploy to production

Pushing to `main` in a consumer repo triggers the caller workflow, which invokes the reusable `deploy-reusable.yml` from this platform repo. The workflow diffs against **the server's currently deployed commit** and picks a tier:

| Mode | Trigger | What happens |
| --- | --- | --- |
| **Full** | `docker-compose.yml`, `.env`, mod list, synced mod-config dirs, datapacks, bluemap, deploy scripts, releases | Secrets uploaded → deploy.sh: countdown → kick → whitelist-block → save → restart → regenerate .env → config sync → permissions → whitelist restore → Discord notify |
| **Infra** | Other config or script changes | Image pull + compose up (mc untouched via `--no-recreate`) + force-recreate sidecars |
| **Pull** | Docs, CI, modpack manifests | Config sync only |

After a full deploy, CI also rebuilds the `.mrpack` + download page (Discord ping only when mod content actually changed).

### Server access

Production host is `DROPLET_HOST` in `.env` (also a GitHub Actions variable). The server directory is `~/server`.

```bash
ssh -i ~/.ssh/mc_deploy_key deploy@SERVER                                    # shell
ssh -i ~/.ssh/mc_deploy_key deploy@SERVER 'docker exec -i mc rcon-cli "list"' # RCON one-shot
ssh -i ~/.ssh/mc_deploy_key deploy@SERVER 'docker logs mc --tail 50'          # log snapshot
./ops stats                                                                   # system + container summary
```

RCON is never exposed publicly — it only exists inside the Docker network, reached via `docker exec`.

### Reset the world (launch events)

`./ops reset-seed <seed>` — backs up (restic + tar), stops the stack, deletes world/BlueMap/Chunky/DH data, updates the seed, and restarts. Triple-confirmed and prints undo instructions. Commit `.env` afterwards.

## Contributing

This is the platform repo. Contributors work here to improve the images, bundle scripts, default configs, and workflows that all consumers inherit.

**Repo layout:** `docker/` contains Dockerfiles for all GHCR images. `scripts/` has the operational scripts shipped in the bundle plus template-only tooling. `config/` holds the default configs seeded by the `defaults-seed` image.

**How defaults get released:** push to `main` triggers image builds. Cut a release with `vX.Y.Z` tag to publish the stack bundle and tag images. See [docs/releasing.md](docs/releasing.md).

**Local development:** contributors can run the full stack from a checkout:

```bash
cp .env.example .env
./scripts/dev-up.sh              # or: docker compose --profile local up -d
```

**Quality gates:** `./scripts/test-scripts.sh --quick` (shellcheck, py_compile, compose validation). CI runs the same plus yamllint.

See [CONTRIBUTING.md](CONTRIBUTING.md) for commit conventions, mod change checklists, and PR expectations.

## Troubleshooting

**Server won't start:**

| Symptom             | Fix                                                                                     |
| ------------------- | --------------------------------------------------------------------------------------- |
| Mod incompatibility | A mod has no 1.21.1 build. Check `./scripts/check-updates.sh`, mark it `?` or remove it |
| Missing dependency  | Check the crash log for the mod ID, add the library to the mod list                     |
| Out of memory       | Raise `MEMORY` in `.env`; look for `OutOfMemoryError` in logs                     |
| Port conflict       | `lsof -i :25577`                                                                        |
| Mod download fails  | Verify the slug + pinned ID on Modrinth; use `./dev up --offline` if Modrinth is down   |
| Modrinth `429 Too Many Requests` on first boot | Expected — ~150 downloads trip the per-IP limit. Completed downloads persist, the container retries, and the scripts wait for it to converge (can take 10+ min). On servers, `./ops prepare` offers to upload your locally-synced mods instead, skipping Modrinth entirely |

**Can't connect:**

| Check           | Command                                                                                 |
| --------------- | --------------------------------------------------------------------------------------- |
| Server running? | `docker ps` — mc should be `(healthy)`                                                  |
| RCON responds?  | `docker exec -i mc rcon-cli "list"` (no response can just mean autopaused)              |
| Whitelisted?    | `docker exec -i mc rcon-cli "whitelist list"` — or did they `/register` + get the role? |
| Firewall open?  | `sudo ufw status` — `25577/tcp` allowed                                                 |
| DNS resolves?   | `dig mc.example.com` → server IP                                                        |
| Correct port?   | `mc.example.com:25577` (the SRV record usually makes the bare hostname work)            |

**Backup fails:** verify R2 credentials in `.env`; `restic snapshots` ("repository does not exist" → `restic init`); `df -h`; `docker logs mc-backup --tail 50`.

**Voice chat:** UDP `24454` must be open in UFW **and** mapped in Docker. Icon error = mod version mismatch, re-import the `.mrpack`. Walls not muffling = Sound Physics is client-side, ships in the pack.

**BlueMap:** force render `bluemap update`; nuclear option `bluemap purge world` then update; check `https://map.example.com` routes through the tunnel.

**Uptime Kuma:** `config/uptime-kuma/kuma-config.json` is the source of truth — `kuma-init` re-syncs on every deploy and recreates anything deleted only via the UI. Monitors are deliberately minimal (container health + HTTP checks): every game-port probe that was ever added woke the server from autopause, so don't add them back.

**Discord:** see [Discord integration](#discord-integration) above.

**Performance (lag, high MSPT):**

| Action       | Command                                                                               |
| ------------ | ------------------------------------------------------------------------------------- |
| Health check | `docker exec -i mc rcon-cli "spark health"` — TPS 20, MSPT < 50ms                    |
| Profile      | `spark profiler start` … 30-60s … `spark profiler stop`                               |
| Pre-generate | idle-tasks runs Chunky automatically when empty; progress via `/mc status` in Discord |
| Reduce load  | Lower `VIEW_DISTANCE`/`SIMULATION_DISTANCE` in `.env`                          |

## Deployment targets

Deploys to any Ubuntu 24.04 machine. `./ops provision` routes to the provider; `.env` + `.env` hold everything.

| Target | Approximate cost | Notes |
| --- | --- | --- |
| **Hetzner Cloud** | ~€11/mo (cx33 x86) | Free L3/L4 DDoS protection, EU DCs. ARM (cax21, ~€7.5/mo) is cheaper when available — the provision script offers live alternatives if your pick is sold out |
| **DigitalOcean** | ~$48/mo (4vCPU/8GB) | Multiple DC regions available |
| **Local machine** | electricity | Needs static IP or DDNS (`./ops ddns --install-cron`), router port-forwards for `25577/tcp` + `24454/udp`, ideally a UPS |
| **WSL2** | electricity | Use `networkingMode=mirrored` in `.wslconfig`, or `netsh portproxy` (re-run after WSL restarts) |

Home hosting: the Cloudflare tunnel still covers HTTP services; the game port must be forwarded directly (free tier is HTTP-only). Consider [TCPShield](https://tcpshield.com) (free tier) to hide your home IP — add the `tcpshield` mod so the server sees real player IPs, and note it proxies TCP only (voice stays direct).

## Backup alternatives

`mc-backup` is plain restic — swap `RESTIC_REPOSITORY` in `.env` to change backend: Backblaze B2 (`s3:https://s3.REGION.backblazeb2.com/BUCKET`, 10GB free), local path, `sftp:user@host:/path`, MinIO, Wasabi. Init the new repo (`restic -r <repo> init`), restart `mc-backup`, test with `./ops backup`.

## Security

`./ops harden` applies (idempotent, run once at provision — **never during a CI deploy**, it restarts Docker):

- **UFW**: default deny inbound; only SSH, game TCP, voice UDP
- **SSH**: key-only, no root, fail2ban (4 failures → 24h)
- **Rate limiting** (iptables hashlimit in UFW before.rules): game 6 conn/IP/min, voice 10 pkt/IP/min, SYN 30/IP/min; Docker bridge traffic exempt
- **fail2ban jails**: MC connection spam (10 in 2m → 1h), login flood (20 in 5m → 6h), nginx exploit scans (`wp-login.php`, `/.env`, admin panels → 1 week on first hit, banned on the real `X-Forwarded-For` IP); Cloudflare ranges, Docker nets, localhost whitelisted
- **Docker**: `iptables=false` so containers can't bypass UFW (NAT/forwarding handled in before.rules); log rotation
- **systemd log pipes** feed container logs to `/var/log/*` for fail2ban (Docker container IDs change on recreate, so fail2ban can't read them directly)
- **journald** capped at 200MB; 2G swap, swappiness 10
- **`ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE`** — non-negotiable in production

## Fixed decisions

Minecraft **1.21.1**, Fabric, Docker, conventional networking, Cloudflare HTTP tunnel only, Incendium-only Nether, restic to R2, guild-scoped Discord commands owned by discord-sync. See [AGENTS.md](AGENTS.md#fixed-decisions-template-defaults) for the full list and rationale.

## Acknowledgements

- [itzg/docker-minecraft-server](https://github.com/itzg/docker-minecraft-server) — the Docker image this project is built on

This project is released under the [MIT Licence](LICENSE).
