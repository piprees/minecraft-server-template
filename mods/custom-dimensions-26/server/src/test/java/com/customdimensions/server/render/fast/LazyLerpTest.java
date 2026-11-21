package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.LerpFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LazyLerpTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** An alpha of 0, 1 or a fraction in bands up each column, so both sides are used in runs. */
    record Bands(long salt) implements DensityFunction {
        @Override
        public DensitySampler compileSampler(CompileContext context) {
            return new DensitySampler() {
                @Override
                public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume v) {
                    DensitySampler.sampleVolumeNaive(c, out, v, this);
                }

                @Override
                public float sampleValue(SamplerContext c, int x, int y, int z) {
                    float h = LatticeInterpolationTest.Hash.at(salt, x >> 3, y >> 4, z >> 3);
                    return h < -0.3F ? 0.0F : h > 0.3F ? 1.0F : h + 0.3F;
                }
            };
        }

        @Override
        public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return this;
        }

        @Override
        public Interval range() {
            return Interval.of(0, 1);
        }

        @Override
        public int domainAxes() {
            return ALL_AXES;
        }

        @Override
        public MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    static void assertSame(DensityFunction vanilla, DensityVolume v) {
        DensitySampler expected = vanilla.compileSampler(null);
        DensitySampler lazy = LazyLerp.rewrite(vanilla).compileSampler(null);
        try (ScopedDensityBuffer a = SamplerContext.EMPTY_UNCACHED.acquireBuffer(v);
             ScopedDensityBuffer b = SamplerContext.EMPTY_UNCACHED.acquireBuffer(v)) {
            expected.sampleVolume(SamplerContext.EMPTY_UNCACHED, a, v);
            lazy.sampleVolume(SamplerContext.EMPTY_UNCACHED, b, v);
            for (int i = 0; i < v.size(); i++) {
                int ii = i;
                assertEquals(Float.floatToIntBits(a.get(i)), Float.floatToIntBits(b.get(i)), () -> "point " + ii);
            }
        }
    }

    @Test
    void lazySidesGiveVanillaValues() {
        DensityVolume v = new DensityVolume(6, 96, 5, -40, -64, 16, 8, 4, 8);
        assertSame(new LerpFunction(new Bands(1), new LatticeInterpolationTest.Hash(2), new LatticeInterpolationTest.Hash(3)), v);
        assertSame(new LerpFunction(new Bands(4), DensityFunctions.constant(2.5F), new LatticeInterpolationTest.Hash(5)), v);
        assertSame(new LerpFunction(new Bands(6), new LatticeInterpolationTest.Hash(7), DensityFunctions.constant(-0.5F)), v);
    }

    @Test
    void nestedLerpsInsideOtherTermsStayVanilla() {
        DensityFunction inner = new LerpFunction(new Bands(8), DensityFunctions.constant(0.9375F), new LatticeInterpolationTest.Hash(9));
        DensityFunction outer = DensityFunctions.squeeze(DensityFunctions.mul(
                new LerpFunction(new Bands(10), DensityFunctions.constant(2.5F), inner), DensityFunctions.constant(0.64F)));
        assertSame(outer, new DensityVolume(4, 200, 4, 3, -64, -9, 4, 1, 4));
    }

    @Test
    void sampleValueMatchesVanilla() {
        DensityFunction f = new LerpFunction(new Bands(11), new LatticeInterpolationTest.Hash(12), new LatticeInterpolationTest.Hash(13));
        DensitySampler expected = f.compileSampler(null);
        DensitySampler lazy = LazyLerp.rewrite(f).compileSampler(null);
        for (int y = -64; y < 64; y += 3) {
            assertEquals(expected.sampleValue(SamplerContext.EMPTY_UNCACHED, 5, y, -7), lazy.sampleValue(SamplerContext.EMPTY_UNCACHED, 5, y, -7));
        }
    }
}
