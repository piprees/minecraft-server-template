---
title: Mod List Formats
description: The exact syntax of modrinth-mods.txt, mods-extra.txt, mods-remove.txt, the ? optional marker, the datapack: prefix, and version holds
tags: [modrinth-mods.txt, mods-extra.txt, mods-remove.txt, holds, datapack, optional]
---

# Mod List Formats

## `config/modrinth-mods.txt` (platform defaults)

One entry per line: `slug:versionId`. Comments start with `#`; blank lines are ignored. `datapack:slug:versionId` lines are Modrinth datapacks; `pin-mod-versions.sh` passes them through unchanged.

```
fabric-api:Nlt8gI9z
carpet:f2mvlGrg
datapack:borrow-their-arrows:Qq8BwuBw         # pick up arrows shot by mobs
```

**Trailing `?` marks a mod optional** — a failed resolution skips it instead of failing the seed/boot. The convention is `slug:versionId?` (e.g. `attributefix:XwbErf6s?`). `pin-mod-versions.sh`'s optional-detection (`[[ "$stripped" == *\? ]]`) checks whether the whole stripped line ends in `?`, so a `?` anywhere else is not treated as optional. Always put `?` as the very last character of the line.

**A disabled mod keeps its reason.** When commenting a line out rather than deleting it, give the reason in the comment — it is the only record of _why_ for the next person (or agent) who wonders whether it should come back.

**Section headers** (`# === core / performance ===`) are load-bearing: `.github/dep-review/policy.json` `never_automerge_sections` matches them by exact text, and `scripts/dep_review/partition.py` uses that to send a mod's updates to the next-major PR. Never rename a header, and file a worldgen mod under a worldgen header ([DEPENDENCIES.md § The weekly mod re-pin](../../../../DEPENDENCIES.md#the-weekly-mod-re-pin)). Keep new entries under the most relevant existing header.

## `overlay/mods-extra.txt` (consumer additions)

Same `slug:versionId` / `?` / `datapack:` syntax as the platform file. Template (`examples/consumer/overlay/mods-extra.txt`):

```
# mods-extra.txt — Additional server mods (added on top of platform defaults).
#
# Format: one mod per line as slug:versionId
#   slug       = the Modrinth project slug (from the URL)
#   versionId  = the Modrinth version ID (click the version, copy from URL)
#
# Example:
#   # tree-harvester:AANobbMI
#
# Lines starting with # are ignored. Blank lines are ignored.
# The ? suffix marks a mod as optional (won't fail the boot if unavailable):
#   # some-experimental-mod:versionId?
```

If a slug here matches one already in the platform defaults, the seed's merge step **replaces** the platform line with this one (see `mod-delivery-pipeline.md` stage 1) — this is how a consumer re-pins or reconfigures a default mod without needing `mods-remove.txt` + re-adding it.

## `overlay/mods-remove.txt` (consumer removals)

One **slug only** per line (no version ID) — must match a slug that exists in the platform's `config/modrinth-mods.txt`, or the seed logs `seed: warning: slug '<slug>' in mods-remove.txt not found in defaults` to stderr and otherwise ignores it (does not fail the boot). Template (`examples/consumer/overlay/mods-remove.txt`):

```
# mods-remove.txt — Default server mods to remove from this instance.
#
# Format: one Modrinth slug per line. The slug must match a mod in the
# platform's default modrinth-mods.txt (shipped in the seed image).
#
# Example:
#   # distant-horizons
#
# Lines starting with # are ignored. Blank lines are ignored.
```

## Version holds (`modpack/adventure.mrpack.json` → `_holds`)

Not a separate file — a top-level JSON object in the client pack manifest, slug → free-text reason naming the release condition. Both of `pin-mod-versions.sh`'s re-pin loops read it (server list and client manifest), leave a held slug's `versionId` untouched and log it as `HELD`.

```jsonc
"_holds": {
  "some-mod": "pre-release; release when a release build for 1.21.1 ships"
}
```

Rules: [DEPENDENCIES.md § Holds](../../../../DEPENDENCIES.md#holds). Read the live object for the current holds.

## Two-place config rule: the flat-path exception

Most mods with configuration get a directory: `config/<modname>/` (e.g. `config/openpartiesandclaims/`, `config/boring_default_game_rules/`). A few read a single bare file instead — confirmed from `docker/defaults-seed/Dockerfile`'s `COPY` lines:

```dockerfile
COPY config/tectonic.json        /defaults/config/tectonic.json
COPY config/messages.json        /defaults/config/messages.json
COPY config/starterkit.json5     /defaults/config/starterkit.json5
COPY config/update-check-mute.txt /defaults/config/update-check-mute.txt
```

Don't assume directory-vs-flat-file from the mod's name — check the `Dockerfile`'s existing `COPY` lines (or the mod's own docs/jar) before adding a new config-bearing mod.
