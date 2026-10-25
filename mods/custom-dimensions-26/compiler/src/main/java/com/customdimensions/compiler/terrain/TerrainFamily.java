package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.customdimensions.compiler.terrain.Df.abs;
import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.c;
import static com.customdimensions.compiler.terrain.Df.clamp;
import static com.customdimensions.compiler.terrain.Df.floor;
import static com.customdimensions.compiler.terrain.Df.lerp;
import static com.customdimensions.compiler.terrain.Df.max;
import static com.customdimensions.compiler.terrain.Df.min;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.negY;
import static com.customdimensions.compiler.terrain.Df.ramp;
import static com.customdimensions.compiler.terrain.Df.spline;
import static com.customdimensions.compiler.terrain.Df.sqrt;
import static com.customdimensions.compiler.terrain.Df.square;
import static com.customdimensions.compiler.terrain.Df.sub;

/**
 * The library's terrains. Each one shapes a surface height in blocks
 * ({@link Shape#height}), how closely the ground follows it
 * ({@link Shape#factor}, vanilla's {@code factor}), vanilla's
 * {@code jaggedness}, and optionally a 3D term that removes ground
 * ({@link Shape#cut}) or adds it ({@link Shape#plus}). Every terrain starts
 * from the shared land parameters (continents from the router's
 * continentalness, hills from its erosion) and adds its own.
 */
public enum TerrainFamily {

    CONTINENTS("continents", "Oceans and land masses from continentalness, with erosion hills.",
            List.of(), Map.of(),
            Map.of("relief", 8.0, "landHeight", 72.0, "roughness", 0.3, "coast", -0.3),
            Map.of("relief", 100.0, "landHeight", 130.0, "roughness", 0.75, "coast", 0.0),
            false, ctx -> ctx.land()),

    EROSION("erosion", "Broad worn basins where erosion is high, flat-floored and smooth.",
            List.of(new TerrainParam("basinDepth", 0, 80, 24, "Blocks the most eroded land sinks, never below sea level + 2"),
                    new TerrainParam("basinWidth", 0.05, 1, 0.4, "Share of the erosion range that forms basins")),
            Map.of("relief", 40.0),
            Map.of("basinDepth", 10.0, "basinWidth", 0.25),
            Map.of("basinDepth", 70.0, "basinWidth", 0.7, "relief", 80.0),
            false, TerrainFamily::erosion),

    RIDGES("ridges", "Mountain ranges along the ridges noise, with jagged peaks.",
            List.of(new TerrainParam("peakHeight", 0, 200, 70, "Blocks a range rises above the land"),
                    new TerrainParam("jaggedness", 0, 1.5, 0.35, "Spikiness of the peaks (vanilla's jaggedness)"),
                    new TerrainParam("rangeWidth", 0.05, 1, 0.4, "Share of the ridges range that is mountain")),
            Map.of(),
            Map.of("peakHeight", 30.0, "jaggedness", 0.1, "rangeWidth", 0.3),
            Map.of("peakHeight", 170.0, "jaggedness", 1.2, "rangeWidth", 0.55),
            false, TerrainFamily::ridges),

    TERRACES("terraces", "Land stepped into flat terraces, capped by a plateau height.",
            List.of(new TerrainParam("stepHeight", 3, 48, 10, "Blocks between terrace levels"),
                    new TerrainParam("riser", 0.05, 0.95, 0.35, "Share of each step that is slope rather than flat"),
                    new TerrainParam("plateauHeight", 64, 256, 256, "Height above which land is cut flat")),
            Map.of("relief", 50.0, "roughness", 0.15),
            Map.of("stepHeight", 4.0, "riser", 0.6, "relief", 30.0),
            Map.of("stepHeight", 28.0, "riser", 0.12, "relief", 110.0, "plateauHeight", 180.0),
            false, TerrainFamily::terraces),

