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
| 1 | Portals must not spawn inside rock | `PortalSite.findArrivalY` | ❌ none | No test that a ceilinged/solid column yields `NO_SITE` and carves |
| 2 | You must always be able to step out of an arrival | `PortalSite.carveEgress` | ❌ none | No test of the egress cell set; **not called at all on the reuse path** |
| 3 | Portals must break properly when mined | `onPlayerBrokePortalBlock` + `healPortalHole` | ⚠️ partial | `ArrivalPortalEdgeTest` covers the entry edge, nothing covers break-vs-heal interaction |
| 4 | Surrounding blocks must stay breakable | `healPortalHole`, `PortalAuraManager` | ❌ none | No test that heal only touches *registered* cells |
| 5 | Masks must not leak geometry outside the frame | `ProjectionVolume.occluders` | ✅ good | 32 tests — the one well-covered area |
| 6 | No fake block inside/in front of a player | `PlayerProjectionState` | ❌ none | Phase 8c invariant unimplemented and untested |
| 7 | Fake blocks must always be cleaned up | `ImmersiveProjector` (6 paths) | ❌ 2 tests | No test per teardown path; leaked blocks seen live |
| 8 | Client must not lag when moving past a portal | `ImmersiveProjector` refresh/delta | ❌ none | No packet-volume budget test |
| 9 | Portal presentation reflects the DESTINATION | `createTargetPortal` presentation lookup | ❌ none | Phase 7; no test that an ember→overworld arrival is not ember-coloured |
| 10 | Fake blocks must never become real | projector write paths | ❌ none | Asserted only by an in-game RCON recipe |
| 11 | Scaled portals return you where you left | `returnMapping` / `EntityTickPortalMixin` | ⚠️ | Documented as translation-free; lands a scale-8 player ~1650 blocks away |

Five of eleven required behaviours have **zero** automated coverage. Two more
are partial. The one with the most coverage (#5) is the one nobody has
complained about — which is the whole point.

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

**In-game verification (Carpet bot, local elfydd, 2026-07-25):** egress fix
proven by re-burying the real ember-fields portal (0/16 air cells) and
traversing — 16/16 after. 8c proven by the new mask count: `12 suppressed by
bodies` with a bot in the slab, `0` without. Boot clean, `Restarts=0`, no
mixin failures.

**Note:** carpet ships in `config/modrinth-mods.txt` (`carpet:f2mvlGrg`), so
`mods/AGENTS.md`'s "install carpet temporarily — LOCAL ONLY, never ship"
recipe is out of date; the bot is available without installing anything.

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

### Still open, in priority order

1. `ImmersiveProjector` — 1382 loc, 2 tests. Six teardown paths, the leaked
   fake blocks, and the multi-second lag spike. Needs pure cores + a
   packet-volume budget test.
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
