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

## Map markers

> **Note:** The BlueMap Sign Markers mod was removed in v2.14.0 (BlueMap moved to a standalone sidecar). Player-placed sign markers and live player positions are no longer available on the web map. Static config markers (below) still work.

### Static config markers (admin-placed)

For permanent landmarks that shouldn't depend on a sign existing in-world, add markers directly to the BlueMap map config. Per-map config lives in `config/bluemap/maps/` (synced to `data/config/bluemap/maps/` on deploy; consumers override via `overlay/config/bluemap/maps/`). **Overlay files replace the template file wholesale**, so copy the template's `world.conf` first, then append a marker set:

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

Full marker reference (icons, lines, areas): [bluemap.bluecolored.de/wiki/customization/Markers.html](https://bluemap.bluecolored.de/wiki/customization/Markers.html).

### Map sync

Markers don't stop at the web map: the client pack ships [MapLink](https://modrinth.com/mod/maplink), which mirrors BlueMap markers and area overlays into every player's Xaero's minimap and world map in-game. Waystone markers are already tracked; if a marker set of yours doesn't appear in Xaero's, check the `markerLayers` lists in `modpack/overrides/configureddefaults/config/maplink/general.json5`.

## Starter kit

The starter kit is handed to every new player by [Starter Kit](https://modrinth.com/mod/starter-kit). Config lives in `config/starterkit/`:

| File | Purpose |
| --- | --- |
| `kits/<Name>.txt` | One line per slot: `head/chest/legs/feet/offhand`, hotbar+inventory slots `0`-`35`, plus `effects` |
| `descriptions/<Name>.txt` | Flavour text shown on the kit choice screen / in chat |
| `../starterkit.json5` | Behaviour: `chooseKitText`, multiple-kit handling, effects toggle |

The easiest way to build a kit is **in-game**: arrange your inventory exactly as the kit should be (damaged gear, enchantments, modded items, even written books all work - items are stored as full NBT), then run `/starterkit set` as an op (see the command notes in `starterkit.json5`; `/starterkit add <name>` creates additional kits, and disabling `randomizeMultipleKitsToggle` gives players a choice screen on first join). Copy the resulting files from `data/config/starterkit/` into `overlay/config/starterkit/` so they survive redeploys and are canonical in your repo.

## Player-facing messages

Where each piece of player-facing (or Discord-facing) text lives. The MOTD is covered by setup (`MOTD` in `.env`); the rest:

| Surface | File / key |
| --- | --- |
| Kit choice prompt | `chooseKitText` in `config/starterkit.json5` |
| Restart countdowns, kick messages | `restart.*` keys in `config/messages.json` |
| Discord welcome pin | `discord.welcome_pin` in `config/messages.json` (push with `./scripts/discord-pin-sync.sh --push`) |
| In-game `/rules` | `config/essentialcommands/rules.txt` |
| Join/leave/chat formatting | [Styled Chat](https://modrinth.com/mod/styled-chat) - the template ships no config (mod defaults), so its generated config in `data/config/` is yours to edit; see the mod's docs for the format |

Consumers override any of these via `overlay/config/` (same relative paths).

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

Worked example: villagers render as player-model humans via [Human Era: Villagers & Illagers](https://modrinth.com/resourcepack/human-era-villagers-illagers) plus its FreshAni Activator and FA Iron Golem Remover companion files (the remover keeps golems vanilla-style - delete its `options.txt` and `files` entries if you want HEVI's human-soldier golems), with [Quik's Human Guard Villagers](https://modrinth.com/resourcepack/quiks-human-guard-villagers) covering Guard Villagers' guards. Any of the author's other micropacks can be added the same way: an extra `files` entry, enabled above the main pack.

## Minecraft version

The template targets **1.21.1**. Upgrading requires all ~260 mods to support the target version - see README § Update Minecraft version.

## Worldgen: terrain shape (Tectonic)

The platform ships `config/tectonic.json` — a fully-enumerated Tectonic 3.x
config tuned for wide, realistically proportioned terrain (see the comments
in the file for every dial and its rationale). Consumers override any of it
via `overlay/config/tectonic.json`; the file must stay COMPLETE (every key)
so a partial parse can never silently fall back to factory defaults — start
from the platform copy. Changes apply to newly generated chunks only; see
[docs/migration-v3.md](migration-v3.md) for the seam tradeoff.

`max_y` interacts with the two jar-baked noise presets: `adventure:wide`
assumes the 448 height; dropping global `max_y` back to 320 trims pinned-wide
dimensions at 320.

Dial semantics (verified against Tectonic 3.0.26 source/wiki — official
tooltip meanings, not folklore):

| Section.key | Default | Effect |
| --- | --- | --- |
| `continents.erosion_scale` | 0.25 | **The main "wider mountains" dial.** Lower ⇒ thicker mountain ranges, wider terrain between them |
| `continents.ridge_scale` | 0.25 | Lower ⇒ wider rivers, valleys and plateau systems |
| `continents.continents_scale` | 0.13 | Lower ⇒ larger continents and oceans |
| `continents.flat_terrain_skew` | 0.1 | Higher favours flat/rolling terrain over stepped plateaus |
| `continents.ocean_offset` | -0.8 | Land/ocean skew (above -0.45: no deep oceans; above -0.2: no oceans) |
| `continents.rolling_hills` | true | Smooth hilly plains |
| `global_terrain.vertical_scale` | 1.125 | Height multiplier above sea level; 1.0 ⇒ gentler relief |
| `global_terrain.elevation_boost` | 0 | Extra vertical scale applied to mountains faster than lowlands |
| `global_terrain.min_y` / `max_y` | -64 / 320 | Build/gen height — multiples of 16; raising vertical scale without raising `max_y` causes generation issues |
| `global_terrain.ultrasmooth` | false | Removes staircase/terracing artifacts; caveat: odd generation in deep oceans + windswept biomes |
| `biomes.temperature_scale` / `vegetation_scale` | 0.25 | Lower ⇒ larger climate regions (biome layout, not shape) |
| `experimental.alternate_*_scaling` | false | Companions to low scale values; kept compatible here because c2me's density-function compiler is force-disabled |
| `oceans.ocean_depth` / `deep_ocean_depth` | -0.22 / -0.45 | Ocean depths |

Halving `erosion_scale`/`ridge_scale` doubles the wavelength of the
mountain/valley rhythm; keeping `vertical_scale` ≈ 1 with a mild
`elevation_boost` spreads the same heights over wider slopes — gentler
average gradient is most of what "realistic proportions" reads as. Widen
the climate scales alongside or biomes stripe across the larger landforms.

## Worldgen: structure frequency presets

`config/datapacks/structures/` (active) tunes structure spawn rates; the
`dense`/`sparse` variants live in `config/datapack-presets/` and swap in via
the overlay (same pack name wins):

```bash
cp -r .stack/current/stack/config/datapack-presets/dense/structures overlay/config/datapacks/structures
```

See `config/datapack-presets/README.md` for what each preset changes and
what is deliberately left alone (Cristel Lib mods, custom placement types,
ultra-rares). The presets are generated by `scripts/gen-structure-presets.py`
from the curated dial list in `scripts/data/structure-dials.csv` (357 sets
audited) — re-run it after structure-mod pin bumps.

How placement tuning works (1.21.1 mechanics, for hand-rolled overrides):

- A structure set's `placement` has `spacing` (grid cell size in chunks),
  `separation` (minimum gap, strictly < spacing) and `frequency` (0–1
  chance a cell attempts generation). Expected structures per area ∝
  `frequency / spacing²`.
- **`frequency` is the safe knob on an existing world** — reducing it
  never moves placements already generated. Changing `spacing` or `salt`
  re-rolls the grid for future chunks (visible inconsistency near
  explored-terrain borders; never touches generated chunks).
- Overrides are whole-file: a world datapack shadows a mod's bundled
  `data/<ns>/worldgen/structure_set/<name>.json` at the same path, and
  the full `structures` list must be re-declared.
- Two installed mods are natively configurable instead (don't datapack
  them): **Towns and Towers** and **Explorify** read Cristel Lib configs
  (`config/towns_and_towers/`, `config/cristellib/`) through the normal
  config-sync pipeline.
- Per-dimension density is the mod's `structureDensity` field (next
  section) — vanilla cannot vary one set's frequency per dimension.

## Worldgen: per-dimension profiles

Each dimension file in `config/custom-dimensions/dimensions/` accepts two
optional worldgen fields (the monolithic `multiverse_config.json` remains a
deprecated fallback):

- `"noiseSettings"`: a `worldgen/noise_settings` registry id. The mod ships
  `adventure:wide` (broad realistic relief) and `adventure:compressed`
  (tight dramatic relief); any datapack-registered id works. Unset keeps
  the dimension type's default generator. Ignored for void/superflat.
- `"structureDensity"`: `dense` | `normal` | `sparse` | `none`. Theme-aware
  (dungeon/loot/settlement/landmark/deco): dense boosts dungeons+loot ~2x,
  sparse halves them. Dimensions with `"hostileSpawning": false` also drop
  all dungeon-theme structure sets and rarify settlements/ships to ~0.3x
  automatically.

Two generator types accept extra creation-time fields (worldgen — baked
into `level.dat` at creation, changes need a world wipe):

- `"type": "checkerboard"` tiles the `biomes` list in a fixed grid
  (vanilla checkerboard biome source) over overworld terrain noise —
  the layout is seed-independent, terrain and structures still follow
  the seed. `"checkerboardScale"` (0–62, default 2) sets the cell size:
  one cell is `2^(scale+4)` blocks per side (scale 2 = 64 blocks).
  Invalid biome entries are skipped with a warning; an empty list falls
  back to a plain overworld generator.
- `"type": "superflat"` accepts `"flatBiome"` (biome id, default plains)
  and `"layers"` — bottom-up like vanilla, `height` = thickness:

  ```json
  {
    "type": "superflat",
    "flatBiome": "minecraft:desert",
    "layers": [
      { "block": "minecraft:bedrock", "height": 1 },
      { "block": "minecraft:sandstone", "height": 10 },
      { "block": "minecraft:sand", "height": 3 }
    ]
  }
  ```

  Any invalid layer (unknown block, bad height) falls back to the whole
  default bedrock/dirt/grass stack — never a half-built world. Biome
  features and structures still generate on superflat terrain (desert
  wells, dungeons), exactly as vanilla superflat presets behave.

A dimension can opt out of seed rolling entirely with
`"seedRoll": { "skip": true }` — the roller neither measures nor scores
it (superflat dims are always skipped; the flag exists for anything else
whose seed you've pinned by hand).

Three further worldgen dials (Tier 3 of the custom-world-settings
matrix), all mirrored by the seed-rolling pipeline:

- `"settingsOverrides"` — whitelisted generator-settings swaps applied on
  top of the type's (or `noiseSettings` preset's) settings. Creation-time
  worldgen. Fields: `seaLevel` (int), `defaultBlock` / `defaultFluid`
  (block ids — think netherrack body, lava seas), and
  `disableMobGeneration` (bool). Invalid values warn and keep the base
  value per field. Arbitrary inline noise settings remain unsupported by
  design — author a jar preset instead.

  ```json
  { "settingsOverrides": { "seaLevel": 100, "defaultFluid": "minecraft:lava" } }
  ```

- **Per-biome placement parameters** — a `biomes` entry may be an object
  `{ "id": "...", "parameters": { ... } }` instead of a plain id string.
  Parameters are vanilla multi-noise intervals (`temperature`,
  `humidity`, `continentalness`, `erosion`, `depth`, `weirdness`: number
  or `[min, max]` within -2..2; `offset` 0..1). An overridden biome gets
  exactly that region and is withdrawn from the natural/round-robin
  mixing; unset axes span everything. Invalid parameters warn and the
  entry behaves as a plain listed biome. Creation-time worldgen.

  ```json
  "biomes": [
    { "id": "minecraft:plains", "parameters": { "temperature": [-2.0, 0.0] } },
    { "id": "minecraft:cherry_grove", "parameters": { "temperature": [0.0, 2.0] } }
  ]
  ```

- `"biomePatches"` — fixed biome patches over the generated layout,
  three modes per patch. **Stamp** (no `replace`): the listed biome
  claims every column in the area. **Clipped swap** (`replace` set):
  within the area, only columns the normal layout resolves to the
  `replace` biome are substituted — the natural blob keeps its organic
  shape, recoloured (`"*"` matches any biome ≈ a stamp). **Global swap**
  (`"scope": "global"`): dimension-wide wholesale replacement — an
  explicit `replace` id swaps that biome everywhere (no area needed);
  without one the area becomes a *selector*: every distinct biome
  touching it swaps globally (selector sampling sweeps up to 256 blocks
  of the radius). There is no per-blob identity in a biome source, so
  "replace that mesa even past the radius" means ALL instances of that
  biome — usually what you wanted in a curated dimension.

  Shared knobs: `"shape"`: `"circle"` (default) or `"square"`
  (Chebyshev — tiles cleanly against chunk grids); `"blend"`: edge
  jitter in blocks (0–64, default 8, `0` = razor edge) — smooth
  deterministic noise wobbles stamp/clip borders so they don't read as
  compass shapes. Precedence: local patches in config order (a
  non-matching swap falls through), then global rules. The killer app
  is a **guaranteed spawn biome at (0, 0)** — no more rolling seeds
  against a spawn filter. Creation-time worldgen. Terrain SHAPE is
  density-function-driven and mostly biome-independent: a desert patch
  on a mountain is a sandy mountain — pick sites with the terrain mood
  in mind. Invalid patches are skipped with a warning.

  ```json
  "biomePatches": [
    { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
    { "biome": "terralith:moonlight_grove", "x": 800, "z": -200, "radius": 400,
      "replace": "minecraft:dark_forest", "shape": "square", "blend": 16 },
    { "biome": "minecraft:cherry_grove", "replace": "minecraft:badlands", "scope": "global" },
    { "biome": "minecraft:river", "x": 500, "z": 500, "radius": 48, "scope": "global" }
  ]
  ```

- `"structures": { "spacing": { "<set-id>": { "spacing": N, "separation": M } } }`
  — exact placement values for one structure SET (registry set id, e.g.
  `minecraft:villages`, NOT a structure id), overriding the theme-based
  `structureDensity` factors for that set. Invariants enforced:
  `2 <= spacing <= 4096`, `0 <= separation < spacing`; violations (and
  custom placement types) warn and fall back to the theme path. Unlike
  the fields above this is boot-re-read, but placements only affect
  newly generated chunks (the grid re-rolls at explored-terrain borders,
  same caveat as datapack spacing edits). The peaceful overlay's
  dungeon-set drops always win over a spacing entry.

The shipped 74-dimension mapping is documented in
[docs/dimension-profiles-v3.md](dimension-profiles-v3.md).

## Portals

The full portal schema lives in
[`mods/custom-dimensions/README.md`](../mods/custom-dimensions/README.md)
— that file is the source of truth, not this guide. Headlines of what a
dimension's `portal` block can do (all boot-re-read; no world wipes):

- **Frame materials**: a plain block id, `#ns:tag`, an explicit list, or
  `{"colorGroup": "<dye>"}`; `framePlaceBlock` names the concrete block
  mod-built frames use; `frameMaterials` {top/sides/bottom} gives frame
  segments different requirements.
- **Shapes**: `door` (1x2), `doorway` (2x3), `end_exit` (horizontal ring
  with an optional `centreBlock` pedestal), `end_gateway` (frameless
  one-block teleporter), or an explicit pattern template; plus an
  `orientation` constraint.
- **Auras**: portals affect their surroundings — by default each linked
  pair leaks the other side's sampled nature through (terrain, plants,
  trees, fluids), bounded by per-side budgets; `portal.aura` overrides
  palettes, adds explicit conversions and fire, or switches it off.
- **Anchors, single-use portals, exit portals, exit shrines**: see the
  mod README; shrine frames rebuild in the dimension's own
  `framePlaceBlock` and shrine spacing derives from `borders.player`.

## Dimension links and exit conditions

Every exit target — `exitPortal.target`, `portal.anchor.exit`, and the
`exits` rules below — accepts the shorthands `"bed"` | `"worldSpawn"` |
`"origin"` **or a dimension-link descriptor**
`{"dimension": "adventure:the_starwell", "arrival": "anchor" | "spawn" | [x,y,z]}`,
making dimensions composable into chains and hubs (enter the gauntlet
only through the boneyard; a nexus dim whose shrines lead to three
sibling pockets). Arrivals surface-resolve like portal anchors — never
strand, never surprise-kill. Boot-re-read: no world wipes.

The per-dimension `exits` block maps TRIGGERS to targets — ways out
without a portal:

```json
"exits": {
  "void":        { "target": "bed" },
  "death":       { "action": "respawnAt", "target": "worldSpawn" },
  "death:lava":  { "action": "teleport", "target": { "dimension": "adventure:the_furnace_halls" } },
  "fallFrom":    { "minHeight": 120, "target": "origin" },
  "enderPearl":  { "target": { "dimension": "adventure:the_starwell", "arrival": "anchor" } }
}
```

- **`void`** — fired when a player falls below the world floor, before
  vanilla void damage. `{"action": "kill"}` explicitly opts back into
  vanilla. Arrivals get 15s of slow falling (sky-drop safe).
- **`death`** / **`death:<cause>`** / **`death:mob:<entity_id>`** — most
  specific key wins (`death:lava`, `death:drowning`, `death:mob:minecraft:zombie`;
  cause keys are damage-type id paths). `"action": "teleport"` cancels
  the death and leaves instead (the run continues, ~3 hearts);
  `"respawnAt"` dies normally and respawns at the target (one-shot;
  keepInventory is the usual gamerule and stays orthogonal).
- **`enderPearl`** — throwing a pearl exits instead (the pearl is not
  consumed in flight; the use is intercepted).
- **`fallFrom`** — falling `minHeight` blocks (default 100) without
  landing teleports mid-fall, with slow falling on arrival.

Design guarantees: exit conditions ADD routes, never remove them
(peaceful dims keep their exit portals); a 5-second per-player cooldown
stops trigger loops; boot validation WARNs (never crashes, never
auto-fixes) when a dimension's only exits are death triggers or when a
link names a dimension that doesn't exist — a dangling link falls back
to the overworld spawn at runtime.

### Exit shrines (the pretty way home)

```json
"exitShrines": { "enabled": true, "target": "bed" }
```

Enabling shrines scatters `adventure:exit_shrine` jigsaw ruins through
the dimension (spacing ~24 chunks): mossy stone platforms with a
standing crying-obsidian portal frame over a buried beacon. The mod
detects the beacon when a shrine's chunk loads, lights the frame, and
registers it as a permanent exit zone with the configured `target`
(ExitTarget grammar — shorthands or dimension links; default `"bed"`).
The structure set ships effectively disabled (frequency 0.001), raised
to full only for opted-in dimensions, so shrines never leak into the
overworld or unopted dims. The spawn `exitPortal` remains the
guarantee; shrines are scenery. Worldgen placement is creation-time
(new chunks only); the beacon detection itself is boot-re-read.
Templates are generated by `scripts/gen-exit-shrine.py` — rerun it
after design changes and rebuild the mod jar. Seed rolling: add
`"exit_shrine": "near_spawn"` to `seedRoll.wants` to favour candidates
with a shrine in walking distance.

## Worldgen: seed rolling

`./dev seed-roll` rolls every rollable dimension with the pure-Python
roller (structure screening + biome/terrain sampling — no server boots),
banks raw measurements in `config/custom-dimensions/candidates/`, and
writes winner seeds back into the dimension configs. `./dev seed-rescore`
re-scores the banked measurements against your current configs without
re-rolling; `./dev seed-status` shows per-dimension candidate counts,
winners, and freshness; `./dev seed-viewer` is the interactive picker.

**Seed groups**: dimensions whose generation-affecting config is
byte-identical (same type, noiseSettings, biome list, structureDensity,
patches, …) share every seed's measurements — the roller measures each
seed once per group and banks it for every member, and the finaliser
guarantees group members get **distinct** winner seeds (two identical-
generation dims on the same seed would be literal world clones). Scoring
fields (`seedRoll`, `structures.wants/shuns`, difficulty, portal, colours)
don't affect grouping — curate them freely.

`seed-status` states worth knowing: `STALE` means scores need a
`seed-rescore` after a config edit; `DRIFTED` means the winner was
measured under a **different generation config** — its measurements no
longer describe the world your config generates, and only a re-roll of
that dimension fixes it.
