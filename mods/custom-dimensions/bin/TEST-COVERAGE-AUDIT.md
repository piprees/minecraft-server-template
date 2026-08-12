# Test coverage audit — custom-dimensions

> Companion to `MANUAL-VERIFICATION.md`. That document is the short list of
> things that genuinely cannot be automated. This one says what *should* be
> automated and is not.

## The headline

**Test density is inversely correlated with defect density.** The tested set
is concentrated on pure, side-effect-free code that has never produced a
defect. The stateful, world-touching classes where the defects actually come
from are the ones that were written without tests.

| Class | LOC | Defects found in it (live) |
|---|---:|---|
| `ImmersiveProjector` | 1382 | leaked fake blocks, lag spike, teardown |
| `PortalHelper` | 1605 | portal self-destruction, duplicate zones, holes |
| `PortalSite` | 193 | arrival entombed in rock |
| `ServerWorldMixin` | 350 | egress never re-checked on reuse |
| `ArrivalResolver` | 142 | preview 4 blocks above the landing spot |
| `ImmersivePreloader` | 97 | preview worked once then never again |
| `DimensionConfig` | 1466 | — |
| `ProjectionVolume` | 879 | — |
| `EntityPassthrough` | 779 | — |

The two classes with the most tests have produced no live defects. The
classes that started with none produced all of them.

## Why this happened

The tested set is exactly the set that is *easy* to test: pure functions over
plain values. `ProjectionVolume` was deliberately written that way ("takes
plain values … and returns plain values"). `PortalSite` was written against
`ServerWorld` directly, so it got none — despite being the class that decides
whether a player can move after they arrive.

Testability was never the constraint. `ProjectionVolume` proves the pattern:
**take probes as functional interfaces, keep the decision pure, and let the
caller supply the world.** Every untested class below can be brought under
test the same way, without a Minecraft runtime.

## Behaviour → coverage matrix

Behaviours as stated by the project owner, mapped to the code that owns them
and the tests that cover them.

| # | Required behaviour | Owning code | Covered? | Notes |
|---|---|---|---|---|
| 1 | Portals must not spawn inside rock | `PortalSite.findArrivalY` | ✅ | 23 tests incl. the entombed-column case |
| 2 | You must always be able to step out of an arrival | `PortalSite.ensureEgress` | ✅ | Unit + e2e (0/16 air cells → 16/16 after traversal) |
| 3 | Portals must break properly when mined | `onPlayerBrokePortalBlock` + `PortalBreakLink` | ✅ | 17 tests + e2e both directions, persisted |
| 4 | Surrounding blocks must stay breakable | world border + `PortalAuraManager` | ✅ | `ArrivalReachability` in the boot validator; 15 unit + 4 data-driven over the real config set |
| 5 | Masks must not leak geometry outside the frame | `ProjectionVolume.occluders` | ✅ | 32 tests |
| 6 | No fake block inside/in front of a player | `ProjectionVolume.occupiedCells` | ✅ | 9 tests + e2e (`12 suppressed by bodies`) |
| 7 | Fake blocks must always be cleaned up | `ImmersiveProjector` (6 paths) | ❌ | No test per teardown path; leaked blocks seen live |
| 8 | Client must not lag when moving past a portal | `PlayerProjectionState` + `ProjectionBudget` | ✅ | 11 unit tests + e2e; capped at 192/pass, was 984 |
| 9 | Portal presentation reflects the DESTINATION | `createTargetPortal` presentation lookup | ❌ | No test that an ember→overworld arrival is not ember-coloured |
| 10 | Fake blocks must never become real | projector write paths | ❌ | Asserted only by an in-game RCON recipe |
| 11 | Scaled portals return you where you left | `returnMapping` / `EntityTickPortalMixin` | ⚠️ | Translation-free; lands a scale-8 player ~1650 blocks away |

### A failure mode this audit does not measure

`ArrivalReachability` shipped **fully written, with zero callers**. It was not
an untested class — it was an unreached one, and it never appeared as a gap
because the audit counts tests per class, not whether anything calls the class
at all. A green build and a green suite both said fine; the check simply did
not run. Worth a periodic grep for public classes nothing references.

## The rule this suggests

> A class that writes to the world, or decides whether a player can move,
> does not ship without a pure, tested core.

Concretely, for each untested class: extract the decision as a pure function
over injected probes, unit test the decision exhaustively, and leave only the
thin "read world, call decision, write world" shell untested. That shell is
what the Carpet-bot e2e loop is for — a much smaller surface than what the bot
is asked to cover today.

## Load-bearing invariants the tests pin

- **Restores outrank sends** (`ProjectionBudget`). A fake block still showing
  is a defect the player collides with; one not yet sent is merely absent.
- **Classify the whole volume before spending the ceiling.** Acting inside the
  classification loop lets iteration order decide priority.
- **A deferred restore keeps its `lastSent` entry; a deferred send leaves it
  stale** so the next pass re-sends. Deferral is safe only under that pairing.
- **`isDeferring` is logged as a COUNT.** A projection permanently behind
  budget means the slab is too big for the interval, and without the number it
  looks identical to a healthy one.

## Still open, in priority order

1. `WorldBorderManager` — 93 loc, 0 tests. Per-dimension borders scaled
   against the overworld maximum. (`ArrivalReachability` tests the ARITHMETIC
   that consumes these borders, not the class that applies them to a world.)
2. Return-destination routing — which dimension a return portal sends you to,
   including chains that do not end at the overworld. `ExitTarget` has 4
   tests; nothing covers a chained return.
3. Scaled return fallback — `returnMapping` is translation-free, so a scale-8
   player without a tracked origin returns ~1650 blocks from where they left.
4. `ImmersiveProjector` teardown — 6 documented cleanup paths, 2 tests total.
5. Sweep `mods/AGENTS.md`'s portal and immersive invariants for stated
   behaviours with no assertion behind them.

## What in-game testing is still for

Visual and audio quality, parallax, and "does this feel right" — genuinely not
automatable. It should **stop** being the mechanism by which we discover that
egress is missing, that cleanup leaked, or that a mask is wrong. Those are all
decidable from a pure function and a table of cases.

Carpet ships as a platform default and needs no setup. Stock carpet crashes
the server alongside Supplementaries on piston ticks; the offending mixin is
stripped automatically by `scripts/patch-mod-data.py`
(`docs/known-issues/carpet-supplementaries-piston-crash.md`).

Suite size is measured from `build/test-results/test/*.xml`, never by hand.
