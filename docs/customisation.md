# Customisation guide

How to make this template your own. The template ships with generic placeholder branding - neutral grey theme, ⛏️ emoji icon, "Adventure Server" name, `example.com` domain. Replace these with your own before going live.

## Brand identity

### Quick start

1. Pick a name and slug. `setup.sh` prompts for `BRAND_NAME` (display name, e.g. "Oakwood SMP") and `BRAND_SLUG` (lowercase filename slug, e.g. "oakwood"). These propagate everywhere via env vars.

2. Set your domain in `DOMAIN` (e.g. `play.oakwood.gg`). Subdomains `mc.`, `map.`, `pack.`, `status.`, `mods.` are fixed prefixes, only the apex changes.

3. Replace the SVGs in `assets/`:
   - `icon.svg` - 128×128 square icon (nav bar, favicon, status page)
   - `logo.svg` - horizontal lockup with wordmark
   - `cover.svg` - 1280×640 social/OG cover image
   - `favicon.svg` - 32×32 browser tab icon

   To generate raster favicons from SVG (optional, for older browser support):

   ```bash
   rsvg-convert -w 32 -h 32 assets/favicon.svg > assets/favicon-32.png
   rsvg-convert -w 180 -h 180 assets/icon.svg > assets/apple-touch-icon.png
   ```

   `build-modpack.sh` copies assets into `modpack/dist/` so they're served at `pack.DOMAIN/`.

4. Edit `DESIGN.md` for the design tokens. The placeholder uses system fonts and a grey palette. To add a display font:
   - Place the `.woff2` in `modpack/template/fonts/`
   - Update the CSS in each web surface (see AGENTS.md § Web surfaces for the list)
   - Add a CORS header on `/fonts/` in `config/nginx/pack-web.conf.template` if Kuma loads it cross-origin

5. Edit the `discord.welcome_pin` entry in `config/messages.json` for the Discord welcome pin. Run `./scripts/discord-pin-sync.sh --push` to update the live pin (or `--init` for first-time setup).

### Files to check

| File | What to customise |
| --- | --- |
| `.env` | `BRAND_NAME`, `BRAND_SLUG`, `MOTD` |
| `config/.env` | `DOMAIN`, `SEED`, spawn coords, Discord IDs, `DISCORD_INVITE_URL` |
| `config/messages.json` | All player/Discord-facing messages, including the welcome pin |
| `config/essentialcommands/rules.txt` | In-game `/rules` text |
| `config/essentialcommands/EssentialCommands.properties` | MOTD shown on join |
| `modpack/template/index.html` | Pack download page (themed via CSS custom properties) |
| `config/uptime-kuma/custom.css` | Status page styling |
| `config/cloudflare/maintenance-worker.js` | Offline/maintenance page |
| `config/nginx/nav-proxy.conf.template` | Nav bar injected into all web pages (five copies - keep in sync) |
| `DESIGN.md` | Design token reference (colours, typography, spacing, components) |
| `PRODUCT.md` | Product vision doc (adapt or replace for your server) |
| `assets/` | All brand imagery |

## Multi-instance

Each clone can run an independent stack:

- **`COMPOSE_PROJECT_NAME`** - derived from the directory name by `setup.sh`; isolates Docker networks and volumes.
- **`CONTAINER_PREFIX`** - set automatically when a name clash is detected (e.g. two stacks both wanting a container called `mc`). Default empty, so a single instance keeps the familiar `mc`, `discord-sync` etc. names.
- **Ports** - `GAME_PORT`, `VOICE_PORT`, `WEB_PORT`, `KUMA_PORT` are all configurable; `setup.sh` detects clashes and offers alternatives.

## What to keep private

Use `config/.env` (gitignored) for anything instance-specific: seed, spawn coordinates, Discord snowflake IDs, domain, player usernames, tunnel names. The committed `.env` holds generic defaults that work for any fork.

## Mod list

The template ships a curated ~150 server + ~110 client mod list focused on exploration. To build your own:

1. Edit `config/modrinth-mods.txt` (server mods) and `modpack/adventure.mrpack.json` (client mods)
2. Follow the dependency checklist in `AGENTS.md` § Mods
3. Pin versions: `./scripts/pin-mod-versions.sh --apply`
4. Test locally: `./dev up`

## Minecraft version

The template targets **1.21.1**. Upgrading requires all ~260 mods to support the target version - see README § Update Minecraft version.
