package com.customdimensions.server.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** A level's per-biome feature lists, swapped by {@link com.customdimensions.server.level.WorldgenRuntime}. */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {

    @Accessor("generationSettingsGetter")
    Function<Holder<Biome>, BiomeGenerationSettings> customdimensions$generationSettingsGetter();

    @Mutable
    @Accessor("generationSettingsGetter")
    void customdimensions$setGenerationSettingsGetter(Function<Holder<Biome>, BiomeGenerationSettings> getter);

    @Mutable
    @Accessor("featuresPerStep")
    void customdimensions$setFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep);
}
