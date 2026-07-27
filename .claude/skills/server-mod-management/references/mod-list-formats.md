---
title: Mod List Formats
description: The exact syntax of modrinth-mods.txt, mods-extra.txt, mods-remove.txt, the ? optional marker, the datapack: prefix, and version holds — with real counts from the shipped list
tags: [modrinth-mods.txt, mods-extra.txt, mods-remove.txt, holds, datapack, optional]
---

# Mod List Formats

## `config/modrinth-mods.txt` (platform defaults)

One entry per line: `slug:versionId`. Comments start with `#`; blank lines are ignored. As of this writing the file has 156 active (non-comment, non-blank) entries, of which 2 use the `datapack:` prefix (`datapack:ati-structures-fabricforge:K7cpaKjN` and `datapack:borrow-their-arrows:Qq8BwuBw`).

```
fabric-api:aUrTRV7H
carpet:f2mvlGrg
datapack:borrow-their-arrows:Qq8BwuBw         # pick up arrows shot by mobs
```

**Trailing `?` marks a mod optional** — a failed resolution skips it instead of failing the seed/boot. The convention is `slug:versionId?`, e.g. a hypothetical `some-experimental-mod:AANobbMI?`. One entry in the shipped list deviates from this convention: `attributefix?:XwbErf6s` has the `?` positioned _before_ the colon rather than at the end of the line. `pin-mod-versions.sh`'s optional-detection (`[[ "$stripped" == *\? ]]`) checks whether the whole stripped line ends in `?` — this line doesn't, so it is **not** treated as optional by the re-pin tooling despite the `?` character being present. It happens to still resolve correctly today because `resolve-mods.py` looks up the pin by `versionId` (immutable), not by slug, so the malformed slug never reaches the API — but don't copy this pattern. Always put `?` as the very last character of the line.

**Commented-out entries carry their removal reason** as an inline comment, e.g. `# balm:jR9x1yws  # removed: only dep was waystones+netherportalfix (both removed)`. Follow this convention when disabling a mod rather than deleting the line outright — it's the only record of _why_ for the next person (or agent) who wonders whether it should come back.

**Section headers** (`# === core / performance ===`) are purely organisational — nothing parses them. Keep new entries under the most relevant existing header rather than inventing a new one, unless a whole new category is genuinely starting.

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

## Version holds (`modpack/adventure.mrpack.json` → `_clientMods.holds`)

Not a separate file — a JSON object inside the client pack manifest, keyed by slug with a free-text reason as the value:

```jsonc
"holds": {
  "c2me-fabric": "0.4.0-alpha.0.21 wedges fresh-world creation: ...",
  "critters-and-companions": "2.6.x claims 1.21.1 but is built against a newer Architectury ...",
  "xaeros-world-map": "1.42.0 removed Waypoint's int x/y/z fields; ...",
  "xaeros-minimap": "era-pair of xaeros-world-map 1.41.2 - held at 26.1.0 ..."
}
```

Only consulted by `pin-mod-versions.sh`'s **client-manifest** re-pin loop (the Python block that rewrites `_clientMods.required`/`.optional`) — a slug in `holds` there is skipped entirely, its current `versionId` left untouched. The bash loop that rewrites `config/modrinth-mods.txt` (the server list) does not read this object at all; see the "Version holds" trap in the main `SKILL.md` for what that means in practice for a server-only held mod like `c2me-fabric`.

## Two-place config rule: the flat-path exception

Most mods with configuration get a directory: `config/<modname>/` (e.g. `config/openpartiesandclaims/`, `config/boring_default_game_rules/`). A few read a single bare file instead — confirmed from `docker/defaults-seed/Dockerfile`'s `COPY` lines:

```dockerfile
COPY config/tectonic.json        /defaults/config/tectonic.json
COPY config/messages.json        /defaults/config/messages.json
COPY config/starterkit.json5     /defaults/config/starterkit.json5
COPY config/update-check-mute.txt /defaults/config/update-check-mute.txt
```

Don't assume directory-vs-flat-file from the mod's name — check the `Dockerfile`'s existing `COPY` lines (or the mod's own docs/jar) before adding a new config-bearing mod.
