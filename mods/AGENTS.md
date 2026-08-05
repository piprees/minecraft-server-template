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
- **Placing NETHER_PORTAL blocks: frames first, portal blocks last with `Block.NOTIFY_LISTENERS | Block.FORCE_STATE`.** Vanilla `NetherPortalBlock` re-validates its shape on any neighbour update and pops to air unless framed by OBSIDIAN specifically — with `NOTIFY_ALL`, a custom-framed portal self-destructs during its own placement (each block's update pops the previous one). Arrival teleports are coordinate-based, so traversal can appear to "work" while the return portal never existed — the Carpet-bot loop below is what actually catches this.
- **Arrival placement asks the COLUMN, never the dimension type** (`PortalSite`). In a ceilinged world the search band runs from just under the roof down to the world floor, where "the roof" is measured live: `findCeilingY` is the highest opaque block in the column (so the whole interior always has cover overhead, which makes "standing on the roof" unreachable by construction) and `findRoofUndersideY` is the first open block below the contiguous roof slab (so an entombed column is not carved out near the TOP of a forty-block mass). The old start was `bottomY + logicalHeight() - 2`; `logicalHeight` is a dimension-TYPE property, 128 for anything nether-shaped, and these generators ignore it — `the_boneyard`'s roof measures y≈180–190 with the playable floor near y≈100, so the band topped out at 126 and could miss the playable space entirely. Non-ceilinged dims keep the `findSurfaceY` heightmap start and take none of this. Never trust `World.getTopY` on an unloaded chunk (it silently returns bottomY).
- **When no open site exists, CARVE — never fall back to the heightmap.** `MOTION_BLOCKING_NO_LEAVES` reads the roof in a ceilinged dimension, so `if (siteY == NO_SITE) siteY = surfaceY;` puts players on the nether roof at y=192 — the exact failure `PortalSite` exists to prevent, dressed as a rescue path. `PortalSite.findCarveY` re-searches the same band with the requirement relaxed from "already open" to "openable", preferring solid ground under the floor row; `createTargetPortal` lays a floor under a vertical arrival's bottom row when there is none. Carveability is defined ONCE and shared with `carveEgress`, so the search only promises sites the carve can deliver. Both failing means bedrock all the way down, and the traversal is REFUSED with a log line and an action-bar message rather than teleporting someone somewhere unopenable. The creation log says which was used (`[open site]` / `[carved site]`) — "carved" on every arrival in a dimension means its band is wrong again.
- **Reachability is checked at boot** (`ArrivalReachability` → `PortalSafetyValidator`). Entering DIVIDES by scale, so a source portal at radius R arrives at `R / scale` and must land inside the destination's PLAYER border; outside it vanilla forbids breaking AND placing every block and the symptom points nowhere near the cause. The margin is **0** on purpose: every dimension is authored as exactly `overworldBorder / scale`, so any margin warns about all 74 and stops being a warning. NB the class shipped fully written with zero callers for a release — a green build and a green suite both said fine while the check did not exist.
- **Portals break at BOTH ends** (`PortalBreakLink`, Phase 9c). Mining the source frame clears the arrival; mining the arrival closes the source zone. The two ends match on the source column each arrival cell was stamped with at creation (`setSourceColumn`), never by inverting the scale transform. `PortalBreakLink.centreColumn` is the ONE definition of a zone's column, shared with `ServerWorldMixin` — two copies of that average would drift and the break would silently match nothing. **Exempt:** anchor dims (one arrival shared by many sources; guarded by `hasAnchor()` on the source side and `exitMode != null` on the arrival side), exit portals and shrines (they carry an `exitMode`), and single-use expiry — which is why the trigger lives at the two places a player actually breaks something and NEVER inside `removeZone`, which `expireSingleUse` also calls. The source frame is deliberately left standing when its arrival breaks: deregistering closes the way, and the blocks stay the player's to mine or re-ignite. A counterpart in a cold chunk is deregistered immediately and its blocks queued for `processPendingBreaks` (loaded chunks only — never sync-load). Both paths `savePortalLinks()` immediately: the zone-validity path had no save of its own, so a broken portal survived in `portal_links.json` until a clean shutdown and came back on a crash.
- **Anchor portals** (`portal.anchor`): every source portal lands at one fixed anchor; per-source target portals are suppressed. The anchor arrival portal's return targets carry an `exitMode` (`origin`/`bed`/`worldSpawn`) resolved by `EntityTickPortalMixin` — `bed` uses `getRespawnTarget(true, NO_OP)` (alive=true: locates without consuming respawn-anchor charges).
- **Single-use portals** (`portal.singleUse`): the countdown lives on the zone (`singleUseTicksLeft`, -1 = unarmed), ticks in `ServerWorldMixin`, and persists in the `portal_links.json` zone record — written at countdown start and shutdown, so restarts resume rather than reset. Decay-map resolution is pure logic in `PortalDecay` (unit-tested).
- **Exit portals** (`exitPortal`): `ExitPortalManager` builds/rebuilds a frame near dimension spawn, checked every 100 ticks from the world tick with a chunk-loaded guard (never sync-load a chunk just to inspect it). `PortalSafetyValidator` WARNs at boot (fingerprint tone: never crash, never auto-fix) when singleUse/anchor dims lack one.
- **Portal config is NOT creation-time-only** — unlike worldgen it re-reads every boot, so anchor/singleUse/exitPortal changes apply to existing dimensions without a wipe.
- **Frame materials** (`FrameMatcher`): `frameBlock` accepts a plain id, `#ns:tag`, a list, or `{"colorGroup": "<dye>"}` (16 `adventure:<colour>_blocks` tags ship in the jar datapack). Accepting is not placing — `framePlaceBlock` is what mod-built frames use. `orientation` gates ignition axes (absent = "any"). Zone records are immutable snapshots of their ignition-time accept forms.
- **Persisted `PortalDefinition.frameBlock` must ALWAYS be a plain parseable id — never a `#tag` form.** Older jars `Identifier.of()` it in an uncaught world-tick path (`isZoneValid` ← `restoreZones`); a `#` in a persisted zone record crash-loops any server running a pre-FrameMatcher build. Accept forms belong in `frameAccepts`; `toPortalDefinition` enforces this and a unit test pins it. General rule: fields serialised into `portal_links.json` must stay parseable by every jar that might ever read them back — deploys roll back.
- **Registered portal blocks are protected from neighbour-update popping** (`NetherPortalProtectionMixin`). Vanilla `NetherPortalBlock.getStateForNeighborUpdate` pops any portal not framed in obsidian whenever an adjacent block changes, so any mod that converts blocks beside a portal silently deletes custom-framed arrivals within seconds. The mixin protects only positions registered in `PortalHelper`'s return-target map, so player-built vanilla portals keep vanilla rules. Reach for a temporary portal-pop stack-trace mixin early if arrival portals vanish — with several mods converting blocks in the pack, the actual culprit is rarely the first suspect.

**Portal auras** (`portal.aura.subsume`): what an aura may convert — `none` | `natural` (default) | `everything`. Pure policy logic lives in `AuraPolicy`; the claim gate is `compat/ClaimsCompat`.

- **Claims are a hard veto ABOVE the policy, and `everything` does not bypass them.** Evaluated in `runPass` before `convertAt`, so it covers flora and fluids as well as replacements. One rule, no exceptions — the owner's framing is that claiming land IS the interaction ("if they built a portal like that without a protection mechanism like a claim then, well, that's karma"), not a safety net bolted onto a destructive feature.
- **Open Parties and Claims is reached by REFLECTION against its public server API** (`xaero.pac.common.server.api.OpenPACServerAPI`), never a compile dependency and never its internals — consumers assemble their own mod list. Every failure path answers "not claimed", so an upstream rename degrades to pre-claims behaviour rather than silently switching the aura off. Call volume is ~2 lookups per 40 ticks per portal side, so no cache is needed.
- **`natural` discriminates by BLOCK IDENTITY** (`#adventure:aura_protected`, a jar-datapack tag), not by history. The two obvious history-based mechanisms are both wrong here: a Ledger SQL query per candidate from the world tick is the same class of mistake as sync-loading a chunk, and a link-time terrain snapshot fails the motivating case outright — people build the house first and put the portal in the basement afterwards, so every cobble is already standing when the snapshot is taken. Identity has no ordering hole and gives players a rule they can state: the aura spreads through the world, not through what you made.
- **The policy is resolved LIVE at use time** (`PortalAuraManager.subsumeFor`), keyed on the dimension whose nature is leaking — the same key for both the source zone and the arrival site. The value persisted in a zone record is only a fallback for a dimension that has left the config. Without this, auditing the shipped set would change nothing on a running world, because every live portal is already lit.
- **`subsume` gates explicit `conversions` too.** A conversion map names what an author wants changed; it does not carry permission to eat a player's walls. An author who means that says `everything`.
- **`none` also withholds fire and fluids**, which only fill air and would otherwise pass a literal reading of "never replaces an existing block". They are the two things an aura does that damage what is around them, and `none` exists for dimensions meant to be unassuming. Flora and configured trees still appear.

**Immersive portals** (`portal.immersive`): a presentation layer over an existing portal — see through it, hear the far side, throw things through. Server-side only; no client mod.

```jsonc
"portal": {
  // absent = immersive, all defaults. "immersive": false is the OPT-OUT.
  // or, with tuning:
  // "immersive": {
  //   "enabled": true,          // an explicit false here = not immersive
  //   "previewDepth": 8,        // blocks deep behind the frame (1-16)
  //   "previewRadius": 2,       // pad beyond the frame edge (0-4)
  //   "refreshInterval": 4,     // ticks between delta refreshes (min 2)
  //   "activationRange": 24,    // blocks (1-64)
  //   "audio": true,            // biome ambience leaking through
  //   "entityPassthrough": true // items/projectiles/orbs/falling blocks
  // }
}
```

- **Immersive is ON by default** (`ImmersiveSettings.fromJson(null)` returns `DEFAULTS`). An absent `immersive` field means immersive; `"immersive": false` is the opt-out. A malformed value also falls back to DEFAULTS rather than off — the rest of that parser defaults rather than rejecting, and silently disabling a feature is this codebase's worst failure mode.
- **The sightline mask reads the world, not the frame's shape** (`ProjectionVolume.occluders`). A block is projected when its shadow on the portal plane touches the aperture AND never leaves the aperture plus whatever really occludes around it. Two earlier rules were both reported in game: centre-only leaked geometry past the frame edge at grazing angles, and whole-shadow-inside-the-aperture was "too conservative" — it also hid anything clipping a frame CORNER, because a geometric ring built from the four in-plane offsets never contains the diagonals. Probing real block states fixes the corners and lets a portal set into a wide WALL show the much larger window that wall is already occluding.
- **`seesThroughOpening`'s "eye is past the plane" shortcut must check the APERTURE, not the plane.** `viewerFarSide` only flips the slab on a block boundary, so there is a one-block band on the normal axis where the eye is past the plane's midpoint while the slab is still on the far side — and that band is infinite in the in-plane axes. Treating all of it as "standing in the doorway" made the whole slab, padding included, pop in for anyone walking sideways PAST a portal, with the frame wall between them and the opening. It is also the only path that ever showed the padded columns, which is where fake blocks a player could collide with and mine came from.
- **The arrival aperture is faked away, the source zone's is not.** An arrival portal is real `NETHER_PORTAL` blocks, so it keeps vanilla's purple swirl and its client-side particle storm in front of the preview. `PlayerProjectionState` overlays the aperture cells with whatever the return world holds there (air, in practice) — no swirl to draw, and no `randomDisplayTick` either, because the client believes it is air. The two directions are told apart by `isRegisteredPortalPosition` on an interior cell: a source zone's interior carries no portal blocks by construction, which is the same invariant that makes source zones invisible.
- **Immersive portals THIN their particles, they do not lose them.** Suppressing the interior fill entirely left "a perfectly hollow box". Both the source fill (`PortalHelper.spawnParticles`) and the arrival dust (`spawnTargetPortalParticles`, gated on `ImmersiveProjector.isImmersiveArrival`) emit one particle per cell every `IMMERSIVE_PARTICLE_INTERVAL` ticks instead of two per tick — about a twelfth of the density, which reads as dust drifting out without fogging the view.
- **`immersive` is boot-re-read, like the rest of the portal block** — no world wipe needed. But it is `transient` on `PortalDefinition` and deliberately NOT serialised into `portal_links.json`, so a zone restored from disk deserialises with it null. `PortalHelper.restoreZones` re-stamps it from live config via `MultiverseConfig.getImmersiveFor(targetWorld)`. Without that re-stamp every already-ignited immersive portal silently stops being immersive on the next restart. Stamping **null** is correct and load-bearing: it's how turning the setting off takes effect for existing zones.
- **Never sync-load a chunk from the projector, the audio pass, or entity pass-through.** All target-world reads go through `getChunkManager().getWorldChunk(cx, cz, false)`; null means skip. `PortalHelper.findSurfaceY` force-generates, so the immersive code deliberately reimplements its maths (`ImmersiveProjector.arrivalSurfaceY`, `EntityPassthrough.arrivalSurfaceY`) on an already-loaded chunk. Sync-generating from a world tick is the Epic Dungeons + c2me wedge in Known issues — a cosmetic preview must never be able to hang the tick loop.
- **Arrival chunks need a ticket, not just pre-generation.** Phase 0's proximity pre-load generates a 5x5 grid, but with no ticket and no player there the outer ring unloads again within seconds. The projector holds a chunk ticket while any player is near, released on every teardown path, with a 100-tick expiry refreshed every 20 so a missed release self-heals rather than pinning chunks forever. Without the ticket the preview works exactly once and then never again (the pre-loader's dedupe only clears on world UNLOAD, and only the *chunks* had unloaded).
- **Anchor dimensions share one arrival between many source portals**, and a chunk ticket is a single entry keyed on `(type, level, argument)` — so a release must check no other zone still wants that chunk, or the first teardown drops the ticket for every holder.
- **Fake blocks are client-only `BlockUpdateS2CPacket`s**, never placed in the world. `ChunkDeltaUpdateS2CPacket` looks like the batched answer but its only 1.21.1 constructor takes a `ChunkSection` and reads states *out of the real world*, so it cannot express fake states at all — don't "optimise" back to it. Cleanup must restore `world.getBlockState(pos)`, never a hardcoded AIR (a projection overlapping a real portal block must come back as the portal block).
- **Cleanup has six paths and all six matter**: out of range, zone removed (hook `PortalHelper.removeZone`, which covers frame-break *and* single-use expiry), disconnect, join (a relog in range would otherwise leave stale `lastSent`, making the delta pass a no-op against a client holding fresh real blocks), player world change, and target world unloaded. Leaked fake blocks persist until the player relogs.
- **Audio uses `world.playSound`, never game events.** `GameEventSuppressionMixin` drops every game event in a managed world with no players, and the target world usually has none. Sound is emitted in the SOURCE world at the portal centre, sampling biome data from the target.
- **There is no cross-portal weather relay and there cannot be one.** Every dimension this mod creates is built over an `UnmodifiableLevelProperties` wrapping the same main world properties (see `DimensionManager.getOrCreateDimension`), exactly as vanilla does, so `isRaining()`/`isThundering()` read one save-wide flag. `target raining && !source raining` is unsatisfiable. Vanilla's `/weather` also ignores `execute in <dim>` and always targets the overworld.
- **Entity pass-through detects a swept path, not a position.** A bow arrow covers ~3 blocks per tick and would tunnel straight through a one-block-thick portal. The interior is tested as a set of block positions rather than its bounding box, so irregular flood-filled frames don't grab things flying past a concave corner. Crossing is entry-EDGE triggered (like the player loop) and the "inside" set is recorded *before* the cooldown gate — otherwise an entity waiting out its cooldown inside the zone re-fires the instant it expires (ping-pong).
- **Cross-dimension teleport RECREATES a non-player entity**, so anything set on the original reference afterwards is set on a corpse. Use `Entity.teleportTo(TeleportTarget)`: it carries velocity as a first-class field (so position and velocity land together on the entity that arrives) and returns the live arrival. Living entities are excluded wholesale — pathfinding, AI memories, leashes and spawn tracking are all world-scoped.
- **Gateway portals are excluded** from block projection and entity pass-through (a frameless single block has no projection plane, and vanilla already handles entities standing in one).
- **Resolve the arrival through `ArrivalResolver`, never the heightmap directly.** The player path lands at the EXISTING arrival portal when there is one (`landY = existing.getY()`) and only consults `findSurfaceY` to build a new one. Building that portal *changes the heightmap it came from* — `createTargetPortal` places solid frame blocks above the top interior row, so `MOTION_BLOCKING_NO_LEAVES` at that column afterwards reports the top of our own frame, not the ground: a bot can land at y=63 while the heightmap answers 67, putting the preview four blocks high and showing empty sky. `ArrivalResolver` reads `PortalHelper`'s **in-memory** registered-target map first (same `(5, 16)` box and `(x, z, y)` scan order as `findExistingPortal`, so it picks the same portal) and falls back to the heightmap only for a column with no portal yet. Don't use `findExistingPortal` here despite it being the player path's tool — it scans real blocks and would touch unloaded chunks.
- **Distinguish air from unknown when sampling the target.** An unloaded chunk reads as "no block" exactly like air does, and conflating them silently shrank every preview to 2 blocks deep (`PlayerProjectionState.decideDepth` declines to decide below 75% known, and measures its air threshold over known samples only). The same three-state discipline applies to any future heuristic over projected content.
- **Log counts, not just events.** Both the chunk-ticket bug and the arrival-resolution bug were only visible because the projector logs how many blocks it projected and 4e logs its air/solid/unknown tallies. An "activated" line alone would have looked perfectly healthy in all three broken states.

