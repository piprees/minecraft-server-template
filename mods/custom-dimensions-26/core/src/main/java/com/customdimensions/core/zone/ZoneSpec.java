package com.customdimensions.core.zone;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.definition.BiomeEntry;
import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One entry of a definition's {@code zones}, read and checked: its shape about
 * a resolved centre, its blend and edge jitter, and the layers it replaces.
 * {@code centre: "spawn"} resolves to the definition's {@code spawn} (T31), or
 * to 0, 0 when it has none.
 */
public record ZoneSpec(int index, String shapeName, ZoneMask mask, JsonObject terrain, List<BiomeEntry> biomes,
                       Map<String, String> swap, String aquifers, JsonObject structures) {

    public static final double DEFAULT_BLEND = 32;
    static final Set<String> KEYS = Set.of("shape", "centre", "radius", "inner", "outer", "halfSize", "angles", "points",
            "blend", "edgeJitter", "terrain", "biomes", "swap", "liquids", "structures");
    static final Set<String> SHAPES = Set.of("circle", "ring", "square", "box", "sector", "polygon", "all");

    public ZoneSpec {
        biomes = List.copyOf(biomes);
        swap = Map.copyOf(swap);
    }

    /** Every zone of {@code def}, in order; a bad zone throws naming the slug and its index. */
    public static List<ZoneSpec> all(String slug, Definition def) {
        List<ZoneSpec> out = new ArrayList<>();
        for (int i = 0; i < def.zones().size(); i++) {
            out.add(read(slug, i, def.zones().get(i), def.spawn()));
        }
        return out;
    }

    public static ZoneSpec read(String slug, int index, JsonObject zone, BlockPos spawn) {
        String where = slug + ": zones[" + index + "]";
        try {
            return parse(index, zone, spawn);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
    }

    /** A zone with neither biomes nor a swap leaves biomes to the layers beneath it. */
    public boolean changesBiomes() {
        return !biomes.isEmpty() || !swap.isEmpty();
    }

    /** A swap on a zone covering the whole dimension: it applies to every biome the dimension resolves. */
    public boolean globalSwap() {
        return mask.shape() instanceof ZoneShape.All && biomes.isEmpty() && !swap.isEmpty();
    }

    private static ZoneSpec parse(int index, JsonObject zone, BlockPos spawn) {
        for (String key : zone.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown field " + key);
        }
        String shapeName = zone.has("shape") ? zone.get("shape").getAsString() : "circle";
        if (!SHAPES.contains(shapeName)) {
            throw new IllegalArgumentException("shape " + shapeName + " is not one of " + new java.util.TreeSet<>(SHAPES));
        }
        double[] centre = centre(zone.get("centre"), spawn);
        ZoneShape shape = shape(shapeName, zone);
        double blend = zone.has("blend") ? zone.get("blend").getAsDouble() : DEFAULT_BLEND;
        double jitter = zone.has("edgeJitter") ? zone.get("edgeJitter").getAsDouble() : 0;
        ZoneMask mask = new ZoneMask(centre[0], centre[1], shape, blend, jitter);
        JsonObject terrain = null;
        if (zone.has("terrain") && !zone.get("terrain").isJsonNull()) {
            JsonElement t = zone.get("terrain");
            if (t.isJsonPrimitive()) {
                terrain = new JsonObject();
                terrain.addProperty("base", t.getAsString());
            } else {
                terrain = t.getAsJsonObject().deepCopy();
            }
        }
        List<BiomeEntry> biomes = biomes(zone.get("biomes"));
        Map<String, String> swap = new LinkedHashMap<>();
        if (zone.has("swap")) {
            zone.getAsJsonObject("swap").entrySet().forEach(e -> swap.put(e.getKey(), e.getValue().getAsString()));
        }
        String aquifers = null;
        if (zone.has("liquids")) {
            JsonObject liquids = zone.getAsJsonObject("liquids");
            for (String key : liquids.keySet()) {
                if (!key.equals("aquifers")) throw new IllegalArgumentException("liquids." + key + " is not a zone field; sea level is one per dimension");
            }
            if (liquids.has("aquifers")) {
                aquifers = liquids.get("aquifers").getAsString();
                if (!aquifers.equals("on") && !aquifers.equals("dry")) {
                    throw new IllegalArgumentException("liquids.aquifers " + aquifers + " is not on or dry");
                }
            }
        }
        JsonObject structures = zone.has("structures") ? zone.getAsJsonObject("structures").deepCopy() : null;
        if (shape instanceof ZoneShape.All && (terrain != null || aquifers != null)) {
            throw new IllegalArgumentException("a zone of shape all only swaps or sets biomes; terrain and liquids belong to the definition");
        }
        return new ZoneSpec(index, shapeName, mask, terrain, biomes, swap, aquifers, structures);
    }

    static double[] centre(JsonElement value, BlockPos spawn) {
        if (value == null || value.isJsonNull()) return new double[] {0, 0};
        if (value.isJsonPrimitive()) {
            if (!"spawn".equals(value.getAsString())) {
                throw new IllegalArgumentException("centre must be [x, z] or \"spawn\", got " + value);
            }
            return spawn == null ? new double[] {0, 0} : new double[] {spawn.x(), spawn.z()};
        }
        JsonArray a = value.getAsJsonArray();
        if (a.size() != 2) throw new IllegalArgumentException("centre must be [x, z]");
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    static ZoneShape shape(String name, JsonObject zone) {
        return switch (name) {
            case "circle" -> new ZoneShape.Circle(number(zone, "radius"));
            case "ring" -> new ZoneShape.Ring(number(zone, "inner"), number(zone, "outer"));
            case "square" -> new ZoneShape.Box(number(zone, "radius"), number(zone, "radius"));
            case "box" -> {
                JsonArray half = required(zone, "halfSize").getAsJsonArray();
                if (half.size() != 2) throw new IllegalArgumentException("halfSize must be [x, z]");
                yield new ZoneShape.Box(half.get(0).getAsDouble(), half.get(1).getAsDouble());
            }
            case "sector" -> {
                JsonArray angles = required(zone, "angles").getAsJsonArray();
                if (angles.size() != 2) throw new IllegalArgumentException("angles must be [from, to]");
                double outer = zone.has("outer") ? number(zone, "outer") : number(zone, "radius");
                double inner = zone.has("inner") ? number(zone, "inner") : 0;
                yield new ZoneShape.Sector(inner, outer, angles.get(0).getAsDouble(), angles.get(1).getAsDouble());
            }
            case "polygon" -> {
                List<double[]> points = new ArrayList<>();
                for (JsonElement p : required(zone, "points").getAsJsonArray()) {
                    JsonArray xz = p.getAsJsonArray();
                    if (xz.size() != 2) throw new IllegalArgumentException("polygon points are [x, z] pairs");
                    points.add(new double[] {xz.get(0).getAsDouble(), xz.get(1).getAsDouble()});
                }
                yield new ZoneShape.Polygon(points);
            }
            case "all" -> new ZoneShape.All();
            default -> throw new IllegalArgumentException("shape " + name);
        };
    }

    static List<BiomeEntry> biomes(JsonElement value) {
        List<BiomeEntry> out = new ArrayList<>();
        if (value == null || value.isJsonNull()) return out;
        for (JsonElement e : value.getAsJsonArray()) {
            if (e.isJsonPrimitive()) {
                out.add(new BiomeEntry(e.getAsString(), null));
            } else {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("id")) throw new IllegalArgumentException("biomes entries need an id");
                out.add(new BiomeEntry(o.get("id").getAsString(), o.has("climate") ? o.getAsJsonObject("climate") : null));
            }
        }
        return out;
    }

    private static JsonElement required(JsonObject zone, String key) {
        if (!zone.has(key) || zone.get(key).isJsonNull()) throw new IllegalArgumentException(key + " is required for this shape");
        return zone.get(key);
    }

    private static double number(JsonObject zone, String key) {
        return required(zone, key).getAsDouble();
    }
}
