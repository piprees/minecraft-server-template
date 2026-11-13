package com.customdimensions.server.mixin;

import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The material rule's context outside a chunk, for {@link com.customdimensions.server.render.fast.TileSampler}'s run faces. */
@Mixin(MaterialRuleContext.class)
public interface MaterialRuleContextAccessor {

    @Invoker("<init>")
    static MaterialRuleContext customdimensions$create(MaterialSystem system, RandomState random, DensityVolume expected,
                                                       DensitySamplerSet samplers, Function<BlockPos, Holder<Biome>> biomes,
                                                       WorldGenerationContext context, Set<Holder<Biome>> possibleBiomes) {
        throw new AssertionError();
    }

    @Invoker("updateXZ")
    void customdimensions$updateXZ(int x, int z, int gradientX, int gradientZ);

    @Invoker("updateY")
    void customdimensions$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int y);
}
