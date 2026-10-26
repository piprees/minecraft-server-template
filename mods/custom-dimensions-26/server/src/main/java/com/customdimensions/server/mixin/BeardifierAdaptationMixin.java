package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureAdaptation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/** The terrain around a structure follows its dimension's {@code terrainAdaptation}, else the structure's own. */
@Mixin(Beardifier.class)
abstract class BeardifierAdaptationMixin {
    @WrapOperation(method = "forStructuresInChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(IILjava/util/function/Predicate;)Ljava/util/List;"))
    private static List<StructureStart> customdimensions$adaptedStarts(StructureManager manager, int x, int z,
            Predicate<Structure> vanilla, Operation<List<StructureStart>> original) {
        return original.call(manager, x, z, (Predicate<Structure>) s -> StructureAdaptation.of(manager, s) != TerrainAdjustment.NONE);
    }

    @WrapOperation(method = "forStructuresInChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/Structure;terrainAdaptation()Lnet/minecraft/world/level/levelgen/structure/TerrainAdjustment;"))
    private static TerrainAdjustment customdimensions$adaptation(Structure structure, Operation<TerrainAdjustment> original,
            @Local(argsOnly = true) StructureManager manager) {
        return StructureAdaptation.of(manager, structure);
    }
}
