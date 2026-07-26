# Phase 9 — Arrival placement must be reachable, viable, and symmetric

> **Status:** 9a, 9b and 9c SHIPPED 2026-07-26. The root cause (entry
> multiplied instead of dividing) was fixed 2026-07-25.
> **Remaining:** nothing. The three dimensions 9b flagged were given anchors by
> the owner on 2026-07-26; the shipped set now satisfies the invariant with no
> exceptions.
> **Depends on:** Phase 8 (shipped) — `PortalSite.ensureEgress` and
> `PortalSite.hasEgress` are the building blocks this phase extended.

## The defect

Arrival placement was `target = source × scale` — the wrong direction — and
nothing else. It never asked whether the resulting coordinate was somewhere a
player could actually exist. Three independent ways that failed, all now
closed:

1. **Outside the destination's world border.** Vanilla forbids breaking AND
   placing blocks outside the border, so a player arriving there can do
   nothing at all — the portal frame, the portal itself, and every surrounding
   block are inert. *Root cause fixed 2026-07-25; guarded at boot by 9b.*
2. **Inside solid terrain, or on top of the roof.** *Fixed by 9a.*
3. **Not symmetric** — breaking a portal on one side left the other standing.
   *Fixed by 9c.*

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

**This was systemic, not one dimension.** Under the old multiply-on-entry, a
dimension needed `border ≥ 8192 × scale`, and almost none had it. Under the
corrected divide-on-entry the requirement inverts to `border ≥ 8192 / scale`,
which is exactly how the dimensions are authored — so the old table of "58
dimensions can strand a player" is a record of what WAS broken under the
inverted transform, not what is. Measured against the real config set on
2026-07-26 the check found **three** genuine failures, for an unrelated reason
(pocket dimensions at scale 1 into a 256 border); all three were fixed the
same day and the set is now clean.

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
tests had been written to match it.** Both are corrected, and
`ShippedDimensionReachabilityTest.noShippedDimensionUsesAFractionalScale`
now fails the build if a fractional scale ever reappears.

Explicitly rejected: clamping an arrival to the border. That silently collapses
distinct source portals onto one destination and breaks relative placement. The
scaled coordinate is the INTENT; when it is not viable, search for the nearest
place that is.

---

## 9a — A real placement algorithm  ✅ SHIPPED 2026-07-26

Two defects, both seen live in `the_boneyard` on 2026-07-25.

### 9a-1. The NO_SITE fallback used the roof-reading heightmap

`ServerWorldMixin` did:

```java
boolean carved = siteY == PortalSite.NO_SITE;
if (carved) { siteY = surfaceY; }     // surfaceY = MOTION_BLOCKING_NO_LEAVES
```

`carved` was then never used again. There was no carve — it was a rename of a
number known to be wrong, because `MOTION_BLOCKING_NO_LEAVES` reads the
CEILING in a nether-type dimension. So the one path that existed to rescue a
bad column put the player on the nether roof at y=192, silently undoing
everything `PortalSite` is for.

**Fixed** by `PortalSite.findCarveY`: the same band searched again with the
requirement relaxed from "already open" to "openable", preferring a site with
solid ground under its floor row and falling back to an unsupported one
(`createTargetPortal` now lays a floor under a vertical arrival's bottom row,
which it previously did only for horizontal ones). `NO_SITE` from that second
pass means bedrock or block entities all the way down, and the traversal is
**refused** with a log line and an action-bar message rather than teleporting
someone somewhere we know we cannot open.

Carveability is defined once and shared with `carveEgress`, so the search only
ever promises sites the carve can actually deliver.

### 9a-2. The search band came from `logicalHeight`

The ceilinged-world start was `bottomY + logicalHeight() - 2`. `logicalHeight`
is a property of the dimension TYPE — 128 for anything nether-shaped — and
these generators ignore it completely.

Measured live in `the_boneyard`, 2026-07-26:

| column | roof mass | open space | floor |
|---|---|---|---|
| (250, 250) | ~160–180 | 120–140 | ~100 |
| (300, 300) | ~145–190 | 100–140 | ~100 |
| (450, 450) | above 145 | 80–120 | ~78 |

The roof is not a one-block bedrock lid; it is terrain forty-five blocks
thick whose top varies between about y=180 and y=190. A band anchored at 126
cannot see any of that.

**Fixed** by asking the column instead of the type:

- `PortalSite.findCeilingY` — the highest opaque block in the column. Starting
  the interior below it makes "standing on the roof" unreachable *by
  construction*: the whole interior always sits under something.
- `PortalSite.findRoofUndersideY` — the first open block below the contiguous
  roof slab. Without this, an entombed column got carved out five blocks below
  the top of a forty-block slab: under cover, and a horrible place to arrive.
- The band top is the minimum of the two, and the band now runs all the way
  down to the world floor rather than `SCAN_DEPTH` of it.

