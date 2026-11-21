// The scout-then-refine search here follows seedlod's tile generator (dev.winter.seedlod.gen.TileGen,
// MIT, (c) 2026 Derped; THIRD_PARTY_NOTICES.md): sample the density sparsely on its interpolation
// cell corners, then sample exactly only where that finds terrain.
package com.customdimensions.server.render.fast;

/**
 * Finds every solid run of a tile of columns from a density function
 * without filling the columns. A scout samples the density on rows one
 * interpolation cell apart ({@code cellY} blocks); each cell whose two rows
 * disagree in sign holds a crossing, and only that cell is sampled block by
 * block through the exact density. A cell whose rows agree is taken to hold
 * no crossing, so a solid or open layer thinner than a cell and between two
 * rows is missed.
 *
 * <p>The scout samples the columns themselves when they sit on the cell
 * lattice (a stride that is a multiple of the cell and a lattice-aligned
 * origin), where the density there is the function's own value, and when the
 * stride is at least a cell, where the lattice round the tile would hold more
 * points than the tile has columns (its value there is the density with the
 * interpolation left out, close to it; the refined cells are exact). A finer
 * stride off the lattice samples the lattice round the tile and interpolates
 * each column's rows between its four corners. One scanner per thread: it
 * keeps scratch arrays.
 */
public final class RunScanner {
    /** Row spacing for a density that is not interpolated. */
    public static final int PLAIN_ROW = 8;

    /** A density function sampled in batches. */
    public interface Density {
        /**
         * Fills {@code out[(z * nx + x) * ny + y]} with the density at
         * {@code (x0 + x * sxz, y0 + y * sy, z0 + z * sxz)}.
         */
        void volume(int x0, int y0, int z0, int nx, int ny, int nz, int sxz, int sy, float[] out);
    }

    private final Density scout;
    private final Density exact;
    private final int lo;
    private final int hi;
    private final int cellXz;
    private final int cellY;
    private float[] lattice = new float[0];
    private float[] rows = new float[0];
    private final float[] cell;
    private final boolean[] solid;
    private long columns;
    private long scoutPoints;
    private long refinedCells;

    /**
     * @param scout  the density at lattice points (interpolation may be left out: it is exact there)
     * @param exact  the density as chunks see it, sampled block by block in one column
     * @param lo     the lowest block the noise fills
     * @param hi     the highest block the noise fills
     * @param cellXz the interpolation cell's width, 0 when the density is not interpolated
     * @param cellY  the interpolation cell's height, ignored when {@code cellXz} is 0
     */
    public RunScanner(Density scout, Density exact, int lo, int hi, int cellXz, int cellY) {
        if (hi < lo) throw new IllegalArgumentException("noise range " + lo + ".." + hi);
        this.scout = scout;
        this.exact = exact;
        this.lo = lo;
        this.hi = hi;
        this.cellXz = cellXz;
        this.cellY = cellXz == 0 ? PLAIN_ROW : cellY;
        this.cell = new float[this.cellY + 1];
        this.solid = new boolean[hi - lo + 1];
    }

    /** Whether a tile at {@code (x0, z0)} with {@code step} has its columns on the cell lattice. */
    public boolean onLattice(int x0, int z0, int step) {
        return cellXz == 0 || step % cellXz == 0 && Math.floorMod(x0, cellXz) == 0 && Math.floorMod(z0, cellXz) == 0;
    }

    /** Whether a tile's scout samples its columns themselves rather than the lattice round them. */
    public boolean scoutsColumns(int x0, int z0, int step) {
        return onLattice(x0, z0, step) || step >= cellXz;
    }

