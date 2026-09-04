package com.customdimensions.client.mixin;

import com.customdimensions.client.realtime.SpectatorPass;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the spectator pass around the source world's own render.
 *
 * <p>The destination render must be OUTSIDE {@code WorldRenderer.render}:
 * every {@code WorldRenderEvents} phase fires from inside it with the source
 * world's matrices set, its framebuffer bound and the shared
 * {@code BufferBuilderStorage} mid-use. The blit rides the return, where the
 * source world is finished and the main framebuffer is bound again.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSpectatorMixin {

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void customdimensions$spectatorPass(RenderTickCounter counter, CallbackInfo ci) {
        SpectatorPass.render((GameRenderer) (Object) this, counter);
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void customdimensions$spectatorBlit(RenderTickCounter counter, CallbackInfo ci) {
        SpectatorPass.blit();
    }
}
