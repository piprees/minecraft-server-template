# Custom Dimensions v4 — Unified Configuration & Seed Rolling

## Problem Statement

The current custom-dimensions system has too many moving parts:

- **74 dimensions + 74 portals + 4 worlds** crammed into one 2,500-line `multiverse_config.json`
- **Difficulty** lives in a separate mod (`configurable-difficulty`) with its own 165-line JSON5 config
- **World borders** are set via RCON dance in `deploy.sh` using ChunkyBorder
- **Seed rolling** is a standalone Python pipeline (`scripts/seed/`) that reads from the config but writes back awkwardly
- **seedRoll** properties are advisory metadata in the dimension config that the mod completely ignores — only the Python scripts read them
- **Structure wants/shuns** use short-name strings that map to a curated Python dict, not the actual game registry
- **Consumer customisation** requires copying/editing the entire monolithic config or using the overlay system

The result: configuring a dimension requires touching 4+ files across 3 systems, and the seed roller's scoring model is disconnected from the mod's runtime behaviour.

## Goals

1. **One file per dimension** — each dimension is fully described by a single JSON file
2. **One mod for everything** — difficulty, borders, portals, skybox, terrain, structures, seed rolling config
3. **Consumer-friendly** — drop a file in your overlay to add/override/disable a dimension
4. **Seed rolling as a first-class feature** — candidates stored alongside configs, scoring recomputable without re-rolling
5. **Extractors for game data** — scripts to pull biomes, structures, blocks, mobs from installed mod JARs

## Architecture

### Directory Structure

```
config/custom-dimensions/
├── settings.json                          # global defaults (namespace, idle unload, frame defaults)
├── dimensions/
│   ├── overworld.json                     # base-world override (seed, spawn, difficulty, borders)
│   ├── the_nether.json                    # base-world override
│   ├── the_end.json                       # base-world override
│   ├── paradise_lost.json                 # static mod dimension override
│   ├── the_claymarsh.json                 # custom dimension (full config)
│   ├── the_scorched_mesa.json             # custom dimension
│   └── ...                                # one file per dimension
├── candidates/
│   ├── the_claymarsh.json                 # seed candidates + measurements + scores
│   ├── overworld.json                     # world seed candidates
│   └── ...
└── extractors/                            # output from game-data extraction scripts
    ├── biomes.json                        # all biomes from installed mods
    ├── structures.json                    # all structure sets from installed mods
    ├── blocks.json                        # all blocks from installed mods
    └── entities.json                      # all entity types from installed mods
```

### Consumer Overlay

Consumers place files in `overlay/config/custom-dimensions/dimensions/`. Resolution order:

1. Consumer overlay file exists → **use it exclusively** (consumer owns this dimension)
2. Consumer overlay file exists with top-level `"overrides": { ... }` → **merge over platform default**
3. No consumer file → **use platform default**
4. Consumer file exists but is empty `{}` → **skip this dimension entirely** (custom dims are not created; base worlds fall back to vanilla with env-var seed/spawn if set)

Consumer-added dimensions get namespace `{BRAND_SLUG}:{filename-without-extension}`. Platform dimensions keep `adventure:*`. Base worlds keep their vanilla IDs.

### Per-Dimension Config Schema

Each dimension file is a complete, self-contained description. Example `the_claymarsh.json`:

