package com.customdimensions.mixin;

import com.customdimensions.dimension.FixedStructurePlacement;
import com.customdimensions.dimension.ForcedBiomeBypass;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Half one of the forced-placement biome bypass: record, on every placement
 * decision, whether the chunk about to be offered to
 * {@code ChunkGenerator.trySetStructureStart} was claimed by a
 * {@link FixedStructurePlacement} ({@code structures.force}).
 *
 * Every placement writes here, not just fixed ones — writing only on the
 * fixed-and-true case would leave a stale arm behind whenever
 * {@code shouldGenerate} is called with no start attempt behind it (the
 * {@code /locate} path does exactly that). {@link ForcedBiomeBypass} has the
 * full reasoning, including why RETURN is the correct point under the
 * exclusion-zone reentrancy vanilla already has.
 *
 * Cost is one ThreadLocal write per structure set per chunk, and the only
 * behaviour change is for sets whose placement is a FixedStructurePlacement.
 */
@Mixin(StructurePlacement.class)
public abstract class StructurePlacementForcedBiomeMixin {

    @Inject(method = "shouldGenerate", at = @At("RETURN"))
    private void customdimensions$armForcedBiomeBypass(StructurePlacementCalculator calculator,
                                                       int chunkX, int chunkZ,
                                                       CallbackInfoReturnable<Boolean> cir) {
        String dimensionName = null;
        if (cir.getReturnValueZ() && (Object) this instanceof FixedStructurePlacement fixed) {
            dimensionName = fixed.dimensionName();
        }
        ForcedBiomeBypass.arm(dimensionName);
    }
}
