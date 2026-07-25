# Immersive Portals — Decision Record and Agent Briefing

> **Location:** `mods/custom-dimensions/immersive/`
> **Phase docs:** `PHASE-0-*.md` through `PHASE-6-*.md` in this directory
> **Status:** Phases 0-4 and 6 shipped and verified in game; Phase 7 partly
> shipped. Phase 5 (client companion) is specified and not started.
> **Phase 8 (`PHASE-8-SOLIDITY.md`) is the highest-priority open work — it
> opens with an unresolved live defect that can trap a player.**

## What this is

An MVP immersive portal experience for the custom-dimensions mod. Players can
see through portal frames into the destination dimension, hear ambient sounds
from the other side, and throw items through. No dependency on the
ImmersivePortals mod. No client mod required for Phases 0-4 — a vanilla
client gets the whole MVP. Phase 5 specifies the things that provably
CANNOT be done server-side, and is the only part that needs one.

**Config-driven:** `"immersive": true` in a dimension's `portal` block enables it.

## For agents: how to use this document

This is the single decision record for the immersive portals feature. If you
are an agent assigned to work on any part of this feature:

1. **Read this file first** — it has the architecture decision, config schema,
   dependency graph, and risk register
2. **Read your assigned phase doc** — `PHASE-{N}-*.md` has the implementation
   checklist, verification steps, and detailed code analysis for that phase
3. **Read the mod's AGENTS.md** — `mods/AGENTS.md` has the mixin conventions,
   verification loop, and shipping rules that apply to ALL mod work
4. **Check the dependency graph** — do not start a phase until its dependencies
   are verified as shipped
5. **Ship independently** — each phase has its own shipping criteria. When your
   phase passes its criteria, it ships. Do not wait for later phases.

### What "shipped" means for a phase

A phase is shipped when:
- All implementation checklist items are done
- All verification checklist items pass
- Non-immersive portals are completely unaffected (zero behavioural change)
- All existing unit tests pass (`./gradlew test`)
- The build produces a valid remapped jar (see `mods/AGENTS.md` §1: verify
  the artefact, not the build)
- The verification loop passes (build → install → restart → RCON exercise →
  soak) per `mods/AGENTS.md` §2-4

### Collaborative rules

- **Each phase is sized for one agent session.** Don't combine phases.
- **Phase 0 must ship before any other phase starts.** It introduces the config
  schema and pre-loading infrastructure that all other phases depend on.
- **Phases 2 and 3 are independent** and can be built in parallel by different
  agents. Phase 2 requires Phase 1 (reuses its tick loop). Phase 3 requires
  only Phase 0 (pre-loaded world + config) and can start as soon as Phase 0 ships.
- **Phase 4 must be last** — it refines output from all prior phases.
- **New files go in `com.customdimensions.immersive`** package. Do not scatter
  immersive logic across existing files — keep it contained.
- **The `ImmersiveProjector.tick()` call** in `ServerWorldMixin.onTick()` is the
  single integration point with the existing codebase. All immersive logic
  flows through that entry point.
- **Never modify traversal logic.** Immersive is a PRESENTATION feature. The
  existing teleport, zone validation, ignition, and portal-link systems are
  not touched. If your phase needs traversal changes, stop and escalate.

---

## Architecture Decision

### Why server-side fake blocks, not client-side rendering

Three approaches were researched in parallel:

| Approach | Pros | Cons | Decision |
|---|---|---|---|
| **Full ImmersivePortals (stencil-buffer recursive render)** | True live view, parallax, entities visible | Archived/unmaintained, version-locks Sodium/Iris, breaks ~150 mods, requires client mod, multi-month build | **Rejected** |
| **Client-side render-to-texture** | Live view, correct lighting | Requires client mod, Sodium/Iris risk, second WorldRenderer, custom chunk streaming | **Deferred** (Phase 5, out of scope) |
| **Server-side fake block packets (immersive-cursedness technique)** | No client mod, zero Sodium/Iris risk, proven technique (shipped 1.16–1.19), ~830 lines total, fits server-authoritative architecture | No entities visible, source-dimension lighting, no block entity NBT | **Selected** |

