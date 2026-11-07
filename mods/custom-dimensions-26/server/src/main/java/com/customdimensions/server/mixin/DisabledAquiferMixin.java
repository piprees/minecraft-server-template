package com.customdimensions.server.mixin;

import com.customdimensions.server.level.BasinFluidPicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The aquifer of a generator without aquifers schedules a basin's fluid to flow
 * where the basin nears its rim, so a gap in the rim spills into the void; all
 * other fluid it places stays still, as vanilla's does.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$1")
abstract class DisabledAquiferMixin {
    @Shadow @Final Aquifer.FluidPicker val$fluidRule;
    @Unique private boolean customdimensions$flows;

    @Inject(method = "computeSubstance", at = @At("RETURN"))
    private void customdimensions$basin(int x, int y, int z, double density, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        customdimensions$flows = state != null && !state.getFluidState().isEmpty() && val$fluidRule instanceof BasinFluidPicker basins
                && basins.holds(x, y, z) && basins.basin(x, z) < 1;
        if (customdimensions$flows) ((BasinFluidPicker) val$fluidRule).scheduled();
    }

    @Inject(method = "shouldScheduleFluidUpdate", at = @At("HEAD"), cancellable = true)
    private void customdimensions$schedule(CallbackInfoReturnable<Boolean> cir) {
        if (customdimensions$flows) cir.setReturnValue(true);
    }
}
