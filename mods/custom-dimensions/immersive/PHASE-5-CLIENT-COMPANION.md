# Phase 5 — Client Companion Mod

> **Depends on:** Phases 0–4 (shipped). This phase does not modify them.
> **Status:** Not started — specification only.
> **Nature:** This is the first phase that requires a CLIENT mod. Everything
> before it is server-side and works on a vanilla client.

## Why this phase exists

The MVP deliberately ruled out a client mod (PLAN.md, Architecture Decision).
That was the right call: it bought ~80% of the experience for ~10% of the cost
and zero Sodium/Iris risk. But in-game testing found a hard ceiling — a
specific, enumerable set of things that **cannot** be done from the server, no
matter how clever the server code is.

This document is that list, written down at the moment it was learned rather
than rediscovered later.

**The bar for adding a client mod is high.** It makes the pack harder to
install, adds a version-locked dependency, and risks conflicting with Sodium
and Iris — which the original decision record called out as the reason to
avoid this route. Do not take it on for one cosmetic win. The case is only
strong because the items below are *collectively* the difference between "a
clever server trick" and "a portal".

---

## Hard blockers — server-side is provably impossible

### 5a. The dimension-change loading screen

**Reported in-game 2026-07-25:** *"When I walk through, I still see a loading
screen briefly."*

Phase 0 removes the *stall* — the target world and its arrival chunks are
ready before the player crosses. It does not and cannot remove the **screen**.
The vanilla client shows `DownloadingTerrainScreen` on any cross-dimension
transfer, gated on its own state, regardless of how ready the server is. No
packet the server can send suppresses it.

Phase 0's doc has been corrected to say "no generation stall", not "no
screen" — the original wording misled the tester into reading a working
feature as broken.

**What the client mod does:** suppress or shorten `DownloadingTerrainScreen`
for transfers into managed dimensions, ideally cross-fading instead. The
server already guarantees the chunks are there, so the screen is pure
ceremony in this specific case.

**Care required:** it is ceremony *only* when the server has actually
pre-loaded. Suppressing it unconditionally would show players an empty void
while chunks stream in. Gate it on a server-sent signal (see 5f).

### 5b. Portal block transparency

**Reported in-game 2026-07-25**, with a screenshot of an arrival portal: the
purple `nether_portal` texture plus particles completely obscure the preview
behind it.

The portal block's texture is a client-side render. The server cannot make it
transparent. And it cannot simply be removed server-side either: the block is
load-bearing for vanilla's "is this entity standing in a portal" detection and
for the entire return path.

**What the client mod does:** render `NETHER_PORTAL`/`END_PORTAL` blocks
belonging to managed dimensions as transparent (or heavily reduced alpha), so
the projection behind them is visible. The block stays where it is; only its
appearance changes.

This is the single highest-value item in this phase. Without it, the arrival
side of a bidirectional projection is looking through frosted glass.

### 5c. Entities visible through the portal

Listed as a known limitation in PLAN.md and in Phase 1's research notes.
Showing mobs and players on the far side needs entity-spawn packets for
entities that are not in the player's current world, positioned in the
player's current world. Doing that with a vanilla client means lying to it
about entity locations, which desyncs interaction, targeting and culling.

**What the client mod does:** receive a lightweight "ghost entity" channel and
render read-only proxies with no physics, no interaction and no AI.

### 5d. Correct lighting for projected blocks

Phase 4a approximates this with fake `Blocks.LIGHT` at the aperture, and the
approximation is visible: block light decrements one per step, so an 8-deep
preview is bright at the front and dim at the back regardless of the
destination's real lighting. A sunlit meadow and a lit cave look similar.

The real fix is sampling the destination's light values and applying them to
the projected geometry, which only a client renderer can do.

### 5e. Biome-dependent colours

Water, foliage and grass colour are computed client-side from the biome the
client believes it is in. Projected blocks therefore take the SOURCE biome's
palette — a swamp seen through a portal into a cherry grove is the wrong
green. PLAN.md already documents this as a fundamental vanilla limitation.