```jsonc
{
  // === Identity ===
  "type": "overworld",                    // overworld | nether | end | void | superflat | multi_biome | sky_islands | nether_islands | "ns:clone_type"
  "description": "Quiet wetland wilderness — reed beds, drowned ruins, things half-sunk in the clay.",

  // === World Generation ===
  "seed": -4254781042587868201,           // or "env" to read from SEED env var (base worlds only)
  "spawn": [128, 64, -45],               // dimension spawn point (written by seed roller, or manual)
  "noiseSettings": "adventure:wide",      // custom noise preset (null = type default)
  "biomes": [                             // biome list for multi_biome/void types
    "natures_spirit:marsh",
    "minecraft:swamp",
    "minecraft:mangrove_swamp"
  ],

  // === World Borders ===
  "borders": {
    "player": 8192,                       // in-game world border radius (0 = no border)
    "generation": 8192                    // chunky/tool generation limit (0 = no limit, independent of player border)
  },

  // === Difficulty ===
  "difficulty": {
    "hostileSpawning": true,              // false = peaceful dimension (no hostile mobs at all)
    "mobMultiplier": 1.8,                 // overall difficulty multiplier
    "attributes": {                       // fine-grained control (all optional, default to global settings)
      "health": true,
      "damage": true,
      "armor": true,
      "speed": false,
      "knockback": false
    },
    "playerLuck": 0.8,                    // loot quality multiplier (1.0 = normal)
    "depthScaling": {                     // mobs harder underground (null = inherit global)
      "enabled": true,
      "startY": 64,
      "endY": -64,
      "minMultiplier": 1.0,
      "maxMultiplier": 1.5
    }
  },

  // === Structures ===
  "structureDensity": "sparse",           // dense | normal | sparse (shifts placement bands)
  "structures": {
    "wants": {                            // structures that SHOULD appear, with placement ranges
      "swamp_ruin": { "min": 0, "max": 2000 },           // absolute block distances
      "mangrove_hut": { "min": 0, "max": 800 },
      "muddy_dungeon": { "min": 2000, "max": 8192 },
      "graveyard": { "min": 500, "max": 4000 }
    },
    "shuns": {                            // structures that should NOT appear near spawn
      "village": { "minDistance": 4000 }, // must be at LEAST this far
      "mansion": { "minDistance": 0 },    // must not exist at all inside radius (0 = anywhere)
      "trial_chambers": { "minDistance": 0 },
      "ancient_city": { "minDistance": 0 }
    },
    "endgame": {                          // override endgame near-spawn protection
      "allow": false,                     // true = allow endgame structures near spawn
      "safeRadius": 1228                  // auto-calculated from borders if not set
    }
  },

  // === Portal ===
  "portal": {
    "frameBlock": "minecraft:clay",
    "igniterItem": "minecraft:amethyst_shard",
    "color": "9B8B7A",
    "lightLevel": 11,
    "scale": 8.0,                         // coordinate scaling (nether = 8, end = 1, etc.)
    "cooldown": 40,                       // ticks before re-entry
    "sounds": {
      "ignite": "block.portal.trigger",
      "enter": "block.portal.travel",
      "exit": "block.portal.travel"
    }
  },

  // === Skybox & Dimension Type ===
  "environment": {
    "skyColor": "#7BA4FF",                // null = inherit from type
    "fogColor": "#C0D8FF",
    "ambientLight": 0.0,                  // 0.0-1.0
    "fixedTime": null,                    // null = normal day/night cycle; 6000 = permanent noon; 18000 = permanent midnight
    "hasCeiling": false,
    "hasSkylight": true,
    "ultraWarm": false,                   // nether-style water evaporation
    "natural": true,                      // compass/clock work normally
    "bedWorks": true,
    "respawnAnchorWorks": false,
    "piglinSafe": false,
    "hasRaids": true,
    "minY": -64,
    "height": 384,
    "logicalHeight": 384
  },

  // === Seed Rolling ===
  "seedRoll": {
    "mood": "serene",                     // weighting archetype for scoring
    "spawnFilter": [                      // biomes that qualify as a good spawn
      "natures_spirit:marsh",
      "minecraft:swamp",
      "minecraft:mangrove_swamp",
      "terralith:orchid_swamp",
      "natures_spirit:bamboo_wetlands"
    ],
    "spawnRadius": 768,                   // max distance to nearest spawn-filter biome
    "water": "high",                      // none | normal | high | sea
    "locateCap": 9192,                    // max distance for structure/biome locates (radius + 1000)
    "terrain": null,                      // solid | islands | void (auto-detected if null)
    "heightRange": null                   // [minY, maxY] for column probes (auto-detected if null)
  }
}
```

### Base-World Files

`overworld.json` when you just want to set seed + spawn:

```json
{
  "seed": 12345678,
  "spawn": [100, 64, -200]
}
```

Everything else inherits from vanilla. The `"seed": "env"` sentinel reads `SEED` from the environment (backwards compatibility).

