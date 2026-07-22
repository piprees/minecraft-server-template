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
- Target-side return links persist to `config/portal_links.json` inside the server data directory. **Current limitation:** source portal zones are process-local and must not be assumed to survive restart until route persistence is implemented; exercise a restart round-trip in the local verification loop.
- **Placing NETHER_PORTAL blocks: frames first, portal blocks last with `Block.NOTIFY_LISTENERS | Block.FORCE_STATE`.** Vanilla `NetherPortalBlock` re-validates its shape on any neighbour update and pops to air unless framed by OBSIDIAN specifically — with `NOTIFY_ALL`, a custom-framed portal self-destructs during its own placement (each block's update pops the previous one). This shipped silently for months: arrival teleports are coordinate-based so traversal "worked" while the return portal never existed (found 2026-07-13 by the Carpet-bot loop below).
- **Arrival placement comes from the target column's heightmap** (`findSurfaceY`, scaled centre, chunk force-generated first) — never from offsets against `getBottomY()`, and never trust `World.getTopY` on an unloaded chunk (it silently returns bottomY).
- **Anchor portals** (`portal.anchor`): every source portal lands at one fixed anchor; per-source target portals are suppressed. The anchor arrival portal's return targets carry an `exitMode` (`origin`/`bed`/`worldSpawn`) resolved by `EntityTickPortalMixin` — `bed` uses `getRespawnTarget(true, NO_OP)` (alive=true: locates without consuming respawn-anchor charges).
- **Single-use portals** (`portal.singleUse`): the countdown lives on the zone (`singleUseTicksLeft`, -1 = unarmed), ticks in `ServerWorldMixin`, and persists in the `portal_links.json` zone record — written at countdown start and shutdown, so restarts resume rather than reset. Decay-map resolution is pure logic in `PortalDecay` (unit-tested).
- **Exit portals** (`exitPortal`): `ExitPortalManager` builds/rebuilds a frame near dimension spawn, checked every 100 ticks from the world tick with a chunk-loaded guard (never sync-load a chunk just to inspect it). `PortalSafetyValidator` WARNs at boot (fingerprint tone: never crash, never auto-fix) when singleUse/anchor dims lack one.
- **Portal config is NOT creation-time-only** — unlike worldgen it re-reads every boot, so anchor/singleUse/exitPortal changes apply to existing dimensions without a wipe.

**Feature ideas** live in `~/Projects/minecraft-server-template/mods/.ideas` as individual markdown files — not in this document.

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

Dimensions are now created automatically at boot from `config/custom-dimensions/` (one file per dimension; the monolithic `config/multiverse_config.json` remains a deprecated fallback) — there are no `/dimension create` or `/portal link` commands. Verify via RCON that dimensions loaded correctly:

```bash
# Namespace comes from custom-dimensions/settings.json (default "adventure";
# legacy fallback: multiverse_config.json "namespace" field)
NS=$(python3 -c "
import json, os
p = 'data/config/custom-dimensions/settings.json'
if os.path.exists(p):
    print(json.load(open(p)).get('namespace', 'adventure'))
else:
    print(json.load(open('data/config/multiverse_config.json')).get('namespace', 'adventure'))
")
docker exec -i mc rcon-cli "execute in ${NS}:the_blossom_gardens run seed"   # proves dimension was created from config
docker exec -i mc rcon-cli "execute in ${NS}:the_canvas run seed"
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10
```

To add/change dimensions: edit (or add) `config/custom-dimensions/dimensions/<slug>.json`, commit, deploy. The mod reads the directory at boot, creates missing worlds, and reconciles orphans (any managed-namespace world not in the config is unloaded). Consumers override via `overlay/config/custom-dimensions/dimensions/` — full replace, `"overrides"` deep-merge, or empty `{}` to skip a dimension entirely.

### 3b. Player-dependent paths: drive a Carpet fake player (local only)

Paths that only trigger on real player presence (portal traversal, zone entry, presence timers) CAN be tested headlessly — install fabric-carpet temporarily and puppet a bot. This turned "needs a human in-game" into an automated loop and caught a bug code review missed (the portal self-destruction above: the bot arrived fine but could never return).

```bash
# Install (LOCAL ONLY — never ship): resolve the 1.21.1 build via the Modrinth
# API, download to data/mods/TEMP-carpet-test.jar, docker stop mc && docker start mc.
docker exec -i mc rcon-cli 'carpet commandPlayer true'
docker exec -i mc rcon-cli 'player Bot spawn'          # async — wait ~3s, verify with "list"

# --- Build a portal frame and ignite it ---
# Example: cherry_planks frame (the_blossom_gardens portal, igniter: cherry_sapling)
X=2000; Y=80; Z=2000
docker exec -i mc rcon-cli "tp Bot $((X+1)).5 $((Y+1)) $Z.5"
# Build frame, give igniter, then:
docker exec -i mc rcon-cli 'item replace entity Bot hotbar.0 with minecraft:cherry_sapling 8'
docker exec -i mc rcon-cli 'player Bot hotbar 1'
docker exec -i mc rcon-cli 'player Bot look west'      # look at frame wall from INSIDE
docker exec -i mc rcon-cli 'player Bot use once'        # right-click to ignite

# --- Assert traversal ---
sleep 10                                                 # dimension creation takes several seconds on first visit
docker exec -i mc rcon-cli 'data get entity Bot Dimension'   # expect the target dimension
docker exec -i mc rcon-cli 'data get entity Bot Pos'

# --- Return trip ---
docker exec -i mc rcon-cli 'tp Bot <x+5> <y> <z>'      # step out of portal zone
sleep 5                                                  # wait for cooldown to clear
docker exec -i mc rcon-cli 'tp Bot <portal_x> <portal_y> <portal_z>'  # step back in
sleep 8
docker exec -i mc rcon-cli 'data get entity Bot Dimension'   # expect overworld

# --- Portal breaking ---
docker exec -i mc rcon-cli 'setblock <frame_x> <frame_y> <frame_z> air'  # break one frame block
sleep 3
# Interior portal blocks should now be air (zone validation cleared them)
```

Gotchas learned the hard way:

- **Ignition positioning**: the bot must be INSIDE the frame looking at the frame wall — from outside, cherry_sapling plants itself on the adjacent block instead of triggering ignition. The `PortalIgnitionMixin` hooks `ItemStack.useOnBlock` at HEAD, but the clicked position must have an air block adjacent to the frame for flood-fill to find the portal shape.
- `player Bot spawn at ...` may ignore the position (tp after instead).
- Vanilla resets portal cooldown every tick while an entity stands IN a portal, so return trips need step-out → wait cooldown (check `data get entity Bot PortalCooldown` = 0) → step-in.
- Assert with `data get entity` and log greps (`docker exec mc cat /data/logs/latest.log`), never RCON chat echoes.
- Autopause kicks the bot: use `docker exec mc sh -c 'touch /data/.skip-pause'` before testing.
- Always clean up: `player Bot kill`, remove the carpet jar (`docker exec mc rm -f /data/mods/TEMP-carpet-test.jar`), restart mc.

### 4. Soak time-based paths

Anything on a timer (idle unload, cooldowns, periodic saves) must be soaked through its **real** window, not assumed from reading the code — the tick-loop crash class only shows up when the timer actually fires:

```bash
# Add a test dimension to multiverse_config.json, restart mc, then:
# wait out the full timer window (e.g. idle unload = 5 min + the check cadence), then:
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
docker exec mc cat /data/logs/latest.log | grep -iE 'Unloading idle|ConcurrentModification'  # expect the feature line, no CME
```

**Tick-loop threading rule:** never mutate the server's worlds map (or any collection vanilla iterates per tick) from a `ServerWorld.tick` / world-tick mixin — that's a `ConcurrentModificationException` crash when the timer fires. Defer mutations to `ServerTickEvents.END_SERVER_TICK` (see `MultiverseServer` and the pending-load queue in `DimensionManager` for the pattern).

**Dynamic world lifecycle rule:** any code path that adds a `ServerWorld` to the server's worlds map MUST fire `ServerWorldEvents.LOAD`, and any path that removes/closes one MUST fire `ServerWorldEvents.UNLOAD` before `close()`. Distant Horizons, BlueMap, and c2me build their per-level state exclusively from these Fabric events — skipping LOAD made DH NPE on the first portal teleport into a runtime dimension and locked the player out of production (2026-07-12). Also: never call `getOrCreateDimension` synchronously from command context — world creation there deadlocks the main thread; queue via `requestWorldLoad` (END_SERVER_TICK) instead.

**c2me DFC trap (per-dimension seeds):** `ServerWorldSeedMixin` overrides `ServerWorld.getSeed()` per dimension — that value feeds `NoiseConfig` (terrain, biome layout, aquifers) and structure placement. c2me's density-function compiler (`c2me-opts-dfc`) caches compiled+instantiated density functions across `NoiseConfig` creations and IGNORES the seed, so with it enabled every custom dimension silently clones the main world. `deploy.sh` (step 8c) and `dev-up.sh` force `useDensityFunctionCompiler = false` in `c2me.toml`; the rest of c2me stays enabled. When testing seeds, use the locate oracle: two dims with different seeds must give different `execute in adventure:<dim> run locate biome/structure` results; same seed must match. Two sharp edges: (1) c2me STRIPS the unknown key when it rewrites its config at boot — the key is read first, so enforcement holds, but only because both boot paths re-patch it every time; (2) a bare `docker restart mc` therefore boots WITHOUT the patch — after a manual restart in the local loop, re-run `./dev up` (or re-add the key) before trusting seed results.

### 5. Ship and verify at each layer

Once the local loop passes: commit → `gh workflow run release.yml -f version=vX.Y.Z` → consumer `./dev sync` (or `./ops update` for production only). Then verify **outcomes, not script output**:

- Script counters count commands _sent_, not commands that _succeeded_ — a brigadier parse error still increments "Created: 74". Check the persisted result instead (e.g. count entries in `data/config/multiverse_config.json`) and spot-check entities via RCON.
- Snapshot production state, never stream: `docker logs mc --tail 50`, `docker inspect mc --format '... RestartCount ...'`. A RestartCount above 0 means a crash you haven't explained yet.
- Under deploy load (world creation, Chunky, mod sync) RCON responses can time out and come back empty — treat an empty response as a failure to re-check, never as success.
- If production's persisted mod state predates a format/namespace change, stop mc, delete the state file, and re-run `deploy.sh` — deploys recreate everything idempotently.

**Releasing:** `release.yml` builds the mod with Gradle, stages the remapped jar as `dist/local-mods/<mod>.jar`, and `build-stack-bundle.sh` packs it into the stack bundle as `stack/local-mods/`. On production, `deploy.sh` copies `stack/local-mods/*.jar` into `data/mods/` **before mc starts** (step 8b — ordering is load-bearing: a jar copied after the health wait can never fix a boot that the old jar breaks); locally, `dev-up.sh` does the same on every `./dev up`. Nothing is published to Modrinth and no jars are committed to git.

## Current mods

| Mod | Status | Purpose |
| --- | --- | --- |
| `custom-dimensions` | In development | Boot-time dimension creation from repo config, custom portal frames with configurable igniters, coordinate scaling, coloured particles, bidirectional travel, per-dimension noise settings (`noiseSettings` → jar-baked `adventure:wide`/`adventure:compressed`, generated by `scripts/gen-terrain-presets.py` — regenerate on Tectonic pin bumps) and theme-aware structure density (`structureDensity` + automatic peaceful overlay; themes from `scripts/gen-structure-presets.py`) |

### Seed rolling pipeline

The seed-rolling system at `scripts/seed/` evaluates dimension seeds without running the game. See `scripts/seed/README.md` for the full architecture. Key integration points with the custom-dimensions mod:

- `biome_params.json` is dumped via the mod's `/customdim dump-biome-params` command (captures TerraBlender + all mod biomes across 4 families)
- Dimension configs at `config/custom-dimensions/dimensions/` drive what gets rolled — the roller reads `type`, `biomes`, `seedRoll`, `structureDensity`, and `difficulty` from each file
- Per-dimension `seedRoll` blocks control spawn filters, wants/shuns, mood, and terrain preferences
- Winners are written back to `config/custom-dimensions/candidates/` and optionally into the dimension config's `seed` field

## Architecture (custom-dimensions)

```
MultiverseServer (entrypoint)
├── WorldLoaderMixin → hooks server start/stop
│   ├── MultiverseConfig.load() → reads repo-owned JSON config (read-only)
│   ├── PortalHelper.loadPortalLinks() → JSON portal state
│   ├── DimensionManager.registerDimensions() → unfreezes registry, adds entries
│   └── DimensionManager.bootCreateDimensions() → queues all config dims for creation
├── END_SERVER_TICK → drains pending world loads/unloads, reconciles orphans
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
├── MobAttributeMixin → per-dimension difficulty (MobEntity.initialize TAIL)
│   └── DifficultyManager: mobMultiplier x depth factor as persistent
│       attribute modifiers (hostile mobs only; 0x = peaceful no-op);
│       player luck via JOIN/world-change events
├── WorldBorderManager → borders.player as the vanilla border per world
│   (SERVER_STARTED, overworld first — vanilla's border syncer trap)
├── DimensionTypeBuilder → "environment" block registers {ns}:{slug}_type
│   (invalid heights fall back to the base type, never a crash)
├── ServerChunkLoadingManagerMixin → per-dimension structure density
│   └── rebuilds the world's StructurePlacementCalculator with rescaled
│       UNREGISTERED placement copies (DimensionStructures) — the global
│       registry is never mutated; custom placement types pass through
│       unchanged (only whole-set drops apply to them)
├── MinecraftServerAccessor → server internals access
├── StructurePlacementAccessor / StructurePlacementCalculatorInvoker
│   └── placement field access + the private calculator ctor (the public
│       Stream create() would zero the concentric-ring seed)
└── SimpleRegistryAccessor → registry unfreezing
```
