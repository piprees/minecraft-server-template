# Test coverage audit — custom-dimensions

> Written 2026-07-25 after a session in which four separate defects were
> diagnosed live, in game, by probing a running server over RCON. None of
> them needed a running server to find. That is the problem this document
> exists to fix.

## The headline

**Test density is inversely correlated with defect density.** 273 tests
across 25 classes, concentrated almost entirely on pure, side-effect-free
code that has never produced a defect. The stateful, world-touching classes
where every single defect has come from are the ones with no tests at all.

| Class | LOC | Tests | Defects found in it (live) |
|---|---:|---:|---|
| `ImmersiveProjector` | 1382 | **2** | leaked fake blocks, lag spike, teardown |
| `PortalHelper` | 1605 | 11 | portal self-destruction, duplicate zones, holes |
| `PortalSite` | 193 | **0** | **arrival entombed in rock** |
| `ServerWorldMixin` | 350 | **0** | **egress never re-checked on reuse** |
| `ArrivalResolver` | 142 | **0** | preview 4 blocks above the landing spot |
| `ImmersivePreloader` | 97 | **0** | preview worked once then never again |
| `DimensionConfig` | 1466 | 44 | — |
| `ProjectionVolume` | 879 | 32 | — |
| `EntityPassthrough` | 779 | 25 | — |

The two classes with the most tests have produced no live defects. The
classes with none have produced all of them.

## Why this happened

The tested set is exactly the set that is *easy* to test: pure functions
over plain values. `ProjectionVolume` was deliberately written that way
("takes plain values … and returns plain values") and got 32 tests for it.
`PortalSite` was written against `ServerWorld` directly, so it got none —
despite being the class that decides whether a player can move after they
arrive.

Testability was never the constraint. `ProjectionVolume` proves the pattern
works: **take probes as functional interfaces, keep the decision pure, and
let the caller supply the world.** Every untested class below can be brought
under test the same way, without a Minecraft runtime.

## Behaviour → coverage matrix

Behaviours as stated by the project owner, mapped to the code that owns them
and the tests that currently cover them.

| # | Required behaviour | Owning code | Covered? | Gap |
|---|---|---|---|---|
| 1 | Portals must not spawn inside rock | `PortalSite.findArrivalY` | ✅ | 23 tests incl. the entombed-column case |
| 2 | You must always be able to step out of an arrival | `PortalSite.ensureEgress` | ✅ | Unit + e2e (0/16 air cells -> 16/16 after traversal) |
| 3 | Portals must break properly when mined | `onPlayerBrokePortalBlock` | ⚠️ partial | `healPortalHole` removed; still no test that a broken pane STAYS broken |
| 4 | Surrounding blocks must stay breakable | world border + `PortalAuraManager` | ⚠️ partial | Root cause was the border, now fixed; no regression test |
| 5 | Masks must not leak geometry outside the frame | `ProjectionVolume.occluders` | ✅ good | 32 tests — the one well-covered area |
| 6 | No fake block inside/in front of a player | `ProjectionVolume.occupiedCells` | ✅ | 9 tests + e2e (`12 suppressed by bodies`) |
| 7 | Fake blocks must always be cleaned up | `ImmersiveProjector` (6 paths) | ❌ 2 tests | No test per teardown path; leaked blocks seen live |
| 8 | Client must not lag when moving past a portal | `PlayerProjectionState` + `ProjectionBudget` | ✅ | 11 unit tests + e2e; capped at 192/pass, was 984 |
| 9 | Portal presentation reflects the DESTINATION | `createTargetPortal` presentation lookup | ❌ none | Phase 7; no test that an ember→overworld arrival is not ember-coloured |
| 10 | Fake blocks must never become real | projector write paths | ❌ none | Asserted only by an in-game RCON recipe |
| 11 | Scaled portals return you where you left | `returnMapping` / `EntityTickPortalMixin` | ⚠️ | Documented as translation-free; lands a scale-8 player ~1650 blocks away |

**Updated 2026-07-25 (end of session):** 1, 2, 5, 6 and 8 are now covered by
unit tests AND e2e. 3, 4 and 11 are partial; 7, 9 and 10 remain uncovered.
Two of eleven still have zero automated coverage, down from five.

## The rule this suggests

> A class that writes to the world, or decides whether a player can move,
> does not ship without a pure, tested core.

Concretely, for each untested class: extract the decision as a pure function
over injected probes, unit test the decision exhaustively, and leave only the
thin "read world, call decision, write world" shell untested. That shell is
what the Carpet-bot e2e loop is for — and it is a much smaller surface than
what the bot is being asked to cover today.

## Priority order

1. **`PortalSite`** — owns behaviours 1 and 2, the two that trap players. 193
   lines, no tests, and a live defect this session. Highest value per line.
2. **The reuse path** (`ServerWorldMixin`, `existing != null`) — the actual
   root cause of the entombment. Egress is guaranteed only at creation.
3. **`ImmersiveProjector` teardown** — 6 documented cleanup paths, 2 tests
   total, and leaked fake blocks observed live.
4. **Phase 8c suppression** — no fake block into a player's body. Also the
   prerequisite for making the arrival preview show real geometry safely.
5. **`healPortalHole` / break interaction** — behaviours 3 and 4.

## Progress log

Suite baseline at audit time: **273 tests / 25 classes**.

