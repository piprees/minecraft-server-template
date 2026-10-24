package com.customdimensions.server.mixin;

import com.customdimensions.server.companion.CompanionFeed;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block change on a destination's far side reaches the companions drawing it; no Fabric event sees every change. */
@Mixin(ServerChunkCache.class)
abstract class ChunkBlockChangedMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void customdimensions$invalidateFeed(BlockPos pos, CallbackInfo ci) {
        CompanionFeed.invalidate(level.dimension(), pos.getX() >> 4, pos.getZ() >> 4);
    }
}