The fake-block approach delivers ~80% of the immersive experience with ~10% of
the engineering cost. It's the right MVP.

### How it works

The server maintains a "projection volume" behind each active immersive portal.
For each position in this volume:

1. Compute the corresponding position in the target dimension (coordinate scaling)
2. Read the block state at that target-dimension position
3. Send a fake `BlockUpdateS2CPacket` to nearby players

The client renders these as real blocks. Parallax works for free because the
blocks sit at real 3D world coordinates. When the player walks away or the
portal breaks, the server sends correction packets restoring real blocks.

### What it looks like

Standing near an immersive portal, you see the terrain of the destination
dimension through the portal frame — grass, stone, trees, water, whatever is
actually generated there. As you move around, the view shifts naturally. You
hear the biome's ambient sounds. You can throw items through and they appear
on the other side. Then you walk through and transition instantly (pre-loaded
chunks, no loading pause).

### Known limitations (acceptable for MVP)

- Block entities (chests, signs) render without NBT data
- Lighting comes from the source dimension
- Entities on the other side are not visible
- Weather effects are not projected
- Biome-dependent colours (water, foliage) use the source biome's palette
- Gateway portals get particles only, not block projection

---

## Config Schema

Extends `DimensionConfig.Portal`. All fields optional.

```jsonc
{
  "portal": {
    "frameBlock": "minecraft:amethyst_block",
    "igniterItem": "minecraft:amethyst_shard",
    "color": "#9B59B6",

    "immersive": true
    // — or, with tuning: —
    // "immersive": {
    //   "enabled": true,
    //   "previewDepth": 8,       // blocks deep (default 8, max 16)
    //   "previewRadius": 2,      // blocks beyond frame edge (default 2, max 4)
    //   "refreshInterval": 4,    // ticks between updates (default 4 = 5Hz, min 2)
    //   "activationRange": 24,   // blocks from portal to activate (default 24, max 64)
    //   "audio": true,           // cross-portal ambient sound (default true)
    //   "entityPassthrough": true // items/projectiles pass through (default true)
    // }
  }
}
```

`immersive` is boot-re-read (not creation-time). Changes apply without a wipe.
NOT serialised into `portal_links.json` zone records.

---

## Dependency Graph

```
Phase 0 (Config + Pre-loading)
  │
  ├──► Phase 1 (Portal Preview — hero feature)
  │      │
  │      ├──► Phase 2 (Audio — reuses Phase 1 tick loop)
  │      │
  │      └──► Phase 4 (Polish — refines Phases 1, 2, 3)
  │
  └──► Phase 3 (Entity Pass-Through — independent of Phase 1)
         │
         └──► Phase 4
```

**Critical path:** 0 → 1 → 4
**Parallel:** Phase 3 can start after Phase 0. Phase 2 can start after Phase 1.
Both are independent of each other.

---

## Phase Summary

| Phase | Doc | Goal | Size | New Files | Risk |
|---|---|---|---|---|---|
| 0 | `PHASE-0-INSTANT-TRANSITION.md` | Config parsing + pre-load target world/chunks on approach | S | 1 | Low |
| 1 | `PHASE-1-PORTAL-PREVIEW.md` | Fake block projection through portal frame (hero feature) | M | 3 | Medium |
| 2 | `PHASE-2-CROSS-PORTAL-AUDIO.md` | Biome ambience + weather sounds leak through portal | S | 0 | Low |
| 3 | `PHASE-3-ENTITY-PASSTHROUGH.md` | Items, projectiles, XP orbs pass through with velocity | S | 0 | Medium |
| 4 | `PHASE-4-POLISH.md` | Lighting, edge particles, smart throttling, gateway hints | S | 0 | Low |
| 5 | `PHASE-5-CLIENT-COMPANION.md` | Client mod: loading screen, portal transparency, ghost entities, real lighting/biome colour | L | n/a | High |
| 6 | `PHASE-6-AURA-POLICY.md` | `aura.subsume` policy — may an aura convert player-placed blocks? Claims as a hard gate | M | n/a | Medium | **Shipped** |
| 7 | `PHASE-7-PORTAL-IDENTITY.md` | Presentation describes where a portal GOES; overworld identity, End fill-activation | M | n/a | Medium | Partly shipped |
| 8 | `PHASE-8-SOLIDITY.md` | Fake blocks must never trap a player — open live defect, highest priority | S | n/a | **High** |

