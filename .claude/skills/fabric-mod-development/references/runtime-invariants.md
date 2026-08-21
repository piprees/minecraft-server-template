---
title: Runtime invariants
description: Tick-loop threading rules, ServerWorldEvents lifecycle, the c2me density-function-compiler seed trap, and sync-chunk-load hazards for Fabric mods on this server
tags: [tick-loop, ConcurrentModificationException, ServerWorldEvents, c2me, sync-load, threading]
---

# Runtime invariants

These are the rules that, when broken, produce a class of failure that looks unrelated to the change that caused it — a `ConcurrentModificationException` minutes after boot, a Distant Horizons NPE on first teleport, or an RCON command that hangs forever with `docker ps` still reporting healthy. Every one of these has happened in production or in the local loop; none of them are hypothetical.

## Tick-loop threading rule

**Never mutate the server's worlds map — or any collection vanilla iterates per tick — from a `ServerWorld.tick`/world-tick mixin.** Vanilla iterates its worlds map every tick; a mutation mid-iteration throws `ConcurrentModificationException` the moment the responsible timer fires. This is why the failure looks intermittent: it only crashes when the mutating code path actually runs during a tick, not on every boot.

Defer any such mutation to `ServerTickEvents.END_SERVER_TICK`. The pattern used in this codebase: a pending-load queue drained on `END_SERVER_TICK`, so world-adding/removing logic never runs inside the iteration itself.

```bash
# Soak assertion — the timer has to actually fire to catch this:
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
docker exec mc cat /data/logs/latest.log | grep -iE 'Unloading idle|ConcurrentModification'
```

## `ServerWorldEvents` lifecycle

**Any code path that adds a `ServerWorld` to the server's worlds map MUST fire `ServerWorldEvents.LOAD`. Any path that removes/closes one MUST fire `ServerWorldEvents.UNLOAD` before `close()`.**

Distant Horizons and c2me build their per-level state exclusively from these Fabric events — they do not poll the worlds map themselves. Skipping `LOAD` on a runtime-created dimension made Distant Horizons NPE on the first portal teleport into it and locked a player out of production. The bug is invisible in the mod that skipped the event; it surfaces as a crash in a completely different mod.

**Never call `getOrCreateDimension` synchronously from command context.** World creation there deadlocks the main thread. Queue it via `requestWorldLoad`, drained on `END_SERVER_TICK` — the same mechanism as the tick-loop rule above.

## Never sync-load a chunk

Chunk loading must always go through the async chunk manager (`getChunkManager().getWorldChunk(cx, cz, false)`, returning null rather than generating on a miss) from any tick-driven or command-driven code path. A synchronous force-generate from a world tick — or from RCON commands like `forceload add` / `execute if block` on ungenerated chunks under load — can hang the main thread forever if it collides with an unrelated worldgen bug in another mod's feature placement.

This is the "Epic Dungeons + c2me wedge" documented in this repo's known issues: a third-party mod's malformed loot table id throws during chunk feature placement, c2me's chunk-upgrade path fails once, and a main-thread sync load waiting on that same chunk then hangs indefinitely. RCON goes i/o-timeout while `docker ps` still reports the container healthy — the wedge is silent from the outside. The generalisable rule for anything you write: build in the same pattern the immersive-portals subsystem uses (deliberately reimplementing surface-height maths against already-loaded chunks rather than calling the force-generating helper) rather than trusting any convenience method to be side-effect-free.

```bash
# A wedge caused by this looks like an unresponsive server with no crash and no
# unhealthy status. If a headless RCON test hangs with no output, suspect a
# sync-load collision before suspecting the network or RCON itself.
```

## The c2me density-function-compiler (DFC) seed trap

`ServerWorldSeedMixin` overrides `ServerWorld.getSeed()` per dimension — that value feeds `NoiseConfig` (terrain, biome layout, aquifers) and structure placement. c2me's density-function compiler (`c2me-opts-dfc`) caches compiled+instantiated density functions across `NoiseConfig` creations and **ignores the seed**, so with it enabled every custom dimension silently clones the main world's terrain.

The mod's preLaunch entrypoint (`C2meConfigPatch`) forces `useDensityFunctionCompiler = false` into `c2me.toml` on every boot; the rest of c2me stays enabled. Two facts to hold:

1. **c2me reads its config at mixin-bootstrap time — before any entrypoint.** Every boot after the first is self-patched, bare `docker restart mc` included ([TROUBLESHOOTING.md#d6](../../../../TROUBLESHOOTING.md#d6)).
2. **The one gap is a fresh environment's very first boot** (new jar, no key on disk) — `deploy.sh` (step 8c) and `dev-up.sh` pre-patch as the second layer, which covers that boot on every scripted path.

**Verify by reading the file.** c2me `0.4.0-alpha.0.27` rewrites `c2me.toml` each boot with its own comment block and keeps the value:

```bash
docker exec mc grep useDensityFunctionCompiler /data/config/c2me.toml
```

`useDensityFunctionCompiler = false` is the proof. Below that pin c2me treats the key as unknown and strips it, and the proof is instead the log line `Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used`.

When testing seeds, use the locate oracle: two dimensions with different configured seeds must give different `execute in <ns>:<dim> run locate biome/structure` results; two dimensions with the same seed must match. If they don't behave this way, suspect the DFC patch didn't apply on the current boot before suspecting your own mod code.

## Persisted-state compatibility

Fields serialised into any JSON persisted across restarts (this mod's `portal_links.json`, but the rule generalises to any mod-owned state file) must stay parseable by every jar version that might read them back — deploys roll back, and older jars still need to boot cleanly against state a newer jar wrote. A field that "helpfully" swaps a plain id for a `#tag` form, or a fixed enum for a nested object, can crash-loop an older jar reading a newer file in an uncaught path. When a persisted schema changes, prefer additive fields with safe defaults on absence over changing the meaning of an existing field.

If a schema change is genuinely incompatible, delete the mod's state files under `data/config/` before restarting rather than trying to keep an old file forward-compatible — stale or half-migrated state masks bugs and creates ghosts more often than it saves time.
