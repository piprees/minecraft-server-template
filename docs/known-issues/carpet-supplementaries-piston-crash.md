# Carpet × Supplementaries: piston crash (root-caused 2026-07-25)

> **Status:** root cause found and PATCHED AWAY. carpet still ships; the offending mixin is stripped from its jar automatically on every deploy and every local `./dev up`. Not a bug in this project's code.
>
> **This file exists because the previous investigation was lost.** On Investigation notes go in a tracked path. This crash was diagnosed once, written to an untracked file, lost, and rediscovered from scratch eleven days later.

## Symptom

Server crashes in the tick loop while a piston fires. Player is doing nothing unusual; there is no relationship to portals, dimensions, or any in-house mod.

```
java.lang.IllegalStateException: Another mod passed a null moving-piston
BlockEntity into Level.setBlockEntity during PistonBaseBlock.moveBlocks. This
is not a Supplementaries bug; some other mod is overriding
MovingPistonBlock.newMovingBlockEntity and returning null. Carpet is a known
mod that does this.
    at class_2665.wrapOperation$...$supplementaries$supp$captureBeForPistonMove
    at class_2665.method_11481   (PistonBaseBlock.moveBlocks)
    at class_3218.method_18765   (ServerLevel.tickBlock)
```

Observed twice in the wild: 2026-07-14 (near spawn, `minecraft:overworld`) and 2026-07-25 20:47 (`adventure:the_dustbowl`). Two crash reports in eleven days of daily play looked like a race — it is not. It is **fully deterministic** given the right redstone shape; see Reproduction below.

## Root cause

Verified by disassembling the shipped carpet jar (`fabric-carpet-1.21-1.4.147+v240613`), not by inference.

`carpet/mixins/PistonBaseBlock_movableBEMixin` installs two **unconditional** `@Redirect`s on `PistonBaseBlock.moveBlocks`:

| carpet method | signature | body |
| --- | --- | --- |
| `returnNull` | matches `MovingPistonBlock.newMovingBlockEntity(BlockPos, BlockState, BlockState, Direction, boolean, boolean)` | `aconst_null; areturn` |
| `dontDoAnything` | matches `Level.setBlockEntity(BlockEntity)` | `return` |

Neither consults `CarpetSettings.movableBlockEntities`. The rule _is_ checked — in `ifHasBlockEntity`, `moveGrindstones`, `movableCMD`, `onMove` and `setBlockEntityWithCarried` (8 references across those five) — but **not in the two redirects**. So regardless of whether `movableBlockEntities` is enabled, carpet always nulls the moving-piston BlockEntity and always swallows the `setBlockEntity` call, then reinstates the correct entity itself through the rule-aware `setBlockEntityWithCarried`.

That is coherent _on its own_. Carpet has simply replaced the call sequence.

Supplementaries wraps the **same** `setBlockEntity` call site with a MixinExtras `@WrapOperation` (`captureBeForPistonMove`) and treats a null BlockEntity as a hard error — it even names carpet in the message.

Supplementaries' wrapper therefore always observes carpet's null whenever a piston moves a block that has a block entity, and throws.

**An earlier draft of this document guessed this was a mixin-ORDERING race. The reproduction below disproved that** — it fires 100% of the time, on the first attempt, every time. There is no timing component. What made it look intermittent was how rarely anyone pushes a chest, not instability.

Neither mod is misbehaving in isolation; they simply implement the same feature at the same call site. Carpet's own `movableBlockEntities` rule does **not** reconcile them (the redirects ignore it), but Supplementaries' `push_block_entities` does — see the workaround.

## Reproduction — one piston, one chest

Reliable and instant, which nobody had before. Both mods present, Supplementaries' `push_block_entities` on (the shipped default):

```bash
D=minecraft:overworld
rcon "execute in $D run forceload add 248 -462 256 -456"
rcon "execute in $D run fill 248 70 -460 254 70 -460 minecraft:stone"
rcon "execute in $D run setblock 251 71 -460 minecraft:chest"
rcon "execute in $D run setblock 250 71 -460 minecraft:piston[facing=east]"
rcon "execute in $D run setblock 249 71 -460 minecraft:redstone_block"   # fires
```