**Verification recipes (immersive)** — everything below is headless; the visual and audio quality checks genuinely need a human in-game.

```bash
# Mod DEBUG logging is off by default and audio/entity lines are DEBUG.
# It is an ENV VAR, not a file patch: set CUSTOMDIM_LOG_LEVEL=debug in .env
# and restart mc. log4j2-adventure.xml reads it via log4j2's Environment
# Lookup (${env:CUSTOMDIM_LOG_LEVEL:-info}).
#
# Do not patch log4j2-adventure.xml in the stack-config volume instead: every
# seed run reverts that volume (TROUBLESHOOTING.md#t12), so the patch vanishes on the next
# `./dev up` and takes the diagnostics with it.
echo "CUSTOMDIM_LOG_LEVEL='debug'" >> .env   # consumer repo
docker stop -t 60 mc && docker start mc      # restart is enough; no recreate needed

# Activation / teardown (INFO — always visible)
docker exec mc sh -c 'grep -E "immersive: (projection|holding|released)" /data/logs/latest.log | tail'
# Expect "projection activated ... (336 blocks)" for a 2x3 doorway at depth 8
# radius 2. A SHORT count on the first send is normal, not a defect: the
# ticket loads asynchronously so the far chunk misses that tick and the delta
# pass fills it. Pin the arrival chunks with a second player to see the full 336.

# The load-bearing assert: fake blocks must never become real. Lay markers
# through the slab, activate, then prove they survived.
docker exec -i mc rcon-cli 'setblock <x> <y> <z+1> minecraft:gold_block'
docker exec -i mc rcon-cli 'execute if block <x> <y> <z+1> minecraft:gold_block'

# Entity pass-through (capture a baseline BEFORE the change, or the result
# is unfalsifiable). kill's output doubles as the arrival count and cleanup.
docker exec -i mc rcon-cli 'summon minecraft:item <zone x> <y> <z> {Item:{id:"minecraft:diamond",Count:1b},Motion:[0.0,0.0,-0.4]}'
docker exec -i mc rcon-cli 'execute in <ns>:<dim> positioned <ax> <ay> <az> run kill @e[type=item,distance=..48]'
# Load-bearing negative: a pig must NOT cross (living entities excluded).
```

