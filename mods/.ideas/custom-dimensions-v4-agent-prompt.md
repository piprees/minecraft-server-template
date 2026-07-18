# Agent Prompt: Custom Dimensions v4 — Phase 1 (Config Directory Migration)

> **Target agent**: Fable/Mythos-class (1M+ context). Single session, no subagents.
> **Tools available**: Claude Code CLI with Bash, Read, Write, Edit, Serena MCP (semantic code intelligence), Context7 MCP (library docs), VS Code IDE integration (getDiagnostics, executeCode).
> **Estimated scope**: ~12 files changed, ~2000 lines. One session.

---

## Prompt

You are implementing Phase 1 of the Custom Dimensions v4 plan for a Minecraft Fabric mod (1.21.1, Java 21, Gradle 8.13). The full plan is at `mods/.ideas/custom-dimensions-v4-plan.md` — read it first, along with `README.md`, `AGENTS.md`, and `mods/AGENTS.md`.

### What you're building

Replace the monolithic `config/multiverse_config.json` (2,500 lines, 74 dimensions + 74 portals + 4 worlds) with a **per-dimension file directory** at `config/custom-dimensions/dimensions/`. Each dimension gets one self-contained JSON file. The mod reads the directory at boot; the seed-rolling Python scripts read/write per-file.

### The spec

Read `mods/.ideas/custom-dimensions-v4-plan.md` for the complete schema, directory structure, consumer overlay resolution, and backwards compatibility rules. The key design decisions are already made — implement them exactly:

1. **Directory**: `config/custom-dimensions/dimensions/{slug}.json` — one file per dimension
2. **Settings**: `config/custom-dimensions/settings.json` — global defaults (namespace, idle unload, default borders/difficulty)
3. **Consumer overlay**: `overlay/config/custom-dimensions/dimensions/` — full replace, `"overrides"` merge, or empty `{}` to skip
4. **Namespace**: `BRAND_SLUG` env var → consumer namespace; falls back to `"adventure"` from settings.json
5. **Base worlds**: filenames `overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json` override vanilla worlds; `"seed": "env"` reads from `SEED` env var
6. **Backwards compat**: if `config/multiverse_config.json` exists and `config/custom-dimensions/` doesn't, read the old format with a deprecation warning

### Step-by-step task list

**Before writing any code**, use Serena to activate the project (`activate_project`) and read the instructions manual (`initial_instructions`). Then use `get_symbols_overview` and `find_symbol` to understand the existing code before modifying it.

#### 1. Understand the existing config system (read-only)

- Read `mods/custom-dimensions/src/main/java/com/customdimensions/config/MultiverseConfig.java` — understand how it loads the monolithic JSON
- Read `DimensionDefinition.java`, `PortalDefinition.java`, `WorldSeedDefinition.java` — understand the current data model
- Read `MultiverseServer.java` — understand the boot sequence (config load → register dims → set spawn)
- Read `DimensionManager.java` — understand how `createDimensionOptions()` uses the config
- Read `scripts/seed/dimension_profiles.py` — understand how the Python side reads config
- Read `scripts/seed/score-dimensions.py` — understand how finalise writes winners back

Do NOT proceed until you understand the full data flow from JSON → Java classes → dimension creation, and from JSON → Python profiles → seed scoring → JSON writeback.

#### 2. Create the unified DimensionConfig class

Create `mods/custom-dimensions/src/main/java/com/customdimensions/config/DimensionConfig.java`:

- One class that replaces `DimensionDefinition` + `PortalDefinition` + `WorldSeedDefinition`
- Every field from the v4 schema (identity, world gen, borders, difficulty, structures, portal, environment, seedRoll)
- All fields nullable with sensible defaults via getter methods
- Nested inner classes for `Borders`, `Difficulty`, `DepthScaling`, `Structures`, `Portal`, `PortalSounds`, `Environment`, `SeedRoll`, `StructureWant`, `StructureShun`, `EndgameConfig`
- `getDimensionId(namespace)` computes `{namespace}:{slug}` from the filename
- `getLocateCap()` returns `borders.generation + 1000` if not explicitly set in seedRoll
- `isBaseWorld()` returns true for overworld/the_nether/the_end/paradise_lost filenames
- `getEffectiveSeed(envSeed)` handles the `"env"` sentinel
- Use `@SerializedName` annotations matching the JSON keys exactly
- Use Gson for deserialisation (already a dependency)

#### 3. Create the directory-based config loader

Create `mods/custom-dimensions/src/main/java/com/customdimensions/config/DimensionConfigLoader.java`:

- `loadAll(Path configDir, Path overlayDir, String namespace)` → `Map<String, DimensionConfig>`
- Scan `configDir/dimensions/*.json` for platform defaults
- Scan `overlayDir/dimensions/*.json` for consumer overrides
- Implement the resolution order:
  - Consumer file with `"overrides"` key → deep-merge over platform default
  - Consumer file without `"overrides"` → replace platform default entirely
  - Consumer file is empty `{}` → return a "skip" marker (null or sentinel)
  - No consumer file → use platform default
- Load `configDir/settings.json` for global defaults; merge under each dimension
- Consumer-added dimensions (files in overlay but not in platform) get namespace from `BRAND_SLUG` env var
- Handle `"seed": "env"` by reading the `SEED` environment variable
- Log each dimension loaded, overridden, skipped, or consumer-added
- **Backwards compat**: static method `loadLegacy(Path monolithicConfig)` reads the old `multiverse_config.json` format and converts to `Map<String, DimensionConfig>`

#### 4. Update MultiverseConfig to use the new loader

Modify `MultiverseConfig.java`:

