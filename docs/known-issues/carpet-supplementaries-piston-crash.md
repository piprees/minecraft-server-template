# Carpet × Supplementaries: piston crash (root-caused 2026-07-25)

> **Status:** root cause found. Fixed by removing carpet from the platform
> default mod list. Not a bug in this project's code.
>
> **This file exists because the previous investigation was lost.** On
> 2026-07-14 an agent wrote `mods/.ideas/supplementaries-piston-crash.md` and
> the commit (`9432f98`) that referenced it changed **only** `mods/AGENTS.md`
> — the note itself was never committed and `mods/.ideas/` no longer exists.
> The finding evaporated, and the crash was rediscovered from scratch eleven
> days later. Investigation notes go in a tracked path.

## Symptom

Server crashes in the tick loop while a piston fires. Player is doing nothing
unusual; there is no relationship to portals, dimensions, or any in-house mod.

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

Observed twice in the wild: 2026-07-14 (near spawn, `minecraft:overworld`) and
2026-07-25 20:47 (`adventure:the_dustbowl`). Two crash reports in eleven days
of daily play looked like a race — it is not. It is **fully deterministic**
given the right redstone shape; see Reproduction below.

## Root cause

Verified by disassembling the shipped carpet jar
(`fabric-carpet-1.21-1.4.147+v240613`), not by inference.

`carpet/mixins/PistonBaseBlock_movableBEMixin` installs two **unconditional**
`@Redirect`s on `PistonBaseBlock.moveBlocks`:

| carpet method | signature | body |
|---|---|---|
| `returnNull` | matches `MovingPistonBlock.newMovingBlockEntity(BlockPos, BlockState, BlockState, Direction, boolean, boolean)` | `aconst_null; areturn` |
| `dontDoAnything` | matches `Level.setBlockEntity(BlockEntity)` | `return` |

Neither consults `CarpetSettings.movableBlockEntities`. The rule *is* checked —
in `ifHasBlockEntity`, `moveGrindstones`, `movableCMD`, `onMove` and
`setBlockEntityWithCarried` (8 references across those five) — but **not in the
two redirects**. So regardless of whether `movableBlockEntities` is enabled,
carpet always nulls the moving-piston BlockEntity and always swallows the
`setBlockEntity` call, then reinstates the correct entity itself through the
rule-aware `setBlockEntityWithCarried`.

That is coherent *on its own*. Carpet has simply replaced the call sequence.

Supplementaries wraps the **same** `setBlockEntity` call site with a MixinExtras
`@WrapOperation` (`captureBeForPistonMove`) and treats a null BlockEntity as a
hard error — it even names carpet in the message.

Supplementaries' wrapper therefore always observes carpet's null whenever a
piston moves a block that has a block entity, and throws.

**An earlier draft of this document guessed this was a mixin-ORDERING race.
The reproduction below disproved that** — it fires 100% of the time, on the
first attempt, every time. There is no timing component. What made it look
intermittent was how rarely anyone pushes a chest, not instability.

Neither mod is misbehaving in isolation; they simply implement the same
feature at the same call site. Carpet's own `movableBlockEntities` rule does
**not** reconcile them (the redirects ignore it), but Supplementaries'
`push_block_entities` does — see the workaround.

## Reproduction — one piston, one chest

Reliable and instant, which nobody had before. Both mods present,
Supplementaries' `push_block_entities` on (the shipped default):

```bash
D=minecraft:overworld
rcon "execute in $D run forceload add 248 -462 256 -456"
rcon "execute in $D run fill 248 70 -460 254 70 -460 minecraft:stone"
rcon "execute in $D run setblock 251 71 -460 minecraft:chest"
rcon "execute in $D run setblock 250 71 -460 minecraft:piston[facing=east]"
rcon "execute in $D run setblock 249 71 -460 minecraft:redstone_block"   # fires
```

The server crashes within a tick. Verified 2026-07-25 across all three arms:

| carpet | `push_block_entities` | result |
|---|---|---|
| absent | `true` | chest pushed, no crash — **shipped configuration** |
| present | `true` | **CRASH**, `RestartCount` +1, new crash report |
| present | `false` | no crash; chest not pushed (vanilla piston behaviour) |

The middle row is the bug. The bottom row is the workaround.

## Why it looked intermittent

It is not intermittent at all — it needs a piston to move a block that HAS a
block entity. In normal play that is rare: vanilla cannot push chests, so it
only happens where a player has deliberately built a contraption that relies
on Supplementaries' `push_block_entities`. Two occurrences in eleven days
matches how often somebody actually pushed a chest, not a race condition.

That is why "reproduce it" defeated the earlier attempts — they were looking
for a timing bug near spawn rather than a specific redstone shape.

## Fix

Carpet was removed from `config/modrinth-mods.txt`.

It had been promoted to a platform default in `842e9fe` — *"ops-gated
fake-player tooling the platform's own verification loop depends on"* — a
reasonable motive, but it put a **testing tool** on every player's server where
it can hard-crash the tick loop. `mods/AGENTS.md`'s own older recipe had said
"install temporarily — LOCAL ONLY, never ship"; the promotion contradicted it.

Supplementaries stays: it is content players actually want.

### Getting the Carpet bot back for verification

Add it to the **consumer overlay** for the duration of the test, then remove it:

```bash
echo 'carpet:f2mvlGrg' >> overlay/mods-extra.txt
./dev up
# ... run the bot loop (mods/AGENTS.md § 3b) ...
sed -i '' '/^carpet:/d' overlay/mods-extra.txt
./dev up
```

**If the test world has piston contraptions**, also set
`tweaks.piston_tweaks.push_block_entities: false` in
`data/config/supplementaries-common.json` for the duration. That is the
monkey-patch, and it is verified above: with Supplementaries' duplicate
feature off, carpet's redirects have nothing to collide with. The cost is
that pistons stop pushing block entities entirely — Supplementaries' feature
is gone and carpet's `movableBlockEntities` is off by default.

**Why not patch it in our own mod.** We ship mixins, so a third handler on
that call site is technically possible, but both existing ones are
`@Redirect`/`@WrapOperation` on the SAME `setBlockEntity` invocation and the
outcome already depends on their relative order. Adding a third contender to a
race, to fix a crash that only appears when a testing tool is installed, is a
worse trade than not shipping the testing tool. The config toggle achieves the
same result with no bytecode and no ordering assumptions.

## What this cost, and the lesson

Two agents before this one attributed piston crashes to **our own** block
placement. `PortalHelper.createTargetPortal` still carries
`NOTIFY_LISTENERS | FORCE_STATE` with a comment blaming Supplementaries NPEing
on neighbour cascades (`e64e87f`). That change is harmless and probably still
worth keeping, but the reasoning attached to it is not this crash: today's is an
`IllegalStateException` from a null BlockEntity, raised on a vanilla piston tick
with no portal anywhere near it.

The generalisable lesson: **when a third-party mod names the culprit in its own
error message, believe it and go and read that mod's bytecode.** The answer was
one `javap` away for eleven days.
