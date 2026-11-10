---
title: Overlay contract
description: Every overlay/ path in a consumer repo, its real merge semantics, and worked examples — including the custom-dimensions staging exception and the modpack manifest patch schema.
tags: [overlay, seed.sh, custom-dimensions, modpack, merge-manifest, rsync]
---

# Overlay contract

The `defaults-seed` image lays platform defaults into shared Docker volumes at boot, then applies your `overlay/` on top. Everything here is verified against `docker/defaults-seed/seed.sh` (the seed's merge logic) and `docker/defaults-seed/Dockerfile` (what the image actually ships as defaults), plus `docker/modpack-builder/entrypoint.sh` and `merge-manifest.py` for the client pack.

## `overlay/config/` — whole-file replacement

`seed.sh` runs, in order:

```bash
rsync -a --delete /defaults/config/ /out/config/
rsync -a --exclude=custom-dimensions /overlay/config/ /out/config/
```

Platform defaults land first; your overlay rsyncs on top, file by file. There is **no in-file merging** — if `overlay/config/serilum/starterkit.json` exists, it wholly replaces the platform default at that path. The consumer README's own warning applies here: a partial `starterkit.json` missing keys falls back to the mod's factory defaults for the missing keys, not the platform default — copy the whole file and edit it, never write a partial one.

Directory structure mirrors the template's `config/`. Two ways to know what path to use:

1. Check `examples/consumer/overlay/config/README.md` for the pattern (`overlay/config/uptime-kuma/kuma-config.json`, `overlay/config/datapacks/my-datapack/`).
2. Check what the `defaults-seed` `Dockerfile` actually `COPY`s from `config/` — anything not copied into the seed image can't be overridden by an overlay, because there's no platform default at that path for the seed to lay down first. As of the current Dockerfile this includes (non-exhaustive): `messages.json`, `starterkit/`, `serilum/`, `uptime-kuma/`, `nginx/`, `datapacks/`, `custom-dimensions/`, and per-mod config dirs (`openpartiesandclaims/`, `servercore/`, and others).

## `overlay/config/custom-dimensions/` — the one path that does NOT rsync over the default

The seed container leaves it out of the config volume, and nothing copies it over the platform files. `deploy.sh` (step 8) and `dev-up.sh` resolve it into `data/config/custom-dimensions/`, the folders the 26.3 mod reads, with `scripts/stage-dimensions.py`:

| Your file | Effect |
| --- | --- |
| `dimensions/<slug>.json` with `"schemaVersion": 2` | Replaces the platform definition, or adds a dimension of that slug |
| `dimensions/<slug>.json`, body `{"overrides": {...}}` | Merged over the platform definition: objects key by key, other values replace, `null` removes a key |
| `dimensions/<slug>.json`, body `{}` | Removes a platform lazy dimension; refused for `overworld`, `the_nether`, `the_end`, `the_aviary` |
| `dimensions/settings.json` | The same `schemaVersion` or `overrides` forms over the platform `settings.json` |
| `bridges/<name>.json` | A structure bridge beside the platform set, or a replacement of the one of that name |
| A schema v1 file (no `schemaVersion`, no `overrides`) | Not staged; the platform definition stays and the deploy log names the file in a WARN |
| `*_thumb.json`, images | Ignored (1.21.1 seed-viewer files) |

Removing an overlay file takes effect on the next run: `data/config/custom-dimensions/.overlay-staged.json` records what was staged, and a file the overlay no longer carries goes back to the platform copy. A seed picked on the server (`/customdim pick`) is written into the platform definition in `data/config`, which the next full deploy replaces; keep it as `{"overrides": {"seed": <seed>}}` in the overlay. The definition schema is in the template repo's `.claude/skills/custom-dimension-authoring/SKILL.md`.

## `overlay/mods-extra.txt` / `overlay/mods-remove.txt`

Applied by `seed.sh` against `/defaults/modrinth-mods.txt` (the platform's `config/modrinth-mods.txt`, baked into the image):

1. Every default line is kept unless its slug is in `mods-remove.txt` (dropped) or matched by slug in `mods-extra.txt` (the extra line **replaces** the default line for that slug — lets you re-pin a default mod to a different version by re-listing its slug in `mods-extra.txt`).
2. Anything left in `mods-extra.txt` after that pass is appended at the end, in file order.
3. `seed.sh` warns (doesn't fail) if a `mods-remove.txt` slug doesn't match any default slug — a typo'd removal silently does nothing beyond that warning.

Format for both files: `slug:versionId` (Modrinth slug and version ID), one per line, `#` comments and blank lines ignored, trailing `?` marks a mod optional (boot doesn't fail if it can't resolve).

Removing a mod's dependents is on you — `seed.sh` doesn't do dependency resolution, it's a flat text merge. See the mandatory dependency checklist in the template repo's [DEPENDENCIES.md § Adding a mod](https://github.com/piprees/minecraft-server-template/blob/main/DEPENDENCIES.md#adding-a-mod) before adding anything.

## `overlay/assets/`

Optional branding: `icon.svg` (nav bar, favicon, Discord embed), `logo.svg` (download page header), `cover.png` (1920×1080 recommended; download page hero / social preview), `favicon.ico` (32×32 or 64×64, browser tab). All optional — the platform ships placeholders used when a file is absent. Consumed both by `build-modpack.sh` (via the `modpack-builder` entrypoint, which prefers `/overlay/assets` over `/defaults/assets` wholesale — it's an all-or-nothing directory swap, not a per-file merge) and by nav-proxy for the site nav bar.

## `overlay/modpack/` — the client pack overlay

Two independent pieces, both consumed by `docker/modpack-builder/entrypoint.sh` before it calls `build-modpack.sh`:

### `overlay/modpack/manifest.json` — a patch, not a file replacement

Applied by `docker/modpack-builder/merge-manifest.py` (`merge(default, patch)`), deep-merging onto the platform's `modpack/adventure.mrpack.json`.:

```json
{
  "remove": ["distant-horizons"],
  "add": {
    "required": ["tree-harvester:AANobbMI"],
    "optional": ["some-cosmetic-mod:xyz789"]
  },
  "name": "My Server Pack",
  "versionId": "custom-1"
}
```

- `remove[]` — slugs dropped from **both** `_clientMods.required` and `_clientMods.optional` on the platform manifest. Entries match by slug, so `"sodium"` drops `sodium:QV48eyCs`.
- `add.required[]` / `add.optional[]` — `slug:versionId` strings added; an entry already listed under the same slug, in either list, is replaced.
- `name`, `versionId` — scalar overrides at the top level.
- `_resourcePacks`, `_shaderPacks` — whole-section replacement (not merged) if present in the patch.
- An empty `{}` (the default when the file doesn't exist) changes nothing.

Adding or removing an existing-catalogue client mod needs no template PR. What does: any mod that needs a `stableOnly` or `_holds` entry, or a new manifest section. A server-side removal in `overlay/mods-remove.txt` reaches the client pack on its own: `strip-removed-mods.py` drops the same slugs from `_clientMods` before the build.

### `overlay/modpack/overrides/` and `overlay/modpack/template/index.html`

- `overlay/modpack/overrides/` is copied into the built pack's `overrides/` tree, on top of the platform's own `modpack/overrides/` (whole-file copy per path, same semantics as `overlay/config/`).
- `overlay/modpack/template/index.html`, if present, replaces the entire download-page template (`entrypoint.sh` symlinks it in place of the platform default) — it's all-or-nothing, not a partial override.

## Worked example: rebranding + one extra mod + one custom dimension tweak

```
overlay/
├── assets/
│   ├── icon.svg
│   └── cover.png
├── mods-extra.txt              # "tree-harvester:AANobbMI"
├── modpack/
│   └── manifest.json           # {"add": {"required": ["tree-harvester:AANobbMI"]}}
└── config/
    └── custom-dimensions/
        └── dimensions/
            └── the_gauntlet.json   # {"overrides": {"difficulty": {"mobMultiplier": 2.0}}}
```

`./dev up` (or a push) re-seeds all three: the mod jar resolves and installs, the client pack gains the mod on next `./dev pack`/CI rebuild, and `the_gauntlet` boots with the overridden multiplier merged over the platform's base config — all without touching a single template-repo file.
