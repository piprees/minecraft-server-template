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
 * noise on top of {@code scale}, {@code layout}, a shape added to
 * continentalness so the bordered area composes as one place, and
 * {@code landShare}, the share of the player border that is land.
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
     * (degrees, 0 east, 90 south). With {@code warp}, the point the shape is read at moves along the warp noises
     * {@code climateWarp} uses, two standard deviations of {@code warp.blocks} over features {@code warp.wavelength}
     * across, so its edges wander; at most {@link PlaceField#MAX_WARP_RATIO} of the wavelength, the map does not fold
     * and a ring stays one ring.
     */
    public record Layout(String type, double centreX, double centreZ, double radius, double direction, double falloff,
                         double strength, PlaceField.Warp warp) {
        public Layout(String type, double centreX, double centreZ, double radius, double direction, double falloff, double strength) {
            this(type, centreX, centreZ, radius, direction, falloff, strength, PlaceField.Warp.NONE);
        }

        /** Whether the shape's sampling point moves. */
        public boolean warped() {
            return warp.blocks() > 0;
        }
    }

    /**
     * The router climate's own changes: {@code temperature} and {@code vegetation} added to the router's values (a
     * shift, not a clamp: a biome whose climate box lies past the shifted range takes less of the map or none, and
     * the box at the far end takes what lies beyond it), and its sampling point moved by up to {@code warp} blocks
     * (two standard deviations) over features {@code wavelength} blocks across, so every climate boundary between
     * biomes wanders.
     */
    public record Climate(double temperature, double vegetation, double warp, double wavelength) {
        public static final Climate NONE = new Climate(0, 0, 0, 512);

        public boolean isNone() {
            return equals(NONE);
        }
    }

    public static final TerrainParam SHIFT = new TerrainParam("climateShift", -1, 1, 0,
            "Added to the router's temperature or vegetation; vanilla's biome bands are about 0.3 to 0.55 wide");
    public static final TerrainParam WARP = new TerrainParam("blocks", 0, 1024, 0,
            "Blocks the climate's sampling point moves by, two standard deviations; every biome boundary wanders by about as much");
    public static final TerrainParam WAVELENGTH = new TerrainParam("wavelength", 64, 4096, 512,
            "Blocks across the warp's features: a boundary bends about once a wavelength");
    public static final List<String> SHIFT_KEYS = List.of("temperature", "vegetation");
    public static final List<String> WARP_KEYS = List.of("blocks", "wavelength");

    public static final List<String> SCALE_KEYS = List.of("continents", "erosion", "ridges", "climate");
    public static final List<String> LAYOUT_TYPES = List.of("radial", "ring", "linear", "none");
    static final Set<String> LAYOUT_KEYS = Set.of("type", "centre", "radius", "direction", "falloff", "strength", "warp");
    public static final TerrainParam SCALE_RANGE = new TerrainParam("scales", 0.25, 4, 1,
            "Horizontal stretch of one router noise, multiplied with scale; 2 is twice as broad");
    public static final TerrainParam STRENGTH = new TerrainParam("strength", 0, 1, 0.5,
            "How far the layout moves continentalness: 1 adds the whole shape (-1 to 1), 0 none");
    public static final TerrainParam FALLOFF = new TerrainParam("falloff", 16, 8192, 256,
            "Blocks over which the shape goes from land (1) to sea (-1)");
    public static final TerrainParam RADIUS = new TerrainParam("radius", 0, 8192, 512,
            "Blocks from the centre to where a radial shape starts to fall, or to a ring's crest");

    public static final TerrainParam LAND_SHARE = new TerrainParam("landShare", 0, 1, 0.5,
            "Share of the player border that is land, measured from each seed's continentalness when the level loads");
    /** The density function type that shifts continentalness to a land share at load. */
    public static final String LAND_SHARE_TYPE = "customdimensions:land_share";

    private final double scale;
    private final Scales scales;
    private final Layout layout;
    private final Double landShare;
    private final Climate climate;

    private TerrainShaping(double scale, Scales scales, Layout layout, Double landShare, Climate climate) {
        this.scale = scale;
        this.scales = scales;
        this.layout = layout;
        this.landShare = landShare;
        this.climate = climate;
    }

    public static final TerrainShaping NONE = new TerrainShaping(1, Scales.NONE, null, null, Climate.NONE);

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

    /** The share of the border that is land, or null for the noise's own. */
    public Double landShare() {
        return landShare;
    }

    /** The climate's shift and warp; {@link Climate#NONE} for neither. */
    public Climate climate() {
        return climate;
    }

    /** Whether the shaping changes nothing: every stretch 1, no layout, no land share and no climate shift or warp. */
    public boolean isNone() {
        return scale == 1 && scales.equals(Scales.NONE) && layout == null && landShare == null && climate.isNone();
    }

    /**
     * {@code continents} shifted at load so {@link #landShare} of the border ({@code radius} round the origin)
     * lies at or above {@code coast}; unchanged without a land share.
     */
    public JsonElement withLandShare(JsonElement continents, double coast, int radius) {
        if (landShare == null) return continents;
        JsonObject f = new JsonObject();
        f.addProperty("type", LAND_SHARE_TYPE);
        f.add("argument", continents);
        f.addProperty("share", landShare);
        f.addProperty("coast", coast);
        f.addProperty("radius", radius);
        return f;
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
        Double landShare = terrain.has("landShare") ? number(slug, "terrain.landShare", terrain.get("landShare"), LAND_SHARE) : null;
        double[] shift = object(slug, terrain, "climateShift", SHIFT_KEYS, new double[]{0, 0}, new TerrainParam[]{SHIFT, SHIFT});
        double[] warp = object(slug, terrain, "climateWarp", WARP_KEYS, new double[]{0, WAVELENGTH.defaultValue()},
                new TerrainParam[]{WARP, WAVELENGTH});
        return new TerrainShaping(scale, scales, layout, landShare, new Climate(shift[0], shift[1], warp[0], warp[1]));
    }

    /** The numbers of {@code terrain.<field>}, an object of {@code keys} each in its range; {@code defaults} for absent ones. */
    private static double[] object(String slug, JsonObject terrain, String field, List<String> keys, double[] defaults,
                                   TerrainParam[] ranges) {
        return object(slug, terrain, field, "terrain." + field, keys, defaults, ranges);
    }

    /** As above for {@code parent.<field>}, named {@code name} in refusals. */
    private static double[] object(String slug, JsonObject parent, String field, String name, List<String> keys, double[] defaults,
                                   TerrainParam[] ranges) {
        double[] v = defaults.clone();
        if (!parent.has(field)) return v;
        JsonElement e = parent.get(field);
        if (!e.isJsonObject()) throw error(slug, name + " must be an object");
        for (var kv : e.getAsJsonObject().entrySet()) {
            int i = keys.indexOf(kv.getKey());
            if (i < 0) throw error(slug, name + "." + kv.getKey() + " is not a field; fields: " + String.join(", ", keys));
            v[i] = number(slug, name + "." + kv.getKey(), kv.getValue(), ranges[i]);
        }
        return v;
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
        PlaceField.Warp warp = PlaceField.Warp.NONE;
        if (o.has("warp")) {
            double[] w = object(slug, o, "warp", "terrain.layout.warp", WARP_KEYS, new double[]{0, WAVELENGTH.defaultValue()},
                    new TerrainParam[]{WARP, WAVELENGTH});
            if (w[0] > PlaceField.MAX_WARP_RATIO * w[1]) {
                throw error(slug, "terrain.layout.warp moves " + TerrainSpec.fmt(w[0]) + " blocks over a " + TerrainSpec.fmt(w[1])
                        + "-block wavelength; above " + PlaceField.MAX_WARP_RATIO + " of the wavelength the warp can fold the map and split the "
                        + type + "'s shape");
            }
            warp = new PlaceField.Warp(w[0], w[1]);
        }
        return new Layout(type, cx, cz, radius, direction, falloff, strength, warp);
    }

    /** {@code continents} plus the layout's shape times its strength; unchanged without a layout. Warp noises are {@code namespace}'s. */
    JsonElement withLayout(JsonElement continents, String namespace) {
        if (layout == null) return continents;
        return add(continents, mul(shape(layout, namespace), layout.strength()));
    }

    /** Whether the layout or the climate moves a sampling point along the warp noises. */
    public boolean warps() {
        return climate.warp() > 0 || layout != null && layout.warped();
    }

    /** The layout's shape: -1 to 1 over the plane, read at its warped point; the warp noises are {@code namespace}'s. */
    static JsonElement shape(Layout l, String namespace) {
        return switch (l.type()) {
            case "radial" -> spline(distance(l, namespace), l.radius(), 1, l.radius() + l.falloff(), -1);
            case "ring" -> spline(distance(l, namespace), l.radius() - l.falloff(), -1, l.radius(), 1, l.radius() + l.falloff(), -1);
            case "linear" -> {
                double a = Math.toRadians(l.direction());
                JsonElement along = add(mul(offset(l, "x", namespace), c(Math.cos(a))), mul(offset(l, "z", namespace), c(Math.sin(a))));
                yield spline(along, -l.falloff() / 2, -1, l.falloff() / 2, 1);
            }
            default -> throw new IllegalArgumentException("layout " + l.type());
        };
    }

    /** Blocks from the layout's centre to its warped point. */
    static JsonElement distance(Layout l, String namespace) {
        if (!l.warped()) return ZoneDensity.distance(Math.round(l.centreX()), Math.round(l.centreZ()));
        return ZoneDensity.distance(offset(l, "x", namespace), offset(l, "z", namespace));
    }

    /** The warped point's offset from the layout's centre along {@code axis}, in blocks. */
    private static JsonElement offset(Layout l, String axis, String namespace) {
        JsonElement g = ZoneDensity.gradient(axis, Math.round("x".equals(axis) ? l.centreX() : l.centreZ()));
        if (!l.warped()) return g;
        double k = l.warp().blocks() / (2 * Noises.sigma("warp_" + axis));
        return add(g, mul(Df.noise(namespace + ":terrain/warp_" + axis, TerrainLibrary.WARP_FEATURE / l.warp().wavelength(), 0, false), k));
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
