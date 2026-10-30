package com.customdimensions.compiler.terrain;

import com.customdimensions.compiler.datapack.VanillaData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.cache;
import static com.customdimensions.compiler.terrain.Df.max;
import static com.customdimensions.compiler.terrain.Df.min;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.ref;
import static com.customdimensions.compiler.terrain.Df.unary;

/**
 * Compiles a {@link TerrainSpec} into density functions under
 * {@code <namespace>:terrain/<name>/} and a noise router wired from them.
 *
 * <p>The terrain replaces the three numbers vanilla's overworld shapes its
 * land with ({@code offset}, {@code factor}, {@code jaggedness}) and its
 * {@code sloped_cheese}; caves, aquifers, ore veins and the slides stay
 * vanilla's. {@code offset} is {@code height / 128 - 1}, so vanilla's depth
 * gradient puts the surface at {@code height}. The router's continentalness,
 * erosion, ridges, temperature and vegetation are the preset's own, stretched
 * by {@code scale}, so biomes follow the terrain. Every id a zone blends
 * between is a file: {@code final_density}, {@code depth},
 * {@code chunk_surface_level}, {@code preliminary_surface_level}.
 */
public final class TerrainLibrary {

    /** Presets a terrain can replace the land of: one surface between the slides, no ceiling. */
    public static final Set<String> PRESETS = Set.of("overworld", "large_biomes", "amplified", "floating_islands");
    /** Why the other presets keep their own terrain. */
    public static final Map<String, String> REFUSED = Map.of(
            "caves", "its terrain is a cave system under a solid ceiling, not a surface",
            "nether", "its terrain is a cavern between a floor and a ceiling, not a surface",
            "end", "the End biome source reads the router's erosion as its island shape, which a terrain replaces");

    /** Router entries taken from the preset and stretched by {@code scale}. */
    static final List<String> CLIMATE = List.of("continents", "erosion", "ridges", "temperature", "vegetation");
    /** Vanilla function names whose ids a copied vanilla function refers to, and ours replace. */
    static final List<String> REPLACED = List.of("continents", "erosion", "ridges", "ridges_folded", "temperature",
            "vegetation", "offset", "factor", "jaggedness", "depth", "sloped_cheese", "final_density",
            "preliminary_surface_level", "chunk_surface_level");
    static final List<String> VANILLA_PREFIXES = List.of("minecraft:overworld", "minecraft:overworld_amplified",
            "minecraft:overworld_large_biomes");
    /** The End's bottom-slide density: open air at the bottom of the world. */
    static final double OPEN_FLOOR = -0.234375;
    static final String FLOOR_SLIDE_TOP = "-40";

    /** Noise settings with the terrain's router, and every file the terrain adds by pack-relative path. */
    public record Compiled(JsonObject settings, Map<String, JsonObject> files) {
    }

    private TerrainLibrary() {
    }

