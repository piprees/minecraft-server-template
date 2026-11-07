package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A level's structure state takes its dimension's structure control as the chunk map builds it, base and Fantasy levels alike. */
@Mixin(ChunkMap.class)
abstract class ChunkMapStructuresMixin {
    @ModifyExpressionValue(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;createState(Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J)Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"))
    private ChunkGeneratorStructureState customdimensions$structureControl(ChunkGeneratorStructureState state,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) ChunkGenerator generator) {
        return StructureRuntime.apply(level.dimension(), level.registryAccess(), generator, state);
    }
}
