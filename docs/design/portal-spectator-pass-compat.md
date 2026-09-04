# The spectator pass against Sodium and Iris

Whether a second `WorldRenderer`, driven directly through the public
`WorldRenderer.render(...)` from the head of `GameRenderer.renderWorld` into an
offscreen framebuffer, survives the pack's own renderers.

Read from the jars in the live instance:
`sodium-fabric-0.8.13-beta.2+mc1.21.1.jar` (`sodium:QV48eyCs`) and
`iris-fabric-1.8.14-beta.1+mc1.21.1.jar` (`iris:bAo1Qhte`).

| | Verdict |
| --- | --- |
| **Sodium** | **SURVIVES WITH CONSTRAINTS** — one named leak, entity culling |
| **Iris** | **NEEDS A RUNTIME SPIKE** — three global assumptions, none fatal on paper, all unproven |

## Sodium — survives

**Chunk state is per-`WorldRenderer`-instance.**
`net.caffeinemc.mods.sodium.mixin.core.render.world.LevelRendererMixin
implements LevelRendererExtension` and holds `private SodiumWorldRenderer
renderer`. Its constructor inject
`init(class_310, class_898, class_824, class_4599, CallbackInfo)` runs
`this.renderer = new SodiumWorldRenderer(client)` — that signature is the
four-argument `WorldRenderer(MinecraftClient, EntityRenderDispatcher,
BlockEntityRenderDispatcher, BufferBuilderStorage)` constructor that
`client/realtime/DestinationWorlds.java:129-130` already calls.

A second `WorldRenderer` therefore gets its own `SodiumWorldRenderer` and its
own `RenderSectionManager`. **There is no shared chunk state**, so a destination
pass cannot corrupt the source world's terrain.

**`setWorld` is hooked, per instance, and is safe.**
`LevelRendererMixin.onWorldChanged(class_638, CallbackInfo)` is
`RenderDevice.enterManagedCode()`, `this.renderer.setLevel(world)`,
`exitManagedCode()`. It reads no global and assumes no single world.

**Vanilla chunk storage is off on every instance.**
`LevelRendererMixin.nullifyBuiltChunkStorage` nulls it, so the second renderer
genuinely renders through Sodium rather than falling back to vanilla.

### The one leak: entity culling

`SodiumWorldRenderer.instanceNullable()` is a global lookup: it reads
`MinecraftClient.getInstance().worldRenderer` (`field_1769`), casts to
`LevelRendererExtension`, and returns that renderer's `SodiumWorldRenderer`.
**It always resolves to the main renderer, whichever renderer is executing.**

Exactly two classes outside `SodiumWorldRenderer` call it:

| Caller | On the render path |
| --- | --- |
| `mixin/features/render/entity/cull/EntityRendererMixin.preShouldRender` | **Yes** — wraps the frustum test with `instanceNullable().isEntityVisible(entity)` |
| `mixin/features/gui/hooks/debug/DebugScreenOverlayMixin` | No — F3 screen |

During the spectator pass every entity visibility test asks the **source**
world's section manager about a **destination** entity. Symptom: destination
entities wrongly culled or wrongly kept — mobs and projectiles flickering or
missing through the portal. Not corruption, and not terrain. It lands directly
on P7, because the arrow is an entity.

Three ways out, increasing invasiveness:

1. Turn Sodium's entity culling off — `useEntityCulling` is a field on
   `SodiumWorldRenderer`. **Unestablished:** whether it is reachable from
   Sodium's config alone.
2. Accept mis-culling for the first cut and measure it.
3. Make `MinecraftClient.worldRenderer` mutable by mixin and swap it for the
   duration of the pass. Fixes this and anything else reading that field.

## Iris — three global assumptions

**Iris already renders the world twice per frame.** `ShadowRenderer.renderShadows(LevelRendererAccessor, Camera)`
is a second full pass, and Sodium tolerates it. So a second pass in one frame is
not unprecedented.

**But there is no third slot.** Iris knows which pass is running from globals:
`ShadowRenderer.ACTIVE`, `ShadowRenderer.MODELVIEW`, `PROJECTION`, `FRUSTUM`,
`visibleBlockEntities` (all `public static`), and
`ShadowRenderingState.areShadowsCurrentlyBeingRendered()`. Its Sodium
integration is pass-specific — `compat.sodium.mixin.MixinRenderSectionManagerShadow`
alongside `MixinRenderSectionManager`. **Two states are representable, main and
shadow. Ours would be treated as a main pass, and nothing outside Iris can add
a third.**

### 1. The pipeline is global and dimension-keyed

`MixinLevelRenderer.iris$setupPipeline`, injected at the head of
`WorldRenderer.render`, calls
`Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension())`.
`PipelineManager` holds `Map<NamespacedId, WorldRenderingPipeline>
pipelinesPerDimension` and a single current `pipeline` field.

The pass sets `client.world` to the destination, so `getCurrentDimension()`
returns the destination — correct, and it means **the global pipeline is
swapped to the destination's and back, twice per frame, whenever a portal is in
view.** First sight of a destination also constructs a pipeline.

### 2. Per-frame state is a singleton

The same method writes `CapturedRenderingState.INSTANCE` —
`setGbufferModelView`, `setGbufferProjection`, `setTickDelta`, `setCloudTime` —
and calls `DHCompat.checkFrame()`, a per-frame guard.

Destination-first ordering helps: the source pass runs second and overwrites
these, so the frame ends with the source's values. Anything Iris does between
the two passes sees the destination's.

### 3. Framebuffer binding

**`Framebuffer.endWrite()` does not restore the previous binding.** Its body is
`GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, 0)` — it hard-binds the
default framebuffer. Under Iris, whose own targets are bound during the world
pass, `beginWrite`/`endWrite` on our framebuffer would leave the default bound
and Iris's target unbound.

`MixinGlStateManager_FramebufferBinding` keeps `private static int
iris$drawFramebuffer` / `iris$readFramebuffer` and **skips redundant binds**
(`iris$avoidRedundantBind`). So:

- Capture the current binding with a GL **read**
  (`glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)`) — a read does not desync the
  tracker.
- Restore through `GlStateManager._glBindFramebuffer`, never raw LWJGL. A raw
  bind leaves Iris's tracker stale, and its next bind is skipped as redundant
  when it is not.
- Do not rely on `endWrite`.

## The smallest runtime test

One thing to render, one thing to look at, and it separates all three failure
classes in two runs.

**Run 1 — Sodium only (Iris installed, no shader pack enabled).** Stand up the
second `WorldRenderer` for the nexus destination and call `render(...)` once per
frame from the head of `GameRenderer.renderWorld` into an offscreen
`SimpleFramebuffer`. Blit that framebuffer to a screen corner — **not** into the
aperture, no mask. Look at:

1. Does the corner show the destination?
2. Does the source world still look right?
3. FPS before and after.

Failure here is Sodium or the pass itself; the mask is not involved and cannot
be blamed.

**Run 2 — enable a shader pack.** Same test. If the source world breaks only
now, Iris is the cause and it is isolated in one step.

## Unestablished

- Whether Sodium's `useEntityCulling` is reachable from its config alone.
- Whether `RenderDevice.enterManagedCode()` / `exitManagedCode()` nest or
  assert. The pair is entered by `setWorld` and by Sodium's own draw path.
- Whether Iris's two pipeline swaps per frame are merely a cost or a
  correctness problem. Nothing in the bytecode forbids it; nothing promises it.
- Everything above is static reading. **Neither mod has been run against a
  second `WorldRenderer`.** The two runs above are the cheapest way to convert
  all of this into fact.
