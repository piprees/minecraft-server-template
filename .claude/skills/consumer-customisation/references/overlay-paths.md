---
title: Overlay Paths
description: Every overlay path a consumer can use and what it overrides in the platform defaults
tags: [overlay, config, consumer, customisation]
---

# Overlay path reference

Consumer repos customise the platform through `overlay/` — files that replace or extend platform defaults at deploy time. The seed container applies the overlay on top of the platform's `defaults-seed` image contents.

## Config overrides

| Overlay path | What it overrides | Notes |
| --- | --- | --- |
| `overlay/config/<modname>/` | `config/<modname>/` | Full-file replacement. The overlay file wins. |
| `overlay/config/messages.json` | `config/messages.json` | Player/Discord messages |
| `overlay/config/essentialcommands/rules.txt` | In-game `/rules` text | |
| `overlay/config/tectonic.json` | `config/tectonic.json` | Must be COMPLETE (every key) — partial file silently falls back to factory defaults |
| `overlay/config/datapacks/structures/` | `config/datapacks/structures/` | Same-name pack wins. Delete to return to platform default. |
| `overlay/config/starterkit/` | `config/starterkit/` | Kit files: `kits/<Name>.txt`, `descriptions/<Name>.txt` |
| `overlay/config/uptime-kuma/kuma-config.json` | `config/uptime-kuma/kuma-config.json` | Full config replacement |
| `overlay/config/custom-dimensions/dimensions/<slug>.json` | Platform dimension config | No `"overrides"` key = full replace; `"overrides": {...}` = deep-merge; empty `{}` = dimension disabled |
| `overlay/config/structure_themes.json` | (consumer-only) | Theme consumer-added mods' structure sets for per-dimension density |

## Mod management

| Overlay path | What it does |
| --- | --- |
| `overlay/mods-extra.txt` | Server mods to ADD (format: `slug:versionId`, one per line) |
| `overlay/mods-remove.txt` | Default mods to REMOVE (one slug per line) |

## Client pack (modpack)

| Overlay path | What it overrides |
| --- | --- |
| `overlay/modpack/overrides/configureddefaults/config/customsplashscreen/backgrounds/` | Loading screen background image(s) |
| `overlay/modpack/overrides/configureddefaults/config/customsplashscreen/square_logo.png` | Loading screen logo |
| `overlay/modpack/overrides/configureddefaults/config/customsplashscreen.json` | Loading screen config |
| `overlay/modpack/overrides/configureddefaults/resourcepacks/server-panorama/assets/minecraft/textures/gui/title/background/` | Title screen panorama cubemap faces |

## Assets and branding

| Overlay path | What it overrides |
| --- | --- |
| `overlay/assets/icon.svg` | 128×128 square icon |
| `overlay/assets/logo.svg` | Horizontal logo with wordmark |
| `overlay/assets/cover.svg` | 1280×640 social/OG cover |
| `overlay/assets/favicon.svg` | 32×32 browser tab icon |

## Rules

- `overlay/config/` is **full-file replacement** (not a merge), except dimension configs with `"overrides"` which deep-merge.
- Files in `overlay/` deploy as part of the **full** deploy tier when they match `FULL_PATTERNS` in the reusable workflow (`overlay/config/`, `overlay/mods-extra.txt`, `overlay/mods-remove.txt`). Other overlay changes (assets, branding) deploy as **infra** tier.
- The `overlay/` directory itself is consumer-owned — the platform's `./dev update` never overwrites it.
