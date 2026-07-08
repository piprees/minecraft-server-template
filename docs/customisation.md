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

## Spawn

Spawn is the first thing every player sees - it carries the server's narrative more than any web page. The template leaves spawn selection to you:

1. **Pick the location.** Roll or choose a `SEED` (`config/.env`), explore, and find somewhere with character - a village jetty, a cliff-top ruin. Set it as world spawn in-game:

   ```
   /setworldspawn <x> <y> <z>
   ```

2. **Record the coordinates.** `setup.sh` prompts for `SPAWN_X`, `SPAWN_Y`, `SPAWN_Z` and stores them in `config/.env` (gitignored - they're instance-specific). They're used to centre the BlueMap web map on spawn (`initial-setup.sh`) and as the camera position for the title-screen panorama capture (see above).

3. **Dress the set.** Small props sell a story better than signs explaining it: a moored boat, a smouldering campfire ([Healing Campfire](https://modrinth.com/mod/healing-campfire) makes it mechanically welcoming), a Supplementaries sign post pointing to named places, a [Bountiful](https://modrinth.com/mod/bountiful) bounty board as the town notice board, a lectern with a written book that sets the scene.

### A note on waystones

The pack ships [Waystones](https://modrinth.com/mod/waystones) with the mod's default behaviour: waystones **generate naturally in villages** (no config shipped) and must be **activated in person** before a player can teleport to them - so they gate fast travel behind exploration rather than replacing it. Right-click with a name tag (or use the UI when placing a crafted one) to rename a waystone - naming the spawn waystone after your town puts the name in every player's teleport menu.

If you'd rather have less teleportation: remove `waystones` (and its lib `balm`) via your overlay's `mods-remove.txt`, and note that Essential Commands also ships with `/tpa`, `/home`, `/warp`, `/rtp` and `/spawn` enabled - each has an `enable_*` toggle in `config/essentialcommands/EssentialCommands.properties`.

## Map markers

BlueMap supports named POI markers - the easiest way to put your place names on the shared web map. Per-map config lives in `config/bluemap/maps/` (synced to `data/config/bluemap/maps/` on deploy; consumers override via `overlay/config/bluemap/maps/`). **Overlay files replace the template file wholesale**, so copy the template's `world.conf` first, then append a marker set:

```hocon
marker-sets: {
    places: {
        label: "Places"
        toggleable: true
        default-hidden: false
        markers: {
            spawn-town: {
                type: "poi"
                label: "Saltmere"
                position: { x: 120, y: 64, z: -340 }
                max-distance: 10000
            }
        }
    }
}
```

Add one marker per named place as players explore. Full marker reference (icons, lines, areas): [bluemap.bluecolored.de/wiki/customization/Markers.html](https://bluemap.bluecolored.de/wiki/customization/Markers.html).

Two related client-side systems can carry the same names in-game: [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) waypoints and [Areas](https://modrinth.com/mod/areas) regions are both per-player, but you can ship defaults to every client by placing their config files in `overlay/modpack/overrides/configureddefaults/config/` (coordinates are seed-specific, which is why the template doesn't).

## Starter kit

The starter kit is handed to every new player by [Starter Kit](https://modrinth.com/mod/starter-kit). Config lives in `config/starterkit/`:

| File | Purpose |
| --- | --- |
| `kits/<Name>.txt` | One line per slot: `head/chest/legs/feet/offhand`, hotbar+inventory slots `0`-`35`, plus `effects` |
| `descriptions/<Name>.txt` | Flavour text shown on the kit choice screen / in chat |
| `../starterkit.json5` | Behaviour: `chooseKitText`, multiple-kit handling, effects toggle |

The easiest way to build a kit is **in-game**: arrange your inventory exactly as the kit should be (damaged gear, enchantments, modded items, even written books all work - items are stored as full NBT), then run `/starterkit set` as an op (see the command notes in `starterkit.json5`; `/starterkit add <name>` creates additional kits). Copy the resulting files from `data/config/starterkit/` into `overlay/config/starterkit/` so they survive redeploys and are canonical in your repo.

Narrative tips that cost nothing:

- **Worn gear tells a story.** The default kit's armour is pre-damaged - an adventurer who has travelled, not a shop mannequin.
- **A written book is a quest hook.** A letter explaining who the player is and why they're here turns the kit from loot into a scene.
- **Multiple kits become character origins.** Disable `randomizeMultipleKitsToggle` and players choose a kit on first join - each kit description a different backstory arriving at the same town.
- `chooseKitText` in `starterkit.json5` is the first line a new player reads - make it match your world, not "choose a starter kit".

## Player-facing messages

All the places a player (or Discord member) reads server text, and where each lives:

| Surface | File / key |
| --- | --- |
| Server-list MOTD (multiplayer screen) | `MOTD` in `.env` |
| On-join MOTD | `motd` + `enable_motd` in `config/essentialcommands/EssentialCommands.properties` (formatting codes as per the shipped default) |
| Kit choice prompt | `chooseKitText` in `config/starterkit.json5` |
| Restart countdowns, kick messages | `restart.*` keys in `config/messages.json` |
| Discord welcome pin | `discord.welcome_pin` in `config/messages.json` (push with `./scripts/discord-pin-sync.sh --push`) |
| In-game `/rules` | `config/essentialcommands/rules.txt` |
| Join/leave/chat formatting | [Styled Chat](https://modrinth.com/mod/styled-chat) - the template ships no config (mod defaults), so its generated config in `data/config/` is yours to edit; see the mod's docs for the format |

Consumers override any of these via `overlay/config/` (same relative paths). A consistent voice across all of them - server list, join message, kit text, Discord - is what makes the narrative feel deliberate rather than decorative.

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

## Resource packs

Resource packs auto-install with the modpack. They're declared in `modpack/adventure.mrpack.json` under `_resourcePacks.packs`, and `build-modpack.sh` resolves each slug to its **newest version tagged for `MC_VERSION`** on Modrinth at build time.

Two entry forms:

```json
"packs": [
  "better-leaves",
  { "slug": "human-era-villagers-illagers", "files": ["HEVI FreshAni Activator.zip"] }
]
```

A plain slug downloads the version's primary file. The object form *also* downloads the named companion files (micropacks) from that same resolved version - so add-ons can never drift out of sync with their main pack.

Downloading a pack doesn't enable it (Dramatic Skys ships download-only, for players to opt into). Packs are **enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt` on the `resourcePacks:` line. Two rules:

- **Order is priority**: the last entry in the array sits on top and overrides everything below it.
- **Filenames are pinned**: when a pack updates on Modrinth its filename usually changes, and the build **fails with a filename-drift error** until you refresh the `options.txt` entry. This is deliberate - the alternative is a pack that silently stops applying.

### The human villagers stack

Villagers (plus illagers, zombie villagers and wandering traders) render as player-model humans via [Human Era: Villagers & Illagers](https://modrinth.com/resourcepack/human-era-villagers-illagers) (CC-BY-SA-4.0), driven by the Entity Model/Texture Features client mods, with [Quik's Human Guard Villagers](https://modrinth.com/resourcepack/quiks-human-guard-villagers) doing the same for Guard Villagers' guards. Stack order (bottom → top): Fresh Animations → HEVI → Quik's Guards → HEVI FreshAni Activator → HEVI FA Iron Golem Remover.

The **Iron Golem Remover** keeps golems vanilla-style; HEVI would otherwise restyle them as human soldiers/mechas. If you want HEVI's golems, delete that filename from `options.txt` and its entry from the manifest's `files` list. The author publishes more micropacks (gender ratios, profession tweaks) on the pack's Modrinth page - add any of them as extra `files` entries and enable them above the main pack.

## Minecraft version

The template targets **1.21.1**. Upgrading requires all ~260 mods to support the target version - see README § Update Minecraft version.