`overworld.json` with overrides mode (consumer wanting to just tweak difficulty):

```json
{
  "overrides": {
    "difficulty": {
      "mobMultiplier": 1.5
    }
  }
}
```

Empty `the_nether.json` in consumer overlay = vanilla nether, no custom seed/difficulty/borders.

### Candidate Storage

`candidates/the_claymarsh.json`:

```jsonc
{
  "configHash": "a1b2c3d4",              // md5 of the dimension config (minus seed/spawn)
  "candidates": {
    "-4254781042587868201": {
      "measurements": {
        "spawn_biome": "natures_spirit:marsh",
        "spawn_x": 128,
        "spawn_z": -45,
        "spawn_filter_dist": 0,
        "structure_swamp_ruin_dist": 450,
        "structure_mangrove_hut_dist": 120,
        "structure_muddy_dungeon_dist": 3200,
        "biome_minecraft:swamp_dist": 0,
        "height_r0c0": 62,
        "height_r1c1": 64,
        "water_r0c0": 1,
        "errors": 0
      },
      "scores": {
        "a1b2c3d4": {                     // keyed by config hash
          "total": 77.6,
          "namesake": 1.0,
          "variety": 0.85,
          "terrain": 0.72,
          "structures": 0.68,
          "timestamp": "2026-07-18T09:00:00Z"
        }
      }
    },
    "8234567890123456789": {
      "measurements": { ... },
      "scores": { "a1b2c3d4": { ... } }
    }
  },
  "winner": "-4254781042587868201",       // current best (or human pick)
  "rejected": {                           // spawn-filter rejects (seeds not to re-roll)
    "1234567890": "spawn filter: nearest biome at 2000 blocks",
    "9876543210": "endgame structure ancient_city at 180 blocks"
  },
  "abandoned": {                          // RCON failures (not scored, don't re-roll)
    "5555555555": "rcon-timeout"
  }
}
```

When the config changes (biomes, structures, difficulty, etc.), the `configHash` changes. Existing measurements remain valid (they're seed-specific), but scores under the old hash are stale — the roller recomputes scores for all candidates against the new config without re-rolling any seeds.

### Global Settings

`settings.json`:

```json
{
  "namespace": "adventure",
  "idleUnloadMinutes": 5,
  "defaults": {
    "frameBlock": "minecraft:crying_obsidian",
    "borders": {
      "player": 8192,
      "generation": 8192
    },
    "difficulty": {
      "hostileSpawning": true,
      "mobMultiplier": 1.0,
      "attributes": {
        "health": true,
        "damage": true,
        "armor": true,
        "speed": false,
        "knockback": false
      },
      "playerLuck": 1.0,
      "depthScaling": {
        "enabled": true,
        "startY": 64,
        "endY": -64,
        "minMultiplier": 1.0,
        "maxMultiplier": 1.5
      }
    }
  }
}
```

---

## Implementation Phases

### Phase 0: Game Data Extractors (Python scripts, no mod changes)

**Goal**: Scripts that pull every biome, structure, block, and entity from installed mod JARs into machine-readable JSON. These feed the configurator and validate dimension configs.

**Files**:
- `scripts/extract-structure-sets.py` — **already done** (377 structure sets extracted)
- `scripts/extract-biomes.py` — new: scan all JARs for `worldgen/biome/*.json`, extract biome ID, category, temperature, precipitation, mob spawns, features
- `scripts/extract-blocks.py` — new: scan all JARs for block registries, extract block ID, material, hardness, tool requirements
- `scripts/extract-entities.py` — new: scan all JARs for entity type registries, extract entity ID, category (hostile/passive/neutral), health, damage, spawn groups

**Output**: `config/custom-dimensions/extractors/{biomes,structures,blocks,entities}.json`

**Verification**: Run against Elfydd's `data/mods/`, compare counts against known mod content (e.g. Terralith adds ~100 biomes, Nature's Spirit adds ~30).

**Risk**: Low — pure Python, no mod changes, no runtime impact.

**Size**: S (1 session, ~3 files)

---

### Phase 1: Config Directory Migration (mod + scripts)

