package com.customdimensions.server.zone;

import com.customdimensions.core.zone.LandShare;
import com.customdimensions.server.CustomDimensions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * {@code customdimensions:land_share}: {@code argument} (a terrain's
 * continentalness) shifted so that {@code share} of it over the border
 * ({@code radius} round the origin) lies at or above {@code coast}. The shift
 * is measured from the seed's own noise when the level's router compiles
 * ({@link LandShare}), so the share holds for any seed and any stretch.
 */
public record LandShareFunction(DensityFunction argument, double share, double coast, int radius) implements DensityFunction {
    public static final MapCodec<LandShareFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.CODEC.fieldOf("argument").forGetter(LandShareFunction::argument),
            Codec.doubleRange(0, 1).fieldOf("share").forGetter(LandShareFunction::share),
            Codec.DOUBLE.fieldOf("coast").forGetter(LandShareFunction::coast),
            Codec.intRange(16, 30_000_000).fieldOf("radius").forGetter(LandShareFunction::radius)).apply(i, LandShareFunction::new));

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        DensitySampler input = argument.compileSampler(context);
        float offset = (float) measure(input);
        return new DensitySampler() {
            @Override
            public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
                input.sampleVolume(ctx, buffer, volume);
                for (int k = 0; k < buffer.size(); k++) buffer.set(k, buffer.get(k) + offset);
            }

            @Override
            public float sampleValue(SamplerContext ctx, int x, int y, int z) {
                return input.sampleValue(ctx, x, y, z) + offset;
            }
        };
    }

    private double measure(DensitySampler input) {
        long start = System.nanoTime();
        double[] samples = new double[LandShare.GRID * LandShare.GRID];
        int y = QuartPos.toBlock(QuartPos.fromBlock(64));
        for (int i = 0; i < LandShare.GRID; i++) {
            int x = QuartPos.toBlock(QuartPos.fromBlock(LandShare.gridCoordinate(i, radius)));
            for (int j = 0; j < LandShare.GRID; j++) {
                int z = QuartPos.toBlock(QuartPos.fromBlock(LandShare.gridCoordinate(j, radius)));
                samples[i * LandShare.GRID + j] = input.sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
            }
        }
        double offset = LandShare.offset(samples, share, coast);
        CustomDimensions.LOG.info("land share: continentalness shifted by {} so {} of a {}-block radius is land ({} measured) in {} ms",
                String.format(java.util.Locale.ROOT, "%.4f", offset), share, radius,
                String.format(java.util.Locale.ROOT, "%.4f", LandShare.landShare(samples, offset, coast)), (System.nanoTime() - start) / 1_000_000);
        return offset;
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction rewritten = rule.rewrite(argument);
        return rewritten == argument ? this : new LandShareFunction(rewritten, share, coast, radius);
    }

    @Override
    public Interval range() {
        Interval in = argument.range();
        return Interval.of((float) (in.min() - LandShare.MAX_SHIFT), (float) (in.max() + LandShare.MAX_SHIFT));
    }

    @Override
    public int domainAxes() {
        return argument.domainAxes();
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
