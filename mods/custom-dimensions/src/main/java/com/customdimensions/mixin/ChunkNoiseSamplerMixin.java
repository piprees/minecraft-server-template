package com.customdimensions.mixin;

import com.customdimensions.dimension.KernelDensity;
import com.customdimensions.dimension.TerrainAdaptationOverride;
import com.customdimensions.dimension.TerrainKernel;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The kernels' delivery point into a chunk's generation, chosen because
 * this constructor provably executes with our transforms on the full
 * platform modstack — unlike StructureWeightSampler's sample/factory-RETURN
 * callbacks, which five mods' combined transforms starve (see
 * {@link KernelDensity}).
 *
 * The pending kernel pieces are stashed at the beard factory's HEAD, and
 * vanilla's only noise-fill path builds this sampler immediately after that
 * factory on the same thread — so the stash is live for all three hooks:
 *
 * 1. The final block-state density function (the local the aquifer lambda
 *    captures) is wrapped in {@link KernelDensity}, adding kernel density
 *    on top of everything the router and other mods contribute.
 * 2. The DRAIN kernel's aquifer touchpoint: the fluid-level sampler is
 *    wrapped so queries inside a drain piece's expanded box answer dry,
 *    both aquifer branches included.
 * 3. TAIL disarms the terrain-adaptation override and clears the stash —
 *    this constructor is the stash's last consumer, and a TAIL here is the
 *    reliable end-of-extent hook (the factory's own RETURN callbacks never
 *    run on this stack).
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class ChunkNoiseSamplerMixin {

    // Static is load-bearing: at a constructor's HEAD (before super()) Mixin
    // rejects instance handlers — "handler before super() invocation must be
    // static".
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static AquiferSampler.FluidLevelSampler customdimensions$drainFluidLevels(
            AquiferSampler.FluidLevelSampler original) {
        return TerrainKernel.wrapFluidSampler(TerrainKernel.pending(), original);
    }

    @ModifyVariable(method = "<init>", at = @At(value = "STORE", ordinal = 0))
    private DensityFunction customdimensions$addKernelDensity(DensityFunction function) {
        return KernelDensity.wrap(function, TerrainKernel.pending());
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void customdimensions$endKernelExtent(CallbackInfo ci) {
        TerrainAdaptationOverride.disarm();
        TerrainKernel.setPending(null);
    }
}
