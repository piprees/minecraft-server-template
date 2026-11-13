package com.customdimensions.server.render.fast;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;

/**
 * Every block of a tile of columns from one batch of a final density whose
 * {@code interpolated} terms are {@link LatticeInterpolation}s: those terms
 * sample their inputs only on the cell corners under the tile's columns and
 * fill the blocks as vanilla interpolates them, and the terms outside them
 * (squeeze, min, range choices, the beardifier) are vanilla's own, applied
 * per block. So each block's density is the one a noise column of the same
 * column gets, with no scout and no refinement, and a layer thinner than a
 * cell is kept. One scanner per thread.
 */
public final class LatticeScanner {
    private final DensitySampler.Bound density;
    private final List<int[]> cells;
    private final int lo;
    private final int hi;
    private boolean[] solid;
    private long columns;
    private long corners;

    /**
     * @param density the final density with its {@code interpolated} terms rewritten ({@link LatticeInterpolation#rewrite})
     * @param original the final density as the generator has it, for its interpolation cells
     */
    public LatticeScanner(DensitySampler.Bound density, DensityFunction original, int lo, int hi) {
        if (hi < lo) throw new IllegalArgumentException("noise range " + lo + ".." + hi);
        this.density = density;
        this.cells = cellsOf(original);
        this.lo = lo;
        this.hi = hi;
        this.solid = new boolean[hi - lo + 1];
    }

    /** The cell {xz, y} of each {@code interpolated} occurrence in {@code function}. */
    static List<int[]> cellsOf(DensityFunction function) {
        List<int[]> out = new ArrayList<>();
        new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
                if (f instanceof InterpolatedFunction i) {
                    out.add(new int[] {i.cellSizeXz(), i.cellSizeY()});
                    return f;
                }
                return f.rewriteChildren(this);
            }
        }.rewrite(function);
        return out;
    }

    /** The runs of the {@code nx × nz} columns at {@code (x0 + i * step, z0 + j * step)}, index {@code i + nx * j}. */
    public ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz) {
        return scan(x0, z0, step, nx, nz, lo, hi);
    }

    /**
     * As {@link #scan(int, int, int, int, int)} over blocks {@code from} to
     * {@code to} only. {@code from} must be {@code lo} or the bottom of an
     * interpolation cell row ({@link #align()}), so each block keeps the value
     * a whole column gives it.
     */
    public ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz, int from, int to) {
        if (from < lo || to > hi || from > to) throw new IllegalArgumentException("window " + from + ".." + to);
        int ny = to - from + 1;
        DensityVolume v = new DensityVolume(nx, ny, nz, x0, from, z0, step, 1, step);
        ColumnRuns[] out = new ColumnRuns[nx * nz];
        try (ScopedDensityBuffer buffer = density.sampleVolume(v)) {
            for (int j = 0; j < nz; j++) {
                for (int i = 0; i < nx; i++) {
                    int base = v.indexUnchecked(i, 0, j);
                    for (int y = 0; y < ny; y++) solid[y] = buffer.get(base + y) > 0;
                    out[i + nx * j] = ColumnRuns.of(solid, from, ny);
                }
            }
        }
        for (int[] c : cells) corners += LatticeInterpolation.corners(v, c[0], c[1]);
        columns += (long) nx * nz;
        return out;
    }

    /** Corner evaluations of the interpolated terms' inputs, over every term, so far. */
    public long corners() {
        return corners;
    }

    public long columns() {
        return columns;
    }

    /** {@code interpolated} occurrences in the final density. */
    public int terms() {
        return cells.size();
    }

    /** The cell height a window's floor snaps to: the tallest term's, when every term's divides it; 0 when none does. */
    public int align() {
        int a = 0;
        for (int[] c : cells) a = Math.max(a, c[1]);
        for (int[] c : cells) if (a % c[1] != 0) return 0;
        return a;
    }
}
