package com.customdimensions.client.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a rig frame shows a place: a frame is rejected when it is occluded, empty, a fog wall, dark, or looks at the
 * world border. The ray measures come from {@link FrameCensus}, the image measures from the screenshot's pixels.
 * Thresholds are calibrated against the rig's own good and bad frames (README § Frame-quality gate).
 */
public final class FrameGate {
    /** Rays within this many blocks count as pressed to the lens. */
    static final double NEAR = 4;
    /** Occluded: the median ray distance (misses count as the render distance) under this. */
    static final double MEDIAN_MIN = 4;
    /** Occluded: one block id covering more than this share of all rays within {@link #NEAR}. */
    static final double NEAR_BLOCK_MAX = 45;
    /** Empty: sky, void and unloaded rays together over this share, unless the shot is meant to be a void or sky view. */
    static final double EMPTY_MAX = 75;
    /** Fog wall: more than this share of pixels within {@link #FOG_DISTANCE} of the fog colour. */
    static final double FOG_MAX = 60;
    /** RGB distance (0-441) within which a pixel counts as fog. */
    static final double FOG_DISTANCE = 24;
    /** Dark: mean Rec. 709 luma (0-255) under this. */
    static final double LUMA_MIN = 30;
    /** Border: more than this share of rays reach the world border before any block, within the render distance. */
    static final double BORDER_MAX = 2;
    /** Pixels sampled on each axis for the image measures. */
    static final int SAMPLES = 160;

    private FrameGate() {}

    /** One frame's measures: percentages are of all rays or all sampled pixels. */
    public record Measures(double medianDistance, String nearBlock, double nearBlockPercent, double skyPercent, double voidPercent,
            double unloadedPercent, double borderPercent, double luma, double fogPercent) {
        double emptyPercent() {
            return skyPercent + voidPercent + unloadedPercent;
        }
    }

    /** The reasons a frame fails, empty when it passes; {@code emptyAllowed} for a shot meant to show sky or void. */
    public static List<String> reasons(Measures m, boolean emptyAllowed) {
        List<String> out = new ArrayList<>();
        if (m.luma() < LUMA_MIN) {
            out.add(String.format(Locale.ROOT, "dark: mean luma %.1f < %.0f", m.luma(), LUMA_MIN));
        }
        if (m.medianDistance() < MEDIAN_MIN) {
            out.add(String.format(Locale.ROOT, "occluded: median ray %.1f blocks < %.0f", m.medianDistance(), MEDIAN_MIN));
        } else if (m.nearBlockPercent() > NEAR_BLOCK_MAX) {
            out.add(String.format(Locale.ROOT, "occluded: %s %.1f%% of rays within %.0f blocks", m.nearBlock(), m.nearBlockPercent(), NEAR));
        }
        if (!emptyAllowed && m.emptyPercent() > EMPTY_MAX) {
            out.add(String.format(Locale.ROOT, "empty: sky %.1f%% + void %.1f%% + unloaded %.1f%% > %.0f%%", m.skyPercent(), m.voidPercent(),
                    m.unloadedPercent(), EMPTY_MAX));
        }
        if (m.fogPercent() > FOG_MAX) {
            out.add(String.format(Locale.ROOT, "fog wall: %.1f%% of pixels within %.0f of the fog colour", m.fogPercent(), FOG_DISTANCE));
        }
        if (m.borderPercent() > BORDER_MAX) {
            out.add(String.format(Locale.ROOT, "border: %.1f%% of rays reach the world border", m.borderPercent()));
        }
        return out;
    }

    /**
     * Mean luma and the share of pixels near {@code fogRgb} over a {@value #SAMPLES}-wide grid of an image given as
     * packed RGB ints (alpha ignored), row-major.
     */
    public static double[] image(int[] rgb, int width, int height, int fogRgb) {
        int fr = fogRgb >> 16 & 0xFF, fg = fogRgb >> 8 & 0xFF, fb = fogRgb & 0xFF;
        int sx = Math.max(1, width / SAMPLES), sy = Math.max(1, height / (SAMPLES * 9 / 16));
        double luma = 0;
        int n = 0, fog = 0;
        for (int y = sy / 2; y < height; y += sy) {
            for (int x = sx / 2; x < width; x += sx) {
                int p = rgb[y * width + x];
                int r = p >> 16 & 0xFF, g = p >> 8 & 0xFF, b = p & 0xFF;
                luma += 0.2126 * r + 0.7152 * g + 0.0722 * b;
                double dr = r - fr, dg = g - fg, db = b - fb;
                if (dr * dr + dg * dg + db * db <= FOG_DISTANCE * FOG_DISTANCE) {
                    fog++;
                }
                n++;
            }
        }
        return new double[] {n == 0 ? 0 : luma / n, n == 0 ? 0 : fog * 100.0 / n};
    }

    /**
     * Distance along {@code (dx, dz)} from {@code (x, z)} to the edge of a square border centred on {@code (cx, cz)}
     * with half-size {@code half}; infinite for a ray that never meets it (vertical, or starting outside).
     */
    public static double borderDistance(double x, double z, double dx, double dz, double cx, double cz, double half) {
        if (Math.abs(x - cx) > half || Math.abs(z - cz) > half) {
            return 0;
        }
        double t = Double.POSITIVE_INFINITY;
        if (dx > 1e-9) t = Math.min(t, (cx + half - x) / dx);
        if (dx < -1e-9) t = Math.min(t, (cx - half - x) / dx);
        if (dz > 1e-9) t = Math.min(t, (cz + half - z) / dz);
        if (dz < -1e-9) t = Math.min(t, (cz - half - z) / dz);
        return t;
    }
}
