---
name: consumer-customisation
description: |
  Consumer-facing customisation via the overlay contract — rebranding (name, slug, MOTD, assets), loading screen (Custom Splash Screen backgrounds and logo), title screen panorama (cubemap capture and placement), starter kits (in-game /starterkit set workflow), player-facing messages (messages.json, rules.txt, styled chat), resource packs (Modrinth resolution, filename pinning, enable order in options.txt), removing default mods (overlay/mods-remove.txt, removal safety, client pack sync), adding server mods (overlay/mods-extra.txt), multi-instance setup (COMPOSE_PROJECT_NAME, ports, CONTAINER_PREFIX), and what to keep private (config/.env).

  Use when: rebranding a consumer server, changing the loading screen or title screen panorama, adding or removing mods via overlay, configuring the starter kit, editing player messages, managing resource packs, or setting up multiple server instances. Not for worldgen tuning (see worldgen-tuning), portal/dimension config (see custom-dimension-authoring), or web surface styling (see web-surface-branding).
---

# Consumer Customisation

You are customising a consumer server built from the Adventure Server template. Everything here works through the **overlay contract** — files in `overlay/` that replace or extend platform defaults without forking the template. The platform ships generic placeholder branding (neutral grey theme, Adventure Server name, `example.com` domain); this skill covers making it yours.

**Not this skill:** worldgen tuning (terrain, structures, dimensions) → [worldgen-tuning](../worldgen-tuning/SKILL.md); portal/exit/dimension config → [custom-dimension-authoring](../custom-dimension-authoring/SKILL.md); web page styling → [web-surface-branding](../web-surface-branding/SKILL.md); the overlay contract internals (how merging works, how deploy.sh applies it) → [consumer-repo-operations](../consumer-repo-operations/SKILL.md).

## MANDATORY: read before editing

| File | Why you need it |
| --- | --- |
| [references/overlay-paths.md](references/overlay-paths.md) | Every overlay path and what it overrides |
| [references/panorama-capture.md](references/panorama-capture.md) | Step-by-step cubemap capture for the title screen |
| [references/resource-packs.md](references/resource-packs.md) | Resource pack system: slug forms, filename pinning, options.txt enable order |
| [examples/consumer/README.md](../../../examples/consumer/README.md) | The consumer-facing quick reference |

## Brand identity — quick start

1. **Name and slug.** `./ops setup` prompts for `BRAND_NAME` (display name, e.g. "Oakwood SMP") and `BRAND_SLUG` (lowercase filename slug, e.g. "oakwood"). These propagate everywhere via env vars.

2. **Domain.** Set `DOMAIN` in `.env` (e.g. `play.oakwood.gg`). Subdomains `mc.`, `map.`, `pack.`, `status.`, `mods.` are fixed prefixes.

3. **Assets.** Replace the SVGs in `assets/` (or `overlay/assets/` for consumers):
   - `icon.svg` — 128×128 square icon (nav bar, favicon, status page)
   - `logo.svg` — horizontal lockup with wordmark
   - `cover.svg` — 1280×640 social/OG cover image
   - `favicon.svg` — 32×32 browser tab icon

4. **Design tokens.** Edit `DESIGN.md` for colours, typography, spacing. See [web-surface-branding](../web-surface-branding/SKILL.md) for how tokens propagate to each web surface.

5. **Discord welcome pin.** Edit `discord.welcome_pin` in `config/messages.json`, then `./scripts/discord-pin-sync.sh --push`.

### Files to check

| File                                  | What to customise                                                 |
| ------------------------------------- | ----------------------------------------------------------------- |
| `.env`                                | `BRAND_NAME`, `BRAND_SLUG`, `MOTD`                                |
| `config/.env`                         | `DOMAIN`, Discord IDs, `DISCORD_INVITE_URL` — `SEED`/spawn coords are a legacy fallback, see § What to keep private |
| `config/messages.json`                | All player/Discord-facing messages, including the welcome pin     |
| `config/essentialcommands/rules.txt`  | In-game `/rules` text                                             |
| `modpack/template/index.html`         | Pack download page (themed via CSS custom properties)             |
| `config/uptime-kuma/kuma-config.json` | Status page styling (`statusPage.customCSS`)                      |
| `DESIGN.md`                           | Design token reference                                            |
| `assets/`                             | All brand imagery                                                 |

