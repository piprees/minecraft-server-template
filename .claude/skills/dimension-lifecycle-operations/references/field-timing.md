---
title: Field Timing Reference
description: Every custom-dimensions config field classified creation-time-only vs boot-re-read, with the source evidence for each classification and what "applying it" actually costs.
tags: [creation-time, boot-re-read, worldgen, level.dat, borders, environment, portal]
---

# Field Timing Reference

This is the field-by-field backing for the timing table in `SKILL.md`. Use it when the summary table doesn't cover the exact field you're asking about, or when you need to justify a classification to someone who doubts it.

Vocabulary matches `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) exactly — the two skills must never disagree about which bucket a field is in.

## Creation-time only (needs a world wipe to change)

These fields are serialised into `level.dat`'s `Data.WorldGenSettings.dimensions` entry the moment a dimension is first created. `DimensionManager.registerDimensions` skips any key already present in the registry, and vanilla re-persists the stored generator on every save — so editing the config afterwards has no effect, ever, without deleting `data/world` (local) or running the reset-seed ritual (production).

| Field | Why it's creation-time |
| --- | --- |
| `type` | Determines the chunk generator baked into `WorldGenSettings` at creation. |
| `noiseSettings` | Swaps the `ChunkGeneratorSettings` at creation; same serialisation. |
| `biomes` | The biome source is part of the generator, baked in at creation. |
| `seed` | `ServerWorldSeedMixin` feeds this into `NoiseConfig` at world creation; changing it after the fact does nothing to already-generated (or even not-yet-generated, since the generator object is already fixed) chunks. |
| `environment.minY`, `environment.height`, `environment.logicalHeight` | These determine chunk section count and Y-indexing on disk. Rebuilding the `DimensionType` registry entry fresh at each boot (which the mod does do) does not retroactively resize already-written chunk sections — changing these after chunks exist corrupts or mismatches on-disk data, same failure class as vanilla's own height-change warnings. |

**A subtlety worth knowing but not relying on**: the `DimensionType` registry entry itself (built by `DimensionTypeBuilder.typeEntryFor`) _is_ rebuilt fresh from config on every boot — the guard that returns an existing entry (`registry.getEntry(key)`) is an idempotency check within a single boot, not a permanent freeze across boots. That means the _non-storage-shape_ `environment` fields below genuinely do take a fresh value each boot. It's only `minY`/`height`/`logicalHeight` (storage shape) that you should treat as fixed.

## Re-read every boot (edit, restart, done)

No world wipe needed. Verified against `mods/custom-dimensions/README.md` and `mods/AGENTS.md` — both say explicitly "boot-re-read" for the portal block and difficulty.

| Field | Evidence |
| --- | --- |
| `portal.*` (frame materials, `shape`, `immersive`, `aura`, `anchor`, `singleUse`, `exitPortal`, sounds, cooldown, colour) | `mods/AGENTS.md`: "Portal config is NOT creation-time-only — unlike worldgen it re-reads every boot, so anchor/singleUse/exitPortal changes apply to existing dimensions without a wipe." `immersive` specifically: "It is boot-re-read, like the rest of the portal block, so it applies to dimensions that already exist without a world wipe." |
| `difficulty.*` (`mobMultiplier`, `attributes`, `playerLuck`, `depthScaling`, `hostileSpawning`) | Applied via `MobAttributeMixin` at mob spawn/join time against live config lookups — no persisted generator involved. |
| `borders.player` | `WorldBorderManager.apply()` sets the vanilla `WorldBorder` from config at `SERVER_STARTED` (and on `ServerWorldEvents.LOAD` for runtime-created worlds, post-boot). Re-applied every boot; `0` means "no border, leave untouched". |
| `structureDensity` | Rebuilds the world's `StructurePlacementCalculator` with rescaled placement copies at boot — never mutates the global registry. New chunks only; already-generated chunks keep whatever placed before the change. |
| `structures.force`, `structures.mode`/`list`, `structures.spacing` | Same runtime-rebuild mechanism as `structureDensity` — "a RUNTIME rebuild (re-read every boot, newly generated chunks only) — not creation-time worldgen" (`mods/custom-dimensions/README.md`). |
| `exits` | Trigger→action map evaluated live at void/death/pearl/fall time; no persisted state beyond `portal_links.json` bookkeeping for anti-loop cooldowns. |
| `exitShrines` | Split timing: the jigsaw structure worldgen placement is creation-time (it's a structure, like any other), but the beacon-detection that registers a shrine as an exit zone runs on chunk load and is boot-re-read — turning `exitShrines.enabled` off stops new detections without touching already-placed structures. |
| `environment.fixedTime`, `hasCeiling`, `hasSkylight`, `ultraWarm`, `natural`, `bedWorks`, `respawnAnchorWorks`, `piglinSafe`, `hasRaids`, `ambientLight`, `effects`, `infiniburn`, `monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit` | Part of the same `DimensionType` object as the creation-time heights, but these fields don't affect on-disk chunk storage shape — they're read fresh into the rebuilt type each boot. `skyColor`/`fogColor` are accepted in config but are client-side rendering concerns the mod explicitly does not (and cannot) apply server-side; a boot log line says so. |

## Not read by the mod at all (tooling metadata)

| Field | What actually reads it |
| --- | --- |
| `borders.generation` | **Not the mod.** `WorldBorderManager`'s own source comment: "`borders.generation` is deliberately NOT applied here: it is metadata for tooling." The only current consumer is the `unmined-render` sidecar's `generation_radius()` function, which reads it (falling back to `settings.json`'s `defaults.borders.generation`, then `PREGEN_BORDER_RADIUS`) to clamp the rendered map area. Historically also fed Chunky pre-generation bounds. Changing it needs no restart and no wipe — it takes effect on the renderer's next scheduled pass, or immediately via `./ops map render`. |

Do not classify `borders.generation` alongside `borders.player` (boot-re-read by the mod) or alongside the creation-time worldgen fields — it is neither. It's descriptive metadata the mod ignores entirely.

## Cosmetic / documentation-only (no runtime effect either way)

| Field | Note |
| --- | --- |
| `description` | Documentation-only, surfaced by tooling, never parsed by the mod. |
| `dimensionId` | Legacy. The id derives from `{namespace}:{filename}`; setting this does nothing (the four reserved filenames resolve to their existing ids regardless). |

## Quick self-check

If you're not sure which bucket a field is in and it isn't listed above:

1. Does changing it require the chunk generator, biome source, or on-disk chunk shape to differ? → creation-time.
2. Does it gate a portal, difficulty modifier, structure placement calculator, or a world border? → boot-re-read.
3. Is it read by anything other than the mod (a sidecar, a script)? → check that consumer's source before assuming a timing model at all — `borders.generation` is the proof that "looks like worldgen metadata" and "is worldgen metadata the mod applies" are different claims.
