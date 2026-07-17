package com.customdimensions.mixin;

import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets void dimensions swap the flat generator's fixed biome source for a
 * filtered multi-noise source AFTER construction — FlatChunkGenerator has no
 * constructor that takes a biome source, but a void world should still carry
 * the biome layout its config lists (mob spawning, fog, ambience).
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    @Accessor("biomeSource")
    BiomeSource getBiomeSourceField();

    @Mutable
    @Accessor("biomeSource")
    void setBiomeSource(BiomeSource biomeSource);
}
