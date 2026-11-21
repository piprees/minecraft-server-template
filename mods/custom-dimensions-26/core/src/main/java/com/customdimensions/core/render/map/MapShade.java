package com.customdimensions.core.render.map;

/**
 * How the web map turns block colours into pixels, the same for generated
 * and explored tiles so the two meet without a seam. Water is drawn by
 * depth: each block of it hides {@value #WATER_OPACITY} of what lies under
 * it and the water itself darkens towards {@value #DEEP} of its colour at
 * {@value #DEEP_AT} blocks. A part-transparent block (ice, glass, leaves) is
 * laid over what is under it by its opacity. Relief is Lambert light from
 * the north-west at 45 degrees on the slope in blocks per block, relative to
 * flat ground, so the light keeps one direction and strength at every zoom.
 */
public final class MapShade {
    public static final double WATER_OPACITY = 0.25;
    public static final double DEEP = 0.65;
    public static final int DEEP_AT = 24;
    /** A height for no column: drawn transparent. */
    public static final short NO_HEIGHT = Short.MIN_VALUE;
    static final double LIGHT_X = -0.5;
    static final double LIGHT_Z = -0.5;
    static final double LIGHT_Y = Math.sqrt(0.5);
    static final double MIN_LIGHT = 0.6;
    static final double MAX_LIGHT = 1.25;

    private MapShade() {}

    /** Water {@code depth} blocks deep over {@code floor}. */
    public static int water(int water, int floor, int depth) {
        if (depth <= 0) return floor;
        double cover = 1 - Math.pow(1 - WATER_OPACITY, depth);
        int deep = scale(water, 1 - (1 - DEEP) * Math.min(1.0, depth / (double) DEEP_AT));
        return mix(deep, floor, cover);
    }

    /** {@code top} at opacity {@code alpha} (0 to 255) over {@code under}. */
    public static int over(int top, int alpha, int under) {
        return mix(top, under, alpha / 255.0);
    }

    /** {@code a} with weight {@code wa}, {@code b} with the rest. */
    public static int mix(int a, int b, double wa) {
        double wb = 1 - wa;
        int r = (int) Math.round(((a >> 16) & 0xFF) * wa + ((b >> 16) & 0xFF) * wb);
        int g = (int) Math.round(((a >> 8) & 0xFF) * wa + ((b >> 8) & 0xFF) * wb);
        int bl = (int) Math.round((a & 0xFF) * wa + (b & 0xFF) * wb);
        return r << 16 | g << 8 | bl;
    }

    /** The light on a slope of {@code dx}, {@code dz} blocks of rise per block east and south, 1 on flat ground. */
    public static double light(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz + 1);
        double lambert = (-dx * LIGHT_X - dz * LIGHT_Z + LIGHT_Y) / len;
        return Math.clamp(lambert / LIGHT_Y, MIN_LIGHT, MAX_LIGHT);
    }

    public static int scale(int rgb, double f) {
        int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = (int) Math.min(255, Math.round((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }

    /**
     * Shades a {@code w × h} image of block colours by its heights,
     * {@code step} blocks a pixel: opaque ARGB out, transparent where the
     * height is {@link #NO_HEIGHT}; a {@code flat} pixel (a fluid's surface)
     * is not shaded. A neighbour past the edge or without a height counts as
     * level with the pixel.
     */
    public static int[] relief(int[] rgb, short[] heights, boolean[] flat, int w, int h, int step) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                short hi = heights[i];
                if (hi == NO_HEIGHT) continue;
                if (flat != null && flat[i]) {
                    out[i] = 0xFF000000 | rgb[i];
                    continue;
                }
                double dx = (at(heights, w, h, x + 1, y, hi) - at(heights, w, h, x - 1, y, hi)) / (2.0 * step);
                double dz = (at(heights, w, h, x, y + 1, hi) - at(heights, w, h, x, y - 1, hi)) / (2.0 * step);
                out[i] = 0xFF000000 | scale(rgb[i], light(dx, dz));
            }
        }
        return out;
    }

    private static int at(short[] heights, int w, int h, int x, int y, short fallback) {
        if (x < 0 || y < 0 || x >= w || y >= h) return fallback;
        short v = heights[y * w + x];
        return v == NO_HEIGHT ? fallback : v;
    }
}