**Total:** ~830 new lines across 4 new files + 5 modified files.

---

## Files Created by This Feature

All new files live in `com.customdimensions.immersive`:

| File | Phase | Purpose |
|---|---|---|
| `ImmersiveSettings.java` | 0 | Config record (depth, radius, interval, range, audio, entity) |
| `ImmersivePreloader.java` | 0 | Pre-generate arrival chunks on proximity |
| `ProjectionVolume.java` | 1 | Compute source→target position mapping for the projection slab |
| `PlayerProjectionState.java` | 1 | Per-player per-portal fake-block tracking + packet sending |
| `ImmersiveProjector.java` | 1 | Main tick loop: activate/deactivate/refresh projections + audio |

## Files Modified by This Feature

| File | Phase(s) | What changes |
|---|---|---|
| `DimensionConfig.java` | 0 | Add `immersive` field to `Portal` inner class |
| `PortalDefinition.java` | 0 | Add `ImmersiveSettings` getter |
| `ServerWorldMixin.java` | 0, 1, 3 | Proximity check, `ImmersiveProjector.tick()` call, entity scan |
| `MultiverseServer.java` | 1 | Disconnect cleanup hook for `ImmersiveProjector` |
| `WorldLoaderMixin.java` | 0, 1 | `clear()` calls at shutdown |
| `EntityTickPortalMixin.java` | 3 | Non-player entity return teleport in arrival portals |

---

## Risk Register

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| Fake block packets cause client desync | Medium | Low | Cleanup on zone removal, disconnect, world unload; client self-corrects on chunk reload |
| Performance: 336-block refresh at 5Hz | Low | Low | Configurable; delta updates near-empty in steady state; only active near players |
| 1.21.1 packet format differs from 1.19 ref | Low | High | Expected; verify `ChunkDeltaUpdateS2CPacket` constructor; fallback to individual `BlockUpdateS2CPacket` |
| Target chunks not generated at projection time | Low | Medium | Phase 0 pre-loads; ungenerated positions show source blocks |
| Ender pearl chain teleport | Medium | Medium | Test explicitly; may need special-case |
| Leaked fake blocks on crash | Low | Low | Visual-only; vanish on relog |
| Projection overlaps player builds | Low | Medium | Visual-only; mining/placing works against real blocks |

---

## Agent Gotchas — Read Before Implementing

These are traps that will waste your time if you don't know about them upfront.
They come from the existing codebase, the ~150-mod server environment, and
1.21.1 API specifics.

### 1. The server runs packet-fixer

`packet-fixer` (Modrinth slug `packet-fixer`) is in the active server mod list.
It patches various vanilla packet-handling bugs. Before sending fake
`BlockUpdateS2CPacket` or `ChunkDeltaUpdateS2CPacket` packets in Phase 1,
verify that packet-fixer doesn't intercept, rewrite, or drop them. Test by
checking whether a single manually-sent `BlockUpdateS2CPacket` actually renders
on the client. If packet-fixer strips it, you may need to use
`player.networkHandler.sendPacket()` directly rather than going through any
higher-level API.

### 2. Supplementaries piston crash (already documented but easy to forget)

`PortalHelper.createTargetPortal()` already uses `NOTIFY_LISTENERS | FORCE_STATE`
(not `NOTIFY_ALL`) specifically because Supplementaries' `captureBeForPistonMove`
mixin NPEs when `NOTIFY_NEIGHBORS` cascades to adjacent pistons. The fake block
packets in Phase 1 are CLIENT-ONLY (never placed in the world), so this doesn't
apply — but if you ever need to place a real block as part of immersive logic
(Phase 4a's LIGHT blocks are fake, so they're safe), use the same flags.

### 3. c2me and chunk generation threading

