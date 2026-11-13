package com.customdimensions.server.render.fast;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The heights a tile's columns are scanned over, from the pre-scan's coarse
 * grid of whole columns. A roofed world is scanned whole ({@link #FULL}): its
 * layers need every run. A world with floating runs (open space under its
 * lowest run in at least {@link FastRender#LAYER_SHARE} of coarse columns:
 * sky tiers, islands over a void) is scanned from {@link #MARGIN} under the
 * lowest run the grid found to {@link #MARGIN} over the highest run near the
 * tile ({@link #TIERS}). Any other world is scanned from {@link #DEPTH}
 * under the lowest surface near the tile to {@link #MARGIN} over the highest
 * ({@link #SURFACE}): its run list holds the runs in that window, the lowest
 * cut off at the window's floor. "Near" is the tile widened by one coarse
 * spacing. A tile whose window shows solid ground at its top is widened to
 * the noise top; one with (surface) a column with no ground in it, or (tiers)
 * ground at its floor, to the noise floor. Windows start on interpolation cell rows, so
 * every block keeps the density a whole column gives it. The rule follows
 * from the runs found, never from the dimension's name.
 */
public final class ScanWindow {
    public static final String FULL = "full";
    public static final String TIERS = "tiers";
    public static final String SURFACE = "surface";
    /** Blocks over the highest run near a tile, and under the lowest run of a tier world. */
    public static final int MARGIN = 16;
    /** Blocks under the lowest surface near a tile that a surface world's window keeps. */
    public static final int DEPTH = 32;

    private final String mode;
    private final int lo;
    private final int hi;
    private final int align;
    private final int k;
    private final int n;
    private final int[] tops;
    private final int lowest;
    private final AtomicLong tiles = new AtomicLong();
    private final AtomicLong rescanned = new AtomicLong();
    private final AtomicLong rows = new AtomicLong();

    /**
     * @param tops    the coarse grid's top solid block per column, row-major, {@link Integer#MIN_VALUE} for an empty column
     * @param k       grid pixels between coarse columns
     * @param n       coarse columns a side
     * @param lowest  the lowest solid block the coarse grid found
     * @param align   the interpolation cell's height windows snap to; 0 scans whole columns
     */
    ScanWindow(String mode, int lo, int hi, int align, int k, int n, int[] tops, int lowest) {
        this.mode = align <= 0 ? FULL : mode;
        this.lo = lo;
        this.hi = hi;
        this.align = align;
        this.k = k;
        this.n = n;
        this.tops = tops;
        this.lowest = lowest;
    }

    /** The mode for a pre-scan's shares of roofed and floating columns. */
    static String mode(long columns, long roofed, long floating) {
        if (roofed >= FastRender.LAYER_SHARE * columns) return FULL;
        return floating >= FastRender.LAYER_SHARE * columns ? TIERS : SURFACE;
    }

    public String mode() {
        return mode;
    }

    /**
     * The window {from, to} for the tile of {@code nx × nz} grid pixels from
     * pixel {@code (px, py)}: the whole noise range in {@link #FULL} mode or
     * when no coarse column near the tile has ground.
     */
    public int[] window(int px, int py, int nx, int nz) {
        tiles.incrementAndGet();
        if (FULL.equals(mode)) return whole();
        int cx0 = Math.max(0, Math.floorDiv(px, k) - 1);
        int cx1 = Math.min(n - 1, Math.floorDiv(px + nx - 1, k) + 2);
        int cz0 = Math.max(0, Math.floorDiv(py, k) - 1);
        int cz1 = Math.min(n - 1, Math.floorDiv(py + nz - 1, k) + 2);
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int t = tops[cz * n + cx];
                if (t == Integer.MIN_VALUE) continue;
                max = Math.max(max, t);
                min = Math.min(min, t);
            }
        }
        if (max == Integer.MIN_VALUE) return whole();
        int from = TIERS.equals(mode) ? lowest - MARGIN : min - DEPTH;
        int to = max + MARGIN;
        from = Math.max(lo, Math.floorDiv(from, align) * align);
        to = Math.min(hi, Math.floorDiv(to, align) * align + align - 1);
        if (from >= to) return whole();
        rows.addAndGet(to - from + 1);
        return new int[] {from, to};
    }

    private int[] whole() {
        rows.addAndGet(hi - lo + 1);
        return new int[] {lo, hi};
    }

    /**
     * The wider window runs scanned over {@code [from, to]} need, or null when
     * they need none: up to the noise top when ground reaches the window's top,
     * down to the noise floor when a surface column has no ground or a tier
     * column's ground reaches the floor.
     */
    public int[] widen(ColumnRuns[] runs, int from, int to) {
        boolean up = false;
        boolean down = false;
        for (ColumnRuns r : runs) {
            if (to < hi && r.count() > 0 && r.top(0) >= to) up = true;
            if (from > lo && SURFACE.equals(mode) && r.count() == 0) down = true;
            if (from > lo && TIERS.equals(mode) && r.count() > 0 && r.bottom(r.count() - 1) <= from) down = true;
        }
        if (!up && !down) return null;
        rescanned.incrementAndGet();
        int[] wider = {down ? lo : from, up ? hi : to};
        rows.addAndGet(wider[1] - wider[0] + 1);
        return wider;
    }

    public long tiles() {
        return tiles.get();
    }

    public long rescanned() {
        return rescanned.get();
    }

    /** Mean blocks a side a tile's window spans, rescans included. */
    public double meanRows() {
        long t = tiles.get();
        return t == 0 ? 0 : (double) rows.get() / (t + rescanned.get());
    }

    public String rule() {
        return switch (mode) {
            case FULL -> "whole columns: the pre-scan found a roof in at least " + FastRender.LAYER_SHARE + " of its columns, "
                    + "or the density has no interpolation cell to align a window to";
            case TIERS -> "from " + MARGIN + " blocks under the lowest run of the pre-scan to " + MARGIN
                    + " over the highest run near the tile, on cell rows; floating runs in at least " + FastRender.LAYER_SHARE
                    + " of its columns; a tile with ground at either edge of its window widened to the noise top or floor";
            default -> "from " + DEPTH + " blocks under the lowest surface near the tile to " + MARGIN
                    + " over the highest, on cell rows; the lowest run cut off at the window's floor; a tile with ground at "
                    + "its window's top widened to the noise top, one with a column with no ground to the noise floor";
        };
    }
}
