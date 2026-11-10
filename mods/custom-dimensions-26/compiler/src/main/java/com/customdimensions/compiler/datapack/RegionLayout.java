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
 *       climate reaches inside the world border and 1 the highest
 *       ({@code customdimensions:zoned}'s {@code regions} maps the sampled
 *       climate there). A cell's share of rank space is the biome's
 *       {@code weights} entry over their sum (1 when absent), so its share of
 *       the border is that weight over the sum for any seed and any {@code terrain.scales}.</li>
 *   <li>With two axes the regional biomes fill columns along the first axis
 *       in list order and rows along the second, the direction alternating by
 *       column, so consecutive biomes in the list share a border.</li>
 * </ul>
 */
public final class RegionLayout {
    public static final String MODE = "regions";
    public static final List<String> FIELDS = List.of("mode", "axes", "weights", "roles", "sea");
    /** Axes a region may be cut on; depth varies down a column, so it stays with bound biomes. */
    public static final List<String> REGION_AXES = List.of("temperature", "humidity", "continentalness", "erosion", "weirdness");
    public static final List<String> DEFAULT_AXES = List.of("temperature", "humidity");
    /** The radius regions are measured over when a definition gives no border. */
    public static final int DEFAULT_RADIUS = 1024;

    /** The multi-noise entries, and the runtime's {@code regions} object for {@code customdimensions:zoned}. */
    public record Plan(JsonArray entries, JsonObject regions) {}

    private RegionLayout() {
    }