c2me rewrites chunk generation to be multi-threaded. `ServerWorld.getChunk()`
in `ImmersivePreloader` (Phase 0b) calls into the chunk system, which under
c2me may behave differently under contention. The call is safe from
`ServerWorldMixin.onTick()` (server thread), but never call it from a
background thread or an async callback. The existing `findSurfaceY()` in
`PortalHelper` already does the same `getChunk()` call from the server thread —
follow that pattern.

### 4. Distant Horizons per-world state

DH builds per-level state from `ServerWorldEvents.LOAD`/`UNLOAD`. Phase 0's
proximity pre-loading triggers `DimensionManager.requestWorldLoad()` which
fires `LOAD` — DH will see this and start building its state for the new world.
This is correct and expected. Do NOT suppress or delay the `LOAD` event.

However: if the world is subsequently idle-unloaded (5-minute timeout) and
re-loaded, DH sees a second `LOAD`. This is also correct (DH handles it), but
it means the pre-loader should not keep re-triggering `requestWorldLoad()` for
already-loaded worlds — the `preloaded` set in `ImmersivePreloader` handles
this, but make sure it's keyed on the ZONE, not the world (multiple portals can
target the same world).

### 5. Source portal zones have NO portal blocks

This is documented in `mods/AGENTS.md` but bears repeating because it breaks
intuition. Source-side portal zones are invisible — they have no NETHER_PORTAL
or END_PORTAL blocks. Only ARRIVAL portals have real portal blocks. This means:

- Phase 1's projection volume extends BEHIND the portal frame on the source
  side, where there are normal world blocks (dirt, air, etc.). The projection
  overwrites those visually. This is fine.
- Phase 3's entity scan must check position against zone INTERIORS, not
  against portal block types. Entities standing in the source zone are standing
  in AIR (or the igniter's particles), not in a portal block.
- `EntityTickPortalMixin.tickPortalTeleportation()` only fires when an entity
  is IN a portal block — so it only handles the arrival-side return path, not
  the source-side initial pass-through.

### 6. The namespace guard pattern

Every mixin that does path-based dimension lookups MUST check
`MultiverseConfig.getInstance().isManagedNamespace(key.getNamespace())`
FIRST. Without this, a dimension from another mod whose PATH happens to
match one of our slugs gets incorrectly treated as ours. See
`PeacefulDimensionSpawnMixin`, `MobSpawnMixin`, `GameEventSuppressionMixin`
for the established pattern. Phase 3's entity scan in `ServerWorldMixin`
doesn't need this (it iterates `PORTAL_ZONES` which are only populated for
our dimensions), but if you add any new mixin that resolves config by world
key path, apply the guard.

### 7. GameEventSuppressionMixin and empty target worlds

Phase 0 pre-loads the target world, but it may have zero players (the player
is still in the source world). `GameEventSuppressionMixin` suppresses ALL
game events in managed worlds with no players. This means: if Phase 2's
audio relay tries to use game events in the empty target world, they'll be
silently dropped. Use `world.playSound()` (network packets, not game events)
for all audio — this is already the plan, but don't accidentally switch to a
game-event-based approach.

### 8. NetherPortalProtectionMixin and fake blocks

