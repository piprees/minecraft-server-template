---
name: dimension-lifecycle-operations
description: Operate on a custom-dimensions Fabric mod dimension that already exists on a live Minecraft server — deciding whether a config change is a five-second edit or a world wipe, fully removing a dimension (the level.dat scrub), interpreting the fingerprint drift WARN, and confirming a dimension rendered on the map. Use when a dimension needs deleting, a worldgen field (type, noiseSettings, biomes, seed) is being changed on a world that already has players in it, a boot hangs after deleting a dimension's world directory, `docker logs mc` shows "Error upgrading chunk" or "Non [a-z0-9/._-] character in path", spark reports "Timed out waiting for world statistics", or a dimension is missing from the map after `customdim create`. Complements .claude/skills/custom-dimension-authoring/SKILL.md (authoring the JSON) without duplicating its schema — that skill writes the file, this one operates on the live world it produces.
---

# Dimension Lifecycle Operations

You are operating on a **custom-dimensions** dimension that already exists on a running server (local or production) — not writing its JSON. For the schema, biome/structure catalogues, and mood scoring, see `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`). This skill owns what happens after the config exists: which fields take effect on restart versus never without a wipe, removing a dimension without wedging the boot, reading the drift warning correctly, and confirming a dimension is actually live and on the map.

**Not this skill:** writing/editing dimension JSON → `custom-dimension-authoring`. Rolling seeds — there is no dedicated skill for this any more; see `mods/custom-dimensions/README.md` § Seed roller and `mods/AGENTS.md` § Diagnostic artefacts. Mod internals/mixins/build → `fabric-mod-development`.

## The timing question — ask this first

Every dimension-lifecycle task reduces to one question: _does this field apply on restart, or does it require destroying data?_ Get this wrong and you either wipe a world that didn't need it, or spend an hour restarting a server that was never going to pick up the change.

| Field group | Timing | To apply a change |
| --- | --- | --- |
| `type`, `noiseSettings`, `biomes`, `seed`, `environment.minY`/`height`/`logicalHeight` | **Creation-time only** | Wipe `data/world` (local) / a reset-seed ritual (production, `./ops reset-seed` — confirm-before-proceed) |
| `portal` (all of it — `immersive`, `aura`, `anchor`, `singleUse`, `exitPortal`, frame materials, `shape`, sounds), `difficulty`, `borders.player`, `structureDensity`, `structures.force`/`mode`/`list`/`spacing`, `exits`, most of `environment` (`fixedTime`, `hasCeiling`, `hasSkylight`, `ultraWarm`, `natural`, `bedWorks`, `respawnAnchorWorks`, `piglinSafe`, `hasRaids`, `ambientLight`, `effects`, `infiniburn`, `monsterSpawnLightLevel`/`BlockLightLimit`) | **Re-read every boot** | Edit the file, restart mc, done — no wipe |
| `structures.wants`/`shuns` | **Re-read every boot** — pool weights, newly generated chunks only | Edit the file, restart mc; already-generated chunks keep what they got. Boot WARNs that the pool moved, explicitly without asking for a wipe |
| `borders.generation` | **Never applied to a world** — tooling metadata for the renderer, Chunky, `getLocateCap` and lint (see below) | Edit the file; takes effect on the next map render pass, no restart needed. It is in the generation fingerprint, so it re-keys the seed bank |

Full field-by-field reference, with the exact source line for each classification: `references/field-timing.md`. Read it before telling anyone a field is safe to change on a live world — the exceptions (`environment` heights, `borders.generation`) are easy to get backwards from general Minecraft knowledge.

**`structures.force`/`mode`/`spacing` are boot-re-read but only affect newly generated chunks** — like `structureDensity`, this is a runtime structure-placement-calculator rebuild, not worldgen.

## Why "creation-time" really means it

The dimension's chunk generator is serialised into `level.dat`'s `Data.WorldGenSettings.dimensions` entry the first time the dimension is created. `registerDimensions` skips any key already present in that registry, and vanilla re-persists the stored generator on every save — so a config edit to `type`/`noiseSettings`/`biomes`/`seed` has **zero effect** on a dimension that already exists, forever, until the world is wiped. Two shortcuts that look like they should work and don't:

- **Deleting the dimension's region directory** (`data/world/dimensions/<ns>/<slug>/region/`) only regenerates _old-style_ chunks using the _same stored generator_ — it does not pick up your config change.
- **`customdim destroy <name>`** unloads the world from memory. It does **not** touch `level.dat`. The next boot re-reads the config, sees the dimension key still in the registry, and recreates it — with the OLD generator, not your edit (verified empirically 2026-07-22, converting a dimension to `type: cave`).

If the goal is "change this dimension's terrain/biomes on a world with players already in it", the honest answer is a world wipe (local: delete `data/world`; production: the reset-seed ritual). If a re-read alternative can meet the actual goal — a different portal, difficulty, structure density, or border — offer that instead of the wipe.

