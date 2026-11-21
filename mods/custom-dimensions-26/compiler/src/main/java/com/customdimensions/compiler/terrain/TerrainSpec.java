package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A definition's {@code terrain} object, checked against the library: the
 * terrain, its parameters (defaults, then the named {@code setting}, then
 * {@code params}), the horizontal {@code scale} and vertical
 * {@code heightMultiplier}, and the large-scale {@link TerrainShaping}
 * ({@code scales}, {@code layout}).
 */
public record TerrainSpec(TerrainFamily family, String setting, Map<String, Double> params, double scale,
                          double heightMultiplier, TerrainShaping shaping, String floor) {

    public static final TerrainParam SCALE = new TerrainParam("scale", 0.25, 4, 1,
            "Horizontal stretch of every terrain noise and of the biome climate; 2 is twice as broad. scales multiply it per router noise");
    public static final TerrainParam HEIGHT_MULTIPLIER = new TerrainParam("heightMultiplier", 0.25, 3, 1,
            "Multiplies every height above or below sea level");
    static final Set<String> KEYS = Set.of("base", "setting", "params", "scale", "heightMultiplier", "scales", "layout", "floor", "landShare",
            "climateShift", "climateWarp");
    /** What a {@code spires} terrain's columns stand on: the land, a void, a sea floor, or islands of their own over a void. */
    public static final List<String> FLOORS = List.of("land", "void", "sea", "islands");
    public static final String LAND_FLOOR = "land";
    /** Fields that shape a preset's own terrain, without a {@code base}. */
    static final Set<String> PRESET_KEYS = Set.of("base", "scale", "scales", "layout", "landShare", "climateShift", "climateWarp");

    /** The terrain a definition asks for, or null when it keeps the preset's own ({@code base} absent or null). */
    public static TerrainSpec parse(String slug, JsonObject terrain) {
        if (terrain == null || !terrain.has("base") || terrain.get("base").isJsonNull()) {
            presetShaping(slug, terrain);
            return null;
        }
        for (String key : terrain.keySet()) {
            if (!KEYS.contains(key)) {
                throw error(slug, "terrain." + key + " is not a terrain field; fields: " + String.join(", ", List.copyOf(new java.util.TreeSet<>(KEYS))));
            }
        }
        JsonElement baseEl = terrain.get("base");
        String base = baseEl.isJsonPrimitive() && baseEl.getAsJsonPrimitive().isString() ? baseEl.getAsString() : null;
        TerrainFamily family = base == null ? null : TerrainFamily.byId(base);
        if (family == null) {
            throw error(slug, "terrain.base " + baseEl + " is not a library terrain; one of "
                    + java.util.Arrays.stream(TerrainFamily.values()).map(TerrainFamily::id).collect(Collectors.joining(", ")));
        }
        String setting = "default";
        if (terrain.has("setting")) {
            setting = terrain.get("setting").getAsString();
            if (!TerrainFamily.SETTINGS.contains(setting)) {
                throw error(slug, "terrain.setting \"" + setting + "\" is not one of " + String.join(", ", TerrainFamily.SETTINGS));
            }
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (TerrainParam p : family.params()) {
            values.put(p.name(), p.defaultValue());
        }
        values.putAll(family.presets().get(setting));
        if (terrain.has("params")) {
            if (!terrain.get("params").isJsonObject()) {
                throw error(slug, "terrain.params must be an object");
            }
            for (Map.Entry<String, JsonElement> e : terrain.getAsJsonObject("params").entrySet()) {
                TerrainParam p = family.param(e.getKey());
                if (p == null) {
                    throw error(slug, "terrain.params." + e.getKey() + " is not a parameter of " + family.id() + "; parameters: "
                            + family.params().stream().map(TerrainParam::name).collect(Collectors.joining(", ")));
                }
                values.put(p.name(), number(slug, "terrain.params." + p.name(), e.getValue(), p));
            }
        }
        double scale = terrain.has("scale") ? number(slug, "terrain.scale", terrain.get("scale"), SCALE) : 1;
        double height = terrain.has("heightMultiplier")
                ? number(slug, "terrain.heightMultiplier", terrain.get("heightMultiplier"), HEIGHT_MULTIPLIER) : 1;
        String floor = LAND_FLOOR;
        if (terrain.has("floor")) {
            JsonElement f = terrain.get("floor");
            if (family != TerrainFamily.SPIRES) {
                throw error(slug, "terrain.floor is for spires, which stand on land, a void, a sea floor or islands; " + family.id()
                        + " has its own ground");
            }
            if (!f.isJsonPrimitive() || !f.getAsJsonPrimitive().isString() || !FLOORS.contains(f.getAsString())) {
                throw error(slug, "terrain.floor must be one of " + String.join(", ", FLOORS));
            }
            floor = f.getAsString();
        }
        return new TerrainSpec(family, setting, Map.copyOf(values), scale, height, TerrainShaping.parse(slug, terrain, scale), floor);
    }

    /**
     * The shaping of a preset's own terrain ({@code base} absent or null): its
     * {@code scale}, {@code scales} and {@code layout}; null when there is no
     * {@code terrain}, a {@code base} is set, or the shaping changes nothing.
     */
    public static TerrainShaping presetShaping(String slug, JsonObject terrain) {
        if (terrain == null || terrain.has("base") && !terrain.get("base").isJsonNull()) {
            return null;
        }
        for (String key : terrain.keySet()) {
            if (!PRESET_KEYS.contains(key)) {
                throw error(slug, "terrain." + key + " needs a terrain.base");
            }
        }
        double scale = terrain.has("scale") ? number(slug, "terrain.scale", terrain.get("scale"), SCALE) : 1;
        TerrainShaping shaping = TerrainShaping.parse(slug, terrain, scale);
        return shaping.isNone() ? null : shaping;
    }

    private static double number(String slug, String field, JsonElement e, TerrainParam range) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw error(slug, field + " must be a number, got " + e);
        }
        double v = e.getAsDouble();
        if (!(v >= range.min() && v <= range.max())) {
            throw error(slug, field + " = " + e + " is outside [" + fmt(range.min()) + ", " + fmt(range.max()) + "]");
        }
        if (range.whole() && v != Math.rint(v)) {
            throw error(slug, field + " = " + e + " must be a whole number");
        }
        return v;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static IllegalArgumentException error(String slug, String message) {
        return new IllegalArgumentException(slug + ": " + message);
    }

    /** Whether the terrain hangs over a void: an airborne family, or spires standing on a void or on islands of their own. */
    public boolean airborne() {
        return family.airborne() || "void".equals(floor) || "islands".equals(floor);
    }

    /** Whether a sea at {@code sea} lies below the deepest ocean basin, so it holds no water and the land builds from the sea floor. */
    public boolean seaUnderLand(double sea) {
        Double floor = params.get("seaFloor");
        return floor != null && sea < floor - Ctx.BASIN_DEPTH;
    }

    public double param(String name) {
        Double v = params.get(name);
        if (v == null) {
            throw new IllegalArgumentException(family.id() + " has no parameter " + name);
        }
        return v;
    }
}
