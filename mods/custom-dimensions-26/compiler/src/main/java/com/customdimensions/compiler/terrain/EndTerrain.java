package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static com.customdimensions.compiler.terrain.Df.abs;
import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.cache;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.ramp;
import static com.customdimensions.compiler.terrain.Df.ref;
import static com.customdimensions.compiler.terrain.Df.spline;
import static com.customdimensions.compiler.terrain.Df.type;

/**
 * {@link TerrainFamily#END}: the End's islands shaped on the end preset's own
 * router. Vanilla's final density there is its slides round
 * {@code minecraft:end/sloped_cheese}, the island density {@code end/islands}
 * plus {@code end/base_3d_noise}; the terrain replaces that reference with
 * {@code <prefix>/sloped_cheese} over {@code <prefix>/islands}, and the
 * router's {@code erosion}, which the End's biome source reads as the island
 * shape, with {@code <prefix>/islands} too, so highlands, midlands and barrens
 * follow the shaped islands.
 *
 * <p>{@code <prefix>/islands} is vanilla's island density (without the central
 * dome when the definition removes it) plus {@code coverage}; a layout blends it
 * into islands of the terrain's own where its shape is positive and clears
 * vanilla's where it is negative ({@link #laidOut}). {@code peaks} adds ridges where
 * the island density lies above 0; {@code islandHeight} starts the top slide
 * that many blocks higher, so the island tops reach higher.
 */
final class EndTerrain {

    static final String SLOPED_CHEESE = "minecraft:end/sloped_cheese";
    static final String ISLANDS = "minecraft:end/islands";
    static final String BASE_3D_NOISE = "minecraft:end/base_3d_noise";
    /** Island density a layout at strength 1 takes off where its shape is -1: enough to clear a whole island. */
    static final double LAYOUT = 0.4;
    /** The {@code islands} noise's frequency under a layout: islands about 80 blocks across (about 128 at 1). */
    static final double OWN_ISLAND_FREQUENCY = 1.6;
    /** A layout's own island density per standard deviation of its noise, and the offset under it: about 40% of the ground is island. */
    static final double OWN_ISLAND_DENSITY = 0.5, OWN_ISLAND_OFFSET = -0.12;
    /** Density a ridge adds at {@code peaks} 1: about 30 blocks of crest against the top slide. */
    static final double PEAK_DENSITY = 3;
    /** Island density over which a ridge rises from an island's edge to its full height. */
    static final double PEAK_INLAND = 0.25;
    /** The top slide's starting height in vanilla's End, which {@code islandHeight} raises. */
    static final int TOP_SLIDE_FROM = 56;

    private EndTerrain() {
    }

