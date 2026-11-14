package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import org.junit.jupiter.api.Test;

class LatticeInterpolationTest {
    static final int LO = -64;
    static final int HEIGHT = 384;

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** A hash of the position in [-1, 1]: every corner different, so any misplaced corner or lerp shows. */
    record Hash(long salt) implements DensityFunction {
        static float at(long salt, int x, int y, int z) {
            long h = salt ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
            h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
            h ^= h >>> 29;
            return (h & 0xFFFFFF) / (float) 0x7FFFFF - 1;
        }

        @Override
        public DensitySampler compileSampler(CompileContext context) {
            return new DensitySampler() {
                @Override
                public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume v) {
                    DensitySampler.sampleVolumeNaive(c, out, v, this);
                }

                @Override
                public float sampleValue(SamplerContext c, int x, int y, int z) {
                    return at(salt, x, y, z);
                }
            };
        }

        @Override
        public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return this;
        }

        @Override
        public Interval range() {
            return Interval.of(-1, 1);
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

    /** Vanilla's own interpolation of one noise column from {@code LO}, as {@code getBaseColumn} samples it. */
    static float[] vanillaColumn(DensityFunction f, int x, int z) {
        DensitySampler s = f.compileSampler(null);
        DensityVolume v = new DensityVolume(1, HEIGHT, 1, x, LO, z);
        float[] out = new float[HEIGHT];
        try (ScopedDensityBuffer b = SamplerContext.EMPTY_UNCACHED.acquireBuffer(v)) {
            s.sampleVolume(SamplerContext.EMPTY_UNCACHED, b, v);
            for (int y = 0; y < HEIGHT; y++) out[y] = b.get(v.indexUnchecked(0, y, 0));
        }
        return out;
    }

    /** Every column of an {@code n × n} tile from {@code (x0, z0)} at {@code step}, against vanilla's column, bit for bit. */
    static void assertTile(DensityFunction vanilla, int x0, int z0, int step, int n) {
        DensitySampler s = LatticeInterpolation.rewrite(vanilla).compileSampler(null);
        DensityVolume v = new DensityVolume(n, HEIGHT, n, x0, LO, z0, step, 1, step);
        try (ScopedDensityBuffer b = SamplerContext.EMPTY_UNCACHED.acquireBuffer(v)) {
            s.sampleVolume(SamplerContext.EMPTY_UNCACHED, b, v);
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    float[] expected = vanillaColumn(vanilla, x0 + i * step, z0 + j * step);
                    for (int y = 0; y < HEIGHT; y++) {
                        float got = b.get(v.indexUnchecked(i, y, j));
                        int ii = i;
                        int jj = j;
                        int yy = y;
                        assertEquals(Float.floatToIntBits(expected[y]), Float.floatToIntBits(got),
                                () -> "column " + (x0 + ii * step) + "," + (z0 + jj * step) + " y " + (LO + yy));
                    }
                }
            }
        }
    }

    static DensityFunction interpolated(long salt) {
        return new InterpolatedFunction(new Hash(salt), 4, 8);
    }

    @Test
    void onLatticeStrideMatchesVanilla() {
        assertTile(interpolated(1), 0, 0, 8, 6);
        assertTile(interpolated(2), -64, 128, 16, 4);
        assertTile(interpolated(3), -8, -4, 4, 6);
    }

    @Test
    void offLatticeStrideMatchesVanilla() {
        assertTile(interpolated(4), 1, 3, 1, 9);
        assertTile(interpolated(5), -7, 5, 2, 7);
        assertTile(interpolated(6), 3, -2, 6, 5);
        assertTile(interpolated(7), -13, 22, 5, 5);
    }

    @Test
    void outerTermsAreAppliedPerBlock() {
        DensityFunction f = DensityFunctions.add(DensityFunctions.min(DensityFunctions.squeeze(interpolated(8)),
                DensityFunctions.mul(interpolated(9), DensityFunctions.constant(1.5F))), DensityFunctions.constant(0.1F));
        assertTile(f, 0, 0, 8, 4);
        assertTile(f, 2, 1, 3, 5);
    }

    @Test
    void cornersCountOnlyTheLatticeUnderTheColumns() {
        DensityVolume tile = new DensityVolume(16, HEIGHT, 16, 0, LO, 0, 8, 1, 8);
        assertEquals(16L * 16 * 49, LatticeInterpolation.corners(tile, 4, 8));
        DensityVolume fine = new DensityVolume(16, HEIGHT, 16, 1, LO, 1, 1, 1, 1);
        assertEquals(5L * 5 * 49, LatticeInterpolation.corners(fine, 4, 8));
    }

    @Test
    void rewriteFindsEveryInterpolatedTerm() {
        DensityFunction f = DensityFunctions.min(interpolated(1), interpolated(2));
        assertTrue(LatticeInterpolation.interpolates(f));
        assertFalse(LatticeInterpolation.interpolates(new Hash(1)));
        assertEquals(2, LatticeScanner.cellsOf(f).size());
    }
}
