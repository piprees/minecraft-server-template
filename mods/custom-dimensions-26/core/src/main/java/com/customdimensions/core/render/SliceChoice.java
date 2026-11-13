package com.customdimensions.core.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The fixed heights a wave draws top-down, chosen from a dimension's noise
 * range, sea level and a pre-scan of its columns, never from its name. The
 * surface itself is the map layer; these are the heights under, over and
 * between it:
 * <ul>
 *   <li>{@code sea-level}: the top block of the sea (one under the settings' sea level), when the sea level lies
 *       inside the noise range above its floor;</li>
 *   <li>a surface world: {@code caves} halfway between the median surface and {@code deep-caves}, and
 *       {@code deep-caves} {@value #DEEP} blocks over the noise floor, each when the median surface stands
 *       {@value #UNDERGROUND} blocks over it;</li>
 *   <li>a roofed world (a roof over {@link #LAYER_SHARE} of columns): {@code ceiling} at the median underside of
 *       the roof, {@code caves} halfway between the median floor and that underside when they are
 *       {@value #GAP} blocks apart, and {@code deep-caves} as above, under the floor;</li>
 *   <li>a sky world (floating runs over {@link #LAYER_SHARE} of columns): {@code sky-N} at the median of each
 *       band of the pre-scan ({@link #bands}), top band first, at most {@value #MAX_BANDS};</li>
 *   <li>a void (no solid column in the pre-scan): sea level only.</li>
 * </ul>
 * Two heights within {@value #MIN_APART} blocks keep the first in that order.
 */
public final class SliceChoice {
    public static final double LAYER_SHARE = 0.02;
    public static final int DEEP = 16;
    public static final int UNDERGROUND = 48;
    public static final int GAP = 24;
    public static final int MAX_BANDS = 4;
    public static final int MIN_APART = 8;
    /** A band's gap of open heights at most this tall is bridged. */
    public static final int BAND_BRIDGE = 8;
    /** A height belongs to a band when at least this share of solid columns is solid there. */
    public static final double BAND_SHARE = 0.01;
    /** A band is kept when it holds at least this share of the solid mass. */
    public static final double BAND_MASS = 0.05;
    /** A height belongs to a band of run middles when its count is at least this share of the highest count. */
    public static final double MIDDLE_SHARE = 0.02;
    /** A band splits at a dip in its smoothed counts at most this share of the lower of the peaks either side. */
    public static final double VALLEY = 0.85;

    /** One fixed height: its name, its block and why it was chosen. */
    public record Slice(String name, int y, String rule) {}

    /**
     * What a pre-scan found. {@code solid[y - lo]} counts the columns solid at
     * {@code y}; {@code medianSurface} is the median top of the surface run
     * (the floor under a roof), null when no column has ground; {@code middles[y - lo]}
     * counts the runs whose middle block is {@code y}, null when not counted;
     * {@code roofUnderside} the median lowest block of a roofed column's roof, null when none is roofed.
     */
    public record Profile(int lo, int hi, int seaLevel, int columns, int solidColumns, Integer medianSurface,
                          double roofedShare, double floatingShare, Integer roofUnderside, long[] solid, long[] middles) {
        /** A profile without run middles: bands come from the solid counts. */
        public Profile(int lo, int hi, int seaLevel, int columns, int solidColumns, Integer medianSurface, double roofedShare,
                       double floatingShare, Integer roofUnderside, long[] solid) {
            this(lo, hi, seaLevel, columns, solidColumns, medianSurface, roofedShare, floatingShare, roofUnderside, solid, null);
        }
    }

    /** A solid band of heights, {@code bottom} to {@code top}, its {@code middle} where half its mass lies below. */
    public record Band(int bottom, int top, int middle, long mass) {}

    private SliceChoice() {}

    public static List<Slice> choose(Profile p) {
        List<Slice> out = new ArrayList<>();
        if (p.seaLevel() > p.lo() && p.seaLevel() < p.hi()) {
            out.add(new Slice("sea-level", p.seaLevel() - 1, "the top block of the sea, one under the settings' sea level"));
        }
        if (p.solidColumns() == 0) return dedupe(out);
        int deep = p.lo() + DEEP;
        if (p.floatingShare() >= LAYER_SHARE && p.roofedShare() < LAYER_SHARE) {
            List<Band> bands = bands(p);
            for (int i = 0; i < bands.size(); i++) {
                Band b = bands.get(i);
                out.add(new Slice("sky-" + (i + 1), b.middle(), "the median of band " + b.bottom() + " to " + b.top()
                        + " of the pre-scan's " + (p.middles() != null ? "run middles" : "solid heights") + ", counted from the top"));
            }
            return dedupe(out);
        }
        if (p.roofedShare() >= LAYER_SHARE && p.roofUnderside() != null) {
            int under = p.roofUnderside();
            out.add(new Slice("ceiling", under, "the median underside of the roof in roofed columns"));
            Integer floor = p.medianSurface();
            if (floor != null && under - floor >= GAP) {
                out.add(new Slice("caves", (floor + under) / 2, "halfway between the median floor (" + floor
                        + ") and the roof's median underside"));
            }
            if (floor != null && floor - deep >= UNDERGROUND - DEEP) {
                out.add(new Slice("deep-caves", deep, DEEP + " blocks over the noise floor, under the median floor (" + floor + ")"));
            }
            return dedupe(out);
        }
        Integer surface = p.medianSurface();
        if (surface != null && surface - p.lo() >= UNDERGROUND) {
            out.add(new Slice("caves", (surface + deep) / 2, "halfway between the median surface (" + surface + ") and "
                    + DEEP + " blocks over the noise floor"));
            out.add(new Slice("deep-caves", deep, DEEP + " blocks over the noise floor"));
        }
        return dedupe(out);
    }

    /** Top-down, dropping a height within {@link #MIN_APART} of one kept before it. */
    static List<Slice> dedupe(List<Slice> in) {
        List<Slice> out = new ArrayList<>();
        for (Slice s : in) {
            boolean near = false;
            for (Slice k : out) near |= Math.abs(k.y() - s.y()) < MIN_APART;
            if (!near) out.add(s);
        }
        out.sort(Comparator.comparingInt(Slice::y).reversed());
        return out;
    }

    /** {@code counts} averaged over {@code 2 * half + 1} heights, clipped at the ends. */
    static double[] smooth(long[] counts, int half) {
        double[] out = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            long sum = 0;
            int n = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(counts.length - 1, i + half); j++) {
                sum += counts[j];
                n++;
            }
            out[i] = (double) sum / n;
        }
        return out;
    }

    /** {@code [from, to]} cut at its deepest valley ({@link #VALLEY}) and each side again, into {@code out}, bottom first. */
    static void split(double[] smooth, int from, int to, List<int[]> out) {
        int best = -1;
        double bestRatio = VALLEY;
        double[] leftMax = new double[to - from + 1];
        double running = 0;
        for (int i = from; i <= to; i++) {
            running = Math.max(running, smooth[i]);
            leftMax[i - from] = running;
        }
        running = 0;
        for (int i = to; i >= from; i--) {
            double right = running;
            running = Math.max(running, smooth[i]);
            if (i == from || i == to) continue;
            double peak = Math.min(leftMax[i - from - 1], right);
            if (peak <= 0) continue;
            double ratio = smooth[i] / peak;
            if (ratio <= bestRatio && smooth[i] <= smooth[i - 1] && smooth[i] <= smooth[i + 1]) {
                bestRatio = ratio;
                best = i;
            }
        }
        if (best < 0) {
            out.add(new int[] {from, to});
            return;
        }
        split(smooth, from, best, out);
        split(smooth, best + 1, to, out);
    }

    /**
     * The bands of a pre-scan, top first, from its run middles when it counted
     * them (heights holding {@link #MIDDLE_SHARE} of the most middles at one
     * height), else from its solid counts: heights solid in at least
     * {@link #BAND_SHARE} of solid columns, gaps up to {@link #BAND_BRIDGE}
     * bridged, split where the counts (averaged over {@code 2 * BAND_BRIDGE + 1}
     * heights) dip to {@link #VALLEY} of the peaks either side, each holding
     * {@link #BAND_MASS} of the mass, the {@link #MAX_BANDS} heaviest kept.
     */
    public static List<Band> bands(Profile p) {
        long[] m = p.middles();
        long most = 0;
        if (m != null) for (long c : m) most = Math.max(most, c);
        if (most > 0) return bands(m, Math.max(1, Math.round(MIDDLE_SHARE * most)), p.lo());
        return bands(p.solid(), Math.max(1, Math.round(BAND_SHARE * Math.max(1, p.solidColumns()))), p.lo());
    }

    private static List<Band> bands(long[] solid, long threshold, int lo) {
        long total = 0;
        for (long c : solid) total += c;
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        int last = -1;
        for (int i = 0; i < solid.length; i++) {
            if (solid[i] < threshold) continue;
            if (start >= 0 && i - last - 1 <= BAND_BRIDGE) {
                last = i;
                continue;
            }
            if (start >= 0) spans.add(new int[] {start, last});
            start = i;
            last = i;
        }
        if (start >= 0) spans.add(new int[] {start, last});
        double[] smooth = smooth(solid, BAND_BRIDGE);
        List<int[]> split = new ArrayList<>();
        for (int[] span : spans) split(smooth, span[0], span[1], split);
        spans = split;
        List<Band> out = new ArrayList<>();
        for (int[] s : spans) {
            long mass = 0;
            for (int i = s[0]; i <= s[1]; i++) mass += solid[i];
            if (total == 0 || mass < BAND_MASS * total) continue;
            long half = 0;
            int middle = s[0];
            for (int i = s[0]; i <= s[1]; i++) {
                half += solid[i];
                if (2 * half >= mass) {
                    middle = i;
                    break;
                }
            }
            out.add(new Band(lo + s[0], lo + s[1], lo + middle, mass));
        }
        out.sort(Comparator.comparingLong(Band::mass).reversed());
        if (out.size() > MAX_BANDS) out = new ArrayList<>(out.subList(0, MAX_BANDS));
        out.sort(Comparator.comparingInt(Band::top).reversed());
        return out;
    }
}
