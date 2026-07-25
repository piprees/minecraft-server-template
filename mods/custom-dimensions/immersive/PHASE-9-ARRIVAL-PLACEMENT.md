# Phase 9 — Arrival placement must be reachable, viable, and symmetric

> **Status:** root cause FIXED 2026-07-25 (entry now divides by `scale`);
> 9a, 9b and 9c remain. The defect below is written as it was found — read
> "Scale semantics: RESOLVED" before acting on the numbers in it.
> **Priority:** high. The arithmetic is fixed, but nothing yet VALIDATES that
> a chosen arrival is reachable, viable, and symmetric.
> **Depends on:** Phase 8 (shipped) — `PortalSite.ensureEgress` and
> `PortalSite.hasEgress` are the building blocks this phase extends.

## The defect

Arrival placement was `target = source × scale` — the wrong direction — and
nothing else. It never asks whether the resulting coordinate is somewhere a
player can actually exist. Three independent ways that fails; (1) is fixed at
the root, (2) and (3) remain:

1. **Outside the destination's world border.** Vanilla forbids breaking AND
   placing blocks outside the border. A player arriving there can do nothing
   at all — the portal frame, the portal itself, and every surrounding block
   are inert.
2. **Inside solid terrain.** Partly addressed in Phase 8 (`ensureEgress`),
   but egress is carved *after* the site is chosen, so a bad site is still a
   bad site.
3. **Not symmetric.** Breaking a portal on one side leaves the other side
   standing.

### Evidence (local, 2026-07-25)

Reported in game: *"Still can't break ANY of the blocks around the portal,
the portal frame, the portal itself, anything… Tried breaking the portal in
the overworld, it broke properly."*

```
overworld portal (236, −453) × scale 8.0  →  arrival (1888, −3624)
adventure:the_ember_fields player border  →  radius 1024
arrival distance from origin              →  3624   (2600 outside)
```

Confirmed by the boot log — the border is real, not theoretical:

```
[13:52:30] World border for adventure:the_ember_fields: radius 1024 (from config)
```

**This was systemic, not one dimension.** Under the old multiply-on-entry,
a dimension needed `border ≥ 8192 × scale`, and almost none had it. Under the
corrected divide-on-entry the requirement inverts to `border ≥ 8192 / scale`,
which is exactly how all 74 non-pocket dimensions are already authored — so
the table below is now a record of what WAS broken, not what is:

| | count |
|---|---:|
| Reachable for every overworld portal | **22** |
| **Can strand a player outside the border** | **58** |
| Anchor dims (fixed arrival — exempt) | 1 |

Worst cases — the usable overworld radius before arrivals go out of bounds:

| dimension | scale | border | usable overworld radius |
|---|---:|---:|---:|
| `the_slatemouth`, `the_shallows`, `the_dustbowl`, +5 more | 16 | 512 | **32 blocks** |
| `the_forged_depths`, `the_pale_reach`, +3 more | 12 | 683 | **57 blocks** |
| `the_ember_fields`, `the_ashgrove`, +30 more | 8 | 1024 | **128 blocks** |
| `the_crucible`, `the_gauntlet` | 4 | 2048 | **512 blocks** |
| `the_starwell`, `the_tidepools`, +2 more | 1 | 256 | **256 blocks** |

Every dimension in that table works perfectly for a portal built near spawn
and traps the player for one built any further out. That is why it survived
so long: the failure is a function of *where you built*, not of the code
path taken.

### Why this was expensive to diagnose

The symptom ("I can't break anything, and blocks seem to come back") points
at a protection mixin, a fake-block bug, or a permissions problem. The cause
is arithmetic in a config file, surfacing through one INFO log line from
`WorldBorderManager` — a 93-line class with **zero tests**. Two prior
sessions chased fake blocks and portal protection instead.

## Scale semantics: RESOLVED, and the code was wrong

`portal.scale` is the Nether-style travel ratio stated the way people say it
— **"8 nether : 1 over"**. One block walked in the DESTINATION is worth
`scale` blocks back home, so **entering divides and returning multiplies**:

```
scale 8, walk 10 blocks in the dim  ->  10 * 8 = 80 overworld blocks
scale 8, portal at overworld 1888   ->  1888 / 8 = 236 in the dim
```

The code MULTIPLIED on entry, inflating arrivals instead of compacting them.
Fixed 2026-07-25 in `ProjectionVolume.scaledMapping` and `ServerWorldMixin`.

Three independent sources agree on the ratio reading, and the old code agreed
with none of them: all 52 non-1.0 configs use whole ratios (4, 8, 12, 16) and
not one uses a fraction; every dimension's `borders.player` is authored as
`overworldBorder / scale`, which is only coherent with dividing; and vanilla's
own `coordinate_scale` for the Nether is 8, applied as a divisor.

**The old `mods/custom-dimensions/README.md` line — "e.g. 0.125 for
nether-style 1:8" — was the single source that said otherwise, and two unit
tests had been written to match it.** Both are corrected. If you find yourself
reasoning about this again, the README now carries the worked example; trust
it and the configs, not any surviving fractional scale.

**Migration consequence:** this moves where every existing portal points.
Arrivals built at the old multiplied coordinates are orphaned — the records in
`portal_links.json` still name them, but a new traversal resolves the correct
divided column and builds a fresh arrival there. Acceptable on a world you can
wipe; anything else needs a migration pass or a reset.

Explicitly rejected: clamping an arrival to the border. That silently collapses
distinct source portals onto one destination and breaks relative placement. The
scaled coordinate is the INTENT; when it is not viable, search for the nearest
place that is.

## The work

