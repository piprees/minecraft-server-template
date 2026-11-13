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

    /** The runs from run {@code first} down: the column with its top {@code first} runs taken away. */
    public ColumnRuns from(int first) {
        if (first <= 0) return this;
        if (first >= count()) return EMPTY;
        return new ColumnRuns(java.util.Arrays.copyOfRange(bottom, first, count()), java.util.Arrays.copyOfRange(top, first, count()),
                truncated);
    }

    /** The run holding block {@code y}, or -1 when it is open. */
    public int runAt(int y) {
        for (int i = 0; i < count(); i++) {
            if (y > top[i]) return -1;
            if (y >= bottom[i]) return i;
        }
        return -1;
    }
}
