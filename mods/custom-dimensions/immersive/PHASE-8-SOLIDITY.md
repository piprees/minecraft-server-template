# Phase 8 — Solidity: fake blocks must never trap a player

> **Status: SHIPPED 2026-07-25.** 8a, 8b and 8c are closed, verified headless
> against the live local server with a Carpet bot. 8d recorded below as a
> standing option, not taken.
> **Depends on:** nothing. Phases 0–4, 6 and most of 7 are shipped.

## Outcome (read this before the analysis below — parts of it were wrong)

**8a — the cause was neither (a) nor (b). It was (c): real terrain.**
The arrival was a *pre-fix* 4×3 portal (`PortalSite`'s standard size is 2×3)
built inside solid calcite. `createTargetPortal` calls
`PortalSite.carveEgress`, but `ServerWorldMixin`'s reuse branch
(`existing != null`) never did — so egress was guaranteed **only for portals
being created**, and every traversal after the first re-entombed the player.
Fixed by `PortalSite.ensureEgress` on the reuse path.

*Headless proof:* re-buried both faces of the real portal (0/16 air cells),
drove a bot through the overworld source portal, re-probed — **16/16 air**.

**The F3+A test in this document does not work.** F3+A is
`WorldRenderer.reload()`: it rebuilds render meshes from the client's local
`ClientWorld` and never re-requests chunks, so fake blocks survive it. It
cannot discriminate (a) from (b) and it gave a misleading answer here. The
working discriminator is a server `execute … if block … minecraft:air` probe
compared against what the player reports seeing. `PLAN.md § Ghosts` carries
the same wrong claim.

**8b — resolved differently, by owner decision.** `NetherPortalProtectionMixin`
was **kept**, not removed: it only intercepts NEIGHBOUR updates, and our
arrival frames are not obsidian, so removing it makes vanilla pop every
arrival whenever an adjacent block changes — that is not "vanilla behaviour",
it is portals evaporating. What actually made portals indestructible was
**`healPortalHole`, now removed**: it refilled any registered cell from a
surviving neighbour on every particle pass, so a creative player watched
panes respawn forever. `onPlayerBrokePortalBlock` still takes the whole
aperture down on one break.

**8c — implemented and verified.** `ProjectionVolume.occupiedCells` is the
pure invariant; `PlayerProjectionState` suppresses every body cell (padded by
1 for the step between refresh passes, viewer included) using the same
restore-and-drop-from-`lastSent` bookkeeping as the sightline mask.

*Headless proof:* the mask line now carries the count —
`1044 of 1056 maskable visible … 12 suppressed by bodies` with a bot standing
in the slab, `0 suppressed by bodies` with nobody in it.

**Tests:** `PortalSiteTest` (23), `BodySuppressionTest` (9),
`PortalScalingContractTest` (11). Suite 273 → 316. See
`../TEST-COVERAGE-AUDIT.md` for the coverage gaps this exposed — `PortalSite`
had **zero** tests despite owning the two behaviours that trap players.

## Original analysis (historical — see Outcome above for what was true)

## The defect

Reported in game 2026-07-25. Arriving in `adventure:the_ember_fields`, the
project owner was wedged inside blocks and could not get out:

> *"I can't escape the dimension as I'm wedged in rocks and every time I break
> them they respawn. I've had to teleport out as I was properly stuck."*

Coordinates: the arrival portal is at `1888, 249, -3624`; he was standing at
`1890, 248, -3624`.

**This is unresolved, and there are two candidate causes that need OPPOSITE
fixes. Do not write code until you know which one it is.**

### (a) Fake blocks

He breaks a block, the server agrees it is gone, and the projection repaints it
on the next delta pass. The client then collides with something the server
says is not there — so it "respawns", and mining it again does nothing because
server-side there is nothing to mine.

Client-side collision against fake blocks is inherent to the server-side
approach and was already reported earlier in the same session ("the fake
blocks that get rendered actually block my player from moving sometimes… I can
actually break these leaves too"). What makes this the leading hypothesis is
timing: `previewRadius` was raised from 2 to 4 in the same session, widening
the slab, and he was buried INSIDE it.

### (b) Something genuinely rebuilding the blocks

No mechanism was found for this, and two obvious suspects are ruled out:

- `NetherPortalProtectionMixin` only stops PORTAL blocks popping, and only on
  neighbour updates.
- `healPortalHole` only fills AIR with PORTAL blocks, and only at registered
  positions.

Neither can produce calcite. Absence of a mechanism is not proof, but it does
mean (b) needs a real discovery step rather than a guess.

### The decisive test

**Have a human press F3+A (client chunk reload) before mining, while stuck.**

- Wall vanishes → **(a)**, the projection.
- Wall stays → **(b)**, something server-side.

A server-side probe was run at `1890, 248, -3624` and reported SOLID — but that
is two blocks off the actual arrival, so it does **not** discriminate. Re-probe
at `1888, 249, -3624` and at the player's exact standing position:

```bash
docker exec -i mc rcon-cli 'execute in adventure:the_ember_fields if block 1888 249 -3624 minecraft:air'
```

### A loose thread worth pulling

That arrival logged:

```
immersive: arrival projection activated ... (12 blocks)
```

A healthy arrival projects a few hundred. Being walled in explains it — almost
everything masks out — but numbers like that have been the diagnostic signal
for three separate defects in this feature already. Confirm it is explained
rather than assuming it.

## The work

### 8a. Establish the cause, then fix it

Per the test above. If (a), go to 8c. If (b), find the writer — a log of every
`setBlockState` in that region for a few seconds will name it faster than
reading code.

### 8b. Remove `NetherPortalProtectionMixin`

Requested by the owner:

> *"I don't think we need to protect portals like this, normal vanilla ones
> aren't protected in this way either."*

He is right, and the justification is already gone. That mixin existed solely
because **netherportalspread**'s corruption engine deleted custom-framed
arrival portals within seconds of detection — and that mod was **RETIRED in
v3.7.0**, replaced by portal auras. Removing it costs nothing still in use.

Keep `healPortalHole` for now but review it: it exists because an arrival with
a missing pane strands whoever is standing in it. Now that player breaks
propagate through the whole aperture (shipped in `1aff455`), it is much less
load-bearing than it was — but it is the last line against a portal losing a
pane to something we do not control.

### 8c. Never paint a fake block into a body

The likely real fix for the whole collision class, and cheap: the projection
should refuse to send a position occupied by, or adjacent to, any player — its
own viewer included.

This makes `previewRadius: 4` safe to keep, and it generalises: no future
change to depth, radius or the mask can put a fake block inside somebody.
Worth stating as an invariant in `PlayerProjectionState` alongside the
`lastSent` one, because it is the same class of guarantee.

Watch the bookkeeping: a position that becomes suppressed because a player
walked into it must be RESTORED and removed from `lastSent` on the same pass,
exactly as the mask does. Skipping it is what strands fake blocks.

### 8d. Reconsider whether the slab may contain walkable space at all

The deeper question behind 8c, and the honest framing of the trade this whole
approach makes: the projection slab is real space in the source world. Anything
a player can stand in is somewhere they can collide with a block that is not
there.

Options, cheapest first:

1. Suppress near players (8c) — treats the symptom, keeps the look.
2. Refuse to project positions with no solid backing, so the slab hugs terrain
   rather than filling open air a player could walk into. Narrows the effect.
3. Accept it and wait for Phase 5's render-to-texture, which removes fake
   blocks from the world entirely and makes the whole class impossible.

Recommendation: do 1 now, record 2 as available, treat 3 as the real answer.

## Verification

- Reproduce first. A fix for a defect you could not reproduce is a guess.
- The load-bearing assert stays the same as every other phase: **fake blocks
  must never become real.** Lay markers through the slab, activate, prove they
  survived (`mods/AGENTS.md` § immersive verification recipes).
- Log counts. Every defect in this feature has been silent absence — green
  build, green tests, feature quietly not happening. Not one was a crash.
- Check for ghosts before believing any visual report (`PLAN.md` § Ghosts).
  Orphaned fake blocks look identical to a live failure and have cost real
  diagnosis time twice.