    CANYONS("canyons", "Deep meandering cuts through the land, flat-floored and steep-walled.",
            List.of(new TerrainParam("depth", 8, 120, 40, "Blocks a canyon cuts below the land around it"),
                    new TerrainParam("width", 0.01, 0.3, 0.06, "Canyon width, as a share of the channel noise"),
                    new TerrainParam("frequency", 0.25, 4, 1, "How often canyons occur; higher is more and tighter")),
            Map.of("landHeight", 96.0, "roughness", 0.35),
            Map.of("depth", 14.0, "width", 0.04, "relief", 16.0),
            Map.of("depth", 110.0, "width", 0.1, "landHeight", 150.0, "relief", 30.0),
            false, TerrainFamily::canyons),

    RIVERS("rivers", "Rivers cut to just below sea level across the land, with banks.",
            List.of(new TerrainParam("width", 0.01, 0.25, 0.05, "River width, as a share of the channel noise"),
                    new TerrainParam("depth", 1, 16, 4, "Blocks the river bed lies below sea level"),
                    new TerrainParam("bankWidth", 0.01, 0.3, 0.08, "Width of the slope from river to land"),
                    new TerrainParam("frequency", 0.25, 4, 1, "How often rivers occur")),
            Map.of(),
            Map.of("width", 0.025, "depth", 2.0, "bankWidth", 0.04),
            Map.of("width", 0.08, "depth", 12.0, "bankWidth", 0.12, "frequency", 1.4),
            false, TerrainFamily::rivers),

    FLOATING_ISLANDS("floating_islands", "Islands in the sky over a void: domed tops, tapering undersides.",
            List.of(new TerrainParam("altitude", 40, 240, 150, "Height of the island layer"),
                    new TerrainParam("thickness", 4, 64, 18, "Blocks an island's top rises above the layer"),
                    new TerrainParam("underside", 4, 120, 40, "Blocks an island's underside hangs below the layer"),
                    new TerrainParam("coverage", 0.02, 0.8, 0.3, "Share of the sky with islands"),
                    new TerrainParam("size", 0.25, 4, 1, "Island size; higher is larger and fewer"),
                    new TerrainParam("spread", 0, 80, 24, "Blocks island altitudes vary by")),
            Map.of(),
            Map.of("coverage", 0.15, "thickness", 8.0, "underside", 14.0, "spread", 10.0),
            Map.of("coverage", 0.5, "thickness", 40.0, "underside", 100.0, "spread", 60.0, "size", 2.0),
            true, TerrainFamily::floatingIslands),

    ISLANDS("islands", "An ocean of islands and archipelagos.",
            List.of(new TerrainParam("coverage", 0.02, 0.7, 0.2, "Share of the map that is land"),
                    new TerrainParam("size", 0.25, 4, 1, "Island size; higher is larger and fewer"),
                    new TerrainParam("islandHeight", 4, 120, 28, "Blocks an island rises above sea level"),
                    new TerrainParam("archipelago", 0, 1, 0.4, "How much islands break into clusters of smaller ones")),
            Map.of("seaFloor", 30.0),
            Map.of("coverage", 0.35, "islandHeight", 10.0, "archipelago", 0.1),
            Map.of("coverage", 0.12, "islandHeight", 90.0, "archipelago", 0.5, "size", 1.5),
            false, TerrainFamily::islands),

    VOIDS("voids", "Land broken by bottomless voids, each ringed by a raised rim.",
            List.of(new TerrainParam("coverage", 0.02, 0.5, 0.1, "Share of the land that is void"),
                    new TerrainParam("size", 0.25, 4, 1, "Void size; higher is larger and fewer"),
                    new TerrainParam("rimHeight", 0, 60, 14, "Blocks a rim rises above the land"),
                    new TerrainParam("rimWidth", 0.01, 0.3, 0.06, "Rim width, as a share of the void noise")),
            Map.of("coast", -0.6, "seaFloor", 56.0),
            Map.of("coverage", 0.04, "rimHeight", 4.0, "rimWidth", 0.03),
            Map.of("coverage", 0.3, "rimHeight", 50.0, "rimWidth", 0.15, "size", 2.0),
            true, TerrainFamily::voids),

