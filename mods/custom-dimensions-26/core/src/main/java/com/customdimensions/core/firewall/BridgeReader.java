package com.customdimensions.core.firewall;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Reads and writes bridge files ({@code schema/bridge.schema.json}). */
public final class BridgeReader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final Set<String> FIELDS = Set.of("$schema", "mod", "reviewed", "structureSets", "structures", "features", "spawns", "mixins", "registries");
    private static final Set<String> STRUCTURE_FIELDS = Set.of("id", "set", "size", "mood", "climate", "depth");

    private BridgeReader() {}

    /** Every {@code *.json} in {@code dir} by mod id; an absent folder holds none. */
    public static Map<String, Bridge> readAll(Path dir) {
        Map<String, Bridge> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Bridge bridge = read(file);
                String expected = file.getFileName().toString().replaceFirst("\\.json$", "");
                if (!bridge.mod().equals(expected)) throw new BridgeFormatException(file, "mod \"" + bridge.mod() + "\" does not match the file name " + expected + ".json");
                out.put(bridge.mod(), bridge);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public static Bridge read(Path file) {
        try {
            return parse(Files.readString(file), file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Bridge parse(String text, Path file) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new BridgeFormatException(file, "not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new BridgeFormatException(file, "not a JSON object");
        JsonObject o = root.getAsJsonObject();
        unknown(file, o, FIELDS, "");
        String mod = string(file, o, "mod", "");
        if (!mod.matches("[a-z0-9_.-]+")) throw new BridgeFormatException(file, "mod \"" + mod + "\" is not a mod id");
        boolean reviewed = o.has("reviewed") && bool(file, o.get("reviewed"), "reviewed");
        List<Bridge.Structure> structures = new ArrayList<>();
        JsonArray array = array(file, o, "structures");
        for (int i = 0; i < array.size(); i++) structures.add(structure(file, array.get(i), "structures[" + i + "]"));
        return new Bridge(mod, reviewed, ids(file, o, "structureSets"), List.copyOf(structures), ids(file, o, "features"),
                ids(file, o, "spawns"), strings(file, o, "mixins"), ids(file, o, "registries"));
    }

    private static Bridge.Structure structure(Path file, JsonElement e, String where) {
        if (!e.isJsonObject()) throw new BridgeFormatException(file, where + " is not an object");
        JsonObject o = e.getAsJsonObject();
        unknown(file, o, STRUCTURE_FIELDS, where + ".");
        String id = string(file, o, "id", where + ".");
        id(file, id, where + ".id");
        String set = o.has("set") ? string(file, o, "set", where + ".") : null;
        if (set != null) id(file, set, where + ".set");
        Bridge.Size size = Bridge.Size.UNMEASURED;
        if (o.has("size") && !o.get("size").isJsonNull()) {
            if (!o.get("size").isJsonObject()) throw new BridgeFormatException(file, where + ".size is not an object");
            JsonObject s = o.getAsJsonObject("size");
            size = new Bridge.Size(slot(file, s, "x", where), slot(file, s, "y", where), slot(file, s, "z", where));
        }
        return new Bridge.Structure(id, set, size, tags(file, o, "mood", Bridge.MOODS, where), tags(file, o, "climate", Bridge.CLIMATES, where),
                tags(file, o, "depth", Bridge.DEPTHS, where));
    }

    private static Integer slot(Path file, JsonObject size, String axis, String where) {
        JsonElement v = size.get(axis);
        if (v == null || v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsDouble() != Math.floor(v.getAsDouble()) || v.getAsInt() < 1) {
            throw new BridgeFormatException(file, where + ".size." + axis + " is not a positive whole number of blocks");
        }
        return v.getAsInt();
    }

    private static List<String> tags(Path file, JsonObject o, String key, Set<String> allowed, String where) {
        List<String> tags = strings(file, o, key);
        for (String tag : tags) {
            if (!allowed.contains(tag)) throw new BridgeFormatException(file, where + "." + key + " \"" + tag + "\" is not one of " + new java.util.TreeSet<>(allowed));
        }
        return tags;
    }

    private static List<String> ids(Path file, JsonObject o, String key) {
        List<String> ids = strings(file, o, key);
        ids.forEach(id -> id(file, id, key));
        return ids;
    }

    private static void id(Path file, String id, String where) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new BridgeFormatException(file, where + " \"" + id + "\" is not a namespaced id");
    }

    private static List<String> strings(Path file, JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonArray array = array(file, o, key);
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new BridgeFormatException(file, key + " holds a non-string");
            out.add(e.getAsString());
        }
        return List.copyOf(out);
    }

    private static JsonArray array(Path file, JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return new JsonArray();
        if (!e.isJsonArray()) throw new BridgeFormatException(file, key + " is not an array");
        return e.getAsJsonArray();
    }

    private static String string(Path file, JsonObject o, String key, String where) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new BridgeFormatException(file, where + key + " is missing or not a string");
        return e.getAsString();
    }

    private static boolean bool(Path file, JsonElement e, String key) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new BridgeFormatException(file, key + " is not true or false");
        return e.getAsBoolean();
    }

    private static void unknown(Path file, JsonObject o, Set<String> fields, String where) {
        for (String key : o.keySet()) {
            if (!fields.contains(key)) throw new BridgeFormatException(file, "unknown field " + where + key);
        }
    }

    /** The bridge as its file holds it: every field written, empty lists and null size slots included. */
    public static String write(Bridge bridge) {
        JsonObject o = new JsonObject();
        o.addProperty("mod", bridge.mod());
        o.addProperty("reviewed", bridge.reviewed());
        o.add("structureSets", GSON.toJsonTree(bridge.structureSets()));
        JsonArray structures = new JsonArray();
        for (Bridge.Structure s : bridge.structures()) {
            JsonObject so = new JsonObject();
            so.addProperty("id", s.id());
            if (s.set() != null) so.addProperty("set", s.set());
            JsonObject size = new JsonObject();
            size.add("x", s.size().x() == null ? JsonNull.INSTANCE : GSON.toJsonTree(s.size().x()));
            size.add("y", s.size().y() == null ? JsonNull.INSTANCE : GSON.toJsonTree(s.size().y()));
            size.add("z", s.size().z() == null ? JsonNull.INSTANCE : GSON.toJsonTree(s.size().z()));
            so.add("size", size);
            so.add("mood", GSON.toJsonTree(s.mood()));
            so.add("climate", GSON.toJsonTree(s.climate()));
            so.add("depth", GSON.toJsonTree(s.depth()));
            structures.add(so);
        }
        o.add("structures", structures);
        o.add("features", GSON.toJsonTree(bridge.features()));
        o.add("spawns", GSON.toJsonTree(bridge.spawns()));
        o.add("mixins", GSON.toJsonTree(bridge.mixins()));
        o.add("registries", GSON.toJsonTree(bridge.registries()));
        return GSON.toJson(o) + "\n";
    }

    /** A bridge file that does not follow the format, with the file in the message. */
    public static final class BridgeFormatException extends IllegalArgumentException {
        BridgeFormatException(Path file, String message) {
            super((file == null ? "bridge" : file.getFileName().toString()) + ": " + message);
        }
    }
}
