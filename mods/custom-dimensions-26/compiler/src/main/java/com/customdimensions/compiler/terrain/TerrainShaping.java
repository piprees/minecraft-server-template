package com.customdimensions.compiler.terrain;

import com.customdimensions.compiler.zone.ZoneDensity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.c;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.spline;

/**
 * The large-scale controls of a definition's {@code terrain}, with or without
 * a library {@code base}: {@code scales}, a horizontal stretch per router
 * noise on top of {@code scale}, and {@code layout}, a shape added to
 * continentalness so the bordered area composes as one place.
 */
public final class TerrainShaping {

    /** Per-noise stretches; each multiplies {@code scale} for its router entries. */
    public record Scales(double continents, double erosion, double ridges, double climate) {
        public static final Scales NONE = new Scales(1, 1, 1, 1);

        /** The stretch of a router entry ({@link TerrainLibrary#CLIMATE}). */
        public double of(String routerKey) {
            return switch (routerKey) {
                case "continents" -> continents;
                case "erosion" -> erosion;
                case "ridges" -> ridges;
                case "temperature", "vegetation" -> climate;
                default -> throw new IllegalArgumentException("no scale for " + routerKey);
            };
        }
    }

    /**
     * A shape in continentalness units, -1 to 1, times {@code strength}, added
     * to the router's continentalness: {@code radial} is 1 within
     * {@code radius} of the centre, falling to -1 over {@code falloff};
     * {@code ring} is 1 at {@code radius}, -1 {@code falloff} inside and
     * outside it; {@code linear} rises from -1 to 1 across a band
     * {@code falloff} wide through the centre, towards {@code direction}
     * (degrees, 0 east, 90 south).
     */
    public record Layout(String type, double centreX, double centreZ, double radius, double direction, double falloff,
                         double strength) {
    }

    public static final List<String> SCALE_KEYS = List.of("continents", "erosion", "ridges", "climate");
    public static final List<String> LAYOUT_TYPES = List.of("radial", "ring", "linear", "none");
    static final Set<String> LAYOUT_KEYS = Set.of("type", "centre", "radius", "direction", "falloff", "strength");
    public static final TerrainParam SCALE_RANGE = new TerrainParam("scales", 0.25, 4, 1,
            "Horizontal stretch of one router noise, multiplied with scale; 2 is twice as broad");
    public static final TerrainParam STRENGTH = new TerrainParam("strength", 0, 1, 0.5,
            "How far the layout moves continentalness: 1 adds the whole shape (-1 to 1), 0 none");
    public static final TerrainParam FALLOFF = new TerrainParam("falloff", 16, 8192, 256,
            "Blocks over which the shape goes from land (1) to sea (-1)");
    public static final TerrainParam RADIUS = new TerrainParam("radius", 0, 8192, 512,
            "Blocks from the centre to where a radial shape starts to fall, or to a ring's crest");

    private final double scale;
    private final Scales scales;
    private final Layout layout;

    private TerrainShaping(double scale, Scales scales, Layout layout) {
        this.scale = scale;
        this.scales = scales;
        this.layout = layout;
    }

    public static final TerrainShaping NONE = new TerrainShaping(1, Scales.NONE, null);

    public double scale() {
        return scale;
    }

    public Scales scales() {
        return scales;
    }

    /** The layout, or null for none. */
    public Layout layout() {
        return layout;
    }

    /** Whether the shaping changes nothing: every stretch 1 and no layout. */
    public boolean isNone() {
        return scale == 1 && scales.equals(Scales.NONE) && layout == null;
    }

    /** The stretch of a router entry: {@code scale} times its {@code scales} entry. */
    public double stretch(String routerKey) {
        return scale * scales.of(routerKey);
    }

    /** {@code scale}, {@code scales} and {@code layout} of a {@code terrain} object; absent ones change nothing. */
    static TerrainShaping parse(String slug, JsonObject terrain, double scale) {
        Scales scales = Scales.NONE;
        if (terrain.has("scales")) {
            JsonElement e = terrain.get("scales");
            if (!e.isJsonObject()) throw error(slug, "terrain.scales must be an object");
            double[] v = {1, 1, 1, 1};
            for (var kv : e.getAsJsonObject().entrySet()) {
                int i = SCALE_KEYS.indexOf(kv.getKey());
                if (i < 0) {
                    throw error(slug, "terrain.scales." + kv.getKey() + " is not a scale; scales: " + String.join(", ", SCALE_KEYS));
                }
                v[i] = number(slug, "terrain.scales." + kv.getKey(), kv.getValue(), SCALE_RANGE);
            }
            scales = new Scales(v[0], v[1], v[2], v[3]);
        }
        Layout layout = null;
        if (terrain.has("layout")) {
            layout = layout(slug, terrain.get("layout"));
        }
        return new TerrainShaping(scale, scales, layout);
    }

