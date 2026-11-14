package com.customdimensions.server.render.fast;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Mth;
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

/**
 * {@code interpolated} for a volume of any step: its input sampled once on
 * the cell corners the volume's columns sit between, and each requested
 * block filled as vanilla's {@link InterpolatedFunction} fills a cell (the
 * same lerps in the same order, and the same running sum up each cell from
 * the volume's lowest block), so a block gets the value a noise column of
 * the same lowest block gets. Vanilla's own sampler meets a volume whose
 * step is not the cell's by sampling every block under it; this one samples
 * only the corners: a 16 × 16 tile of columns at a step of 8 costs the
 * corners under its columns, not the 128 × 128 blocks under the tile.
 */
public record LatticeInterpolation(DensityFunction input, int cellXz, int cellY) implements DensityFunction {
    static final MapCodec<LatticeInterpolation> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("a render-only density function is never decoded");
    });

    /** {@code function} with every {@code interpolated} replaced by a {@link LatticeInterpolation} of the same cell. */
    public static DensityFunction rewrite(DensityFunction function) {
        DfRewriteRule rule = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof InterpolatedFunction i) return new LatticeInterpolation(this.rewrite(i.input()), i.cellSizeXz(), i.cellSizeY());
                return f.rewriteChildren(this);
            }
        };
        return rule.rewrite(function);
    }

    /** Whether {@code function} holds an {@code interpolated}. */
    public static boolean interpolates(DensityFunction function) {
        boolean[] found = {false};
        new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof InterpolatedFunction) found[0] = true;
                return f.rewriteChildren(this);
            }
        }.rewrite(function);
        return found[0];
    }

    /** The corners a volume's columns need along one horizontal axis: first corner, count, spacing. */
    record Axis(int first, int count, int spacing, boolean onCorners) {
        static Axis of(int min, int size, int step, int cell) {
            if (step % cell == 0 && Math.floorMod(min, cell) == 0) return new Axis(min, size, step, true);
            int max = min + (size - 1) * step;
            int c0 = Math.floorDiv(min, cell);
            int c1 = Math.floorDiv(max, cell);
            return new Axis(c0 * cell, c1 - c0 + (Math.floorMod(max, cell) == 0 ? 1 : 2), cell, false);
        }
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(input.compileSampler(context), cellXz, cellY, 1.0F / cellXz, 1.0F / cellY);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction rewritten = rule.rewrite(input);
        return rewritten == input ? this : new LatticeInterpolation(rewritten, cellXz, cellY);
    }

    @Override
    public Interval range() {
        return input.range();
    }

    @Override
    public @Axes int domainAxes() {
        return input.domainAxes();
    }

    @Override
    public MapCodec<LatticeInterpolation> codec() {
        return CODEC;
    }

    /** Points the input is sampled at for a volume: its corner lattice. */
    public static long corners(DensityVolume volume, int cellXz, int cellY) {
        if (onCellLattice(volume, cellXz, cellY)) return volume.size();
        Axis x = Axis.of(volume.minBlockX(), volume.sizeX(), volume.stepBlockX(), cellXz);
        Axis z = Axis.of(volume.minBlockZ(), volume.sizeZ(), volume.stepBlockZ(), cellXz);
        return (long) x.count() * z.count() * rows(volume, cellY);
    }

    static boolean onCellLattice(DensityVolume v, int cellXz, int cellY) {
        return (v.stepBlockX() == cellXz || v.sizeX() == 1) && (v.stepBlockY() == cellY || v.sizeY() == 1)
                && (v.stepBlockZ() == cellXz || v.sizeZ() == 1) && Math.floorMod(v.minBlockX(), cellXz) == 0
                && Math.floorMod(v.minBlockY(), cellY) == 0 && Math.floorMod(v.minBlockZ(), cellXz) == 0;
    }

    /** Corner rows under a volume: the cells from its lowest block through the {@code sizeY × stepY} blocks vanilla fills. */
    static int rows(DensityVolume v, int cellY) {
        int maxY = v.minBlockY() + v.sizeY() * v.stepBlockY() - 1;
        int c0 = Math.floorDiv(v.minBlockY(), cellY);
        int c1 = Math.floorDiv(maxY, cellY);
        return c1 - c0 + (Math.floorMod(maxY, cellY) == 0 ? 1 : 2);
    }

    record Sampler(DensitySampler input, int cellXz, int cellY, float cellXzInv, float cellYInv) implements DensitySampler {
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume volume) {
            if (onCellLattice(volume, cellXz, cellY)) {
                input.sampleVolume(context, out, volume);
                return;
            }
            Axis ax = Axis.of(volume.minBlockX(), volume.sizeX(), volume.stepBlockX(), cellXz);
            Axis az = Axis.of(volume.minBlockZ(), volume.sizeZ(), volume.stepBlockZ(), cellXz);
            // The blocks vanilla fills for this volume run from its lowest block up through sizeY * stepY blocks.
            int minY = volume.minBlockY();
            int sy = volume.stepBlockY();
            int maxY = minY + volume.sizeY() * sy - 1;
            int row0 = Math.floorDiv(minY, cellY);
            int ny = rows(volume, cellY);
            DensityVolume lattice = new DensityVolume(ax.count(), ny, az.count(), ax.first(), row0 * cellY, az.first(),
                    ax.spacing(), cellY, az.spacing());
            try (ScopedDensityBuffer corners = context.acquireBuffer(lattice)) {
                input.sampleVolume(context, corners, lattice);
                for (int iz = 0; iz < volume.sizeZ(); iz++) {
                    int bz = volume.blockZ(iz);
                    int cz;
                    float alphaZ;
                    if (az.onCorners()) {
                        cz = iz;
                        alphaZ = 0;
                    } else {
                        cz = Math.floorDiv(bz, cellXz) - Math.floorDiv(az.first(), cellXz);
                        alphaZ = (bz - Math.floorDiv(bz, cellXz) * cellXz) * cellXzInv;
                    }
                    int cz1 = Math.min(cz + 1, az.count() - 1);
                    for (int ix = 0; ix < volume.sizeX(); ix++) {
                        int bx = volume.blockX(ix);
                        int cx;
                        float alphaX;
                        if (ax.onCorners()) {
                            cx = ix;
                            alphaX = 0;
                        } else {
                            cx = Math.floorDiv(bx, cellXz) - Math.floorDiv(ax.first(), cellXz);
                            alphaX = (bx - Math.floorDiv(bx, cellXz) * cellXz) * cellXzInv;
                        }
                        int cx1 = Math.min(cx + 1, ax.count() - 1);
                        column(out, volume, ix, iz, corners, lattice, cx, cx1, cz, cz1, alphaX, alphaZ, minY, maxY, sy, row0, ny);
                    }
                }
            }
        }

        private void column(DensityBuffer out, DensityVolume volume, int ix, int iz, DensityBuffer corners, DensityVolume lattice,
                            int cx, int cx1, int cz, int cz1, float alphaX, float alphaZ, int minY, int maxY, int sy, int row0,
                            int ny) {
            int i00 = lattice.indexUnchecked(cx, 0, cz);
            int i10 = lattice.indexUnchecked(cx1, 0, cz);
            int i01 = lattice.indexUnchecked(cx, 0, cz1);
            int i11 = lattice.indexUnchecked(cx1, 0, cz1);
            int outBase = volume.indexUnchecked(ix, 0, iz);
            int cells = Math.floorDiv(maxY, cellY) - row0 + 1;
            for (int r = 0; r < cells; r++) {
                int next = Math.min(r + 1, ny - 1);
                int cellStart = (row0 + r) * cellY;
                int from = Math.max(cellStart, minY);
                int to = Math.min(cellStart + cellY - 1, maxY);
                if (from > to) continue;
                float v0 = Mth.lerp(alphaX, Mth.lerp(alphaZ, corners.get(i00 + r), corners.get(i01 + r)),
                        Mth.lerp(alphaZ, corners.get(i10 + r), corners.get(i11 + r)));
                float v1 = Mth.lerp(alphaX, Mth.lerp(alphaZ, corners.get(i00 + next), corners.get(i01 + next)),
                        Mth.lerp(alphaZ, corners.get(i10 + next), corners.get(i11 + next)));
                float valueStep = (v1 - v0) * cellYInv;
                float value = v0 + valueStep * (from - cellStart);
                for (int y = from; y <= to; y++) {
                    int k = y - minY;
                    if (k % sy == 0) out.set(outBase + k / sy, value);
                    value += valueStep;
                }
            }
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            int xIn = Math.floorMod(blockX, cellXz);
            int yIn = Math.floorMod(blockY, cellY);
            int zIn = Math.floorMod(blockZ, cellXz);
            if (xIn == 0 && yIn == 0 && zIn == 0) return input.sampleValue(context, blockX, blockY, blockZ);
            DensityVolume v = new DensityVolume(2, 2, 2, blockX - xIn, blockY - yIn, blockZ - zIn, cellXz, cellY, cellXz);
            try (ScopedDensityBuffer b = context.acquireBuffer(v)) {
                input.sampleVolume(context, b, v);
                return Mth.lerp3((float) xIn / cellXz, (float) yIn / cellY, (float) zIn / cellXz,
                        b.get(v.indexUnchecked(0, 0, 0)), b.get(v.indexUnchecked(1, 0, 0)), b.get(v.indexUnchecked(0, 1, 0)),
                        b.get(v.indexUnchecked(1, 1, 0)), b.get(v.indexUnchecked(0, 0, 1)), b.get(v.indexUnchecked(1, 0, 1)),
                        b.get(v.indexUnchecked(0, 1, 1)), b.get(v.indexUnchecked(1, 1, 1)));
            }
        }
    }
}
