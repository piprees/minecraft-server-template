---
title: Known issues
description: Standing watch list of recurring server-side failure modes — symptoms, diagnosis, and recovery
tags: [c2me, epic-dungeons, wedge, carpet, supplementaries, piston, unmined-render, chunk-corruption]
---

# Known issues (watch list)

This is a living list of failure modes that have already happened at least
once, with enough detail to recognise them fast the second time. Extend it
when a new one gets root-caused — that's cheaper than re-discovering it.

## Epic Dungeons loot ids crash feature placement → c2me wedges the main thread

**First seen 2026-07-23 (local); second trigger found 2026-07-24.**

`epic:chests/DungeonZombie` (uppercase — an invalid identifier path) throws
`Non [a-z0-9/._-] character in path` during chunk feature placement when an
Epic Dungeons dungeon generates. Under c2me, the chunk upgrade fails once
(`Error upgrading chunk [x, z] to "minecraft:features"`), and a main-thread
sync load waiting on that chunk — an RCON `forceload add`, an
`execute if block` on an ungenerated chunk — then **hangs forever**: RCON goes
i/o-timeout while `docker ps` keeps reporting healthy.

Two triggers produce this:

1. A dungeon generating naturally under a sync chunk load (forceload, some
   `execute` forms).
2. Deleting a runtime dimension's **world directory** without scrubbing its
   `level.dat` entry first — the next boot re-creates the dimension and
   regenerates its spawn chunks, and if a dungeon lands there, **the boot
   itself wedges**. Hit twice on 2026-07-24 cleaning up fixture dimensions.
   The correct procedure (level.dat scrub before deleting anything) is in
   `TROUBLESHOOTING.md#d3`.

**Diagnosis caveat — do not over-trust one signal.** spark's
`Timed out waiting for world statistics` alone is **not** proof of this wedge.
It also fires through legitimately heavy boots (mass dimension creation can
run 10+ minutes without anything being wrong). Confirm the wedge specifically
with:

- `Error upgrading chunk` and `DungeonZombie` counts in the log
  (`./scripts/game-log.sh mc --grep 'Error upgrading chunk|DungeonZombie'`)
- Whether the log has genuinely **stopped advancing** — a heavy-but-healthy
  boot keeps producing new lines; a wedge doesn't.

**Recovery:** `docker stop -t 90 mc && docker start mc` (local only —
production restarts go through `deploy.sh`, never a raw `docker restart`).
If the trigger was a regenerating deleted dimension, you must also do the
level.dat scrub (`TROUBLESHOOTING.md#d3`) or it wedges again
on every subsequent boot.

Upstream candidate — the loot table id is Epic Dungeons' own data bug, not
this project's.

## c2me `TheChunkSystem` ConcurrentModificationException

Log signature: `Error executing task on Chunk source main thread executor for
<dim>` … `TheChunkSystem.lambda$onItemUpgrade$0`.

c2me 0.4.0-alpha's chunk-system rewrite races vanilla's entity manager during
heavy multi-dimension chunk activity (boot world creation, map-render initial
loads, forceloads). **Non-fatal** — the executor catches it — but bursts of it
correlate with degraded TPS during boots.

Do **not** filter this out of logs (it's a real error and an upstream
candidate for a c2me fix). If it starts actually crashing servers rather than
just logging, the only mitigation is removing c2me entirely — revisit on c2me
version bumps.

## Carpet × Supplementaries piston crash — root-caused and patched away

`java.lang.IllegalStateException: Another mod passed a null moving-piston
BlockEntity...` when a piston moves a block that has a block entity (e.g.
pushing a chest). Fully root-caused, reproduced deterministically, and fixed
by patching the offending mixin (`PistonBaseBlock_movableBEMixin`) out of the
shipped carpet jar — `scripts/patch-mod-data.py`, run from both `deploy.sh`
and `dev-up.sh`. **Not a bug in this project's code**, and not something to
re-investigate — the full write-up (root cause, reproduction steps, and why it
looked intermittent for eleven days) is in
`docs/known-issues/carpet-supplementaries-piston-crash.md`. If a carpet
version bump ever brings this back, `smoke-test.yml` catches it in CI before
it reaches a player — read that doc before assuming a new investigation is
needed.

## The map renderer

The map is rendered by the `unmined-render` sidecar (uNmINeD CLI) into static
tiles. There is no RCON interface and no live link to mc — it keeps serving
while mc restarts or autopauses. Renders are incremental, so a quiet pass
usually means nothing changed rather than something failed. Inspect it with:

```bash
docker logs unmined-render --tail 30
./scripts/map-render.sh status     # container state + recent render activity
./scripts/map-render.sh render     # force a pass (restarts the sidecar; a
                                    # restart triggers an immediate render)
```

There is still no RCON interface either way, and renders are incremental —
forcing a pass is cheap. If this drift gets fixed upstream (the stale docs
corrected), this entry can be removed.
