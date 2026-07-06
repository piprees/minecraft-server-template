# My Minecraft Server

A consumer repo powered by
[minecraft-server-template](https://github.com/piprees/minecraft-server-template).

## Quick start

The guided path — the wizard walks you through every credential (with the
exact dashboard pages and settings), writes `.env`, and can take you all the
way from local test to production:

```bash
./ops setup
```

Local-only alternative, if you'd rather fill the file in by hand:

```bash
cp .env.example .env          # every variable documented in comments
./dev up                       # pulls the stack bundle + starts everything
```

Connect at `mc.<LOCAL_DOMAIN>:<SERVER_PORT>` (default `mc.myserver.local:25577`).
Add the `/etc/hosts` entries printed by `./dev up` for subdomain routing.

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

Bump `STACK_VERSION` in `.env` (or leave it as `v1` to track the latest
v1.x.y), then:

```bash
./dev update                   # re-pulls the bundle + Docker images
./dev up                       # restart with the new version
```

## Going to production

The `ops` script delegates to the bundle's operational scripts with your
consumer environment loaded:

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

Then push to `main` -- the caller workflow in `.github/workflows/deploy.yml`
handles CI/CD via the reusable workflow.

Run `./ops help` for the full list of available commands.

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

Place the file in `overlay/config/` with the same path as the template's
`config/` directory. Your file replaces the platform default.

### Rebrand

Edit `.env`: `BRAND_NAME`, `BRAND_SLUG`, `MOTD`, `DISCORD_INVITE_URL`.
Place custom assets in `overlay/assets/` (see `overlay/assets/README.md`).

### Customise the web pages

All four surfaces ship with one shared dark palette (the template's
`DESIGN.md` tokens) and get the nav bar injected by the nav-proxy. To
restyle them:

| Surface | Override with | Notes |
| -- | -- | -- |
| `pack.DOMAIN` (download page) | `overlay/modpack/template/index.html` | Replaces the whole page template; rebuild with `./dev pack` or push |
| `mods.DOMAIN` (mod status) | `overlay/config/mods-page.css` | Appended after the default styles, so override selectively |
| `status.DOMAIN` (Uptime Kuma) | `overlay/config/uptime-kuma/kuma-config.json` | Full config replacement; copy the default from the template repo and edit `statusPage.customCSS` |
| `map.DOMAIN` (BlueMap) | upstream webapp | Only the nav bar is ours |

The nav bar itself lives in the template's `config/nginx/nav-proxy.conf`
(platform-level; open an issue or PR there for structural changes).

Changes under `overlay/` deploy as the infra tier on push — no server
restart.

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
├── dev                         # local dev commands (up/down/logs/rcon/pack)
├── ops                         # operational commands (setup/provision/deploy/...)
├── stack-pull.sh               # vendored bundle fetcher
├── .github/workflows/deploy.yml # CI/CD caller workflow
├── .stack/                     # git-ignored bundle cache
├── data/                       # git-ignored world + server state
├── modpack-dist/               # git-ignored built modpack
└── backups/                    # git-ignored local backups
```
