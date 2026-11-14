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
 * every block keeps the density a whole column gives it. A roofed world can
 * instead be scanned from its floor up to a height under its roof
 * ({@link #FLOOR}): from {@link #DEPTH} under the lowest floor near the tile
 * to the start height the pre-scan measured, a run reaching that height being
 * the roof's, and a tile with a column with no floor in it widened to the
 * noise floor. The rule follows from the runs found, never from the
 * dimension's name. Coarse columns sit {@code spacing} units apart from
 * {@code (ox, oz)}; a unit is a block, or a grid pixel for the pixel form.
 */
public final class ScanWindow {
    public static final String FULL = "full";
    public static final String TIERS = "tiers";
    public static final String SURFACE = "surface";
    public static final String FLOOR = "floor";
    /** Blocks over the highest run near a tile, and under the lowest run of a tier world. */
    public static final int MARGIN = 16;
    /** Blocks under the lowest surface near a tile that a surface world's window keeps. */
    public static final int DEPTH = 32;

    private final String mode;
    private final int lo;
    private final int hi;
    private final int align;
    private final int ox;
    private final int oz;
    private final int spacing;
    private final int n;
    private final int[] tops;
    private final int lowest;
    private final int[] floors;
    private final int start;
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
        this(mode, lo, hi, align, 0, 0, k, n, tops, lowest, null, hi);
    }

    /**
     * @param ox      the block x of the first coarse column; {@code oz} its z
     * @param spacing blocks between coarse columns
     * @param floors  the coarse grid's floor (the top of the run under a roof, else the top run) per column, or null
     * @param start   the top of a {@link #FLOOR} window
     */
    ScanWindow(String mode, int lo, int hi, int align, int ox, int oz, int spacing, int n, int[] tops, int lowest, int[] floors,
               int start) {
        this.mode = align <= 0 ? FULL : mode;
        this.lo = lo;
        this.hi = hi;
        this.align = align;
        this.ox = ox;
        this.oz = oz;
        this.spacing = spacing;
        this.n = n;
        this.tops = tops;
        this.lowest = lowest;
        this.floors = floors;
        this.start = start;
    }

    /** This window in {@code mode} with its counters at zero; {@link #FLOOR} needs the floors. */
    ScanWindow as(String mode) {
        if (FLOOR.equals(mode) && floors == null) throw new IllegalStateException("no floors for a floor window");
        return new ScanWindow(mode, lo, hi, align, ox, oz, spacing, n, tops, lowest, floors, start);
    }

    /** This window with a {@link #FLOOR} window's top at {@code start}, its counters at zero. */
    ScanWindow withStart(int start) {
        return new ScanWindow(mode, lo, hi, align, ox, oz, spacing, n, tops, lowest, floors, start);
    }

    /** The cell height windows snap to; 0 when only whole columns scan. */
    public int align() {
        return align;
    }

    /** The top of a {@link #FLOOR} window. */
    public int start() {
        return start;
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
        return windowAt(px, py, px + nx - 1, py + nz - 1);
    }

    /** The window for the columns from {@code (x0, z0)} to {@code (x1, z1)}, in this window's units. */
    public int[] windowAt(int x0, int z0, int x1, int z1) {
        tiles.incrementAndGet();
        if (FULL.equals(mode)) return whole();
        int cx0 = Math.max(0, Math.floorDiv(x0 - ox, spacing) - 1);
        int cx1 = Math.min(n - 1, Math.floorDiv(x1 - ox, spacing) + 2);
        int cz0 = Math.max(0, Math.floorDiv(z0 - oz, spacing) - 1);
        int cz1 = Math.min(n - 1, Math.floorDiv(z1 - oz, spacing) + 2);
        int[] heights = FLOOR.equals(mode) ? floors : tops;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int t = heights[cz * n + cx];
                if (t == Integer.MIN_VALUE) continue;
                max = Math.max(max, t);
                min = Math.min(min, t);
            }
        }
        if (FLOOR.equals(mode)) {
            int from = max == Integer.MIN_VALUE ? lo : Math.max(lo, Math.floorDiv(min - DEPTH, align) * align);
            int to = Math.min(hi, start);
            if (from >= to) return whole();
            rows.addAndGet(to - from + 1);
            return new int[] {from, to};
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
            if (FLOOR.equals(mode)) {
                ColumnRuns floor = r.count() > 0 && r.top(0) >= to ? r.from(1) : r;
                if (from > lo && floor.count() == 0) down = true;
                continue;
            }
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
            case FLOOR -> "from " + DEPTH + " blocks under the lowest floor near the tile to " + start
                    + ", under the roof's thickness measured by the pre-scan; a run reaching " + start + " is the roof's and is "
                    + "dropped; a tile with a column with no floor in it widened to the noise floor";
            case TIERS -> "from " + MARGIN + " blocks under the lowest run of the pre-scan to " + MARGIN
                    + " over the highest run near the tile, on cell rows; floating runs in at least " + FastRender.LAYER_SHARE
                    + " of its columns; a tile with ground at either edge of its window widened to the noise top or floor";
            default -> "from " + DEPTH + " blocks under the lowest surface near the tile to " + MARGIN
                    + " over the highest, on cell rows; the lowest run cut off at the window's floor; a tile with ground at "
                    + "its window's top widened to the noise top, one with a column with no ground to the noise floor";
        };
    }
}