Config negatives are worth exercising because they run the whole parse->gate path: `"immersive": {"enabled": true, "audio": false}` must give a projection with zero audio DEBUG lines, and `{"entityPassthrough": false}` must put entities back to not crossing.

## Diagnostic artefacts — the answer goes in a file, never down the wire

**RCON is a doorbell, not a delivery van.** It concatenates feedback lines
with no separator and truncates the response at a few KB, so any command that
iterates a registry or a world comes back as one unreadable, half-missing
string — which looks like a working command until you try to use it. It also
carries no error type: a parse failure, a timeout and an empty result are
indistinguishable, and under load an empty response reads exactly like
success.

So verification does not parse command output. **A diagnostic command answers
with a one-line summary plus a path, writes the real answer to a versioned
file, and a checker in `scripts/` asserts over that file with no server
running.** RCON's job is to trigger the dump and to run short commands; the
answer is read from the file.

### The contract

1. **One line back, everything else on disk** —
   `<command> <subject>: <summary> -> <path>`. The summary must be
   independently useful (counts, not "OK").
2. **Artefacts live under `config/custom-dimensions/`** in the server data
   directory (`data/config/custom-dimensions/…` on the host).
3. **JSON by default.** `structure-audit.txt` and `biome_grid.csv` are
   grandfathered because their consumer is a human or a spreadsheet.
4. **Every artefact carries `schemaVersion` and `generatedAt`**
   (`Artefacts.jsonHeader` / `Artefacts.textHeader`). A checker meeting an
   unexpected version must fail loudly, never mis-read silently.
   `biome_params.json` is the one exception — it is a bare JSON array the
   seed roller loads as a list, and wrapping it would break every roller.
5. **Writes are atomic** — `Artefacts.write` does `.tmp` + `ATOMIC_MOVE`. A
   62k-position census takes real time to serialise and a checker reading
   mid-write would report a fault that does not exist.
6. **Every artefact has a checker** in `scripts/` that runs with no Docker
   and exits non-zero on failure.
7. **A command that iterates a registry or a world MUST NOT answer inline.**

### What exists today

