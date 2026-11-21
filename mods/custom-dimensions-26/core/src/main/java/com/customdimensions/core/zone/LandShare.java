package com.customdimensions.core.zone;

import java.util.Arrays;

/**
 * {@code terrain.landShare}: the shift added to a level's continentalness so
 * that {@code share} of the samples over its border lie at or above the coast
 * line. The runtime samples the seed's continentalness on a {@link #GRID}-square
 * grid over the border once per level and adds {@link #offset} everywhere, so
 * the share holds for any seed and any stretch of the noise.
 */
public final class LandShare {
    /** Samples per side of the measuring grid, as the biome regions measure their climate. */
    public static final int GRID = 96;
    /** The furthest the shift moves continentalness either way. */
    public static final double MAX_SHIFT = 2;

    private LandShare() {
    }

    /** The block coordinate of grid index {@code i} over a border of {@code radius} round the origin. */
    public static int gridCoordinate(int i, int radius) {
        double step = 2.0 * radius / (GRID - 1);
        return (int) Math.round(-radius + i * step);
    }

    /**
     * The shift that puts {@code coast} at the {@code 1 - share} quantile of
     * {@code samples} (interpolated between neighbours), clamped to
     * ±{@link #MAX_SHIFT}; 0 with no samples.
     */
    public static double offset(double[] samples, double share, double coast) {
        if (samples.length == 0) return 0;
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double q = quantile(sorted, 1 - share);
        return Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, coast - q));
    }

    /** The {@code f} quantile of ascending {@code sorted}, linear between neighbouring samples. */
    static double quantile(double[] sorted, double f) {
        int n = sorted.length;
        if (n == 1 || f <= 0) return sorted[0];
        if (f >= 1) return sorted[n - 1];
        double pos = f * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(n - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** The share of {@code samples} at or above {@code coast} once {@code offset} is added. */
    public static double landShare(double[] samples, double offset, double coast) {
        if (samples.length == 0) return 0;
        int land = 0;
        for (double s : samples) if (s + offset >= coast) land++;
        return (double) land / samples.length;
    }
}
