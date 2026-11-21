package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a structure may stand (lore/placement.md § Fitting the setting, G3):
 * slope across its footprint, the surface height, its height relative to the
 * ground round it, its distance to water and to a cliff face, shelter, and
 * biome. Read from a definition's {@code structures.sites.<id>} or a set
 * piece's {@code site}; tested on a {@link Terrain} at a site's centre column.
 * Every test is optional; an empty filter accepts everywhere.
 */
public record SiteFilter(Integer slope, int radius, Integer minHeight, Integer maxHeight, Relative relative,
                         Distance water, Distance cliff, List<String> shelter, List<String> biomes) {

    public static final Set<String> FIELDS = Set.of("slope", "radius", "height", "relative", "water", "cliff", "shelter", "biomes");
    public static final Set<String> RELATIVE_KINDS = Set.of("summit", "spur", "valley");
    public static final Set<String> WATER_KINDS = Set.of("water", "river", "ocean", "shore");
    public static final Set<String> SHELTER_KINDS = Set.of("overhang", "hollow", "lee", "canopy");
    /** The biome tags a {@code canopy} shelter reads as tree cover. */
    public static final List<String> CANOPY_TAGS = List.of("#minecraft:is_forest", "#minecraft:is_jungle", "#minecraft:is_taiga");
    public static final int DEFAULT_RADIUS = 8;
    public static final int DEFAULT_RISE = 8;
    public static final int DEFAULT_REACH = 48;
    public static final int DEFAULT_CLIFF_RISE = 10;
    /** Water and cliff searches look no further than this. */
    public static final int MAX_SEARCH = 128;
    static final int RING_STEP = 8;
    static final int CLIFF_STEP = 4;
    static final int HOLLOW_REACH = 24;
    static final int HOLLOW_RISE = 4;
    static final int LEE_REACH = 32;
    static final int LEE_RISE = 12;
    static final int OVERHANG_REACH = 8;

    public static final SiteFilter NONE = new SiteFilter(null, DEFAULT_RADIUS, null, null, null, null, null, List.of(), List.of());

    /** Height relative to the ring of ground {@code reach} blocks out: a summit, a spur or a valley floor. */
    public record Relative(String kind, int rise, int reach) {}

    /** A distance window in blocks to the nearest water of a kind, or to a cliff face of {@code rise}. */
    public record Distance(String kind, Integer min, Integer max, int rise) {
        int limit() {
            return Math.min(MAX_SEARCH, Math.max(min == null ? 0 : min, max == null ? 0 : max));
        }
    }

    /** What a filter reads of the world, at block columns. */
    public interface Terrain {
        /** The surface height (the first free block above the terrain). */
        int height(int x, int z);

        /** The height of the first free block above the solid floor under any water; equal to {@link #height} on land. */
        int floor(int x, int z);

        /** Whether the biome at the position is one of {@code biomes} (ids or {@code #tags}). */
        boolean inBiomes(int x, int y, int z, List<String> biomes);

        /** Whether solid rock hangs over the air above {@code y} at this column (an overhang or a cave mouth). */
        boolean overhang(int x, int z, int y);
    }

    public SiteFilter {
        shelter = shelter == null ? List.of() : List.copyOf(shelter);
        biomes = biomes == null ? List.of() : List.copyOf(biomes);
    }

    public boolean isEmpty() {
        return slope == null && minHeight == null && maxHeight == null && relative == null && water == null && cliff == null
                && shelter.isEmpty() && biomes.isEmpty();
    }

    /** This filter with the radius set, for a set piece whose footprint is its size. */
    public SiteFilter withRadius(int r) {
        return new SiteFilter(slope, r, minHeight, maxHeight, relative, water, cliff, shelter, biomes);
    }

    /** Why the site centred at ({@code x}, {@code z}) fails this filter, or null when it passes. Cheapest tests first. */
    public String refusal(Terrain t, int x, int z) {
        if (isEmpty()) return null;
        int h = t.height(x, z);
        if (minHeight != null && h < minHeight) return "surface " + h + " is below " + minHeight;
        if (maxHeight != null && h > maxHeight) return "surface " + h + " is above " + maxHeight;
        if (slope != null) {
            int spread = spread(t, x, z, radius);
            if (spread > slope) return "slope " + spread + " over " + radius + " blocks exceeds " + slope;
        }
        if (!biomes.isEmpty() && !t.inBiomes(x, h, z, biomes)) return "biome is not one of " + biomes;
        if (relative != null && !relativeHolds(t, x, z, h, relative)) return "not a " + relative.kind() + " (rise " + relative.rise()
                + " over " + relative.reach() + " blocks)";
        if (!shelter.isEmpty() && shelter.stream().noneMatch(k -> sheltered(t, x, z, h, k))) return "no shelter of " + shelter;
        if (water != null) {
            String why = window("water (" + water.kind() + ")", nearestWater(t, x, z, water), water);
            if (why != null) return why;
        }
        if (cliff != null) {
            String why = window("cliff face of " + cliff.rise(), nearestCliff(t, x, z, cliff), cliff);
            if (why != null) return why;
        }
        return null;
    }

    /** Why a start whose box's floor is at {@code floorY} fails this filter's height range, or null when it lies inside it. */
    public String floorRefusal(int floorY) {
        if (minHeight != null && floorY < minHeight) return "its floor y " + floorY + " is below " + minHeight;
        if (maxHeight != null && floorY > maxHeight) return "its floor y " + floorY + " is above " + maxHeight;
        return null;
    }

    public boolean accepts(Terrain t, int x, int z) {
        return refusal(t, x, z) == null;
    }

    /** The largest height difference over the footprint's centre, corners and edge midpoints. */
    static int spread(Terrain t, int x, int z, int r) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int h = t.height(x + dx * r, z + dz * r);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        return hi - lo;
    }

    private static final int[][] RING = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** The eight heights on a ring of {@code reach} blocks (diagonals at the same distance). */
    static int[] ring(Terrain t, int x, int z, int reach) {
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) {
            double norm = RING[i][0] != 0 && RING[i][1] != 0 ? Math.sqrt(0.5) : 1;
            out[i] = t.height(x + (int) Math.round(RING[i][0] * reach * norm), z + (int) Math.round(RING[i][1] * reach * norm));
        }
        return out;
    }

    static boolean relativeHolds(Terrain t, int x, int z, int h, Relative r) {
        int[] ring = ring(t, x, z, r.reach());
        int lower = 0;
        int higher = 0;
        int max = Integer.MIN_VALUE;
        for (int v : ring) {
            if (v <= h - r.rise()) lower++;
            if (v >= h + r.rise()) higher++;
            max = Math.max(max, v);
        }
        return switch (r.kind()) {
            case "summit" -> lower == 8 && h >= max;
            case "spur" -> lower >= 5 && lower < 8;
            case "valley" -> higher >= 6;
            default -> false;
        };
    }

    static boolean sheltered(Terrain t, int x, int z, int h, String kind) {
        switch (kind) {
            case "overhang" -> {
                if (t.overhang(x, z, h)) return true;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (t.overhang(x + d[0] * OVERHANG_REACH, z + d[1] * OVERHANG_REACH, h)) return true;
                }
                return false;
            }
            case "hollow" -> {
                int higher = 0;
                for (int v : ring(t, x, z, HOLLOW_REACH)) if (v >= h + HOLLOW_RISE) higher++;
                return higher >= 6;
            }
            case "lee" -> {
                for (int v : ring(t, x, z, LEE_REACH)) if (v >= h + LEE_RISE) return true;
                return false;
            }
            case "canopy" -> {
                return t.inBiomes(x, h, z, CANOPY_TAGS);
            }
            default -> {
                return false;
            }
        }
    }

    static boolean isWater(Terrain t, int x, int z, String kind) {
        int h = t.height(x, z);
        if (h <= t.floor(x, z)) return false;
        return switch (kind) {
            case "river" -> t.inBiomes(x, h - 1, z, List.of("#minecraft:is_river"));
            case "ocean" -> t.inBiomes(x, h - 1, z, List.of("#minecraft:is_ocean"));
            default -> true;
        };
    }

    /** The distance to the nearest matching column on rings every {@value #RING_STEP} blocks, or -1 when none lies within the search. */
    static int nearestWater(Terrain t, int x, int z, Distance d) {
        boolean shore = "shore".equals(d.kind());
        boolean wet = shore && isWater(t, x, z, "water");
        int limit = d.limit();
        for (int r = 0; r <= limit; r += RING_STEP) {
            int n = r == 0 ? 1 : Math.max(8, (int) Math.ceil(2 * Math.PI * r / RING_STEP));
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                int px = x + (int) Math.round(r * Math.cos(a));
                int pz = z + (int) Math.round(r * Math.sin(a));
                boolean hit = shore ? isWater(t, px, pz, "water") != wet : isWater(t, px, pz, d.kind());
                if (hit) return r;
            }
        }
        return -1;
    }

    /** The distance to the nearest drop of {@code rise} or more between columns {@value #CLIFF_STEP} apart along eight rays, or -1. */
    static int nearestCliff(Terrain t, int x, int z, Distance d) {
        int limit = d.limit();
        int best = -1;
        for (int[] dir : RING) {
            int prev = t.height(x, z);
            for (int r = CLIFF_STEP; r <= limit + CLIFF_STEP; r += CLIFF_STEP) {
                double norm = dir[0] != 0 && dir[1] != 0 ? Math.sqrt(0.5) : 1;
                int h = t.height(x + (int) Math.round(dir[0] * r * norm), z + (int) Math.round(dir[1] * r * norm));
                if (Math.abs(h - prev) >= d.rise()) {
                    int at = r - CLIFF_STEP;
                    if (best < 0 || at < best) best = at;
                    break;
                }
                prev = h;
            }
        }
        return best;
    }

    private static String window(String what, int distance, Distance d) {
        if (d.max() != null && (distance < 0 || distance > d.max())) {
            return "no " + what + " within " + d.max() + " blocks";
        }
        if (d.min() != null && distance >= 0 && distance < d.min()) {
            return what + " at " + distance + " blocks, nearer than " + d.min();
        }
        return null;
    }

    // ---- reading and writing ---------------------------------------------------------------

    /** Parses one filter; {@code where} prefixes every error ("the_x: structures.sites.minecraft:igloo"). */
    public static SiteFilter read(String where, JsonObject o) {
        for (String key : o.keySet()) {
            if (!FIELDS.contains(key)) throw new IllegalArgumentException(where + "." + key + " is not a site field (" + FIELDS + ")");
        }
        Integer slope = optInt(where, o, "slope", 0);
        int radius = o.has("radius") ? optInt(where, o, "radius", 1) : DEFAULT_RADIUS;
        Integer minH = null;
        Integer maxH = null;
        if (o.has("height")) {
            JsonObject h = obj(where, o, "height");
            only(where + ".height", h, Set.of("min", "max"));
            minH = optInt(where + ".height", h, "min", Integer.MIN_VALUE);
            maxH = optInt(where + ".height", h, "max", Integer.MIN_VALUE);
            if (minH != null && maxH != null && minH > maxH) throw new IllegalArgumentException(where + ".height: min is above max");
        }
        Relative relative = null;
        if (o.has("relative")) {
            JsonElement e = o.get("relative");
            JsonObject r = e.isJsonPrimitive() ? kindOnly(e.getAsString()) : obj(where, o, "relative");
            only(where + ".relative", r, Set.of("kind", "rise", "reach"));
            String kind = string(where + ".relative", r, "kind");
            if (!RELATIVE_KINDS.contains(kind)) throw new IllegalArgumentException(where + ".relative.kind must be one of " + RELATIVE_KINDS);
            relative = new Relative(kind, r.has("rise") ? optInt(where + ".relative", r, "rise", 1) : DEFAULT_RISE,
                    r.has("reach") ? optInt(where + ".relative", r, "reach", 4) : DEFAULT_REACH);
        }
        Distance water = null;
        if (o.has("water")) {
            JsonObject w = obj(where, o, "water");
            only(where + ".water", w, Set.of("kind", "min", "max"));
            String kind = w.has("kind") ? string(where + ".water", w, "kind") : "water";
            if (!WATER_KINDS.contains(kind)) throw new IllegalArgumentException(where + ".water.kind must be one of " + WATER_KINDS);
            water = distance(where + ".water", w, kind, 0);
        }
        Distance cliff = null;
        if (o.has("cliff")) {
            JsonObject c = obj(where, o, "cliff");
            only(where + ".cliff", c, Set.of("min", "max", "rise"));
            cliff = distance(where + ".cliff", c, "cliff", c.has("rise") ? optInt(where + ".cliff", c, "rise", 2) : DEFAULT_CLIFF_RISE);
        }
        List<String> shelter = new ArrayList<>();
        if (o.has("shelter")) {
            JsonElement s = o.get("shelter");
            if (s.isJsonArray()) s.getAsJsonArray().forEach(e -> shelter.add(e.getAsString()));
            else shelter.add(s.getAsString());
            for (String k : shelter) {
                if (!SHELTER_KINDS.contains(k)) throw new IllegalArgumentException(where + ".shelter `" + k + "` must be one of " + SHELTER_KINDS);
            }
        }
        List<String> biomes = new ArrayList<>();
        if (o.has("biomes")) {
            if (!o.get("biomes").isJsonArray()) throw new IllegalArgumentException(where + ".biomes must be a list of biome ids or #tags");
            o.getAsJsonArray("biomes").forEach(e -> biomes.add(e.getAsString()));
        }
        return new SiteFilter(slope, radius, minH, maxH, relative, water, cliff, shelter, biomes);
    }

    /** {@code structures.sites}: structure id or {@code #tag} to its filter, in file order. */
    public static Map<String, SiteFilter> readAll(String slug, JsonElement sites) {
        Map<String, SiteFilter> out = new LinkedHashMap<>();
        if (sites == null) return out;
        if (!sites.isJsonObject()) throw new IllegalArgumentException(slug + ": structures.sites must be an object of structure id to site");
        for (Map.Entry<String, JsonElement> e : sites.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) throw new IllegalArgumentException(slug + ": structures.sites." + e.getKey() + " must be an object");
            if (!e.getKey().contains(":")) {
                throw new IllegalArgumentException(slug + ": structures.sites." + e.getKey() + " must be a structure id or #tag");
            }
            out.put(e.getKey(), read(slug + ": structures.sites." + e.getKey(), e.getValue().getAsJsonObject()));
        }
        return out;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (slope != null) o.addProperty("slope", slope);
        o.addProperty("radius", radius);
        if (minHeight != null || maxHeight != null) {
            JsonObject h = new JsonObject();
            if (minHeight != null) h.addProperty("min", minHeight);
            if (maxHeight != null) h.addProperty("max", maxHeight);
            o.add("height", h);
        }
        if (relative != null) {
            JsonObject r = new JsonObject();
            r.addProperty("kind", relative.kind());
            r.addProperty("rise", relative.rise());
            r.addProperty("reach", relative.reach());
            o.add("relative", r);
        }
        if (water != null) {
            JsonObject w = new JsonObject();
            w.addProperty("kind", water.kind());
            if (water.min() != null) w.addProperty("min", water.min());
            if (water.max() != null) w.addProperty("max", water.max());
            o.add("water", w);
        }
        if (cliff != null) {
            JsonObject c = new JsonObject();
            if (cliff.min() != null) c.addProperty("min", cliff.min());
            if (cliff.max() != null) c.addProperty("max", cliff.max());
            c.addProperty("rise", cliff.rise());
            o.add("cliff", c);
        }
        if (!shelter.isEmpty()) o.add("shelter", array(shelter));
        if (!biomes.isEmpty()) o.add("biomes", array(biomes));
        return o;
    }

    public static SiteFilter fromJson(JsonObject o) {
        return read("site", o);
    }

    private static JsonObject kindOnly(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        return o;
    }

    private static Distance distance(String where, JsonObject o, String kind, int rise) {
        Integer min = optInt(where, o, "min", 0);
        Integer max = optInt(where, o, "max", 0);
        if (min == null && max == null) throw new IllegalArgumentException(where + " needs min or max");
        if (min != null && max != null && min > max) throw new IllegalArgumentException(where + ": min is above max");
        if ((min != null && min > MAX_SEARCH) || (max != null && max > MAX_SEARCH)) {
            throw new IllegalArgumentException(where + ": distances are at most " + MAX_SEARCH + " blocks");
        }
        return new Distance(kind, min, max, rise);
    }

    private static void only(String where, JsonObject o, Set<String> keys) {
        for (String k : o.keySet()) if (!keys.contains(k)) throw new IllegalArgumentException(where + "." + k + " is not a field (" + keys + ")");
    }

    private static JsonObject obj(String where, JsonObject o, String key) {
        if (!o.get(key).isJsonObject()) throw new IllegalArgumentException(where + "." + key + " must be an object");
        return o.getAsJsonObject(key);
    }

    private static String string(String where, JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw new IllegalArgumentException(where + "." + key + " must be a string");
        return o.get(key).getAsString();
    }

    private static Integer optInt(String where, JsonObject o, String key, int min) {
        if (!o.has(key)) return null;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(where + "." + key + " must be a number");
        double v = e.getAsDouble();
        if (v != Math.rint(v)) throw new IllegalArgumentException(where + "." + key + " must be a whole number");
        if (v < min) throw new IllegalArgumentException(where + "." + key + " must be at least " + min);
        return (int) v;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(v -> a.add(new JsonPrimitive(v)));
        return a;
    }
}
