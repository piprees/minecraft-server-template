# My Minecraft Server

A consumer repo powered by [minecraft-server-template](https://github.com/piprees/minecraft-server-template). Run `./ops setup` and follow it carefully: the wizard walks you through every credential (with the exact dashboard pages and settings), writes `.env`, and can take you from local test all the way to production.

## Quick start

<!-- Maintainer note: keep this section to 3 commands maximum.
     Everything below here is optional for local-only development. -->

Local-only alternative, if you'd rather fill the file in by hand:

```bash
cp .env.example .env          # every variable documented in comments
./dev up                      # pulls the stack bundle + starts everything
```

Connect at `mc.<LOCAL_DOMAIN>:<SERVER_PORT>` (default `mc.myserver.local:25577`). Add the `/etc/hosts` entries `./dev up` prints for subdomain routing.

```bash
./dev logs                    # tail the Minecraft server logs
./dev rcon "list"             # RCON command ("./dev rcon" alone opens a console)
./dev pack                    # build the client modpack into ./modpack-dist/
./dev cache                   # snapshot Docker images, mod JARs, offline client bundles
./dev down                    # stop everything
```

### Update the platform

`STACK_VERSION` in `.env` pins the platform release: a major pin like `v2` floats on the latest `v2.x.y`, an exact pin (`v2.0.1`) holds it, and unset tracks the latest release. `./ops setup` records the line in use.

```bash
./dev update                  # re-pull the bundle + Docker images
./dev up                      # restart on the new version
./dev rollback                # list versions; "./dev rollback v2.6.0" reverts to one
./ops sync                    # local down → update → .env to GitHub → server deploy → local up
```

### Update your extra mods

```bash
./dev pin                     # re-pin overlay/mods-extra.txt to latest builds
git diff overlay/mods-extra.txt
./dev up                      # or push to main to deploy
```

The `Updates` workflow (`.github/workflows/update.yml`) does the same weekly and opens a PR with the diff, plus a note when a new stack release is available.

### Seed rolling

`./dev seeds` opens the seed viewer the mod hosts. Roll candidate seeds for any dimension, compare their maps and scores, fly around the best two in a throwaway world, and pick one — the chosen seed (and where you were standing) is written into that dimension's overlay config.

**When you have picked your seeds, turn the renders off.** Every server start draws a map for every configured dimension so the viewer has something to show — measured at 2118% CPU for a few hours on a 82-dimension pack, against 44% with it off. That is the price of having the viewer ready to browse, and it is worth paying while you are still choosing. Once you have locked your seeds in, it is pure waste on every boot. Put these in `.env`:

```bash
SEED_PRIME=false          # stop drawing every dimension at startup
SEED_RENDER_HIGH=false    # stop the full-size maps
SEED_RENDER_LOW=false     # stop the thumbnails
```

The viewer still opens and still rolls when you ask it to — you are turning off the automatic batch, not the tool. Delete the lines (or set them back to `true`) whenever you want to roll again. If your server feels slow for the first few hours after a restart and you have never set these, this is the first thing to check.

## Going to production

The `ops` script delegates to the bundle's operational scripts with your consumer environment loaded:

```bash
./ops setup                   # interactive wizard: credentials → .env
./ops preflight               # validate everything before provisioning
./ops provision               # create the cloud server (Hetzner by default)
./ops harden                  # lock down SSH, firewall, fail2ban
./ops prepare                 # deploy key, .env on server, GitHub env sync
./ops cloudflare              # tunnel + DNS records + R2 bucket
./ops update                  # later: pull bundle + images on the server ("./ops update v1.0.18" pins)
```

Then push to `main` — the caller workflow in `.github/workflows/deploy.yml` handles CI/CD via the platform's reusable workflow.

Day to day: `./ops doctor` (full health triage — start here), `./ops ssh`, `./ops status`, `./ops logs mc --tail 200`, `./ops backup`, `./ops chunky`.

## Customising your server

### Mods

Add server mods to `overlay/mods-extra.txt` (one `slug:versionId` per line, e.g. `tree-harvester:AANobbMI`); remove defaults by slug in `overlay/mods-remove.txt` (e.g. `distant-horizons`). Then `./dev up` locally, or push to `main`.

Every default mod is removable without breaking the boot — including the worldgen pair (Tectonic, Terralith): the platform's structure datapacks strip removed mods' overrides automatically, and the custom-dimension noise presets are self-contained. Two caveats: remove a mod's dependents with it (`fabric-seasons-terralith-compat` goes when `terralith` goes), and removing a worldgen mod changes NEW terrain only — existing chunks keep their shape and new ones generate with vanilla semantics, so expect borders. CI's smoke removal matrix guards this promise.

**Keep the CLIENT pack in sync.** The client manifest is yours (forked), and ~50 default mods are required on BOTH sides — removing one of those server-side while clients still carry it gets every player kicked at the Fabric handshake ("Incompatible mod set"). Per removed slug: check `_clientMods.required` in `modpack/adventure.mrpack.json`, remove it there too along with any client-only dependents (removing `trinkets` also takes `charm-of-undying` and `elytra-slot`), then rebuild the pack — the coherence check catches dangling dependencies, and the build warns when a slug in `mods-remove.txt` is still required client-side.

### Config, worldgen, and branding

A file placed in `overlay/config/` at the same path as the platform's `config/` replaces that default. Changes under `overlay/` deploy as the infra tier on push — no server restart.

| Want | Do |
| --- | --- |
| Terrain shape | Copy the platform's `config/tectonic.json` to `overlay/config/tectonic.json` and adjust the dials — keep **every** key, a partial file silently falls back to factory defaults. New chunks only; existing terrain keeps its shape |
| Structure frequency | `cp -r .stack/current/stack/config/datapack-presets/dense/structures overlay/config/datapacks/structures` (or `sparse`); delete the overlay copy to return to the shipped "sparse & natural" preset |
| Per-dimension character | Optional `"noiseSettings"` (`adventure:wide` / `adventure:compressed`) and `"structureDensity"` (`dense`/`normal`/`sparse`/`none`) per dimension; `"hostileSpawning": false` also drops dungeon-theme structures |
| Your own structure mods | They keep default spawn rates until themed: `overlay/config/structure_themes.json` maps each structure set id to a theme (`dungeon`, `settlement`, `maritime`, `landmark`, `deco`, `loot`) — e.g. `{"somemod:big_dungeon": "dungeon"}` — and then `structureDensity` plus the peaceful overlay apply to them |
| Branding | `.env`: `BRAND_NAME`, `BRAND_SLUG`, `MOTD`, `DOMAIN`, `SEED`, spawn coords, Discord IDs; assets (icon/logo/cover/favicon SVG) in `overlay/assets/`, see `overlay/assets/README.md` |
| Messages and rules | `overlay/config/messages.json` (player/Discord messages, welcome pin), `overlay/config/essentialcommands/rules.txt` (in-game `/rules`) |

### Web pages

All four surfaces ship with one shared dark palette and get the nav bar injected by the nav-proxy.

| Surface | Override with | Notes |
| --- | --- | --- |
| `pack.DOMAIN` (download page) | `overlay/modpack/template/index.html` | Replaces the whole page template; rebuild with `./dev pack` or push |
| `mods.DOMAIN` (mod status) | `overlay/config/mods-page.css` | Appended after the default styles, so override selectively |
| `status.DOMAIN` (Uptime Kuma) | `overlay/config/uptime-kuma/kuma-config.json` | Full config replacement; copy the default from the template repo and edit `statusPage.customCSS` |
| `map.DOMAIN` (world map) | rendered by the `unmined-render` sidecar | Static output; only the nav bar and viewer shell are ours |

The nav bar itself lives in the template's `config/nginx/nav-proxy.conf.template` (platform-level; open an issue or PR there for structural changes).

### Client-side look

| What | Where | Constraints |
| --- | --- | --- |
| Loading screen background | `overlay/modpack/overrides/configureddefaults/config/customsplashscreen/backgrounds/` | Several images there are picked from at random on startup ([Custom Splash Screen](https://modrinth.com/mod/custom-splash-screen)) |
| Loading screen logo | `…/configureddefaults/config/customsplashscreen/square_logo.png` | Hidden by default; show it by creating `…/configureddefaults/config/customsplashscreen.json` containing `{"logoStyle": "Aspect1to1"}` |
| Title screen panorama | `overlay/modpack/overrides/configureddefaults/resourcepacks/server-panorama/assets/minecraft/textures/gui/title/background/` | 6 **square** PNGs, `panorama_0`–`panorama_5` = South, West, North, East, Up, Down, captured at exactly 90° FOV (`fov:0.5`, `fovEffectScale:0.0`) — [capture guide](https://github.com/piprees/minecraft-server-template/blob/main/.claude/skills/consumer-customisation/references/panorama-capture.md) |
| Starter kit | `overlay/config/starterkit/` | Arrange your inventory in-game exactly as the kit should be, run `/starterkit set` as an op, then copy the files out of `data/config/starterkit/` so they survive redeploys |
| Resource packs | Declared in the template's manifest, auto-installed with the modpack | Enabled by exact filename in `modpack/overrides/configureddefaults/options.txt`; the build fails on an enabled filename that wasn't downloaded, so refresh it when a pack's version bumps |

Signs carrying place names show an on-screen title when a player enters the area ([Areas](https://modrinth.com/mod/areas)). The web map is a static render — terrain only, no live player positions — and stays online 24/7, even while the server is asleep.

## Commands

`./dev help` and `./ops help` list every command with a description. The ones worth knowing before you run them:

| Command | What to know |
| --- | --- |
| `./dev link [path]` | Point `.stack/current` at a platform checkout (default `../minecraft-server-template`). Run **once**: the farm is symlinks, so an edited script or compose file and every rebuilt mod jar go live on the next `./dev up`. A `config/` edit also needs `./dev refresh-config`. Local only — deploys refuse it |
| `./dev unlink` | Restore the newest pulled release bundle (`./dev pull`, `update` and `rollback` also undo a link) |
| `./dev refresh-config` | Force-refresh platform config defaults into `data/config` (backs up first; your overlay still wins) |
| `./dev reset-world` | Delete the LOCAL world + player data — the same set `./ops reset-seed` deletes on production. Keeps `.env`, mods, config, overlay and the seed bank |
| `./ops rcon "list"` | Always targets production (`./dev rcon` is the local one) |
| `./ops restart <service>` | Force-recreates that service |
| `./ops map render` | Force a full map re-render; normal updates are automatic |
| `./ops wipe-chunk --block X Z` | Delete a region file so it regenerates from the seed |
| `./ops reset-seed <seed>` | World reset: triple-confirmed, backs up first |
| `./ops teardown` | Destroys your cloud resources |

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
