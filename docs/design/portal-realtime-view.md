# Real-time portal view

How a portal shows the far side. The companion client renders the destination
itself with a camera; the server's block stream is the fallback for players
without it.

## Intent

Look through a portal and see the other side as it is now — players, mobs,
items, an arrow in flight, sky, weather, lights. Out to the client's own render
distance, with Distant Horizons beyond that.

**Acceptance:** fire an arrow through a portal and watch where it goes.

## The two paths

| | Companion client | Vanilla client |
| --- | --- | --- |
| Renders | The destination world, from a camera, masked into the aperture | A block slab streamed from the server |
| Extent | Client render distance + DH | 12 deep x 6 padding, `[16, 32]` clamped |
| Server sends | Portal geometry and destination identity. **No block data.** | The slab, every 4 ticks |
| Fidelity | Live | Blocks only, refreshed on an interval |

The slab is the fallback, not the baseline. It exists so a player without the
mod still sees something, and it must keep working unchanged.

## What already exists

Measured, not assumed.

- **The handshake is built.** `companion/CompanionNetwork.java` registers a C2S
  `Hello(protocolVersion)` on `customdimensions:handshake/v1`; a player joins
  `COMPANIONS` only on an exact `PROTOCOL_VERSION` match, so version skew
  degrades to vanilla rather than to a hybrid. `isCompanion(UUID)` is the gate.
  Log markers for proof: `companion-accept:handshake`,
  `companion-send:preloaded-transfer`, `companion-send:projection`.
- **The fork point is built.** `immersive/PlayerProjectionState.java:291` already
  branches: a companion takes `sendCompanion(...)`, everyone else takes the slab
  path below it.
- **Entity pass-through is built.** `immersive/EntityPassthrough.java` (639
  lines) covers items, projectiles, experience orbs, falling blocks, vehicles
  and leashed entities; `mixin/EntityTickPortalMixin.java` (211 lines) drives it
  per tick.

So the arrow almost certainly **travels** today. It cannot be **seen**.

## Why the current client path cannot become live

- `client/render/ProjectionMesh.java:95-140` captures only
  `BlockRenderType.MODEL` block quads and fluid quads over a fixed volume. No
  entities, no block entities, no sky, nothing animated.
- `client/render/QuadCapture.java` javadoc: the mesh is *"built once per
  projection and clipped against the aperture every frame"* — clipped per frame,
  not re-sampled.
- `client/render/ProjectionRenderer.java:20` draws `BEFORE_ENTITIES`, so the
  source world's entities paint over the projection.
- `immersive/PlayerProjectionState.shouldRefresh` slows the refresh with a
  `STATIONARY_MULTIPLIER` while the player stands still — exactly when it looks
  frozen.

Raising the refresh rate buys an expensive stutter. The design has to change.

## Approach: a camera, not a simulation

Render the destination as a real scene from a transformed camera, masked into
the aperture. Do not reproduce block updates, entity spawns, weather or time as
individual synchronised events — that is a simulation of a world, and it will
always lag the world it copies.

The client needs the destination world present locally. A vanilla client holds
one `ClientWorld`; this needs a second, fed by the server, rendered from a
camera at the viewer's corresponding position on the far side.

## Phases

Each phase lands something visible and is verified before the next begins.

### P1 — Settings and controls

Client-side config: enable/disable the real-time view, max render distance for
it, DH on/off, and a fallback-to-slab toggle. Keybind to toggle at runtime for
A/B comparison.

Ships first because every later phase needs a way to turn itself off next to the
old path, and because a screenshot pair across a toggle is the cheapest possible
before/after.

**Done when:** the toggle flips between paths in-game without a relaunch.

### P2 — The handshake suppresses the stream

`isCompanion` already gates `sendCompanion`. Extend the handshake so the client
declares it will render locally (a capability flag, not merely a version), and
have the server send portal geometry and destination identity only — no block
payloads — for those players.

**Done when:** `companion-send:projection` stops for a companion client while a
vanilla client on the same server still receives the slab and still renders it.
Both halves measured in one run; a companion going quiet is only half the proof.

