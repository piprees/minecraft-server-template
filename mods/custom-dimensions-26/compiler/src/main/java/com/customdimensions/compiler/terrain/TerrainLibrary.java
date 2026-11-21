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
import static com.customdimensions.compiler.terrain.Df.type;
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
            "caves", "its terrain is a cave system under a solid ceiling, not a surface; terrain.base roofed shapes it",
            "nether", "its terrain is a cavern between a floor and a ceiling, not a surface; terrain.base roofed shapes it",
            "end", "the End biome source reads the router's erosion as its island shape, which a terrain replaces; terrain.base end shapes it");
    /** Presets whose own terrain takes only {@code terrain.scales.climate} without a {@code base}: they have no land climate to stretch. */
    public static final Set<String> CLIMATE_ONLY = Set.of("nether", "caves", "end");

    /** The preset whose vanilla settings a terrain of {@code family} on {@code preset} starts from. */
    public static String settingsPreset(String preset, TerrainFamily family) {
        if (family.ownRouter()) return preset;
        return PRESETS.contains(preset) ? preset : "overworld";
    }

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
        return compile(slug, namespace, name, spec, preset, settings, seaLevel, vanilla, Biomes.NONE);
    }

    /**
     * What a terrain's router gives its biomes beyond its own climate: {@code place}, the router's
     * continentalness from the terrain's place cue (void, basin water, land; {@link TerrainFamily.Shape#place}),
     * and {@code underside}, the router's depth measured down from the islands' tops, so a biome bound below
     * the top takes their undersides. Each is for an airborne terrain; the End's own islands need neither.
     * On a roofed terrain {@code place} is the floor against the sea ({@link RoofedTerrain#COAST_BLOCKS}) and
     * {@code belowFloor} the blocks under the floor from which each of its below-floor bands starts, ascending.
     */
    public record Biomes(boolean place, boolean underside, java.util.List<Integer> belowFloor) {
        public static final Biomes NONE = new Biomes(false, false);

        public Biomes {
            belowFloor = java.util.List.copyOf(belowFloor);
        }

        public Biomes(boolean place, boolean underside) {
            this(place, underside, java.util.List.of());
        }
    }

    /** Router depth per block below the islands' tops: as vanilla's, 1 per 128 blocks. */
    static final double UNDERSIDE_DEPTH_BLOCKS = 128;

    /** As {@link #compile(String, String, String, TerrainSpec, String, JsonObject, Integer, VanillaData)}, the router feeding {@code biomes}. */
    public static Compiled compile(String slug, String namespace, String name, TerrainSpec spec, String preset,
                                   JsonObject settings, Integer seaLevel, VanillaData vanilla, Biomes biomes) {
        if (spec.family().roofed()) {
            return RoofedTerrain.compile(slug, namespace, name, spec, preset, settings, seaLevel, biomes);
        }
        if (!biomes.belowFloor().isEmpty()) {
            throw new IllegalArgumentException(slug + ": biomeLayout.belowFloor binds a biome under the floor of a roofed terrain, not "
                    + spec.family().id());
        }
        if (spec.family() == TerrainFamily.END) {
            return EndTerrain.compile(slug, namespace, name, spec, preset, settings, true);
        }
        if ((biomes.place() || biomes.underside()) && !spec.airborne()) {
            throw new IllegalArgumentException(slug + ": biomes follow the islands of a terrain over a void; " + spec.family().id()
                    + " lies on the ground, where continentalness and depth already do");
        }
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
            functions.put(key, scaled(vanilla.densityFunction(path(source.getAsString())), spec.shaping().stretch(key)));
        }
        functions.put("continents", layout(functions.get("continents"), spec.shaping(), namespace));
        climate(functions, spec.shaping().climate(), namespace);
        Map<String, String> ids = ids(prefix);
        functions.put("ridges_folded", replace(vanilla.densityFunction("overworld/ridges_folded"), ids));

        int minY = settings.getAsJsonObject("noise").get("min_y").getAsInt();
        int maxY = minY + settings.getAsJsonObject("noise").get("height").getAsInt();
        int presetSea = settings.get("sea_level").getAsInt();
        double sea = seaLevel != null ? seaLevel : presetSea > minY ? presetSea : 63;
        TerrainFamily.checkStack(slug, spec, maxY);
        Ctx ctx = new Ctx(spec, namespace, prefix, sea, minY, maxY);
        TerrainFamily.Shape shape = spec.family().build(ctx);
        JsonElement height = shape.height();
        if (!spec.airborne() && spec.heightMultiplier() != 1) {
            height = add(mul(add(height, -ctx.shore()), spec.heightMultiplier()), ctx.shore());
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
        if (spec.airborne()) {
            openFloor(finalDensity);
            openFloor(preliminary);
        }
        functions.put("final_density", shape.solid() == null ? finalDensity : max(finalDensity, shape.solid()));
        JsonElement probe = pointwise(finalDensity);
        functions.put("surface_top", cache(findTopSurface(shape.solid() == null ? probe : max(probe, shape.solid()),
                ref(prefix + "/height"), minY)));
        functions.put("biome_depth", biomeDepth(replace(vanilla.densityFunction("overworld/depth"), ids), prefix));
        functions.put("preliminary_surface_level", preliminary);
        functions.put("chunk_surface_level", replace(vanilla.densityFunction("overworld/chunk_surface_level"), ids));

        JsonObject out = replace(settings, ids);
        if (shape.aquiferCeiling() != null && out.has("aquifers")) {
            functions.put("aquifer_surface_level", cache(min(ref(prefix + "/preliminary_surface_level"), shape.aquiferCeiling())));
            out.getAsJsonObject("aquifers").addProperty("surface_level", prefix + "/aquifer_surface_level");
        }
        JsonObject outRouter = out.getAsJsonObject("noise_router");
        outRouter.add("continents", shape.continents());
        for (String key : List.of("erosion", "ridges", "temperature", "vegetation", "final_density", "chunk_surface_level")) {
            outRouter.addProperty(key, prefix + "/" + key);
        }
        outRouter.addProperty("depth", prefix + "/biome_depth");
        if (biomes.place() && shape.place() != null) {
            functions.put("place", cache(shape.place()));
            outRouter.addProperty("continents", prefix + "/place");
        }
        if (biomes.underside() && shape.top() != null) {
            functions.put("island_top", cache(shape.top()));
            functions.put("island_depth", cache(mul(add(ref(prefix + "/island_top"), Df.negY(minY, maxY)), 1 / UNDERSIDE_DEPTH_BLOCKS)));
            outRouter.addProperty("depth", prefix + "/island_depth");
        }
        if (spec.airborne()) {
            out.remove("aquifers");
            out.addProperty("sea_level", minY);
        }
        out.remove("debug_functions");

        Map<String, JsonObject> files = new TreeMap<>();
        functions.forEach((key, json) -> files.put(dataPath(prefix + "/" + key, "worldgen/density_function"), json));
        for (String noise : ctx.noises()) {
            files.put(dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
        }
        warpNoises(files, spec.shaping(), namespace);
        return new Compiled(out, files);
    }

    /** The presets whose own terrain {@code scales} and {@code layout} can shape, and the vanilla prefix of each one's functions. */
    public static final Map<String, String> SHAPED_PRESETS = Map.of("overworld", "overworld", "large_biomes", "overworld_large_biomes",
            "amplified", "overworld_amplified");
    /** Vanilla functions a shaped preset copies from its own prefix, reading the stretched climate. */
    static final List<String> PRESET_SHAPE = List.of("offset", "factor", "jaggedness", "depth", "sloped_cheese", "final_density",
            "preliminary_surface_level", "chunk_surface_level");

    /**
     * {@code settings} (a copy of the preset's vanilla noise settings) with the
     * preset's own land read from stretched router noises and the layout:
     * vanilla's functions copied under {@code <namespace>:terrain/<name>/},
     * each referring to the terrain's climate instead of vanilla's.
     */
    public static Compiled shapePreset(String slug, String namespace, String name, TerrainShaping shaping, String preset,
                                       JsonObject settings, VanillaData vanilla) {
        String own = SHAPED_PRESETS.get(preset);
        if (own == null) {
            throw new IllegalArgumentException(slug + ": terrain.scales and terrain.layout shape the presets "
                    + String.join(", ", new java.util.TreeSet<>(SHAPED_PRESETS.keySet())) + " without a terrain.base, not " + preset);
        }
        String prefix = namespace + ":terrain/" + name;
        Map<String, String> ids = ids(prefix);
        Map<String, JsonObject> functions = new LinkedHashMap<>();
        JsonObject router = settings.getAsJsonObject("noise_router");
        for (String key : CLIMATE) {
            functions.put(key, scaled(vanilla.densityFunction(path(router.get(key).getAsString())), shaping.stretch(key)));
        }
        functions.put("continents", layout(functions.get("continents"), shaping, namespace));
        climate(functions, shaping.climate(), namespace);
        functions.put("ridges_folded", replace(vanilla.densityFunction("overworld/ridges_folded"), ids));
        for (String n : PRESET_SHAPE) {
            JsonObject f = replace(vanilla.densityFunction(own + "/" + n), ids);
            functions.put(n, "sloped_cheese".equals(n) ? scaled(f, shaping.scale()) : f);
        }
        functions.put("surface_top", cache(findTopSurface(pointwise(functions.get("final_density")), ref(prefix + "/offset"), settings)));
        functions.put("biome_depth", biomeDepth(functions.get("depth"), prefix));
        JsonObject out = replace(settings, ids);
        out.getAsJsonObject("noise_router").addProperty("depth", prefix + "/biome_depth");
        out.remove("debug_functions");
        Map<String, JsonObject> files = new TreeMap<>();
        functions.forEach((key, json) -> files.put(dataPath(prefix + "/" + key, "worldgen/density_function"), json));
        warpNoises(files, shaping, namespace);
        return new Compiled(out, files);
    }

    /**
     * Refuses a shaping of a {@link #CLIMATE_ONLY} preset's own terrain that
     * sets anything but {@code scales.climate}.
     */
    public static void checkClimateOnly(String slug, String preset, TerrainShaping shaping) {
        TerrainShaping.Scales s = shaping.scales();
        if (shaping.scale() != 1 || s.continents() != 1 || s.erosion() != 1 || s.ridges() != 1 || shaping.layout() != null
                || shaping.landShare() != null) {
            throw new IllegalArgumentException(slug + ": the " + preset + " preset's own terrain takes only terrain.scales.climate, "
                    + "which stretches the climate its biomes are laid out on"
                    + ("end".equals(preset) ? "" : "; terrain.base roofed shapes its floor and ceiling"));
        }
    }

    /** {@link EndTerrain}: an {@code end} terrain on the end preset, on the outer islands alone when {@code centralIsland} is false. */
    public static Compiled compileEnd(String slug, String namespace, String name, TerrainSpec spec, String preset, JsonObject settings,
                                      boolean centralIsland) {
        return EndTerrain.compile(slug, namespace, name, spec, preset, settings, centralIsland);
    }

    /**
     * Router continentalness on a roofed terrain whose biomes follow its sea: at the sea line and at the ends of
     * the shore band, or null when {@code sea} lies over no part of the floor.
     */
    public static double[] roofedCoast(TerrainSpec spec, double sea) {
        return RoofedTerrain.coast(spec, sea);
    }

    /** A roofed terrain's floor, ceiling and what of it lies under the preset's sea, for the compile report. */
    public static String describeRoofed(TerrainSpec spec, double sea, String fluid) {
        return RoofedTerrain.describe(spec, sea, fluid);
    }

    /** The router's temperature and vegetation after a climate stretch: the stretched functions by pack path, and the router rewired. */
    public record Climate(Map<String, JsonObject> files, boolean stretched) {
    }

    /**
     * {@code settings}' router temperature and vegetation, each read from its
     * id or written in place, stretched by {@code stretch} and given {@code climate}'s shift and warp under
     * {@code <namespace>:terrain/<name>/}; constants stay as they are.
     */
    public static Climate stretchClimate(String namespace, String name, JsonObject settings, double stretch,
                                         TerrainShaping.Climate climate, VanillaData vanilla) {
        Map<String, JsonObject> files = new TreeMap<>();
        if (stretch == 1 && climate.isNone()) return new Climate(files, false);
        JsonObject router = settings.getAsJsonObject("noise_router");
        String prefix = namespace + ":terrain/" + name;
        for (String key : List.of("temperature", "vegetation")) {
            JsonElement e = router.get(key);
            JsonObject function;
            if (e.isJsonObject()) {
                function = e.getAsJsonObject();
            } else if (e.getAsJsonPrimitive().isString()) {
                function = vanilla.densityFunction(path(e.getAsString()));
            } else {
                continue;
            }
            files.put(dataPath(prefix + "/" + key, "worldgen/density_function"), climate(scaled(function, stretch), key, climate, namespace));
            router.addProperty(key, prefix + "/" + key);
        }
        boolean changed = !files.isEmpty();
        if (changed) warpNoises(files, climate, namespace);
        return new Climate(files, changed);
    }

    /** The noises a climate warp moves its sampling point along, on x and on z. */
    static final List<String> WARP_NOISES = List.of("warp_x", "warp_z");
    /** Blocks across one feature of a warp noise at {@code xz_scale} 1: its base octave is -8. */
    static final double WARP_FEATURE = 256;

    /** {@code functions}' temperature and vegetation with {@code climate} applied ({@link #climate(JsonObject, String, TerrainShaping.Climate, String)}). */
    private static void climate(Map<String, JsonObject> functions, TerrainShaping.Climate climate, String namespace) {
        if (climate.isNone()) return;
        for (String key : List.of("temperature", "vegetation")) {
            functions.put(key, climate(functions.get(key), key, climate, namespace));
        }
    }

    /**
     * {@code function}, a router temperature or vegetation, with {@code climate}'s shift for {@code key} added and,
     * with a warp, every shifted noise in it sampled at a point moved along the {@link #WARP_NOISES}: two standard
     * deviations of each move {@code warp} blocks, over features {@code wavelength} blocks across. Vanilla's shifted
     * noise samples at {@code block * xz_scale + shift}, so a move of {@code b} blocks is a shift of
     * {@code b * xz_scale}. The warp noises are {@code namespace}'s.
     */
    static JsonObject climate(JsonObject function, String key, TerrainShaping.Climate climate, String namespace) {
        if (climate.isNone()) return function;
        JsonObject out = function.deepCopy();
        if (climate.warp() > 0) warp(out, climate, namespace);
        double shift = "temperature".equals(key) ? climate.temperature() : climate.vegetation();
        return shift == 0 ? out : add(out, shift);
    }

    private static void warp(JsonElement e, TerrainShaping.Climate climate, String namespace) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> warp(v, climate, namespace));
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        o.entrySet().forEach(kv -> warp(kv.getValue(), climate, namespace));
        if (!o.has("type") || !"minecraft:noise".equals(o.get("type").getAsString()) || !o.has("shift_x")) return;
        double k = climate.warp() / (2 * Noises.sigma("warp_x")) * o.get("xz_scale").getAsDouble();
        double frequency = WARP_FEATURE / climate.wavelength();
        o.add("shift_x", add(o.get("shift_x"), mul(Df.noise(namespace + ":terrain/warp_x", frequency, 0, false), k)));
        o.add("shift_z", add(o.get("shift_z"), mul(Df.noise(namespace + ":terrain/warp_z", frequency, 0, false), k)));
    }

    /** {@code files} with the warp noises' files under {@code namespace} when {@code shaping}'s climate or layout warps. */
    private static void warpNoises(Map<String, JsonObject> files, TerrainShaping shaping, String namespace) {
        if (shaping.warps()) warpNoises(files, namespace);
    }

    /** {@code files} with the warp noises' files under {@code namespace} when {@code climate} warps. */
    private static void warpNoises(Map<String, JsonObject> files, TerrainShaping.Climate climate, String namespace) {
        if (climate.warp() > 0) warpNoises(files, namespace);
    }

    private static void warpNoises(Map<String, JsonObject> files, String namespace) {
        for (String noise : WARP_NOISES) {
            files.put(dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
        }
    }

    /** {@link #findTopSurface} below a preset's nominal height, which its {@code offset} gives as {@code height / 128 - 1}. */
    private static JsonObject findTopSurface(JsonElement density, JsonElement offset, JsonObject settings) {
        return findTopSurface(density, mul(add(offset, 1), 128), settings.getAsJsonObject("noise").get("min_y").getAsInt());
    }

    /**
     * {@code files} with the terrain's continentalness ({@code <namespace>:terrain/<name>/continents}) shifted at
     * load so the shaping's land share of a {@code radius} border lies at or above {@code coast}, where the terrain
     * meets its sea; unchanged without a land share.
     */
    public static Map<String, JsonObject> withLandShare(Map<String, JsonObject> files, String namespace, String name,
                                                        TerrainShaping shaping, double coast, int radius) {
        if (shaping == null || shaping.landShare() == null) return files;
        String path = dataPath(namespace + ":terrain/" + name + "/continents", "worldgen/density_function");
        JsonObject continents = files.get(path);
        if (continents == null) throw new IllegalStateException("no continents at " + path);
        Map<String, JsonObject> out = new TreeMap<>(files);
        out.put(path, cache(shaping.withLandShare(continents, coast, radius)));
        return out;
    }

    /** {@code density} held at or below -1 where {@code mask} is 1, and unchanged in sign where it is 0. */
    public static JsonObject voided(JsonElement density, JsonElement mask) {
        return min(density, add(mul(mask, -2), 1));
    }

    /** {@code continents} with the shaping's layout added (cached), or unchanged without one. */
    static JsonObject layout(JsonObject continents, TerrainShaping shaping, String namespace) {
        return shaping.layout() == null ? continents : cache(shaping.withLayout(continents, namespace));
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
    /** Vertical step of the scan for the terrain's top below its nominal height. */
    static final int SURFACE_TOP_STEP = 4;

    /**
     * The highest solid cell of {@code density} at or below {@code upper}, in
     * {@link #SURFACE_TOP_STEP}-block steps; {@code minY} where there is none.
     */
    private static JsonObject findTopSurface(JsonElement density, JsonElement upper, int minY) {
        JsonObject o = type("find_top_surface");
        o.add("density", density);
        o.add("upper_bound", upper);
        o.addProperty("lower_bound", minY);
        o.addProperty("cell_height", SURFACE_TOP_STEP);
        return o;
    }

    /**
     * {@code density} for single points: interpolation and blending give way to
     * their input, structure beards to 0 and noodle caves to solid, so a probe
     * costs one evaluation of the shape and the large caves, not a cell's corners.
     */
    static JsonElement pointwise(JsonElement e) {
        if (e.isJsonPrimitive()) return NOODLES.equals(e.getAsString()) ? new JsonPrimitive(64.0) : e;
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(pointwise(x)));
            return out;
        }
        if (!e.isJsonObject()) return e;
        JsonObject o = e.getAsJsonObject();
        String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        switch (type) {
            case "minecraft:interpolated", "minecraft:blend_density" -> {
                return pointwise(o.get("input"));
            }
            case "minecraft:beardifier" -> {
                return new JsonPrimitive(0.0);
            }
            default -> {
                JsonObject out = new JsonObject();
                o.entrySet().forEach(kv -> out.add(kv.getKey(), pointwise(kv.getValue())));
                return out;
            }
        }
    }

    private static final String NOODLES = "minecraft:overworld/caves/noodle";

    /**
     * The depth biomes read: vanilla's, measured from the terrain's solid top
     * where that lies below its nominal height, so a valley floor, a lake bed
     * or a flooded cave mouth open to the sky is surface, not cave (T210).
     * {@code depth} is vanilla's {@code gradient + offset}; the terrain's own
     * shape keeps reading it.
     */
    private static JsonObject biomeDepth(JsonObject depth, String prefix) {
        if (!"minecraft:add".equals(depth.get("type").getAsString()) || !depth.has("right")) {
            throw new IllegalStateException("overworld/depth is not gradient + offset: " + depth);
        }
        JsonObject out = depth.deepCopy();
        out.add("right", min(depth.get("right"), add(mul(ref(prefix + "/surface_top"), 1 / 128.0), -1)));
        return out;
    }

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
        o.addProperty("type", p.whole() ? "integer" : "number");
        o.add("minimum", number(p.min()));
        o.add("maximum", number(p.max()));
        o.add("default", number(p.defaultValue()));
        return o;
    }

    private static JsonPrimitive number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? new JsonPrimitive((long) v) : new JsonPrimitive(v);
    }
}
