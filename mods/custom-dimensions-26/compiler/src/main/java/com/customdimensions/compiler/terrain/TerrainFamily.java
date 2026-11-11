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
            List.of(new TerrainParam("crust", 0, 48, 0, "Blocks under the surface that no noise cave opens into, so caves never break the ground; 0 lets them")),
            Map.of(),
            Map.of("relief", 8.0, "landHeight", 72.0, "roughness", 0.3, "coast", -0.3),
            Map.of("relief", 100.0, "landHeight", 130.0, "roughness", 0.75, "coast", 0.0),
            false, ctx -> crust(ctx, ctx.land())),

    EROSION("erosion", "Land worn down to flat basin floors at landHeight, with the hills that remain between them.",
            List.of(new TerrainParam("basinShare", 0.02, 0.9, 0.4, "Share of the land worn down to the flat basin floor"),
                    new TerrainParam("hillHeight", 0, 160, 40, "Blocks the remaining hills rise above the basin floor"),
                    new TerrainParam("steepness", 0, 1, 0.55, "0 is soft rolling hills, 1 is cliff-sided flat-topped mesas")),
            Map.of("landHeight", 70.0, "relief", 0.0, "roughness", 0.3),
            Map.of("basinShare", 0.1, "hillHeight", 16.0, "steepness", 0.0, "relief", 8.0),
            Map.of("basinShare", 0.7, "hillHeight", 80.0, "steepness", 0.95),
            false, TerrainFamily::erosion),

    RIDGES("ridges", "Mountain ranges along the ridges noise, with jagged peaks.",
            List.of(new TerrainParam("peakHeight", 0, 240, 70, "Blocks a range rises above the land"),
                    new TerrainParam("jaggedness", 0, 1.5, 0.35, "Spikiness of the peaks (vanilla's jaggedness)"),
                    new TerrainParam("rangeWidth", 0.05, 1, 0.4, "Share of the ridges range that is mountain"),
                    new TerrainParam("crest", 0.25, 1, 1, "Profile of a range: 1 rises evenly from foot to crest; lower lifts the flanks so the range stands tall from a steep foot"),
                    new TerrainParam("erosionFalloff", 0, 1, 1, "How far high erosion lowers the ranges: 1 to a quarter, 0 not at all"),
                    new TerrainParam("web", 0, 1, 0, "Where ranges run: 0 on vanilla's peaks, rings round the weirdness noise's extremes; 1 along its zero line, one web across the map")),
            Map.of(),
            Map.of("peakHeight", 45.0, "jaggedness", 0.15, "rangeWidth", 0.35),
            Map.of("peakHeight", 150.0, "jaggedness", 1.2, "rangeWidth", 0.55),
            false, TerrainFamily::ridges),

    TERRACES("terraces", "Land stepped into flat terraces, capped by a plateau height.",
            List.of(new TerrainParam("stepHeight", 3, 48, 8, "Blocks between terrace levels"),
                    new TerrainParam("riser", 0.05, 0.95, 0.2, "Share of each step that is slope rather than flat"),
                    new TerrainParam("plateauHeight", 64, 256, 256, "Height above which land is cut flat")),
            Map.of("relief", 72.0, "landHeight", 96.0, "roughness", 0.15),
            Map.of("stepHeight", 4.0, "riser", 0.5, "relief", 40.0),
            Map.of("stepHeight", 24.0, "riser", 0.1, "relief", 110.0, "plateauHeight", 190.0),
            false, TerrainFamily::terraces),

    CANYONS("canyons", "Dry steep-walled canyons whose flat floors step down in falls toward sea level, like old river beds.",
            List.of(new TerrainParam("depth", 8, 120, 24, "Blocks the floor lies below the land at a canyon's upper reaches"),
                    new TerrainParam("width", 0.01, 0.3, 0.06, "Canyon width, as a share of the channel noise"),
                    new TerrainParam("frequency", 0.25, 4, 1, "How often canyons occur; higher is more and tighter"),
                    new TerrainParam("grade", 0, 1, 1, "How far floors descend from their upper reaches toward sea level; 0 keeps every floor depth below the land"),
                    new TerrainParam("cascadeStep", 2, 32, 8, "Blocks between floor levels; each drop is a dry fall"),
                    new TerrainParam("pools", 0, 0.3, 0.05, "Share of canyon floor sunk 4 blocks into hollows, which hold water at sea level")),
            Map.of("landHeight", 112.0, "roughness", 0.35),
            Map.of("depth", 14.0, "width", 0.04, "relief", 16.0, "landHeight", 96.0, "grade", 0.25, "cascadeStep", 4.0),
            Map.of("depth", 36.0, "width", 0.1, "landHeight", 170.0, "relief", 30.0, "cascadeStep", 14.0),
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

    FLOATING_ISLANDS("floating_islands", "Islands in the sky over a void: domed or flat-topped, tapering undersides, in one layer or stacked tiers.",
            join(List.of(new TerrainParam("altitude", 40, 240, 150, "Height of the island layer"),
                    new TerrainParam("thickness", 4, 64, 18, "Blocks an island's top rises above the layer"),
                    new TerrainParam("underside", 4, 120, 40, "Blocks an island's underside hangs below the layer"),
                    new TerrainParam("coverage", 0.02, 0.8, 0.3, "Share of the sky with islands"),
                    new TerrainParam("size", 0.25, 4, 1, "Island size; higher is larger and fewer"),
                    new TerrainParam("spread", 0, 80, 24, "Blocks island altitudes vary by"),
                    new TerrainParam("flatness", 0, 1, 0, "Shape of an island's top: 0 a dome, 1 a flat table that rolls off over a short edge"),
                    count("terraces", 0, 5, 0, "Plateau levels a flat top breaks into, each terraceStep above the last; 0 or 1 is one level"),
                    new TerrainParam("terraceStep", 2, 32, 6, "Blocks between plateau levels")), TerrainParam.TIERS),
            Map.of(),
            Map.of("coverage", 0.15, "thickness", 8.0, "underside", 14.0, "spread", 10.0),
            Map.of("coverage", 0.5, "thickness", 40.0, "underside", 100.0, "spread", 60.0, "size", 2.0),
            true, TerrainFamily::floatingIslands),

    BASIN_ISLANDS("basin_islands", "Sky islands joined into lake masses: a dished basin held by a rim at the water line, with gaps where it spills into the void.",
            join(List.of(new TerrainParam("altitude", 40, 240, 150, "Height of the island layer and of the basins' water line"),
                    new TerrainParam("thickness", 4, 64, 18, "Blocks an island's top rises above the layer"),
                    new TerrainParam("underside", 4, 120, 40, "Blocks an island's underside hangs below the layer"),
                    new TerrainParam("coverage", 0.02, 0.8, 0.3, "Share of the sky with islands"),
                    new TerrainParam("size", 0.25, 4, 1, "Island size; higher is larger and fewer"),
                    new TerrainParam("spread", 0, 80, 24, "Blocks the altitudes of islands outside a lake mass vary by"),
                    new TerrainParam("lakeShare", 0.05, 0.8, 0.4, "Share of the sky joined into lake masses"),
                    new TerrainParam("lakeSize", 0.25, 4, 1, "Lake mass size; higher is larger and fewer"),
                    new TerrainParam("basinDepth", 2, 40, 8, "Blocks the basin floor lies below the water line at a mass's heart"),
                    new TerrainParam("rimHeight", 1, 16, 4, "Blocks the rim round a mass stands above the water line"),
                    new TerrainParam("gaps", 0, 0.5, 0.35, "Share of the rim cut below the water line, where a basin spills into the void"),
                    new TerrainParam("islandShare", 0.02, 0.8, 0.3, "Share of a lake mass's basin with islands, smaller and more of them than outside"),
                    new TerrainParam("islandRise", 0, 60, 8, "Blocks of cliff the islands inside a lake mass rise by out of the water")),
                    TerrainParam.TIERS),
            Map.of(),
            Map.of("lakeShare", 0.25, "basinDepth", 4.0, "rimHeight", 2.0, "gaps", 0.15, "islandRise", 4.0, "spread", 10.0,
                    "islandShare", 0.3),
            Map.of("lakeShare", 0.6, "lakeSize", 2.0, "basinDepth", 24.0, "rimHeight", 10.0, "gaps", 0.45, "islandRise", 40.0, "islandShare", 0.6,
                    "spread", 60.0, "thickness", 30.0, "underside", 80.0),
            true, TerrainFamily::basinIslands),

    ISLANDS("islands", "An ocean of islands and archipelagos.",
            List.of(new TerrainParam("coverage", 0.02, 0.7, 0.2, "Share of the map that is land"),
                    new TerrainParam("size", 0.25, 4, 1, "Island size; higher is larger and fewer"),
                    new TerrainParam("islandHeight", 4, 120, 28, "Blocks an island rises above sea level"),
                    new TerrainParam("archipelago", 0, 1, 0.4, "How much islands break into clusters of smaller ones"),
                    new TerrainParam("trench", 0, 40, 10, "Blocks the sea floor falls below seaFloor far from any island; 0 keeps it flat"),
                    new TerrainParam("crust", 0, 48, 0, "Blocks under the surface that no noise cave opens into, so caves never break the ground or the sea floor; 0 lets them")),
            Map.of("seaFloor", 30.0),
            Map.of("coverage", 0.35, "islandHeight", 10.0, "archipelago", 0.1),
            Map.of("coverage", 0.12, "islandHeight", 90.0, "archipelago", 0.5, "size", 1.5),
            false, ctx -> crust(ctx, islands(ctx))),

    VOIDS("voids", "Land broken by bottomless voids, each ringed by a raised rim.",
            List.of(new TerrainParam("coverage", 0.02, 0.5, 0.1, "Share of the land that is void"),
                    new TerrainParam("size", 0.25, 4, 1, "Void size; higher is larger and fewer"),
                    new TerrainParam("rimHeight", 0, 60, 14, "Blocks a rim rises above the land"),
                    new TerrainParam("rimWidth", 0.01, 0.3, 0.06, "Rim width, as a share of the void noise")),
            Map.of("coast", -0.6, "seaFloor", 56.0),
            Map.of("coverage", 0.04, "rimHeight", 4.0, "rimWidth", 0.03),
            Map.of("coverage", 0.3, "rimHeight", 50.0, "rimWidth", 0.15, "size", 2.0),
            true, TerrainFamily::voids),

    SHATTERED("shattered", "Land broken into tilted blocks by fault cliffs and split by deep near-vertical chasms.",
            List.of(new TerrainParam("faultHeight", 0, 48, 16, "Blocks each tilted block rises to before its fault cliff drops"),
                    new TerrainParam("faultSpacing", 0.25, 4, 1, "Fault spacing; higher is larger blocks"),
                    new TerrainParam("crackWidth", 0.01, 0.3, 0.1, "Chasm width, as a share of the crack noise"),
                    new TerrainParam("crackScale", 0.25, 4, 1.3, "Chasm spacing; higher is wider apart"),
                    new TerrainParam("chasmDepth", 4, 60, 24, "Blocks a chasm floor lies below sea level"),
                    new TerrainParam("lift", 0, 1, 0.3, "How far the ground breaks into overhangs and shards")),
            Map.of("relief", 36.0, "landHeight", 88.0),
            Map.of("faultHeight", 5.0, "crackWidth", 0.04, "lift", 0.1, "chasmDepth", 8.0),
            Map.of("faultHeight", 32.0, "faultSpacing", 0.7, "crackWidth", 0.16, "lift", 0.8, "relief", 50.0),
            false, TerrainFamily::shattered),

    SPIRES("spires", "Tall stone columns rising from the land.",
            List.of(new TerrainParam("spireHeight", 16, 200, 70, "Blocks a spire rises above the land"),
                    new TerrainParam("coverage", 0.005, 0.3, 0.04, "Share of the land under spires"),
                    new TerrainParam("thickness", 0.25, 4, 1, "Spire girth; higher is thicker"),
                    new TerrainParam("taper", 0, 1, 0.5, "0 is straight-sided columns, 1 is cones")),
            Map.of("roughness", 0.4),
            Map.of("spireHeight", 36.0, "coverage", 0.03, "taper", 0.6, "thickness", 1.4),
            Map.of("spireHeight", 180.0, "coverage", 0.08, "taper", 0.1, "thickness", 0.7),
            false, TerrainFamily::spires),

    ROOFED("roofed", "A cavern floor under a solid ceiling, on the nether or caves preset's own router: plains or rough country, "
            + "with pits, chasms, sunken corridors, stepped ledges and floor-to-ceiling pillars.",
            List.of(TerrainParam.SHARED.get(4).withDefault(0.35)),
            List.of(new TerrainParam("floorHeight", -60, 120, 40, "Height of the cavern floor in blocks; below the preset's sea level it lies under the lava or water sea"),
                    new TerrainParam("floorRelief", 0, 60, 12, "Blocks the floor rises and falls about floorHeight: 0 is flat plains, 40 rough country"),
                    new TerrainParam("ceilingGap", 16, 160, 52, "Blocks of open height from floorHeight to the ceiling, which rises and falls by half the floor's relief"),
                    new TerrainParam("flats", 0, 0.9, 0, "Share of the floor worn flat at floorHeight, its relief gone"),
                    new TerrainParam("pit", 0, 0.6, 0, "Share of the floor sunk into large pits; with terrain.layout, the layout's shape decides where"),
                    new TerrainParam("pitDepth", 4, 96, 32, "Blocks below floorHeight a pit's flat floor lies"),
                    new TerrainParam("pitSize", 0.25, 4, 1, "Pit size; higher is larger and fewer"),
                    new TerrainParam("chasms", 0, 0.3, 0, "Width of the deep narrow chasms cut into the floor, as a share of the chasm noise; 0 none"),
                    new TerrainParam("chasmDepth", 8, 120, 48, "Blocks a chasm cuts below the floor round it"),
                    new TerrainParam("corridors", 0, 0.3, 0, "Width of the long sunken corridors, as a share of the corridor noise; 0 none"),
                    new TerrainParam("corridorDepth", 2, 60, 14, "Blocks below floorHeight a corridor's flat bed lies"),
                    new TerrainParam("ledges", 0, 1, 0, "How far slopes and walls break into flat shelves: 0 none, 1 every wall stepped"),
                    new TerrainParam("ledgeStep", 3, 32, 8, "Blocks between shelves"),
                    new TerrainParam("pillars", 0, 0.2, 0, "Share of the floor under floor-to-ceiling pillars"),
                    new TerrainParam("pillarSize", 0.25, 4, 1, "Pillar girth; higher is thicker")),
            Map.of("floorRelief", 5.0, "roughness", 0.15, "ceilingGap", 44.0),
            Map.of("floorHeight", 36.0, "floorRelief", 30.0, "ceilingGap", 72.0, "roughness", 0.75, "pit", 0.12, "chasms", 0.05,
                    "ledges", 0.5, "pillars", 0.03),
            false, ctx -> {
                throw new IllegalStateException("roofed compiles through RoofedTerrain");
            }),

    END("end", "The End's islands on the end preset's own router, grown or shrunk, raised taller, ridged along their tops "
            + "and gathered by terrain.layout; the End's biome source reads the shaped islands, so its highlands follow them.",
            List.of(),
            List.of(new TerrainParam("coverage", -0.3, 0.3, 0, "Added to the End's island density: above 0 islands grow and join into "
                            + "masses, below 0 they shrink to fewer and smaller ones"),
                    new TerrainParam("islandHeight", 0, 48, 0, "Blocks higher the island tops reach: the End's top slide starts this much higher"),
                    new TerrainParam("peaks", 0, 1, 0, "Ridges raised along the island tops: 1 lifts a crest about 30 blocks"),
                    new TerrainParam("peakWidth", 0.02, 0.3, 0.08, "Ridge width, as a share of the ridge noise")),
            Map.of("coverage", -0.08),
            Map.of("coverage", 0.12, "islandHeight", 32.0, "peaks", 0.6),
            false, ctx -> {
                throw new IllegalStateException("end compiles through EndTerrain");
            });

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
        this(id, purpose, landShared(sharedDefaults), own, gentle, extreme, airborne, build);
    }

    TerrainFamily(String id, String purpose, List<TerrainParam> shared, List<TerrainParam> own,
                  Map<String, Double> gentle, Map<String, Double> extreme, boolean airborne, Function<Ctx, Shape> build) {
        this.id = id;
        this.purpose = purpose;
        List<TerrainParam> all = new ArrayList<>(shared);
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

    private static List<TerrainParam> join(List<TerrainParam> a, List<TerrainParam> b) {
        List<TerrainParam> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private static TerrainParam count(String name, int min, int max, int defaultValue, String description) {
        return TerrainParam.count(name, min, max, defaultValue, description);
    }

    /** The shared land parameters, with a family's own defaults for some. */
    private static List<TerrainParam> landShared(Map<String, Double> defaults) {
        List<TerrainParam> out = new ArrayList<>();
        for (TerrainParam p : TerrainParam.SHARED) {
            out.add(defaults.containsKey(p.name()) ? p.withDefault(defaults.get(p.name())) : p);
        }
        return out;
    }

    public String id() {
        return id;
    }

    /** A terrain under a ceiling, built on the nether or caves preset's router ({@link RoofedTerrain}). */
    public boolean roofed() {
        return this == ROOFED;
    }

    /** A terrain built on its preset's own router and settings: roofed on nether and caves, end on the end preset. */
    public boolean ownRouter() {
        return this == ROOFED || this == END;
    }

    /** Blocks under and over sea level a shore spans. */
    public static final double SHORE_BELOW = 3, SHORE_ABOVE = 3;

    /**
     * Router continentalness at sea level and at the ends of the shore ({@link #SHORE_BELOW} under it to
     * {@link #SHORE_ABOVE} over it on the base height profile), or null for a terrain with no sea.
     * {@link Ctx#land()} puts every land family's sea level at -0.11.
     */
    public double[] shore(TerrainSpec spec) {
        double m = spec.heightMultiplier();
        if (spec.airborne()) return null;
        return switch (this) {
            case FLOATING_ISLANDS, BASIN_ISLANDS, ROOFED, END -> null;
            case ISLANDS -> {
                // islands(): height over sea and continentalness along the island noise, in sigmas from the coverage threshold.
                double peak = spec.param("islandHeight") * m;
                double[] u = {-0.6, -0.06, 0, 0.3, 1.2}, h = {-1000, -3 * m, m, 0.4 * peak, peak};
                double[] cu = {-0.6, -0.2, 0, 0.4, 1.2}, cv = {-0.6, -0.3, -0.15, 0.05, 0.4};
                yield new double[]{at(cu, cv, at(h, u, 0)), at(cu, cv, at(h, u, -SHORE_BELOW)), at(cu, cv, at(h, u, SHORE_ABOVE))};
            }
            default -> {
                // Ctx.land(): sea - 5 at -0.15, sea at -0.11, sea + 4 at -0.05.
                double[] c = {-0.36, -0.15, -0.11, -0.05}, h = {-1000, -5 * m, 0, 4 * m};
                yield new double[]{-0.11, at(h, c, -SHORE_BELOW), at(h, c, SHORE_ABOVE)};
            }
        };
    }

    /** {@code ys} at {@code x}, linear between the points {@code xs} (ascending), held at the ends; rounded to 4 places. */
    static double at(double[] xs, double[] ys, double x) {
        double v;
        if (x <= xs[0]) v = ys[0];
        else if (x >= xs[xs.length - 1]) v = ys[ys.length - 1];
        else {
            int i = 1;
            while (x > xs[i]) i++;
            v = ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
        }
        return Math.round(v * 10000) / 10000.0;
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

    /** Whether the terrain shapes basins that {@code liquids.basins} can fill. */
    public boolean shapesBasins() {
        return this == BASIN_ISLANDS;
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
     * continentalness as biomes should read it, the weight of vanilla's 3D
     * noise, a function that weighs it further where it varies (null:
     * none), a ceiling on the surface level the aquifers read (null: none), and ground no cave
     * cuts, added to the final density as a maximum (null: none). An airborne terrain also gives its
     * {@code place}, the router continentalness biomes read when they follow its islands ({@link #PLACE_VOID}
     * over the void, {@link #PLACE_BASIN} over a basin's water, {@link #PLACE_LAND} on land), and its islands'
     * {@code top} in blocks, which an underside biome's depth is measured from (null: none).
     */
    record Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
                 JsonElement continents, double noise3d, JsonElement noiseWeight, JsonElement aquiferCeiling, JsonElement solid,
                 JsonElement place, JsonElement top) {

        Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
              JsonElement continents, double noise3d, JsonElement noiseWeight, JsonElement aquiferCeiling, JsonElement solid) {
            this(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, solid, null, null);
        }

        Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
              JsonElement continents, double noise3d, JsonElement noiseWeight, JsonElement aquiferCeiling) {
            this(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, null);
        }

        Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
              JsonElement continents, double noise3d, JsonElement noiseWeight) {
            this(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, null, null);
        }

        Shape(JsonElement height, JsonElement factor, JsonElement jaggedness, JsonElement cut, JsonElement plus,
              JsonElement continents, double noise3d) {
            this(height, factor, jaggedness, cut, plus, continents, noise3d, null, null, null);
        }

        Shape withHeight(JsonElement h) {
            return new Shape(h, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, solid, place, top);
        }

        Shape withFactor(JsonElement f) {
            return new Shape(height, f, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, solid, place, top);
        }

        Shape withAquiferCeiling(JsonElement ceiling) {
            return new Shape(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, ceiling, solid, place, top);
        }

        Shape withSolid(JsonElement s) {
            return new Shape(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, s, place, top);
        }

        Shape withPlace(JsonElement p, JsonElement t) {
            return new Shape(height, factor, jaggedness, cut, plus, continents, noise3d, noiseWeight, aquiferCeiling, solid, p, t);
        }
    }

    /** {@link Shape#place} over the void, over a basin's open water, and on land. */
    public static final double PLACE_VOID = -1, PLACE_BASIN = -0.4, PLACE_LAND = 0.5;
    /** The place cue's cut between the void and held ground, and between a basin's water and land. */
    public static final double PLACE_HELD = -0.7, PLACE_SHORE = 0.05;
    /** The island noise's {@code core} at which a column reads as land for its place. */
    static final double PLACE_FROM = 0.02, PLACE_TO = 0.1;
    /** Standard deviations a layout at strength 1 moves an airborne terrain's coverage noise by: coverage near 0 at the shape's -1. */
    static final double AIRBORNE_LAYOUT = 3;

    /** {@code n} with the layout's shift added ({@link Ctx#layoutShift}); unchanged without a layout. */
    private static JsonElement laidOut(Ctx ctx, JsonElement n, double sigma) {
        JsonElement shift = ctx.layoutShift(sigma, AIRBORNE_LAYOUT);
        return shift == null ? n : add(n, shift);
    }

    /**
     * The basin floor is flat at {@code landHeight} inland; the wear noise
     * raises hills out of it wherever it lies above its {@code basinShare}
     * quantile, over a rise that narrows from rolling (steepness 0) to a cliff
     * with a flat mesa top (steepness 1).
     */
    private static Shape erosion(Ctx ctx) {
        double sea = ctx.shore();
        double coast = ctx.p("coast");
        double sigma = Noises.sigma("wear");
        double floorAt = Noises.threshold(1 - ctx.p("basinShare"), sigma);
        double rise = sigma * (0.1 + 2.4 * (1 - ctx.p("steepness")));
        JsonElement n = ctx.noise("wear", 1, 0);
        JsonElement hill = spline(n, -1.5, 0, floorAt, 0, floorAt + rise, 1, 1.5, 1);
        JsonElement floorLevel = spline(ctx.continents(), -1.5, ctx.p("seaFloor") - Ctx.BASIN_DEPTH, coast - 0.25, ctx.p("seaFloor"),
                coast - 0.04, sea - 5, coast, sea, coast + 0.06, sea + 3, coast + 0.2, ctx.p("landHeight"), 1.5, ctx.p("landHeight"));
        JsonElement land = ctx.landMask();
        JsonElement h = add(add(floorLevel, mul(mul(hill, land), ctx.p("hillHeight"))),
                mul(mul(land, ctx.hills()), ctx.p("relief")));
        Shape base = ctx.land();
        return new Shape(h, ctx.factor(), c(0), null, null, base.continents(), 0.5);
    }

    private static Shape ridges(Ctx ctx) {
        Shape land = ctx.land();
        double w = ctx.p("rangeWidth"), web = ctx.p("web");
        JsonElement zeroLine = add(mul(Df.abs(ctx.weirdness()), -3), 1);
        JsonElement line = web <= 0 ? ctx.peaksValleys() : web >= 1 ? zeroLine
                : add(mul(ctx.peaksValleys(), 1 - web), mul(zeroLine, web));
        JsonElement onRidge = ramp(line, 1 - 2 * w, 1);
        JsonElement inland = ramp(ctx.continents(), ctx.p("coast") + 0.05, ctx.p("coast") + 0.3);
        double f = ctx.p("erosionFalloff");
        JsonElement rugged = f >= 1 ? spline(ctx.erosion(), -1, 1, 0, 0.8, 0.5, 0.45, 1, 0.25)
                : spline(ctx.erosion(), -1, 1, 0, 1 - 0.2 * f, 0.5, 1 - 0.55 * f, 1, 1 - 0.75 * f);
        JsonElement mountain = Df.cache(mul(mul(onRidge, inland), rugged));
        JsonElement h = add(land.height(), mul(profile(mountain, ctx.p("crest")), ctx.p("peakHeight")));
        return new Shape(h, land.factor(), mul(mountain, ctx.p("jaggedness")), null, null, land.continents(), 1);
    }

    /** {@code x} to the power {@code crest} on 0..1, as a spline through quarter points; the plain ramp at crest 1. */
    static JsonElement profile(JsonElement x, double crest) {
        if (crest >= 1) return spline(x, 0, 0, 1, 1);
        return spline(x, 0, 0, 0.125, Math.pow(0.125, crest), 0.25, Math.pow(0.25, crest), 0.5, Math.pow(0.5, crest), 0.75,
                Math.pow(0.75, crest), 1, 1);
    }

    private static Shape terraces(Ctx ctx) {
        Shape land = ctx.land();
        JsonElement stepped = steps(land.height(), ctx.shore(), ctx.p("stepHeight"), ctx.p("riser"));
        return new Shape(min(stepped, c(ctx.p("plateauHeight"))), land.factor(), land.jaggedness(), null, null,
                land.continents(), 0.15);
    }

    /**
     * A height floored into levels {@code step} apart from {@code base}, each
     * flat for {@code 1 - riser} of the step and rising over the rest; never
     * above the height it steps.
     */
    static JsonElement steps(JsonElement height, double base, double step, double riser) {
        JsonElement above = mul(add(height, -base), 1 / step);
        JsonElement level = floor(above);
        JsonElement frac = sub(above, level);
        JsonElement rise = spline(frac, 0, 0, 1 - riser, 0, 1, 1);
        return add(mul(add(level, rise), step), base);
    }

    /** Share of each cascade step that is the fall rather than the flat floor. */
    static final double CASCADE_RISER = 0.05;
    /**
     * The weight of vanilla's 3D noise inside a canyon: small enough that it
     * lifts a floor by a block at most under the land's own factor. The factor
     * stays the land's, so caves start as deep below a canyon as below the land
     * and a cell interpolates a wall between corners of like size (T149).
     */
    static final double CHANNEL_NOISE = 1 / 16.0;
    /** Blocks a pool sinks below its floor. */
    static final double POOL_DEPTH = 4;

    /**
     * An old river bed cut into the land. Inside the channel the ground drops
     * to a floor {@code depth} below the land at a canyon's upper reaches; the
     * grade noise and the coast lower it toward sea level by {@code grade} of
     * the way, and the floor is stepped {@code cascadeStep} apart into flat
     * reaches with dry falls between them. Where the grade noise lies in its
     * lowest {@code pools} share the floor sinks into a hollow, which holds
     * water where the floor is at sea level. Inside the channel the 3D noise
     * and jaggedness fall away, so the ground follows the height: floors stay
     * flat and walls steep.
     */
    private static Shape canyons(Ctx ctx) {
        Shape land = ctx.land();
        double sea = ctx.shore();
        double w = ctx.p("width");
        double pools = ctx.p("pools");
        double sigma = Noises.sigma("grade");
        JsonElement height = ctx.named("canyon_land", land.height());
        JsonElement channel = abs(ctx.noise("gorge", ctx.p("frequency"), 0));
        JsonElement onLand = ramp(ctx.continents(), ctx.p("coast"), ctx.p("coast") + 0.15);
        JsonElement profile = Df.cache(mul(spline(channel, 0, 1, w * 0.6, 1, w, 0.15, w * 1.6, 0), onLand));
        JsonElement grade = ctx.named("canyon_grade", ctx.noise("grade", ctx.p("frequency"), 0));
        double low = pools > 0 ? Noises.threshold(1 - pools, sigma) : -2.5 * sigma;
        JsonElement inland = ramp(ctx.continents(), ctx.p("coast"), ctx.p("coast") + 0.4);
        JsonElement upstream = mul(ramp(grade, low, low + 2.5 * sigma), inland);
        JsonElement keep = sub(c(1), mul(sub(c(1), upstream), ctx.p("grade")));
        JsonElement bed = ctx.named("canyon_bed", add(mul(max(add(height, -(sea + 1 + ctx.p("depth"))), c(0)), keep), sea + 1));
        JsonElement floor = steps(bed, sea + 1, ctx.p("cascadeStep"), CASCADE_RISER);
        if (pools > 0) {
            floor = sub(floor, mul(ramp(grade, low, low - 0.1 * sigma), POOL_DEPTH));
        }
        JsonElement h = lerp(profile, height, min(height, floor));
        return new Shape(h, land.factor(), lerp(profile, land.jaggedness(), c(0)), null, null, land.continents(), land.noise3d(),
                lerp(profile, c(1), c(CHANNEL_NOISE)));
    }

    /**
     * Blocks under sea level the aquifers read the surface near a river: an
     * aquifer adds 8 to each surface sample and takes the sea where that lies
     * under sea level.
     */
    static final double RIVER_WATER_TABLE = 9;
    /**
     * How far past its banks a river holds the water table, in the channel
     * noise's units as {@code width} and {@code bankWidth} are: about the 16
     * blocks between an aquifer's surface samples, so a cave that opens into
     * the banks has a sample on the river.
     */
    static final double RIVER_WATER_REACH = 0.1;

    /**
     * Channels cut {@code depth} below sea level through the land, rising to
     * it over {@code bankWidth}. Within {@link #RIVER_WATER_REACH} of the
     * banks the river is the water table: the aquifers read the surface no
     * higher than {@link #RIVER_WATER_TABLE} under sea level, so a cave that
     * opens beside a channel below sea level fills from it (T161).
     */
    private static Shape rivers(Ctx ctx) {
        Shape land = ctx.land();
        double w = ctx.p("width");
        double banks = w + ctx.p("bankWidth");
        JsonElement channel = abs(ctx.noise("channel", ctx.p("frequency"), 0));
        JsonElement profile = spline(channel, 0, 1, w, 1, banks, 0);
        JsonElement bed = lerp(profile, land.height(), c(ctx.shore() - ctx.p("depth")));
        double table = ctx.sea() - RIVER_WATER_TABLE;
        double reach = banks + RIVER_WATER_REACH;
        JsonElement ceiling = spline(channel, 0, table, reach, table, reach + 0.01, ctx.maxY());
        return land.withHeight(min(land.height(), bed)).withAquiferCeiling(ceiling);
    }

    /**
     * Islands where the islands noise lies in its highest {@code coverage} share, with {@code terrain.layout}'s
     * shape moving that noise by up to {@link #AIRBORNE_LAYOUT} of its deviations: radial gathers the islands
     * round its centre and clears the sky past its falloff, ring sets them round a hollow.
     */
    private static Shape floatingIslands(Ctx ctx) {
        double m = ctx.heightMultiplier();
        double sigma = Noises.sigma("islands");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        JsonElement n = laidOut(ctx, ctx.noise("islands", 1 / ctx.p("size"), 0), sigma);
        JsonElement core = Df.cache(ramp(n, t, t + sigma));
        JsonElement layer = add(spread(ctx), ctx.p("altitude"));
        JsonElement top = islandTop(ctx, layer, core, ctx.p("thickness") * m, ctx.p("size"));
        if (tiers(ctx) > 1) top = ctx.named("tier0_top", top);
        JsonElement bottom = add(sub(layer, mul(core, ctx.p("underside") * m)), 2);
        JsonElement y = negY(ctx.minY(), ctx.maxY());
        JsonElement inside = min(add(top, y), sub(c(0), add(bottom, y)));
        JsonElement place = add(mul(ramp(core, PLACE_FROM, PLACE_TO), PLACE_LAND - PLACE_VOID), PLACE_VOID);
        Stack stack = stack(ctx, new Stack(inside, place, top), y);
        Shape shape = new Shape(c(ctx.minY() - 64.0), ctx.factor(), c(0), null, mul(stack.inside(), 1 / 8.0), c(0.3),
                0.1 + 0.6 * ctx.p("roughness"));
        return shape.withPlace(stack.place(), stack.top());
    }

    /** Blocks an island layer's altitude varies by, from the altitude noise: {@code spread} is one deviation. */
    private static JsonElement spread(Ctx ctx) {
        return mul(ctx.noise("altitude", 1, 0), ctx.p("spread") * ctx.heightMultiplier() / Noises.sigma("altitude"));
    }

    /** The island noise's {@code core} over which a flat top rolls off its edge to the full table. */
    static final double FLAT_EDGE = 0.15;
    /** Share of each terrace step that is slope rather than flat plateau. */
    static final double TERRACE_RISER = 0.15;
    /** Deviations of the detail noise either side of its mean that span the plateau levels. */
    static final double TERRACE_SPAN = 1.5;

    /**
     * An island's top over {@code layer}: {@code thickness} times a profile of its core, from the dome
     * {@code sqrt(core)} at {@code flatness} 0 to a table that reaches its full height over the first
     * {@link #FLAT_EDGE} of the core at 1. With {@code terraces} of 2 or more, the detail noise (at the
     * island noise's frequency for {@code size}) lifts the table in {@code terraceStep} levels over the same edge,
     * so a mass breaks into plateaus at different heights.
     */
    private static JsonElement islandTop(Ctx ctx, JsonElement layer, JsonElement core, double thickness, double size) {
        double flat = ctx.p("flatness", 0);
        JsonElement dome = sqrt(core);
        JsonElement profile = dome;
        if (flat > 0) {
            JsonElement table = sqrt(ramp(core, 0, FLAT_EDGE));
            profile = flat >= 1 ? table : add(mul(dome, 1 - flat), mul(table, flat));
        }
        JsonElement top = add(add(layer, mul(profile, thickness)), -2);
        int levels = (int) ctx.p("terraces", 0);
        if (levels >= 2) {
            double step = ctx.p("terraceStep");
            JsonElement d = clamp(mul(ctx.noise("detail", 1 / size, 0), 1 / Noises.sigma("detail")), -TERRACE_SPAN, TERRACE_SPAN);
            JsonElement raw = mul(add(d, TERRACE_SPAN), (levels - TERRACE_RISER) * step / (2 * TERRACE_SPAN));
            top = add(top, mul(steps(raw, 0, step, TERRACE_RISER), ramp(core, 0, FLAT_EDGE)));
        }
        return top;
    }

    /** The island layers a terrain over a void lays, counting its own. */
    static int tiers(Ctx ctx) {
        return (int) ctx.p("tiers", 1);
    }

    /** Each tier's island noise frequency against the one under it, besides its size: no tier repeats the pattern below it. */
    static final double TIER_FREQUENCY = 1.31;
    /** Blocks of island over which a tier's place cue turns to land: an island its gap has cut away reads as void. */
    static final double TIER_HELD = 2;
    /** Blocks a tier needs over the gap at least, for an island of its own above the tier under it. */
    static final double TIER_ROOM = 8;

    /** An airborne terrain's islands so far: where they are solid (above 0), the place cue, and the island top a y belongs to. */
    record Stack(JsonElement inside, JsonElement place, JsonElement top) {}

    /**
     * {@code base} with {@code tiers} − 1 further layers of plain floating islands above it. Tier k floats
     * {@code tierSpacing} above tier k − 1 with the same altitude spread; its coverage is {@code tierCoverage} or the
     * coverage under it times {@code tierShrink}, its size, thickness and underside are those under it times
     * {@code tierShrink}, and its island noise runs {@link #TIER_FREQUENCY} times faster again, so its islands stand
     * over lower ones and over open void alike. A tier's underside never comes within {@code tierGap} of the top
     * under it: where it would, the underside is cut back, and where nothing is left the island is gone. Above
     * the midpoint of that gap the place cue and the island top are the tier's own, below it the tier under it's.
     */
    private static Stack stack(Ctx ctx, Stack base, JsonElement y) {
        int tiers = tiers(ctx);
        if (tiers <= 1) return base;
        double m = ctx.heightMultiplier();
        double sigma = Noises.sigma("islands");
        double shrink = ctx.p("tierShrink");
        double coverage = ctx.p("coverage"), size = ctx.p("size"), thickness = ctx.p("thickness"), underside = ctx.p("underside");
        Stack s = base;
        JsonElement below = base.top();
        for (int k = 1; k < tiers; k++) {
            coverage = ctx.p("tierCoverage") > 0 ? ctx.p("tierCoverage") : coverage * shrink;
            size *= shrink;
            thickness *= shrink;
            underside *= shrink;
            double t = Noises.threshold(coverage, sigma);
            JsonElement n = laidOut(ctx, ctx.noise("islands", Math.pow(TIER_FREQUENCY, k) / size, 0), sigma);
            JsonElement core = Df.cache(ramp(n, t, t + sigma));
            JsonElement layer = add(spread(ctx), ctx.p("altitude") + k * ctx.p("tierSpacing"));
            JsonElement top = ctx.named("tier" + k + "_top", islandTop(ctx, layer, core, thickness * m, size));
            JsonElement bottom = ctx.named("tier" + k + "_bottom",
                    max(add(sub(layer, mul(core, underside * m)), 2), add(below, ctx.p("tierGap"))));
            JsonElement inside = min(add(top, y), sub(c(0), add(bottom, y)));
            JsonElement held = mul(ramp(core, PLACE_FROM, PLACE_TO), ramp(sub(top, bottom), 0, TIER_HELD));
            JsonElement place = add(mul(held, PLACE_LAND - PLACE_VOID), PLACE_VOID);
            JsonElement above = ramp(add(mul(add(below, bottom), 0.5), y), 0.5, -0.5);
            s = new Stack(max(s.inside(), inside), lerp(above, s.place(), place), lerp(above, s.top(), top));
            below = top;
        }
        return s;
    }

    /**
     * Refuses a stack of tiers that cannot fit the dimension: the top tier's islands over {@code maxY}, or a
     * {@code tierSpacing} with no room for an island over {@code tierGap}.
     */
    public static void checkStack(String slug, TerrainSpec spec, int maxY) {
        if (!spec.params().containsKey("tiers") || spec.param("tiers") <= 1) return;
        int tiers = (int) spec.param("tiers");
        double spacing = spec.param("tierSpacing"), gap = spec.param("tierGap");
        if (spacing < gap + TIER_ROOM) {
            throw new IllegalArgumentException(slug + ": terrain.params.tierSpacing " + TerrainSpec.fmt(spacing)
                    + " leaves no room for a tier's islands over tierGap " + TerrainSpec.fmt(gap) + "; it needs at least "
                    + TerrainSpec.fmt(gap + TIER_ROOM));
        }
        double thickness = spec.param("thickness") * spec.heightMultiplier() * Math.pow(spec.param("tierShrink"), tiers - 1);
        double levels = spec.params().getOrDefault("terraces", 0.0);
        double terraces = levels >= 2 ? (levels - 1) * spec.param("terraceStep") : 0;
        double top = spec.param("altitude") + (tiers - 1) * spacing + thickness + terraces;
        if (top > maxY) {
            throw new IllegalArgumentException(slug + ": terrain.params.tiers " + tiers + " at tierSpacing " + TerrainSpec.fmt(spacing)
                    + " from altitude " + TerrainSpec.fmt(spec.param("altitude")) + " lift the top tier's islands to y "
                    + Math.round(top) + ", above the dimension's top at " + maxY);
        }
    }

    /** Where a basin's water may stand: above 0, and below 1 within reach of the rim, where its fluid is scheduled to flow. */
    public static final String BASIN = "basin";
    /** The basin floor's height in blocks: water stands no lower than {@link #BASIN_FLOOR_REACH} under it. */
    public static final String BASIN_FLOOR = "basin_floor";
    public static final int BASIN_FLOOR_REACH = 3;
    /**
     * Blocks an island's top drops where no island stands, so the islands' top over a basin's open water is its
     * floor and an underside biome never takes a basin floor.
     */
    static final double NO_ISLAND_DROP = 512;
    /** The island noise's {@code core} over which an island inside a lake mass climbs its cliff: about two blocks. */
    static final double ISLAND_CLIFF_FROM = 0.02;
    static final double ISLAND_CLIFF_TO = 0.1;
    /** The island noise's frequency inside a lake mass against outside: smaller islands, more of them. */
    static final double INNER_ISLANDS = 1.5;
    /** The share of {@code thickness} an island inside a lake mass domes by over its cliff: a flat top. */
    static final double INNER_DOME = 0.3;
    /** Blocks under the water line a rim gap's crest is cut to; its lip falls a rim's height further to the edge. */
    static final double GAP_DROP = 5;
    /** The gap noise's frequency against the detail noise's: blobs broad enough to breach a rim's whole width. */
    static final double GAP_FREQUENCY = 0.25;
    /** Blocks over a gap an island's top is held from, outside the gap: no island stands in it. */
    static final double GAP_CLEAR = 512;

    /**
     * Floating islands with lake masses: where the {@code lakes} noise lies in its
     * highest {@code lakeShare}, a mass joins the islands at {@code altitude}. Across
     * the mass's edge (in the lakes noise's units, from its threshold) the top
     * climbs from under the water line to a rim {@code rimHeight} above it, holds
     * a crest, falls back to a shore just under the water and dishes to
     * {@code basinDepth} below it. The islands inside a mass sit on the water
     * line, read from the island noise at {@link #INNER_ISLANDS} times its frequency over
     * {@code islandShare} of the basin inside the shore; no island stands on the rim, so open
     * water rings every island. A block or two in from its shore each climbs a cliff
     * {@code islandRise} high that drops into the lake, to a top domed by only
     * {@link #INNER_DOME} of {@code thickness}; outside, islands keep the layer's spread.
     * Where the {@code detail} noise lies in its highest {@code gaps} share the
     * rim is cut to {@link #GAP_DROP} under the water line, its outer lip lower still
     * to the edge, and no island stands in the cut, so the basin spills off the edge. The basin and its floor
     * are written as {@link #BASIN} and {@link #BASIN_FLOOR} for {@code liquids.basins}; the mass up
     * to a block under its floor is ground no cave cuts, so a basin holds its water.
     */
    private static Shape basinIslands(Ctx ctx) {
        double m = ctx.heightMultiplier();
        double a = ctx.p("altitude");
        double sigma = Noises.sigma("islands");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        double sl = Noises.sigma("lakes");
        double tl = Noises.threshold(ctx.p("lakeShare"), sl);
        JsonElement lakes = ctx.named("lakes", laidOut(ctx, ctx.noise("lakes", 1 / ctx.p("lakeSize"), 0), sl));
        double edge = tl;
        double risen = tl + 0.08 * sl;
        double crest = tl + 0.14 * sl;
        double crestEnd = tl + 0.2 * sl;
        double shore = tl + 0.32 * sl;
        double heart = tl + 0.8 * sl;
        double ti = Noises.threshold(ctx.p("islandShare"), sigma);
        JsonElement outerCore = mul(ramp(laidOut(ctx, ctx.noise("islands", 1 / ctx.p("size"), 0), sigma), t, t + sigma),
                ramp(lakes, edge, edge - 0.1 * sl));
        JsonElement basin = Df.cache(ramp(lakes, shore, shore + 0.12 * sl));
        JsonElement innerCore = mul(ramp(ctx.noise("islands", INNER_ISLANDS / ctx.p("size"), 0), ti, ti + sigma), basin);
        JsonElement core = Df.cache(add(outerCore, innerCore));
        double rim = ctx.p("rimHeight");
        double depth = ctx.p("basinDepth");
        JsonElement massTop = spline(lakes, edge - 0.01, a - 6, edge, a - 4, risen, a + rim, crestEnd, a + rim,
                shore, a - 2, heart, a - depth);
        JsonElement cut = null;
        if (ctx.p("gaps") > 0) {
            double sd = Noises.sigma("detail");
            double tg = Noises.threshold(ctx.p("gaps"), sd);
            JsonElement gap = ramp(ctx.noise("detail", GAP_FREQUENCY / ctx.p("lakeSize"), 0), tg, tg + 0.3 * sd);
            JsonElement band = spline(lakes, edge - 0.1 * sl, 0, edge - 0.08 * sl, 1, crestEnd, 1, shore, 0);
            cut = ctx.named("gap", mul(gap, band));
            massTop = sub(massTop, mul(cut, rim + GAP_DROP));
        }
        JsonElement floor = ctx.named(BASIN_FLOOR, massTop);
        JsonElement massBottom = spline(lakes, edge, a - 2, crestEnd, a - depth - 4, heart, a - depth - 4 - 0.5 * ctx.p("underside") * m);
        JsonElement inMass = ramp(lakes, edge - 0.1 * sl, edge);
        JsonElement spread = add(mul(ctx.noise("altitude", 1, 0), ctx.p("spread") * m / Noises.sigma("altitude")), a);
        JsonElement layer = lerp(inMass, spread, c(a));
        JsonElement cliff = ramp(core, ISLAND_CLIFF_FROM, ISLAND_CLIFF_TO);
        JsonElement dome = mul(sub(c(1), mul(basin, 1 - INNER_DOME)), ctx.p("thickness") * m);
        JsonElement rise = add(mul(sqrt(core), dome), mul(mul(cliff, inMass), ctx.p("islandRise") * m));
        JsonElement top = add(add(layer, rise), -2);
        if (cut != null) {
            top = min(top, add(mul(sub(c(1), cut), GAP_CLEAR), a - GAP_DROP));
        }
        JsonElement bottom = add(sub(layer, mul(core, ctx.p("underside") * m)), 2);
        JsonElement y = negY(ctx.minY(), ctx.maxY());
        JsonElement island = min(add(top, y), sub(c(0), add(bottom, y)));
        JsonElement mass = min(add(floor, y), sub(c(0), add(massBottom, y)));
        ctx.named(BASIN, mul(add(lakes, -crest), 1 / (shore - crest)));
        JsonElement sealed = mul(min(add(add(floor, -1), y), sub(c(0), add(massBottom, y))), 1 / 8.0);
        JsonElement onIsland = ramp(core, PLACE_FROM, PLACE_TO);
        JsonElement water = ramp(lakes, crest, shore);
        JsonElement open = mul(water, sub(c(1), onIsland));
        JsonElement held = max(onIsland, mul(inMass, sub(c(1), water)));
        JsonElement place = add(add(mul(open, PLACE_BASIN - PLACE_VOID), mul(held, PLACE_LAND - PLACE_VOID)), PLACE_VOID);
        JsonElement islandTop = max(sub(top, mul(sub(c(1), ramp(core, 0, ISLAND_CLIFF_FROM)), NO_ISLAND_DROP)), floor);
        if (tiers(ctx) > 1) islandTop = ctx.named("tier0_top", islandTop);
        Stack stack = stack(ctx, new Stack(max(island, mass), place, islandTop), y);
        return new Shape(c(ctx.minY() - 64.0), ctx.factor(), c(0), null, mul(stack.inside(), 1 / 8.0), c(0.3), 0.1 + 0.6 * ctx.p("roughness"))
                .withSolid(sealed).withPlace(stack.place(), stack.top());
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
        double sea = ctx.shore();
        double peak = ctx.p("islandHeight");
        JsonElement nc = Df.cache(clamp(n, -4, 4));
        JsonElement h = spline(nc, -4.5, ctx.p("seaFloor") - ctx.p("trench"), t - 0.6 * s, ctx.p("seaFloor"), t - 0.06 * s, sea - 3,
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

    /**
     * A sawtooth of the plates noise tilts the land up by {@code faultHeight}
     * across each block and drops it at a fault cliff; a crack noise stretched
     * four times taller than wide cuts near-vertical chasms through the land
     * down to {@code chasmDepth} below sea level.
     */
    private static Shape shattered(Ctx ctx) {
        Shape land = ctx.land();
        double lift = ctx.p("lift");
        JsonElement q = mul(ctx.noise("plates", 1 / ctx.p("faultSpacing"), 0), 1 / (0.5 * Noises.sigma("plates")));
        JsonElement tilt = sub(q, floor(q));
        JsonElement h = add(land.height(), mul(mul(tilt, ctx.landMask()), ctx.p("faultHeight")));
        double sigma = Noises.sigma("shatter");
        double xz = 1 / ctx.p("crackScale");
        JsonElement crack = abs(ctx.noise("shatter", xz, 0.25 * xz));
        JsonElement chasm = mul(add(crack, -ctx.p("crackWidth") * sigma / 0.3), 12 / sigma);
        JsonElement floorBelow = mul(add(negY(ctx.minY(), ctx.maxY()), ctx.shore() - ctx.p("chasmDepth")), 0.2);
        JsonElement offshore = mul(sub(c(1), ctx.landMask()), 10);
        JsonElement cut = max(max(chasm, floorBelow), offshore);
        JsonElement factor = lerp(c(lift), land.factor(), c(1.2));
        return new Shape(h, factor, land.jaggedness(), cut, null, land.continents(), 0.6 + 2.5 * lift);
    }

    /** Blocks under the height a crust starts: the top is the 3D noise's, so the crust never stands above the ground. */
    static final double CRUST_TOP = 3;

    /**
     * {@code land} with ground no cave cuts from {@link #CRUST_TOP} to {@code crust} blocks under its height, so
     * spaghetti, noodle and cheese caves stay below the crust and never open pits and ledges in the surface.
     */
    private static Shape crust(Ctx ctx, Shape land) {
        double crust = ctx.p("crust");
        if (crust <= CRUST_TOP) return land;
        JsonElement y = negY(ctx.minY(), ctx.maxY());
        JsonElement band = min(add(add(land.height(), -CRUST_TOP), y), sub(c(0), add(add(land.height(), -crust), y)));
        return land.withSolid(mul(band, 1 / 8.0));
    }

    /** Blocks of island a spire on {@code terrain.floor} islands stands on: the platform's dome, and its underside below landHeight. */
    static final double SPIRE_ISLAND_TOP = 6, SPIRE_ISLAND_UNDERSIDE = 18;
    /** Standard deviations of the spire noise below a spire's threshold over which its island platform reaches out. */
    static final double SPIRE_ISLAND_REACH = 0.8;
    /** Router continentalness under a spire standing in a sea, and on the sea floor between: land biomes on the columns, sea biomes round them. */
    static final double SPIRE_LAND = 0.2, SPIRE_SEA = -0.6;

    /**
     * Columns where the spire noise lies in its highest {@code coverage} share, {@code spireHeight} over
     * {@code landHeight} at their crest. {@code terrain.floor} says what stands between them: {@code land}
     * (the shared land, the columns rising inland), {@code sea} (the sea floor at {@code seaFloor}, every column
     * rising out of the sea), {@code void} (nothing: each column stands from the bottom of an open world) or
     * {@code islands} (each column on a domed island of its own, over a void).
     */
    private static Shape spires(Ctx ctx) {
        if (!LAND_FLOOR.equals(ctx.floor())) return spiresOver(ctx);
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

    private static final String LAND_FLOOR = TerrainSpec.LAND_FLOOR;

    /** {@link #spires} on a sea floor, over a void, or on islands of their own. */
    private static Shape spiresOver(Ctx ctx) {
        double sigma = Noises.sigma("spires");
        double t = Noises.threshold(ctx.p("coverage"), sigma);
        JsonElement s = Df.cache(laidOut(ctx, ctx.noise("spires", 1 / ctx.p("thickness"), 0), sigma));
        JsonElement a = Df.cache(ramp(s, t, t + 0.6 * sigma));
        JsonElement column = clamp(mul(a, 6), 0, 1);
        JsonElement profile = lerp(c(ctx.p("taper")), column, a);
        double base = ctx.p("landHeight");
        JsonElement crest = add(mul(profile, ctx.p("spireHeight")), base);
        JsonElement present = ramp(s, t, t + 0.02 * sigma);
        if ("sea".equals(ctx.floor())) {
            JsonElement h = lerp(present, c(ctx.p("seaFloor")), crest);
            JsonElement factor = lerp(column, ctx.factor(), c(24));
            return new Shape(h, factor, c(0), null, null, lerp(present, c(SPIRE_SEA), c(SPIRE_LAND)), 0.1 + 0.6 * ctx.p("roughness"));
        }
        JsonElement y = negY(ctx.minY(), ctx.maxY());
        JsonElement top, bottom, held;
        if ("islands".equals(ctx.floor())) {
            JsonElement platform = Df.cache(ramp(s, t - SPIRE_ISLAND_REACH * sigma, t));
            top = max(crest, add(mul(sqrt(platform), SPIRE_ISLAND_TOP), base - 2));
            bottom = sub(c(base + 2), add(mul(platform, SPIRE_ISLAND_UNDERSIDE), mul(column, 0.25 * ctx.p("spireHeight"))));
            held = ramp(platform, PLACE_FROM, PLACE_TO);
        } else {
            top = lerp(present, c(ctx.minY() - 64.0), crest);
            bottom = c(ctx.minY() - 64.0);
            held = present;
        }
        JsonElement inside = min(add(top, y), sub(c(0), add(bottom, y)));
        JsonElement place = add(mul(held, PLACE_LAND - PLACE_VOID), PLACE_VOID);
        return new Shape(c(ctx.minY() - 64.0), ctx.factor(), c(0), null, mul(inside, 1 / 8.0), c(0.3), 0.1 + 0.6 * ctx.p("roughness"))
                .withPlace(place, top);
    }
}