## The drift warning — read it correctly

The mod fingerprints each dimension's creation-time worldgen config into `config/custom-dimensions-fingerprints.json` and logs a WARN at boot when a dimension's current config has drifted from what its world was actually created with. **This is informational, not an error, and never triggers any auto-fix or auto-regeneration.** It exists so a stale `noiseSettings` edit doesn't get silently forgotten about — it does not mean the dimension is broken, and it is never licence to delete anything. If the drift is real (someone changed `type`/`biomes`/`seed` and expected it to take effect), the fix is the wipe above, not touching the fingerprint file.

## Removing a dimension — do not just delete the folder

**Deleting a runtime dimension's world directory without scrubbing its `level.dat` entry is a boot wedge, not a cleanup.** The lingering `Data.WorldGenSettings.dimensions` entry recreates the dimension on the very next boot, and with the world directory gone it **regenerates spawn chunks from scratch on that boot** — which can hit the Epic Dungeons/c2me wedge (below) on the boot itself, hanging the server before you even notice the dimension came back.

The full six-step scrub, proven locally 2026-07-24, is in `references/removal-procedure.md`. Short version: stop mc → back up `level.dat` → edit it with `nbtlib` to remove the dimension's `WorldGenSettings.dimensions` key → delete the world directory, config file, fingerprint entry, and any `portal_links.json` records referencing it → **only then** start mc. Every file must be gone before `docker start`, or the boot re-reads and re-creates them from whatever is still on disk.

Refuse the "just delete the folder" shortcut whenever asked to remove a dimension — it is the single most common way this goes wrong.

## Adding a dimension to a live world

This direction is the easy one. The mod reads `config/custom-dimensions/dimensions/*.json` at boot (one file per dimension), creates any dimension in that directory it doesn't already have a registry entry for, and reconciles orphans — any world in the mod's managed namespace that is no longer in the config gets unloaded (not deleted). Consumers add dimensions via `overlay/config/custom-dimensions/dimensions/<slug>.json`.

**Worlds are created lazily on first player entry, not at boot** — adding a config file and restarting does not itself generate the world; nothing exists on disk until a player (or a Carpet bot, or a forced portal traversal) actually enters it. A dimension with a config file but no player visit yet is normal, not broken, and won't appear on the map (below) until it has.

```bash
cp config/custom-dimensions/dimensions/<slug>.json <consumer>/overlay/config/custom-dimensions/dimensions/<slug>.json
./dev up   # or push to main for production
```

## The map — fully automatic, no manual registration

The map renderer (`docker/unmined-render/render-loop.sh`) needs no per-dimension registration at all:

- It walks `data/world/dimensions/<ns>/<slug>/region/*.mca` directly on every pass (default every `UNMINED_INTERVAL`, 6h) and renders whatever it finds. A dimension appears **automatically** once it has real generated chunks — no config file to write, no reload command to run.
- It reads `borders.generation` from the dimension's own config (falling back to `settings.json`'s `defaults.borders.generation`, then `PREGEN_BORDER_RADIUS`) to clamp the rendered area. `WorldBorderManager` (the mod) explicitly does _not_ apply it — the other readers are Chunky's pre-generation extent (`scripts/idle-tasks.sh`), `DimensionConfig.getLocateCap()` and `DimensionLint.checkGenerationBorder`; none of them gates chunk generation.
- Served at `map.DOMAIN` via `nav-proxy` from `data/unmined-web/`, always up regardless of `mc`'s state (plain files).

```bash
./ops map status                 # container state + recent render activity
./ops map render                 # restart unmined-render, forcing an immediate pass
```

