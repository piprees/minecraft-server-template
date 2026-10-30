package com.customdimensions.server.mixin;

import com.customdimensions.server.level.DryFluidPicker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No fluid in a column a dry zone covers, whichever aquifer cell the fluid would come from. */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
abstract class NoiseBasedAquiferMixin {
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow private boolean shouldScheduleFluidUpdate;

    @Inject(method = "computeSubstance", at = @At("RETURN"), cancellable = true)
    private void customdimensions$dry(int x, int y, int z, double density, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state != null && !state.getFluidState().isEmpty() && globalFluidPicker instanceof DryFluidPicker dry && dry.covers(x, z)) {
            shouldScheduleFluidUpdate = false;
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
