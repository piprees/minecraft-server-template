package com.customdimensions.core.survey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * k-medoids over mixed feature vectors with the Gower distance: each numeric
 * feature is scaled by its own 5th–95th percentile range and clipped to 0–1,
 * each categorical one counts 0 or 1, and the distance is their mean. The
 * number of clusters is the one with the best mean silhouette from 2 to
 * {@code maxK} whose every cluster holds at least {@link #MIN_SHARE} of the items (and two); one cluster when no split reaches {@link #MIN_SILHOUETTE}
 * or there are fewer than {@link #MIN_ITEMS} items. Deterministic: greedy
 * BUILD, then swaps until none lowers the total cost.
 */
public final class KMedoids {
    public static final double MIN_SILHOUETTE = 0.15;
    public static final int MIN_ITEMS = 6;
    /** The smallest share of items a cluster may hold; a rarer group is left to {@link #outliers} as landmarks. */
    public static final double MIN_SHARE = 0.03;

    /** Clusters: each item's cluster, the medoid item of each cluster, its silhouette and every item's distance to its medoid. */
    public record Result(int k, int[] assignment, int[] medoids, double silhouette, double[] toMedoid, double[][] distance) {
        public int size(int cluster) {
            int n = 0;
            for (int a : assignment) if (a == cluster) n++;
            return n;
        }
    }

    private KMedoids() {}

    /** The Gower distance matrix of {@code numeric[item][feature]} and {@code categorical[item][feature]}. */
    public static double[][] gower(double[][] numeric, String[][] categorical) {
        int n = numeric.length;
        int nf = n == 0 ? 0 : numeric[0].length;
        int cf = n == 0 || categorical.length == 0 ? 0 : categorical[0].length;
        double[] range = new double[nf];
        for (int f = 0; f < nf; f++) {
            double[] column = new double[n];
            for (int i = 0; i < n; i++) column[i] = numeric[i][f];
            range[f] = Stats.percentile(column, 95) - Stats.percentile(column, 5);
        }
        double[][] d = new double[n][n];
        int features = Math.max(1, nf + cf);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double sum = 0;
                for (int f = 0; f < nf; f++) {
                    if (range[f] <= 0) continue;
                    sum += Math.min(1, Math.abs(numeric[i][f] - numeric[j][f]) / range[f]);
                }
                for (int f = 0; f < cf; f++) {
                    String a = categorical[i][f], b = categorical[j][f];
                    if (a == null ? b != null : !a.equals(b)) sum += 1;
                }
                d[i][j] = d[j][i] = sum / features;
            }
        }
        return d;
    }

    public static Result cluster(double[][] distance, int maxK) {
        int n = distance.length;
        Result best = single(distance);
        if (n < MIN_ITEMS) return best;
        for (int k = 2; k <= Math.min(maxK, n / 3); k++) {
            Result r = run(distance, k);
            int smallest = n;
            for (int c = 0; c < k; c++) smallest = Math.min(smallest, r.size(c));
            if (smallest < Math.max(2, Math.ceil(n * MIN_SHARE))) continue;
            if (r.silhouette() > best.silhouette() && r.silhouette() >= MIN_SILHOUETTE) best = r;
        }
        return best;
    }

    private static Result single(double[][] d) {
        int n = d.length;
        int medoid = 0;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double cost = 0;
            for (int j = 0; j < n; j++) cost += d[i][j];
            if (cost < bestCost) {
                bestCost = cost;
                medoid = i;
            }
        }
        int[] assignment = new int[n];
        double[] to = new double[n];
        for (int i = 0; i < n; i++) to[i] = n == 0 ? 0 : d[i][medoid];
        return new Result(1, assignment, n == 0 ? new int[0] : new int[] {medoid}, 0, to, d);
    }

    static Result run(double[][] d, int k) {
        int n = d.length;
        List<Integer> medoids = new ArrayList<>();
        double[] nearest = new double[n];
        Arrays.fill(nearest, Double.MAX_VALUE);
        for (int m = 0; m < k; m++) {
            int pick = -1;
            double bestGain = -1;
            for (int c = 0; c < n; c++) {
                if (medoids.contains(c)) continue;
                double gain = 0;
                for (int j = 0; j < n; j++) {
                    double current = medoids.isEmpty() ? 1e9 : nearest[j];
                    gain += Math.max(0, current - d[c][j]);
                }
                if (gain > bestGain) {
                    bestGain = gain;
                    pick = c;
                }
            }
            medoids.add(pick);
            for (int j = 0; j < n; j++) nearest[j] = Math.min(nearest[j], d[pick][j]);
        }
        int[] med = medoids.stream().mapToInt(Integer::intValue).toArray();
        double cost = cost(d, med);
        boolean improved = true;
        int rounds = 0;
        while (improved && rounds++ < 50) {
            improved = false;
            for (int m = 0; m < k; m++) {
                for (int c = 0; c < n; c++) {
                    if (contains(med, c)) continue;
                    int old = med[m];
                    med[m] = c;
                    double trial = cost(d, med);
                    if (trial < cost - 1e-12) {
                        cost = trial;
                        improved = true;
                    } else {
                        med[m] = old;
                    }
                }
            }
        }
        int[] assignment = new int[n];
        double[] to = new double[n];
        for (int i = 0; i < n; i++) {
            int a = 0;
            for (int m = 1; m < k; m++) if (d[i][med[m]] < d[i][med[a]]) a = m;
            assignment[i] = a;
            to[i] = d[i][med[a]];
        }
        return new Result(k, assignment, med, silhouette(d, assignment, k), to, d);
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static double cost(double[][] d, int[] med) {
        double sum = 0;
        for (double[] row : d) {
            double best = Double.MAX_VALUE;
            for (int m : med) best = Math.min(best, row[m]);
            sum += best;
        }
        return sum;
    }

    /** The mean silhouette of an assignment into {@code k} clusters. */
    public static double silhouette(double[][] d, int[] assignment, int k) {
        int n = d.length;
        double total = 0;
        for (int i = 0; i < n; i++) {
            double[] sum = new double[k];
            int[] count = new int[k];
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                sum[assignment[j]] += d[i][j];
                count[assignment[j]]++;
            }
            int own = assignment[i];
            if (count[own] == 0) continue;
            double a = sum[own] / count[own];
            double b = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) if (c != own && count[c] > 0) b = Math.min(b, sum[c] / count[c]);
            if (b == Double.MAX_VALUE) continue;
            double s = Math.max(a, b) == 0 ? 0 : (b - a) / Math.max(a, b);
            total += s;
        }
        return n == 0 ? 0 : total / n;
    }

    /**
     * Items far from their medoid: beyond both the 95th percentile of the
     * distances to medoids and twice their median. Empty under ten items.
     */
    public static List<Integer> outliers(Result r) {
        List<Integer> out = new ArrayList<>();
        if (r.toMedoid().length < 10) return out;
        double p95 = Stats.percentile(r.toMedoid(), 95);
        double median = Stats.percentile(r.toMedoid(), 50);
        double cut = Math.max(p95, 2 * median);
        for (int i = 0; i < r.toMedoid().length; i++) if (r.toMedoid()[i] > cut && r.toMedoid()[i] > 0) out.add(i);
        out.sort((a, b) -> Double.compare(r.toMedoid()[b], r.toMedoid()[a]));
        return out;
    }
}
