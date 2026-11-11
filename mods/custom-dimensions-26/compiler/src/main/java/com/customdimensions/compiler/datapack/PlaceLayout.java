package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.PlaceField;
import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code biomeLayout.places}: the regional land biomes, alone or in named groups, laid out as places, one
 * contiguous region of the border each whose share is its weight ({@link PlaceField}). The router's first
 * region axis becomes the place field, one value per place, which the runtime reads as it is; each place's
 * biomes take a column of that axis, mixed along the second axis by its ranked climate noise. Every other role
 * area keeps its own biomes: those a place names in its column, the rest in every column.
 */
public final class PlaceLayout {
    public static final List<String> FIELDS = List.of("groups", "arrangement", "direction", "at", "neighbours", "apart", "warp");
    public static final List<String> ARRANGEMENTS = List.of("auto", "ring", "sectors", "bands", "core");
    /** Axes the place field may replace: the climate the terrain does not read. */
    public static final List<String> PLACE_AXES = List.of("temperature", "humidity");
    static final List<String> WARP_FIELDS = List.of("blocks", "wavelength");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    /** Border units from the centre of a ring's sites. */
    static final double RING = 0.5;
    /** Border units from the centre of a core's ring of sites. */
    static final double CORE_RING = 0.6;
    /** Wavelength of the default warp, as a share of the border's radius, and its blocks as a share of the wavelength. */
    static final double DEFAULT_WAVELENGTH = 0.5;
    static final double DEFAULT_WARP = 0.1;

    /**
     * The places: each one's name and biomes, the arrangements the seed chooses from, whether the square's
     * symmetries vary them too, the warp ({@code null}: the terrain's climate warp, else the default), and the axis.
     */
    public record Places(List<String> names, List<List<String>> members, double[] shares, List<PlaceField.Arrangement> arrangements,
                         boolean symmetries, PlaceField.Warp warp, String axis, String arrangement) {
        int size() {
            return names.size();
        }

        /** The biomes of {@code ids} in place {@code p}'s column: its own, else those no place names, else all of them. */
        List<String> column(int p, List<String> ids) {
            List<String> own = new ArrayList<>();
            for (String id : ids) if (members.get(p).contains(id)) own.add(id);
            if (!own.isEmpty()) return own;
            List<String> free = new ArrayList<>();
            for (String id : ids) if (members.stream().noneMatch(m -> m.contains(id))) free.add(id);
            return free.isEmpty() ? ids : free;
        }
    }

    /** One area's cells: the multi-noise entries, the column edges and the biomes by column. */
    record Cells(List<JsonObject> entries, List<Double> cuts, List<List<String>> columns) {}

    private PlaceLayout() {
    }

    /**
     * The radius places are laid over: the smaller of the player and generation borders, the area that generates
     * and that the survey measures, else {@link RegionLayout#DEFAULT_RADIUS}.
     */
    static int radius(com.customdimensions.core.definition.Borders borders) {
        int r = Integer.MAX_VALUE;
        if (borders != null && borders.player() != null) r = Math.min(r, borders.player());
        if (borders != null && borders.generation() != null) r = Math.min(r, borders.generation());
        return r == Integer.MAX_VALUE ? RegionLayout.DEFAULT_RADIUS : r;
    }

    /** Whether {@code layout} lays its biomes out as places. */
    static boolean present(JsonObject layout) {
        return layout != null && layout.has("places");
    }

