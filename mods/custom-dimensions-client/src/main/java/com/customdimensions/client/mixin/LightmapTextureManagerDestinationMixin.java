package com.customdimensions.client.mixin;

import com.customdimensions.client.realtime.DestinationLightmap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The lightmap describes the world being rendered.
 *
 * <p>{@code update} takes no world and reads {@code client.world} for sky
 * brightness, lightning and the dimension's ambient light, so a destination
 * pass would otherwise light its dimension by the source's sky. The override
 * is set only across the pass's own call ({@link DestinationLightmap}); every
 * other update reads the field as vanilla does.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapTextureManagerDestinationMixin {

    @Redirect(method = "update", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;"
                    + "world:Lnet/minecraft/client/world/ClientWorld;",
            opcode = Opcodes.GETFIELD))
    private ClientWorld customdimensions$lightmapWorld(MinecraftClient client) {
        ClientWorld destination = DestinationLightmap.held();
        return destination == null ? client.world : destination;
    }
}
