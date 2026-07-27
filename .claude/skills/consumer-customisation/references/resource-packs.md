---
title: Resource Packs
description: How resource packs are declared, resolved, enabled, and the filename-pinning system that catches silent breakage on version bumps
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
2. **Filenames are pinned**: when a pack updates on Modrinth its filename usually changes, and the build **fails with a filename-drift error** until you refresh the `options.txt` entry. This is deliberate — the alternative is a pack that silently stops applying.

## Worked example

Villagers render as player-model humans via [Human Era: Villagers & Illagers](https://modrinth.com/resourcepack/human-era-villagers-illagers) plus its FreshAni Activator and FA Iron Golem Remover companion files (the remover keeps golems vanilla-style — delete its `options.txt` and `files` entries if you want HEVI's human-soldier golems), with [Quik's Human Guard Villagers](https://modrinth.com/resourcepack/quiks-human-guard-villagers) covering Guard Villagers' guards.

Any of the author's other micropacks can be added the same way: an extra `files` entry, enabled above the main pack.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Build fails with filename-drift error | A pack updated on Modrinth and its filename changed. Update the `options.txt` entry to match the new filename. |
| Pack downloaded but not applying | Not listed in `options.txt`. Download ≠ enable. |
| Pack applying but wrong priority | Order in the `resourcePacks:` array — last wins. |
