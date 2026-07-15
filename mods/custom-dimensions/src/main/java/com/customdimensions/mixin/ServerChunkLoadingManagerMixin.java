package com.customdimensions.mixin;

import com.customdimensions.dimension.DimensionStructures;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-dimension structure density: after the vanilla constructor builds the
 * world's StructurePlacementCalculator from the shared registry, replace it
 * with a transformed copy for dimensions that declare structureDensity or
 * peaceful behaviour. Runs once per world load — chunk generation and
 * /locate both read this calculator, so they stay consistent.
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    @Final
    private NoiseConfig noiseConfig;

    @Shadow
    @Final
    @Mutable
    private StructurePlacementCalculator structurePlacementCalculator;

    @Shadow
    protected abstract ChunkGenerator getChunkGenerator();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void customdimensions$applyStructureProfile(CallbackInfo ci) {
        StructurePlacementCalculator replaced = DimensionStructures.transformed(
                this.world,
                this.getChunkGenerator().getBiomeSource(),
                this.noiseConfig,
                this.structurePlacementCalculator);
        if (replaced != null) {
            this.structurePlacementCalculator = replaced;
        }
    }
}