    /**
     * {@code settings} (a copy of the end preset's) with the shaped islands in its final density and router
     * erosion; {@code centralIsland} false builds on the outer islands alone.
     */
    static TerrainLibrary.Compiled compile(String slug, String namespace, String name, TerrainSpec spec, String preset,
                                           JsonObject settings, boolean centralIsland) {
        if (!"end".equals(preset)) {
            throw new IllegalArgumentException(slug + ": terrain.base end shapes the end preset's islands, not " + preset);
        }
        check(slug, spec);
        String prefix = namespace + ":terrain/" + name;
        Ctx ctx = new Ctx(spec, namespace, prefix, 0, 0, 128);
        JsonElement base = centralIsland ? ref(ISLANDS) : type("end_outer_islands");
        JsonElement islands = base;
        if (ctx.p("coverage") != 0) islands = add(islands, ctx.p("coverage"));
        TerrainShaping.Layout layout = spec.shaping().layout();
        if (layout != null) islands = laidOut(ctx, islands, layout);
        Map<String, JsonObject> functions = new LinkedHashMap<>();
        functions.put("islands", cache(islands));
        JsonElement cheese = add(ref(prefix + "/islands"), ref(BASE_3D_NOISE));
        if (ctx.p("peaks") > 0) {
            double w = ctx.p("peakWidth") * Noises.sigma("detail") / 0.3;
            JsonElement ridge = spline(abs(ctx.noise("detail", 1, 0)), 0, 1, w, 0);
            JsonElement inland = ramp(ref(prefix + "/islands"), 0, PEAK_INLAND);
            cheese = add(cheese, mul(mul(ridge, inland), PEAK_DENSITY * ctx.p("peaks")));
        }
        functions.put("sloped_cheese", (JsonObject) cheese);
        JsonObject out = settings.deepCopy();
        JsonObject router = out.getAsJsonObject("noise_router");
        JsonElement vanillaDensity = router.get("final_density");
        if (!vanillaDensity.toString().contains("\"" + SLOPED_CHEESE + "\"")) {
            throw new IllegalStateException("end's final density no longer reads " + SLOPED_CHEESE + ": " + vanillaDensity);
        }
        JsonObject finalDensity = TerrainLibrary.replace(vanillaDensity.getAsJsonObject(), Map.of(SLOPED_CHEESE, prefix + "/sloped_cheese"));
        int raise = (int) Math.round(ctx.p("islandHeight"));
        if (raise > 0 && !raiseTopSlide(finalDensity, raise)) {
            throw new IllegalStateException("end's final density no longer has its top slide from y " + TOP_SLIDE_FROM + ": " + vanillaDensity);
        }
        functions.put("final_density", finalDensity);
        router.addProperty("final_density", prefix + "/final_density");
        router.addProperty("erosion", prefix + "/islands");
        out.remove("debug_functions");
        Map<String, JsonObject> files = new TreeMap<>();
        functions.forEach((key, json) -> files.put(TerrainLibrary.dataPath(prefix + "/" + key, "worldgen/density_function"), json));
        for (String noise : ctx.noises()) {
            files.put(TerrainLibrary.dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
        }
        return new TerrainLibrary.Compiled(out, files);
    }

    /** Refuses the fields an End terrain has no use for. */
    private static void check(String slug, TerrainSpec spec) {
        if (spec.heightMultiplier() != 1) {
            throw new IllegalArgumentException(slug + ": terrain.heightMultiplier is for terrains on a sea; end raises its islands with "
                    + "islandHeight and peaks");
        }
        TerrainShaping.Scales s = spec.shaping().scales();
        if (s.continents() != 1 || s.erosion() != 1 || s.ridges() != 1) {
            throw new IllegalArgumentException(slug + ": terrain.scales takes only climate on the end preset: its router has no "
                    + "continents or ridges, and its erosion is the island shape");
        }
    }

    /**
     * {@code islands} under a layout. Where the layout's shape is positive the density blends, by shape times strength,
     * into islands of the terrain's own: {@code coverage} plus the {@code islands} noise, which carries islands where
     * vanilla's empty ring holds its density near -0.84 and has none to raise; where the shape is negative,
     * {@link #LAYOUT} times strength is taken off, clearing vanilla's islands.
     */
    private static JsonElement laidOut(Ctx ctx, JsonElement islands, TerrainShaping.Layout layout) {
        JsonElement shape = Df.cache(ctx.layoutShape());
        JsonElement own = add(mul(ctx.noise("islands", OWN_ISLAND_FREQUENCY, 0), OWN_ISLAND_DENSITY / Noises.sigma("islands")),
                OWN_ISLAND_OFFSET + ctx.p("coverage"));
        JsonElement pull = mul(Df.clamp(shape, 0, 1), layout.strength());
        JsonElement clear = mul(Df.min(shape, Df.c(0)), LAYOUT * layout.strength());
        return add(Df.lerp(pull, islands, own), clear);
    }

    /** Moves the top slide (the y gradient from {@link #TOP_SLIDE_FROM}) up by {@code blocks}; false when there is none. */
    private static boolean raiseTopSlide(JsonElement e, int blocks) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && "minecraft:gradient".equals(o.get("type").getAsString()) && o.has("axis")
                    && "y".equals(o.get("axis").getAsString()) && o.get("from_coordinate").getAsInt() == TOP_SLIDE_FROM) {
                o.addProperty("from_coordinate", TOP_SLIDE_FROM + blocks);
                o.addProperty("to_coordinate", o.get("to_coordinate").getAsInt() + blocks);
                return true;
            }
            boolean found = false;
            for (var kv : o.entrySet()) found |= raiseTopSlide(kv.getValue(), blocks);
            return found;
        }
        if (e.isJsonArray()) {
            boolean found = false;
            for (JsonElement x : e.getAsJsonArray()) found |= raiseTopSlide(x, blocks);
            return found;
        }
        return false;
    }
}