    SHATTERED("shattered", "Land split by 3D cracks, with overhangs and loose floating shards.",
            List.of(new TerrainParam("crackWidth", 0.01, 0.3, 0.13, "Crack width, as a share of the crack noise"),
                    new TerrainParam("crackScale", 0.25, 4, 1, "Crack spacing; higher is wider apart"),
                    new TerrainParam("lift", 0, 1, 0.5, "How far the ground breaks into overhangs and shards")),
            Map.of("relief", 36.0),
            Map.of("crackWidth", 0.04, "lift", 0.15),
            Map.of("crackWidth", 0.2, "lift", 0.9, "crackScale", 2.0, "relief", 60.0),
            false, TerrainFamily::shattered),

    SPIRES("spires", "Tall stone columns rising from the land.",
            List.of(new TerrainParam("spireHeight", 16, 200, 70, "Blocks a spire rises above the land"),
                    new TerrainParam("coverage", 0.005, 0.3, 0.04, "Share of the land under spires"),
                    new TerrainParam("thickness", 0.25, 4, 1, "Spire girth; higher is thicker"),
                    new TerrainParam("taper", 0, 1, 0.5, "0 is straight-sided columns, 1 is cones")),
            Map.of("roughness", 0.4),
            Map.of("spireHeight", 20.0, "coverage", 0.02, "taper", 0.8),
            Map.of("spireHeight", 180.0, "coverage", 0.08, "taper", 0.1, "thickness", 0.7),
            false, TerrainFamily::spires);

    /** The land parameters every terrain shares. */
    public static final List<TerrainParam> SHARED = TerrainParam.SHARED;

    /** The three gallery settings. */
    public static final List<String> SETTINGS = List.of("gentle", "default", "extreme");

    private final String id;
    private final String purpose;
    private final List<TerrainParam> params;
    private final Map<String, Map<String, Double>> presets;
    private final boolean airborne;
    private final Function<Ctx, Shape> build;

    TerrainFamily(String id, String purpose, List<TerrainParam> own, Map<String, Double> sharedDefaults,
                  Map<String, Double> gentle, Map<String, Double> extreme, boolean airborne, Function<Ctx, Shape> build) {
        this.id = id;
        this.purpose = purpose;
        List<TerrainParam> all = new ArrayList<>();
        for (TerrainParam p : TerrainParam.SHARED) {
            all.add(sharedDefaults.containsKey(p.name()) ? p.withDefault(sharedDefaults.get(p.name())) : p);
        }
        all.addAll(own);
        this.params = List.copyOf(all);
        Map<String, Map<String, Double>> p = new LinkedHashMap<>();
        p.put("gentle", gentle);
        p.put("default", Map.of());
        p.put("extreme", extreme);
        this.presets = Collections.unmodifiableMap(p);
        this.airborne = airborne;
        this.build = build;
    }

    public String id() {
        return id;
    }

    public String purpose() {
        return purpose;
    }

    /** Shared land parameters (with this terrain's defaults) then its own. */
    public List<TerrainParam> params() {
        return params;
    }