| Artefact | Written by | Checked by |
| --- | --- | --- |
| `census/<ns>__<slug>.json` | `customdim structure-census <ns>:<slug>` | `scripts/check-noise-regression.py` |
| `census/rejections__<ns>__<slug>.json` | `NoiseStructureSelectionMixin` (appended on each structural rejection) | `scripts/check-noise-regression.py` |
| `census/occupancy__<ns>__<slug>.json` | `customdim occupant <ns>:<slug> <cx> <cz>` (reads a LOADED chunk, never generates) | `scripts/seed/verify-occupancy.sh` |
| `structure-audit.txt` | `customdim structure-audit [group]` | — (human-read) |
| `biome_params.json` | `customdim dump-biome-params <dim>` | the seed roller consumes it |
| `biome_grid.csv` | `customdim sample-biome-grid <dim> <r> <step>` | — (ad-hoc) |
| `custom-dimensions-fingerprints.json` | the mod, at world creation | `scripts/check-dimension-drift.py` |
| `portal_links.json` | the mod, on every portal mutation | `scripts/check-portal-integrity.py` |

**`occupancy__`/`rejections__` files are append-on-generation-event records,
not regenerable dumps** — a rejection is written once, when the chunk
generates. Any census-directory clear must exempt them (the refresh scripts
do); deleting one erases the only proof a structural rejection happened, and
re-proving it means regenerating the chunk (move the region file aside while
mc is stopped, revisit).

`./dev verify` runs every checker in one pass. It needs no server: run it
while the server is up, paused, or down.

**Start any "is the mod behaving?" question here, not with RCON.** The census
answers which structures reached a pool and where they were placed;
`/customdim occupant` reads a loaded chunk's live `StructureStart`s to
confirm what actually occupies a site. `/locate` is NOT an occupancy
instrument — a miss walks placements for minutes and wedges RCON (see the
locate note below).

