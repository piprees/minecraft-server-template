package com.customdimensions.core.survey;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Whether the bordered area reads as a place or as a sample of a noise field,
 * from the coarse map: connected regions of one surface biome and of one
 * landform class (4-neighbour; regions under {@link #SPECK_HA} are specks,
 * left out of the density and the edge distances), the boundary length between them, the
 * correlation length of surface height and continentalness (the semivariogram
 * range), and where spawn and the centre sit.
 *
 * <p>A place has few large regions, a short boundary and a correlation length
 * that is a sizeable share of the border; a noise sample has many small
 * regions, a long boundary and a correlation length of a few hundred blocks.
 */
public final class Placeness {
    /** Blocks from a region boundary within which a cell counts as near an edge. */
    public static final int EDGE_BLOCKS = 64;
    /** A region under this many hectares is a speck: it counts in {@code count} but not in the density or the edges. */
    public static final double SPECK_HA = 1.0;
    /** A region at least this share of the grid counts as major. */
    public static final double MAJOR_PERCENT = 1.0;
    /** The semivariogram reaches its range at this share of the sill (the field's variance). */
    public static final double SILL_SHARE = 0.95;

    /** One grid's connected regions of equal labels. */
    public record Regions(int[] region, int[] areas, int[] labels) {
        public int count() {
            return areas.length;
        }
    }

    private final int side;
    private final int step;
    private final int originX;
    private final int originZ;

    /** A {@code side × side} grid (index {@code z * side + x}) whose cell (x, z) is at block {@code originX + x * step}. */
    public Placeness(int side, int step, int originX, int originZ) {
        this.side = side;
        this.step = step;
        this.originX = originX;
        this.originZ = originZ;
    }

    /**
     * The placeness section: {@code biomes} and {@code landforms} label each cell
     * (a negative label is no surface and joins no region), {@code heights} and
     * {@code continentalness} are fields (NaN where absent; null skips that
     * field); {@code spawn} is a block x, z or null. {@code landBiomes}, when
     * given, labels only the land cells outside a sea biome (negative elsewhere)
     * and adds {@code landBiomeRegions}, measured over those cells alone.
     */
    public JsonObject toJson(int[] biomes, IntFunction<String> biomeName, int[] landforms, IntFunction<String> landformName,
                             double[] heights, double[] continentalness, int[] spawn) {
        return toJson(biomes, biomeName, landforms, landformName, heights, continentalness, spawn, null);
    }

    public JsonObject toJson(int[] biomes, IntFunction<String> biomeName, int[] landforms, IntFunction<String> landformName,
                             double[] heights, double[] continentalness, int[] spawn, int[] landBiomes) {
        Regions b = regions(biomes);
        Regions l = regions(landforms);
        JsonObject o = new JsonObject();
        o.addProperty("note", "over the coarse map (" + step + "-block cells, " + side + " a side); regions are 4-connected cells of one "
                + "surface biome or one landform class; edge cells lie within " + EDGE_BLOCKS + " blocks of a region boundary; correlation "
                + "length is the semivariogram range (" + Math.round(SILL_SHARE * 100) + "% of the variance) along x and z");
        o.add("biomesPercent", shares(biomes, biomeName));
        o.add("biomeRegions", regionJson(b, biomes));
        o.add("landformRegions", regionJson(l, landforms));
        if (landBiomes != null) o.add("landBiomeRegions", landRegionJson(landBiomes, biomeName));
        JsonObject corr = new JsonObject();
        if (heights != null) corr.add("surfaceHeight", correlation(heights));
        if (continentalness != null) corr.add("continentalness", correlation(continentalness));
        o.add("correlationLength", corr);
        JsonObject dom = new JsonObject();
        double bTop = topShare(b, 1), lTop = topShare(l, 1);
        dom.addProperty("biomePercent", bTop);
        dom.addProperty("biomeTop3Percent", topShare(b, 3));
        dom.addProperty("landformPercent", lTop);
        dom.addProperty("landformTop3Percent", topShare(l, 3));
        dom.addProperty("percent", Math.max(bTop, lTop));
        int[] top = largest(b);
        if (top.length > 0) dom.addProperty("biome", biomeName.apply(b.labels[top[0]]));
        int[] topL = largest(l);
        if (topL.length > 0) dom.addProperty("landform", landformName.apply(l.labels[topL[0]]));
        o.add("dominant", dom);
        int[] edge = edgeDistance(b);
        JsonObject at = new JsonObject();
        at.add("centre", pointJson(0, 0, b, l, biomeName, landformName, edge));
        if (spawn != null) at.add("spawn", pointJson(spawn[0], spawn[1], b, l, biomeName, landformName, edge));
        o.add("at", at);
        return o;
    }

    /**
     * {@code heightAboveMedianBlocks} on each point of {@code placeness.at} inside the map: its cell's surface
     * height minus the median of {@code heights} (NaN cells, the void, left out).
     */
    public void addRelief(JsonObject placeness, double[] heights) {
        JsonObject at = placeness.getAsJsonObject("at");
        if (at == null) return;
        for (String key : at.keySet()) {
            JsonObject p = at.getAsJsonObject(key);
            int x = Math.round((p.get("x").getAsInt() - originX) / (float) step), z = Math.round((p.get("z").getAsInt() - originZ) / (float) step);
            if (x < 0 || z < 0 || x >= side || z >= side) continue;
            Double relief = SurveyMetrics.heightAboveMedian(heights[z * side + x], heights);
            if (relief != null) p.addProperty("heightAboveMedianBlocks", relief);
        }
    }

    /**
     * The islands: 4-connected regions of {@code land} cells (specks included), their count, their sizes in
     * hectares (p50, p90, max), and {@code dominantBiomePercent}, the mean over the islands, weighted by area,
     * of each island's share of land in its own commonest biome ({@code biomes}, negative where none): 100 when
     * every island is one place.
     */
    public JsonObject islandsJson(boolean[] land, int[] biomes) {
        int[] labels = new int[land.length];
        for (int i = 0; i < land.length; i++) labels[i] = land[i] ? 0 : -1;
        Regions r = regions(labels);
        double cellHa = (double) step * step / 10_000.0;
        JsonObject o = new JsonObject();
        o.addProperty("note", "4-connected land cells of the coarse map (" + step + "-block cells), specks included; dominantBiomePercent is the "
                + "area-weighted mean share of each island's land in its own commonest biome");
        o.addProperty("count", r.count());
        double[] sizes = new double[r.count()];
        for (int k = 0; k < sizes.length; k++) sizes[k] = r.areas()[k] * cellHa;
        JsonObject ha = new JsonObject();
        if (sizes.length > 0) {
            ha.addProperty("p50", Stats.round1(Stats.percentile(sizes, 50)));
            ha.addProperty("p90", Stats.round1(Stats.percentile(sizes, 90)));
            ha.addProperty("max", Stats.round1(Arrays.stream(sizes).max().orElse(0)));
        }
        o.add("sizesHa", ha);
        java.util.List<java.util.Map<Integer, Integer>> counts = new ArrayList<>();
        for (int k = 0; k < r.count(); k++) counts.add(new java.util.HashMap<>());
        for (int i = 0; i < labels.length; i++) {
            if (r.region()[i] >= 0 && biomes[i] >= 0) counts.get(r.region()[i]).merge(biomes[i], 1, Integer::sum);
        }
        long top = 0, total = 0;
        for (java.util.Map<Integer, Integer> c : counts) {
            int sum = c.values().stream().mapToInt(Integer::intValue).sum();
            total += sum;
            top += c.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        o.addProperty("dominantBiomePercent", total == 0 ? 0 : Stats.percent(top, total));
        return o;
    }

    /** Each biome's share of the void cells, sampled at a fixed height ({@code biomes}: a label per void cell, negative elsewhere). */
    public JsonObject voidBiomesJson(int[] biomes, IntFunction<String> name) {
        int cells = 0;
        for (int b : biomes) if (b >= 0) cells++;
        return shares(biomes, name, Math.max(1, cells));
    }

    /** Every label's share of the grid, largest first, one decimal (at least 0.1 for a label present at all). */
    JsonObject shares(int[] labels, IntFunction<String> name) {
        return shares(labels, name, labels.length);
    }

    private static JsonObject shares(int[] labels, IntFunction<String> name, int cells) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int l : labels) if (l >= 0) counts.merge(l, 1, Integer::sum);
        JsonObject o = new JsonObject();
        counts.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> o.addProperty(name.apply(e.getKey()), Math.max(0.1, Stats.percent(e.getValue(), cells))));
        return o;
    }

    // ---- regions ---------------------------------------------------------------------------------

    /** Connected regions of equal non-negative labels, 4-neighbour; cells with a negative label are -1. */
    public Regions regions(int[] labels) {
        int[] region = new int[labels.length];
        Arrays.fill(region, -1);
        List<Integer> areas = new ArrayList<>();
        List<Integer> regionLabels = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < labels.length; start++) {
            if (labels[start] < 0 || region[start] >= 0) continue;
            int id = areas.size(), label = labels[start], area = 0;
            region[start] = id;
            queue.add(start);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                area++;
                int x = i % side, z = i / side;
                if (x > 0) visit(i - 1, label, id, labels, region, queue);
                if (x < side - 1) visit(i + 1, label, id, labels, region, queue);
                if (z > 0) visit(i - side, label, id, labels, region, queue);
                if (z < side - 1) visit(i + side, label, id, labels, region, queue);
            }
            areas.add(area);
            regionLabels.add(label);
        }
        return new Regions(region, areas.stream().mapToInt(Integer::intValue).toArray(),
                regionLabels.stream().mapToInt(Integer::intValue).toArray());
    }

    private static void visit(int j, int label, int id, int[] labels, int[] region, ArrayDeque<Integer> queue) {
        if (region[j] < 0 && labels[j] == label) {
            region[j] = id;
            queue.add(j);
        }
    }

    JsonObject regionJson(Regions r, int[] labels) {
        return regionJson(r, labels, side * side, false);
    }

    /**
     * Biome regions over the labelled cells alone: densities, shares and the
     * near-edge share are of those cells, and only a boundary between two
     * labelled cells is an edge, so a coast is no biome edge.
     */
    JsonObject landRegionJson(int[] labels, IntFunction<String> name) {
        int cells = 0;
        for (int l : labels) if (l >= 0) cells++;
        JsonObject o = new JsonObject();
        o.addProperty("note", "regions of one biome over land cells outside a sea biome; shares and densities are of those cells, "
                + "and only a boundary between two of them is an edge");
        o.addProperty("landPercent", Stats.percent(cells, side * side));
        o.add("biomesPercent", shares(labels, name, cells));
        regionJson(regions(labels), labels, cells, true).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
        return o;
    }

    private JsonObject regionJson(Regions r, int[] labels, int cells, boolean labelledOnly) {
        JsonObject o = new JsonObject();
        double cellHa = (double) step * step / 10_000.0;
        double gridKm2 = Math.max(1, cells) * (double) step * step / 1_000_000.0;
        int speckCells = speckCells();
        int major = 0, regions = 0;
        long sumSq = 0, labelled = 0, specks = 0;
        for (int a : r.areas) {
            if (a < speckCells) specks += a;
            else regions++;
            if (Stats.percent(a, cells) >= MAJOR_PERCENT) major++;
            sumSq += (long) a * a;
            labelled += a;
        }
        o.addProperty("count", r.count());
        o.addProperty("perKm2", Stats.round1(regions / gridKm2));
        o.addProperty("specksPercent", Stats.percent(specks, cells));
        o.addProperty("major", major);
        int[] sorted = r.areas.clone();
        Arrays.sort(sorted);
        o.addProperty("medianHa", round2(sorted.length == 0 ? 0 : Stats.percentile(sorted, 50) * cellHa));
        o.addProperty("largestHa", Stats.round1(sorted.length == 0 ? 0 : sorted[sorted.length - 1] * cellHa));
        o.addProperty("largestPercent", topShare(r, 1, cells));
        o.addProperty("top3Percent", topShare(r, 3, cells));
        o.addProperty("areaWeightedMeanHa", Stats.round1(labelled == 0 ? 0 : (double) sumSq / labelled * cellHa));
        long edges = 0;
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = z * side + x;
                if (labelledOnly && labels[i] < 0) continue;
                if (x < side - 1 && labels[i] != labels[i + 1] && (!labelledOnly || labels[i + 1] >= 0)) edges++;
                if (z < side - 1 && labels[i] != labels[i + side] && (!labelledOnly || labels[i + side] >= 0)) edges++;
            }
        }
        o.addProperty("edgeKmPerKm2", round2(edges * step / 1000.0 / gridKm2));
        int[] dist = edgeDistance(r);
        int near = 0;
        for (int i = 0; i < dist.length; i++) if (dist[i] <= EDGE_BLOCKS && (!labelledOnly || labels[i] >= 0)) near++;
        o.addProperty("nearEdgePercent", Stats.percent(near, cells));
        return o;
    }

    /** The share of the grid in the {@code n} largest regions together. */
    double topShare(Regions r, int n) {
        return topShare(r, n, side * side);
    }

    private static double topShare(Regions r, int n, int cells) {
        int[] top = largest(r);
        long sum = 0;
        for (int k = 0; k < Math.min(n, top.length); k++) sum += r.areas[top[k]];
        return Stats.percent(sum, cells);
    }

    /** Region ids, largest first. */
    static int[] largest(Regions r) {
        Integer[] ids = new Integer[r.count()];
        for (int i = 0; i < ids.length; i++) ids[i] = i;
        Arrays.sort(ids, (a, b) -> Integer.compare(r.areas[b], r.areas[a]));
        return Arrays.stream(ids).mapToInt(Integer::intValue).toArray();
    }

    /** The fewest cells a region of {@link #SPECK_HA} or more covers. */
    int speckCells() {
        return (int) Math.ceil(SPECK_HA * 10_000.0 / ((double) step * step));
    }

    /**
     * Each cell's distance in blocks (chessboard, in whole cells) to the nearest
     * boundary between two regions that are not specks; the grid's own edge is no boundary.
     */
    int[] edgeDistance(Regions r) {
        int minCells = speckCells();
        int n = side * side;
        int[] d = new int[n];
        Arrays.fill(d, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            int x = i % side, z = i / side;
            boolean boundary = false;
            for (int dz = -1; dz <= 1 && !boundary; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                    int a = r.region[i], b = r.region[nz * side + nx];
                    if (a != b && a >= 0 && b >= 0 && r.areas[a] >= minCells && r.areas[b] >= minCells) {
                        boundary = true;
                        break;
                    }
                }
            }
            if (boundary) {
                d[i] = 0;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % side, z = i / side;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                    int j = nz * side + nx;
                    if (d[j] == Integer.MAX_VALUE) {
                        d[j] = d[i] + 1;
                        queue.add(j);
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) d[i] = d[i] == Integer.MAX_VALUE ? Integer.MAX_VALUE : d[i] * step;
        return d;
    }

    private JsonObject pointJson(int bx, int bz, Regions b, Regions l, IntFunction<String> biomeName, IntFunction<String> landformName,
                                 int[] edge) {
        JsonObject o = new JsonObject();
        int x = Math.round((bx - originX) / (float) step), z = Math.round((bz - originZ) / (float) step);
        o.addProperty("x", bx);
        o.addProperty("z", bz);
        if (x < 0 || z < 0 || x >= side || z >= side) {
            o.addProperty("outside", true);
            return o;
        }
        int i = z * side + x;
        int cells = side * side;
        if (b.region[i] >= 0) {
            o.addProperty("biome", biomeName.apply(b.labels[b.region[i]]));
            o.addProperty("biomeRegionPercent", Stats.percent(b.areas[b.region[i]], cells));
        }
        if (l.region[i] >= 0) {
            o.addProperty("landform", landformName.apply(l.labels[l.region[i]]));
            o.addProperty("landformRegionPercent", Stats.percent(l.areas[l.region[i]], cells));
        }
        o.addProperty("biomeEdgeBlocks", edge[i] == Integer.MAX_VALUE ? -1 : edge[i]);
        return o;
    }

    // ---- correlation length ----------------------------------------------------------------------

    /**
     * The semivariogram of {@code field} along x and z at lags of whole cells up
     * to half the grid: the range is the first lag whose semivariance reaches
     * {@link #SILL_SHARE} of the field's variance. {@code beyond} is true when no
     * lag reaches it: the field has a trend wider than half the border.
     */
    JsonObject correlation(double[] field) {
        JsonObject o = new JsonObject();
        double sum = 0, sumSq = 0;
        int n = 0;
        for (double v : field) {
            if (Double.isNaN(v)) continue;
            sum += v;
            sumSq += v * v;
            n++;
        }
        if (n < 2) {
            o.addProperty("samples", n);
            return o;
        }
        double mean = sum / n, variance = sumSq / n - mean * mean;
        int maxLag = side / 2;
        double[] gamma = variogram(field, maxLag);
        int range = -1;
        for (int k = 1; k <= maxLag; k++) {
            if (gamma[k] >= SILL_SHARE * variance) {
                range = k;
                break;
            }
        }
        o.addProperty("blocks", (range < 0 ? maxLag : range) * step);
        o.addProperty("beyond", range < 0);
        o.addProperty("sd", Stats.round1(Math.sqrt(Math.max(0, variance))));
        int half = Math.max(1, Math.round(256f / step));
        o.addProperty("semivariance256Percent", variance <= 0 ? 0 : Stats.percent(gamma[Math.min(half, maxLag)], variance));
        return o;
    }

    /** Semivariance by lag in cells, pooled over x and z pairs; index 0 is unused. */
    double[] variogram(double[] field, int maxLag) {
        double[] gamma = new double[maxLag + 1];
        for (int k = 1; k <= maxLag; k++) {
            double s = 0;
            long pairs = 0;
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    double v = field[z * side + x];
                    if (Double.isNaN(v)) continue;
                    if (x + k < side) {
                        double w = field[z * side + x + k];
                        if (!Double.isNaN(w)) {
                            s += (v - w) * (v - w);
                            pairs++;
                        }
                    }
                    if (z + k < side) {
                        double w = field[(z + k) * side + x];
                        if (!Double.isNaN(w)) {
                            s += (v - w) * (v - w);
                            pairs++;
                        }
                    }
                }
            }
            gamma[k] = pairs == 0 ? 0 : s / pairs / 2;
        }
        return gamma;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
