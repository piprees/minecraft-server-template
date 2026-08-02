package com.customdimensions.mixin;

import com.customdimensions.dimension.TerrainAdaptationOverride;
import com.customdimensions.dimension.TerrainKernel;
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
 * Arms the per-dimension terrain-adaptation override for the beard
 * factory's extent — the only place vanilla reads
 * {@code Structure.getTerrainAdaptation()} for the Beardifier — and
 * collects kernel pieces into the pending stash while armed.
 *
 * HEAD only, deliberately: this method's RETURN callbacks are starved on
 * the platform modstack (three other mods hook it, two with cancellable
 * replacement callbacks — live-verified 2026-08-02: our RETURN handlers
 * merged but never executed at priority 900 or 2000). The extent therefore
 * ENDS in ChunkNoiseSamplerMixin's TAIL, which vanilla's noise-fill path
 * constructs immediately after this factory on the same thread.
 *
 * The world comes from the StructureAccessor (a ChunkRegion during noise
 * fill); resolving it can never be allowed to fail generation, so any
 * surprise view type degrades to "no override" rather than throwing.
 */
@Mixin(StructureWeightSampler.class)
public abstract class StructureWeightSamplerMixin {

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
        // Kernel-tagged structures read NONE to vanilla, so no beardifier
        // collects them; their pieces ride the stash to KernelDensity via
        // ChunkNoiseSamplerMixin. Kernel-free worlds pay one boolean check.
        java.util.List<TerrainKernel.Piece> pieces = null;
        if (TerrainAdaptationOverride.hasArmedKernels()) {
            pieces = TerrainKernel.collect(world, pos);
        }
        TerrainKernel.setPending(pieces);
    }
}