Fixing it server-side would mean sending fake biome data, which risks breaking
every other mod that reads biome state. A client mod can colour the projected
volume from the destination's biome without touching the client's real biome
data.

### 5g. Sub-block-accurate clipping at the frame edge

The server-side mask (`ProjectionVolume.seesThroughOpening`) decides visibility
per WHOLE BLOCK, because a block is the smallest thing a `BlockUpdateS2CPacket`
can describe. Two consequences follow, and neither is fixable server-side:

- **The edge is quantised.** The mask is conservative — a block must be
  *entirely* visible through the aperture to be sent — so nothing hangs past
  the frame, but the trade is that partially-visible blocks show as real world
  instead of as the fraction of destination that geometrically belongs there.
  The window is therefore very slightly smaller than the true opening.
- **Geometry snaps as the player walks.** The mask is a function of eye
  position; blocks are the granularity; a cube either is or is not sent. So
  positions pop in and out at the cone boundary as someone moves past a
  portal. Reported in-game and confirmed as inherent.

**Do not attempt to smooth this server-side.** Hysteresis, fading, or holding a
block "one more pass" all reintroduce the leaked-fake-block class the feature
spent three rounds eliminating (a position that stops qualifying must be
restored on the same pass — see PHASE-1 §1h). The honest fix is a client
renderer that clips the projected volume against the portal's actual aperture
rectangle per fragment, which removes both the quantised edge and the popping
at once, and makes the conservative server mask unnecessary for anyone running
the companion mod.

---

## Enabling work

### 5f. A server→client capability handshake

Everything above needs the client to know **which** blocks and dimensions are
part of a projection, and needs the server to know whether the client can be
trusted to handle it.

- Custom payload channel, versioned from day one.
- Client announces the companion mod and its protocol version on join.
- Server sends, per active projection: the aperture, the projected volume, the
  destination dimension id, and whether it pre-loaded (for 5a).
- **A vanilla client must remain fully supported.** If the handshake does not
  happen, the server behaves exactly as it does today. This is not negotiable:
  the whole feature was built to work without a client mod, and an invite-only
  server still has players who will not install one.

---

## Things that do NOT need a client mod

Recorded so nobody spends the budget twice:

- **Seeing destination terrain through the frame** — shipped (Phase 1).
- **Parallax** — shipped, and free: the fake blocks sit at real coordinates.
- **Cross-portal audio** — shipped (Phase 2), packets only.
- **Entities passing through** — shipped (Phase 3), including living entities.
- **Instant transition in the sense of no generation stall** — shipped
  (Phase 0). Only the screen itself needs a client.
- **Per-dimension weather leaking through the portal** — NOT possible, and not
  a client-mod problem either: weather in 1.21.1 is one save-wide flag shared
  by every dimension (see Phase 2 §2c). A client mod cannot invent state the
  server does not have. Giving dimensions independent weather is a separate,
  much larger piece of work.

---

## Risks specific to this phase

| Risk | Why it matters here |
|---|---|
| Sodium/Iris incompatibility | The original reason a client mod was rejected. Any render hook must be tested against both — they are in the shipped client pack. |
| Version lock | A client mod pins the pack to a client version far harder than a server mod pins the server. Weigh against the MC-version upgrade cadence in AGENTS.md. |
| Two-sided version skew | Server and client mod will drift. The 5f handshake must be versioned and must degrade to vanilla behaviour, never to a broken hybrid. |
| Pack install friction | The pack is distributed as a `.mrpack` with packwiz auto-update; adding a required client mod is a pack-wide decision, not a mod-level one. |

## Verification reality

Phases 0–4 were verified headlessly (RCON + Carpet bot + log greps) with
visual checks deferred to a human. **This phase cannot be verified that way at
all** — every item on it is a render-path change. Budget for in-game
verification by a human as the primary loop, not the fallback, and expect
screenshot-driven iteration like the one that produced this document.