The server crashes within a tick. Verified 2026-07-25 across all three arms:

| carpet               | `push_block_entities` | result                                                |
| -------------------- | --------------------- | ----------------------------------------------------- |
| absent               | `true`                | chest pushed, no crash                                |
| present, **stock**   | `true`                | **CRASH**, `RestartCount` +1, new crash report        |
| present, **stock**   | `false`               | no crash; chest not pushed (vanilla behaviour)        |
| present, **patched** | `true`                | **no crash AND chest pushed** — shipped configuration |

Row 2 is the bug. Row 3 is the config-only workaround. Row 4 is what we ship: everything works, nothing is given up.

## Why it looked intermittent

It is not intermittent at all — it needs a piston to move a block that HAS a block entity. In normal play that is rare: vanilla cannot push chests, so it only happens where a player has deliberately built a contraption that relies on Supplementaries' `push_block_entities`. Two occurrences in eleven days matches how often somebody actually pushed a chest, not a race condition.

That is why "reproduce it" defeated the earlier attempts — they were looking for a timing bug near spawn rather than a specific redstone shape.

## Fix — patch the mixin out of the jar

`scripts/patch-mod-data.py` removes `PistonBaseBlock_movableBEMixin` from `carpet.mixins.json`. That is the class owning `returnNull` and `dontDoAnything`, so with it unlisted the redirects are never applied and vanilla piston behaviour is restored. The rest of carpet — including the fake players the verification loop needs — is untouched.

The script already existed for the Epic Dungeons loot ids and is the right home: idempotent, exits 0 always, and a patched jar keeps its filename so the skip-existing download and the manifest prune both leave it alone.

It runs from `deploy.sh` on production and, **since 2026-07-25, from `dev-up.sh` locally too**. It had been production-only, so local dev had never had the Epic Dungeons repair either — local and production now repair the same jars.

What is given up: carpet's own `movableBlockEntities` rule stops working. It is off by default and duplicates Supplementaries' `push_block_entities`, so nothing is actually lost. The rule still appears in `carpet list`; it simply has no effect.

**Verified after patching:** fake player spawns and reports its position, `carpet list` works, a piston pushes a chest, no crash, `RestartCount` 0.

### If you bump the carpet pin

**This is now automated** — `smoke-test.yml` runs the reproduction on every release, so a bad pin fails CI rather than reaching a player. It asserts two different failure modes:

1. `PistonBaseBlock_movableBEMixin` is absent from the installed jar — catches the patch not running at all;
2. a piston actually pushes a chest with no crash and no restart — catches a carpet release RENAMING or splitting that mixin, where the patcher matches nothing and cheerfully reports success.

The second is the one that matters: the failure mode of a renamed mixin is the crash coming back silently, not an error from the patcher.

### Alternatives considered

**Config-only** (`push_block_entities: false`) works — row 3 above — but costs players a gameplay feature to accommodate a tooling mod. Rejected once the jar patch proved viable.

**A mixin of our own.** We ship mixins, so a third handler on that call site is possible, but both existing ones already contend for the same `setBlockEntity` invocation. Removing a participant is strictly simpler than adding one.

**Not shipping carpet.** The first fix attempted, and it works, but carpet is genuinely useful on a live server and the verification loop wants it always present. Patching the jar keeps it without the crash.

## What this cost, and the lesson

Two agents before this one attributed piston crashes to **our own** block placement. `PortalHelper.createTargetPortal` still carries `NOTIFY_LISTENERS | FORCE_STATE` with a comment blaming Supplementaries NPEing on neighbour cascades (`e64e87f`). That change is harmless and probably still worth keeping, but the reasoning attached to it is not this crash: today's is an `IllegalStateException` from a null BlockEntity, raised on a vanilla piston tick with no portal anywhere near it.

The generalisable lesson: **when a third-party mod names the culprit in its own error message, believe it and go and read that mod's bytecode.** The answer was one `javap` away for eleven days.
