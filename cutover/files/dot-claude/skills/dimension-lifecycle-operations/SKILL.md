---
name: dimension-lifecycle-operations
description: Operate on a custom-dimensions dimension that already exists on a live 26.3 server — deciding whether a definition change is a restart or a world wipe, opening, unloading and deleting a lazy level, reading the D2 WARN the pack logs when a generator changes under existing chunks, auditing the definitions against the pack, and confirming a dimension shows on the map. Use when a dimension needs deleting or resetting, a worldgen field (generator, terrain, biomes, biomeLayout, zones, liquids, materials, seed) changes on a world with players in it, `customdim list` shows a level stuck in `opening` or `unloading`, or the boot log names D2. Complements custom-dimension-authoring (writing the definition) without repeating its schema.
---

# Dimension lifecycle operations

You are operating on a dimension that exists on a running server. Definitions: `config/custom-dimensions/dimensions/<slug>.json`. The four base worlds (`overworld`, `the_nether`, `the_end`, `the_aviary`) load at start and are never unloaded; every other dimension is a lazy Fantasy level, `<namespace>:<slug>`, stored in `data/world/dimensions/<namespace>/<slug>/` and opened on first use ([mod README § Runtime levels](../../../mods/custom-dimensions-26/README.md#runtime-levels)).

## Which change is which

The mod compiles the definitions into `data/world/datapacks/customdimensions/` at every start-up and rewrites the pack only when it differs ([§ The pack at start-up](../../../mods/custom-dimensions-26/README.md#the-pack-at-start-up)).

| Change | Takes effect | On existing chunks |
| --- | --- | --- |
| `difficulty`, `rules`, `gameRules`, `deaths`, `wins`, `exits`, `portals` (frames, colours, links), `borders.player`, `atmosphere` | Next restart (a full deploy) | Applies everywhere |
| `structures` | Next restart | New chunks only; structures already placed stay |
| `generator`, `terrain`, `biomes`, `biomeLayout`, `zones`, `liquids`, `materials`, `carvers`, `ores`, `dimensionType`, `seed` | Next restart | **New chunks only** ([D2](../../../TROUBLESHOOTING.md#d2)): old and new ground meet at a seam. The pack logs one WARN naming D2 per dimension whose noise settings or generator changed while its region folder holds chunks. `seed` is the exception: a level with chunks keeps the seed they generated from (recorded in its folder's `customdimensions-seed.json`) and logs a WARN naming both seeds, so a new seed reaches a level only after its folder is wiped |

A worldgen change on a dimension players have built in is a decision for the owner: accept the seam (far from builds), or reset that dimension.

## Commands

All are `/customdim` (permission 4), over RCON as `./ops rcon "customdim …"`:

| Command | Use |
| --- | --- |
| `list` | Every dimension's state (`base`, `lazy`, `queued`, `opening`, `active`, `unloading`, `deleting`), players and loaded chunks |
| `info <dimension>` | Generator, seed, height, difficulty, portals, exits, wins, borders, the game rule values of its own, state |
| `activate <dimension>`, `unload <dimension>` | Open or close a lazy level, one at a time ([K6](../../../TROUBLESHOOTING.md#k6)) |
| `delete <dimension> [confirm]` | Without `confirm`, names the level folder that would go; with it, unloads and deletes it. Base worlds are refused; the definition stays, so the level is generated afresh on next entry |
| `audit` | Writes `<world>/customdimensions/audit.md`: stale or missing compiled files, level folders with no definition, portal links to unknown dimensions, missing frame blocks, skipped biomes, the firewall's findings |

## Reset one dimension

**Confirm with the owner first** — it deletes players' builds in that dimension (AGENTS.md § Confirm before proceeding).

1. `./ops backup` and wait for `snapshot ... saved`.
2. `./ops rcon "customdim list"`: nobody in it.
3. `./ops rcon "customdim delete <slug>"`, read the folder it names, then `./ops rcon "customdim delete <slug> confirm"`.
4. Change the definition if that was the point, and deploy it.
5. `./ops rcon "customdim activate <slug>"`, then `customdim list` until it reads `active` (snapshots by hand, never a loop).

Fantasy never writes its level stems to `level.dat`, so a deleted level leaves nothing behind to re-create it ([mod README § Runtime levels](../../../mods/custom-dimensions-26/README.md#runtime-levels)).

## Remove a dimension for good

Reset it as above without step 5, then delete its definition file from `config/custom-dimensions/dimensions/` and every portal link that names it (`customdim audit` lists them), and deploy.

## The map

`unmined-render` draws a dimension once it has region files ([D8](../../../TROUBLESHOOTING.md#d8)); a lazy level nobody has entered has none. `./ops map render` forces a pass.

## Traps

- **An empty 26.x server stops ticking** ([T176](../../../TROUBLESHOOTING.md#t176)), and autopause freezes the JVM after 10 empty minutes: RCON silence on a healthy container is usually that, not an outage.
- **A level held by our ticket never unloads** ([T118](../../../TROUBLESHOOTING.md#t118)); `unloading` that never ends is a held ticket.
- **26.x has no always-loaded spawn chunks** ([T117](../../../TROUBLESHOOTING.md#t117)).
- **Time lives in server-wide clocks on 26.x** ([T223](../../../TROUBLESHOOTING.md#t223)): dimensions with the same day length share one, and `time set` moves the clock of the dimension it runs in ([T179](../../../TROUBLESHOOTING.md#t179)).
- **Never run a measurement that loads chunks on the server thread for long** ([T120](../../../TROUBLESHOOTING.md#t120)): `render`, `roll` and `survey` run off it; one at a time.
