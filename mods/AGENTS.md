# In-House Server Mods

Fabric mods built and maintained as part of this platform. Each subdirectory is a standalone Gradle project producing a JAR for the server's mod list. This file is what you must know **before** touching one. Subsystem detail lives in `docs/mod-internals/`: [portals](../docs/mod-internals/portals.md), [worldgen & structures](../docs/mod-internals/worldgen-structures.md), [noise placement design intent](../docs/design/noise-placement.md), [diagnostics & seed rolling](../docs/mod-internals/diagnostics.md), [architecture](../docs/mod-internals/architecture.md). Player-facing config schema: [`custom-dimensions/README.md`](custom-dimensions/README.md).

## Environment

Java 21 (temurin), pinned by `mise.toml` in this directory. Fabric Loom handles Yarn mappings and mixin annotation processing.

```bash
cd mods/<mod-name>
mise install                          # ensures Java 21
gradle wrapper --gradle-version 8.13  # one-time, if no wrapper yet
mise exec -- ./gradlew build          # output: build/libs/<mod>-<version>.jar
```

**Always `mise exec --`** — it is what applies the toolchain pin ([P4](../TROUBLESHOOTING.md#p4)).

## Conventions

- **Target:** Minecraft 1.21.1, Fabric Loader 0.16+, Java 21. Match the server's MC version exactly — worldgen mods must be present from chunk zero.
- **Mod ID:** lowercase, no hyphens; use the directory name.
- **Mappings:** Yarn in source, intermediary in compiled jars. Never write intermediary names (`class_XXXX`, `method_XXXX`) in source.

**Mixins:**

- Every mixin class must be listed in `<modid>.mixins.json`. An unlisted mixin silently doesn't apply and throws `ClassCastException` when its accessor interface is used.
- `@Accessor` and `@Invoker` interfaces go in the same `mixins` array as `@Mixin` classes.
- Verify targets exist on the class you are targeting in 1.21.1 — methods move between classes across MC versions. Check Yarn mappings or `yarn-mappings-viewer`.
- Set `"defaultRequire": 1` in the mixin config so missing targets crash at startup rather than skipping silently.
- Test with the full server mod stack, not just vanilla — other mods' mixins transform your target classes.

## Threading and lifecycle invariants

- **Never mutate the server's worlds map** — or any collection vanilla iterates per tick — from a `ServerWorld.tick` / world-tick mixin. That is a `ConcurrentModificationException` crash when the timer fires. Defer mutations to `ServerTickEvents.END_SERVER_TICK` (`MultiverseServer`, and the pending-load queue in `DimensionManager`).
- **Never call `getOrCreateDimension` synchronously from command context** — world creation there deadlocks the main thread. Queue via `requestWorldLoad` (END_SERVER_TICK).
- **Any path that adds a `ServerWorld` to the worlds map MUST fire `ServerWorldEvents.LOAD`; any path that removes or closes one MUST fire `ServerWorldEvents.UNLOAD` before `close()`.** Distant Horizons and c2me build their per-level state exclusively from these Fabric events — skipping LOAD NPEs DH on the first portal teleport into a runtime dimension and can lock a player out of production.
- **Never sync-load or force-generate a chunk** from a tick path, the immersive projector, the audio pass, entity pass-through, or an aura pass. Read through `getChunkManager().getWorldChunk(cx, cz, false)`; null means skip.
- **Never run a bare synchronous `/locate` into an ungenerated custom dimension** — it blocks the main thread long enough to wedge RCON while `docker ps` stays healthy (recover with `docker stop -t 90 mc && docker start mc`). Use `/customdim structure-census`.
- **Log counts, not just events.** An "activated" line alone looks healthy in every broken state.

## Seed roller invariants

These are load-bearing product behaviour, not optimisations. Both are covered
by tests — if a change makes one fail, the change is wrong, not the test.

- **A roll always appends.** Asking for N seeds means N MORE for every targeted
  dimension, whatever its board already holds. Never skip a dimension because
  it is already full; the point of a roll is to find candidates that beat what
  is there.
- **The best banked seed becomes CURRENT after a roll**, written to the overlay
  so it survives a restart. `POST /pick` ("Use this seed") is a manual override
  and is a separate path.
- **Tier 1 spends its whole seed budget**, stopping only on cancel or a yield to
  a dimension opened in the viewer. Tier 2 measures every shortlisted seed.

## Portal system

Full internals, plus the aura and immersive rules: [docs/mod-internals/portals.md](../docs/mod-internals/portals.md).

- **Portal config is NOT creation-time-only.** Unlike worldgen it re-reads every boot, so frame, shape, anchor, singleUse, exitPortal, aura and immersive changes apply to existing dimensions without a world wipe.
- **Anything serialised into `portal_links.json` must stay parseable by every jar that might read it back** — deploys roll back. `PortalDefinition.frameBlock` is always a plain id, never a `#tag`; accept forms ride in `frameAccepts`.
- **Place frames first, portal blocks last, with `Block.NOTIFY_LISTENERS | Block.FORCE_STATE`.** `NOTIFY_ALL` makes a custom-framed portal destroy itself during its own placement.
- **Arrival placement asks the COLUMN, never the dimension type** (never `logicalHeight`, never `World.getTopY` on an unloaded chunk), and when no open site exists it CARVES. Falling back to the heightmap puts players on the nether roof; refuse the traversal when neither works.
- **Source portal interiors carry no portal blocks** — zones are invisible, only arrival portals are real `NETHER_PORTAL` blocks. Assert source-side success by traversal, never by probing the interior.
- **Portals break at both ends**, matched on the source column stamped into each arrival cell at creation — never by inverting the scale transform.
- **`borders.player` must be `overworldBorder / scale`.** Entering divides by scale; an arrival outside the destination's player border lands somewhere vanilla forbids breaking and placing every block, and the symptom points nowhere near the cause.
- **Removing a dimension needs the `level.dat` scrub** — `/customdim destroy` only unloads. Procedure: [`dimension-lifecycle-operations`](../.claude/skills/dimension-lifecycle-operations/references/removal-procedure.md).

## Diagnostic artefacts

**RCON is a doorbell, not a delivery van.** It concatenates feedback lines with no separator, truncates at a few KB, and cannot tell a parse failure from a timeout from an empty result. Never verify by parsing its output.

- A diagnostic command answers `<command> <subject>: <summary> -> <path>` and writes the real answer via `Artefacts.write`. The summary must be independently useful — counts, not "OK".
- Artefacts live under `.seed-rolling/` (a directory sibling to `data/`, out of reach of `deploy.sh` step 8 and `./dev refresh-config`). `./dev clean` only removes them when `seeds` is named explicitly.
- Census records (`rejections__*`, `occupancy__*`) live in the world save under `customdimensions/census/` instead, keyed by dimension with no `inputHash` — a world wipe is the only thing that should remove them.
- Every artefact opens with `Artefacts.jsonHeader(kind)`; callers append their own fields.
- A command that iterates a registry or a world writes a file. `structure-census` and `spike-compare` are the two documented inline exceptions.
- **Every biome-tag, structure-biome and structure-set question is answered by `/customdim catalogue`**, which dumps the live registries; `c:*` tags exist only at runtime, so a jar scan gets them wrong. `scripts/extract-registries.py` copies the dump into `config/custom-dimensions/extractors/registries.json`.
- **Start any "is the mod behaving?" question here, not with RCON**: `structure-census` and `/customdim occupant`, having checked the boot log's `DimensionFingerprints` drift WARN first — under drift every other result is measuring an older config ([D2](../TROUBLESHOOTING.md#d2)).

The full contract, the artefact table and the seed-rolling internals: [docs/mod-internals/diagnostics.md](../docs/mod-internals/diagnostics.md).

## Verification loop

900+ JUnit tests run under `mise exec -- ./gradlew test` (`useJUnitPlatform`, `build.gradle:27`) and cover the worldgen-composition subsystem in real depth — run them first. They do NOT replace the runtime loop: anything touching mixins, chunk generation or portal behaviour is only proven against the real modded server, locally first. A release→deploy cycle costs 10–15 minutes and restarts production; the local loop costs ~1 minute. **Never cut a release for a change you haven't run through this loop.**

### 1. Verify the artefact, not the build

Gradle reports `BUILD SUCCESSFUL` even when `remapJar` emits an empty or unremapped jar. Inspect the jar you intend to ship:

```bash
mise exec -- ./gradlew build
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'   # expect your real class count, not 0
unzip -l build/libs/<mod>-<version>.jar | grep refmap          # <mod>-refmap.json MUST be present
unzip -p build/libs/<mod>-<version>.jar path/to/SomeMixin.class | strings | grep -m3 'class_'
# intermediary names (class_XXXX) = remapped correctly; Yarn names only = dev jar, will crash
```

**Dev jar trap:** `build/devlibs/<mod>-<version>-dev.jar` uses Yarn named mappings and has no refmap — it works only inside a Loom dev environment. On a real server every mixin fails (`could not find any targets ... No refMap loaded`) and the server crash-loops. Only ever ship `build/libs/<mod>-<version>.jar`. CI enforces the class-count and refmap checks (`mod-build.yml`, `release.yml`).

### 2. Fast local loop

Canonical workflow, including what a link does and does not reach: `.claude/skills/local-stack-testing/SKILL.md` § Linked local development. The mod-shaped summary:

```bash
cd ~/Projects/elfydd && ./dev link        # once per consumer; readlink .stack/current -> dev

cd ~/Projects/minecraft-server-template/mods/<name>
mise exec -- ./gradlew build

cd ~/Projects/elfydd
./dev up
ls data/mods | grep <modid>                                      # exactly one line
docker inspect mc --format '{{.State.Health.Status}}'            # must be healthy
docker logs mc --tail 80 2>&1 | grep -iE 'mixin apply|<modid>|error'
```

- `./dev link` builds a symlink farm at `.stack/dev/stack`; `local-mods/<jar>` symlinks each built jar under `<checkout>/mods/*/build/libs/`, skipping `-dev`/`-sources` jars and any jar with no refmap. A rebuild changes what the symlink points at, so no re-link is needed — **except after `gradlew clean` or a `mod_version` change**, where the symlink names a deleted file and `./dev up` aborts.
- **Never place or delete a jar in `data/mods/` by hand.** Each `./dev up` installs the farm's jars, rewrites `data/mods/.local-mods-manifest`, then deletes every `data/mods/*.jar` named in neither this boot's seed manifest, that file, nor `$STACK_DIR/local-mods/`.
- **A seed-viewer change is a mod REBUILD, not a container restart** — markup, CSS and JS are jar resources. **A CSS change needs `./build-viewer-css.sh` first:** `web/app.css` is Tailwind source, the jar ships `web/app.built.css`, and Gradle never compiles it, so an unbuilt edit rebuilds silently with the old stylesheet (`mod-build.yml` runs `--check`).
- **c2me's DFC patch is automatic.** The preLaunch entrypoint (`C2meConfigPatch`) forces `useDensityFunctionCompiler = false` into `data/config/c2me.toml` on every boot, so bare `docker stop mc && docker start mc` cycles stay patched ([D6](../TROUBLESHOOTING.md#d6) — the scripts pre-patch as a second layer for a fresh environment's first boot). Verify by reading the file — `docker exec mc grep useDensityFunctionCompiler /data/config/c2me.toml` must answer `= false` on c2me `0.4.0-alpha.0.27`, which keeps the key; below that pin it is stripped as unknown and D6's log grep is the proof.
- If a persisted state format changed (config schema, namespace, IDs), delete the mod's state file(s) under `data/config/` before restarting — stale state masks bugs and creates ghosts.

### 3. Exercise via RCON, headless

Dimensions are created at boot from `config/custom-dimensions/dimensions/*.json`; there are no `/dimension create` or `/portal link` commands. Consumers override via `overlay/config/custom-dimensions/dimensions/` — full replace, `"overrides"` deep-merge, or empty `{}` to skip. The mod reads the directory at boot, creates missing worlds, and unloads any managed-namespace world not in the config.

```bash
# Namespace comes from custom-dimensions/settings.json (default "adventure")
NS=$(python3 -c "
import json
print(json.load(open('data/config/custom-dimensions/settings.json')).get('namespace', 'adventure'))
")
docker exec -i mc rcon-cli "execute in ${NS}:the_blossom_gardens run seed"   # proves creation from config
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10
```

### 3b. Player-dependent paths: drive a Carpet fake player (local only)

Portal traversal, zone entry and presence timers can be tested headlessly by puppeting a Carpet bot over RCON. Carpet ships as a platform default (made safe next to Supplementaries by `scripts/patch-mod-data.py` stripping one mixin on every deploy and every `./dev up`; see [`docs/known-issues/carpet-supplementaries-piston-crash.md`](../docs/known-issues/carpet-supplementaries-piston-crash.md), and re-run its reproduction if you bump the carpet pin).

- **Full rig and every gotcha:** [`carpet-bot-harness.md`](../.claude/skills/fabric-mod-development/references/carpet-bot-harness.md) — setup, ignition, traversal, return trip, breaking, and the recipe families (tag/colour, per-part, gateway, aura, shape).
- **Portal-specific recipes** (pattern shapes, `end_exit` centreBlock, exit-portal asserts, frame conversion by other mods): [docs/mod-internals/portals.md § Carpet portal test recipes](../docs/mod-internals/portals.md#carpet-portal-test-recipes).
- Capture a baseline BEFORE the change or the result is unfalsifiable, and assert with `data get entity` and log greps, never RCON chat echoes.
- Autopause kicks the bot: `docker exec mc sh -c 'touch /data/.skip-pause'` before testing. Always clean up afterwards.

### 4. Soak time-based paths

Anything on a timer (idle unload, cooldowns, periodic saves) must be soaked through its **real** window — the tick-loop crash class only shows up when the timer fires (see § Threading and lifecycle invariants):

```bash
# Add a test dimension under config/custom-dimensions/dimensions/, restart mc,
# wait out the full timer window (e.g. idle unload = 5 min + the check cadence), then:
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
docker exec mc cat /data/logs/latest.log | grep -iE 'Unloading idle|ConcurrentModification'  # feature line, no CME
```

### 5. Ship and verify at each layer

Once the local loop passes: commit → `gh workflow run release.yml -f version=vX.Y.Z` → consumer `./dev update` (or `./ops update` for production). Then verify **outcomes, not script output**:

- Script counters count commands _sent_, not commands that _succeeded_ — a brigadier parse error still increments "Created: 74". Check the persisted result (e.g. count `data/config/custom-dimensions/dimensions/*.json`) and spot-check via RCON.
- Snapshot production state, never stream: `docker logs mc --tail 50`, `docker inspect mc --format '... RestartCount ...'`. A RestartCount above 0 is a crash you haven't explained yet.
- Under deploy load (world creation, Chunky, mod sync) RCON responses can time out and come back empty — treat an empty response as a failure to re-check, never as success.
- If production's persisted mod state predates a format or namespace change, stop mc, delete the state file, re-run `deploy.sh`.

**Delivery pipeline (never hand-copy jars into consumer repos, never publish to Modrinth):** `release.yml` builds each mod and stages the **remapped** jar from `build/libs/` as `dist/local-mods/<mod>.jar`; `build-stack-bundle.sh` packs it into the bundle as `stack/local-mods/`; `deploy.sh` copies it into `data/mods/` **before mc starts** (step 8b — ordering is load-bearing: a jar copied after the health wait can never fix a boot the old jar breaks), and `dev-up.sh` does the same on every `./dev up`. No jars are committed to git.

## Worldgen and structures

Full rules and the measured numbers behind them: [docs/mod-internals/worldgen-structures.md](../docs/mod-internals/worldgen-structures.md).

### Worldgen self-containment rules

Registry JSON baked into the mod jar is ALWAYS loaded — a reference to an absent mod's content is a boot break, not a cosmetic gap.

- **Noise ids are load-bearing; DF ids are not.** Vanilla seeds each noise by MD5-hashing its id string, so noises get byte-identical SAME-ID copies while density functions are cloned into `adventure:`.
- **Never ship a copy of a vanilla-shipped `minecraft:` id** — ours would win over vanilla once the owning mod is removed and repaint the real overworld. Mod-INVENTED `minecraft:` ids must be cloned, or they dangle.
- **Regenerate the jar-baked data on pin bumps:** `scripts/gen-terrain-presets.py` (Tectonic/Terralith or MC version), `scripts/gen-structure-groups.py` (any structure-mod pin). `--check` gates staleness.

### Noise structure placement

Noise placement is the default for every managed dimension: structure sets are sorted into seven meta-groups, biome-filtered against the dimension's own biome source, and each active group gets one `NoiseStructurePlacement`.

- **Placement is ORDER-FREE** (threshold + unsigned white-noise rank within the exclusion radius), so the traversal may be optimised freely — but **rank on white noise, never on the placement field itself**, or the exclusion radius goes inert.
- **Frequency scales with the playable radius**, and **chunk coordinates must never land on the noise lattice** (`StructureNoise.sampleChunk` adds irrational offsets).
- **Data is jar-baked, not read from config**; the copies under `config/custom-dimensions/` exist for seed rolling.
- Do **not** "optimise" a large dense dimension's placement build by capping positions, shrinking `MAX_RADIUS_CHUNKS`, or raising exclusion — all three change worldgen.

### Structure placement lessons

- **`/locate` is first-found-in-radius-order, not nearest-across-sets**, and an out-of-biome forced structure generates but is **not locatable**. Verify with `structure-census` and the boot log.
- **Forced start attempts are performed by the mod**, not left to vanilla — `ChunkGeneratorForcedStartMixin` runs first at priority 900 and bypasses both the biome gate and other mods' HEAD cancels ([T25](../TROUBLESHOOTING.md#t25)). Vanilla behaviour is unchanged for every other set.
- **Vanilla `CubicSpline` extrapolates LINEARLY beyond its endpoints** — a re-implementation that clamps flattens any spline with non-zero edge derivatives.
- **A site is filled from its own candidate chain** (`StructurePick.candidates`): the assigned structure, then bounded re-draws, each held to its declared biomes unless `include`/`wants` admitted it. The chain is pure and the mixin is idempotent, which is what keeps placement order-free. A chain that declines end to end leaves the site empty, recorded once per site in the world's census file.

## Architecture (custom-dimensions)

Component tree: [docs/mod-internals/architecture.md](../docs/mod-internals/architecture.md).

## Current mods

| Mod | Status | Purpose |
| --- | --- | --- |
| `custom-dimensions` | Shipped | Boot-time dimension creation from repo config, custom portal frames and igniters, coordinate scaling, bidirectional travel, per-dimension noise settings and structure density, portal auras, and immersive portals (server-side, no client mod) |