    /**
     * The places of {@code layout.places} over {@code placeable}, the regional biomes of the land (all regional
     * biomes on a terrain with no coast); {@code regional} are every regional biome, which a group may name.
     */
    static Places build(String slug, JsonObject layout, List<String> axes, List<String> placeable, List<String> regional,
                        Map<String, Double> weights, List<BiomeEntry> biomes, int radius) {
        JsonElement e = layout.get("places");
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.places must be an object");
        JsonObject spec = e.getAsJsonObject();
        for (String key : spec.keySet()) {
            if (!FIELDS.contains(key)) throw error(slug, "biomeLayout.places." + key + " is not a field; fields: " + String.join(", ", FIELDS));
        }
        String axis = axes.get(0);
        if (!PLACE_AXES.contains(axis)) {
            throw error(slug, "biomeLayout.places replaces the first region axis with the place field, which must be temperature or humidity, not "
                    + axis + ": the terrain reads the others");
        }
        for (BiomeEntry b : biomes) {
            if (b.climate() == null) continue;
            JsonElement range = b.climate().get(axis);
            if (range != null && !range.isJsonNull()) {
                throw error(slug, "biomes[" + b.id() + "].climate." + axis + " narrows the place axis, which holds one value per place; bind it on another axis");
            }
        }
        Map<String, List<String>> groups = groups(slug, spec, regional);
        List<String> names = new ArrayList<>();
        List<List<String>> members = new ArrayList<>();
        for (String id : placeable) {
            String group = null;
            for (Map.Entry<String, List<String>> g : groups.entrySet()) if (g.getValue().contains(id)) group = g.getKey();
            String name = group != null ? group : id;
            if (names.contains(name)) continue;
            names.add(name);
            members.add(group != null ? groups.get(group) : List.of(id));
        }
        for (String g : groups.keySet()) {
            if (!names.contains(g)) throw error(slug, "biomeLayout.places.groups." + g + " names no regional land biome, which a place is laid from");
        }
        int n = names.size();
        if (n == 0) throw error(slug, "biomeLayout.places has no regional land biome to lay out");
        if (n > PlaceField.MAX_PLACES) throw error(slug, "biomeLayout.places lays out " + n + " places; the limit is " + PlaceField.MAX_PLACES);
        if (axes.size() == 1) {
            for (int p = 0; p < n; p++) {
                if (members.get(p).stream().filter(placeable::contains).count() > 1) {
                    throw error(slug, "biomeLayout.places group " + names.get(p) + " mixes its biomes on a second axis; give biomeLayout.axes two");
                }
            }
        }
        double[] weight = new double[n];
        double total = 0;
        for (int p = 0; p < n; p++) {
            for (String id : members.get(p)) if (placeable.contains(id)) weight[p] += weights.getOrDefault(id, 1.0);
            total += weight[p];
        }
        double[] shares = new double[n];
        for (int p = 0; p < n; p++) shares[p] = weight[p] / total;
        PlaceField.Hints hints = new PlaceField.Hints(pairs(slug, spec, "neighbours", names), pairs(slug, spec, "apart", names));
        for (int[] a : hints.apart()) {
            for (int[] b : hints.neighbours()) {
                if (a[0] == b[0] && a[1] == b[1] || a[0] == b[1] && a[1] == b[0]) {
                    throw error(slug, "biomeLayout.places asks " + names.get(a[0]) + " and " + names.get(a[1]) + " to be both neighbours and apart");
                }
            }
        }
        double direction = number(slug, spec, "biomeLayout.places.", "direction", 0, 360, 0);
        String arrangement = spec.has("arrangement") ? string(slug, spec, "arrangement") : "auto";
        if (!ARRANGEMENTS.contains(arrangement)) throw error(slug, "biomeLayout.places.arrangement must be one of " + ARRANGEMENTS);
        double[][] at = at(slug, spec, names, radius);
        boolean anyAt = false, allAt = true;
        for (double[] a : at) {
            anyAt |= a != null;
            allAt &= a != null;
        }
        if (anyAt && !"auto".equals(arrangement)) {
            throw error(slug, "biomeLayout.places.at places sites by hand, which a named arrangement already does; give one or the other");
        }
        List<PlaceField.Arrangement> arrangements = new ArrayList<>();
        if (!"auto".equals(arrangement) || allAt) {
            double[][] sites = switch (arrangement) {
                case "ring" -> PlaceField.ring(n, RING, direction);
                case "sectors" -> PlaceField.sectors(shares, RING, direction);
                case "bands" -> PlaceField.bands(shares, direction);
                case "core" -> PlaceField.core(n, CORE_RING, direction);
                default -> {
                    double[][] s = new double[2][n];
                    for (int p = 0; p < n; p++) {
                        s[0][p] = at[p][0];
                        s[1][p] = at[p][1];
                    }
                    yield s;
                }
            };
            PlaceField.Arrangement a = PlaceField.solve(sites[0], sites[1], shares, null, false, PlaceField.SOLVE_GRID);
            String where = allAt && "auto".equals(arrangement) ? "the sites biomeLayout.places.at gives" : "the " + arrangement + " arrangement";
            if (a == null) throw error(slug, "biomeLayout.places: " + where + " cannot give every place its share; move the sites apart");
            String broken = PlaceField.broken(PlaceField.adjacency(a.labels(PlaceField.SOLVE_GRID), PlaceField.SOLVE_GRID, n), hints);
            if (broken != null) throw error(slug, "biomeLayout.places: in " + where + " " + named(broken, names));
            arrangements.add(a);
        } else {
            PlaceField.Search search = PlaceField.search(shares, at, hints, seed(slug, names));
            for (PlaceField.Arrangement found : search.arrangements()) {
                PlaceField.Arrangement a = PlaceField.refine(found, shares, PlaceField.SOLVE_GRID);
                if (a != null && PlaceField.broken(PlaceField.adjacency(a.labels(PlaceField.SOLVE_GRID), PlaceField.SOLVE_GRID, n), hints) == null) {
                    arrangements.add(a);
                }
            }
            if (arrangements.isEmpty()) {
                throw error(slug, "biomeLayout.places: no automatic arrangement holds the hints: " + named(search.refusal() == null
                        ? "no arrangement gives every place its share" : search.refusal(), names));
            }
        }
        return new Places(List.copyOf(names), List.copyOf(members), shares, List.copyOf(arrangements), "auto".equals(arrangement) && !anyAt,
                warp(slug, spec), axis, allAt && "auto".equals(arrangement) ? "at" : arrangement);
    }

