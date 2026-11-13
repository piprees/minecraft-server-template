package com.customdimensions.server.render.fast;

/**
 * The solid runs of one column, topmost first: run {@code i} fills blocks
 * {@code bottom(i)} to {@code top(i)} inclusive, with open space (air or
 * fluid) between runs. At most {@link #MAX_RUNS}; a column with more keeps
 * its topmost and is {@link #truncated()}.
 */
public final class ColumnRuns {
    public static final int MAX_RUNS = 24;
    public static final ColumnRuns EMPTY = new ColumnRuns(new int[0], new int[0], false);

    private final int[] bottom;
    private final int[] top;
    private final boolean truncated;

    ColumnRuns(int[] bottom, int[] top, boolean truncated) {
        this.bottom = bottom;
        this.top = top;
        this.truncated = truncated;
    }

    /** Runs from a solid profile: {@code solid[i]} is block {@code lo + i}; scanned top-down. */
    public static ColumnRuns of(boolean[] solid, int lo, int n) {
        int[] b = new int[MAX_RUNS];
        int[] t = new int[MAX_RUNS];
        int count = 0;
        boolean truncated = false;
        int i = n - 1;
        while (i >= 0) {
            while (i >= 0 && !solid[i]) i--;
            if (i < 0) break;
            int runTop = i;
            while (i >= 0 && solid[i]) i--;
            if (count == MAX_RUNS) {
                truncated = true;
                break;
            }
            t[count] = lo + runTop;
            b[count] = lo + i + 1;
            count++;
        }
        if (count == 0) return EMPTY;
        return new ColumnRuns(java.util.Arrays.copyOf(b, count), java.util.Arrays.copyOf(t, count), truncated);
    }

    /**
     * These runs with the lowest carried down to {@code floor} when it was cut
     * off at a window's floor {@code from}: ground known to continue below the
     * window. Itself when no run reaches {@code from}.
     */
    public ColumnRuns withFloor(int from, int floor) {
        int last = count() - 1;
        if (last < 0 || bottom[last] > from || bottom[last] <= floor) return this;
        int[] b = bottom.clone();
        b[last] = floor;
        return new ColumnRuns(b, top, truncated);
    }

    /** These runs as far up as block {@code to}: runs above it dropped, the one through it cut off there. */
    public ColumnRuns clip(int to) {
        if (count() == 0 || top[0] <= to) return this;
        int k = 0;
        while (k < count() && bottom[k] > to) k++;
        int[] t = java.util.Arrays.copyOfRange(top, k, count());
        int[] b = java.util.Arrays.copyOfRange(bottom, k, count());
        if (t.length > 0) t[0] = Math.min(t[0], to);
        return new ColumnRuns(b, t, truncated);
    }

    /** These runs within blocks {@code from} to {@code to}: cut off at both. */
    public ColumnRuns within(int from, int to) {
        ColumnRuns c = clip(to);
        int n = 0;
        while (n < c.count() && c.top[n] >= from) n++;
        int[] t = java.util.Arrays.copyOf(c.top, n);
        int[] b = java.util.Arrays.copyOf(c.bottom, n);
        if (n > 0) b[n - 1] = Math.max(b[n - 1], from);
        return new ColumnRuns(b, t, c.truncated);
    }

    /** These runs without the topmost: a roof left out. */
    public ColumnRuns dropTop() {
        if (count() == 0) return this;
        return new ColumnRuns(java.util.Arrays.copyOfRange(bottom, 1, count()), java.util.Arrays.copyOfRange(top, 1, count()), truncated);
    }

    /** Whether both hold the same runs. */
    public boolean sameAs(ColumnRuns o) {
        return java.util.Arrays.equals(top, o.top) && java.util.Arrays.equals(bottom, o.bottom);
    }

    public int count() {
        return top.length;
    }

    public int top(int i) {
        return top[i];
    }

    public int bottom(int i) {
        return bottom[i];
    }

    public boolean truncated() {
        return truncated;
    }

    /** Whether the column is solid at {@code noiseTop}: a roof (the Nether, caves). */
    public boolean roofed(int noiseTop) {
        return count() > 0 && top[0] >= noiseTop;
    }

    /**
     * The surface as {@code Sampler.surface} reads it from a noise column: one
     * above the top solid block; under a roof, one above the first solid block
     * under the roof's underside, or one above the roof when it reaches
     * {@code minY}; {@code minY} for an empty column.
     */
    public int surface(int noiseTop, int minY) {
        int i = surfaceRun(noiseTop, minY);
        if (i == -2) return noiseTop + 1;
        return i < 0 ? minY : top[i] + 1;
    }

    /**
     * The run whose top is the surface: -1 when there is none (an empty column
     * or a roof over nothing), -2 when the column is solid from the roof to
     * {@code minY}.
     */
    public int surfaceRun(int noiseTop, int minY) {
        if (count() == 0) return -1;
        if (!roofed(noiseTop)) return 0;
        if (bottom[0] <= minY) return -2;
        return count() > 1 ? 1 : -1;
    }

    /** The highest run reaching down to or below {@code y}: the first one a cut at {@code y} shows from above; -1 for none. */
    public int runAtOrBelow(int y) {
        for (int i = 0; i < count(); i++) {
            if (bottom[i] <= y) return i;
        }
        return -1;
    }

    /** Whether block {@code y} is solid. */
    public boolean solidAt(int y) {
        for (int i = 0; i < count(); i++) {
            if (y > top[i]) return false;
            if (y >= bottom[i]) return true;
        }
        return false;
    }
}
