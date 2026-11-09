package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.BiomeEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code biomeLayout} {@code "regions"}: a biome list laid out as large regions
 * on slow climate axes instead of bands.
 *
 * <ul>
 *   <li>A listed biome with a {@code climate} is <em>bound</em>: it keeps that
 *       box, which wins over every region (an ocean on continentalness, a cave
 *       on depth, a peak on erosion).</li>
 *   <li>Every other listed biome is <em>regional</em>: it takes a cell of the
 *       region axes in rank space, where −1 is the lowest value the seed's
 *       climate reaches inside the generation border and 1 the highest
 *       ({@code customdimensions:zoned}'s {@code regions} maps the sampled
 *       climate there). A cell's share of rank space is the biome's
 *       {@code weights} entry over their sum (1 when absent), so its share of
 *       the border is that weight for any seed and any {@code terrain.scales}.</li>
 *   <li>With two axes the regional biomes fill columns along the first axis
 *       in list order and rows along the second, the direction alternating by
 *       column, so consecutive biomes in the list share a border.</li>
 * </ul>
 */
public final class RegionLayout {
    public static final String MODE = "regions";
    public static final List<String> FIELDS = List.of("mode", "axes", "weights");
    /** Axes a region may be cut on; depth varies down a column, so it stays with bound biomes. */
    public static final List<String> REGION_AXES = List.of("temperature", "humidity", "continentalness", "erosion", "weirdness");
    public static final List<String> DEFAULT_AXES = List.of("temperature", "humidity");
    /** The generation radius regions are measured over when a definition gives none. */
    public static final int DEFAULT_RADIUS = 1024;

    /** The multi-noise entries, and the runtime's {@code regions} object for {@code customdimensions:zoned}. */
    public record Plan(JsonArray entries, JsonObject regions) {}

    private RegionLayout() {
    }

    /** Throws when {@code layout} is malformed; {@code slug} names the definition. */
    static void validate(String slug, JsonObject layout) {
        for (String key : layout.keySet()) {
            if (!FIELDS.contains(key)) throw error(slug, "biomeLayout." + key + " is not a field; fields: " + String.join(", ", FIELDS));
        }
        JsonElement mode = layout.get("mode");
        if (mode == null || !mode.isJsonPrimitive() || !MODE.equals(mode.getAsString())) {
            throw error(slug, "biomeLayout.mode must be \"" + MODE + "\"");
        }
        axes(slug, layout);
        weights(slug, layout);
    }