    public TerrainParam param(String name) {
        return params.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** The overrides of the {@code gentle}, {@code default} (none) and {@code extreme} settings. */
    public Map<String, Map<String, Double>> presets() {
        return presets;
    }

    /**
     * A terrain over a void: no aquifers, no solid floor, and sea level at the
     * bottom of the world unless the definition sets one.
     */
    public boolean airborne() {
        return airborne;
    }

    public static TerrainFamily byId(String id) {
        for (TerrainFamily f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }

    Shape build(Ctx ctx) {
        return build.apply(ctx);
    }

    // ---- shapes ---------------------------------------------------------------------------------

    /**
     * What a terrain contributes: surface height in blocks, vanilla's factor and
     * jaggedness, a 3D cut and a 3D addition (null: none), the router's
     * continentalness as biomes should read it, and the weight of vanilla's
     * 3D noise.
     */
    record Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
                 JsonElement continents, double noise3d) {

        Shape withHeight(JsonElement h) {
            return new Shape(h, factor, jaggedness, cut, plus, continents, noise3d);
        }

        Shape withFactor(JsonElement f) {
            return new Shape(height, f, jaggedness, cut, plus, continents, noise3d);
        }
    }

    private static Shape erosion(Ctx ctx) {
        Shape land = ctx.land();
        double width = ctx.p("basinWidth");
        JsonElement worn = mul(Df.ramp(ctx.erosion(), 0.35 - width, 0.35), ctx.landMask());
        JsonElement sunk = sub(land.height(), mul(worn, ctx.p("basinDepth")));
        JsonElement floorLevel = min(land.height(), c(ctx.sea() + 2));
        return land.withHeight(max(sunk, floorLevel))
                .withFactor(lerp(worn, land.factor(), mul(land.factor(), 2.5)));
    }

    private static Shape ridges(Ctx ctx) {
        Shape land = ctx.land();
        double w = ctx.p("rangeWidth");
        JsonElement onRidge = ramp(ctx.peaksValleys(), 1 - 2 * w, 1);
        JsonElement inland = ramp(ctx.continents(), ctx.p("coast") + 0.05, ctx.p("coast") + 0.3);
        JsonElement rugged = spline(ctx.erosion(), -1, 1, -0.2, 0.9, 0.3, 0.25, 1, 0.1);
        JsonElement mountain = Df.cache(mul(mul(onRidge, inland), rugged));
        JsonElement h = add(land.height(), mul(square(mountain), ctx.p("peakHeight")));
        return new Shape(h, land.factor(), mul(mountain, ctx.p("jaggedness")), null, null, land.continents(), 1);
    }

    private static Shape terraces(Ctx ctx) {
        Shape land = ctx.land();
        double step = ctx.p("stepHeight");
        double riser = ctx.p("riser");
        JsonElement above = mul(add(land.height(), -ctx.sea()), 1 / step);
        JsonElement level = floor(above);
        JsonElement frac = sub(above, level);
        JsonElement rise = spline(frac, 0, 0, 1 - riser, 0, 1, 1);
        JsonElement stepped = add(mul(add(level, rise), step), ctx.sea());
        return land.withHeight(min(stepped, c(ctx.p("plateauHeight"))));
    }

    private static Shape canyons(Ctx ctx) {
        Shape land = ctx.land();
        double w = ctx.p("width");
        JsonElement channel = abs(ctx.noise("gorge", ctx.p("frequency"), 0));
        JsonElement profile = spline(channel, 0, 1, w * 0.6, 1, w, 0.15, w * 1.6, 0);
        JsonElement onLand = ramp(ctx.continents(), ctx.p("coast"), ctx.p("coast") + 0.15);
        return land.withHeight(sub(land.height(), mul(mul(profile, onLand), ctx.p("depth"))));
    }

    private static Shape rivers(Ctx ctx) {
        Shape land = ctx.land();
        double w = ctx.p("width");
        JsonElement channel = abs(ctx.noise("channel", ctx.p("frequency"), 0));
        JsonElement profile = spline(channel, 0, 1, w, 1, w + ctx.p("bankWidth"), 0);
        JsonElement bed = lerp(profile, land.height(), c(ctx.sea() - ctx.p("depth")));
        return land.withHeight(min(land.height(), bed));
    }

    private static Shape floatingIslands(Ctx ctx) {
        double m = ctx.heightMultiplier();
        double sigma = Noises.sigma("islands");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        JsonElement n = ctx.noise("islands", 1 / ctx.p("size"), 0);
        JsonElement core = Df.cache(ramp(n, t, t + sigma));
        JsonElement layer = add(mul(ctx.noise("altitude", 1, 0), ctx.p("spread") * m / Noises.sigma("altitude")), ctx.p("altitude"));
        JsonElement top = add(add(layer, mul(sqrt(core), ctx.p("thickness") * m)), -2);
        JsonElement bottom = add(sub(layer, mul(core, ctx.p("underside") * m)), 2);
        JsonElement y = negY(ctx.minY(), ctx.maxY());
        JsonElement inside = min(add(top, y), sub(c(0), add(bottom, y)));
        JsonElement plus = mul(inside, 1 / 8.0);
        Shape shape = new Shape(c(ctx.minY() - 64.0), ctx.factor(), c(0), null, plus, c(0.3), 0.1 + 0.6 * ctx.p("roughness"));
        return shape;
    }

    private static Shape islands(Ctx ctx) {
        double a = ctx.p("archipelago");
        double big = Noises.sigma("islands");
        double fine = Noises.sigma("detail");
        JsonElement n = add(mul(ctx.noise("islands", 1 / ctx.p("size"), 0), (1 - a) / big),
                mul(ctx.noise("detail", 1 / ctx.p("size"), 0), a / fine));
        double sigma = Math.sqrt((1 - a) * (1 - a) + a * a);
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        double s = sigma;
        double sea = ctx.sea();
        double peak = ctx.p("islandHeight");
        JsonElement nc = Df.cache(clamp(n, -4, 4));
        JsonElement h = spline(nc, -4.5, ctx.p("seaFloor") - 10, t - 0.6 * s, ctx.p("seaFloor"), t - 0.06 * s, sea - 3,
                t, sea + 1, t + 0.3 * s, sea + peak * 0.4, t + 1.2 * s, sea + peak, 4.5, sea + peak * 1.1);
        JsonElement hills = mul(mul(ramp(nc, t, t + 0.5 * s), ctx.hills()), ctx.p("relief"));
        JsonElement continents = spline(nc, -4.5, -0.8, t - 0.6 * s, -0.6, t - 0.2 * s, -0.3, t, -0.15,
                t + 0.4 * s, 0.05, t + 1.2 * s, 0.4, 4.5, 0.6);
        return new Shape(add(h, hills), ctx.factor(), c(0), null, null, continents, 1);
    }

    private static Shape voids(Ctx ctx) {
        Shape land = ctx.land();
        double sigma = Noises.sigma("voids");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        double rim = ctx.p("rimWidth") * sigma / 0.3;
        JsonElement v = Df.cache(ctx.noise("voids", 1 / ctx.p("size"), 0));
        JsonElement bump = spline(v, -4, 0, t - 2 * rim, 0, t - 0.3 * rim, 1, t, 1);
        JsonElement h = add(land.height(), mul(mul(bump, ctx.landMask()), ctx.p("rimHeight")));
        JsonElement cut = mul(sub(c(t), v), 40 / sigma);
        return new Shape(h, land.factor(), land.jaggedness(), cut, null, land.continents(), land.noise3d());
    }

    private static Shape shattered(Ctx ctx) {
        Shape land = ctx.land();
        double sigma = Noises.sigma("shatter");
        double lift = ctx.p("lift");
        JsonElement crack = abs(ctx.noise("shatter", 1 / ctx.p("crackScale"), 1 / ctx.p("crackScale")));
        JsonElement cut = mul(add(crack, -ctx.p("crackWidth") * sigma / 0.3), 12 / sigma);
        JsonElement factor = lerp(c(lift), land.factor(), c(1.2));
        return new Shape(land.height(), factor, land.jaggedness(), cut, null, land.continents(), 1 + 2.5 * lift);
    }

    private static Shape spires(Ctx ctx) {
        Shape land = ctx.land();
        double sigma = Noises.sigma("spires");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        JsonElement s = ctx.noise("spires", 1 / ctx.p("thickness"), 0);
        JsonElement a = Df.cache(mul(ramp(s, t, t + 0.6 * sigma), ramp(ctx.continents(), ctx.p("coast"), ctx.p("coast") + 0.1)));
        JsonElement column = clamp(mul(a, 6), 0, 1);
        JsonElement profile = lerp(c(ctx.p("taper")), column, a);
        JsonElement h = add(land.height(), mul(profile, ctx.p("spireHeight")));
        JsonElement factor = lerp(column, land.factor(), c(24));
        return new Shape(h, factor, land.jaggedness(), null, null, land.continents(), land.noise3d());
    }
}
