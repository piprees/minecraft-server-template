package com.customdimensions.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A world render reads the world it was given, not the world the player is in.
 *
 * <p>{@code render}, {@code renderSky} and {@code renderWeather} reach past
 * {@code this.world} to {@code client.world} for the tick manager, the fog
 * world, thick fog, the sky type and the rain gradient. On the client's own
 * renderer the two are the same object; on a destination renderer they are
 * not, and without this the destination is skied, fogged and rained as the
 * dimension the player is standing in.
 *
 * <p>This is what lets the second pass run without writing
 * {@code MinecraftClient.world}. That field is read from other threads — the
 * sound engine reads it on every sound it starts — and a world no client
 * lifecycle ever stood up has no per-world state there
 * ({@code TROUBLESHOOTING.md#t92}).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererDestinationMixin {

    @Shadow
    private ClientWorld world;

    @Redirect(method = "render", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;"
                    + "world:Lnet/minecraft/client/world/ClientWorld;",
            opcode = Opcodes.GETFIELD))
    private ClientWorld customdimensions$renderedWorld(MinecraftClient client) {
        return this.world;
    }

    @Redirect(method = "renderSky", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;"
                    + "world:Lnet/minecraft/client/world/ClientWorld;",
            opcode = Opcodes.GETFIELD))
    private ClientWorld customdimensions$skiedWorld(MinecraftClient client) {
        return this.world;
    }

    @Redirect(method = "renderWeather", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;"
                    + "world:Lnet/minecraft/client/world/ClientWorld;",
            opcode = Opcodes.GETFIELD))
    private ClientWorld customdimensions$weatheredWorld(MinecraftClient client) {
        return this.world;
    }
}
