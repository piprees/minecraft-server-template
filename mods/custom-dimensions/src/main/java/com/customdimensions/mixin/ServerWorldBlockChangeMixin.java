package com.customdimensions.mixin;

import com.customdimensions.companion.DestinationFeed;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a changed chunk for resending to anyone watching this world through
 * an immersive portal.
 *
 * <p>{@code updateListeners} is vanilla's per-block-change broadcast, so this
 * runs for every block change in every world. It neither cancels nor allocates:
 * {@link DestinationFeed#invalidate} returns on an empty record, which is every
 * server with nobody looking through a frame.
 */
@Mixin(ServerWorld.class)
public class ServerWorldBlockChangeMixin {

    @Inject(method = "updateListeners", at = @At("HEAD"))
    private void onUpdateListeners(BlockPos pos, BlockState oldState, BlockState newState,
            int flags, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        DestinationFeed.invalidate(world.getRegistryKey().getValue(),
                pos.getX() >> 4, pos.getZ() >> 4);
    }
}
