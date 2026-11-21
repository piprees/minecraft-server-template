package com.customdimensions.core.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The places of a dimension laid out with {@code biomeLayout.places}, read from its compiled biome source: the place
 * axis (the region axes' first), the column edges on it (place {@code i} of {@code n} holds the {@code i}th column), and
 * each biome whose entries all lie in one column, which is that place's. A biome in several columns (a role area's
 * shared biome, a bound biome) belongs to no place. A place is named for its group, else for its one member that
 * plays no role ({@code role}), else its first member.
 */
public record PlaceMap(String axis, List<Double> cuts, List<String> names, Map<String, Integer> members) {
    /** Slack on a column edge, in climate units: the compiler rounds edges to four decimals. */
    static final double EPS = 1e-4;

    public PlaceMap {
        cuts = List.copyOf(cuts);
        names = List.copyOf(names);
        members = Map.copyOf(members);
    }

    public int count() {
        return cuts.size() + 1;
    }

    /** The place whose column holds {@code value} on the place axis. */
    public int index(double value) {
        int i = 0;
        while (i < cuts.size() && value >= cuts.get(i)) i++;
        return i;
    }

    /** The place {@code biome} belongs to, or -1. */
    public int of(String biome) {
        Integer p = members.get(biome);
        return p == null ? -1 : p;
    }

    /**
     * The places of {@code biomeSource} (a biome source's JSON: the {@code customdimensions:zoned} source whose
     * {@code regions.places} is true, with its {@code base} multi-noise list), named by {@code groups} (group name to
     * member biomes); null when the source lays out no places.
     */
    public static PlaceMap of(JsonElement biomeSource, Map<String, List<String>> groups, Predicate<String> role) {
        JsonObject zoned = find(biomeSource);
        if (zoned == null) return null;
        JsonObject regions = zoned.getAsJsonObject("regions");
        String axis = regions.getAsJsonArray("axes").get(0).getAsString();
        List<Double> cuts = cuts(regions);
        JsonArray entries = entries(zoned.get("base"));
        if (entries == null) return null;
        int n = cuts.size() + 1;
        Map<String, Set<Integer>> columns = new LinkedHashMap<>();
        for (JsonElement e : entries) {
            JsonObject o = e.getAsJsonObject();
            String biome = o.get("biome").getAsString();
            double[] range = range(o.getAsJsonObject("parameters").get(axis));
            int lo = column(range[0] + EPS, cuts), hi = column(range[1] - EPS, cuts);
            columns.computeIfAbsent(biome, k -> new LinkedHashSet<>()).add(lo == hi ? lo : -1);
        }
        Map<String, Integer> members = new LinkedHashMap<>();
        List<List<String>> byPlace = new ArrayList<>();
        for (int p = 0; p < n; p++) byPlace.add(new ArrayList<>());
        columns.forEach((biome, set) -> {
            if (set.size() == 1 && !set.contains(-1)) {
                int p = set.iterator().next();
                members.put(biome, p);
                byPlace.get(p).add(biome);
            }
        });
        List<String> names = new ArrayList<>();
        for (int p = 0; p < n; p++) names.add(name(byPlace.get(p), groups, role, p));
        return new PlaceMap(axis, cuts, names, members);
    }

    private static String name(List<String> members, Map<String, List<String>> groups, Predicate<String> role, int p) {
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            for (String m : members) if (g.getValue().contains(m)) return g.getKey();
        }
        for (String m : members) if (!role.test(m)) return m;
        return members.isEmpty() ? "place_" + p : members.get(0);
    }

    /** The zoned source that lays out places, searched through the JSON; null when there is none. */
    private static JsonObject find(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement regions = o.get("regions");
        if (regions != null && regions.isJsonObject() && regions.getAsJsonObject().has("places")
                && regions.getAsJsonObject().get("places").getAsBoolean()) {
            return o;
        }
        JsonElement base = o.get("base");
        return base == null ? null : find(base);
    }

    private static JsonArray entries(JsonElement base) {
        if (base == null || !base.isJsonObject()) return null;
        JsonElement biomes = base.getAsJsonObject().get("biomes");
        return biomes != null && biomes.isJsonArray() ? biomes.getAsJsonArray() : entries(base.getAsJsonObject().get("base"));
    }

    /** The place edges: the regions' own columns, else the first area's. */
    private static List<Double> cuts(JsonObject regions) {
        List<Double> out = new ArrayList<>();
        JsonArray columns = regions.has("columns") ? regions.getAsJsonArray("columns") : null;
        if ((columns == null || columns.isEmpty()) && regions.has("areas")) {
            for (JsonElement a : regions.getAsJsonArray("areas")) {
                JsonArray c = a.getAsJsonObject().getAsJsonArray("columns");
                if (c != null && !c.isEmpty()) {
                    columns = c;
                    break;
                }
            }
        }
        if (columns != null) columns.forEach(c -> out.add(c.getAsDouble()));
        return out;
    }

    private static int column(double value, List<Double> cuts) {
        int i = 0;
        while (i < cuts.size() && value >= cuts.get(i)) i++;
        return i;
    }

    /** A parameter as {@code [min, max]}: a number is both. */
    private static double[] range(JsonElement e) {
        if (e == null) return new double[]{-2, 2};
        if (e.isJsonPrimitive()) return new double[]{e.getAsDouble(), e.getAsDouble()};
        if (e.isJsonArray()) return new double[]{e.getAsJsonArray().get(0).getAsDouble(), e.getAsJsonArray().get(1).getAsDouble()};
        JsonObject o = e.getAsJsonObject();
        return new double[]{o.get("min").getAsDouble(), o.get("max").getAsDouble()};
    }
}