| Date | Change | Suite |
|---|---|---|
| 2026-07-25 | `PortalSiteTest` (23) — arrival placement + egress, incl. the ember-fields entombment as a table case | 296 / 26 |
| 2026-07-25 | `PortalScalingContractTest` (11) — the travel transform, truncation rule, round trip, live ember-fields column pinned | 307 / 27 |
| 2026-07-25 | `BodySuppressionTest` (9) — Phase 8c, never paint a fake block into a body | 316 / 28 |
| 2026-07-25 | `ProjectionBudgetTest` (11) — packet ceiling; restores outrank sends | 327 / 29 |
| 2026-07-25 | budget WIRED into `PlayerProjectionState.send()`; e2e: 192 max/pass, 0 over ceiling, backlog drains | 327 / 29 |

**In-game verification (Carpet bot, local elfydd, 2026-07-25):** egress fix
proven by re-burying the real ember-fields portal (0/16 air cells) and
traversing — 16/16 after. 8c proven by the new mask count: `12 suppressed by
bodies` with a bot in the slab, `0` without. Boot clean, `Restarts=0`, no
mixin failures.

**Note:** carpet ships as a platform default and needs no setup. Stock carpet
crashes the server alongside Supplementaries on piston ticks; the offending
mixin is stripped automatically by `scripts/patch-mod-data.py`
(`docs/known-issues/carpet-supplementaries-piston-crash.md`).

Shipped alongside:
- `PortalSite` refactored to pure cores (`findArrivalY`, `fits`) + new
  `egressCells`, `hasEgress`, `ensureEgress`.
- **Reuse-path fix**: `ServerWorldMixin` now calls `ensureEgress` before
  teleporting into an EXISTING arrival. Root cause of the live trap — egress
  was guaranteed only at creation, so a pre-fix or later-buried arrival
  stranded the player on every visit.
- `healPortalHole` **removed** (owner decision). With
  `NetherPortalProtectionMixin` it made portals indestructible in creative.
  The mixin stays: it compensates for a non-obsidian frame and never resists
  a player.

### In progress: ImmersiveProjector (audit priority 1)

**Root cause of the lag spike is identified and evidenced.** Not a mystery —
an unbounded delta. Live, from the reporter's own session:

```
0 of 1056 maskable visible, 984 restored, 12 aperture cells overlaid
972 of 1056 maskable visible,   8 restored
```

Walking past a portal inverts the whole sightline mask, so every position the
player could see needs a correction packet in the SAME pass: ~1000
`BlockUpdateS2CPacket`s in one tick, per viewer, per portal, at the default
4-tick interval. That is the "massive lag spike lasting several seconds", and
the deferred restores are why "the fake blocks stuck around for ages".

`PlayerProjectionState`'s own javadoc asserted this could not happen — *"the
budget is fine without batching … 336 CANDIDATE positions … well under half"*.
Both numbers are stale: `previewRadius` went 2 → 4 in a later session, taking
the slab to 1056, and nothing revisited the note.

**Done:** `ProjectionBudget` — the pure decision, with the load-bearing rule
that **restores outrank sends** (a fake block still showing is a defect the
player collides with; one not yet sent is merely absent). 11 tests including
both live passes as regressions.

**Done — the wiring.** `PlayerProjectionState.send()` now CLASSIFIES the
whole volume first (the sightline probe still runs exactly once per position)
and only then spends the ceiling, restores before sends. Acting inside the
classification loop would have let iteration order decide priority.

*Verified live, 46 passes, Carpet bot circling a portal:*

```
max restores in a single pass: 192   (the ceiling)
passes EXCEEDING the ceiling:    0
max deferred in a single pass: 528   -> drains to 0 over following passes
```

Previously 984 in one tick. The mask line now carries a `N deferred to the
next pass` count, so a projection permanently behind budget (slab too big for
the interval) is visible rather than silent.

**Superseded note —** `PlayerProjectionState.send()` handles restores and
sends in one interleaved loop, so applying the ceiling greedily would let
iteration order decide priority. It needs splitting into two phases within the
pass: restores first (up to the ceiling), then sends with what remains.
Deferral is already safe under the existing invariants — a deferred restore
keeps its `lastSent` entry (which is exactly what that invariant requires),
and a deferred send leaves `lastSent` stale so the next pass re-sends it. Log
`isDeferring` as a COUNT: a projection permanently behind budget means the
slab is too big for the interval, and looks identical to a healthy one
without the number.

### Still open, in priority order
2. Phase 8c — never paint a fake block into a body. Prerequisite for fixing
   the scaled-portal preview safely.
3. `WorldBorderManager` — 93 loc, 0 tests. Per-dimension borders scaled
   against the overworld maximum.
4. Return-destination routing — which dimension a return portal sends you to,
   including chains that do not end at the overworld. `ExitTarget` has 4
   tests; nothing covers a chained return.
5. Scaled return fallback — `returnMapping` is translation-free, so a scale-8
   player without a tracked origin returns ~1650 blocks from where they left.
6. `healPortalHole` removal needs a regression test: a broken pane must STAY
   broken, and one break must take the whole aperture down.
7. Sweep the phase docs (`immersive/PHASE-*.md`) and `.ideas/` for stated
   behaviours with no assertion behind them.

## What in-game testing is still for

Visual and audio quality, parallax, and "does this feel right" — genuinely not
automatable. It should **stop** being the mechanism by which we discover that
egress is missing, that cleanup leaked, or that a mask is wrong. Those are all
decidable from a pure function and a table of cases.
