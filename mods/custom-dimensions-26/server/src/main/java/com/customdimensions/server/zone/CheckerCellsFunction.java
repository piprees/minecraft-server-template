package com.customdimensions.server.zone;

import com.customdimensions.core.zone.CheckerCells;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;

/**
 * {@code customdimensions:checker_cells}: 1 in the checkerboard cells whose
 * biome is one of {@code cells} (indices into a {@code count}-biome list at
 * {@code scale}, as {@link CheckerCells} computes vanilla's), 0 elsewhere;
 * horizontal only.
 */
public record CheckerCellsFunction(int scale, int count, List<Integer> cells) implements DensityFunction {
    public static final MapCodec<CheckerCellsFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.intRange(0, 62).fieldOf("scale").forGetter(CheckerCellsFunction::scale),
            Codec.intRange(1, 4096).fieldOf("count").forGetter(CheckerCellsFunction::count),
            Codec.INT.listOf().fieldOf("cells").forGetter(CheckerCellsFunction::cells)).apply(i, CheckerCellsFunction::new));

    public CheckerCellsFunction {
        cells = List.copyOf(cells);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        boolean[] marked = new boolean[count];
        for (int c : cells) if (c >= 0 && c < count) marked[c] = true;
        return new DensitySampler() {
            @Override
            public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
                DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
            }

            @Override
            public float sampleValue(SamplerContext ctx, int x, int y, int z) {
                return marked[CheckerCells.index(x, z, scale, count)] ? 1f : 0f;
            }
        };
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return Interval.of(0f, 1f);
    }

    @Override
    public int domainAxes() {
        return AXIS_X | AXIS_Z;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
