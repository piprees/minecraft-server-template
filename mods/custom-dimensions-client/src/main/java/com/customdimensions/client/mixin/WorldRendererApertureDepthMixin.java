package com.customdimensions.client.mixin;

import com.customdimensions.client.render.ProjectionRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a portal opening's own depth back before the source world's translucent
 * terrain is drawn.
 *
 * <p>The two points are the translucent {@code renderLayer} calls in
 * {@code render}, ordinals 3 and 5 — bytecode 2235 and 2370, the fabulous and
 * ordinary branches, only one of which runs. A shader pack's deferred programs
 * and its pre-translucent depth copy happen earlier, at the {@code translucent}
 * profiler constant at 2213, so injecting here reads the far depth to them and
 * still restores before anything depth-tests. Bytecode order decides that, not
 * mixin priority.
 *
 * <p>No render phase sits in that window, which is why this is a mixin at all:
 * Fabric's {@code BEFORE_DEBUG_RENDER} is 2077 and its {@code AFTER_TRANSLUCENT}
 * is 2445.
 */
@Mixin(WorldRenderer.class)
public class WorldRendererApertureDepthMixin {

    private static final String RENDER_LAYER =
            "Lnet/minecraft/client/render/WorldRenderer;renderLayer("
                    + "Lnet/minecraft/client/render/RenderLayer;DDD"
                    + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";

    // Both branches or neither: one silently unmatched leaves a far depth in
    // the buffer for the rest of the frame.
    @Inject(method = "render", require = 2, at = {
        @At(value = "INVOKE", target = RENDER_LAYER, ordinal = 3),
        @At(value = "INVOKE", target = RENDER_LAYER, ordinal = 5),
    })
    private void customdimensions$restoreApertureDepth(CallbackInfo info) {
        ProjectionRenderer.stampNear();
    }
}
