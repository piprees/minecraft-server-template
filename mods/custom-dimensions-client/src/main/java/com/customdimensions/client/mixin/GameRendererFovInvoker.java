package com.customdimensions.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's effective field of view, which is private.
 *
 * <p>It applies the spyglass and bow-draw multiplier, the death squeeze and
 * the water and lava submersion effect on top of the option. The destination
 * pass draws at whatever the source frame draws at, so it asks vanilla rather
 * than reproducing the arithmetic.
 */
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {

    @Invoker("getFov")
    double customdimensionsclient$getFov(Camera camera, float tickDelta, boolean changingFov);
}
