---
title: Resource Packs
description: How resource packs are declared, resolved, enabled, and how enabled filenames follow version bumps without silent breakage
tags: [resource-packs, modpack, options.txt, modrinth, filename-pinning]
---

# Resource pack system

Resource packs auto-install with the modpack. They're declared in `modpack/adventure.mrpack.json` under `_resourcePacks.packs`, and `build-modpack.sh` resolves each slug to its **newest version tagged for `MC_VERSION`** on Modrinth at build time.

## Two entry forms

```json
"packs": [
  "better-leaves",
  { "slug": "human-era-villagers-illagers", "files": ["HEVI FreshAni Activator.zip"] }
]
```

- A **plain slug** downloads the version's primary file.
- The **object form** also downloads the named companion files (micropacks) from that same resolved version — so add-ons can never drift out of sync with their main pack.

## Enabling packs

Downloading a pack doesn't enable it (Dramatic Skys ships download-only, for players to opt into). Packs are **enabled by exact filename** in `modpack/overrides/configureddefaults/options.txt` on the `resourcePacks:` line.

### Two rules

1. **Order is priority**: the last entry in the array sits on top and overrides everything below it.
2. **Filenames follow the pin**: a pack's filename carries its version, so a re-pin renames it. The build (`scripts/pack_files.py`) rewrites each absent `options.txt` entry to the one downloaded file with the same name minus version tokens, and **fails** when there is none or more than one — a pack that silently stops applying is never shipped. A re-pin stays on the pinned style variant, so `(Short and Fluffy)` never turns into `(Tall)`.

## Worked example

Villagers render as player-model humans via [Human Era: Villagers & Illagers](https://modrinth.com/resourcepack/human-era-villagers-illagers) plus its FreshAni Activator and FA Iron Golem Remover companion files (the remover keeps golems vanilla-style — delete its `options.txt` and `files` entries if you want HEVI's human-soldier golems), with [Quik's Human Guard Villagers](https://modrinth.com/resourcepack/quiks-human-guard-villagers) covering Guard Villagers' guards.

Any of the author's other micropacks can be added the same way: an extra `files` entry, enabled above the main pack.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Build fails: `options.txt enables '<file>' but ...` | The enabled pack has no download with the same name minus version tokens: it left `_resourcePacks`, or its new filename differs by more than a version. Update the `options.txt` entry to the downloaded filename. |
| Pack downloaded but not applying | Not listed in `options.txt`. Download ≠ enable. |
| Pack applying but wrong priority | Order in the `resourcePacks:` array — last wins. |
