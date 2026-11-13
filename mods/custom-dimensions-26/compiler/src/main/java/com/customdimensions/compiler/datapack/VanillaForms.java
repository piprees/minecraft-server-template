package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.compiler.terrain.TerrainShaping;
import com.customdimensions.compiler.zone.ZoneDensity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Keeps every file a vanilla server reads free of our types (RB-18). A file
 * vanilla loads that names one of them holds its vanilla form instead, and the
 * file as compiled moves to {@code data/customdimensions/runtime_registry/<ns>/<registry>/<path>.json},
 * a folder vanilla ignores; the runtime binds it in place of the vanilla form
 * when the server starts, before any level exists. The vanilla forms:
 * {@code land_share} is its unshifted {@code argument}, {@code zoned} its {@code base}
 * biome source, {@code checker_cells} 0 (no void cells), {@code zone_distance}
 * {@link ZoneDensity#REACH} (beyond every edge). A type of ours without a vanilla
 * form in a file vanilla loads is refused, with the file and the type.
 */
public final class VanillaForms {

    /** The folder, under the runtime's namespace, that holds the compiled forms of files vanilla loads. */
    public static final String FOLDER = "runtime_registry";

    /** Our types by id, each to its vanilla form. */
    static final Map<String, Function<JsonObject, JsonElement>> FORMS = Map.of(
            TerrainShaping.LAND_SHARE_TYPE, o -> o.get("argument"),
            ZonesCompiler.SOURCE_TYPE, o -> o.get("base"),
            DatapackCompiler.CHECKER_CELLS, o -> new JsonPrimitive(0),
            ZoneDensity.DISTANCE_TYPE, o -> new JsonPrimitive(ZoneDensity.REACH));

    /** Folders under the runtime's namespace that vanilla does not read. */
    static final Set<String> MOD_ONLY_FOLDERS = Set.of(DatapackCompiler.LAZY_FOLDER, RuntimeWorldgen.FOLDER,
            StructuresCompiler.FOLDER, FOLDER);

    private static final String OURS = DatapackCompiler.RUNTIME_NAMESPACE + ":";
    private static final String CACHE = "minecraft:cache";

    private VanillaForms() {
    }

    /** True for a pack file a vanilla server reads: a JSON file under {@code data/} outside the runtime's own folders. */
    public static boolean vanillaVisible(String path) {
        if (!path.startsWith("data/") || !path.endsWith(".json")) return false;
        for (String folder : MOD_ONLY_FOLDERS) {
            if (path.startsWith("data/" + DatapackCompiler.RUNTIME_NAMESPACE + "/" + folder + "/")) return false;
        }
        return true;
    }

    /** True for a path under {@code data/customdimensions/runtime_registry/}. */
    public static boolean isRuntimePath(String path) {
        return path.startsWith("data/" + DatapackCompiler.RUNTIME_NAMESPACE + "/" + FOLDER + "/");
    }

    /** {@code data/<ns>/<rest>} to {@code data/customdimensions/runtime_registry/<ns>/<rest>}. */
    public static String runtimePath(String path) {
        return "data/" + DatapackCompiler.RUNTIME_NAMESPACE + "/" + FOLDER + "/" + path.substring("data/".length());
    }

    /**
     * {@code files} with each vanilla-visible file that names a type of ours in its vanilla form, and the file
     * as compiled at its {@link #runtimePath}; {@code moved} receives each such path and its runtime path.
     */
    static SortedMap<String, String> split(SortedMap<String, String> files, Map<String, String> moved) {
        SortedMap<String, String> out = new TreeMap<>(files);
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!vanillaVisible(e.getKey())) continue;
            JsonElement json = JsonParser.parseString(e.getValue());
            if (types(json).isEmpty()) continue;
            JsonElement vanilla = vanillaForm(json);
            refuse(e.getKey(), vanilla);
            out.put(e.getKey(), JsonWriter.write(vanilla));
            String runtime = runtimePath(e.getKey());
            String existing = out.putIfAbsent(runtime, e.getValue());
            if (existing != null && !existing.equals(e.getValue())) {
                throw new IllegalStateException(runtime + " is emitted twice with different content");
            }
            moved.put(e.getKey(), runtime);
        }
        return out;
    }

    /** Refuses the pack when a vanilla-visible file names a type of ours. */
    static void check(Map<String, String> files) {
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (vanillaVisible(e.getKey())) refuse(e.getKey(), JsonParser.parseString(e.getValue()));
        }
    }

    private static void refuse(String path, JsonElement json) {
        List<String> types = types(json);
        if (!types.isEmpty()) {
            throw new IllegalStateException(path + " names `" + types.getFirst() + "`, which a vanilla server cannot load, and "
                    + "that type has no vanilla form; the pack would load on no vanilla server (RB-18)");
        }
    }

    /** The {@code type} values in our namespace anywhere in {@code json}, in document order. */
    static List<String> types(JsonElement json) {
        List<String> out = new ArrayList<>();
        collect(json, out);
        return out;
    }

    private static void collect(JsonElement json, List<String> out) {
        if (json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            JsonElement type = o.get("type");
            if (type != null && type.isJsonPrimitive() && type.getAsString().startsWith(OURS)) out.add(type.getAsString());
            o.entrySet().forEach(e -> collect(e.getValue(), out));
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(e -> collect(e, out));
        }
    }

    /** {@code json} with each of our types replaced by its vanilla form, and a cache of a cache folded to one. */
    static JsonElement vanillaForm(JsonElement json) {
        if (json.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            json.getAsJsonArray().forEach(e -> out.add(vanillaForm(e)));
            return out;
        }
        if (!json.isJsonObject()) return json;
        JsonObject o = json.getAsJsonObject();
        JsonElement type = o.get("type");
        if (type != null && type.isJsonPrimitive() && FORMS.containsKey(type.getAsString())) {
            return vanillaForm(FORMS.get(type.getAsString()).apply(o));
        }
        JsonObject out = new JsonObject();
        o.entrySet().forEach(e -> out.add(e.getKey(), vanillaForm(e.getValue())));
        if (isCache(out) && isCache(out.get("input"))) return out.get("input");
        return out;
    }

    private static boolean isCache(JsonElement e) {
        if (!e.isJsonObject()) return false;
        JsonObject o = e.getAsJsonObject();
        return o.size() == 2 && o.has("input") && o.get("type") instanceof JsonPrimitive t && CACHE.equals(t.getAsString());
    }
}
