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

**Testing:** There is no automated test framework for Fabric mods in this repo. Test locally:
1. Build the JAR: `./gradlew build`
2. Copy to the server's overlay: `cp build/libs/<mod>.jar ../../overlay/mods/`
3. Boot the local server: `cd ../.. && ./dev up`
4. Check logs for mixin errors: `docker logs mc 2>&1 | grep -i 'mixin\|error\|exception'`
5. Test in-game: create a dimension, link a portal, step through it, return

**Releasing:** Built JARs are committed to `overlay/mods/` in consumer repos (not published to Modrinth). The server loads them alongside Modrinth-managed mods via the itzg image's `/data/mods/` directory.

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
