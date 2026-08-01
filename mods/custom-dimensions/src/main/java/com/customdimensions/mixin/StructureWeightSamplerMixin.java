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
    }

    @Inject(method = "createStructureWeightSampler", at = @At("RETURN"))
    private static void customdimensions$disarmTerrainAdaptation(
            StructureAccessor world, ChunkPos pos,
            CallbackInfoReturnable<StructureWeightSampler> cir) {
        TerrainAdaptationOverride.disarm();
    }
}