`NetherPortalProtectionMixin` prevents vanilla from popping REGISTERED custom
portal blocks on neighbour updates. Phase 1's fake blocks are NOT placed in
the world (they're client-side visual only), so they never trigger neighbour
updates and this mixin is irrelevant. But: if a fake block packet happens to
land at the same position as a real portal block in an overlapping zone
(unlikely but possible with anchor portals), the cleanup must restore the
REAL portal block state, not AIR. `cleanup()` should use
`world.getBlockState(pos)` to get the actual state, which will correctly
return the real portal block.

### 9. `portal_links.json` serialisation boundary

`ImmersiveSettings` is NOT serialised into zone records in `portal_links.json`.
This is deliberate — immersive state is re-read from dimension config every
boot. Zone records carry `PortalDefinition` (which includes `frameBlock`,
`anchorPos`, `singleUse`, `aura` palettes, etc.) but NOT immersive settings.

The downgrade-parseability rule from `mods/AGENTS.md` still applies: if you
add ANY new field to `PortalDefinition` or `StoredPortalZone`, older jars must
ignore it gracefully. Since `ImmersiveSettings` is transient on
`PortalDefinition` (not serialised), this is automatically satisfied — but
don't accidentally add it to the Gson-serialised fields.

### 10. Entity.teleport() in 1.21.1 — the cross-dimension entity identity question

In 1.21.1, `Entity.teleport(ServerWorld, double, double, double, Set, float,
float)` for cross-dimension moves may recreate the entity in the target world.
After the call, the original entity reference may point to a removed entity.
Phase 3 must handle this:

```java
// WRONG — entity may be removed after cross-dimension teleport
entity.teleport(targetWorld, tx, ty, tz, Set.of(), yaw, pitch);
entity.setVelocity(velocity);  // might set velocity on a dead entity

// RIGHT — look up the entity in the target world by UUID
UUID id = entity.getUuid();
entity.teleport(targetWorld, tx, ty, tz, Set.of(), yaw, pitch);
Entity arrived = targetWorld.getEntity(id);
if (arrived != null) {
    arrived.setVelocity(velocity);
    arrived.velocityModified = true;
}
```

Verify which pattern works by testing with an `ItemEntity` — if
`setVelocity()` on the original reference has no effect, the UUID lookup is
required.

### 11. The idle unloader will close pre-loaded worlds

`DimensionManager.unloadIdleDimensions()` runs every 1200 ticks (1 minute)
and closes worlds with no players for 5+ minutes. A world pre-loaded by
Phase 0 but never entered will be closed after the idle timeout. This is
correct behaviour — the pre-loader will re-load it on next approach. But:

- `ImmersivePreloader.preloaded` set must be cleared for that zone when the
  world is unloaded, or the next approach won't re-trigger pre-loading.
  Hook into the existing `ServerWorldEvents.UNLOAD` listener, or simply key
  the preloaded set on the world key + zone key and let
  `DimensionManager.closeWorld()` (which fires `UNLOAD`) be the natural
  invalidation signal.
- `ImmersiveProjector` cleanup must handle the target world becoming null
  mid-session (world unloaded while projection is active). Check for null
  target world in every refresh cycle.

### 12. Tick ordering in ServerWorldMixin

The existing tick order in `ServerWorldMixin.onTick()` is load-bearing:

```
1. restoreZones          — claim pending zones from portal_links.json
2. validity check loop   — remove broken zones, single-use countdown
3. particle spawn        — zone interior particles
4. player proximity loop — detect zone entry, trigger player teleport
5. target portal particles
6. ExitPortalManager.tick
7. PortalAuraManager.tick
8. ImmersiveProjector.tick   ← Phase 1 adds this HERE
9. ExitConditions.tick
10. ExitShrineManager.processQueued
11. DimensionManager.updatePlayerPresence
```

Phase 1's projector tick MUST come after step 4 (player teleport), because a
player who teleports this tick should NOT also get a projection update for the
zone they just left (they're in the target world now). It should come after
step 7 (aura manager) because the aura manager modifies blocks near portals
and the projector should see the post-aura state.

Phase 3's entity scan should go after the projector tick (step 8), as a new
sub-step — entities passing through should see the same zone state the
projector used.

### 13. Build system: new package = no new configuration

New files in `com.customdimensions.immersive` require NO changes to
`build.gradle`, `fabric.mod.json`, or `customdimensions.mixins.json` — they're
plain Java classes, not mixins or entrypoints. The existing `server` entrypoint
(`MultiverseServer`) bootstraps everything. New mixins WOULD need to be added
to `customdimensions.mixins.json` — but none of the five phases introduce new
mixin classes (all changes are to existing mixins).

---

## Open Questions

1. **Translucent blocks in projection?** Recommend: include them. Water through
   a portal to an ocean dim is a great effect.
2. **Block entity rendering?** Chests appear without contents. Acceptable for MVP.
3. **Optimal preview depth?** Default 8 is the sweet spot. 16 shows too many
   lighting artefacts. 4 feels too shallow.
4. **Arrival-side projection?** (See home through the arrival portal.) Deferred
   to post-MVP — doubles the projection count.

---

## Briefing for the next agent (start here)

Phases 0–4 are shipped. Phase 5 (`PHASE-5-CLIENT-COMPANION.md`) is specified
but not started, and is the only planned work that needs a client mod.

**Read these before touching anything:** this file, your phase doc,
`mods/AGENTS.md` (the Portal system section now carries the immersive contract
and its verification recipes), and the source of `ImmersiveProjector`,
`PlayerProjectionState`, `ProjectionVolume`, `ArrivalResolver` and
`EntityPassthrough` — the class comments in those files are where the hard-won
reasoning lives, and several of them exist specifically to stop a future change
reintroducing a bug that cost real debugging time.

### The five rules this feature runs on

1. **Never sync-load a chunk from a tick path.** Every target-world read goes
   through `getChunkManager().getWorldChunk(cx, cz, false)`; null means skip.
   `PortalHelper.findSurfaceY` force-generates, which is why `ArrivalResolver`
   reimplements its maths on a loaded chunk. Sync-generating from the world
   tick is the documented Epic Dungeons + c2me wedge.
2. **`lastSent` is exactly what the client is showing**, and nothing leaves it
   without a correction packet having gone out. Every fake-block bug in this
   feature has been a violation of that sentence.
3. **Resolve "where is the other side" through `ArrivalResolver`, never the
   heightmap directly.** The mod's own arrival frame raises the heightmap it
   would read.
4. **Distinguish air from unknown.** An unloaded chunk reads as "no block"
   exactly like air does, and conflating them silently degrades output.
5. **Gate everything on `getImmersive() != null`.** Non-immersive portals must
   take zero new code paths.

### How to verify (and how not to)

The headless loop — build, install into the local consumer's `data/mods/`,
`docker stop mc && docker start mc`, drive a Carpet bot over RCON, grep logs —
is in `mods/AGENTS.md` and it works. Use it. But understand its ceiling:

**Every single defect in this feature was invisible to a green build and a
green test suite.** Not one was a crash. They were all *silent absence* — the
feature quietly not happening, with no error anywhere. Build-green and
tests-green would have shipped every one of them.

Worse, one headless test **passed for the wrong reason**: the return-trip test
teleported a bot out of the arrival portal and back in, which happens to be
the only sequence that clears vanilla's pinned portal cooldown. It verified the
mechanism while completely missing that a human arriving by portal never gets
that sequence — and the reporter was stranded twice. When you write a test,
ask whether it models how a player actually behaves, not just whether the code
path executes.

Two practical consequences:

- **Log counts, not just events.** The chunk-ticket bug and the arrival-
  resolution bug were both only visible because something downstream logged
  *numbers* (projected block count, air/solid tallies). An "activated" line
  alone looked perfectly healthy in three separate broken states.
- **Capture a baseline before you change behaviour.** The entity pass-through
  work was only falsifiable because the "nothing crosses" state was recorded
  first.

### Getting a human to test it

This feature's remaining risk is visual, and a human found in one session what
headless testing had not: the projection leaking outside the frame, lighting
that swam as the player walked, particles obscuring the view, the aura seeding
the portal's own building material, and the cooldown trap. Screenshots were
worth more than any log.

Set expectations honestly when you hand something over — the tester reasonably
read a working Phase 0 as broken because the doc promised "instant" and he saw
a loading screen. Say what a change will and will not look like.

### Ghosts: restart the server before believing a visual bug report

Fake blocks live on the client until it reloads those chunks. If the server
restarts while a player is connected, anything currently projected is
**orphaned** — the server comes back with no record of it and never sends a
correction. Shutdown now restores properly, so an orderly restart is clean,
but a hard crash still leaves residue and older jars left plenty.

Orphaned blocks look EXACTLY like a live masking failure: destination
geometry floating outside the frame. This cost real diagnosis time twice.

Before treating a screenshot as a regression, check whether a projection was
even active:

```bash
docker exec mc sh -c 'grep -E "immersive: projection (activated|cleared)" /data/logs/latest.log | tail'
docker inspect mc --format '{{.State.StartedAt}}'
# and confirm the position server-side:
docker exec -i mc rcon-cli 'execute in <world> if block <x> <y> <z> minecraft:air'
```

Server says AIR + no recent activation = ghosts. The remedy is a client chunk
reload (F3+A or relog), not a code change.

### A trap that will waste your time

Portal coordinates are frequently negative on a real world. `floor(-424.5)` is
`-425`, not `-424`. An RCON `summon` at `z=-424.5` lands OUTSIDE a portal plane
at `z=-424`. This produced a false regression report during entity testing —
mobs "passed" the same broken test only because they wandered in on their own.

## What live testing found that the plan did not

Every defect in this feature had the same signature: **build green, tests
green, feature silently not happening**. None were reachable by code review.
If you extend this feature, budget for the live loop — it is not optional
here.

| Found | Symptom | Cause |
|---|---|---|
| Phase 0 | Immersive silently off for every portal after a restart | `ImmersiveSettings` is transient (correct), but restored zones deserialise their definition from `portal_links.json`, so it came back null. `restoreZones` now re-stamps from live config. |
| Phase 1 | Preview worked exactly once, then never again | The projector refuses to load chunks (correct) while the pre-loader's dedupe only cleared on world UNLOAD — but only the *chunks* had unloaded. Fixed with a chunk ticket, expiry-backed. |
| Phase 1 | Relog in range showed nothing | Stale `lastSent` made the delta pass a no-op against a client holding fresh real blocks. JOIN now drops the state. |
| Phase 2 | Weather relay never fired | Per-dimension weather does not exist: every world shares one save-wide flag. Cut as unreachable code. |
| Phase 4 | Preview ~4 blocks above where players land, then shrunk to depth 2 | The mod's own arrival frame raises the heightmap the preview sampled; the player path lands at the existing portal instead. Fixed with a shared `ArrivalResolver`. |

Two of these (the Phase 1 ticket, the Phase 4 arrival) were only visible
because something downstream *reported numbers* — the projected-block count
and 4e's air/solid counts. Log the counts, not just the events.

## Verification Strategy

This is the mod's first inherently visual feature. The existing verification
loop (RCON + Carpet bot) covers server-side mechanics. Visual verification
requires a human in-game.

| What | How | Automated? |
|---|---|---|
| Config parsing | Unit test (`ImmersiveSettingsTest`) | Yes |
| Pre-loading | Carpet bot + log grep | Yes |
| Projection geometry | Unit test (`ImmersiveProjectionTest`) | Yes |
| Cleanup reliability | Carpet bot disconnect/zone-break + log grep | Yes |
| Entity pass-through | RCON summon + kill count in target dim | Yes |
| Visual correctness | Human walks around the portal | **No** |
| Parallax | Human moves and observes | **No** |
| Audio quality | Human listens near portal | **No** |
| Regression | Existing unit tests + Carpet bot portal traversal | Yes |

---

## Session notes — 2026-07-25 (Phase 8 shipped, Phase 9 opened)

Four defects, one root cause each, all found in one session. Read these before
touching portals again; three of them cost hours because a document in this
directory asserted something false.

### Corrections to THIS document and PHASE-8

- **`F3+A` does not clear fake blocks.** `PLAN.md § Ghosts` and
  `PHASE-8-SOLIDITY.md` both say it does. It is `WorldRenderer.reload()`: it
  rebuilds render meshes from the client's local `ClientWorld` and never
  re-requests chunk data. Fake blocks arrive as `BlockUpdateS2CPacket`, land in
  `ClientWorld`, and survive it. **Use a relog**, or step beyond view distance
  and back. The PHASE-8 "decisive test" built on F3+A cannot discriminate and
  gave a misleading answer.
- **The authoritative discriminator is a server probe plus a human report,
  together.** `execute in <dim> if block <x> <y> <z> minecraft:air` tells you
  what is REAL. The player tells you what is RENDERED. Divergence is the whole
  point of a projection, so neither half decides alone.
- **An RCON `setblock` is not a player break.** Player breaks fire
  `PlayerBlockBreakEvents`; `setblock` does not. Any break-triggered logic is
  invisible to an RCON-only test — a 60-second "the block stayed air" probe
  proved nothing about the reported symptom.
- **Mod DEBUG logging is `CUSTOMDIM_LOG_LEVEL` in `.env`**, read by
  `log4j2-adventure.xml` via log4j2's Environment Lookup. The old recipe
  patched that file inside the `stack-config` volume, which every seed run
  reverts (trap #12) — the diagnostics vanished mid-session on the next
  `./dev up`. Never re-introduce a post-install patch for this.
- **Carpet ships, and is patched on the way in.** Stock carpet
  unconditionally nulls the moving-piston BlockEntity; Supplementaries
  hard-errors on that, so any piston moving a block entity crashed the tick
  loop (twice in the wild, deterministic once you know the shape).
  `scripts/patch-mod-data.py` strips the one offending mixin from the jar on
  every deploy and every `./dev up`, so the bot is always available and
  nothing is given up. Root-caused from bytecode in
  `docs/known-issues/carpet-supplementaries-piston-crash.md`; re-run its
  repro if the carpet pin moves.

### The scale inversion — the big one

`portal.scale` is the Nether ratio as people say it: **"8 nether : 1 over"**.
One block in the DESTINATION is worth `scale` at home, so **entering divides,
returning multiplies**. The code multiplied on entry.

Consequence: an overworld portal at (236, −453) into a `scale: 8` dimension
arrived at (1888, −3624) instead of (30, −57) — outside that dimension's own
1024 border, where **vanilla forbids breaking or placing any block**. The
player could not mine the rock around them, the frame, or the portal. The
symptom looks exactly like a protection mixin or a fake-block bug. It is
neither.

Three sources agreed on the ratio reading and the code agreed with none: 52
configs use whole ratios and none uses a fraction; every border is authored as
`overworldBorder / scale`; vanilla's own `coordinate_scale` for the Nether is
8. One README line said "0.125 for nether-style 1:8" and two unit tests had
been written to match it — that line was the origin of the whole mess. It is
corrected and now carries a worked example.

**Do not re-derive this.** If a fractional `portal.scale` ever appears in a
config, it means a sprawling dimension, and it is almost certainly a mistake.

### Testing lessons

- **Test the requirement, not the implementation.** A "live regression case"
  was written pinning `236 × 8 = 1888` as correct, from observed behaviour.
  That immortalised the bug and would have blocked its own fix. Derive
  expectations from the docs and the config, then let the test fail.
- **Test density was inversely correlated with defect density.** 273 tests sat
  on config parsing and pure geometry; `PortalSite` (arrival placement and
  egress) and `WorldBorderManager` (the border that caused all this) had
  **zero**. See `../TEST-COVERAGE-AUDIT.md` for the matrix and the rule that
  came out of it: *a class that writes to the world, or decides whether a
  player can move, does not ship without a pure, tested core.*
- The pattern that makes that possible is `ProjectionVolume`'s: take probes as
  functional interfaces, keep the decision pure, let the caller supply the
  world. `PortalSite` was refactored to it and went 0 → 23 tests.

### Live-loop gotchas that cost time

- **`floor(-453.5)` is `-454`.** A bot tp'd to `z=-453.5` stands one block
  OUTSIDE a portal plane at `z=-453`. This produced a false "the bot won't
  traverse" for several attempts. Documented in this file already; it still
  caught us.
- Worlds are lazily unloaded, so `execute in <dim> …` answers
  *"Unknown dimension"* until something loads it. Not a failure.
- `worldborder get` reads the shared vanilla border, **not** the mod's
  per-world one. Trust `WorldBorderManager`'s boot log line instead:
  `World border for <dim>: radius N (from config)`.
- `strings` on a jar entry silently produced nothing; **use `javap -p
  -classpath <jar>`** to prove a method reached the artefact.
- A `git checkout --` revert of one file will happily delete a method another
  file still calls. Build after every revert.

### What "shipped" cost, for planning

Phase 8 took one session including diagnosis: 273 → 316 tests, three new test
classes, and an e2e proof driven with a Carpet bot (re-bury the real portal →
0/16 air cells → traverse → 16/16). The e2e loop is genuinely fast once the
bot is up; the expensive part was the false leads above.