    /** The radius players see: the player border, else the generation border, else {@link #DEFAULT_RADIUS}. */
    static int radius(com.customdimensions.core.definition.Borders borders) {
        if (borders != null && borders.player() != null) return borders.player();
        if (borders != null && borders.generation() != null) return borders.generation();
        return DEFAULT_RADIUS;
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
     * Where a terrain's sea meets its land on router continentalness, and the bands its shore and river biomes
     * take: a river band is {@link #RIVER_WEIRDNESS} either side of weirdness 0 and {@code riverLow} to
     * {@code riverHigh} on continentalness. {@code water}: the sea is water, so it may take a default ocean.
     */
    public record Coast(double line, double shoreLow, double shoreHigh, double riverLow, double riverHigh, boolean water) {
        static final double RIVER_WEIRDNESS = 0.05;

        /** Vanilla's router: its coast band holds the beaches, rivers run inland along weirdness 0. */
        static final Coast VANILLA = new Coast(BiomeRoles.COAST_LOW, BiomeRoles.COAST_LOW, BiomeRoles.COAST_HIGH,
                BiomeRoles.COAST_HIGH, BiomeLayout.HIGH, true);

        /**
         * A library terrain whose sea level sits at {@code shore[0]} and whose shore runs from {@code shore[1]} to
         * {@code shore[2]}; it has no river valleys, so a river takes its mouth, where weirdness 0 crosses the coast.
         */
        static Coast terrain(double[] shore) {
            return new Coast(shore[0], shore[1], shore[2], r(shore[0] - 0.08), r(shore[0] + 0.02), true);
        }

        Coast dry() {
            return new Coast(line, shoreLow, shoreHigh, riverLow, riverHigh, false);
        }

        private static double r(double v) {
            return Math.round(v * 10000) / 10000.0;
        }
    }

    /** Depth a cave area starts at: vanilla's cave biomes' lowest. */
    static final double CAVE_DEPTH = 0.2;
    /** Role groups in carving order: the first wins where areas overlap. */
    static final List<String> GROUPS = List.of(BiomeRoles.RIVER, BiomeRoles.SHORE, BiomeRoles.CAVE, BiomeRoles.LAND, BiomeRoles.OCEAN);

    /**
     * The coast of {@code def}'s terrain, or null when it has no sea or the region axes include continentalness
     * or weirdness, which the role areas are cut on: a library terrain's {@link
     * com.customdimensions.compiler.terrain.TerrainFamily#coastline()}, else vanilla's for the overworld presets.
     */
    static Coast coast(String slug, com.customdimensions.core.definition.Definition def, List<String> axes) {
        if (axes.contains("continentalness") || axes.contains("weirdness")) return null;
        var spec = com.customdimensions.compiler.terrain.TerrainSpec.parse(slug, def.terrain());
        Coast coast;
        if (spec != null) {
            double[] shore = spec.family().shore(spec);
            coast = shore == null ? null : Coast.terrain(shore);
        } else {
            coast = List.of("overworld", "large_biomes", "amplified").contains(def.generator().preset()) ? Coast.VANILLA : null;
        }
        var l = def.liquids();
        boolean water = l == null || ((l.defaultFluid() == null || "minecraft:water".equals(l.defaultFluid())) && !"dry".equals(l.aquifers()));
        return coast == null || water ? coast : coast.dry();
    }

    /** As {@link #plan(String, List, JsonObject, int, BiomeLibrary.Known, CompileReport.Entry, Coast, BiomeRoles)} with no coast. */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry) {
        return plan(slug, biomes, layout, radius, known, entry, null, null);
    }

    /**
     * The entries for {@code biomes} under {@code layout}: bound boxes first, then the regional cells, carved
     * disjoint. {@code radius} is the border the runtime measures the climate over. With a {@code coast}, each
     * regional biome is laid out in its role's area (see {@link #GROUPS}), weights normalised per area.
     */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry, Coast coast, BiomeRoles roles) {
        validate(slug, layout);
        List<String> axes = axes(slug, layout);
        Map<String, Double> weights = weights(slug, layout);
        Map<String, String> overrides = roles(slug, layout, biomes);
        if (coast != null && !coast.water() && biomes.stream().allMatch(b -> b.climate() != null
                || BiomeRoles.LAND.equals(group(roles.role(b.id(), overrides))))) {
            coast = null;
        }
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
        JsonElement seaField = layout.get("sea");
        if (seaField != null && (coast == null || !coast.water() || !seaField.isJsonPrimitive() || !known.contains(seaField.getAsString()))) {
            throw error(slug, coast == null || !coast.water() ? "biomeLayout.sea needs a terrain with a water sea"
                    : "biomeLayout.sea must name a biome the game has, not " + seaField);
        }

        JsonArray kept = new JsonArray();
        bound.forEach(kept::add);
        JsonObject regions = new JsonObject();
        JsonArray axesJson = new JsonArray();
        axes.forEach(axesJson::add);
        regions.add("axes", axesJson);
        String note;
        if (coast == null) {
            Cells cells = cells(regional, weights, axes, area(null, null, null));
            cells.entries().forEach(kept::add);
            if (!cells.cuts().isEmpty()) regions.add("columns", cuts(cells.cuts()));
            note = "Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius: "
                    + regional.size() + " regional biome(s) in " + cells.columns().size() + " column(s) " + cells.columns();
        } else {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            GROUPS.forEach(g -> groups.put(g, new ArrayList<>()));
            for (String id : regional) groups.get(group(roles.role(id, overrides))).add(id);
            Map<String, Double> w = new LinkedHashMap<>(weights);
            String sea = null;
            boolean boundOcean = boundIds.stream().anyMatch(id -> BiomeRoles.OCEAN.equals(roles.role(id, overrides)));
            if (groups.get(BiomeRoles.OCEAN).isEmpty() && !groups.get(BiomeRoles.LAND).isEmpty() && coast.water() && !boundOcean) {
                sea = seaField != null ? seaField.getAsString() : BiomeRoles.sea(meanTemperature(groups.get(BiomeRoles.LAND), weights, roles));
                groups.get(BiomeRoles.OCEAN).add(sea);
            }
            boolean caves = !groups.get(BiomeRoles.CAVE).isEmpty();
            boolean landless = groups.get(BiomeRoles.LAND).isEmpty();
            boolean sealess = groups.get(BiomeRoles.OCEAN).isEmpty() && !boundOcean;
            JsonArray areas = new JsonArray();
            StringBuilder parts = new StringBuilder();
            for (String g : GROUPS) {
                List<String> ids = groups.get(g);
                if (ids.isEmpty()) continue;
                double[] cont, weird = null, depth = null;
                switch (g) {
                    case BiomeRoles.RIVER -> {
                        cont = new double[]{coast.riverLow(), coast.riverHigh()};
                        weird = new double[]{-Coast.RIVER_WEIRDNESS, Coast.RIVER_WEIRDNESS};
                    }
                    case BiomeRoles.SHORE -> cont = new double[]{coast.shoreLow(), coast.shoreHigh()};
                    case BiomeRoles.CAVE -> {
                        cont = new double[]{BiomeLayout.LOW, BiomeLayout.HIGH};
                        depth = new double[]{CAVE_DEPTH, BiomeLayout.HIGH};
                    }
                    case BiomeRoles.LAND -> cont = new double[]{sealess ? BiomeLayout.LOW : coast.line(), BiomeLayout.HIGH};
                    default -> cont = new double[]{BiomeLayout.LOW, landless ? BiomeLayout.HIGH : coast.line()};
                }
                if (caves && (g.equals(BiomeRoles.LAND) || g.equals(BiomeRoles.OCEAN))) depth = new double[]{BiomeLayout.LOW, CAVE_DEPTH};
                Cells cells = cells(ids, w, axes, area(cont, weird, depth));
                cells.entries().forEach(kept::add);
                JsonObject a = new JsonObject();
                a.addProperty("role", g);
                a.add("continentalness", BiomeLayout.range(cont[0], cont[1]));
                if (weird != null) a.add("weirdness", BiomeLayout.range(weird[0], weird[1]));
                if (depth != null) a.add("depth", BiomeLayout.range(depth[0], depth[1]));
                if (!cells.cuts().isEmpty()) a.add("columns", cuts(cells.cuts()));
                areas.add(a);
                parts.append(parts.isEmpty() ? "" : "; ").append(g).append(' ').append(cells.columns());
            }
            regions.add("areas", areas);
            note = "Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius, by role (coast at continentalness "
                    + coast.line() + "): " + parts
                    + (sea == null ? "" : "; the sea takes `" + sea + "`, no ocean biome being listed")
                    + (sealess && !landless ? "; the land biomes take the sea too, which is not water" : "")
                    + (overrides.isEmpty() ? "" : "; roles given: " + overrides);
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
        regions.addProperty("radius", radius);
        entry.note(note + (boundIds.isEmpty() ? "" : "; bound by their climate: " + boundIds));
        return new Plan(carved, regions);
    }

    /** The layout group a role's regional biomes share: peaks and wetlands are land. */
    static String group(String role) {
        return switch (role) {
            case BiomeRoles.PEAK, BiomeRoles.WETLAND -> BiomeRoles.LAND;
            default -> role;
        };
    }

    /** {@code biomeLayout.roles}, each naming a listed biome and a role. */
    static Map<String, String> roles(String slug, JsonObject layout, List<BiomeEntry> biomes) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement e = layout.get("roles");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.roles must be an object of biome id to role");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            if (!v.isJsonPrimitive() || !BiomeRoles.ROLES.contains(v.getAsString())) {
                throw error(slug, "biomeLayout.roles." + kv.getKey() + " must be one of " + BiomeRoles.ROLES);
            }
            if (biomes.stream().noneMatch(b -> b.id().equals(kv.getKey()))) {
                throw error(slug, "biomeLayout.roles." + kv.getKey() + " names no listed biome");
            }
            out.put(kv.getKey(), v.getAsString());
        }
        return out;
    }

    private static double meanTemperature(List<String> ids, Map<String, Double> weights, BiomeRoles roles) {
        double sum = 0, total = 0;
        for (String id : ids) {
            var t = roles.temperature(id);
            if (t.isEmpty()) continue;
            double w = weights.getOrDefault(id, 1.0);
            sum += t.get() * w;
            total += w;
        }
        return total == 0 ? 0.8 : sum / total;
    }

    /** One group's cells, the first axis's column cuts in rank space, and its biomes by column. */
    private record Cells(List<JsonObject> entries, List<Double> cuts, List<List<String>> columns) {}

    /** Every axis full, but continentalness, weirdness and depth bounded where given. */
    private static JsonObject area(double[] cont, double[] weird, double[] depth) {
        JsonObject p = new JsonObject();
        for (String axis : MultiNoise.AXES) p.add(axis, BiomeLayout.range(BiomeLayout.LOW, BiomeLayout.HIGH));
        if (cont != null) p.add("continentalness", BiomeLayout.range(cont[0], cont[1]));
        if (weird != null) p.add("weirdness", BiomeLayout.range(weird[0], weird[1]));
        if (depth != null) p.add("depth", BiomeLayout.range(depth[0], depth[1]));
        return p;
    }

    private static JsonArray cuts(List<Double> cuts) {
        JsonArray out = new JsonArray();
        cuts.forEach(out::add);
        return out;
    }

    /** {@code ids} cut into rank cells of {@code area} on {@code axes}, each a share of its weight over the group's sum. */
    private static Cells cells(List<String> ids, Map<String, Double> weights, List<String> axes, JsonObject area) {
        List<JsonObject> out = new ArrayList<>();
        List<Double> columnCuts = new ArrayList<>();
        List<List<String>> columns = new ArrayList<>();
        if (ids.isEmpty()) return new Cells(out, columnCuts, columns);
        double[] w = new double[ids.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.getOrDefault(ids.get(i), 1.0);
            total += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] /= total;
        int[] groups = axes.size() == 1 ? new int[]{ids.size()} : columns(w, Math.max(1, (int) Math.round(Math.sqrt(w.length))));
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
                JsonObject p = area.deepCopy();
                if (axes.size() == 1) {
                    p.add(axes.get(0), rankRange(y, y1));
                } else {
                    p.add(axes.get(0), rankRange(x, x1));
                    p.add(axes.get(1), rankRange(y, y1));
                }
                p.addProperty("offset", 0.0);
                out.add(BiomeLayout.entry(ids.get(i), p));
                column.add(ids.get(i));
                y = y1;
            }
            columns.add(column);
            if (c < groups.length - 1) columnCuts.add(rank(x1));
            x = x1;
            start = end;
        }
        return new Cells(out, columnCuts, columns);
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
