# Seed Roller v2 — prompt for a fresh agent session

You are building a fast, parallel, fully automated seed roller for
piprees/minecraft-server-template (branch claude/worldgen-customisation-major-4f2z49).

READ FIRST: AGENTS.md, mods/AGENTS.md (verification loop + c2me DFC trap),
config/multiverse_config.json, docs/dimension-profiles-v3.md,
mods/.ideas/v3-testing-log.md (bugs found by previous sessions),
mods/.ideas/process-failures-and-interventions.md (the seed-rolling-specific
failure log), mods/.ideas/agent-guardrails.md (operating contract).

## Context

The existing `./dev seed-roll` command works but is slow (one boot per
seed, sequential). A previous session attempted to build a parallel
roller and spent $214 without finishing. The bugs it found are real and
documented in v3-testing-log.md. The code it wrote is in the repo but
NONE of it is tested end-to-end. Treat it as reference, not as working
code.

The mod already has `customdim create/destroy` RCON commands and
`SEED_ROLL_MODE=true` env var support (skips boot-time dimension
creation). These were tested manually and work. Use them.

## What to build

A single command that:

1. Boots N Docker containers in SEED_ROLL_MODE (no dimensions at boot)
2. For each dimension in multiverse_config.json (excluding void without
   biomes, and superflat without biomes):
   - Reads the dimension's type, seed, noiseSettings, structureDensity,
     biome, and portal scale from the config
   - Generates M candidate seeds (64-bit signed, via python3 struct.unpack)
   - For each candidate: `customdim create` → measure → `customdim destroy`
3. Measurement = locate battery (structures appropriate to dim type) +
   terrain grid + BlueMap render
4. Scores each candidate automatically using scale-aware placement bands
5. When stopped (Ctrl+C) or when all dims have enough candidates:
   - Picks the best seed per dimension automatically
   - Writes the winners into config/multiverse_config.json
   - Generates a viewer HTML with the results for review
   - Opens the viewer

## Scoring model

Each dimension has an effective playable radius = overworld_border / scale.
The portal scale is in the portals array of multiverse_config.json (field
name: `scale`).

Structure distances are scored by placement band relative to the radius:
- near_spawn (0-30% of radius): villages, taverns, mineshafts
- spread (20-70%): trial chambers, fortresses, bastions
- near_border (50-100%): ancient cities, monuments, sanctums
- beyond_border (80-300%): WDA coliseum, mansions

Adjust placements based on structureDensity:
- dense: shift everything closer (near_border → spread, beyond_border → near_border)
- sparse: shift everything further (near_spawn → spread, spread → near_border)

Peaceful dims (hostileSpawning: false): drop hostile structures entirely.

Terrain scoring from noiseSettings:
- compressed: want high relief + high grain
- wide: want moderate relief, low grain, some water
- default: moderate everything

The best seed per dimension is the one with the highest total score.

## Locate battery by dimension type

Overworld family (overworld, multi_biome, amplified, large_biomes, sky_islands):
  village (#minecraft:village), ancient_city, trial_chambers, tavern
  (dungeons_and_taverns:tavern), wda (dungeons_arise:coliseum), monument,
  mansion, mineshaft

Nether (nether, nether_islands):
  fortress (betterfortresses:fortress), bastion, sanctum (incendium:sanctum)

End:
  end_city, end_gateway

Void with biomes:
  locate biome for each biome in the config's biome list

## BlueMap renders

BlueMap is a Fabric mod in the server's mod list. It does NOT auto-discover
runtime dimensions. To render a dimension created via `customdim create`:

1. Write a BlueMap map config file for the dimension into
   /data/config/bluemap/maps/<name>.conf (format: world path + dimension key)
2. Run `bluemap reload` via RCON
3. Unfreeze ticks, forceload chunks, wait for generation
4. Run `bluemap render <map_name>` or let the auto-updater pick it up
5. Grab the tile PNG from /data/bluemap/web/maps/<name>/tiles/

The map config format (from existing configs):
```
world: "world"
dimension: "adventure:<dim_name>"
name: "<dim_name>"
sorting: 100
```

If BlueMap rendering proves too slow or complex, fall back to the
heightmap renderer at scripts/seed/render-region.py — but read the
WORLD_SURFACE AND OCEAN_FLOOR heightmaps, and also read the block
palette from chunk sections to colour by block type (grass=green,
water=blue, sand=yellow, stone=grey, snow=white). The previous session's
renderer only used heights and produced unusable brown images.

## Parallelism

Each worker is a Docker container. Split the dimension list across N
workers. Each worker processes its dims sequentially: create → measure →
destroy → next. Workers write to separate output files. Merge at the end.

No shared CSV. No flock. No orchestrator beyond the initial split.

## Known bugs to avoid

- `od -An -td8` on macOS BSD produces single-byte values. Use
  `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"`.
- `LEVEL_TYPE=flat` on the overworld breaks structure placement in ALL
  custom dimensions. Use a normal overworld.
- `collective` must not be stripped from the mod list (9+ mods depend on it).
- Per-dimension seeds only apply at world creation time. Fresh world
  required (SEED_ROLL_MODE handles this).
- data/config/bluemap/ and data/config/DistantHorizons/ contain
  per-dimension state from the live server. Delete them from seedtest
  worker dirs after copying data/config/.
- BlueMap needs accept-download: true in core.conf or it won't load.
- The `customdim create` noiseSettings argument needs IdentifierArgumentType
  (allows colons). Already implemented in the mod.

## Output

1. Updated config/multiverse_config.json with best seed per dimension
2. .seedtest/viewer.html — interactive viewer showing all candidates,
   scores, renders, and the auto-picked winners highlighted
3. .seedtest/measurements.csv — raw measurement data
4. Console summary: per-dimension best seed + score

## Consumer integration

Wire into `./dev seed-roll-all` in examples/consumer/dev. The command
should work from a consumer repo (elfydd) with the stack symlinked.
The consumer needs `config/multiverse_config.json` accessible — currently
symlinked from elfydd/config/ → .stack/current/stack/config/.

## Time budget

The whole thing should complete in <1 hour for 70 dimensions with 32
candidates each, using 3 workers. That's ~23 dims per worker, ~45 seconds
per candidate (boot is shared, create/measure/destroy is ~30-45s per
candidate). Total: ~23 × 32 × 45s / 60 = ~550 minutes single-threaded,
~185 minutes with 3 workers. Under 1 hour needs either fewer candidates
(16) or more workers (6).

Do the maths. Pick the right worker count for the hardware (18 cores,
32GB RAM, 6G per container). Test with 1 worker and 2 dims first. Verify
the output. Then scale up.