    /**
     * The runs of the {@code nx × nz} columns at {@code (x0 + i * step, z0 + j * step)},
     * index {@code i + nx * j}.
     */
    public ColumnRuns[] scan(int x0, int z0, int step, int nx, int nz) {
        int ry0 = Math.floorDiv(lo, cellY) * cellY;
        int ny = Math.ceilDiv(hi - ry0, cellY) + 1;
        ensureRows(nx * nz * ny);
        if (scoutsColumns(x0, z0, step)) {
            scout.volume(x0, ry0, z0, nx, ny, nz, step, cellY, rows);
            scoutPoints += (long) nx * nz * ny;
        } else {
            interpolateRows(x0, z0, step, nx, nz, ry0, ny);
        }
        ColumnRuns[] out = new ColumnRuns[nx * nz];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                out[i + nx * j] = column(x0 + i * step, z0 + j * step, (j * nx + i) * ny, ry0, ny);
            }
        }
        columns += (long) nx * nz;
        return out;
    }

    private void interpolateRows(int x0, int z0, int step, int nx, int nz, int ry0, int ny) {
        int lx0 = Math.floorDiv(x0, cellXz) * cellXz;
        int lz0 = Math.floorDiv(z0, cellXz) * cellXz;
        int lnx = Math.floorDiv(x0 + (nx - 1) * step - lx0, cellXz) + 2;
        int lnz = Math.floorDiv(z0 + (nz - 1) * step - lz0, cellXz) + 2;
        if (lattice.length < lnx * lnz * ny) lattice = new float[lnx * lnz * ny];
        scout.volume(lx0, ry0, lz0, lnx, ny, lnz, cellXz, cellY, lattice);
        scoutPoints += (long) lnx * lnz * ny;
        for (int j = 0; j < nz; j++) {
            int z = z0 + j * step - lz0;
            int cz = z / cellXz;
            float tz = (float) (z - cz * cellXz) / cellXz;
            for (int i = 0; i < nx; i++) {
                int x = x0 + i * step - lx0;
                int cx = x / cellXz;
                float tx = (float) (x - cx * cellXz) / cellXz;
                int a = (cz * lnx + cx) * ny;
                int b = (cz * lnx + cx + 1) * ny;
                int c = ((cz + 1) * lnx + cx) * ny;
                int d = ((cz + 1) * lnx + cx + 1) * ny;
                int o = (j * nx + i) * ny;
                for (int y = 0; y < ny; y++) {
                    float north = lattice[a + y] + (lattice[b + y] - lattice[a + y]) * tx;
                    float south = lattice[c + y] + (lattice[d + y] - lattice[c + y]) * tx;
                    rows[o + y] = north + (south - north) * tz;
                }
            }
        }
    }

    private ColumnRuns column(int x, int z, int base, int ry0, int ny) {
        int n = hi - lo + 1;
        for (int k = 0; k + 1 < ny; k++) {
            int ya = ry0 + k * cellY;
            int yb = ya + cellY;
            if (yb < lo || ya > hi) continue;
            boolean sa = rows[base + k] > 0;
            boolean sb = rows[base + k + 1] > 0;
            if (sa == sb) {
                for (int y = Math.max(ya, lo); y <= Math.min(yb - 1, hi); y++) solid[y - lo] = sa;
                continue;
            }
            int from = Math.max(ya, lo);
            int to = Math.min(yb - 1, hi);
            exact.volume(x, from, z, 1, to - from + 1, 1, 1, 1, cell);
            refinedCells++;
            for (int y = from; y <= to; y++) solid[y - lo] = cell[y - from] > 0;
        }
        int last = ry0 + (ny - 1) * cellY;
        if (last >= lo && last <= hi && (ny < 2 || rows[base + ny - 2] > 0 == rows[base + ny - 1] > 0)) {
            solid[last - lo] = rows[base + ny - 1] > 0;
        }
        return ColumnRuns.of(solid, lo, n);
    }

    private void ensureRows(int size) {
        if (rows.length < size) rows = new float[size];
    }

    public long columns() {
        return columns;
    }

    public long scoutPoints() {
        return scoutPoints;
    }

    /** Cells sampled block by block: crossings found. */
    public long refinedCells() {
        return refinedCells;
    }
}
