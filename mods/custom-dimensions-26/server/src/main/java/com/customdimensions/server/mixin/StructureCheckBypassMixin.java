package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * The structure search's fast check predicts a start as generation makes it:
 * a forced start or a want ignores its biomes in its own chunk, and a forced
 * start with a {@code y} finds its ground at that height
 * ({@link StructureBiomeBypassMixin}).
 */
@Mixin(StructureCheck.class)
abstract class StructureCheckBypassMixin {
    @Shadow @Final private ResourceKey<Level> dimension;

    @WrapOperation(method = "canCreateStructure",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/Structure;findValidGenerationPoint(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;)Ljava/util/Optional;"))
    private Optional<Structure.GenerationStub> customdimensions$bypassBiome(Structure structure, Structure.GenerationContext context,
            Operation<Optional<Structure.GenerationStub>> original, @Local(argsOnly = true) ChunkPos pos) {
        boolean bypass = StructureRuntime.bypassesBiome(dimension, structure, pos);
        Integer ground = StructureRuntime.forcedGround(dimension, structure, pos);
        if (!bypass && ground == null) {
            return original.call(structure, context);
        }
        return original.call(structure, new Structure.GenerationContext(context.registryAccess(),
                StructureRuntime.ground(context.chunkGenerator(), ground), context.biomeSource(), context.climateSampler(),
                context.randomState(), context.structureTemplateManager(), context.seed(), context.chunkPos(),
                context.heightAccessor(), bypass ? b -> true : context.validBiome()));
    }
}