If a dimension is missing from the map: confirm it has actually generated chunks (a player has to have visited, or it's been pre-genned) before assuming the renderer is broken — an unvisited dimension renders nothing because there is nothing on disk yet.

## Proving a dimension is live

```bash
# Namespace from config/custom-dimensions/settings.json "namespace" (default "adventure")
docker exec -i mc rcon-cli "execute in adventure:<slug> run seed"
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10
```

An empty/error RCON response under load (world creation, Chunky, mod sync in progress) is a timeout to re-check, not proof the dimension failed.

## The wedge — known issue, watch for it after any dimension deletion

Two independent triggers land on the same hang: `docker ps` stays healthy, RCON goes i/o-timeout, and the boot never finishes.

1. **Epic Dungeons loot ids.** `epic:chests/DungeonZombie` (uppercase — invalid identifier path) throws `Non [a-z0-9/._-] character in path` during chunk feature placement when a dungeon generates. Under c2me the chunk upgrade fails once (`Error upgrading chunk [x, z] to "minecraft:features"`), and any main-thread sync chunk load waiting on that chunk hangs forever.
2. **A regenerating deleted dimension.** Delete a world directory without scrubbing `level.dat` (above) and the next boot recreates the dimension and regenerates its spawn chunks — if a dungeon lands there, the boot itself wedges.

**Diagnosis caveat:** spark's `Timed out waiting for world statistics` alone is _not_ proof of the wedge — mass dimension creation legitimately runs 10+ minutes. Confirm with `Error upgrading chunk` / `DungeonZombie` counts and whether the log has stopped advancing.

**Recovery:** `docker stop -t 90 mc && docker start mc` (local only; production goes through `deploy.sh`). If the trigger was a regenerating deleted dimension, you must also do the level.dat scrub — restarting alone reproduces the wedge on the very next boot.

## Traps

1. **Deleting a world directory without scrubbing `level.dat` is a boot wedge, not a cleanup.** The dimension is re-created and its spawn chunks regenerated on the very next boot.
2. **`customdim destroy` does not scrub `level.dat`.** It unloads the world only — the config-registry entry survives and recreates the dimension next boot.
3. **Per-dimension seeds only apply at world creation time.** Editing `seed` in a dimension's config does nothing to a dimension that already exists.
4. **Local: `dev-up.sh` seeds config with skip-if-exists.** A v3→v4 (or any future) config schema upgrade never lands over an existing `data/config/custom-dimensions/` — delete the target file/directory under `data/config/` before `./dev up` if you need the new shape to apply.
5. **Template and consumer configs must stay in sync.** `config/custom-dimensions/` (this repo) is the source of truth; `data/config/custom-dimensions/` (a consumer's live copy) must be an exact mirror after any edit. Always copy, never diff-and-decide.
6. **The wedge's diagnosis is ambiguous — see § The wedge above.** Don't declare a hang "just a slow boot" or "definitely the wedge" from a single log line.
7. **`LEVEL_TYPE=flat` on the overworld breaks structure placement in every custom dimension.** The generator templates off `overworldOpts`; a flat overworld makes every custom dimension fall through to the default (flat) branch regardless of its own `type`.
8. **c2me's density-function compiler must stay disabled**, or every custom dimension silently clones the main world's terrain (it ignores per-dimension seeds). The mod's preLaunch entrypoint re-supplies `useDensityFunctionCompiler = false` on every boot, so a bare `docker restart mc` stays patched ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6) — the scripts still pre-patch as a second layer covering a fresh environment's first boot). Verify by reading `c2me.toml`: `docker exec mc grep useDensityFunctionCompiler /data/config/c2me.toml` must answer `= false` on c2me `0.4.0-alpha.0.27`, which keeps the key. Below that pin it is stripped as unknown and D6's log grep is the proof instead.
9. **Never mutate the server's worlds map from a world-tick context**, and always fire `ServerWorldEvents.LOAD` when adding a `ServerWorld` / `UNLOAD` before closing one. Distant Horizons and c2me build per-level state exclusively from these events — skipping `LOAD` NPE'd Distant Horizons and locked a player out of production (2026-07-12).

## Validation

```bash
# Dimension loaded and generating with the expected seed
docker exec -i mc rcon-cli "execute in <ns>:<slug> run seed"
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10

# Drift warning check (informational only — never treat as a failure on its own)
docker exec mc cat /data/logs/latest.log | grep -i "fingerprint\|drift"

# Health / crash-loop check after any dimension operation
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'

# Map picked it up (only meaningful once the dimension has generated chunks)
./ops map status
```

Loud failures: malformed JSON, invalid `structures.wants`/`shuns` shapes (see `custom-dimension-authoring`). Silent failures: a creation-time field edited on an existing dimension (no error, no effect, ever); a dimension deleted by folder only (no error until the next boot's wedge); a dimension absent from the map because it has never generated chunks.

## `/customdim` commands (debugging surface, permission level 4)

Dimensions and portals are config, not commands — there is **no** `/dimension create` and **no** `/portal link`. `/customdim` is a debugging/diagnostic root, not the way dimensions get created day to day:

| Command | What it does |
| --- | --- |
| `create <name> <type> <seed> [noiseSettings] [structureDensity] [biome…]` | Create a runtime dimension ad hoc. Prefer a config file. |
| `destroy <name>` | Unload a runtime dimension. Does **not** scrub `level.dat` — see § Removing a dimension. |
| `list` | List managed dimensions. |
| `load <name>` | Queue a world load (drained on the next server tick). |
| `locate biome <dimension> <biome_id> [timeout]` | Async biome locate; returns a ticket UUID. |
| `locate structure <dimension> <structure_id> [timeout]` | Async structure locate; returns a ticket UUID. |
| `locate-result <uuid>` | Collect the result of an async locate. |
| `sample-noise <dimension> <x> <z>` | Generation ground-truth oracle at `(x & ~3, 0, z & ~3)`. |
| `debug-prng <seed>` | PRNG diagnostics. |

## See also

- `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) — the JSON schema, biome/structure catalogues, mood scoring. Consult it for anything about what to _write_; this skill is about what happens once it's written and live.
- `references/field-timing.md` — every config field classified, with the source line backing each classification.
- `references/removal-procedure.md` — the full level.dat scrub, step by step.
