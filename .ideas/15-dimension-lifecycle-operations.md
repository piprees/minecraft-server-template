# Skill brief: `dimension-lifecycle-operations`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

The existing `custom-dimension-authoring` skill (`config/custom-dimensions/SKILL.md`) is excellent at *writing a dimension config*. It is deliberately silent on everything that happens **after** a dimension exists on a live world, and that is where the expensive incidents are:

- **Removing a dimension by deleting its world directory is a boot wedge.** A lingering `Data.WorldGenSettings.dimensions` entry re-creates the dim every boot and regenerates its spawn chunks; if an Epic Dungeons dungeon lands there, the boot itself hangs forever. Hit twice on 2026-07-24.
- **`customdim destroy` unloads the world but does NOT scrub the level.dat entry.** Verified empirically.
- **All worldgen config is creation-time-only and survives everything short of a full world wipe** — deleting region files only regenerates old-style chunks, because vanilla re-persists the stored generator on every save.
- **BlueMap does not auto-discover runtime dimensions.** It reads its own config files, not the server's live registry.

None of this is authoring. All of it is operations, and it currently lives as five bullets in `AGENTS.md § Dimension lifecycle traps` plus a known-issue entry.

## Scope

**In:** the life of a dimension after its config exists. Which fields apply when (creation-time vs boot-re-read), applying a worldgen change, adding a dimension to a live world, fully removing one, the fingerprint drift warning, BlueMap registration, and the wedge and its recovery.

**Out:** writing or editing the JSON → `custom-dimension-authoring` (link, never duplicate the schema). Rolling seeds → brief 09. Mod internals → brief 05.

## Relationship to the existing skill