    static List<String> axes(String slug, JsonObject layout) {
        JsonElement e = layout.get("axes");
        if (e == null) return DEFAULT_AXES;
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > 2) {
            throw error(slug, "biomeLayout.axes must list one or two of " + String.join(", ", REGION_AXES));
        }
        List<String> axes = new ArrayList<>();
        for (JsonElement a : e.getAsJsonArray()) {
            String axis = a.isJsonPrimitive() ? a.getAsString() : "";
            if (!REGION_AXES.contains(axis) || axes.contains(axis)) {
                throw error(slug, "biomeLayout.axes must list one or two different axes of " + String.join(", ", REGION_AXES));
            }
            axes.add(axis);
        }
        return axes;
    }

    static Map<String, Double> weights(String slug, JsonObject layout) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonElement e = layout.get("weights");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.weights must be an object of biome id to weight");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !(v.getAsDouble() > 0) || Double.isInfinite(v.getAsDouble())) {
                throw error(slug, "biomeLayout.weights." + kv.getKey() + " must be a number above 0");
            }
            out.put(kv.getKey(), v.getAsDouble());
        }
        return out;
    }

    /**
     * The entries for {@code biomes} under {@code layout}: bound boxes first, then the regional cells, carved
     * disjoint. {@code radius} is the generation border the runtime measures the climate over.
     */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry) {
        validate(slug, layout);
        List<String> axes = axes(slug, layout);
        Map<String, Double> weights = weights(slug, layout);
        JsonArray bound = new JsonArray();
        List<String> regional = new ArrayList<>();
        List<String> boundIds = new ArrayList<>();
        for (BiomeEntry b : biomes) {
            if (!known.contains(b.id())) {
                entry.skippedBiome(b.id(), DatapackCompiler.UNKNOWN_BIOME);
                continue;
            }
            if (b.climate() != null) {
                bound.add(BiomeLayout.entry(b.id(), BiomeLayout.climate(b.id(), b.climate())));
                if (!boundIds.contains(b.id())) boundIds.add(b.id());
            } else if (!regional.contains(b.id())) {
                regional.add(b.id());
            }
        }
        for (String id : weights.keySet()) {
            if (!regional.contains(id)) {
                throw error(slug, "biomeLayout.weights." + id + " names no listed biome without a climate");
            }
        }
        double[] w = new double[regional.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.getOrDefault(regional.get(i), 1.0);
            total += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] /= total;

        JsonArray kept = new JsonArray();
        bound.forEach(kept::add);
        List<Double> columnCuts = new ArrayList<>();
        List<List<String>> columns = new ArrayList<>();
        if (!regional.isEmpty()) {
            int[] groups = axes.size() == 1 ? new int[]{regional.size()} : columns(w, Math.max(1, (int) Math.round(Math.sqrt(w.length))));
            int start = 0;
            double x = 0;
            for (int c = 0; c < groups.length; c++) {
                int end = start + groups[c];
                double width = 0;
                for (int i = start; i < end; i++) width += w[i];
                double x1 = c == groups.length - 1 ? 1 : x + width;
                List<String> column = new ArrayList<>();
                List<Integer> order = new ArrayList<>();
                for (int i = start; i < end; i++) order.add(i);
                if (c % 2 == 1) java.util.Collections.reverse(order);
                double y = 0;
                for (int k = 0; k < order.size(); k++) {
                    int i = order.get(k);
                    double y1 = k == order.size() - 1 ? 1 : y + w[i] / width;
                    JsonObject p = new JsonObject();
                    for (String axis : MultiNoise.AXES) p.add(axis, BiomeLayout.range(BiomeLayout.LOW, BiomeLayout.HIGH));
                    if (axes.size() == 1) {
                        p.add(axes.get(0), rankRange(y, y1));
                    } else {
                        p.add(axes.get(0), rankRange(x, x1));
                        p.add(axes.get(1), rankRange(y, y1));
                    }
                    p.addProperty("offset", 0.0);
                    kept.add(BiomeLayout.entry(regional.get(i), p));
                    column.add(regional.get(i));
                    y = y1;
                }
                columns.add(column);
                if (c < groups.length - 1) columnCuts.add(rank(x1));
                x = x1;
                start = end;
            }
        }
        if (kept.isEmpty()) {
            kept.add(MultiNoise.fullRange("minecraft:plains"));
            entry.note(DatapackCompiler.FALLBACK_NOTE + ": no listed biome is known, so `minecraft:plains` covers all of climate space");
        }
        JsonArray carved = MultiNoise.carve(kept);
        int[] pair = MultiNoise.firstOverlap(carved);
        if (pair != null) {
            throw new IllegalStateException("multi-noise entries " + pair[0] + " and " + pair[1] + " still overlap");
        }
        JsonObject regions = new JsonObject();
        JsonArray axesJson = new JsonArray();
        axes.forEach(axesJson::add);
        regions.add("axes", axesJson);
        if (!columnCuts.isEmpty()) {
            JsonArray cuts = new JsonArray();
            columnCuts.forEach(cuts::add);
            regions.add("columns", cuts);
        }
        regions.addProperty("radius", radius);
        entry.note("Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius: "
                + regional.size() + " regional biome(s) in " + columns.size() + " column(s) " + columns
                + (boundIds.isEmpty() ? "" : "; bound by their climate: " + boundIds));
        return new Plan(carved, regions);
    }

    /**
     * Sizes of {@code k} contiguous groups of the shares {@code w} (summing to 1) whose sums are closest to
     * {@code 1 / k} in the least-squares sense; each group holds at least one share.
     */
    static int[] columns(double[] w, int k) {
        int n = w.length;
        k = Math.min(k, n);
        double target = 1.0 / k;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + w[i];
        double[][] cost = new double[k + 1][n + 1];
        int[][] cut = new int[k + 1][n + 1];
        for (double[] row : cost) java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
        cost[0][0] = 0;
        for (int g = 1; g <= k; g++) {
            for (int j = g; j <= n - (k - g); j++) {
                for (int i = g - 1; i < j; i++) {
                    if (cost[g - 1][i] == Double.POSITIVE_INFINITY) continue;
                    double d = prefix[j] - prefix[i] - target;
                    double c = cost[g - 1][i] + d * d;
                    if (c < cost[g][j] - 1e-15) {
                        cost[g][j] = c;
                        cut[g][j] = i;
                    }
                }
            }
        }
        int[] sizes = new int[k];
        int j = n;
        for (int g = k; g >= 1; g--) {
            int i = cut[g][j];
            sizes[g - 1] = j - i;
            j = i;
        }
        return sizes;
    }

    /** Rank-space bounds of the share interval [{@code f0}, {@code f1}]; the outer edges reach the axis bounds. */
    private static JsonArray rankRange(double f0, double f1) {
        return BiomeLayout.range(f0 <= 0 ? BiomeLayout.LOW : rank(f0), f1 >= 1 ? BiomeLayout.HIGH : rank(f1));
    }

    /** The rank-space value of cumulative share {@code f}. */
    static double rank(double f) {
        return Math.round((-1 + 2 * f) * 10000) / 10000.0;
    }

    private static IllegalArgumentException error(String slug, String message) {
        return new IllegalArgumentException(slug + ": " + message);
    }
}