**A checker is only meaningful against a world created under the config it
is checking** — worldgen is creation-time-only
([D2](../TROUBLESHOOTING.md#d2)). `check-dimension-drift.py` is the guard:
run it FIRST, and if it reports drift, every other result is measuring an
older config.

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

Install straight into the local consumer's `data/mods/` and restart only the mc container — no release, no bundle, no full stack cycle.

**Never use `./dev up` to test a local mod build.** `dev-up.sh` copies `stack/local-mods/*.jar` from the bundle into `data/mods/` on every run, silently overwriting your locally-built JAR with the old released version — a test can then run the OLD code and still "pass" while the change under test never executes. Use `docker stop mc && docker start mc` (or `docker restart mc`) — these restart the container without touching the mod files. Only use `./dev up` when you deliberately want to reset to the bundle's shipped JARs.

**c2me's DFC patch is automatic.** The mod's preLaunch entrypoint
(`C2meConfigPatch`) forces `useDensityFunctionCompiler = false` into
`data/config/c2me.toml` on every boot, so bare `docker stop mc && docker
start mc` cycles stay patched with no manual step
([TROUBLESHOOTING.md#d6](../TROUBLESHOOTING.md#d6) — the scripts still
pre-patch as a second layer covering a fresh environment's first boot).
Verify via log grep, never the config file (the key is stripped again by
the boot that honours it).

```bash
cp build/libs/<mod>-<version>.jar <consumer>/data/mods/<mod>.jar
docker restart mc && sleep 45
docker inspect mc --format '{{.State.Health.Status}}'            # must be healthy
docker logs mc 2>&1 | grep -iE 'mixin apply|<modid>|error' | tail -20
```

If the persisted state format changed (config schema, namespace, IDs), delete the mod's state file(s) under `data/config/` before restarting — stale state from a previous build masks bugs and creates ghosts.

### 3. Exercise via RCON, headless

Dimensions are now created automatically at boot from `config/custom-dimensions/` (one file per dimension) — there are no `/dimension create` or `/portal link` commands. Verify via RCON that dimensions loaded correctly:

```bash
# Namespace comes from custom-dimensions/settings.json (default "adventure")
NS=$(python3 -c "
import json
print(json.load(open('data/config/custom-dimensions/settings.json')).get('namespace', 'adventure'))
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

- **Shared igniter items: every matching definition is a candidate.** Eight dims share `ender_eye` as an igniter, so ignition cannot stop at the first matching portal definition — a first-match-wins lookup makes every shared-igniter portal except the alphabetically first dimension unignitable, hunting for the wrong dim's frame block and failing silently. `getPortalsByIgniter(item, clickedBlockId)` returns all candidates, clicked-frame match first, and ignition tries each.
- **Bot aiming for ignition: have the bot stand INSIDE the frame and `look down` at the bottom frame block** — reliable regardless of frame size. Near-horizontal aims at eye-level frame blocks miss inconsistently. When an ender-eye igniter click misses/fails, vanilla throws the eye and runs a synchronous stronghold locate on the main thread (~30s+ stall that looks like a hang) — always check `data get entity Bot Dimension` before assuming the server died.
- **Zone-injection shortcut** (when ignition isn't the thing under test): stop mc, hand-append `source-zone-v1` records to `data/config/portal_links.json` (frame blocks must exist; interiors can stay air — validity only checks the frame ring), start mc — the restore path registers them and `tp Bot` into the interior drives traversal.
- **Ignition positioning**: the bot must be INSIDE the frame looking at the frame wall — from outside, cherry_sapling plants itself on the adjacent block instead of triggering ignition. The `PortalIgnitionMixin` hooks `ItemStack.useOnBlock` at HEAD, but the clicked position must have an air block adjacent to the frame for flood-fill to find the portal shape.
- `player Bot spawn at ...` may ignore the position (tp after instead).
- Vanilla resets portal cooldown every tick while an entity stands IN a portal, so return trips need step-out → wait cooldown (check `data get entity Bot PortalCooldown` = 0) → step-in.
- Assert with `data get entity` and log greps (`docker exec mc cat /data/logs/latest.log`), never RCON chat echoes.
- **Tag/colour frame recipes**: build the frame from ANY member block (tag breadth is part of the assertion — e.g. oak AND birch for `#minecraft:logs`; red_wool + red_terracotta mixed for a colour group), plus a non-member negative (stone) that must NOT ignite. Orientation configs need a rotated-frame negative (Z-axis frame under `vertical_x` must fail). Arrival-frame material asserts `framePlaceBlock` (`execute in <dim> if block <frame pos> <place block>`).
- **Per-part material recipes** (Tier 2b): the positive frame mixes tag members WITHIN a part (oak + birch side columns for `"sides": "#minecraft:logs"`) — tag breadth per part is part of the assertion. The load-bearing negative is a UNION-VALID frame with one block in the wrong PART (a stone block in a side column when stone is the bottom material): flood-fill still bounds it, only the per-part classifier can reject it. Arrival/exit frames assert per-part placement (`if block <bottom> stone`, `<side> oak_log`, `<top> oak_planks`).
- **Gateway recipes** (end_gateway shape): ignition is click-to-PLACE — bot stands on the platform, `look down`, `use once`; the gateway block appears in the bot's feet cell and (source side now has a REAL block, unlike every other source zone) the bot teleports from inside it. Assert the source `END_GATEWAY` block with `if block`, traversal, the arrival gateway block, and the return trip. Suppression proof: after arrival the bot's Pos must sit AT the arrival gateway (vanilla gateway travel would have flung it elsewhere) — assert Pos stability one cooldown later. Pattern recipes: build the template EXACTLY (the positive), then a same-material frame of a DIFFERENT valid free-form shape (the negative — flood-fill bounds it, only the template overlay rejects it).
- **Aura recipes**: use a DISTINCTIVE far side (an end_stone superflat via custom `layers`) so the derived leak is unambiguous — a grass/dirt far side is indistinguishable from overworld terrain. Build the source frame on a platform SURROUNDED by a known palette (moss_block patch, birch logs) inside the 9×5×9 sample cube; traverse; assert the palettes landed in the records (`python3` over `portal_links.json`: zone `auraPalette` carries the far side's blocks, the `aura-site-v1` record carries the source's), then wait N intervals and probe the annulus for converted blocks on BOTH sides (`if block` sweeps for end_stone near the source, moss/stone near the arrival). Assert the frame ring and interior untouched, and `auraBudgetSpent` > 0 and ≤ budget in the records. Speed the test with `"aura": {"interval": 10, "blocksPerPass": 8}` — never test at the default cadence.
- **A failed arrival-frame `if block` assert may mean conversion, not misplacement.** Any block-converting mod in the pack can rewrite frame materials within seconds of a portal appearing — stone becomes netherrack or nether ores, logs become crimson/warped stems, while quartz, deepslate and planks usually stay put. Assert arrival-frame materials IMMEDIATELY after traversal, and when one fails, probe the suspect mod's conversion table before blaming placement: finding `crimson_stem` where you expected `oak_log` PROVES a log was placed.
- **Shape recipes** (Tier 2a pattern): build frames on floating stone platforms (y=149/150) — clean geometry, no terrain interference, no heightmap climb. Load site chunks by `tp Bot <site>` FIRST (player-driven generation — never RCON forceload; fills fail with "That position is not loaded" otherwise). Positives: bot INSIDE the interior, `look down`, `use once` (clicks the platform/bottom frame; candidate = the interior cell above). Negatives are wrong-SIZE frames (door dim × 2x3 frame, doorway dim × 1x2 and 3x3) and wrong-ORIENTATION frames (horizontal ring for a vertical shape and vice versa) — assert with `data get entity Bot Dimension` unchanged AND the source-zone record count in `portal_links.json` unchanged (count `recordType == "source-zone-v1"` — the zone-count oracle catches "ignited but didn't teleport"). For `end_exit`: ring on the platform with a 3x3 air interior, bot in a CORNER cell (leaves the centre free for the `centreBlock` pedestal assert: `execute in <source world> if block <centre> <block>`). Exit-portal shape asserts: grep "Built exit portal in <dim> at (x, y, z)" then `if block` the interior cells AND one-past-the-end cells (door = portal at y,y+1, frame at y+2).
- **Source portal interiors have NO portal blocks** — zones are invisible; only ARRIVAL portals carry real NETHER_PORTAL blocks. Assert source-side success by traversal (`data get entity Bot Dimension`), never by probing the source interior.
- **Carpet ships and needs no setup** — it is a platform default again, made
  safe by `scripts/patch-mod-data.py` stripping one mixin from its jar on every
  deploy and every `./dev up`. Stock carpet crashes the tick loop next to
  Supplementaries on any piston moving a block entity; see
  `docs/known-issues/carpet-supplementaries-piston-crash.md`, and re-run its
  reproduction if you ever bump the carpet pin.
- **`player Bot attack once` does not break a block that needs more than one click** — including portal blocks, even in creative. Use `player Bot attack continuous`, wait, then `player Bot attack stop`. `attack once` silently does nothing and looks exactly like a broken event hook.
- **Carpet bots may not despawn over RCON.** `player Bot kill`, `player Bot stop` and `kick Bot` can all leave the bot in `list`. It goes on the next `mc` restart — don't spend time on it.
- **RCON `fill`/`setblock` into an unloaded dimension silently does nothing**, answering `Unknown dimension '<ns>:<dim>'` — easy to scroll past when it sits above a result that looks right. Load the world by teleporting a player there first, and read each command's own output rather than only the final assertion.
- **Profile an arrival column BEFORE traversing.** Probing it afterwards measures the portal you just built: `if block <interior> minecraft:air` answers SOLID because there is a `NETHER_PORTAL` there, and the carve has already changed the surrounding cells. Same family as the heightmap-raised-by-our-own-frame trap.
- **When the live world won't produce the failing case, construct it.** Verifying arrival placement in `the_boneyard` took a deliberately entombed column (`fill … minecraft:netherrack` from y=40 to y=195) — three natural traversals all passed and none of them exercised the path under test.
- Autopause kicks the bot: use `docker exec mc sh -c 'touch /data/.skip-pause'` before testing.
- Always clean up: `player Bot kill`, remove the carpet jar (`docker exec mc rm -f /data/mods/TEMP-carpet-test.jar`), restart mc.

### 4. Soak time-based paths

Anything on a timer (idle unload, cooldowns, periodic saves) must be soaked through its **real** window, not assumed from reading the code — the tick-loop crash class only shows up when the timer actually fires:

```bash
# Add a test dimension under config/custom-dimensions/dimensions/, restart mc, then:
# wait out the full timer window (e.g. idle unload = 5 min + the check cadence), then:
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
docker exec mc cat /data/logs/latest.log | grep -iE 'Unloading idle|ConcurrentModification'  # expect the feature line, no CME
```

**Tick-loop threading rule:** never mutate the server's worlds map (or any collection vanilla iterates per tick) from a `ServerWorld.tick` / world-tick mixin — that's a `ConcurrentModificationException` crash when the timer fires. Defer mutations to `ServerTickEvents.END_SERVER_TICK` (see `MultiverseServer` and the pending-load queue in `DimensionManager` for the pattern).

**Dynamic world lifecycle rule:** any code path that adds a `ServerWorld` to the server's worlds map MUST fire `ServerWorldEvents.LOAD`, and any path that removes/closes one MUST fire `ServerWorldEvents.UNLOAD` before `close()`. Distant Horizons and c2me build their per-level state exclusively from these Fabric events — skipping LOAD NPEs DH on the first portal teleport into a runtime dimension and can lock a player out of production. Also: never call `getOrCreateDimension` synchronously from command context — world creation there deadlocks the main thread; queue via `requestWorldLoad` (END_SERVER_TICK) instead.

**c2me DFC trap (per-dimension seeds):** `ServerWorldSeedMixin` overrides `ServerWorld.getSeed()` per dimension — that value feeds `NoiseConfig` (terrain, biome layout, aquifers) and structure placement. c2me's density-function compiler (`c2me-opts-dfc`) caches compiled+instantiated density functions across `NoiseConfig` creations and IGNORES the seed, so with it enabled every custom dimension silently clones the main world. `C2meConfigPatch` (this mod's preLaunch entrypoint) forces `useDensityFunctionCompiler = false` into `c2me.toml` every boot; the rest of c2me stays enabled. c2me reads the key at mixin-bootstrap time and strips it afterwards, so each boot's write feeds the next boot's read — bare restarts stay patched, and `deploy.sh` (step 8c) / `dev-up.sh` pre-patch as a second layer covering a fresh environment's first boot ([TROUBLESHOOTING.md#d6](../TROUBLESHOOTING.md#d6)). Verify by the D6 log grep, never the config file.

### 5. Ship and verify at each layer

Once the local loop passes: commit → `gh workflow run release.yml -f version=vX.Y.Z` → consumer `./dev update` (or `./ops update` for production only). Then verify **outcomes, not script output**:

- Script counters count commands _sent_, not commands that _succeeded_ — a brigadier parse error still increments "Created: 74". Check the persisted result instead (e.g. count `data/config/custom-dimensions/dimensions/*.json`) and spot-check entities via RCON.
- Snapshot production state, never stream: `docker logs mc --tail 50`, `docker inspect mc --format '... RestartCount ...'`. A RestartCount above 0 means a crash you haven't explained yet.
- Under deploy load (world creation, Chunky, mod sync) RCON responses can time out and come back empty — treat an empty response as a failure to re-check, never as success.
- If production's persisted mod state predates a format/namespace change, stop mc, delete the state file, and re-run `deploy.sh` — deploys recreate everything idempotently.

**Releasing:** `release.yml` builds the mod with Gradle, stages the remapped jar as `dist/local-mods/<mod>.jar`, and `build-stack-bundle.sh` packs it into the stack bundle as `stack/local-mods/`. On production, `deploy.sh` copies `stack/local-mods/*.jar` into `data/mods/` **before mc starts** (step 8b — ordering is load-bearing: a jar copied after the health wait can never fix a boot that the old jar breaks); locally, `dev-up.sh` does the same on every `./dev up`. Nothing is published to Modrinth and no jars are committed to git.

## Current mods

| Mod | Status | Purpose |
| --- | --- | --- |
| `custom-dimensions` | Shipped | Boot-time dimension creation from repo config, custom portal frames with configurable igniters, coordinate scaling, coloured particles, bidirectional travel, per-dimension noise settings (`noiseSettings` → jar-baked `adventure:wide`/`adventure:compressed`, generated by `scripts/gen-terrain-presets.py` — self-contained (survives Tectonic/Terralith removal); regenerate on Tectonic OR Terralith pin bumps) and theme-aware structure density (`structureDensity` + automatic peaceful overlay; themes from `scripts/gen-structure-presets.py`), and immersive portals (`portal.immersive` — see-through preview via server-side fake blocks, cross-portal biome audio, entity pass-through; no client mod) |

### Worldgen self-containment rules (optional-mods hardening)

Registry JSON baked into the mod jar is ALWAYS loaded — a reference to an
absent mod's content is a boot break, not a cosmetic gap. Rules learned
making the noise presets survive Tectonic/Terralith removal:

- **Noise ids are load-bearing; DF ids are not.** Vanilla seeds every noise
  parameter by MD5-hashing its id STRING — renaming a noise shifts terrain on
  every existing world. Density functions carry no id-derived seed — cloning
  them into our namespace is generation-neutral. Hence the split: DFs get
  cloned into `adventure:`, noises get byte-identical SAME-ID copies.
- **Never ship a copy of a vanilla-shipped `minecraft:` id.** Both worldgen
  jars override vanilla ids (Terralith overrides `minecraft:temperature`!).
  Our copy would win over VANILLA when the mod is removed — silently
  repainting the real overworld. Reference them and accept vanilla fallback
  semantics in the removed case. Mod-INVENTED `minecraft:` ids (both jars
  invent `minecraft:overworld/noise_router/*`) are the opposite: they must be
  cloned/copied or they dangle. `gen-terrain-presets.py` carries frozen
  vanilla 1.21.1 id sets to classify — regenerate them on MC bumps.
- **Unknown codec fields hide dangling refs.** Terratonic's settings ship a
  `noise_router.preliminary_surface_level` ref to a DF that exists in NO jar —
  harmless only because 1.21.1's codec ignores the unknown field. Strip dead
  data rather than whitelisting dangling refs (the generator does).
- **Pack order ground truth is `datapack list` over RCON** (low → high
  priority). Facts: every Fabric mod is its OWN pack (alphabetical, so
  `terralith` > `customdimensions`); built-in packs like `tectonic:tectonic`
  outrank all mod packs; world packs outrank everything. Byte-identical
  same-id copies make order moot — that's the design property, not an
  accident.
- **Positional classification, not name-based.** Ids collide across the noise
  and DF registries (`tectonic:blend_alpha` is both). A string is a noise ref
  iff it sits in a `"noise"` field or a shift-node `"argument"`; spline
  `"coordinate"` strings are DF refs; `biome_is` biome ids and tag files are
  lazy (never boot-break). Terralith writes some `minecraft:` refs BARE
  ("overworld/temperature") — qualify before lookup.
- **Terratonic patches Terralith's own namespace**: the tectonic jar's
  terratonic overlay overrides two `terralith:` DFs with different bytes than
  the Terralith jar's copies. The live graph uses Tectonic's variants (pack
  order). Any closure walk must resolve conflicts by runtime priority, not
  first-found.

### Noise structure placement

Noise placement is the **default** for every managed dimension. It replaces
the vanilla grid for whole structure GROUPS rather than per set: sets are
sorted into seven meta-groups (deco / settlements / dungeons / landmarks /
maritime / endgame / loot), biome-filtered against the dimension's own biome
source, and each active group gets one `NoiseStructurePlacement`. A dimension
with nothing but `type` and `biomes` gets biome-appropriate structures with
no config at all.

- **Placement is ORDER-FREE, and that is load-bearing.** A chunk places iff
  it is above threshold AND no above-threshold chunk within the exclusion
  radius outranks it, where rank is white noise (`mix64` of seed+coords,
  compared **unsigned**) and ties break on the chunk key. This is the
  parallel formulation of dart throwing. It is not a greedy spiral, and it
  must not become one: the roller mirrors this in Python and parity is a set
  comparison, not two traversals that have to agree step for step. It also
  means the traversal can be optimised freely — swapping an O(r^3) ring walk
  for an O(r^2) scan produced byte-identical output.
- **Rank on white noise, never on the placement field itself.** Local maxima
  of a *smooth* field occur about once per noise feature, so their density is
  fixed by frequency alone — the threshold barely participates and the
  exclusion radius is completely inert. Measured: a 1024-block pocket
  dimension produced ONE structure, and changing exclusion from 3 to 20
  changed nothing. Two dials, two jobs: noise + radial curve decide what
  fraction of the world qualifies, rank decides how far apart the survivors
  sit.
- **Frequency scales with the playable radius**
  (`REFERENCE_RADIUS_CHUNKS / radiusChunks`). `sparse`'s 0.015 is a 67-chunk
  lattice period; a 1024-block dimension is 128 chunks across, so without
  scaling the whole world is ~2 periods — one blob, and whether a group gets
  anything is a coin flip. `the_overgrowth` had zero settlements this way.
- **Chunk coordinates must never land on the noise lattice.** Perlin is
  exactly 0 at a lattice point, which normalises to 0.5 for EVERY seed — and
  0.025 is 1/40, so every 40th chunk on both axes would be a fixed,
  seed-independent candidate, placing under `dense` in every world ever
  generated. `StructureNoise.sampleChunk` adds irrational origin offsets;
  never sample raw chunk coordinates.
- **Doubles everywhere, and our own Perlin.** `float` rounds differently from
  Python's always-double floats (`1.3f` is `1.29999995231628418`) and would
  cost the parity gate for nothing. Vanilla's `PerlinNoiseSampler` is avoided
  because mirroring it means mirroring `net.minecraft.util.math.random.Random`
  too, and it drags Bootstrap into unit tests.
- **The peaceful shift outranks `structureDensity`.** A coarse density dial
  must not resurrect a group the dimension's own difficulty says is not
  there. Only an explicit per-group `structures.noise` entry can undo it.
- **Data is jar-baked, not read from config.** `structure_themes.json` and
  `structure_type_defaults.json` are resources (self-containment rule above);
  the copies under `config/custom-dimensions/` exist for the seed roller,
  which in a consumer reads the STACK BUNDLE
  (`.stack/current/stack/config/custom-dimensions`) plus
  `overlay/config/custom-dimensions` — never `data/`, which belongs to the mc
  container and is wiped to reset a world. Regenerate both
  with `scripts/gen-structure-groups.py` after any structure-mod pin bump —
  `--check` gates staleness.
- **Over half of all sets never enter a group.** 227 of 367 (jar-level
  extraction over `elfydd/data/mods/`) have a custom placement type and pass
  through on their own grid placement — subject only to the set-id filters
  (`structures.mode`/`structures.exclude`, applied in the pass-through loop).
  The population is dominated by Moog's `moogs_structures:advanced_random_spread`
  (221 sets: Voyager 110, Nether 41, Soaring 31, End 25, Temples Reimagined 6);
  the rest are YUNG's four per-mod types, Supplementaries' galleon set, and
  `minecraft:concentric_rings` (DnT end_castle — correctly excluded, rings are
  not grid-compatible). Explorify and Towns & Towers ship plain
  `minecraft:random_spread` and ARE noise-managed — Cristel Lib patches their
  spacing numbers via a built-in datapack before registry load and never
  changes the placement type. A `groups=4/5` boot line is also
  normal: a group whose pool is empty after biome filtering is skipped.
- **Never run a bare synchronous `/locate` into an ungenerated custom
  dimension.** It can block the main thread long enough to wedge RCON while
  `docker ps` stays healthy — no crash, the game log just stops advancing
  (recover with `docker stop -t 90 mc && docker start mc`). **Accepted: this
  is pre-existing and explicitly out of scope.** The control:
  `customdim locate structure
  minecraft:overworld "minecraft:village_plains"` — the **stock vanilla
  overworld**, a stock vanilla structure, a placement this platform never
  touches — also times out, at 120 s. Vanilla's 100-ring search across ~150
  structure mods is the cost. Don't go looking for a placement bug.
  **Chunky pre-generation does NOT fix it** — a complete 1024-radius pass
  over `the_overgrowth` (16,384 chunks) leaves the same locate timing out at
  240 s, up from 180 s before the pass. Verify placement with
  `structure-census` and `scripts/check-noise-regression.py` instead; locate
  proves one instance, the census proves the whole layout.
- **Accepted: a large dense dimension takes seconds to build its
  placements.** `the_end_citadel` (8192 border, `dense`,
  5 groups) is ~2.5 s for 62,556 positions; it logs a warning, runs once per
  world load, and is off the tick loop. Do **not** "optimise" it by capping
  positions, shrinking `MAX_RADIUS_CHUNKS`, or raising exclusion for large
  dimensions — all three change worldgen. The counts are already
  conservative: 39,570 `deco` placements replace 144 structure sets vanilla
  would each place every 20-30 chunks, so noise `deco` is *sparser* than the
  grid it replaces.
- **Base worlds are managed like every other dimension** — seed, border,
  difficulty, portal and structures. Their generator is vanilla's, so their
  files name no `type` and `DimensionConfig.getType()` supplies the family
  from `BASE_WORLD_TYPES`; an explicit `type` still wins. **They resolve by
  EXACT dimension id, never by namespace** (`MultiverseConfig.getBaseWorld`):
  `minecraft:` and `paradise_lost:` carry other mods' dimensions, and the
  lookup behind the managed-namespace gate is by PATH, so widening that set
  would let a third party's `minecraft:whatever` resolve against one of our
  configs. The Nether gates blaze rods on fortresses and the End gates elytra
  on end cities, so `scripts/check-noise-regression.py` holds a
  **reachability floor** for both: expected instances within a radius,
  `positions_within x weight / pool_weight` — presence in a pool says nothing
  about reach when vanilla picks the member by weight.
- `/customdim structure-audit` and `/customdim structure-census <dim>` both
  **write files** and return a summary — RCON concatenates feedback lines
  with no separator and truncates at a few KB, so hundreds of rows come back
  as one unreadable, half-missing string.

### Structure occupancy contract

A site's assigned structure is exact and mirrored; the site is occupied by
that structure iff the structure's own generation accepts the position, and
by nothing else, ever. A structural rejection leaves the site empty and is
itself recorded exactly: `NoiseStructureSelectionMixin` logs every rejection
with dimension, group, structure id and chunk coordinates, and appends it to
`census/rejections__<ns>__<slug>.json` via `Artefacts.write`.

Assignment: `StructurePick.assignedStructure(noiseSeed, cx, cz, sortedPool)`
with `pickSeed = noiseSeed ^ saltOf("structure_pick")`. The sorted pool is a
plain string sort on structure id (stable; duplicate-id entries stay
adjacent). `resolveWeighted` uses `Long.remainderUnsigned(pickValue, totalWeight)`,
cumulative walk in sorted order; null iff `totalWeight <= 0`.

The per-world selection registry (`StructurePick.install/lookup/clear`) is
keyed by `WeightedEntry` OBJECT IDENTITY (`IdentityHashMap`), installed by
`DimensionStructures.transformedNoise`, replaced wholesale on every
calculator rebuild, cleared on `ServerWorldEvents.UNLOAD` in
`MultiverseServer`.

MIRRORED in `scripts/seed/noise_placement.py` (`resolve_structure`,
`pick_seed`) -- change both together, re-run `test_noise_parity.py`.

### Mixin ordering on `ChunkGenerator.trySetStructureStart`

Two mixins target `trySetStructureStart` at HEAD, both priority 900:

1. **`ChunkGeneratorForcedStartMixin`** -- performs `structures.force`
   start attempts from the `ForcedStartOverride` registry. Runs FIRST
   (alphabetical class-name order within the same priority).
2. **`NoiseStructureSelectionMixin`** -- enforces the noise-managed
   structure pick from the `StructurePick` registry.

A `structures.force` placement at a chunk that is also a noise site wins:
the forced-start mixin returns first, and the selection mixin never sees it.
Both use the same technique (create the start with an always-true biome
predicate, set the return value). Neither affects pass-throughs, exit
shrines, or other mods' sets -- those miss both registries and fall through
to vanilla behaviour.

### Structure placement lessons (fixed placements)

- **Vanilla CubicSpline EXTRAPOLATES LINEARLY beyond its endpoints** using
  the endpoint derivative. Any Python re-implementation that clamps to the
  endpoint value flattens splines with non-zero edge derivatives —
  tectonic's `full_continents` is `derivative: 1` at both ends (an identity
  band), so clamping collapsed the whole continents field to a constant.
  Hit in preset_terrain.py AND latently in terrain_height.py (masked there
  by Terralith/Incendium/Nullscape splines having zero edge derivatives —
  the nether snapshot render changed when fixed).
- **`/locate` is first-found-in-radius-order, NOT nearest-across-sets.**
  ChunkGenerator iterates placements in map order ring by ring; when a
  structure exists in several sets (organic + forced), the organic set's
  hit can be returned even when the forced one is closer. Oracle design:
  prove forced placements with `"mode": "none"` + `force` (organic sets
  gone → locate must return the exact forced spot or nothing).
- **Forced start attempts are performed by the mod, not left to
  vanilla.** `ChunkGeneratorForcedStartMixin` (priority 900 — HEAD
  callbacks execute in application order, so it runs before
  default-priority cancels) checks the `ForcedStartOverride` registry of
  (world, chunk, structure) triples at the head of
  `ChunkGenerator.trySetStructureStart` and, for a forced attempt,
  creates and records the start itself with an always-true biome
  predicate. This defeats both the structure's own biome gate and other
  mods' cancellable HEAD injects — all seven YUNG's structure mods
  cancel every vanilla start of the type they replace, which killed
  forced `minecraft:fortress` regardless of placement class
  (TROUBLESHOOTING.md#t25). **Vanilla behaviour is unchanged for every
  other set.** Forced structures also get terrain-adaptation resolution
  from the full registries, so beards and kernels apply even when the
  structure's organic set is biome-prefiltered out of the calculator.
- **StructurePlacementCalculator prefilters by biome availability**, and
  that is a SEPARATE gate from the predicate above. `create()` drops
  whole sets whose structures' valid biomes miss the biome source, and
  `calculate()` indexes structure→placement on the same test. Our forced
  sets are synthesised after `create()` and handed to the private
  constructor (`StructurePlacementCalculatorInvoker`), so they bypass the
  first filter and DO generate; `calculate()` still governs
  `getPlacements`, so an out-of-biome forced structure is **not
  locatable**. Verify with `structure-census` and the boot log, never
  with locate.
- **`customdim sample-noise` is a generation ground-truth oracle**: it
  returns the router climate point at (x&~3, 0, z&~3); for Terratonic
  graphs depth(y=0) = 1 + offset, so surface_Y = 128*depth. A c2me-free
  itzg container (fabric-api + customdimensions only, MAX_TICK_TIME=-1
  under qemu — the watchdog kills slow emulated ticks) is the clean rig:
  preset_terrain.py matched it 36/36 probes to the RCON quantisation
  floor (1e-4), both presets, positive and negative seeds.
- **elfydd/production sample-noise vs headless evaluation is a solved
  mechanism**: Tectonic's `overlay.datapack` makes
  `minecraft:continentalness`/`erosion`/`ridge` value-identical to its
  `tectonic:parameter/*` copies, the DF tree's holders canonicalise, and
  `createNoiseSampler` seeds by the canonical `minecraft:` id. The
  roller mirrors both halves (`KNOWN_NOISE_ALIASES`, octave-origin
  verified, plus overlay-aware jar extraction) and matches the live
  c2me-modded server at zero tolerance on the closed chains
  (`BIOME_PARITY_STRICT=1` in `test_biome_parity.py`) — c2me introduces
  no climate delta there. `/customdim eval-df` walks any residual chain
  divergence node by node.

### Seed rolling pipeline

The seed-rolling system at `scripts/seed/` evaluates dimension seeds without running the game. See `scripts/seed/README.md` for the full architecture. Key integration points with the custom-dimensions mod:

- `biome_params.json` is dumped via the mod's `/customdim dump-biome-params` command (captures TerraBlender + all mod biomes across 4 families)
- Dimension configs at `config/custom-dimensions/dimensions/` drive what gets rolled — the roller reads `type`, `biomes`, `seedRoll`, `structureDensity`, and `difficulty` from each file
- Per-dimension `seedRoll` blocks control spawn filters, wants/shuns, mood, and terrain preferences
- Winners are written back to `config/custom-dimensions/candidates/` and optionally into the dimension config's `seed` field
- **Seed-group rolling**: dims with byte-identical generation config (`dimension_profiles.generation_fingerprint`) share measurements — measured once per group, winners forced distinct at finalise (same fingerprint + same seed = literal world clones). Any NEW generation-affecting config field the mod grows MUST be added to `generation_payload()` or grouping silently lies (the roller-parity rule's fingerprint corollary). Example: derived shrine spacing makes `borders.player` generation-affecting for `exitShrines` dims — the payload carries a conditional `shrineSpacing` entry, added ONLY when applicable so pre-existing non-shrine fingerprints stay byte-stable (an always-present key would flag every candidate store DRIFTED)
- **Config-schema Gson traps**: `structures.wants` is `Map<String, StructureWant>` — a band-name STRING there is a parse crash ("config invalid — skipped"); band wants belong in `seedRoll.wants` (free-form, roller-only). Same family: list-form `structures.shuns` crashes (must be the MAP form). The fork-config GUI's server-side validation enforces both splits
- **Overlay-written dimension files need the staged-overlay mirror**: tools that create consumer dims at runtime (viewer-server's fork/create) must ALSO write the file into the staged overlay (`<config>/custom-dimensions/overlay/dimensions/`), or fast_roller/finalise can't see the dim until the next `./dev up` re-stage

## Architecture (custom-dimensions)

```
MultiverseServer (entrypoint)
├── WorldLoaderMixin → hooks server start/stop
│   ├── MultiverseConfig.load() → reads repo-owned JSON config (read-only)
│   ├── PortalHelper.loadPortalLinks() → JSON portal state
│   └── DimensionManager.registerDimensions() → unfreezes registry, adds entries
│       (worlds are NOT created at boot — lazy creation on first player entry)
├── END_SERVER_TICK → drains pending world loads/unloads, reconciles orphans
├── ServerWorldMixin → per-tick logic
│   ├── validates portal zones (removes broken ones)
│   ├── teleports players stepping into portals
│   ├── spawns coloured particles on all portals
│   ├── immersive proximity scan → ImmersivePreloader (target world + chunks)
│   ├── ImmersiveProjector.tick → fake-block preview + cross-portal audio
│   └── EntityPassthrough.tick → items/projectiles crossing immersive zones
├── immersive/ → presentation layer, gated on portal.immersive (null = off)
│   ├── ImmersiveSettings (config record; transient on PortalDefinition)
│   ├── ImmersivePreloader → proximity world load + arrival chunk pre-gen
│   ├── ProjectionVolume → pure geometry; mirrors the teleport transform
│   ├── PlayerProjectionState → per-player fake-block slab + delta packets
│   ├── ImmersiveProjector → tick driver, chunk tickets, teardown, audio
│   └── EntityPassthrough → swept-path entity crossing (both directions)
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
├── ServerChunkLoadingManagerMixin → per-dimension structure placement
│   └── rebuilds the world's StructurePlacementCalculator with UNREGISTERED
│       placement copies (DimensionStructures) — the global registry is never
│       mutated; custom placement types pass through unchanged
│       ├── NoiseGroupPlan → which groups are active, at what profile,
│       │   radial curve and exclusion (pure; config -> type defaults)
│       ├── NoisePoolBuilder → sorts sets into groups, biome-filters against
│       │   the dimension's biome source, weights by rarity + biome affinity
│       ├── NoiseStructurePlacement → one per active group (thin shell)
│       │   └── NoiseFieldIndex → all the placement maths, no Bootstrap
│       │       └── NoiseProfile → natural/dense/sparse/cluster constants
│       │           └── StructureNoise → own Perlin (see below)
│       ├── StructureGroupRegistry → group/rarity per set + type defaults,
│       │   both jar-baked; unknown sets infer to deco + spacing rarity
│       └── FixedStructurePlacement → structures.force positions; their
│           start attempts are performed by ChunkGeneratorForcedStartMixin
│           from the ForcedStartOverride registry (biome predicate
│           bypassed, other mods' start cancels outrun — see T25)
├── MinecraftServerAccessor → server internals access
├── StructurePlacementAccessor / StructurePlacementCalculatorInvoker
│   └── placement field access + the private calculator ctor (the public
│       Stream create() would zero the concentric-ring seed)
└── SimpleRegistryAccessor → registry unfreezing
```
