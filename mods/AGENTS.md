# In-House Server Mods

> These are Fabric mods built and maintained as part of this platform. Each subdirectory is a standalone Gradle project that produces a JAR for the server's mod list.

## Environment

Java 21 (temurin) is required — pinned via `mise.toml` in this directory. Fabric Loom handles Yarn mappings and mixin annotation processing.

```bash
cd mods/<mod-name>
mise install                         # ensures Java 21
gradle wrapper --gradle-version 8.13 # one-time, if no wrapper yet
./gradlew build                      # output: build/libs/<mod>-<version>.jar
```

## Conventions

**Target:** Minecraft 1.21.1, Fabric Loader 0.16+, Java 21. Match the server's MC version exactly — worldgen mods must be present from chunk zero.

**Mod ID:** lowercase, no hyphens. Use the directory name as the mod ID.

**Mappings:** Yarn (human-readable) in source, intermediary in compiled JARs. Fabric Loom handles this — never write intermediary names (`class_XXXX`, `method_XXXX`) in source code.

**Mixins:**
- Every mixin class must be listed in `<modid>.mixins.json`. Unlisted mixins silently don't apply and cause ClassCastExceptions when accessor interfaces are used.
- `@Accessor` and `@Invoker` interfaces go in the same `mixins` array as `@Mixin` classes.
- Always verify your mixin targets exist on the class you're targeting in 1.21.1 — methods move between classes across MC versions. Check Yarn mappings or use `yarn-mappings-viewer`.
- Set `"defaultRequire": 1` in the mixin config so missing targets crash at startup (fail-fast) rather than silently skipping.
- Test with the full server mod stack, not just vanilla — other mods' mixins can transform your target classes.

**Portal system (custom-dimensions):**
- Portal frames are built from configurable blocks and ignited with configurable items.
- The flood-fill algorithm scans up to 128 blocks in a plane (X or Z axis) bounded by frame blocks.
- Portal zones are validated every tick — if the frame breaks, the portal clears.
- Coordinate scaling applies to the portal center position when creating the target-side portal.
- Player origins are tracked by UUID for return trips.
- All portal state persists to `config/portal_links.json` inside the server data directory.

**Feature ideas** live in `mods/.ideas/` as individual markdown files — not in this document.

## Verification loop

There is no automated test framework for Fabric mods in this repo — verification happens against the real modded server, locally first. A release→deploy cycle costs 10–15 minutes and restarts production; the local loop costs ~1 minute. **Never cut a release for a change you haven't run through this loop.**

### 1. Verify the artefact, not the build

Gradle reports `BUILD SUCCESSFUL` even when `remapJar` emits an empty or unremapped jar (this shipped a production crash loop once). After every build, inspect the jar you intend to ship:

```bash
./gradlew build
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'   # expect your real class count, not 0
unzip -l build/libs/<mod>-<version>.jar | grep refmap          # <mod>-refmap.json MUST be present
unzip -p build/libs/<mod>-<version>.jar path/to/SomeMixin.class | strings | grep -m3 'class_'
# intermediary names (class_XXXX) = remapped correctly; Yarn names only = dev jar, will crash
```

**Dev jar trap:** `build/devlibs/<mod>-<version>-dev.jar` uses Yarn named mappings and has no refmap — it only works inside a Loom dev environment. On a real server every mixin fails (`could not find any targets ... No refMap loaded`) and the server crash-loops. Only ever ship `build/libs/<mod>-<version>.jar`. CI enforces the class-count and refmap checks (`mod-build.yml`, `release.yml`).

### 2. Fast local loop

Install straight into the local consumer's `data/mods/` and restart only the mc container — no release, no bundle, no full stack cycle:

```bash
cp build/libs/<mod>-<version>.jar <consumer>/data/mods/<mod>.jar
docker restart mc && sleep 45
docker inspect mc --format '{{.State.Health.Status}}'            # must be healthy
docker logs mc 2>&1 | grep -iE 'mixin apply|<modid>|error' | tail -20
```

If the persisted state format changed (config schema, namespace, IDs), delete the mod's state file(s) under `data/config/` before restarting — stale state from a previous build masks bugs and creates ghosts.

### 3. Exercise via RCON, headless

Design every feature to be drivable by command so it can be verified without a game client, both here and later by `deploy.sh`:

