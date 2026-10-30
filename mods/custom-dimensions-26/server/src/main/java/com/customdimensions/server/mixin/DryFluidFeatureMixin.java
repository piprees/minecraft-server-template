package com.customdimensions.server.mixin;

import com.customdimensions.server.level.DryFluidPicker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No spring and no lake where a dry zone covers the column, or for a lake any corner of its 16-block square. */
@Mixin({SpringFeature.class, LakeFeature.class})
abstract class DryFluidFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void customdimensions$dry(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (generator instanceof NoiseBasedChunkGenerator noise
                && ((NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$globalFluidPicker().get() instanceof DryFluidPicker dry
                && dry.coversAny(origin.getX(), origin.getZ(), (Object) this instanceof LakeFeature ? 8 : 0)) {
            cir.setReturnValue(false);
        }
    }
}
