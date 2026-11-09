package com.customdimensions.core.survey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/** The survey's pure statistics: percentiles, nearest-neighbour spacing, connected clusters and slope classes. */
public final class Stats {
    private Stats() {}

    /** The {@code p}-th percentile (0–100) of {@code values} by nearest rank; NaN for none. */
    public static double percentile(double[] values, double p) {
        if (values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public static double percentile(int[] values, double p) {
        return percentile(Arrays.stream(values).asDoubleStream().toArray(), p);
    }

    public static double mean(double[] values) {
        return values.length == 0 ? Double.NaN : Arrays.stream(values).average().orElse(Double.NaN);
    }

    /** {@code value}'s share of {@code total} in percent, one decimal; 0 for a zero total. */
    public static double percent(double value, double total) {
        return total <= 0 ? 0 : Math.round(value * 1000.0 / total) / 10.0;
    }

    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * The mean distance from each point to its nearest other point, in the
     * plane of the first two coordinates; NaN with fewer than two points.
     */
    public static double meanNearestNeighbour(List<double[]> points) {
        if (points.size() < 2) return Double.NaN;
        double sum = 0;
        for (int i = 0; i < points.size(); i++) {
            double best = Double.MAX_VALUE;
            double[] a = points.get(i);
            for (int j = 0; j < points.size(); j++) {
                if (i == j) continue;
                double[] b = points.get(j);
                double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
                if (d < best) best = d;
            }
            sum += best;
        }
        return sum / points.size();
    }

    /** A connected group of cells: its size and centre in grid coordinates. */
    public record Cluster(int size, double x, double y, double z) {}

    /**
     * Groups the set cells of {@code cells} (index {@code (y * depth + z) * width + x})
     * into 26-connected clusters.
     */
    public static List<Cluster> clusters(BitSet cells, int width, int depth, int height) {
        List<Cluster> out = new ArrayList<>();
        BitSet seen = new BitSet(cells.length());
        int[] stack = new int[64];
        for (int start = cells.nextSetBit(0); start >= 0; start = cells.nextSetBit(start + 1)) {
            if (seen.get(start)) continue;
            int top = 0;
            stack[top++] = start;
            seen.set(start);
            long size = 0;
            double sx = 0, sy = 0, sz = 0;
            while (top > 0) {
                int c = stack[--top];
                int x = c % width;
                int z = (c / width) % depth;
                int y = c / (width * depth);
                size++;
                sx += x;
                sy += y;
                sz += z;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        int nz = z + dz;
                        if (nz < 0 || nz >= depth) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx;
                            if (nx < 0 || nx >= width) continue;
                            int n = (ny * depth + nz) * width + nx;
                            if (!cells.get(n) || seen.get(n)) continue;
                            seen.set(n);
                            if (top == stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                            stack[top++] = n;
                        }
                    }
                }
            }
            out.add(new Cluster((int) size, sx / size, sy / size, sz / size));
        }
        return out;
    }

    /** Columns by slope class: under 15°, 15–45°, over 45°, and cliffs (a drop of 10+ to a neighbour). */
    public record Slopes(int flat, int moderate, int steep, int cliff, int measured) {}

    /**
     * Slope classes of a {@code side × side} height grid (index {@code z * side + x}),
     * {@link Integer#MIN_VALUE} for a column with no height. The slope is the
     * central-difference gradient; a cliff is a drop of 10 or more to a 4-neighbour.
     */
    public static Slopes slopes(int[] heights, int side) {
        int flat = 0, moderate = 0, steep = 0, cliff = 0, measured = 0;
        for (int z = 1; z < side - 1; z++) {
            for (int x = 1; x < side - 1; x++) {
                int h = heights[z * side + x];
                int e = heights[z * side + x + 1], w = heights[z * side + x - 1];
                int s = heights[(z + 1) * side + x], n = heights[(z - 1) * side + x];
                if (h == Integer.MIN_VALUE || e == Integer.MIN_VALUE || w == Integer.MIN_VALUE
                        || s == Integer.MIN_VALUE || n == Integer.MIN_VALUE) continue;
                measured++;
                double gx = (e - w) / 2.0, gz = (s - n) / 2.0;
                double degrees = Math.toDegrees(Math.atan(Math.hypot(gx, gz)));
                if (degrees < 15) flat++;
                else if (degrees <= 45) moderate++;
                else steep++;
                int drop = Math.max(Math.max(Math.abs(h - e), Math.abs(h - w)), Math.max(Math.abs(h - s), Math.abs(h - n)));
                if (drop >= 10) cliff++;
            }
        }
        return new Slopes(flat, moderate, steep, cliff, measured);
    }

    /** The key with the largest count, or null for an empty map. */
    public static <K> K dominant(Map<K, ? extends Number> counts) {
        K best = null;
        double most = -1;
        for (Map.Entry<K, ? extends Number> e : counts.entrySet()) {
            if (e.getValue().doubleValue() > most) {
                most = e.getValue().doubleValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
