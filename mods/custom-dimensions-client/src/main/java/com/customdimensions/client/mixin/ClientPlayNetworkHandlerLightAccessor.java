package com.customdimensions.client.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.LightData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's own light path, reachable against a world other than the handler's.
 *
 * <p>{@code readLightData} reads {@code this.world} for the lighting provider
 * and for {@code scheduleBlockRenders}, and Sodium injects on it to mark that
 * same world's chunk tracker with {@code FLAG_HAS_LIGHT_DATA}. A chunk without
 * that flag on itself and all eight neighbours never becomes a render section,
 * so a reimplementation of the loop lights the world and draws nothing.
 * Swapping the field for the call is what puts the flag on the destination.
 */
@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerLightAccessor {

    @Accessor("world")
    ClientWorld customdimensionsclient$world();

    @Accessor("world")
    void customdimensionsclient$setWorld(ClientWorld world);

    @Invoker("readLightData")
    void customdimensionsclient$readLightData(int chunkX, int chunkZ, LightData data);
}
