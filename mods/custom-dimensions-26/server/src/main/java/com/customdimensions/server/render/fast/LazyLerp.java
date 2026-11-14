package com.customdimensions.server.render.fast;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

/**
 * A {@code lerp} (and {@code old_blended_noise}, which is a lerp between two
 * limit noises) that, sampled over a volume, samples each side only on the
 * runs of blocks up a column that use it: {@code first} where alpha is not
 * 1, {@code second} where it is not 0. Vanilla's volume path samples both
 * sides everywhere. Each value is still vanilla's own: a side is sampled in
 * whole runs through the same volume code, whose value at a point does not
 * depend on the volume around it, and combined with the same lerp.
 */
public record LazyLerp(DensityFunction original) implements DensityFunction {
    static final MapCodec<LazyLerp> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("a render-only density function is never decoded");
    });
    /** A side used by more than this share of a volume's points is sampled over the whole volume. */
    static final double WHOLE = 0.9;

    /** {@code function} with every {@code lerp} and {@code old_blended_noise} made lazy. */
    public static DensityFunction rewrite(DensityFunction function) {
        DfRewriteRule rule = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof BlendedNoise) return new LazyLerp(f);
                if (f instanceof LerpFunction) return new LazyLerp(f.rewriteChildren(this));
                return f.rewriteChildren(this);
            }
        };
        return rule.rewrite(function);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        DensitySampler s = original.compileSampler(context);
        return switch (s) {
            case LerpFunction.Sampler(DensitySampler a, DensitySampler f, DensitySampler g) -> new Sampler(a, f, 0, g, 0);
            case LerpFunction.ConstFirstSampler(DensitySampler a, float f, DensitySampler g) -> new Sampler(a, null, f, g, 0);
            case LerpFunction.ConstSecondSampler(DensitySampler a, DensitySampler f, float g) -> new Sampler(a, f, 0, null, g);
            default -> s;
        };
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction rewritten = original.rewriteChildren(rule);
        return rewritten == original ? this : new LazyLerp(rewritten);
    }

    @Override
    public Interval range() {
        return original.range();
    }

    @Override
    public @Axes int domainAxes() {
        return original.domainAxes();
    }

    @Override
    public MapCodec<LazyLerp> codec() {
        return CODEC;
    }

    /** A null side is the constant beside it. */
    record Sampler(DensitySampler alpha, DensitySampler first, float firstValue, DensitySampler second, float secondValue)
            implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume volume) {
            alpha.sampleVolume(context, out, volume);
            int n = volume.size();
            int needFirst = 0;
            int needSecond = 0;
            for (int i = 0; i < n; i++) {
                float a = out.get(i);
                if (a != 1.0F) needFirst++;
                if (a != 0.0F) needSecond++;
            }
            try (ScopedDensityBuffer f = first == null || needFirst == 0 ? null : side(context, first, out, volume, needFirst, 1.0F);
                 ScopedDensityBuffer g = second == null || needSecond == 0 ? null : side(context, second, out, volume, needSecond, 0.0F)) {
                for (int i = 0; i < n; i++) {
                    float a = out.get(i);
                    float fv = first == null ? firstValue : a == 1.0F ? 0 : f.get(i);
                    float gv = second == null ? secondValue : a == 0.0F ? 0 : g.get(i);
                    if (a == 0.0F) {
                        out.set(i, fv);
                    } else if (a == 1.0F) {
                        out.set(i, gv);
                    } else {
                        out.set(i, Mth.lerp(a, fv, gv));
                    }
                }
            }
        }

        /** {@code side} at every point whose alpha is not {@code unused}, in runs up each column; other points are left unset. */
        private static ScopedDensityBuffer side(SamplerContext context, DensitySampler side, DensityBuffer alphas, DensityVolume volume,
                                                int needed, float unused) {
            ScopedDensityBuffer buffer = context.acquireBuffer(volume);
            if (needed > WHOLE * volume.size()) {
                side.sampleVolume(context, buffer, volume);
                return buffer;
            }
            int ny = volume.sizeY();
            for (int z = 0; z < volume.sizeZ(); z++) {
                for (int x = 0; x < volume.sizeX(); x++) {
                    int base = volume.indexUnchecked(x, 0, z);
                    int y = 0;
                    while (y < ny) {
                        while (y < ny && alphas.get(base + y) == unused) y++;
                        if (y == ny) break;
                        int start = y;
                        while (y < ny && alphas.get(base + y) != unused) y++;
                        DensityVolume run = new DensityVolume(1, y - start, 1, volume.blockX(x), volume.blockY(start), volume.blockZ(z),
                                volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ());
                        try (ScopedDensityBuffer part = context.acquireBuffer(run)) {
                            side.sampleVolume(context, part, run);
                            for (int k = 0; k < y - start; k++) buffer.set(base + start + k, part.get(k));
                        }
                    }
                }
            }
            return buffer;
        }

        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            float a = alpha.sampleValue(context, x, y, z);
            if (a == 0.0F) return first == null ? firstValue : first.sampleValue(context, x, y, z);
            float g = second == null ? secondValue : second.sampleValue(context, x, y, z);
            if (a == 1.0F) return g;
            return Mth.lerp(a, first == null ? firstValue : first.sampleValue(context, x, y, z), g);
        }
    }
}
