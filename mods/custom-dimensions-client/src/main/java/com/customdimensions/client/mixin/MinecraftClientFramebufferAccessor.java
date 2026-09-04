package com.customdimensions.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to the client's own framebuffer, so a second world render can
 * be given a target that survives the render re-binding from inside itself.
 *
 * <p>{@code WorldRenderer.render} calls
 * {@code client.getFramebuffer().beginWrite(false)} at three points, and the
 * entity-outline one needs only a player and an outline framebuffer, so it
 * fires on every ordinary frame. Everything drawn after it — entities, block
 * entities, translucent terrain, particles, clouds, weather — lands wherever
 * this field points. Pointing it at the offscreen target for the duration of
 * the pass is what makes those land in the pass's own frame rather than the
 * source world's.
 *
 * <p>The field is final, hence {@link Mutable}.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientFramebufferAccessor {

    @Mutable
    @Accessor("framebuffer")
    void customdimensionsclient$setFramebuffer(Framebuffer framebuffer);
}