    /**
     * {@code settings} (a copy of the preset's vanilla noise settings) with the
     * terrain wired in. {@code name} is the terrain's id under
     * {@code <namespace>:terrain/}; {@code seaLevel} the definition's, or null.
     */
    public static Compiled compile(String slug, String namespace, String name, TerrainSpec spec, String preset,
                                   JsonObject settings, Integer seaLevel, VanillaData vanilla) {
        if (!PRESETS.contains(preset)) {
            throw new IllegalArgumentException(slug + ": terrain.base " + spec.family().id() + " cannot shape preset " + preset
                    + ": " + REFUSED.getOrDefault(preset, "it is not a surface preset"));
        }
        String prefix = namespace + ":terrain/" + name;
        Map<String, JsonObject> functions = new LinkedHashMap<>();
        JsonObject router = settings.getAsJsonObject("noise_router");
        JsonObject overworldRouter = vanilla.noiseSettings("overworld").getAsJsonObject("noise_router");
        for (String key : CLIMATE) {
            JsonElement source = router.get(key);
            if (!source.isJsonPrimitive() || !source.getAsJsonPrimitive().isString()) {
                source = overworldRouter.get(key);
            }
            functions.put(key, scaled(vanilla.densityFunction(path(source.getAsString())), spec.scale()));
        }
        Map<String, String> ids = ids(prefix);
        functions.put("ridges_folded", replace(vanilla.densityFunction("overworld/ridges_folded"), ids));

        int minY = settings.getAsJsonObject("noise").get("min_y").getAsInt();
        int maxY = minY + settings.getAsJsonObject("noise").get("height").getAsInt();
        int presetSea = settings.get("sea_level").getAsInt();
        double sea = seaLevel != null ? seaLevel : presetSea > minY ? presetSea : 63;
        Ctx ctx = new Ctx(spec, namespace, prefix, sea, minY, maxY);
        TerrainFamily.Shape shape = spec.family().build(ctx);
        JsonElement height = shape.height();
        if (!spec.family().airborne() && spec.heightMultiplier() != 1) {
            height = add(mul(add(height, -sea), spec.heightMultiplier()), sea);
        }
        ctx.namedFunctions().forEach((key, f) -> functions.put(key, f.getAsJsonObject()));
        functions.put("height", cache(height));
        functions.put("offset", cache(add(mul(ref(prefix + "/height"), 1 / 128.0), -1)));
        functions.put("factor", cache(shape.factor()));
        functions.put("jaggedness", cache(shape.jaggedness()));
        functions.put("depth", replace(vanilla.densityFunction("overworld/depth"), ids));
        functions.put("sloped_cheese", slopedCheese(prefix, shape, spec.scale()));
        JsonObject finalDensity = replace(vanilla.densityFunction("overworld/final_density"), ids);
        JsonObject preliminary = replace(vanilla.densityFunction("overworld/preliminary_surface_level"), ids);
        if (spec.family().airborne()) {
            openFloor(finalDensity);
            openFloor(preliminary);
        }
        functions.put("final_density", finalDensity);
        functions.put("preliminary_surface_level", preliminary);
        functions.put("chunk_surface_level", replace(vanilla.densityFunction("overworld/chunk_surface_level"), ids));

        JsonObject out = replace(settings, ids);
        JsonObject outRouter = out.getAsJsonObject("noise_router");
        outRouter.add("continents", shape.continents());
        for (String key : List.of("erosion", "ridges", "temperature", "vegetation", "depth", "final_density",
                "chunk_surface_level")) {
            outRouter.addProperty(key, prefix + "/" + key);
        }
        if (spec.family().airborne()) {
            out.remove("aquifers");
            out.addProperty("sea_level", minY);
        }
        out.remove("debug_functions");

        Map<String, JsonObject> files = new TreeMap<>();
        functions.forEach((key, json) -> files.put(dataPath(prefix + "/" + key, "worldgen/density_function"), json));
        for (String noise : ctx.noises()) {
            files.put(dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
        }
        return new Compiled(out, files);
    }

    /** Vanilla's overworld-family function ids to the terrain's. */
    static Map<String, String> ids(String prefix) {
        Map<String, String> ids = new TreeMap<>();
        for (String vanillaPrefix : VANILLA_PREFIXES) {
            for (String n : REPLACED) {
                ids.put(vanillaPrefix + "/" + n, prefix + "/" + n);
            }
        }
        return ids;
    }

    /**
     * {@code 4 * quarter_negative((depth + jaggedness * half_negative(jagged)) * factor)}
     * plus vanilla's 3D noise (weighed by the shape's), as vanilla's overworld writes it; then the
     * terrain's cut (a minimum) and addition (a maximum).
     */
    private static JsonObject slopedCheese(String prefix, TerrainFamily.Shape shape, double scale) {
        JsonElement jagged = unary("half_negative", Df.noise("minecraft:jagged", 1500 / scale, 0, false));
        JsonElement depth = add(ref(prefix + "/depth"), mul(ref(prefix + "/jaggedness"), jagged));
        JsonElement surface = mul(unary("quarter_negative", mul(depth, ref(prefix + "/factor"))), 4);
        JsonElement noise3d = shape.noise3d() == 1 ? ref("minecraft:overworld/base_3d_noise")
                : mul(ref("minecraft:overworld/base_3d_noise"), shape.noise3d());
        if (shape.noiseWeight() != null) {
            noise3d = mul(noise3d, shape.noiseWeight());
        }
        JsonElement density = add(surface, noise3d);
        if (shape.cut() != null) {
            density = min(density, shape.cut());
        }
        if (shape.plus() != null) {
            density = max(density, shape.plus());
        }
        return cache(density);
    }

    /** Sets the bottom slide (the lerp over y -64..-40) to open air, as the End's is. */
    private static void openFloor(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && "minecraft:lerp".equals(o.get("type").getAsString()) && o.get("alpha").isJsonObject()) {
                JsonObject alpha = o.getAsJsonObject("alpha");
                if (alpha.has("axis") && "y".equals(alpha.get("axis").getAsString())
                        && FLOOR_SLIDE_TOP.equals(alpha.get("to_coordinate").getAsString())) {
                    o.addProperty("first", OPEN_FLOOR);
                }
            }
            o.entrySet().forEach(kv -> openFloor(kv.getValue()));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(TerrainLibrary::openFloor);
        }
    }

    /** A copy with every {@code minecraft:noise}'s {@code xz_scale} divided by {@code scale}. */
    static JsonObject scaled(JsonObject function, double scale) {
        JsonObject copy = function.deepCopy();
        if (scale != 1) {
            stretch(copy, scale);
        }
        return copy;
    }

    private static void stretch(JsonElement e, double scale) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && "minecraft:noise".equals(o.get("type").getAsString())) {
                o.addProperty("xz_scale", o.get("xz_scale").getAsDouble() / scale);
            }
            o.entrySet().forEach(kv -> stretch(kv.getValue(), scale));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> stretch(v, scale));
        }
    }

    static JsonObject replace(JsonObject json, Map<String, String> ids) {
        return replaceIds(json, ids).getAsJsonObject();
    }

    private static JsonElement replaceIds(JsonElement e, Map<String, String> ids) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && ids.containsKey(e.getAsString())) {
            return new JsonPrimitive(ids.get(e.getAsString()));
        }
        if (e.isJsonObject()) {
            JsonObject out = new JsonObject();
            e.getAsJsonObject().entrySet().forEach(kv -> out.add(kv.getKey(), replaceIds(kv.getValue(), ids)));
            return out;
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(v -> out.add(replaceIds(v, ids)));
            return out;
        }
        return e.deepCopy();
    }

    private static String path(String id) {
        if (!id.startsWith("minecraft:")) {
            throw new IllegalStateException("a vanilla router entry outside minecraft: " + id);
        }
        return id.substring("minecraft:".length());
    }

    static String dataPath(String id, String folder) {
        int colon = id.indexOf(':');
        return "data/" + id.substring(0, colon) + "/" + folder + "/" + id.substring(colon + 1) + ".json";
    }

    /** One row per terrain for docs and the report: name, purpose, parameters with range and default. */
    public static List<TerrainFamily> families() {
        return List.of(TerrainFamily.values());
    }

    /** The schema fragment for {@code terrain}: one {@code $defs} entry per terrain's parameters. */
    public static JsonObject schema() {
        JsonObject defs = new JsonObject();
        for (TerrainFamily f : TerrainFamily.values()) {
            JsonObject props = new JsonObject();
            for (TerrainParam p : f.params()) {
                props.add(p.name(), numberSchema(p));
            }
            JsonObject params = new JsonObject();
            params.addProperty("description", f.purpose());
            params.addProperty("type", "object");
            params.addProperty("additionalProperties", false);
            params.add("properties", props);
            defs.add("terrain_" + f.id(), params);
        }
        return defs;
    }

    static JsonObject numberSchema(TerrainParam p) {
        JsonObject o = new JsonObject();
        o.addProperty("description", p.description());
        o.addProperty("type", "number");
        o.add("minimum", number(p.min()));
        o.add("maximum", number(p.max()));
        o.add("default", number(p.defaultValue()));
        return o;
    }

    private static JsonPrimitive number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? new JsonPrimitive((long) v) : new JsonPrimitive(v);
    }
}
