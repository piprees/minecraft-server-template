package com.customdimensions.mixin.compat;

import com.customdimensions.compat.CarveBounds;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.carver.CarvingMask;
import net.minecraft.world.gen.chunk.AquiferSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds Better Caves' carve to the world it is carving (T84).
 *
 * <p>{@code CaveCarver.Builder.fromConfig} assigns {@code bottomY} straight
 * from the carver datapack, which ships -63 for every carver, and nothing
 * reconciles it with the level. The descent loop is bounded by that value, so
 * in a dimension whose floor is above it every dig below the floor calls
 * {@code CarvingMask.set} with a negative bit index and throws — aborting the
 * whole chunk's carver step, not one block.
 *
 * <p>The mask is the only unguarded operation on the path: {@code ProtoChunk}
 * height-limits both its read and its write, so a cancelled block loses
 * nothing that could have been placed. Cancelling at HEAD makes that explicit
 * rather than leaving it to the replaceable tag to absorb.
 */
@Pseudo
@Mixin(targets = "com.yungnickyoung.minecraft.bettercaves.worldgen.carver.AbstractCarver", remap = false)
public class BetterCavesCarveFloorMixin {

    @Inject(
            method = "carveBlock(Lcom/yungnickyoung/minecraft/bettercaves/worldgen/"
                    + "BetterCavesWorldCarverConfig;Lnet/minecraft/class_2791;Lnet/minecraft/class_2338;"
                    + "Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Lnet/minecraft/class_6643;"
                    + "Lnet/minecraft/class_6350;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1)
    private void customdimensions$skipBlocksOutsideTheWorld(
            @Coerce Object config,
            Chunk chunk,
            BlockPos blockPos,
            BlockState airBlockState,
            BlockState liquidBlockState,
            CarvingMask carvingMask,
            AquiferSampler aquifer,
            CallbackInfo ci) {
        if (!CarveBounds.carvable(blockPos.getY(), chunk.getBottomY(), chunk.getTopY())) {
            ci.cancel();
        }
    }
}
