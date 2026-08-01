package com.customdimensions.mixin;

import com.customdimensions.dimension.TerrainAdaptationOverride;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.StructureWeightSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arms the per-dimension terrain-adaptation override for exactly the dynamic
 * extent of {@code createStructureWeightSampler} — the only place vanilla
 * reads {@code Structure.getTerrainAdaptation()} for the Beardifier, both as
 * the {@code != NONE} filter predicate and per collected start. The forEach
 * runs synchronously inside the method on the same thread, so a HEAD/RETURN
 * ThreadLocal pair covers both call sites without touching any lambda.
 *
 * The world comes from the StructureAccessor (a ChunkRegion during noise
 * fill); resolving it can never be allowed to fail generation, so any
 * surprise view type degrades to "no override" rather than throwing.
 */
@Mixin(StructureWeightSampler.class)
public abstract class StructureWeightSamplerMixin
        implements com.customdimensions.dimension.TerrainKernel.Carrier {

    // Kernel pieces ride the vanilla instance (duck field), never a wrapper
    // — see TerrainKernel.Carrier for the Moog's-coexistence reason.
    @org.spongepowered.asm.mixin.Unique
    private volatile java.util.List<com.customdimensions.dimension.TerrainKernel.Piece>
            customdimensions$kernelPieces;

    @Override
    public void customdimensions$setKernelPieces(
            java.util.List<com.customdimensions.dimension.TerrainKernel.Piece> pieces) {
        this.customdimensions$kernelPieces = pieces;
    }

    @Override
    public java.util.List<com.customdimensions.dimension.TerrainKernel.Piece>
            customdimensions$getKernelPieces() {
        return this.customdimensions$kernelPieces;
    }

    @Inject(method = "sample", at = @At("RETURN"), cancellable = true)
    private void customdimensions$addKernelDensity(
            net.minecraft.world.gen.densityfunction.DensityFunction.NoisePos pos,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> cir) {
        java.util.List<com.customdimensions.dimension.TerrainKernel.Piece> pieces =
                this.customdimensions$kernelPieces;
        com.customdimensions.dimension.TerrainKernel.debugSample(
                System.identityHashCode(this), pieces != null);
        if (pieces != null && !pieces.isEmpty()) {
            cir.setReturnValue(cir.getReturnValue()
                    + com.customdimensions.dimension.TerrainKernel.sampleAll(pieces, pos));
        }
    }

    @Inject(method = "createStructureWeightSampler", at = @At("HEAD"))
    private static void customdimensions$armTerrainAdaptation(
            StructureAccessor world, ChunkPos pos,
            CallbackInfoReturnable<StructureWeightSampler> cir) {
        Identifier worldId = null;
        try {
            WorldAccess access = ((StructureAccessorAccessor) world).getWorld();
            // ChunkRegion (noise fill) and ServerWorld both implement
            // ServerWorldAccess; anything else has no dimension to resolve.
            ServerWorld server = access instanceof net.minecraft.world.ServerWorldAccess swa
                    ? swa.toServerWorld() : null;
            if (server != null) {
                worldId = server.getRegistryKey().getValue();
            }
        } catch (RuntimeException e) {
            // An unexpected world view (no server world behind it) means no
            // per-dimension config applies — vanilla behaviour, not an error.
        }
        TerrainAdaptationOverride.arm(worldId);
    }

    @Inject(method = "createStructureWeightSampler", at = @At("RETURN"), cancellable = true)
    private static void customdimensions$disarmTerrainAdaptation(
            StructureAccessor world, ChunkPos pos,
            CallbackInfoReturnable<StructureWeightSampler> cir) {
        try {
            // Kernel-tagged structures read NONE to vanilla, so its sampler
            // ignored them above; collect their pieces while still armed and
            // attach them to the RETURNED instance (duck field — never a
            // wrapper). Kernel-free worlds pay one boolean check.
            if (com.customdimensions.dimension.TerrainAdaptationOverride.hasArmedKernels()) {
                java.util.List<com.customdimensions.dimension.TerrainKernel.Piece> pieces =
                        com.customdimensions.dimension.TerrainKernel.collect(world, pos);
                com.customdimensions.dimension.TerrainKernel.debugAttach(pos, pieces.size(),
                        System.identityHashCode(cir.getReturnValue()));
                if (!pieces.isEmpty()) {
                    ((com.customdimensions.dimension.TerrainKernel.Carrier) (Object)
                            cir.getReturnValue()).customdimensions$setKernelPieces(pieces);
                }
            }
        } finally {
            TerrainAdaptationOverride.disarm();
        }
    }
}
