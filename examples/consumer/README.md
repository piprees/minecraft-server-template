# My Minecraft Server

A consumer repo powered by [minecraft-server-template](https://github.com/piprees/minecraft-server-template).

To get started, run `./ops setup` and follow the instructions carefully.

## Quick start

<!-- Maintainer note: keep this section to 3 commands maximum.
     Everything below here is optional for local-only development. -->

The setup wizard walks you through every credential (with the exact dashboard pages and settings), writes `.env`, and can take you all the way from local test to production.

Local-only alternative, if you'd rather fill the file in by hand:

```bash
cp .env.example .env          # every variable documented in comments
./dev up                       # pulls the stack bundle + starts everything
```

Connect at `mc.<LOCAL_DOMAIN>:<SERVER_PORT>` (default `mc.myserver.local:25577`). Add the `/etc/hosts` entries printed by `./dev up` for subdomain routing.

```bash
./dev logs                     # tail the Minecraft server logs
./dev rcon "list"              # run an RCON command
./dev rcon                     # interactive RCON console
./dev down                     # stop everything
```

### Build the client modpack

```bash
./dev pack                     # outputs to ./modpack-dist/
```

### Update the platform

`STACK_VERSION` in `.env` pins the platform release: a major pin like `v2` floats on the latest `v2.x.y`, an exact pin (`v2.0.1`) holds it, and unset tracks the latest release. `./ops setup` records the line in use. To update:

```bash
./dev update                   # re-pulls the bundle + Docker images
./dev up                       # restart with the new version
./dev rollback                 # list available versions to roll back to
./dev rollback v2.6.0          # revert to a specific version
```

### Sync everything (local + server + GitHub)

```bash
./ops sync                     # stops local, updates bundle, syncs .env to GitHub, deploys to server, starts local
```

One command to bring local and production into alignment.

### Update your extra mods

```bash
./dev pin                      # re-pin overlay/mods-extra.txt to latest builds
git diff overlay/mods-extra.txt
./dev up                       # or push to main to deploy
```

The `Updates` workflow (`.github/workflows/update.yml`) does the same thing weekly and opens a PR with the diff, plus a note when a new stack release is available.

### Roll seeds locally

```bash
./dev seed-roll                # parallel-roll seeds for every dimension
./dev seed-rescore             # recompute scores vs current configs (no Docker)
./dev seed-status              # candidate counts, winners, score freshness
```

Rolls indefinitely (Ctrl+C to finish). Winners are auto-written into the config; the live viewer at `http://127.0.0.1:8765/viewer.html` lets you pick manually. Measurements are banked — rescoring against updated configs never requires re-rolling.

### Cache assets for offline use

```bash
./dev cache                    # snapshot Docker images, mod JARs, offline client bundles
```

## Going to production

The `ops` script delegates to the bundle's operational scripts with your consumer environment loaded:

```bash
./ops setup                    # interactive wizard: credentials -> .env
./ops preflight                # validate everything before provisioning
./ops provision                # create the cloud server (Hetzner by default)
./ops harden                   # lock down SSH, firewall, fail2ban
./ops prepare                  # deploy key, .env on server, GitHub env sync
./ops cloudflare               # tunnel + DNS records + R2 bucket
./ops update                   # pull latest bundle + images on server, restart
./ops update v1.0.18           # pin to a specific release version
```

Then push to `main` -- the caller workflow in `.github/workflows/deploy.yml` handles CI/CD via the reusable workflow.

### Operations

```bash
./ops doctor                   # full production health triage
./ops ssh                      # drop into server shell
./ops rcon "list"              # RCON command (always targets production)
./ops chunky                   # Chunky pre-generation status
./ops status                   # all container statuses
./ops live-logs mc --errors    # recent errors/warnings
./ops backup                   # trigger an immediate backup
./ops wipe-chunk --block -1808 -2832  # delete a region file (regenerates from seed)
./ops reset-seed <seed>        # world reset (triple-confirmed, backs up first)
```

Run `./ops help` for the full list.

## Customising your server

### Add a server mod

Add a line to `overlay/mods-extra.txt`:

```
tree-harvester:AANobbMI
```

Then `./dev up` (locally) or push to `main` (production).

### Remove a default mod

Add the slug to `overlay/mods-remove.txt`:

```
distant-horizons
```

### Override a config file

Place the file in `overlay/config/` with the same path as the template's `config/` directory. Your file replaces the platform default.

### Tune worldgen

- **Terrain shape**: copy the platform's `config/tectonic.json` to `overlay/config/tectonic.json` and adjust the dials (keep every key — a partial file silently falls back to factory defaults). New chunks only; existing terrain keeps its shape.
- **Structure frequency**: the platform ships a "sparse & natural" `structures` datapack. Swap preset with `cp -r .stack/current/stack/config/datapack-presets/dense/structures overlay/config/datapacks/structures` (or `sparse`); delete the overlay copy to return to default.
- **Per-dimension character**: each dimension in the multiverse config accepts optional `"noiseSettings"` (`adventure:wide` / `adventure:compressed`) and `"structureDensity"` (`dense`/`normal`/`sparse`/`none`); dimensions with `"hostileSpawning": false` automatically lose dungeon-theme structures.
- **Your own structure mods**: mods you add via `overlay/mods-extra.txt` keep their default spawn rates, and per-dimension density can't classify their structure sets until you theme them. Drop `overlay/config/structure_themes.json` mapping each set id to a theme (`dungeon`, `settlement`, `maritime`, `landmark`, `deco`, `loot`) — e.g. `{"somemod:big_dungeon": "dungeon"}` — and `structureDensity` plus the peaceful overlay apply to them too.

### Rebrand

Edit `.env`: `BRAND_NAME`, `BRAND_SLUG`, `MOTD`, `DISCORD_INVITE_URL`. Place custom assets in `overlay/assets/` (see `overlay/assets/README.md`).

| File | What to customise |
| --- | --- |
| `.env` | `BRAND_NAME`, `BRAND_SLUG`, `MOTD`, `DOMAIN`, `SEED`, spawn coords, Discord IDs |
| `overlay/config/messages.json` | Player/Discord-facing messages, welcome pin |
| `overlay/config/essentialcommands/rules.txt` | In-game `/rules` text |
| `overlay/assets/` | icon.svg, logo.svg, cover.svg, favicon.svg |

### Customise the web pages

All four surfaces ship with one shared dark palette and get the nav bar injected by the nav-proxy. To restyle them:

| Surface | Override with | Notes |
| --- | --- | --- |
| `pack.DOMAIN` (download page) | `overlay/modpack/template/index.html` | Replaces the whole page template; rebuild with `./dev pack` or push |
| `mods.DOMAIN` (mod status) | `overlay/config/mods-page.css` | Appended after the default styles, so override selectively |
| `status.DOMAIN` (Uptime Kuma) | `overlay/config/uptime-kuma/kuma-config.json` | Full config replacement; copy the default from the template repo and edit `statusPage.customCSS` |
| `map.DOMAIN` (BlueMap) | upstream webapp | Only the nav bar is ours |

The nav bar itself lives in the template's `config/nginx/nav-proxy.conf` (platform-level; open an issue or PR there for structural changes).

Changes under `overlay/` deploy as the infra tier on push — no server restart.

### Loading screen

The client pack includes [Custom Splash Screen](https://modrinth.com/mod/custom-splash-screen) for a branded loading experience. Override the background image:

```
overlay/modpack/overrides/configureddefaults/config/customsplashscreen/backgrounds/
```

Multiple images in the `backgrounds/` directory are randomly selected at startup. Override the logo:

```
overlay/modpack/overrides/configureddefaults/config/customsplashscreen/square_logo.png
```

To show the logo (hidden by default), create `overlay/modpack/overrides/configureddefaults/config/customsplashscreen.json`:

```json
{
  "logoStyle": "Aspect1to1"
}
```

### Title screen panorama

The template ships a `server-panorama` resource pack with default cubemap images. Override by dropping 6 cubemap face PNGs into:

```
overlay/modpack/overrides/configureddefaults/resourcepacks/server-panorama/
└── assets/minecraft/textures/gui/title/background/
    ├── panorama_0.png   (South)
    ├── panorama_1.png   (West)
    ├── panorama_2.png   (North)
    ├── panorama_3.png   (East)
    ├── panorama_4.png   (Up)
    └── panorama_5.png   (Down)
```

Cubemap faces must be **square** screenshots at **exactly 90° FOV** (`fov:0.5` in options.txt) with `fovEffectScale:0.0`. See the [template docs](https://github.com/piprees/minecraft-server-template/blob/main/docs/customisation.md#capturing-cubemap-faces) for the step-by-step capture guide.

### Area titles from signs

Signs with place names show the text as an on-screen title when entering/leaving the area ([Areas](https://modrinth.com/mod/areas)).

> The web map runs as a standalone renderer container — it has no live player markers and no sign-marker layers (those needed the old in-process BlueMap mod). In exchange, the map stays online 24/7, even while the server is asleep.

### Starter kit

The starter kit is handed to every new player by [Starter Kit](https://modrinth.com/mod/starter-kit). The easiest way to build a kit is **in-game**: arrange your inventory exactly as the kit should be, then run `/starterkit set` as an op. Copy the resulting files from `data/config/starterkit/` into `overlay/config/starterkit/` so they survive redeploys.

### Resource packs

Resource packs are declared in the template's manifest and auto-install with the modpack. They're **enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt` — the build fails if an enabled filename doesn't match a downloaded pack (prevents silently disabled packs after version bumps).

## Command reference

### `./dev` (local development)

| Command | Description |
| --- | --- |
| `./dev up` | Start the local dev stack |
| `./dev down` | Stop the local dev stack |
| `./dev logs` | Tail the Minecraft server logs |
| `./dev rcon "list"` | Run an RCON command locally |
| `./dev rcon` | Interactive RCON console |
| `./dev pack` | Build the client modpack into `./modpack-dist/` |
| `./dev pin` | Re-pin `overlay/mods-extra.txt` to latest mod builds |
| `./dev update` | Pull the latest stack bundle + Docker images |
| `./dev sync` | Update everything: local down, update, env sync to GitHub, server update, local up |
| `./dev seed-roll` | Parallel-roll seeds for every dimension, auto-pick winners |
| `./dev seed-rescore` | Recompute candidate scores vs current configs (no re-rolling) |
| `./dev seed-status` | Candidate-bank status: counts, winners, score freshness |
| `./dev cache` | Snapshot Docker images, mod JARs, offline client bundles |
| `./dev start <service>` | Start a stopped local service |
| `./dev stop <service>` | Stop a running local service |
| `./dev restart <service>` | Force-recreate a local service |
| `./dev status` | Show all local container statuses |

### `./ops` (production)

| Command | Description |
| --- | --- |
| `./ops setup` | Interactive wizard: credentials, .env, deploy |
| `./ops preflight` | Validate everything before provisioning |
| `./ops provision` | Create the cloud server |
| `./ops harden` | Lock down SSH, firewall, fail2ban |
| `./ops prepare` | Deploy key, .env on server, GitHub env sync |
| `./ops cloudflare` | Tunnel + DNS records + R2 bucket |
| `./ops update` | Pull latest bundle + images on server, restart |
| `./ops doctor` | Full production health triage |
| `./ops ssh` | Drop into server shell |
| `./ops ssh '<command>'` | Run a one-shot command on the server |
| `./ops rcon "list"` | RCON command (always targets production) |
| `./ops chunky` | Chunky pre-generation status |
| `./ops status` | All container statuses |
| `./ops logs mc --tail 200` | Recent log snapshot |
| `./ops stats --once` | System + container stats snapshot |
| `./ops backup` | Trigger an immediate backup |
| `./ops wipe-chunk --block X Z` | Delete a region file (regenerates from seed) |
| `./ops reset-seed <seed>` | World reset (triple-confirmed, backs up first) |
| `./ops github-env-sync` | Push local .env to GitHub production environment |
| `./ops start <service>` | Start a stopped production service |
| `./ops stop <service>` | Stop a running production service |
| `./ops restart <service>` | Force-recreate a production service |
| `./ops map render` | Force a full map re-render (normal updates are automatic) |

For in-game commands, RCON recipes, Discord `/mc` commands, and the LuckPerms permission model, see the [Commands reference](https://github.com/piprees/minecraft-server-template/blob/main/COMMANDS.md).

## Directory structure

```
.
├── .env                        # git-ignored configuration + secrets
├── overlay/                    # your customisations
│   ├── mods-extra.txt          # server mods to add
│   ├── mods-remove.txt         # default mods to remove
│   ├── config/                 # config file overrides
│   ├── modpack/                # client pack overlay
│   └── assets/                 # branding (icon, logo, cover)
├── dev                         # local dev commands (up/down/logs/rcon/pack/sync)
├── ops                         # operational commands (setup/provision/deploy/...)
├── .github/workflows/deploy.yml # CI/CD caller workflow
├── .github/workflows/update.yml # weekly mod re-pin PR + stack release notes
├── .stack/                     # git-ignored bundle cache
├── data/                       # git-ignored world + server state
├── modpack-dist/               # git-ignored built modpack
└── backups/                    # git-ignored local backups
```