**Goal**: Replace `multiverse_config.json` with per-dimension files in `config/custom-dimensions/dimensions/`. The mod reads the directory; scripts read/write per-file. Backwards-compatible: if the old monolithic config exists and no directory does, fall back to it.

**Mod changes**:
- `MultiverseConfig.java` → `DimensionConfigLoader.java`: scan `config/custom-dimensions/dimensions/*.json`, deserialise each into a `DimensionConfig` (new unified class replacing `DimensionDefinition` + `PortalDefinition` + `WorldSeedDefinition`)
- `DimensionConfig.java`: new class with every field from the schema above. Optional fields use `@SerializedName` + null defaults. Getter methods compute derived values (e.g. `getLocateCap()` returns `borders.generation + 1000` if not explicitly set)
- `settings.json` loader: reads global defaults, merges under each dimension's config
- Consumer overlay resolution: check `overlay/config/custom-dimensions/dimensions/` first, detect `"overrides"` key for merge mode
- Backwards compat: if `config/multiverse_config.json` exists and `config/custom-dimensions/` doesn't, read the old format and log a deprecation warning
- The `"seed": "env"` sentinel for base worlds

**Script changes**:
- `scripts/seed/dimension_profiles.py` → read from per-file configs instead of monolithic JSON
- `scripts/seed/score-dimensions.py` → read/write per-file, candidate storage in `candidates/`
- `scripts/seed/seed_worker.py` → write measurements to candidate files
- Migration script: `scripts/migrate-to-v4-config.sh` — splits `multiverse_config.json` + `configurable-difficulty.json5` into per-dimension files