Non-ceilinged dimensions keep the heightmap surface start and never apply any
of this — zero behavioural change for overworld-type dims.

**Known limitation, stated rather than silently accepted:** a column with an
isolated solid mass floating ABOVE the roof would take its underside as the
bound and could put a site on the roof top beneath it. No generator in this
pack does that. It is a note for whoever adds one.

### Verified live (Carpet bot, elfydd, 2026-07-26)

```
Created portal in adventure:the_boneyard at (250, 97, 250) [open site]
Created portal in adventure:the_boneyard at (450, 139, 450) [open site]
Created portal in adventure:the_boneyard at (500, 24, 500) [open site]
```

The last one is the decisive case: column (500, 500) was deliberately filled
solid with netherrack from y=40 to y=195, so the old band ([78, 126]) was
entirely rock. It would have returned `NO_SITE`, taken the heightmap, and put
the arrival at ~196 — on the roof, exactly as reported. It now finds real open
space at y=24, beneath the whole slab, with full body-height egress on both
faces (`500 24 501`, `500 25 501`, `500 24 499`, `500 25 499` all air).

The log line now says HOW the site was chosen (`[open site]` / `[carved site]`),
so a dimension whose band is wrong again shows up as "carved" on every arrival
instead of being invisible.

---

## 9b — Config validation at boot  ✅ SHIPPED 2026-07-26

`ArrivalReachability` was already written — correct arithmetic, good comments,
**zero callers and zero tests**. The class existed; the check never ran. It is
now wired into `PortalSafetyValidator` and covered by 15 unit tests plus 4
data-driven ones over the real shipped config set.

**The spec in the original version of this document was wrong.** It said:

> for every non-anchor dimension, if `destinationBorder < sourceBorder × scale`, WARN

That is the MULTIPLY-on-entry formula — wrong in the same direction the code
was. Entering divides, so the condition is
`destinationBorder < sourceBorder / scale + margin`.
`ArrivalReachability` is the authority; this document was the stale half.

**Margin is zero, deliberately.** The spec asked for slack so an arrival's
frame ring and egress pocket land inside the border too. That is a real
concern, but every dimension is authored as *exactly* `overworldBorder / scale`
(8192/8 = 1024, 8192/1 = 8192), so any margin at all makes all 74 fail by
exactly that margin — and a warning that fires on every dimension is not a
warning. The boot check answers the first-order question (does the scaled
arrival column land inside the border) and the last few blocks at the extreme
corner are left to the site search, which is where "nudge inward until it
fits" belongs. `PortalSafetyValidator.ARRIVAL_MARGIN` is a one-character
change if the authoring convention ever gains headroom.

### It found three real ones, and they are fixed

On first run the check fired at boot on exactly three dimensions —
`the_emberglass_foundry`, `the_tidepools` and `the_wuthering_wisteria`, all
pocket dimensions at `scale 1.0` into a 256-block border. A portal built more
than 256 blocks from the overworld origin arrived outside their border, and
the player could touch nothing: the "I can't break anything" symptom, sitting
in the shipped config set, found by a check that had never run.

The owner gave all three a `portal.anchor` on 2026-07-26 — a fixed arrival is
not scaled, so the source radius stops mattering. That is the same fix the
sibling pocket dimension `the_starwell` already had.

**The shipped set now satisfies the invariant with no exceptions.**
`ShippedDimensionReachabilityTest` asserts ZERO unreachable dimensions and
names any offender in the failure message. There is deliberately no allow-list
and the expectation is deliberately not derived from the configs — a derived
expectation passes whatever the configs say, which is the one thing a config
test must not do.

---

## 9c — Symmetric portal breaking  ✅ SHIPPED 2026-07-26

A portal is one thing with two ends, and neither end could be got rid of from
the end you were standing at:

- Mining the frame in the overworld took the source zone down and left the
  arrival standing — still a real `NETHER_PORTAL`, still registered, still
  returning anyone who walked into it to a doorway that no longer existed.
- Mining the arrival left the source zone live, so it rebuilt a fresh arrival
  on the next traversal and the portal came straight back.

### How the two ends find each other

Not by geometry. Recovering a source column from an arrival would mean
multiplying a rounded number back up and searching a box — the arithmetic that
has already been wrong twice here. Every arrival cell instead carries the
column it was built FOR (`sourceWorld`, `sourceX`, `sourceZ`), stamped by
`setSourceColumn` at creation, so matching is an exact equality in both
directions. `PortalBreakLink` is the pure decision; `PortalHelper` reads the
registry and writes the blocks.

`PortalBreakLink.centreColumn` is now the ONE definition of a zone's column,
shared with `ServerWorldMixin`'s traversal path. Two copies of that average
would drift and the break would silently match nothing — which looks exactly
like the bug it fixes.

### What must NOT break symmetrically

