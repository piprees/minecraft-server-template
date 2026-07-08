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

## Loading screen

The client pack includes [Custom Splash Screen](https://modrinth.com/mod/custom-splash-screen) for a branded loading experience. By default, a full-screen background image is shown with the server's square logo and a vanilla progress bar.

### What ships by default

All files live under `modpack/overrides/configureddefaults/config/customsplashscreen/` in the template:

| File | Purpose |
| --- | --- |
| `../customsplashscreen.json` | Config (one level up, next to the directory) |
| `square_logo.png` | Logo shown centre-screen (⛏️ placeholder, hidden by default) |
| `backgrounds/background.png` | Full-screen background image (south-facing panorama shot) |

Default config: `backgroundImage: true`, `logoStyle: "Hidden"`. The logo is ready to use — consumers who want it visible override `logoStyle` to `"Aspect1to1"` and optionally replace `square_logo.png`.

### Overriding the background

Place PNGs in the consumer overlay:

```
overlay/modpack/overrides/configureddefaults/config/customsplashscreen/backgrounds/
```

Multiple images in the `backgrounds/` directory are randomly selected at startup. A single image is used every time. Your images replace the template default.

### Overriding the logo

Replace the square logo:

```
overlay/modpack/overrides/configureddefaults/config/customsplashscreen/square_logo.png
```

Or show it (hidden by default) — create `overlay/modpack/overrides/configureddefaults/config/customsplashscreen.json`:

```json
{
  "logoStyle": "Aspect1to1"
}
```

### Config reference

Override any setting by placing a `customsplashscreen.json` in the consumer overlay. The full schema:

| Key | Default | Values |
| --- | --- | --- |
| `backgroundImage` | `true` | `true` / `false` |
| `logoStyle` | `"Aspect1to1"` | `"Mojang"`, `"Aspect1to1"`, `"Hidden"` |
| `logoBlend` | `false` | `true` / `false` |
| `splashBackgroundColor` | `"#1E2233"` | hex colour (fallback before image loads) |
| `splashProgressBarColor` | `"#E87420"` | hex colour |
| `splashProgressFrameColor` | `"#6B4226"` | hex colour |
| `splashProgressBackgroundColor` | `"#141824"` | hex colour |
| `progressBarBackground` | `true` | `true` / `false` |
| `progressBarType` | `"Vanilla"` | `"Vanilla"`, `"Custom"`, `"SpinningCircle"`, `"Hidden"` |
| `customProgressBarMode` | `"Linear"` | `"Linear"`, `"Stretch"`, `"Slide"` |

## Title screen panorama

The template ships a `server-panorama` resource pack with default cubemap images, enabled by default in `options.txt`. Players see the server's world on the main menu instead of the vanilla panorama.

### Overriding the panorama

Drop 6 cubemap face PNGs into the consumer overlay:

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

Your images replace the template defaults. No `pack.mcmeta` or `options.txt` changes needed.

### Capturing cubemap faces

Cubemap faces must be **square** screenshots taken at **exactly 90° FOV** with no dynamic FOV effects. Mismatched FOV or non-square images cause visible seams at cube edges.

#### 1. Set options.txt before launching

Close Minecraft, then edit `options.txt` in your Prism/MultiMC instance:

```
fov:0.5
fovEffectScale:0.0
fullscreen:false
overrideWidth:1024
overrideHeight:1024
```

- `fov:0.5` = exactly 90° (the slider is non-linear: `0.0` = 70°, `0.5` = 90°, `1.0` = 110°)
- `fovEffectScale:0.0` = disables dynamic FOV from sprinting/potions
- `overrideWidth`/`overrideHeight` = forces a square window (critical — widescreen screenshots won't tile correctly)

#### 2. Launch and freeze the world

```
/gamerule doDaylightCycle false
/gamerule doWeatherCycle false
/time set 6000
/weather clear
/gamemode spectator
```

Press **F1** to hide HUD. Enable shaders if desired.

#### 3. Capture the 6 faces

Replace `<x> <y> <z>` with your coordinates from `.env` (`SPAWN_X`, `SPAWN_Y`, `SPAWN_Z`). Run each command, then take a screenshot (**F2**):

```
/tp @s <x> <y> <z> 0 0        → panorama_0.png (South)
/tp @s <x> <y> <z> 90 0       → panorama_1.png (West)
/tp @s <x> <y> <z> 180 0      → panorama_2.png (North)
/tp @s <x> <y> <z> -90 0      → panorama_3.png (East)
/tp @s <x> <y> <z> 0 -90      → panorama_4.png (Up)
/tp @s <x> <y> <z> 0 90       → panorama_5.png (Down)
```

#### 4. Reset

```
/gamemode survival
/gamerule doDaylightCycle true
/gamerule doWeatherCycle true
```

Press **F1** to show HUD. Restore your preferred settings in `options.txt`:

```
fov:0.5
fovEffectScale:0.5000000000000001
fullscreen:true
overrideWidth:0
overrideHeight:0
```

#### 5. Process and place

Rename the 6 screenshots to `panorama_0.png` through `panorama_5.png` and place them in the overlay path above. Optionally crush them with `pngquant` to reduce pack size:

```bash
for f in panorama_*.png; do pngquant --quality=80-100 --speed 1 --force --output "$f" "$f"; done
```

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