**Verification**: 
- Migration script produces identical runtime behaviour (diff the mod's boot log)
- `./dev seed-roll-all` reads from and writes to new locations
- Consumer overlay resolution tested with Elfydd

**Risk**: Medium — config loading is boot-critical. The backwards-compat fallback mitigates.

**Size**: L (2-3 sessions, ~12 files)

**Dependencies**: None (Phase 0 is nice-to-have but not blocking)

---

### Phase 2: Difficulty Absorption (mod changes)

**Goal**: The custom-dimensions mod reads `difficulty` from each dimension's config and applies mob attribute modifiers directly, replacing the `configurable-difficulty` mod entirely.

**Mod changes**:
- New `DifficultyManager.java`: reads difficulty config per dimension, applies attribute modifiers to mobs at spawn time
- New `MobAttributeMixin.java`: hooks `LivingEntity` spawn events, applies per-dimension health/damage/armor/speed/knockback scaling
- New `PlayerLuckMixin.java`: hooks loot table resolution, applies per-dimension luck modifier
- Existing `MobSpawnMixin.java` / `PeacefulDimensionSpawnMixin.java`: already handle `hostileSpawning: false`, keep as-is
- Depth scaling: port the configurable-difficulty depth formula into our mod
- Config: read from `DimensionConfig.difficulty` (loaded in Phase 1)

**Script changes**:
- Migration script updates: merge `configurable-difficulty.json5` multipliers into per-dimension configs
- Remove `configurable-difficulty` from `modrinth-mods.txt` (the external mod)
- Update `deploy.sh` to stop copying the old difficulty config

**Verification**:
- Boot with custom-dimensions only (no configurable-difficulty mod)
- RCON: spawn a zombie in a 2.0x dimension, verify doubled health via `data get entity @e[type=zombie,limit=1]`
- Verify peaceful dimensions still block hostile spawns
- Verify depth scaling works in overworld

**Risk**: High — attribute modification is subtle, affects gameplay directly. Must test with real player presence (Carpet bot).

**Size**: M (1-2 sessions, ~6 files)

**Dependencies**: Phase 1 (needs per-file config loading)

---

### Phase 3: Per-Dimension World Borders (mod changes)

**Goal**: The mod sets world borders per dimension at boot, from config. Remove ChunkyBorder's border-setting role (keep ChunkyBorder for pre-generation only).

**Mod changes**:
- New `WorldBorderManager.java`: on dimension creation/load, set the vanilla `WorldBorder` from `config.borders.player`
- Hook: `ServerWorldEvents.LOAD` → set border for the loaded world
- The `borders.generation` value is NOT applied as a world border — it's metadata for tools (Chunky, BlueMap render bounds)
- `deploy.sh`: read `borders.generation` from dimension configs for Chunky/BlueMap bounds instead of hardcoded `PREGEN_BORDER_RADIUS`

**Script changes**:
- `deploy.sh`: replace the RCON world-border dance with reading from dimension configs
- `deploy.sh`: ChunkyBorder setup reads `borders.generation` per dimension instead of computing from `PLAYER_BORDER_RADIUS`
- Keep ChunkyBorder mod for pre-generation; just remove its border-setting responsibility

**Verification**:
- Boot, enter a dimension, verify world border matches config
- Change border in config, restart, verify it updated
- Verify Chunky pre-generation still respects generation bounds
- Verify BlueMap render bounds still set correctly

**Risk**: Medium — world borders affect gameplay. Vanilla `WorldBorder` API is straightforward.

**Size**: S-M (1 session, ~4 files)

**Dependencies**: Phase 1

---

### Phase 4: Custom Dimension Types / Skybox (mod changes)

**Goal**: Each dimension can have custom sky colour, fog, ambient light, fixed time, ceiling, etc. via the `environment` config block. The mod creates custom `DimensionType` registry entries at runtime.

**Mod changes**:
- `DimensionManager.createDimensionOptions()`: if `environment` is set, create a custom `DimensionType` from the config instead of cloning overworld/nether/end
- New `DimensionTypeBuilder.java`: constructs `DimensionType` from config fields (sky colour, fog, ambient light, fixed time, ceiling, min_y, height, logical_height, ultraWarm, natural, bedWorks, respawnAnchorWorks, piglinSafe, hasRaids)
- Registry: each custom dimension type gets registered as `{namespace}:{dimension_name}_type`
- Fallback: if `environment` is null/absent, clone from the base type as today

**Verification**:
- Create a dimension with `"fixedTime": 18000` (permanent midnight), verify in-game
- Create a dimension with custom sky/fog colours, verify visually
- Verify dimensions without `environment` block still work (regression)

**Risk**: High — dimension type registry is fundamental to MC. Incorrect entries can crash the server or corrupt saves. Must be thoroughly tested with the full mod stack.

**Size**: M (1-2 sessions, ~4 files)

**Dependencies**: Phase 1

---

### Phase 5: Seed Roller Integration (Python + candidate storage)

**Goal**: The seed roller reads dimension configs from the per-file directory, writes candidates to `candidates/`, supports config-hash-based score invalidation, and recomputes scores without re-rolling.

**Script changes**:
- `dimension_profiles.py`: `build_profile()` reads from `DimensionConfig`-shaped dicts (structure wants/shuns now use `{ "min": N, "max": M }` ranges instead of band names)
- `score-dimensions.py`: 
  - Read candidates from `candidates/{slug}.json` instead of flat CSVs
  - Write scores keyed by config hash
  - `rescore` subcommand: recompute all scores for existing measurements without re-rolling
  - Winner selection respects human overrides in the candidate file
- `seed_worker.py`: write measurements directly to candidate JSON files (atomic: write to `.tmp`, rename)
- `roll-all.sh`: read dimension list from config directory instead of monolithic config
- Remove the flat CSV measurement format (replaced by candidate JSON)
- Backwards compat: if `.seedtest/measurements.csv` exists, import into candidate files on first run

**New features**:
- `./dev seed-rescore` — recompute scores for all candidates using current configs (no Docker, no RCON, instant)
- `./dev seed-status` — show candidate counts, winner scores, config staleness per dimension

**Verification**:
- Roll seeds, verify candidates written to correct files
- Change a dimension's structure wants, run rescore, verify scores update without re-rolling
- Import existing `.seedtest/measurements.csv` into candidate format

**Risk**: Medium — data format change. The import path must not lose existing measurements.

**Size**: L (2-3 sessions, ~8 files)

**Dependencies**: Phase 1

---

### Phase 6: Structure Wants/Shuns with Ranges (scoring model)

**Goal**: Replace the band-name system (`near_spawn`, `spread`, `near_border`) with explicit block-distance ranges. This makes configs self-documenting and removes the STRUCTS short-name indirection.

**Changes**:
- Dimension configs use `{ "min": 0, "max": 2000 }` for wants and `{ "minDistance": 4000 }` for shuns
- Structure IDs can be either short names (looked up in STRUCTS) or full `namespace:path` locate IDs
- `dimension_profiles.py`: `build_profile()` converts range objects to scoring parameters directly
- `score-dimensions.py`: `want_score()` uses the explicit min/max range instead of computing from band fractions
- Backwards compat: if a want value is a string (`"spread"`), convert to the equivalent range using the existing BANDS fractions

**Verification**:
- Score a candidate with the new range format, compare against old band format — scores should be equivalent for matching ranges
- Verify backwards compat with old string-band configs

**Risk**: Low — scoring model, not runtime. Old format still supported.

**Size**: S (1 session, ~3 files)

**Dependencies**: Phase 5

---

## Phase Ordering & Dependencies

```
Phase 0 (extractors)          ← independent, do first
    ↓
Phase 1 (config directory)    ← foundation for everything else
    ↓
    ├── Phase 2 (difficulty)  ← mod change, needs Phase 1 config
    ├── Phase 3 (borders)     ← mod change, needs Phase 1 config
    ├── Phase 4 (skybox)      ← mod change, needs Phase 1 config
    └── Phase 5 (roller)      ← scripts, needs Phase 1 config
         ↓
         Phase 6 (ranges)     ← scoring refinement, needs Phase 5
```

Phases 2, 3, 4 are independent of each other and can be done in any order after Phase 1. Phase 5 can also run in parallel with 2/3/4.

**Recommended order**: 0 → 1 → 5 → 2 → 3 → 6 → 4

Rationale: extractors first (easy win), then config migration (unblocks everything), then roller (highest user pain), then difficulty (most gameplay impact), then borders (deploy simplification), then ranges (refinement), then skybox (nice-to-have, highest risk).

---

## Migration Strategy

### For the platform repo

1. Build the new system alongside the old one (Phase 1 backwards compat)
2. Run `scripts/migrate-to-v4-config.sh` to split the monolithic config
3. Verify identical boot behaviour
4. Remove old config format support after one release cycle
5. Remove `configurable-difficulty` mod from modrinth-mods.txt (Phase 2)
6. Update `deploy.sh` border logic (Phase 3)

### For consumer repos

1. `./dev update` pulls the new bundle with per-file config support
2. Old `multiverse_config.json` still works (backwards compat in Phase 1)
3. Consumers can migrate at their own pace by running the migration script
4. New consumers get the per-file format from day one (scaffold generates it)

---

## Out of Scope (v4)

- Web-based configurator UI (future — could read/write the JSON files)
- In-game dimension creation commands (keep `customdim create` for seed rolling, but dimension authoring is config-driven)
- Biome-specific difficulty scaling (the external mod supports it; we'd port the per-biome multiplier table later)
- Dynamic dimension loading from a central server (multi-server federation)
- Custom dimension types for existing mod dimensions (Paradise Lost's type is baked into its mod)

---

## Open Questions

1. **ChunkyBorder removal timeline**: Keep ChunkyBorder for pre-generation but remove its border role in Phase 3? Or keep it entirely and just feed it config values?
   - **Recommendation**: Keep ChunkyBorder, feed it `borders.generation` from our config. Its pre-generation feature is valuable and not trivially replaceable.

2. **Config validation**: Should the mod validate dimension configs at boot and refuse to load invalid ones, or load what it can and log warnings?
   - **Recommendation**: Log warnings, load what's valid. A typo in one dimension shouldn't prevent the other 73 from loading.

3. **Hot reload**: Should config changes take effect without restart (via `/customdim reload`)?
   - **Recommendation**: Not in v4. Dimension type changes require a restart. Seed/spawn/border/difficulty changes could theoretically hot-reload, but the complexity isn't worth it for a dev/admin tool.

4. **Candidate file locking**: Multiple seed-roller workers write to the same candidate file. Use file locking or per-worker files that merge?
   - **Recommendation**: Per-worker write files (current model) that merge into the candidate file at finalise time. Avoids locking entirely.