## Loading screen

The client pack includes [Custom Splash Screen](https://modrinth.com/mod/custom-splash-screen). Default: full-screen background image with hidden logo and vanilla progress bar.

### Override the background

Place PNGs in: `overlay/modpack/overrides/configureddefaults/config/customsplashscreen/backgrounds/`

Multiple images → randomly selected at startup. Single image → used every time. Your images replace the template default.

### Override the logo

Replace: `overlay/modpack/overrides/configureddefaults/config/customsplashscreen/square_logo.png`

Show it (hidden by default) — create `overlay/modpack/overrides/configureddefaults/config/customsplashscreen.json`:

```json
{ "logoStyle": "Aspect1to1" }
```

### Config reference

| Key                             | Default     | Values                                                  |
| ------------------------------- | ----------- | ------------------------------------------------------- |
| `backgroundImage`               | `true`      | `true` / `false`                                        |
| `logoStyle`                     | `"Hidden"`  | `"Mojang"`, `"Aspect1to1"`, `"Hidden"`                  |
| `logoBlend`                     | `false`     | `true` / `false`                                        |
| `splashBackgroundColor`         | `"#1E2233"` | hex colour                                              |
| `splashProgressBarColor`        | `"#E87420"` | hex colour                                              |
| `splashProgressFrameColor`      | `"#6B4226"` | hex colour                                              |
| `splashProgressBackgroundColor` | `"#141824"` | hex colour                                              |
| `progressBarType`               | `"Vanilla"` | `"Vanilla"`, `"Custom"`, `"SpinningCircle"`, `"Hidden"` |

## Title screen panorama

The template ships a `server-panorama` resource pack with default cubemap images, enabled by default in `options.txt`.

Override by dropping 6 cubemap face PNGs into:

```
overlay/modpack/overrides/configureddefaults/resourcepacks/server-panorama/
└── assets/minecraft/textures/gui/title/background/
    ├── panorama_0.png   (South)  ├── panorama_3.png   (East)
    ├── panorama_1.png   (West)   ├── panorama_4.png   (Up)
    ├── panorama_2.png   (North)  └── panorama_5.png   (Down)
```

Cubemap faces must be **square** screenshots at **exactly 90° FOV** with `fovEffectScale:0.0`. Full capture guide: [references/panorama-capture.md](references/panorama-capture.md).

## Starter kit

Handed to every new player by [Starter Kit](https://modrinth.com/mod/starter-kit). The easiest way to build a kit is **in-game**: arrange your inventory, then `/starterkit set` as an op. Copy resulting files from `data/config/starterkit/` into `overlay/config/starterkit/` so they survive redeploys.

Config files: `kits/<Name>.txt` (slot layout), `descriptions/<Name>.txt` (flavour text), `starterkit.json5` (behaviour: `chooseKitText`, multi-kit handling). `/starterkit add <name>` creates additional kits.

## Player-facing messages

| Surface | File / key |
| --- | --- |
| Kit choice prompt | `chooseKitText` in `config/starterkit.json5` |
| Restart countdowns, kick messages | `restart.*` keys in `config/messages.json` |
| Discord welcome pin | `discord.welcome_pin` in `config/messages.json` |
| In-game `/rules` | `config/essentialcommands/rules.txt` |
| Join/leave/chat formatting | [Styled Chat](https://modrinth.com/mod/styled-chat) — generated config in `data/config/` |

Consumers override any of these via `overlay/config/` (same relative paths).

## Adding and removing mods

| What                 | Edit                                            | Then                         |
| -------------------- | ----------------------------------------------- | ---------------------------- |
| Add a server mod     | `overlay/mods-extra.txt` (`slug:versionId`)     | `./dev up` or push to `main` |
| Remove a default mod | `overlay/mods-remove.txt` (one slug per line)   | Same                         |
| Client mod           | `modpack/adventure.mrpack.json` (`_clientMods`) | Push (CI rebuilds `.mrpack`) |

**Every default mod is removable** without breaking the boot — this is a platform promise, guarded by CI's smoke test removal-matrix. The structures datapack strips removed mods' overrides automatically via `ownership.json`.

**Two consumer responsibilities when removing:**

1. Remove dependents together (e.g. `fabric-seasons-terralith-compat` goes when `terralith` goes)
2. Keep the client pack in sync — a slug in `mods-remove.txt` that's still in `_clientMods.required` gets players kicked at the Fabric handshake

## Resource packs

Full reference: [references/resource-packs.md](references/resource-packs.md).

Declared in `modpack/adventure.mrpack.json` under `_resourcePacks.packs`. Two entry forms: plain slug (primary file) or `{"slug": "...", "files": ["companion.zip"]}` for micropacks.

**Enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt`. The build **fails with a filename-drift error** when an enabled filename doesn't match a downloaded pack — this is deliberate, not a bug.

## Multi-instance

Each clone can run independently:

- `COMPOSE_PROJECT_NAME` — derived from the directory name by `setup.sh`; isolates Docker networks and volumes
- `CONTAINER_PREFIX` — set automatically on name clash (e.g. two stacks both wanting `mc`)
- Ports — `GAME_PORT`, `VOICE_PORT`, `WEB_PORT`, `KUMA_PORT` are all configurable; `setup.sh` detects clashes

## What to keep private

Use `config/.env` (gitignored) for: Discord snowflake IDs, domain, player usernames, tunnel names. The committed `.env` holds generic defaults.

`SEED`/`SPAWN_X/Y/Z` also live in `.env`, but they are a legacy fallback, not the real lever: they only reach terrain/spawn when a dimension config opts in (`"seed": "env"`, or no `spawn` chosen). Set the real seed and spawn in `config/custom-dimensions/dimensions/overworld.json` (or the overlay) instead — see [custom-dimension-authoring](../custom-dimension-authoring/SKILL.md) and `TROUBLESHOOTING.md#t31`.

## Traps

1. **`overlay/config/` is full-file replacement, not a merge** (except `"overrides"` deep-merge in dimension configs). A partial `tectonic.json` silently falls back to factory defaults for missing keys.
2. **The loading screen config JSON is one level UP** from the `customsplashscreen/` directory — `config/customsplashscreen.json`, not `config/customsplashscreen/customsplashscreen.json`.
3. **Removing a worldgen mod changes NEW terrain only.** Existing chunks keep their shape; expect visible borders.
4. **Resource pack filenames change on version bumps.** The build fails deliberately — update `options.txt` when you see a filename-drift error.
5. **`/starterkit set` captures full NBT** — damaged gear, enchantments, modded items, even written books all survive. But `NCR-Encryption.json` in the instance config contains a secret — never bulk-copy a Prism instance's config directory.
6. **Panorama faces must be square at exactly 90° FOV** (`fov:0.5` in options.txt). Non-square or wrong-FOV images cause visible seams.

## Validation

```bash
./dev up                              # applies overlay, boots the server
docker logs mc 2>&1 | grep -iE 'error|warn' | tail -20
# Check overlay was applied:
diff overlay/config/messages.json data/config/messages.json 2>/dev/null   # should match
```

## References

- [references/overlay-paths.md](references/overlay-paths.md) — complete overlay path reference
- [references/panorama-capture.md](references/panorama-capture.md) — step-by-step cubemap capture
- [references/resource-packs.md](references/resource-packs.md) — resource pack system details
- Sibling skills: [worldgen-tuning](../worldgen-tuning/SKILL.md) (terrain/structures), [web-surface-branding](../web-surface-branding/SKILL.md) (web page styling), [consumer-repo-operations](../consumer-repo-operations/SKILL.md) (overlay contract internals), [server-mod-management](../server-mod-management/SKILL.md) (mod dependency checklist)
