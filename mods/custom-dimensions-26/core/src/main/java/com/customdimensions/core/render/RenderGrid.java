package com.customdimensions.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The square of columns a render samples: {@code side × side} pixels, pixel
 * {@code (px, py)} being the column at {@code x(px), z(py)}, the north-west
 * corner of its {@code step × step} cell. North is up.
 */
public record RenderGrid(int centreX, int centreZ, int radius, int step) {
    /** The largest image side, in pixels. */
    public static final int MAX_SIDE = 1024;
    public static final int DEFAULT_RADIUS = 1024;
    public static final int MIN_RADIUS = 16;
    /** The largest radius; at {@link #MAX_SIDE} pixels its smallest step is 8 blocks. */
    public static final int MAX_RADIUS = 4096;
    /** The largest image side of a {@link #wide} grid, in pixels. */
    public static final int WIDE_MAX_SIDE = 4096;
    /** The largest radius of a {@link #wide} grid. */
    public static final int WIDE_MAX_RADIUS = 16384;

    public RenderGrid {
        if (radius < MIN_RADIUS || radius > WIDE_MAX_RADIUS) {
            throw new IllegalArgumentException("radius " + radius + " is outside " + MIN_RADIUS + " to " + WIDE_MAX_RADIUS);
        }
        if (step < 1 || Math.ceilDiv(2 * radius, step) > WIDE_MAX_SIDE) {
            throw new IllegalArgumentException("step " + step + " is below " + Math.max(1, Math.ceilDiv(2 * radius, WIDE_MAX_SIDE))
                    + " for radius " + radius);
        }
    }

    /** The smallest step that keeps the image at {@link #MAX_SIDE} pixels a side or fewer. */
    public static int minimumStep(int radius) {
        return Math.max(1, Math.ceilDiv(2 * radius, MAX_SIDE));
    }

    /**
     * A grid round {@code (centreX, centreZ)} of radius {@link #MIN_RADIUS} to
     * {@link #MAX_RADIUS} and at most {@link #MAX_SIDE} pixels a side; a null
     * step takes {@link #minimumStep}.
     */
    public static RenderGrid of(int centreX, int centreZ, int radius, Integer step) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius " + radius + " is outside " + MIN_RADIUS + " to " + MAX_RADIUS);
        }
        int s = step == null ? minimumStep(radius) : step;
        if (s < minimumStep(radius)) {
            throw new IllegalArgumentException("step " + s + " is below " + minimumStep(radius) + " for radius " + radius);
        }
        return new RenderGrid(centreX, centreZ, radius, s);
    }

    /** A grid up to {@link #WIDE_MAX_RADIUS} and {@link #WIDE_MAX_SIDE} pixels a side, for the fast renderer. */
    public static RenderGrid wide(int centreX, int centreZ, int radius, int step) {
        return new RenderGrid(centreX, centreZ, radius, step);
    }

    public int side() {
        return Math.ceilDiv(2 * radius, step);
    }

    public long columns() {
        return (long) side() * side();
    }

    public int x(int px) {
        return centreX - radius + px * step;
    }

    public int z(int py) {
        return centreZ - radius + py * step;
    }

    /** A run of whole rows, the unit a worker takes. */
    public record Band(int firstRow, int rows) {}

    /** The rows cut into bands of {@code rows} rows (the last may be shorter). */
    public List<Band> bands(int rows) {
        if (rows < 1) throw new IllegalArgumentException("rows " + rows);
        List<Band> out = new ArrayList<>();
        for (int first = 0; first < side(); first += rows) out.add(new Band(first, Math.min(rows, side() - first)));
        return out;
    }
}