**Build this as a sibling that the authoring skill links to**, and add a one-line pointer at the end of `config/custom-dimensions/SKILL.md` § Validation. Do not merge them: the authoring skill is already 347 lines and its audience (write me a themed dimension) is different from this one's (this dimension is misbehaving on a live world).

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md § Dimension lifecycle traps` (lines 164-174) | **The core.** All seven bullets, especially the level.dat scrub procedure |
| `AGENTS.md § Known issues` → Epic Dungeons / c2me wedge | Both triggers, the spark false-positive caveat, the recovery, and the level.dat interaction |
| `config/custom-dimensions/SKILL.md` | The field-timing table (creation-time vs boot-re-read) — reuse its vocabulary exactly so the two skills agree |
| `mods/custom-dimensions/README.md § Configuration, § Commands` | The `customdim` command surface (`create`, `destroy`, `locate`, `sample-noise`, `dump-biome-params`); the v4 per-dimension directory; `portal_links.json` |
| `mods/AGENTS.md § Verification loop → 3. Exercise via RCON` | The namespace resolution snippet; how to prove a dimension loaded |
| `mods/AGENTS.md` → dynamic world lifecycle rule | `ServerWorldEvents.LOAD`/`UNLOAD` — why DH/BlueMap/c2me break without them |
| `config/custom-dimensions/settings.json`, `dimensions/`, `candidates/` | The on-disk layout |
| `config/bluemap/maps/*.conf` (79 files) | One per dimension — the manual registration surface |
| `AGENTS.md` trap 13 | BlueMap sidecar behaviour, `render-mask` config format, the ~5 min load window |
| `scripts/migrate-to-v4-config.sh` (header) | The v3→v4 split, for context on legacy configs |
| `docs/migration-v3.md`, `docs/dimension-profiles-v3.md` | Migration history |
| `custom-dimensions-fingerprints.json` (runtime, under `data/config/`) | The drift-warning mechanism: the mod fingerprints creation-time worldgen config and WARNs when it drifts — **it never deletes or regenerates on its own** |

## Required structure

```
dimension-lifecycle-operations/
├── SKILL.md
└── references/
    ├── field-timing.md      # every config field classified creation-time vs boot-re-read, with what "applying it" costs
    └── removal-procedure.md # the full level.dat scrub, step by step, with the ordering requirement
```

### SKILL.md must contain

1. **The timing question as the opening decision**, because it determines whether the task is five seconds or a world wipe:
   | Field group | Timing | To apply a change |
   | --- | --- | --- |
   | `type`, `noiseSettings`, `biomes`, `seed`, `environment` heights, `borders.generation` | **Creation-time only** | Wipe `data/world` (local) / a reset-seed ritual (production) |
   | `portal` (incl. `immersive`, `aura`, `anchor`, `singleUse`, `exitPortal`), `difficulty`, `borders.player`, `structureDensity`, `structures.force`/`mode`/`spacing` | **Re-read every boot** | Edit, restart, done |
2. **Why creation-time really means it.** The generator is serialised into `level.dat`'s `WorldGenSettings` at creation; `registerDimensions` skips keys already in the registry and vanilla re-persists the stored generator on every save. Deleting the region directory only regenerates old-style chunks. This is the fact agents disbelieve and then test destructively.
3. **The drift warning**, and its correct interpretation: the mod fingerprints creation-time worldgen config in `custom-dimensions-fingerprints.json` and WARNs at boot when a dimension's config has drifted from what its world was created with. It never deletes or regenerates. A WARN is information, not a failure — and not a licence to delete anything.
4. **The removal procedure**, in full, with the ordering requirement flagged as load-bearing. Proven locally 2026-07-24:
   1. stop mc
   2. back up `data/world/level.dat`
   3. `pip install nbtlib` in a scratch venv
   4. delete the dimension keys from `Data.WorldGenSettings.dimensions`
   5. delete the world dirs, config files, `custom-dimensions-fingerprints.json` entries, and `portal_links.json` records referencing the dims
   6. start mc

   **Every file must be gone BEFORE `docker start`**, or the boot re-reads and re-creates them.
5. **Adding a dimension to a live world** — the happy path. The mod reads `config/custom-dimensions/dimensions/` at boot, creates missing worlds, and reconciles orphans (any managed-namespace world not in the config is unloaded). Consumers add via `overlay/config/custom-dimensions/dimensions/`. Worlds are created lazily on first player entry, not at boot.
6. **BlueMap registration** as a separate, manual step: BlueMap discovers dimensions from its config files, not the live registry. A runtime dimension is invisible until a map config is written and BlueMap reloaded. Point at `config/bluemap/maps/` and note the v5.11+ `render-mask` format.
7. **Proving a dimension is live**, via RCON, with the namespace resolution from `mods/AGENTS.md`.

### Traps to capture

1. **Deleting a world directory without scrubbing level.dat is a boot wedge**, not a cleanup. The dim is re-created and its spawn chunks regenerated every boot.
2. **`customdim destroy` does not scrub level.dat.** It unloads the world only.
3. **Per-dimension seeds only apply at world creation time.** Changing a seed in config has no effect on an existing dimension.
4. **Local: `dev-up.sh` seeds configs with skip-if-exists**, so a config schema upgrade never lands. Delete the target file under `data/config/` first.
5. **Template and consumer configs must stay in sync.** `config/custom-dimensions/` is the source of truth; the consumer copy under `data/config/` must be an exact copy. **Always copy, never diff-and-decide.**
6. **The wedge's diagnosis is ambiguous.** `spark`'s `Timed out waiting for world statistics` alone is not proof — mass dimension creation legitimately runs 10+ minutes. Confirm with `Error upgrading chunk` / `DungeonZombie` counts and whether the log has stopped advancing. Recovery is `docker stop -t 90 mc && docker start mc` (local only; production goes through `deploy.sh`), plus the level.dat scrub when the trigger is a regenerating deleted dim.
7. **`LEVEL_TYPE=flat` on the overworld breaks structure placement in every custom dimension** — the generator templates off `overworldOpts`.
8. **c2me's DFC must stay disabled** or every custom dimension silently clones the main world. Both boot paths re-patch it; a bare `docker restart mc` boots unpatched.
9. **Never mutate the worlds map from a world tick**, and always fire `ServerWorldEvents.LOAD`/`UNLOAD` — skipping LOAD NPE'd Distant Horizons and locked a player out of production (2026-07-12).

### Validation section

```bash
docker exec -i mc rcon-cli "execute in <ns>:<slug> run seed"
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10
docker exec mc cat /data/logs/latest.log | grep -i "fingerprint\|drift"
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'
docker logs bluemap --tail 30      # after adding a map config
```

## Done when

- An agent asked to remove a dimension produces the full six-step scrub in the right order and refuses the "just delete the folder" shortcut.
- An agent asked to change a dimension's `biomes` on a live world says plainly that it needs a world wipe, and offers the boot-re-read alternatives if the goal can be met another way.
- The skill is linked from `config/custom-dimensions/SKILL.md` and links back to it, with no schema duplication in either direction.