    private static Layout layout(String slug, JsonElement e) {
        if (!e.isJsonObject()) throw error(slug, "terrain.layout must be an object");
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!LAYOUT_KEYS.contains(key)) {
                throw error(slug, "terrain.layout." + key + " is not a layout field; fields: " + String.join(", ", new TreeSet<>(LAYOUT_KEYS)));
            }
        }
        if (!o.has("type") || !o.get("type").isJsonPrimitive() || !LAYOUT_TYPES.contains(o.get("type").getAsString())) {
            throw error(slug, "terrain.layout.type must be one of " + String.join(", ", LAYOUT_TYPES));
        }
        String type = o.get("type").getAsString();
        if ("none".equals(type)) return null;
        double cx = 0, cz = 0;
        if (o.has("centre")) {
            JsonElement c = o.get("centre");
            if (!c.isJsonArray() || c.getAsJsonArray().size() != 2 || !c.getAsJsonArray().get(0).isJsonPrimitive()
                    || !c.getAsJsonArray().get(1).isJsonPrimitive()) {
                throw error(slug, "terrain.layout.centre must be [x, z]");
            }
            cx = c.getAsJsonArray().get(0).getAsDouble();
            cz = c.getAsJsonArray().get(1).getAsDouble();
            if (Math.abs(cx) > 30_000_000 || Math.abs(cz) > 30_000_000) throw error(slug, "terrain.layout.centre is outside the world");
        }
        boolean linear = "linear".equals(type);
        if (linear && o.has("radius")) throw error(slug, "terrain.layout.radius is for radial and ring; linear takes direction");
        if (!linear && o.has("direction")) throw error(slug, "terrain.layout.direction is for linear; " + type + " takes radius");
        if (!linear && !o.has("radius")) throw error(slug, "terrain.layout.radius is required for " + type);
        double radius = o.has("radius") ? number(slug, "terrain.layout.radius", o.get("radius"), RADIUS) : 0;
        double direction = 0;
        if (o.has("direction")) {
            JsonElement d = o.get("direction");
            if (!d.isJsonPrimitive() || !d.getAsJsonPrimitive().isNumber()) throw error(slug, "terrain.layout.direction must be a number");
            direction = d.getAsDouble();
        }
        double falloff = o.has("falloff") ? number(slug, "terrain.layout.falloff", o.get("falloff"), FALLOFF) : FALLOFF.defaultValue();
        double strength = o.has("strength") ? number(slug, "terrain.layout.strength", o.get("strength"), STRENGTH) : STRENGTH.defaultValue();
        return new Layout(type, cx, cz, radius, direction, falloff, strength);
    }

    /** {@code continents} plus the layout's shape times its strength; unchanged without a layout. */
    JsonElement withLayout(JsonElement continents) {
        if (layout == null) return continents;
        return add(continents, mul(shape(layout), layout.strength()));
    }

    /** The layout's shape: -1 to 1 over the plane. */
    static JsonElement shape(Layout l) {
        long cx = Math.round(l.centreX()), cz = Math.round(l.centreZ());
        return switch (l.type()) {
            case "radial" -> spline(ZoneDensity.distance(cx, cz), l.radius(), 1, l.radius() + l.falloff(), -1);
            case "ring" -> spline(ZoneDensity.distance(cx, cz), l.radius() - l.falloff(), -1, l.radius(), 1, l.radius() + l.falloff(), -1);
            case "linear" -> {
                double a = Math.toRadians(l.direction());
                JsonElement along = add(mul(ZoneDensity.gradient("x", cx), c(Math.cos(a))), mul(ZoneDensity.gradient("z", cz), c(Math.sin(a))));
                yield spline(along, -l.falloff() / 2, -1, l.falloff() / 2, 1);
            }
            default -> throw new IllegalArgumentException("layout " + l.type());
        };
    }

    static double number(String slug, String field, JsonElement e, TerrainParam range) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw error(slug, field + " must be a number, got " + e);
        }
        double v = e.getAsDouble();
        if (!(v >= range.min() && v <= range.max())) {
            throw error(slug, field + " = " + e + " is outside [" + TerrainSpec.fmt(range.min()) + ", " + TerrainSpec.fmt(range.max()) + "]");
        }
        return v;
    }

    private static IllegalArgumentException error(String slug, String message) {
        return new IllegalArgumentException(slug + ": " + message);
    }
}