### 9a. A real placement algorithm

Replace "scale, then build" with an ordered search. Every step is a pure
decision over injected probes, testable without a world:

1. **Compute the ideal scaled coordinate.** Unchanged.
2. **Reuse an existing arrival** within the search box, as today — but only
   if it passes the viability checks below.
3. **If the ideal coordinate is outside the destination's player border**,
   search inward for the nearest viable site along the ray from the border
   centre to the ideal coordinate. Nearest-viable, never clamped-to-edge.
4. **Choose Y with `PortalSite.findArrivalY`** — the ceilinged-world-aware
   scan that already exists.
5. **Require egress** (`PortalSite.hasEgress`); carve if the site is
   otherwise good (`ensureEgress`).
6. **If no viable site exists**, fail loudly and do not teleport. Refusing a
   traversal with a message is strictly better than stranding someone.

Border semantics to respect: a border may be absent or infinite; `generation`
and `player` radii differ and it is the **player** radius that governs block
interaction. Leave a margin so a portal's frame ring and egress pocket are
also inside, not just its centre.

### 9b. Config validation at boot

`PortalSafetyValidator` already WARNs at boot without crashing — the right
home and the right tone. Add: for every non-anchor dimension, if
`destinationBorder < sourceBorder × scale`, WARN with the usable source
radius. That single check would have surfaced all 58 of these before a player
ever built a portal.

### 9c. Symmetric portal breaking

Breaking a portal on one side should break its counterpart, unless the
dimension deliberately shares one arrival (anchor dims) or is otherwise
"fancy". `onPlayerBrokePortalBlock` already deregisters and clears the whole
aperture on the side that was mined; it must also resolve and clear the
linked side. Care needed: the counterpart's chunk may be unloaded (never
sync-load from this path — queue it), and an anchor arrival shared by many
sources must NOT be destroyed by one of them.

## Test plan

**Unit (pure, no world) — the bulk of it.**
- Border containment: ideal coordinate inside / outside / exactly on the edge;
  absent border; `generation` vs `player` radius; the frame ring and egress
  pocket must also fit, not just the centre.
- Inward search: returns the nearest viable site on the ray; never returns a
  clamped edge coordinate; returns "no site" rather than something invalid.
- Round trip: out and back lands within rounding distance (extends
  `PortalScalingContractTest`).
- Every shipped dimension config satisfies 9b's invariant — a data-driven
  test over `config/custom-dimensions/dimensions/*.json` that fails the build
  when a new dimension is authored with an unreachable scale/border pair.
  **This test fails today, by design, until the 58 are fixed.**
- Break symmetry: mining one side clears both apertures; an anchor arrival
  shared by N sources survives one source being broken.

**End-to-end (Carpet bot, local).** Carpet ships as a platform default and is
made safe automatically by `scripts/patch-mod-data.py` — no install, no
overlay, nothing to remember.
- Build a portal beyond the usable radius of a scaled dimension, traverse,
  and assert the arrival is inside the destination border.
- Assert the bot can actually break a block at the arrival — the check that
  would have caught this. `execute in <dim> if block …` proves the world
  state; only a player break proves the *interaction* is permitted.
- Re-bury an arrival and traverse; assert egress is restored (this is the
  Phase 8 regression, already proven once).

**Traps for whoever writes these.** Negative coordinates: `floor(-453.5)` is
`-454`, so a bot tp'd to `z=-453.5` stands one block OUTSIDE a portal plane
at `z=-453`. This cost a false negative during Phase 8 verification. Worlds
are lazily unloaded, so `execute in <dim>` returns *"Unknown dimension"*
until something loads it — that is not a failure. And `worldborder get` reads
the shared vanilla border, not the mod's per-world one; trust the
`WorldBorderManager` boot log instead.

## Also now shipped (was open when this doc was written)

- **Entry divides by `scale`.** "8 nether : 1 over" — one block in the
  destination is worth `scale` at home, so entering divides and returning
  multiplies. The code multiplied. Fixed and pinned by
  `PortalScalingContractTest`.
- **The arrival is built where the player lands.** `ServerWorldMixin` passed
  `targetCentre + dx` to `PortalSite` while teleporting to `targetCentre` —
  `dx` is the PROJECTION offset, so the shift applied twice and the portal was
  built hundreds of blocks away. That is what "there is no return portal at
  all" was.
- **The arrival preview translates to its source column.** `returnMapping`
  translated by zero because `PortalReturnTarget` had no `sourceX`/`sourceZ`.
  It does now; the preview went from 12 blocks to 198 in game.
- **Presentation** — see PHASE-7.

## Still open here

1. **The NO_SITE fallback still uses the roof-reading heightmap.** When
   `PortalSite.findArrivalY` finds nothing it falls back to `findSurfaceY`,
   whose `MOTION_BLOCKING_NO_LEAVES` reads the CEILING in a nether-type
   dimension. That silently undoes the whole point of `PortalSite`. Seen live
   on 2026-07-25: an arrival at y=192, on the nether roof. The column bug was
   making it fire far more often than it should, but the fallback is wrong on
   its own terms and needs a carve-in-place instead.
2. **`findArrivalY`'s ceilinged start uses `logicalHeight`.** For these
   custom nether-type dims the generator fills well above it (open space was
   found at y≈172 in `the_boneyard`), so the search band can miss the
   playable space entirely.
3. **9b config validation** and **9c symmetric portal breaking** — unchanged
   from below.

## Out of scope

Fixing the 58 configs is a content decision, not a code one: either raise the
borders or lower the scales, per dimension, against the design intent for
each. 9b makes the choice visible; it does not make it.