- `load()` method: try new directory first, fall back to old monolithic format with deprecation log
- Replace the lists of `DimensionDefinition`, `PortalDefinition`, `WorldSeedDefinition` with `Map<String, DimensionConfig>`
- Keep the public API surface compatible: `getDimension(name)`, `getDimensions()`, `getPortal(id)`, `getPortals()`, `getWorld(name)` — these now delegate to DimensionConfig lookups
- `getWorldSeedOverride(dimensionId)` reads from the DimensionConfig's seed field
- Preserve the namespace, frameOverworld/Nether/End, idleUnloadMinutes fields from settings.json

#### 5. Update DimensionManager to consume DimensionConfig

Modify `DimensionManager.java`:

- `createDimensionOptions(DimensionConfig config)` replaces `createDimensionOptions(DimensionDefinition def)`
- All existing functionality preserved: type switching, noise settings, biome mixing, seed override, structure density
- Portal config read from `config.portal` instead of a separate PortalDefinition lookup
- World spawn set from `config.spawn` instead of WorldSeedDefinition

#### 6. Write the migration script

Create `scripts/migrate-to-v4-config.sh`:

- Reads `config/multiverse_config.json` and `config/configurable-difficulty/configurable-difficulty.json5`
- Outputs `config/custom-dimensions/settings.json` and `config/custom-dimensions/dimensions/{name}.json` for every dimension, world, and portal
- Merges difficulty multipliers into each dimension's `difficulty.mobMultiplier`
- Preserves the `seedRoll` block as-is in each dimension file
- Merges portal config into the dimension file
- Uses Python (not jq) for the JSON manipulation — the difficulty config has `//` comments that need stripping

#### 7. Update the seed-rolling Python scripts

Modify `scripts/seed/dimension_profiles.py`:

- `build_profile()` accepts a DimensionConfig-shaped dict (the new per-file format)
- New `load_dimension_configs(config_dir)` function: scans the directory, returns `{name: config_dict}`
- Backwards compat: if called with the old monolithic config, convert internally

Modify `scripts/seed/score-dimensions.py`:

- `main()`: read dimension list from `config/custom-dimensions/dimensions/` instead of monolithic config
- Backwards compat: `--config` flag still accepts a monolithic JSON path
- Winner writeback: write seed + spawn to the individual dimension file, not the monolith

Modify `scripts/seed/roll-all.sh`:

- `CONFIG` variable points to `config/custom-dimensions/` directory
- Pass the directory (not a single file) to score-dimensions.py

#### 8. Generate the initial per-dimension config files

Run the migration script against the current `config/multiverse_config.json` to produce the initial set of 78 dimension files (74 custom + 4 worlds).

Verify: the mod boots identically with the new config directory as it did with the old monolithic file.

#### 9. Update the seed container Dockerfile

Modify `docker/defaults-seed/Dockerfile`:

- `COPY` the `config/custom-dimensions/` directory instead of (or in addition to) the single `multiverse_config.json`
- The seed container must place the directory into the shared volume so the mc container reads it at boot

#### 10. Update deploy.sh

Modify `scripts/deploy.sh`:

- Config sync (step 8) copies `config/custom-dimensions/` directory instead of the single file
- Consumer overlay merge reads from `overlay/config/custom-dimensions/` if it exists

#### 11. Tests

- Unit tests for `DimensionConfig` deserialisation (null fields, defaults, seed="env")
- Unit tests for `DimensionConfigLoader` overlay resolution (replace, merge, skip, consumer-added)
- Unit tests for legacy config conversion
- Python: test `load_dimension_configs()` with a temp directory of dimension files
- Verify the migration script output matches the current runtime behaviour

### Constraints

- **Java 21, Fabric 1.21.1** — match the existing mod's target exactly
- **macOS Bash 3.2** — no `declare -A`, no `${var,,}`, no `grep -P`
- **Gson** — already the JSON library in the mod; don't add Jackson or Moshi
- **No breaking changes** — the old monolithic config must still work until explicitly removed
- **Run `./scripts/test-scripts.sh --quick` before considering the work done** — ShellCheck, py_compile, compose validation, seed-roll regression tests must all pass
- **Follow the verification loop in `mods/AGENTS.md`**: build → inspect the JAR → install into consumer `data/mods/` → restart mc → exercise via RCON → verify with docker logs
- **Never use `grep -P`** on macOS
- **Read AGENTS.md §Conventions** for commit message format, scripting conventions, and quality gates

### What success looks like

1. `config/custom-dimensions/dimensions/` contains 78 JSON files (one per dimension/world)
2. `config/custom-dimensions/settings.json` contains global defaults
3. The mod boots and creates all 74 dimensions identically from the per-file config
4. The old `multiverse_config.json` still works as a fallback
5. `./dev seed-roll-all` reads from and writes winners to individual dimension files
6. The migration script is idempotent and produces files that boot identically
7. All existing tests pass + new unit tests for the config loader
8. `./scripts/test-scripts.sh --quick` passes

### What to do if you get stuck

- Use `find_symbol` and `find_referencing_symbols` via Serena to trace how a config field flows through the codebase
- Use Context7 (`npx ctx7@latest docs`) to check Fabric API, Gson, or Minecraft class documentation
- Use `get_diagnostics_for_file` via Serena to check for compile errors after edits
- If a mixin target doesn't exist in 1.21.1, check Yarn mappings via the Fabric docs
- If the mod won't boot, check `docker logs mc --tail 80` for the actual error — mixin failures appear there, not in `data/logs/latest.log`

### Do NOT

- Create subagents — you have full context, use it
- Skip the backwards compatibility fallback — consumers depend on the old format
- Change the dimension creation logic (type switching, biome mixing, seed override) — only change how config is loaded
- Touch portal traversal, particle rendering, or zone validation — those are out of scope
- Add new Minecraft dependencies or change the Gradle config
- Modify `multiverse_config.json` — leave it as the legacy format reference
