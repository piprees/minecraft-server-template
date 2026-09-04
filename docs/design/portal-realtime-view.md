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

### P2 — The client declares it draws its own far side

`isCompanion` already gates `sendCompanion`. The client declares it will render
locally, and the server then sends geometry and destination identity only — no
block payloads — for those players.

**The declaration is its own payload, not a wider `Hello`.** What is declared
changes at runtime (a keybind toggles it), while a handshake answers "can you
speak this protocol" once and never changes; and `CompanionPayloads`' own rule
is to add a `/v2` beside a record rather than widen one in place. A separate
payload also means a client that never sends it is served exactly as today, so
neither a vanilla client nor an older companion needs a branch of its own.

Accept a declaration only from a player already in `COMPANIONS` — one from a
client that could not receive a projection anyway must not stop the server
sending one. Suppression skips the sampling too, not only the send.

**Done when:** `companion-send:projection` stops for a companion client while a
vanilla client on the same server still receives the slab and still renders it.
Both halves measured in one run; a companion going quiet is only half the proof.
Assert it as a count over a fixed window, never as an absence ([T63](../../TROUBLESHOOTING.md#t63)).

**A Carpet bot is the vanilla client.** It never handshakes, so it is one by
construction — both halves in one run with no second real client. Note that a
bot spawns in CREATIVE; set survival before anything gamemode-sensitive.

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

**The scale is already spent, and spending it twice is the trap.** The server
divides by a dimension's `scale` at `immersive/ProjectionVolume.java:648-649`
and bakes the result into a flat offset at `:651`; `toTarget` (`:859-863`) is
then pure addition, and the `dx`/`dy`/`dz` on `CompanionPayloads.PortalFrame`
are that offset. A camera that divides again is right at scale 1 and wrong at
`the_crimson_nexus` (2.0) and `the_crucible` (4.0). Add the offset; verify at
both against a known landmark.

**Done when:** a screenshot pair at both scales puts a known far-side landmark
where it belongs.

`realtime/RealtimeView.java` reads the destination `ClientWorld` and produces
the `Projection` shape the render path already takes from the server, so the
mesh, the cone clip, the backdrop and the depth slice are unchanged. The box
is `DEPTH` 16 by `RADIUS` 8 against the slab's 12 and 6.

Nexus (2.0) is verified: a glowstone wall 5 blocks past the arrival draws with
its top edge 80% up the aperture, where a cone from the eye through the opening
puts it. Crucible (4.0) is verified on the TRANSFORM only — the wire offset is
`(-2445, -25, -2162)`, pure addition, and a local projection builds there — not
on a landmark, for want of a vantage point at that rig.

### P4a — The pass must own the framebuffer

**`WorldRenderer.render` re-binds the main framebuffer from inside itself.** At
bytecode 700 it calls `client.getFramebuffer().beginWrite(false)`, guarded only
by `canDrawEntityOutlines()`:

```java
!isRenderingPanorama() && entityOutlinesFramebuffer != null
    && entityOutlinePostProcessor != null && client.player != null
```

There is no glowing-entity clause — that guard belongs to the other site at
1655, and 655 is the fabulous path, which the pass refuses. So 700 fires on
every ordinary frame. Hand `render` an offscreen target and it returns having
bound the main one; everything drawn after that point lands there and the source
world paints over it.

**The fix is to make what they re-bind TO be correct**, rather than enumerating
who re-binds — the enumeration goes stale every MC version. `MinecraftClient.framebuffer`
is `private final`, so a `@Mutable @Accessor` swaps it to the pass's target for
the duration of the render, released in a `finally`: a throw between swap and
release would leave the whole game drawing to a dead target, and would also trip
`SpectatorPass.disabled()` into the refusal latch. `spectatorRebinds` on the dev
bridge is the runtime proof it holds — it reads 0 when the adoption applies.

**Two things a chunk needs before the renderer will build its section**, both
required and both satisfied by the feed: the chunk AND its 8 neighbours carrying
block-data and light-data status. `DestinationFeed` sends a filled core
(`CORE_RADIUS 2`, a 5x5 whose 3x3 interior qualifies) rather than a visibility
cone, which has no interior. Light goes in through vanilla's own `readLightData`
with the handler's world swapped to the destination, and each fed chunk marks its
own sections via vanilla's `setSectionStatus` + `scheduleBlockRenders` — the half
of `onChunkData` that `loadChunkFromPacket` does not perform.

**The feed sends only RESIDENT chunks and may not load or generate one**
(`mods/AGENTS.md` rule 1), so residency round an arrival decays after a boot and
the feed with it. Any measurement round therefore needs its own server restart.

**The pass must not write `MinecraftClient.world`.** Other mods read that field
from other threads, so a destination world written into it is observable by code
that was never told the world exists — Sound Physics Remastered caches a level
clone per `ClientWorld` and NPEs on ours from the sound-engine thread. The
destination reaches the render through redirects on the field's reads in
`render`, `renderSky` and `renderWeather`, plus a held reference for
`LightmapTextureManager.update`, which takes no world argument.

**With an Iris shader pack loaded, a destination view takes its time and
celestial uniforms from the source dimension.** Iris reads
`MinecraftClient.world` directly in `IrisRenderingPipeline`, `ShadowRenderer` and
its uniform classes. Accepted: it only bites with a pack loaded.

**Entities in a destination world set `ignoreCameraFrustum`.** `EntityRenderer.shouldRender`
returns on that field before it reaches `Frustum.isVisible`, which is where
Sodium wraps the cull — so the flag works with Sodium present or absent and needs
no mixin. The feed is capped and inside the disc by construction, so a frustum
test buys nothing and only costs the entity.

**THE MOD IS STANDALONE.** Sodium cannot be assumed and must not be depended on.
Build against the vanilla renderer; anything Sodium does is a consequence, never
the mechanism.

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

### P8 — Distant Horizons LODs through the aperture

**Deferred, not impossible.** A route exists and needs no mixin into DH.

DH renders from `WorldRenderEvents` handlers that write four public mutable
fields on `ClientApi.RENDER_STATE` — `mcProjectionMatrix`, `mcModelViewMatrix`,
`partialTickTime`, `clientLevelWrapper` — then call the public
`ClientApi.INSTANCE.renderLods()`. `RenderParams.update` derives the
`DhClientLevel`, its `RenderBufferHandler` and the lightmap from
`clientLevelWrapper` alone. **Choosing the level is a field write, not a hook.**

**The camera is not in that state object.** It comes live from
`mc.gameRenderer.getCamera()`, so a pass that moves the real camera gets DH's
culling and LOD centring for free; one that only fakes matrices culls the far
side against the near side's position.

A keyed wrapper is needed before DH will load the level: send
`RequestLevelInitMessage(dimensionId)`. The handler checks only that
`sendLevelKeys` is on and the level is loaded — **not that the player is in
it**.

**Occupancy was never DH's question.** Measured with DH's own `/dh debug`: five
tracked levels, one occupied, including a runtime custom dimension — because
`ServerApi.serverLevelLoadEvent` hangs off `ServerWorldEvents.LOAD`.

**The gap:** `DhServerWorld.getOrLoadLevel` registers no data-request handlers
for players already online, so a dimension activating after a join serves that
player no LODs until they reconnect. Fixable from outside by calling
`level.registerNetworkHandlers(state)` per online player on
`DhApiLevelLoadEvent`.

**Why it waits.** Everything load-bearing is `core.*`/`common.*`, not DH's
versioned `api`, so every DH pin bump is a re-verification. The destination must
stay loaded or there is no key and no data, which idle unload actively fights
([K6](../../TROUBLESHOOTING.md#k6)). And DH's own integration with the one other
mod that renders dimensions through portals answers this situation by
**cancelling the LOD render outright** — possibly a considered correctness
choice we would otherwise re-learn in production.

**P6's acceptance is met by render distance alone, at zero coupling.** Do this
after P1–P7, or not at all.

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
- Server mod: 147 suites / 1758 tests. Client mod: 405 tests. Keep
  both green. Any test touching a `RenderLayer` dies in `<clinit>`
  (`RenderLayer -> ItemRenderer -> Items -> Blocks -> SoundEvents`).

## The spectator pass

A second camera stands in the destination at the corresponding position,
renders that world as a complete frame, and the result is composited into the
aperture. Not a projection drawn into the source scene — a separate view
masked into this one.

Every claim below is read from 1.21.1 bytecode; the class and member are named.

### 1. The swap set

`net.minecraft.client.MinecraftClient`, field modifiers as declared:

| Field | Modifier | Pass |
| --- | --- | --- |
| `world` | `public` | **Never written** — read by other threads; see P4a |
| `player` | `public` | Left alone — see below |
| `cameraEntity` | `public` | Set to the stand-in, if one is used |
| `interactionManager` | `public` | Left alone |
| `worldRenderer` | **`public final`** | Never reassigned — the second renderer is driven directly |
| `gameRenderer` | **`public final`** | Not swapped |
| `particleManager` | **`public final`** | Not swapped |
| `entityRenderDispatcher` | **`private final`** | Instance kept; its world is swapped |
| `blockEntityRenderDispatcher` | **`private final`** | Instance kept; its world is swapped |
| `bufferBuilders` | **`private final`** | Instance kept; the pass owns a second one |

`MinecraftClient.world` reaches the destination render through redirects, never
a write. `WorldRenderer` reads the field in `render`, `renderSky` and
`renderWeather` — tick manager, fog world, thick fog, sky type, rain gradient —
and `WorldRendererDestinationMixin` returns the renderer's own `this.world` at
each, which is the same object on the client's own renderer.
`LightmapTextureManager.update(float)` reads the field for
`getSkyBrightness(float)` and `getLightningTicksLeft()` and takes no world
argument, so it reads a held reference instead (`DestinationLightmap`).

**The dispatchers do not need a mixin.** `EntityRenderDispatcher.setWorld(World)`
is public (its `world` field is private); `BlockEntityRenderDispatcher.world` is
a `public` non-final field and `setWorld(World)` is public. Both also expose
`configure(World, Camera, …)`, which is the per-frame call `GameRenderer`
already makes.

**`WorldRenderer.setWorld` is destructive and must not be used per frame.** Its
body calls `entityRenderDispatcher.setWorld`, assigns `this.world`, then
`reload()`, `BuiltChunkStorage.clear()` and `ChunkBuilder.stop()` — a full
teardown and rebuild of chunk render state. A **second `WorldRenderer`** is
required, which `DestinationWorlds` already constructs
(`client/realtime/DestinationWorlds.java:129-130`).

**No mixin is needed to drive it.** `WorldRenderer.render(RenderTickCounter,
boolean, Camera, GameRenderer, LightmapTextureManager, Matrix4f, Matrix4f)` is
public, so the second renderer is called directly and the final
`MinecraftClient.worldRenderer` field is never touched.

### 2. The camera

`Camera.update(BlockView area, Entity focusedEntity, boolean thirdPerson,
boolean inverseView, float tickDelta)` stores `area` and `focusedEntity`, then
immediately dereferences the entity — `getYaw(f)`, `getPitch(f)`, `prevX`,
`getX()`. **A null focused entity is an NPE, not a no-op.** `setPos` and
`setRotation` are `protected`.

No stand-in entity is needed. `area` takes the destination world; the focused
entity stays `client.player`, whose yaw and pitch are exactly the ones wanted
(the destination frame is on the source's own axis — no rotation, see
`client/realtime/PortalCamera.java`); a `Camera` subclass then calls the
protected `setPos` with the translated position from
`PortalCamera.destinationEye`.

The player is not in the destination world, so `WorldRenderer`'s six
`getFocusedEntity()` checks simply never match — the first-person suppression
they drive has nothing to suppress. **Unestablished:** whether any of those six
requires the entity to be resident in `area`. Read them before relying on this.

### 3. The entry point

`GameRenderer.renderWorld(RenderTickCounter)` does the per-frame setup in this
order: `LightmapTextureManager.update`, `getCameraEntity`/`setCameraEntity`,
`Camera.update`, then the world render. The spectator pass reproduces that
sequence for the destination.

**The destination render must happen outside `WorldRenderer.render`, not inside
it.** Every `WorldRenderEvents` phase — `START`, `AFTER_SETUP`,
`BEFORE_ENTITIES`, `AFTER_ENTITIES`, `BEFORE_BLOCK_OUTLINE`, `BLOCK_OUTLINE`,
`BEFORE_DEBUG_RENDER`, `AFTER_TRANSLUCENT`, `LAST`, `END` — fires from inside
the source world's `WorldRenderer.render`, with its matrices set, its
framebuffer bound and the shared `BufferBuilderStorage` mid-use. So:

- **Destination render:** a mixin at the HEAD of `GameRenderer.renderWorld`,
  before the source pass begins, drawing into an offscreen framebuffer.
- **Composite:** inside the source pass, where the aperture geometry is known.
  `ProjectionRenderer` draws at `BEFORE_ENTITIES` today
  (`client/render/ProjectionRenderer.java:20`), which is exactly why source
  entities paint over it. A composite belongs after entities.
  **Unestablished:** whether `AFTER_ENTITIES` or `AFTER_TRANSLUCENT` is
  correct. Translucent ordering is what will be wrong, and it needs a
  screenshot, not a reading.

### 4. The mask

**1.21.1's framebuffer has no stencil attachment.** `net.minecraft.client.gl.Framebuffer`
declares `public final boolean useDepthAttachment` and `protected int
depthAttachment`; there is no stencil field and no stencil method.
`Framebuffer.initFbo(int, int, boolean)` creates the depth texture with
internal format `6402` (`GL_DEPTH_COMPONENT`) — not `35056`
(`GL_DEPTH24_STENCIL8`) and not `34041` (`GL_DEPTH_STENCIL`), neither of which
appears. `SimpleFramebuffer(int, int, boolean, boolean)` inherits that.

**The stencil operations exist and have nothing to write into.**
`RenderSystem.stencilFunc(int, int, int)`, `stencilMask(int)`,
`stencilOp(int, int, int)` and `clearStencil(int)` are public, backed by
`GlStateManager._stencil*` and a `StencilState`. On MC's own framebuffer there
are zero stencil bits to test.

| Route | Verdict |
| --- | --- |
| Own framebuffer with a packed depth-stencil attachment | Possible, raw GL through `GlStateManager`, and the pass then owns resize and lifecycle |
| **Offscreen framebuffer, composited into the aperture polygon** | **Recommended.** No stencil: the aperture geometry is the mask, and the composite draw depth-tests normally, so source geometry in front of the portal occludes it |
| Depth pre-pass gate | Cheapest to reach and re-buys the half-block depth error the slice exists to manage |

### 5. Restore guarantees

An exception mid-pass must not leave the client pointing at the destination.
That is an unrecoverable client, and it reads as a crash in someone else's mod.

Every swap is set inside `try` and restored in `finally`, innermost last:

| `finally` restores | To |
| --- | --- |
| `client.world` | The source `ClientWorld` captured before the pass |
| `client.cameraEntity` | The captured value, even if it was null |
| `entityRenderDispatcher.setWorld` | The source world |
| `blockEntityRenderDispatcher.setWorld` | The source world |
| Framebuffer binding | The framebuffer bound on entry, via `endWrite` |
| GL depth/blend state | The values captured on entry |

The `finally` runs even for `Error`, and the pass swallows nothing: it restores,
logs once per portal, disables itself for that portal, and rethrows nothing into
the frame. **A pass that has failed once does not run again until the frame
counter resets** — a per-frame exception is a per-frame log flood.

### 6. Re-entrancy

A portal visible through the aperture renders another pass. Bound it.

- The depth counter lives on the pass itself, incremented on entry and
  decremented in the same `finally` as the swaps.
- **Default 1** — one level of nesting, so a portal seen through a portal draws
  its aperture as a flat fallback rather than recursing. Immersive Portals
  bounds the same way.
- The second `BufferBuilderStorage` matters here: `BufferBuilderStorage(int)`
  has a public constructor, and `getEntityVertexConsumers()` returns a shared
  `VertexConsumerProvider.Immediate`. Re-entering the source pass's Immediate
  mid-draw is the concrete failure this bound prevents.

### 7. What of the depth machinery survives

The maintainer's ruling: **no source bleed-through wins**, at the cost of half a
block of depth error. Against a composited pass:

| Piece | What it holds | Under a composite |
| --- | --- | --- |
| `GL_LEQUAL` on BACKDROP | Stops source terrain being erased by destination fragments that borrowed source coordinates | **Redundant** — a composited texture borrows no source coordinates. The aperture polygon has its own real depth |
| `SLICE_FRACTION = 0.9` depth slice | Makes `GL_LEQUAL` survivable by dragging the test depth onto the portal surface | **Redundant with the above**, and its 0.46-block shortfall at a 2x3 opening goes with it |
| The aperture stamp | Writes the portal surface into depth so what is behind it is not drawn over | **Still needed** — it is what gives the composite polygon a depth to test against |
| The clip against the aperture cone | Bounds what the destination may show | **Still needed in the destination pass**, as a clip plane on the far side of the portal plane (`PortalCamera.depthBeyondPlane`) |

Both redundancies are conditional on the composite actually carrying its own
depth. **Do not delete either until a screenshot pair shows the torch and
pedestal cases behaving on the new path** — that is P5's acceptance and it is
unchanged.

### 8. Cost

A full second world render is roughly a second frame: chunk draws, entity
draws, sky and lightmap for the destination, at the destination's own render
distance.

| Bound | Where |
| --- | --- |
| The frustum gate that already exists | ~9us off screen against ~505us on screen — the pass runs only for an aperture actually in view |
| The pass's own render distance | Independent of the player's; `RealtimeSettings.maxRenderDistance` already carries it (`client/config/RealtimeSettings.java:32`, default 16, clamped 2..32) |
| Nesting depth | 1 (above) |
| Portals in view | **Unbounded today.** Two apertures in frame is two full passes. Cap the number of passes per frame and pick the nearest |

**Unestablished:** the actual per-frame cost. It has to be measured, not
estimated, and reported the way every other phase reports its own — the plan
says the number is wanted, not hidden.

### Unestablished

- Whether any of `WorldRenderer`'s six `getFocusedEntity()` uses requires the
  entity to be resident in the camera's `area`.
- Whether the composite belongs at `AFTER_ENTITIES` or `AFTER_TRANSLUCENT`.
- The per-frame cost of one pass, and the cap on passes per frame.
- Whether Sodium and Iris, both in the pack, tolerate a second `WorldRenderer`
  driven outside `GameRenderer.renderWorld`. This is the largest unknown in the
  whole design and nothing above has been checked against either mod.