- **Anchor dimensions.** Many sources share one arrival, so one player mining
  their own frame must not take everybody's way home. Two independent guards:
  `definition.hasAnchor()` on the source side, and `exitMode != null` on the
  arrival side. (The anchor path also never stamps a source column, so there
  would be nothing to match anyway — but an invariant this sharp should not
  rest on an absence alone.)
- **Single-use expiry.** "The way in crumbles behind you" must not crumble the
  way HOME. This is why the trigger lives at the two places a player actually
  breaks something and never inside `removeZone`, which `expireSingleUse`
  also calls.
- **Exit portals and exit shrines.** The mod's own guaranteed way out, not
  half of a player-built pair. They carry an `exitMode` for the same reason
  anchors do.

The source FRAME is deliberately left standing when its arrival is broken.
Deregistering the zone is what closes the way; the frame becomes ordinary
blocks the player can mine, keep, or re-ignite. Destroying somebody's build
because they mined the other end of it would be a bigger surprise than the one
this fixes.

### Cold chunks

The counterpart usually lives in an unloaded chunk — the whole point of a
destination is that nobody is there. Registration is dropped immediately (pure
memory, always correct) and the BLOCKS are cleared now if the chunk is loaded
or queued for `processPendingBreaks`, which drains from the world tick.
Nothing ever sync-loads a chunk from this path.

### A real gap this found

`breakLinkedArrival` mutated memory but nothing persisted it, and the
zone-validity path it runs from had no save of its own — so a broken portal
lived on in `portal_links.json` until a clean shutdown and would come back on a
crash. Caught by checking the persisted file rather than believing the log
line, which reported the break correctly while the file still listed every
cell of it. Both paths now save immediately.

### Verified live (Carpet bot, elfydd, 2026-07-26)

**Source frame broken → arrival cleared**, persisted:

```
Source portal broken in minecraft:overworld — closed its arrival in
adventure:the_boneyard (6 cells, 6 cleared now, 0 deferred)

portal_links.json:  3 zones / 18 arrival cells  ->  2 zones / 12 cells
```

**Arrival broken → source zone closed**, persisted:

```
Arrival portal broken by a player in adventure:the_boneyard at 250, 98, 250
(6 blocks removed, 1 source zone(s) closed)

execute in adventure:the_boneyard if block 250 98 250 minecraft:nether_portal
  -> Test failed          (the portal block is gone)
portal_links.json:  only the untouched (4000, 4000) pair remains
```

Unrelated portals were untouched in both directions. `Restarts=0`, no
`ConcurrentModificationException`, health green throughout.

The anchor negative (an arrival shared by N sources surviving one of them
breaking) is covered by unit tests in `PortalBreakLinkTest`, not e2e — it
would need an anchor dimension and two separate portals to exercise, and both
guards are pure decisions.

---

## Test coverage added

| Class | Tests | What it pins |
|---|---:|---|
| `PortalSiteTest` (extended) | 23 → 35 | ceiling from the column not `logicalHeight`; roof-underside skip; carve preference order; carve refuses when nothing is carveable |
| `ArrivalReachabilityTest` (new) | 15 | the divide-on-entry arithmetic, margins, boundaries, round trip |
| `ShippedDimensionReachabilityTest` (new) | 4 | the REAL config set: no new unreachable dimension, the boot warning fires for exactly the known three, no fractional scale |
| `PortalBreakLinkTest` (new) | 17 | both link directions, anchor/exit-portal exemptions, unstamped records, negative-coordinate truncation |

Suite: **339 → 387 tests, 30 → 33 classes.**

## Traps for whoever works on this next

- **Negative coordinates.** `floor(-453.5)` is `-454`, so a bot tp'd to
  `z=-453.5` stands one block OUTSIDE a portal plane at `z=-453`. Separately,
  Java's `/` truncates TOWARD ZERO, so `centreColumn` of an interior spanning
  −47 and −46 is **−46**, not −47. That value does not have to be
  mathematically ideal, only the SAME on both ends — which is why there is now
  one shared function instead of two copies.
- **`player Bot attack once` does not break a portal block.** A single click
  does not complete the break even in creative; use `attack continuous`, then
  `attack stop`. `attack once` silently does nothing and looks exactly like a
  broken hook.
- **Worlds are lazily unloaded**, so `execute in <dim> …` answers *"Unknown
  dimension"* until something loads it, and RCON `fill` against an unloaded
  dimension silently does nothing. Load it by teleporting a player there
  first, and check the command's own output.
- **Probing an arrival column AFTER building the portal is contaminated** —
  the portal blocks and carved pocket are what you are measuring. Profile the
  column before traversing.
- **`worldborder get` reads the shared vanilla border**, not the mod's
  per-world one. Trust `WorldBorderManager`'s boot log line.

## Out of scope

Fixing the three unreachable configs is a content decision, not a code one:
either raise the borders, lower the scales, or give them an anchor like
`the_starwell` has. 9b makes the choice visible; it does not make it.