    /** A deterministic seed for {@code slug}'s automatic search. */
    static long seed(String slug, List<String> names) {
        long h = 1125899906842597L;
        for (char c : (slug + "|" + String.join(",", names)).toCharArray()) h = 31 * h + c;
        return h;
    }

    private static String named(String sentence, List<String> names) {
        for (int i = names.size() - 1; i >= 0; i--) sentence = sentence.replace("places " + i + " ", "places " + names.get(i) + " ")
                .replace(" and " + i + " ", " and " + names.get(i) + " ");
        return sentence;
    }

    private static Map<String, List<String>> groups(String slug, JsonObject spec, List<String> regional) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        JsonElement e = spec.get("groups");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.places.groups must be an object of group name to a list of biomes");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            String name = kv.getKey();
            if (!NAME.matcher(name).matches()) throw error(slug, "biomeLayout.places.groups." + name + ": a group name is lowercase letters, digits and _");
            if (!kv.getValue().isJsonArray() || kv.getValue().getAsJsonArray().isEmpty()) {
                throw error(slug, "biomeLayout.places.groups." + name + " must list at least one biome");
            }
            List<String> ids = new ArrayList<>();
            for (JsonElement id : kv.getValue().getAsJsonArray()) {
                String s = id.isJsonPrimitive() ? id.getAsString() : "";
                if (!regional.contains(s)) throw error(slug, "biomeLayout.places.groups." + name + " names " + id + ", no listed biome without a climate");
                for (Map.Entry<String, List<String>> g : out.entrySet()) {
                    if (g.getValue().contains(s)) throw error(slug, "biomeLayout.places.groups: " + s + " is in both " + g.getKey() + " and " + name);
                }
                if (!ids.contains(s)) ids.add(s);
            }
            out.put(name, List.copyOf(ids));
        }
        return out;
    }

    private static List<int[]> pairs(String slug, JsonObject spec, String field, List<String> names) {
        List<int[]> out = new ArrayList<>();
        JsonElement e = spec.get(field);
        if (e == null) return out;
        String what = "biomeLayout.places." + field;
        if (!e.isJsonArray()) throw error(slug, what + " must be a list of [place, place] pairs");
        for (JsonElement pair : e.getAsJsonArray()) {
            if (!pair.isJsonArray() || pair.getAsJsonArray().size() != 2) throw error(slug, what + " must be a list of [place, place] pairs");
            int[] ij = new int[2];
            for (int k = 0; k < 2; k++) {
                JsonElement v = pair.getAsJsonArray().get(k);
                ij[k] = v.isJsonPrimitive() ? names.indexOf(v.getAsString()) : -1;
                if (ij[k] < 0) throw error(slug, what + " names " + v + ", which is no place; places: " + names);
            }
            if (ij[0] == ij[1]) throw error(slug, what + " pairs " + names.get(ij[0]) + " with itself");
            out.add(ij);
        }
        return out;
    }

    /** Each place's site from {@code at}, in border units, or null where not given. */
    private static double[][] at(String slug, JsonObject spec, List<String> names, int radius) {
        double[][] out = new double[names.size()][];
        JsonElement e = spec.get("at");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.places.at must be an object of place to [x, z] in blocks");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            int p = names.indexOf(kv.getKey());
            if (p < 0) throw error(slug, "biomeLayout.places.at." + kv.getKey() + " is no place; places: " + names);
            JsonElement v = kv.getValue();
            if (!v.isJsonArray() || v.getAsJsonArray().size() != 2 || !v.getAsJsonArray().get(0).isJsonPrimitive()
                    || !v.getAsJsonArray().get(1).isJsonPrimitive()) {
                throw error(slug, "biomeLayout.places.at." + kv.getKey() + " must be [x, z] in blocks");
            }
            double x = v.getAsJsonArray().get(0).getAsDouble() / radius, z = v.getAsJsonArray().get(1).getAsDouble() / radius;
            if (!(Math.abs(x) < 0.95 && Math.abs(z) < 0.95)) {
                throw error(slug, "biomeLayout.places.at." + kv.getKey() + " must lie inside 95% of the " + radius + "-block border");
            }
            for (int q = 0; q < out.length; q++) {
                if (out[q] != null && Math.hypot(out[q][0] - x, out[q][1] - z) < 0.02) {
                    throw error(slug, "biomeLayout.places.at puts " + names.get(q) + " and " + kv.getKey() + " on one site");
                }
            }
            out[p] = new double[]{x, z};
        }
        return out;
    }

    /** {@code places.warp}, or null to take the terrain's climate warp, else the default. */
    private static PlaceField.Warp warp(String slug, JsonObject spec) {
        JsonElement e = spec.get("warp");
        if (e == null) return null;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.places.warp must be an object of blocks and wavelength");
        for (String key : e.getAsJsonObject().keySet()) {
            if (!WARP_FIELDS.contains(key)) throw error(slug, "biomeLayout.places.warp." + key + " is not a field; fields: " + WARP_FIELDS);
        }
        double wavelength = number(slug, e.getAsJsonObject(), "biomeLayout.places.warp.", "wavelength", 64, 4096, 512);
        double blocks = number(slug, e.getAsJsonObject(), "biomeLayout.places.warp.", "blocks", 0, 1024, 0);
        return checked(slug, "biomeLayout.places.warp", new PlaceField.Warp(blocks, wavelength));
    }

    private static PlaceField.Warp checked(String slug, String field, PlaceField.Warp warp) {
        if (warp.blocks() > PlaceField.MAX_WARP_RATIO * warp.wavelength()) {
            throw error(slug, field + " moves " + warp.blocks() + " blocks over a " + warp.wavelength() + "-block wavelength; above " + PlaceField.MAX_WARP_RATIO + " of "
                    + "the wavelength the warp can fold the map and split a place");
        }
        return warp;
    }

    /** The warp {@code places} takes in {@code def}: its own, else the terrain's climate warp, else the default for {@code radius}. */
    static PlaceField.Warp warp(String slug, Definition def, Places places, int radius) {
        if (places.warp() != null) return places.warp();
        PlaceField.Warp terrain = terrainWarp(slug, def);
        if (terrain != null) {
            return new PlaceField.Warp(Math.min(terrain.blocks(), Math.floor(PlaceField.MAX_WARP_RATIO * terrain.wavelength())), terrain.wavelength());
        }
        double wavelength = Math.max(64, Math.min(4096, Math.round(DEFAULT_WAVELENGTH * radius)));
        return new PlaceField.Warp(Math.round(DEFAULT_WARP * wavelength), wavelength);
    }

    /** {@code def}'s {@code terrain.climateWarp}, or null when it does not warp. */
    static PlaceField.Warp terrainWarp(String slug, Definition def) {
        if (def.terrain() == null) return null;
        var spec = com.customdimensions.compiler.terrain.TerrainSpec.parse(slug, def.terrain());
        var shaping = spec != null ? spec.shaping() : com.customdimensions.compiler.terrain.TerrainSpec.presetShaping(slug, def.terrain());
        return shaping == null || shaping.climate().warp() <= 0 ? null : new PlaceField.Warp(shaping.climate().warp(), shaping.climate().wavelength());
    }

    /** {@code ids} of one role area cut by place: place {@code p}'s column on the place axis, rows by weight on the second. */
    static Cells cells(Places places, List<String> ids, Map<String, Double> weights, List<String> axes, JsonObject area) {
        List<JsonObject> out = new ArrayList<>();
        List<Double> cuts = new ArrayList<>();
        List<List<String>> columns = new ArrayList<>();
        if (ids.isEmpty()) return new Cells(out, cuts, columns);
        int n = places.size();
        for (int p = 0; p < n; p++) {
            List<String> column = places.column(p, ids);
            if (axes.size() == 1 && column.size() > 1) column = List.of(column.get(0));
            double lo = p == 0 ? BiomeLayout.LOW : round(PlaceField.edge(p, n));
            double hi = p == n - 1 ? BiomeLayout.HIGH : round(PlaceField.edge(p + 1, n));
            double total = 0;
            for (String id : column) total += weights.getOrDefault(id, 1.0);
            double y = 0;
            for (int k = 0; k < column.size(); k++) {
                double y1 = k == column.size() - 1 ? 1 : y + weights.getOrDefault(column.get(k), 1.0) / total;
                JsonObject cell = area.deepCopy();
                cell.add(axes.get(0), BiomeLayout.range(lo, hi));
                if (axes.size() == 2) {
                    cell.add(axes.get(1), BiomeLayout.range(y <= 0 ? BiomeLayout.LOW : RegionLayout.rank(y), y1 >= 1 ? BiomeLayout.HIGH : RegionLayout.rank(y1)));
                }
                cell.addProperty("offset", 0.0);
                out.add(BiomeLayout.entry(column.get(k), cell));
                y = y1;
            }
            columns.add(column);
            if (p > 0) cuts.add(round(PlaceField.edge(p, n)));
        }
        return new Cells(out, cuts, columns);
    }

    /** The report's words for {@code places}. */
    static String describe(Places places) {
        StringBuilder b = new StringBuilder();
        for (int p = 0; p < places.size(); p++) {
            b.append(p == 0 ? "" : ", ").append(places.names().get(p)).append(' ').append(Math.round(places.shares()[p] * 1000) / 10.0).append('%');
        }
        return places.size() + " place(s) on the " + places.axis() + " axis (" + b + "), arrangement " + places.arrangement()
                + (places.symmetries() ? ", " + places.arrangements().size() + " automatic layouts × 8 symmetries chosen by the seed"
                : places.arrangements().size() > 1 ? ", " + places.arrangements().size() + " layouts chosen by the seed" : "");
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static String string(String slug, JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw error(slug, "biomeLayout.places." + key + " must be a string");
        return e.getAsString();
    }

    private static double number(String slug, JsonObject o, String field, String key, double min, double max, double fallback) {
        JsonElement e = o.get(key);
        if (e == null) return fallback;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !(e.getAsDouble() >= min && e.getAsDouble() <= max)) {
            throw error(slug, field + key + " must be a number from " + fmt(min) + " to " + fmt(max));
        }
        return e.getAsDouble();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static IllegalArgumentException error(String slug, String message) {
        return new IllegalArgumentException(slug + ": " + message);
    }
}