```bash
docker exec -i mc rcon-cli 'dimension create test_x multi_biome 424242 "minecraft:plains,terralith:shield" true'
docker exec -i mc rcon-cli 'portal link test_x minecraft:quartz_block minecraft:amethyst_shard adventure:test_x 55FF55 11 4'
docker exec -i mc rcon-cli 'execute in adventure:test_x run seed'   # proves the dimension resolves to vanilla commands
docker exec -i mc rcon-cli 'dimension delete test_x'                # always clean up test entities
```

Test the awkward inputs deliberately: quoted comma lists, identifiers with colons, seeds above `Long.MAX_VALUE`, empty optional arguments. Brigadier argument types are stricter than they look, and every one of those has broken a release.

### 4. Soak time-based paths

Anything on a timer (idle unload, cooldowns, periodic saves) must be soaked through its **real** window, not assumed from reading the code — the tick-loop crash class only shows up when the timer actually fires:

```bash
docker exec -i mc rcon-cli 'dimension create soak_test overworld 987654'
# wait out the full timer window (e.g. idle unload = 5 min + the check cadence), then:
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
grep -iE 'Unloading idle|ConcurrentModification' <consumer>/data/logs/latest.log          # expect the feature line, no CME
```

**Tick-loop threading rule:** never mutate the server's worlds map (or any collection vanilla iterates per tick) from a `ServerWorld.tick` / world-tick mixin — that's a `ConcurrentModificationException` crash when the timer fires. Defer mutations to `ServerTickEvents.END_SERVER_TICK` (see `MultiverseServer` and the pending-load queue in `DimensionManager` for the pattern).

### 5. Ship and verify at each layer

Once the local loop passes: commit → `gh workflow run release.yml -f version=vX.Y.Z` → consumer `./dev sync` (or `./ops update` for production only). Then verify **outcomes, not script output**:

- Script counters count commands *sent*, not commands that *succeeded* — a brigadier parse error still increments "Created: 74". Check the persisted result instead (e.g. count entries in `data/config/multiverse_config.json`) and spot-check entities via RCON.
- Snapshot production state, never stream: `docker logs mc --tail 50`, `docker inspect mc --format '... RestartCount ...'`. A RestartCount above 0 means a crash you haven't explained yet.
- Under deploy load (world creation, Chunky, mod sync) RCON responses can time out and come back empty — treat an empty response as a failure to re-check, never as success.
- If production's persisted mod state predates a format/namespace change, stop mc, delete the state file, and re-run `deploy.sh` — deploys recreate everything idempotently.

**Releasing:** `release.yml` builds the mod with Gradle, stages the remapped jar as `dist/local-mods/<mod>.jar`, and `build-stack-bundle.sh` packs it into the stack bundle as `stack/local-mods/`. On production, `deploy.sh` copies `stack/local-mods/*.jar` into `data/mods/` **before mc starts** (step 8b — ordering is load-bearing: a jar copied after the health wait can never fix a boot that the old jar breaks); locally, `dev-up.sh` does the same on every `./dev up`. Nothing is published to Modrinth and no jars are committed to git.

## Current mods

| Mod | Status | Purpose |
| --- | --- | --- |
| `custom-dimensions` | In development | Runtime dimension creation, custom portal frames with configurable igniters, coordinate scaling, coloured particles, bidirectional travel |

## Architecture (custom-dimensions)

```
MultiverseServer (entrypoint)
├── WorldLoaderMixin → hooks server start/stop
│   ├── MultiverseConfig.load() → JSON config
│   ├── PortalHelper.loadPortalLinks() → JSON portal state
│   ├── DimensionManager.registerDimensions() → unfreezes registry, adds entries
│   └── registers /dimension and /portal commands
├── ServerWorldMixin → per-tick logic
│   ├── validates portal zones (removes broken ones)
│   ├── teleports players stepping into portals
│   └── spawns coloured particles on all portals
├── PortalIgnitionMixin → portal creation
│   ├── detects item use matching portal config
│   ├── flood-fills to find valid frame
│   └── registers portal zone
├── EntityTickPortalMixin → vanilla portal override
│   └── redirects teleportation for custom portals
├── MinecraftServerAccessor → server internals access
└── SimpleRegistryAccessor → registry unfreezing
```
