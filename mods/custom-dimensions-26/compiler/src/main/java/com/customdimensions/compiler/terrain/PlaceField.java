package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * A place field: the border cut into one contiguous region per place by a power (weighted Voronoi) diagram over
 * one site per place, written as vanilla density functions whose value is one constant per place.
 *
 * <p>Geometry is in border units: block / {@code radius}, so the border is the square [-1, 1]². A point belongs
 * to the place whose {@code |p - site|² - weight} is least; each weight is solved on a grid so the place holds its
 * share of the square. The cells of a power diagram are convex, so each place is one region of the square.
 *
 * <p>The function computes, per place {@code i}, {@code floor((|p - site|² - weight + lift) * scale) * 16 + i},
 * takes the least, and reads {@code i} back as the remainder by 16: every term is an integer below 2^24, so the
 * game's float arithmetic is exact and the place at a point is the diagram's to within {@code 1 / scale} border
 * units squared. The sampling point is moved by the warp noises inside the border, tapered to nothing at its edge,
 * so place edges wander while the map stays one-to-one and each place one region (see {@link #MAX_WARP_RATIO}).
 */
public final class PlaceField {
    /** Places one field can hold: the remainder by 16 carries the place. */
    public static final int MAX_PLACES = 16;
    /** Automatic arrangements compiled per definition; the seed picks one, then one of the square's eight symmetries. */
    public static final int CANDIDATES = 4;
    /** Warp blocks per wavelength at most: below it the warp moves no point faster than about 0.7 of its neighbours, so the map cannot fold and split a place. */
    public static final double MAX_WARP_RATIO = 0.15;
    /** Cells a side of the grid the weights are solved on. */
    public static final int SOLVE_GRID = 256;
    /** Cells a side of the grid automatic arrangements are searched on. */
    static final int SEARCH_GRID = 64;
    /** Largest share error the solve accepts on {@link #SOLVE_GRID}. */
    public static final double SHARE_TOLERANCE = 0.004;
    /** Border units a pinned site's cell reaches round it at least, so a spawn or an arrival at the site stands well inside its place. */
    public static final double PIN_MARGIN = 0.1;
    /**
     * A heart or wedge term outside its place: a multiple of 16 below 2^24, so float-exact, above every term inside one.
     * Inside the heart its term is {@code hub - 16}, below every wedge's, so the heart wins whatever the hub's index.
     */
    static final double OUTSIDE_HEART = (1 << 24) - MAX_PLACES;
    /** The gain that turns a point's border-unit distance outside a wedge into 1 within a millionth of a border unit. */
    static final double STEEP = 1 << 20;
    /** Rounds of the anchored Lloyd iteration {@link #hold} runs at most. */
    static final int HOLD_ROUNDS = 30;
    /** Border units the coordinates are exact to on either side of the origin; beyond, they hold. */
    static final double REACH = 2;
    /** A point's distance to the place's site is measured from a point at most this far outside the border, in border units. */
    private static final double LIFT_REACH = REACH + 0.25;

    /**
     * One layout of the places: site and weight per place, in border units. With a {@code heart} radius above 0, place
     * {@code hub} is instead the disc of that radius round its site, and the {@code j}th other place in list order the
     * wedge from that site clockwise between compass bearings {@code wedges[j]} and {@code wedges[(j + 1) % (size - 1)]}.
     */
    public record Arrangement(double[] x, double[] z, double[] weight, double heart, double[] wedges, int hub) {
        public Arrangement(double[] x, double[] z, double[] weight) {
            this(x, z, weight, 0, null, 0);
        }

        public int size() {
            return x.length;
        }

        /** The place of the {@code j}th wedge round the heart. */
        public int other(int j) {
            return j < hub ? j : j + 1;
        }

        /** The place at border-unit point {@code (u, v)}. */
        public int place(double u, double v) {
            if (heart > 0) {
                double du = u - x[hub], dv = v - z[hub];
                if (du * du + dv * dv < heart * heart) return hub;
                int m = x.length - 1;
                for (int j = 0; j < m; j++) if (inside(wedges[j], wedges[(j + 1) % m], du, dv) >= 0) return other(j);
                return other(m - 1);
            }
            int best = 0;
            double bestD = Double.POSITIVE_INFINITY;
            for (int i = 0; i < x.length; i++) {
                double d = (u - x[i]) * (u - x[i]) + (v - z[i]) * (v - z[i]) - weight[i];
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        /** The place of each cell of a {@code grid}-square grid over the border, index {@code row * grid + column}. */
        public int[] labels(int grid) {
            int[] out = new int[grid * grid];
            for (int r = 0; r < grid; r++) {
                double v = -1 + (r + 0.5) * 2 / grid;
                for (int c = 0; c < grid; c++) out[r * grid + c] = place(-1 + (c + 0.5) * 2 / grid, v);
            }
            return out;
        }

        /** Each place's share of the border on a {@code grid}-square grid. */
        public double[] shares(int grid) {
            return shares(labels(grid), x.length);
        }

        static double[] shares(int[] labels, int n) {
            double[] out = new double[n];
            for (int l : labels) out[l]++;
            for (int i = 0; i < n; i++) out[i] /= labels.length;
            return out;
        }
    }

    /** The warp of the sampling point: {@code blocks} two standard deviations of the move, over features {@code wavelength} across. */
    public record Warp(double blocks, double wavelength) {
        public static final Warp NONE = new Warp(0, 512);
    }

    private PlaceField() {
    }

    // ---- solving ----------------------------------------------------------------------------

    /**
     * The weights that give each site its {@code target} share (summing to 1) of the border, sites fixed; with
     * {@code relax}, the free sites (not {@code fixed}) also move towards their cells' centroids, which rounds the
     * places. Null when a place cannot reach its share within {@link #SHARE_TOLERANCE} on {@code grid}.
     */
    public static Arrangement solve(double[] sx, double[] sz, double[] target, boolean[] fixed, boolean relax, int grid) {
        return solve(sx, sz, new double[sx.length], target, fixed, relax, grid);
    }

    /** {@code a}'s weights re-solved on {@code grid}, its sites kept; null as {@link #solve}. */
    public static Arrangement refine(Arrangement a, double[] target, int grid) {
        return solve(a.x(), a.z(), a.weight(), target, null, false, grid);
    }

    private static Arrangement solve(double[] sx, double[] sz, double[] w0, double[] target, boolean[] fixed, boolean relax, int grid) {
        int n = sx.length;
        double[] x = sx.clone(), z = sz.clone(), w = w0.clone();
        int rounds = relax ? 10 : 1;
        double error = 1;
        for (int round = 0; round < rounds; round++) {
            error = weights(x, z, w, target, grid, round == rounds - 1 ? 400 : 40);
            if (!relax) break;
            int[] labels = new Arrangement(x, z, w).labels(grid);
            double[] cx = new double[n], cz = new double[n], count = new double[n];
            for (int r = 0; r < grid; r++) {
                for (int c = 0; c < grid; c++) {
                    int l = labels[r * grid + c];
                    cx[l] += -1 + (c + 0.5) * 2 / grid;
                    cz[l] += -1 + (r + 0.5) * 2 / grid;
                    count[l]++;
                }
            }
            for (int i = 0; i < n; i++) {
                if (fixed != null && fixed[i] || count[i] == 0) continue;
                x[i] = clampSite(0.5 * x[i] + 0.5 * cx[i] / count[i]);
                z[i] = clampSite(0.5 * z[i] + 0.5 * cz[i] / count[i]);
            }
        }
        return error <= SHARE_TOLERANCE ? new Arrangement(x, z, w) : null;
    }

    /**
     * The place whose cell comes within {@code margin} of point {@code (u, v)} of place {@code i}'s cell, or -1 when the cell
     * holds the whole disc of {@code margin} round the point: the power bisector of {@code i} and {@code j} lies
     * {@code (|s_j|² - |s_i|² + w_i - w_j - 2 p·(s_j - s_i)) / 2|s_j - s_i|} from {@code p}, inside when positive.
     */
    public static int reach(Arrangement a, int i, double u, double v, double margin) {
        if (a.heart() > 0) {
            int h = a.hub();
            double d = Math.hypot(u - a.x()[h], v - a.z()[h]);
            if (i == h) return a.heart() - d < margin ? a.other(0) : -1;
            if (d - a.heart() < margin) return h;
        }
        int nearest = -1;
        double least = margin;
        for (int j = 0; j < a.size(); j++) {
            if (j == i || a.heart() > 0 && j == a.hub()) continue;
            double dx = a.x()[j] - a.x()[i], dz = a.z()[j] - a.z()[i], d = Math.hypot(dx, dz);
            double t = d == 0 ? Double.NEGATIVE_INFINITY
                    : (a.x()[j] * a.x()[j] + a.z()[j] * a.z()[j] - a.x()[i] * a.x()[i] - a.z()[i] * a.z()[i] + a.weight()[i] - a.weight()[j]
                    - 2 * (u * dx + v * dz)) / (2 * d);
            if (t < least) {
                least = t;
                nearest = j;
            }
        }
        return nearest;
    }

    /** The first place with an anchor whose cell does not hold {@link #PIN_MARGIN} round it, or -1; {@code anchors[i]} null is no anchor. */
    public static int unheld(Arrangement a, double[][] anchors) {
        for (int i = 0; i < a.size(); i++) if (anchors[i] != null && reach(a, i, anchors[i][0], anchors[i][1], PIN_MARGIN) >= 0) return i;
        return -1;
    }

    /**
     * {@code a} with each place's {@code anchors} point held inside its cell by {@link #PIN_MARGIN}, the shares kept.
     * {@code a} itself when every anchor already holds; otherwise a capacity-constrained Lloyd iteration: each anchored
     * site moves by the gap from its cell's centroid to its anchor, so the cell centres on the anchor, each free site
     * half-way to its cell's centroid, and the weights are re-solved. Null when an anchor still does not hold.
     */
    public static Arrangement hold(Arrangement a, double[] target, double[][] anchors, int grid) {
        if (a == null || unheld(a, anchors) < 0) return a;
        int n = a.size();
        double[] x = a.x().clone(), z = a.z().clone(), w = a.weight().clone();
        for (int round = 0; round < HOLD_ROUNDS; round++) {
            int[] labels = new Arrangement(x, z, w).labels(grid);
            double[] cx = new double[n], cz = new double[n], count = new double[n];
            for (int r = 0; r < grid; r++) {
                for (int c = 0; c < grid; c++) {
                    int l = labels[r * grid + c];
                    cx[l] += -1 + (c + 0.5) * 2 / grid;
                    cz[l] += -1 + (r + 0.5) * 2 / grid;
                    count[l]++;
                }
            }
            for (int i = 0; i < n; i++) {
                if (count[i] == 0) continue;
                cx[i] /= count[i];
                cz[i] /= count[i];
                if (anchors[i] != null) {
                    x[i] = clampSite(x[i] + anchors[i][0] - cx[i]);
                    z[i] = clampSite(z[i] + anchors[i][1] - cz[i]);
                } else {
                    x[i] = clampSite(0.5 * x[i] + 0.5 * cx[i]);
                    z[i] = clampSite(0.5 * z[i] + 0.5 * cz[i]);
                }
            }
            Arrangement held = solve(x, z, w, target, null, false, grid);
            if (held == null) continue;
            if (unheld(held, anchors) < 0) return held;
            w = held.weight().clone();
        }
        return null;
    }

    private static double clampSite(double v) {
        return Math.max(-0.95, Math.min(0.95, v));
    }

    /** Adjusts {@code w} in place towards the targets; the largest share error left. */
    private static double weights(double[] x, double[] z, double[] w, double[] target, int grid, int iterations) {
        int n = x.length;
        double gain = 1.5, last = Double.POSITIVE_INFINITY, error = 1;
        double[] best = w.clone();
        double bestError = Double.POSITIVE_INFINITY;
        for (int it = 0; it < iterations; it++) {
            double[] a = new Arrangement(x, z, w).shares(grid);
            error = 0;
            for (int i = 0; i < n; i++) error = Math.max(error, Math.abs(target[i] - a[i]));
            if (error < bestError) {
                bestError = error;
                best = w.clone();
            }
            if (error <= SHARE_TOLERANCE / 2) break;
            if (error > last) gain *= 0.7;
            last = error;
            double mean = 0;
            for (int i = 0; i < n; i++) {
                w[i] += gain * (target[i] - a[i]) * (a[i] == 0 ? 4 : 2);
                mean += w[i] / n;
            }
            for (int i = 0; i < n; i++) w[i] -= mean;
        }
        System.arraycopy(best, 0, w, 0, n);
        return bestError;
    }

    // ---- arrangements -----------------------------------------------------------------------

    /** Compass degrees to a unit direction in x, z: 0 is north (-z), 90 east (+x). */
    static double[] heading(double degrees) {
        double a = Math.toRadians(degrees);
        return new double[]{Math.sin(a), -Math.cos(a)};
    }

    /** {@code n} sites round a ring of {@code radius} border units, the first towards {@code direction}, evenly spaced clockwise. */
    public static double[][] ring(int n, double radius, double direction) {
        double[][] s = new double[2][n];
        for (int i = 0; i < n; i++) {
            double[] h = heading(direction + 360.0 * i / n);
            s[0][i] = radius * h[0];
            s[1][i] = radius * h[1];
        }
        return s;
    }

    /** Sites round a ring at the middle of wedges as wide as the shares, the first wedge centred on {@code direction}. */
    public static double[][] sectors(double[] share, double radius, double direction) {
        int n = share.length;
        double[][] s = new double[2][n];
        double start = direction - 180 * share[0];
        double cum = 0;
        for (int i = 0; i < n; i++) {
            double[] h = heading(start + 360 * (cum + share[i] / 2));
            s[0][i] = radius * h[0];
            s[1][i] = radius * h[1];
            cum += share[i];
        }
        return s;
    }

    /** Sites on the line through the centre along {@code direction}, each at the middle of a band as wide as its share. */
    public static double[][] bands(double[] share, double direction) {
        int n = share.length;
        double[] h = heading(direction);
        double reach = Math.abs(h[0]) + Math.abs(h[1]);
        double[][] s = new double[2][n];
        double cum = 0;
        for (int i = 0; i < n; i++) {
            double t = (-1 + 2 * (cum + share[i] / 2)) * reach * 0.9;
            s[0][i] = t * h[0] / reach;
            s[1][i] = t * h[1] / reach;
            cum += share[i];
        }
        return s;
    }

    /** The first site at the centre, the rest round a ring as {@link #ring}. */
    public static double[][] core(int n, double radius, double direction) {
        double[][] s = new double[2][n];
        if (n == 1) return s;
        double[][] r = ring(n - 1, radius, direction);
        System.arraycopy(r[0], 0, s[0], 1, n - 1);
        System.arraycopy(r[1], 0, s[1], 1, n - 1);
        return s;
    }

    /**
     * Place {@code hub} as a heart: the disc round {@code (cx, cz)} whose area is its share, the others round it in wedges
     * from that point clockwise in list order, the first centred on {@code direction}, each holding its share of the
     * border. Every wedge meets the heart and reaches the border. Null when the heart is narrower than {@link #PIN_MARGIN}
     * or comes within it of the border.
     */
    public static Arrangement heart(double[] share, int hub, double cx, double cz, double direction) {
        int n = share.length;
        double r = Math.sqrt(4 * share[hub] / Math.PI);
        if (n < 2 || r < PIN_MARGIN || r > 1 - Math.max(Math.abs(cx), Math.abs(cz)) - PIN_MARGIN) return null;
        double[] ring = new double[n - 1];
        for (int j = 0; j < n - 1; j++) ring[j] = share[j < hub ? j : j + 1];
        double[] wedges = wedges(ring, r, cx, cz, direction);
        for (int pass = 0; pass < 8; pass++) {
            double first = n == 2 ? 360 : ((wedges[1] - wedges[0]) % 360 + 360) % 360;
            wedges = wedges(ring, r, cx, cz, direction - first / 2);
        }
        double[] x = new double[n], z = new double[n];
        x[hub] = cx;
        z[hub] = cz;
        for (int j = 0; j < n - 1; j++) {
            double a = wedges[j], span = ((wedges[(j + 1) % (n - 1)] - a) % 360 + 360) % 360;
            double[] h = heading(a + (span == 0 ? 360 : span) / 2);
            int p = j < hub ? j : j + 1;
            x[p] = cx + h[0] * (r + PIN_MARGIN);
            z[p] = cz + h[1] * (r + PIN_MARGIN);
        }
        return new Arrangement(x, z, new double[n], r, wedges, hub);
    }

    /** Steps a turn the wedge areas are integrated in. */
    private static final int WEDGE_STEPS = 36_000;

    /**
     * The bearings, clockwise from {@code start}, that cut the square less the disc of {@code r} round {@code (cx, cz)}
     * into wedges from that point in proportion to {@code share}.
     */
    private static double[] wedges(double[] share, double r, double cx, double cz, double start) {
        int m = share.length;
        double[] out = new double[m];
        out[0] = start;
        double rest = 0;
        for (double v : share) rest += v;
        double total = 0;
        double[] area = new double[WEDGE_STEPS];
        for (int s = 0; s < WEDGE_STEPS; s++) {
            double[] h = heading(start + 360.0 * (s + 0.5) / WEDGE_STEPS);
            double reach = Math.min(edge(cx, h[0]), edge(cz, h[1]));
            area[s] = (reach * reach - r * r) / 2;
            total += area[s];
        }
        double cum = 0, want = 0;
        int k = 1;
        for (int s = 0; s < WEDGE_STEPS && k < m; s++) {
            cum += area[s];
            while (k < m && cum >= (want + share[k - 1] / rest) * total) {
                want += share[k - 1] / rest;
                out[k] = start + 360.0 * (s + 1) / WEDGE_STEPS;
                k++;
            }
        }
        for (int i = 0; i < m; i++) out[i] = (out[i] % 360 + 360) % 360;
        return out;
    }

    /** The distance from {@code c} along a step of {@code h} per unit to the border's edge at ±1. */
    private static double edge(double c, double h) {
        return h > 0 ? (1 - c) / h : h < 0 ? (-1 - c) / h : Double.POSITIVE_INFINITY;
    }

    /** How far inside the wedge clockwise from bearing {@code a} to {@code b} the point {@code (u, v)} lies: at or above 0 inside. */
    static double inside(double a, double b, double u, double v) {
        double span = ((b - a) % 360 + 360) % 360;
        if (span == 0) return 1;
        double[] ha = heading(a), hb = heading(b);
        double from = ha[0] * v - ha[1] * u, to = hb[1] * u - hb[0] * v;
        return span <= 180 ? Math.min(from, to) : Math.max(from, to);
    }

    /** Hints the automatic search honours: pairs that must share a border and pairs that must not. */
    public record Hints(List<int[]> neighbours, List<int[]> apart) {
        public static final Hints NONE = new Hints(List.of(), List.of());
    }

    /** A search's refusal when a pinned site cannot hold its place, followed by that place's index. */
    public static final String PIN_REFUSAL = "unheld pin ";

    /** What an automatic search found: up to {@link #CANDIDATES} arrangements, or why none holds the hints. */
    public record Search(List<Arrangement> arrangements, String refusal) {}

    /**
     * Up to {@link #CANDIDATES} automatic arrangements of places with {@code share}, deterministic from
     * {@code seed}: random sites relaxed to round places, the places assigned to sites so the hints hold and as
     * many consecutive places as can share a border; {@code at} fixes a place's site where non-null, and the place's cell
     * holds that point with {@link #PIN_MARGIN} to spare ({@link #hold}).
     */
    public static Search search(double[] share, double[][] at, Hints hints, long seed) {
        int n = share.length;
        List<Arrangement> found = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(seed);
        String refusal = null;
        int attempts = 0;
        while (found.size() < CANDIDATES && attempts < 48) {
            attempts++;
            double[] x = new double[n], z = new double[n];
            boolean[] fixed = new boolean[n];
            for (int i = 0; i < n; i++) {
                x[i] = random.nextDouble(-0.8, 0.8);
                z[i] = random.nextDouble(-0.8, 0.8);
            }
            int[] perm = assign(x, z, at, share, hints, random);
            double[] px = new double[n], pz = new double[n];
            for (int p = 0; p < n; p++) {
                if (at[p] != null) {
                    px[p] = at[p][0];
                    pz[p] = at[p][1];
                    fixed[p] = true;
                } else {
                    px[p] = x[perm[p]];
                    pz[p] = z[perm[p]];
                }
            }
            Arrangement a = solve(px, pz, share, fixed, true, SEARCH_GRID);
            if (a == null) continue;
            Arrangement held = hold(a, share, at, SEARCH_GRID);
            if (held == null) {
                if (refusal == null) refusal = PIN_REFUSAL + unheld(a, at);
                continue;
            }
            a = held;
            int[] labels = a.labels(SEARCH_GRID);
            boolean[][] adj = adjacency(labels, SEARCH_GRID, n);
            String broken = broken(adj, hints);
            if (broken != null) {
                refusal = broken;
                continue;
            }
            found.add(a);
        }
        return new Search(found, found.isEmpty() ? (refusal != null ? refusal : "no arrangement gives every place its share") : null);
    }

    /** A place for each site: {@code perm[place]} is its site, chosen by a hill climb on the sites' Voronoi adjacency. */
    private static int[] assign(double[] x, double[] z, double[][] at, double[] share, Hints hints, SplittableRandom random) {
        int n = x.length;
        boolean[][] siteAdj = adjacency(new Arrangement(x, z, new double[n]).labels(SEARCH_GRID), SEARCH_GRID, n);
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        double score = score(perm, siteAdj, at, hints);
        for (int it = 0; it < 400; it++) {
            int a = random.nextInt(n), b = random.nextInt(n);
            if (a == b) continue;
            int t = perm[a];
            perm[a] = perm[b];
            perm[b] = t;
            double s = score(perm, siteAdj, at, hints);
            if (s >= score) {
                score = s;
            } else {
                perm[b] = perm[a];
                perm[a] = t;
            }
        }
        return perm;
    }

    private static double score(int[] perm, boolean[][] siteAdj, double[][] at, Hints hints) {
        int n = perm.length;
        double s = 0;
        for (int i = 0; i + 1 < n; i++) {
            if (at[i] == null && at[i + 1] == null && siteAdj[perm[i]][perm[i + 1]]) s += 1;
        }
        for (int[] h : hints.neighbours()) if (at[h[0]] == null && at[h[1]] == null && siteAdj[perm[h[0]]][perm[h[1]]]) s += 10;
        for (int[] h : hints.apart()) if (at[h[0]] == null && at[h[1]] == null && siteAdj[perm[h[0]]][perm[h[1]]]) s -= 10;
        return s;
    }

    /** The first hint {@code adj} breaks, as a sentence, or null. */
    public static String broken(boolean[][] adj, Hints hints) {
        for (int[] h : hints.neighbours()) if (!adj[h[0]][h[1]]) return "places " + h[0] + " and " + h[1] + " share no border";
        for (int[] h : hints.apart()) if (adj[h[0]][h[1]]) return "places " + h[0] + " and " + h[1] + " share a border";
        return null;
    }

    /** Which places share a border of at least two cells on a {@code grid}-square label grid. */
    public static boolean[][] adjacency(int[] labels, int grid, int n) {
        int[][] count = new int[n][n];
        for (int r = 0; r < grid; r++) {
            for (int c = 0; c < grid; c++) {
                int l = labels[r * grid + c];
                if (c + 1 < grid && labels[r * grid + c + 1] != l) count[l][labels[r * grid + c + 1]]++;
                if (r + 1 < grid && labels[(r + 1) * grid + c] != l) count[l][labels[(r + 1) * grid + c]]++;
            }
        }
        boolean[][] adj = new boolean[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) adj[i][j] = count[i][j] + count[j][i] >= 2;
        return adj;
    }

    /** The number of 4-connected regions each label forms on a {@code grid}-square grid. */
    public static int[] regions(int[] labels, int grid, int n) {
        int[] count = new int[n];
        boolean[] seen = new boolean[labels.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int s = 0; s < labels.length; s++) {
            if (seen[s]) continue;
            int l = labels[s];
            count[l]++;
            seen[s] = true;
            queue.add(s);
            while (!queue.isEmpty()) {
                int j = queue.poll();
                int r = j / grid, c = j % grid;
                int[] next = {c > 0 ? j - 1 : -1, c + 1 < grid ? j + 1 : -1, r > 0 ? j - grid : -1, r + 1 < grid ? j + grid : -1};
                for (int k : next) {
                    if (k >= 0 && !seen[k] && labels[k] == l) {
                        seen[k] = true;
                        queue.add(k);
                    }
                }
            }
        }
        return count;
    }

    // ---- density functions ------------------------------------------------------------------

    /** The compiled field: its files and the id of the function whose value is the place's. */
    public record Compiled(Map<String, JsonObject> files, String id, int nodes) {}

    /** The value the field takes in place {@code i} of {@code n}: the middle of the {@code i}th of {@code n} equal steps across [-1, 1]. */
    public static double value(int i, int n) {
        return -1 + (2.0 * i + 1) / n;
    }

    /** The edge between places {@code i - 1} and {@code i} on the place axis. */
    public static double edge(int i, int n) {
        return -1 + 2.0 * i / n;
    }

    /** Thresholds that split a noise of spread {@code sigma} into {@code bins} near-equal shares. */
    static double[] bins(int bins, double sigma) {
        double[] t = new double[bins - 1];
        for (int k = 1; k < bins; k++) t[k - 1] = Noises.threshold(1 - (double) k / bins, sigma);
        return t;
    }

    /**
     * The field over a {@code radius}-block border as functions under {@code <namespace>:places/<name>/}:
     * {@code arrangements} chosen by the seed (one: always that one), with the square's eight symmetries chosen
     * by the seed too when {@code symmetries}, and the sampling point warped by {@code warp}.
     */
    public static Compiled compile(String namespace, String name, int radius, List<Arrangement> arrangements, boolean symmetries, Warp warp) {
        Map<String, JsonObject> files = new TreeMap<>();
        String prefix = namespace + ":places/" + name + "/";
        int[] nodes = {0};
        int n = arrangements.get(0).size();
        files.put(TerrainLibrary.dataPath(prefix + "x", "worldgen/density_function"), Df.cache(coordinate("x", radius)));
        files.put(TerrainLibrary.dataPath(prefix + "z", "worldgen/density_function"), Df.cache(coordinate("z", radius)));
        nodes[0] += 4;
        JsonElement u = Df.ref(prefix + "x"), v = Df.ref(prefix + "z");
        if (warp.blocks() > 0) {
            double taper = Math.min(radius / 2.0, 8 * warp.blocks());
            JsonObject window = Df.cache(Df.clamp(Df.mul(Df.add(Df.max(Df.abs(u), Df.abs(v)), -1), -radius / taper), 0, 1));
            files.put(TerrainLibrary.dataPath(prefix + "window", "worldgen/density_function"), window);
            double k = warp.blocks() / (2 * Noises.sigma("warp_x")) / radius;
            double frequency = TerrainLibrary.WARP_FEATURE / warp.wavelength();
            JsonElement win = Df.ref(prefix + "window");
            files.put(TerrainLibrary.dataPath(prefix + "u", "worldgen/density_function"),
                    Df.cache(Df.add(u, Df.mul(win, Df.mul(Df.noise(namespace + ":terrain/warp_x", frequency, 0, false), k)))));
            files.put(TerrainLibrary.dataPath(prefix + "v", "worldgen/density_function"),
                    Df.cache(Df.add(v, Df.mul(win, Df.mul(Df.noise(namespace + ":terrain/warp_z", frequency, 0, false), k)))));
            for (String noise : TerrainLibrary.WARP_NOISES) {
                files.put(TerrainLibrary.dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
            }
            nodes[0] += 8 + 2 * 5;
            u = Df.ref(prefix + "u");
            v = Df.ref(prefix + "v");
        }
        if (symmetries) {
            files.put(TerrainLibrary.dataPath(prefix + "orientation", "worldgen/noise"), Noises.json("warp_x"));
            JsonObject c = Df.noise(prefix + "orientation", 0, 0, false);
            double[] t = bins(8, Noises.sigma("warp_x"));
            JsonElement nu = Df.mul(u, -1), nv = Df.mul(v, -1);
            files.put(TerrainLibrary.dataPath(prefix + "p", "worldgen/density_function"),
                    Df.cache(choose(c, t, List.of(u, nu, u, nu, v, nv, v, nv))));
            files.put(TerrainLibrary.dataPath(prefix + "q", "worldgen/density_function"),
                    Df.cache(choose(c, t, List.of(v, v, nv, nv, u, u, nu, nu))));
            nodes[0] += 2 * (1 + 8 + 2);
            u = Df.ref(prefix + "p");
            v = Df.ref(prefix + "q");
        }
        List<JsonElement> least = new ArrayList<>();
        for (Arrangement a : arrangements) least.add(least(a, u, v, nodes));
        JsonElement m;
        if (arrangements.size() == 1) {
            m = least.get(0);
        } else {
            files.put(TerrainLibrary.dataPath(prefix + "arrangement", "worldgen/noise"), Noises.json("warp_x"));
            m = choose(Df.noise(prefix + "arrangement", 0, 0, false), bins(arrangements.size(), Noises.sigma("warp_x")), least);
            nodes[0] += 1 + arrangements.size();
        }
        files.put(TerrainLibrary.dataPath(prefix + "least", "worldgen/density_function"), Df.cache(m));
        JsonElement l = Df.ref(prefix + "least");
        JsonObject index = Df.sub(l, Df.mul(Df.floor(Df.mul(l, 1.0 / 16)), 16));
        JsonObject place = Df.add(Df.mul(index, 2.0 / n), 1.0 / n - 1);
        files.put(TerrainLibrary.dataPath(prefix + "place", "worldgen/density_function"), place);
        nodes[0] += 7;
        return new Compiled(files, prefix + "place", nodes[0]);
    }

    /** The coordinate along {@code axis} in border units, exact within {@link #REACH} of the origin and clamped beyond. */
    private static JsonObject coordinate(String axis, int radius) {
        JsonObject o = Df.type("gradient");
        o.addProperty("axis", axis);
        o.addProperty("tiling", "clamp_to_edge");
        o.addProperty("from_coordinate", (int) -Math.round(REACH * radius));
        o.addProperty("to_coordinate", (int) Math.round(REACH * radius));
        o.addProperty("from_value", -REACH);
        o.addProperty("to_value", REACH);
        return o;
    }

    /** The scale that keeps {@code floor(d * scale) * 16 + 15} below 2^24 for every {@code d} up to {@code max}: a power of two. */
    static double scale(double max) {
        double s = 1;
        while (max * s * 2 * MAX_PLACES < (1 << 24)) s *= 2;
        return s;
    }

    /** The least of the places' encoded power distances in {@code a}. */
    private static JsonElement least(Arrangement a, JsonElement u, JsonElement v, int[] nodes) {
        int n = a.size();
        double top = Double.NEGATIVE_INFINITY, bottom = Double.POSITIVE_INFINITY;
        for (double w : a.weight()) {
            top = Math.max(top, w);
            bottom = Math.min(bottom, w);
        }
        double max = 2 * Math.pow(LIFT_REACH + 1, 2) + top - bottom;
        double scale = scale(max);
        JsonElement out = null;
        if (a.heart() > 0) {
            int h = a.hub();
            JsonElement du = Df.add(u, -a.x()[h]), dv = Df.add(v, -a.z()[h]);
            out = Df.add(Df.mul(Df.floor(Df.clamp(Df.mul(Df.add(Df.square(du), Df.square(dv)), 1 / (a.heart() * a.heart())), 0, 1)),
                    OUTSIDE_HEART + MAX_PLACES), h - MAX_PLACES);
            nodes[0] += 11;
            for (int j = 0; j < n - 1; j++) {
                double wa = a.wedges()[j], wb = a.wedges()[(j + 1) % (n - 1)];
                double span = ((wb - wa) % 360 + 360) % 360;
                double[] ha = heading(wa), hb = heading(wb);
                JsonElement from = Df.add(Df.mul(dv, ha[0]), Df.mul(du, -ha[1])), to = Df.add(Df.mul(du, hb[1]), Df.mul(dv, -hb[0]));
                JsonElement m = span == 0 ? Df.c(1) : span <= 180 ? Df.min(from, to) : Df.max(from, to);
                out = Df.min(out, Df.add(Df.mul(Df.floor(Df.clamp(Df.mul(m, -STEEP), 0, 1)), OUTSIDE_HEART), a.other(j)));
                nodes[0] += 15;
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            JsonObject d = Df.add(Df.add(Df.square(Df.add(u, -a.x()[i])), Df.square(Df.add(v, -a.z()[i]))), top - a.weight()[i]);
            JsonObject g = Df.mul(Df.floor(Df.mul(Df.clamp(d, 0, max), scale)), MAX_PLACES);
            JsonElement gi = i == 0 ? g : Df.add(g, i);
            out = out == null ? gi : Df.min(out, gi);
            nodes[0] += 11 + (i == 0 ? 0 : 2);
        }
        return out;
    }

    /** {@code options[k]} where {@code c} lies in the {@code k}th interval the ascending {@code thresholds} cut. */
    static JsonElement choose(JsonElement c, double[] thresholds, List<JsonElement> options) {
        JsonElement out = options.get(options.size() - 1);
        for (int k = options.size() - 2; k >= 0; k--) {
            JsonObject r = Df.type("range_choice");
            r.add("input", c);
            r.add("min_inclusive", Df.c(k == 0 ? -1_000_000 : thresholds[k - 1]));
            r.add("max_exclusive", Df.c(thresholds[k]));
            r.add("when_in_range", options.get(k));
            r.add("when_out_of_range", out);
            out = r;
        }
        return out;
    }
}
