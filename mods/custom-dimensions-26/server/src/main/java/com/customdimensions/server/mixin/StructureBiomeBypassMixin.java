package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/** A forced start or a want generates in its own chunk whatever the biome there; a structure a zone shuns does not start inside it; every other start keeps vanilla's check. */
@Mixin(ChunkGenerator.class)
abstract class StructureBiomeBypassMixin {
    @WrapOperation(method = "tryGenerateStructure",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/Structure;generate(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"))
    private StructureStart customdimensions$bypassBiome(Structure structure, Holder<Structure> holder, ResourceKey<Level> level,
            RegistryAccess registries, ChunkGenerator generator, BiomeSource biomes, Climate.Sampler sampler, RandomState random,
            StructureTemplateManager templates, long seed, ChunkPos chunk, int references, LevelHeightAccessor height,
            Predicate<Holder<Biome>> valid, Operation<StructureStart> original) {
        if (StructureRuntime.zoneShunned(level, holder, chunk)) {
            return StructureStart.INVALID_START;
        }
        Predicate<Holder<Biome>> test = StructureRuntime.bypassesBiome(level, structure, chunk) ? b -> true : valid;
        return original.call(structure, holder, level, registries, generator, biomes, sampler, random, templates, seed, chunk,
                references, height, test);
    }
}