### P3 — The destination world, client-side

The server feeds the companion the destination dimension's chunks near the
arrival; the client holds them in a second world instance.

**The known hazard:** `mods/AGENTS.md` — Distant Horizons and c2me build
per-level state **exclusively** from `ServerWorldEvents.LOAD`/`UNLOAD`. Skipping
LOAD NPEs DH and has locked a player out of production once. Fire the events.

**Done when:** the client holds destination chunks with no crash and no DH
warning, proven by a count, not by absence of error ([T63](../../TROUBLESHOOTING.md#t63)).

### P4 — The camera transform

Place the camera at the viewer's corresponding position on the far side and
render into the aperture.

**Scale is the trap.** Per-dimension `scale` — `the_crimson_nexus` 2.0,
`the_crucible` 4.0 — and entering divides by it. Derive the transform, do not
guess it, and verify at scale 1 and scale 4 against a known landmark.

**Done when:** a screenshot pair at both scales puts a known far-side landmark
where it belongs.

### P5 — The mask

The destination scene appears only in the opening, and source-world geometry in
front of the portal still occludes it correctly.

**This is the regression risk.** A night was spent proving the depth behaviour
and several parts now have unit tests. `GL_LEQUAL` on BACKDROP is what stops
source terrain being erased; the depth slice is what makes `GL_LEQUAL`
survivable by dragging its test depth onto the portal surface. Stubbing the
slice does not reproduce the erasure — it reproduces the hillside case, where
the source world shows through the opening. Decide deliberately what a
stencil-masked scene render still needs. Do not delete a proven fix because the
new path "shouldn't need it". `.handoff/LOG.md` from 15:50 records which
mutation reproduced which artefact.

**Done when:** the torch and pedestal cases from the previous session behave
identically on the new path, from the same camera.

### P6 — Distance and Distant Horizons

Extend to client render distance, then DH beyond it. Fetch DH's current API
rather than recalling it; say so plainly if that lookup fails.

**Done when:** the far side visibly extends past the old 12/6 slab, with a
measured distance.

### P7 — The arrow

Entities render through the opening. An arrow crosses without vanishing at the
plane, without jumping, and continues visibly on the far side.

**Done when:** the acceptance test passes, filmed.

## Costs

The pass costs ~9us off screen (frustum gate) and ~505us on screen today. Every
phase reports its per-frame cost. A live view will cost more; the number is
wanted, not hidden.

`ImmersiveSettings.java:46` explains the slab's small numbers in its own words —
*"the slab grows with the depth times the square of the radius"*. That cubic
cost is a **server streaming** constraint and does not bound a local render.
It still bounds the fallback, so leave those constants alone.

## Instruments

- The client emit line: `surface=`, `stamp=`, `gated=`, `renderUs=`, `slice=`.
  Extend it when a phase adds a term worth watching.
- Log markers: `companion-accept:handshake`, `companion-send:projection`.
- The dev bridge on `127.0.0.1:8766` — `/state`, `/screenshot` (path required),
  `/input`. Body takes the action as its single top-level key: `{"use":{}}`.
- `scripts/e2e/lib.sh` and `customdim e2e-state <player>`.

**Look at every rendering claim in a screenshot before making it.**

## Constraints

- The vanilla slab path must not regress. It is the fallback for every player
  without the mod.
- A client mod change needs `./dev reload` plus a client relaunch, never
  `./dev up`. Confirm the install with `cmp` before relaunching.
- The client dies of its own accord ([P6](../../TROUBLESHOOTING.md#p6), a
  HotSpot SIGBUS with no fix). A client death invalidates assertions downstream
  of it and is not a finding about the code.
- Server mod: 134 suites / 1599 tests. Client mod: 18 suites / 304 tests. Keep
  both green. Any test touching a `RenderLayer` dies in `<clinit>`
  (`RenderLayer -> ItemRenderer -> Items -> Blocks -> SoundEvents`).
