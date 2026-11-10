package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.customdimensions.compiler.terrain.Df.abs;
import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.c;
import static com.customdimensions.compiler.terrain.Df.cache;
import static com.customdimensions.compiler.terrain.Df.clamp;
import static com.customdimensions.compiler.terrain.Df.lerp;
import static com.customdimensions.compiler.terrain.Df.max;
import static com.customdimensions.compiler.terrain.Df.min;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.negY;
import static com.customdimensions.compiler.terrain.Df.ramp;
import static com.customdimensions.compiler.terrain.Df.ref;
import static com.customdimensions.compiler.terrain.Df.spline;

/**
 * {@link TerrainFamily#ROOFED}: a floor and a ceiling with open space
 * between, on the nether or caves preset's own router. Vanilla's final
 * density there is its slides (the bedrock floor and roof) round
 * {@code minecraft:nether/base_3d_noise}; the terrain replaces only that
 * reference with {@code <prefix>/cavern}, so the preset's biomes, sea and
 * fluid, slides and squeeze stay vanilla's.
 *
 * <p>{@code cavern} is solid below {@code <prefix>/floor} and above
 * {@code <prefix>/ceiling}, {@link #SLOPE} per block, plus vanilla's 3D noise
 * weighed by {@code roughness}. The floor is {@code floorHeight} with
 * {@code floorRelief} of rolling relief (none over the {@code flats} share),
 * then each feature term lowers it: a {@code pit} to a flat floor
 * {@code pitDepth} under {@code floorHeight}, a {@code corridor} to a flat bed
 * {@code corridorDepth} under it, a {@code chasm} {@code chasmDepth} under the
 * floor round it; {@code ledges} then step it into shelves. {@code pillars}
 * are solid from the world's floor to its roof. The router's {@code depth}
 * is {@code (floorHeight - y) / 128}, so a listed biome with a
 * {@code climate.depth} box takes the low floors of pits and chasms.
 */
final class RoofedTerrain {

    /** Presets a roofed terrain is built on: their final density is slides round the Nether's 3D noise. */
    static final Set<String> PRESETS = Set.of("nether", "caves");
    static final String BASE_3D_NOISE = "minecraft:nether/base_3d_noise";
    /** Density per block of distance below the floor or above the ceiling. */
    static final double SLOPE = 1 / 8.0;
    /** Weight of vanilla's 3D noise at roughness 1: about 24 blocks of overhang and shards. */
    static final double NOISE = 3;
    /** Standard deviations a layout at strength 1 moves the pit noise by. */
    static final double PIT_LAYOUT = 3;
    /** Standard deviations over which a pit's wall falls from the rim to its floor. */
    static final double PIT_WALL = 0.6;
    /** The corridor noise's frequency against its base: long, gently winding passes. */
    static final double CORRIDOR_FREQUENCY = 0.5;
    /** Blocks of depth that read as one unit of the router's depth, as vanilla's overworld. */
    static final double DEPTH_BLOCKS = 128;
    /** Blocks a floor must keep from the preset's floor and roof. */
    static final int MARGIN = 4;

    private RoofedTerrain() {
    }

    /** {@code settings} (a copy of the preset's) with the cavern in its final density and the floor-relative depth in its router. */
    static TerrainLibrary.Compiled compile(String slug, String namespace, String name, TerrainSpec spec, String preset,
                                           JsonObject settings, Integer seaLevel) {
        if (!PRESETS.contains(preset)) {
            throw new IllegalArgumentException(slug + ": terrain.base roofed shapes the caves and nether presets, a floor under a ceiling, not "
                    + preset);
        }
        check(slug, spec, settings);
        String prefix = namespace + ":terrain/" + name;
        int minY = settings.getAsJsonObject("noise").get("min_y").getAsInt();
        int maxY = minY + settings.getAsJsonObject("noise").get("height").getAsInt();
        double sea = seaLevel != null ? seaLevel : settings.get("sea_level").getAsInt();
        Ctx ctx = new Ctx(spec, namespace, prefix, sea, minY, maxY);
        JsonElement floor = ctx.named("floor", floor(ctx, spec.shaping()));
        JsonElement ceiling = ctx.named("ceiling", ceiling(ctx));
        JsonElement y = negY(minY, maxY);
        JsonElement shape = mul(max(add(floor, y), mul(add(ceiling, y), -1)), SLOPE);
        JsonElement cavern = ctx.p("roughness") > 0 ? add(shape, mul(ref(BASE_3D_NOISE), NOISE * ctx.p("roughness"))) : shape;
        if (ctx.p("pillars") > 0) {
            cavern = max(cavern, pillars(ctx));
        }
        Map<String, JsonObject> functions = new LinkedHashMap<>();
        ctx.namedFunctions().forEach((key, f) -> functions.put(key, f.getAsJsonObject()));
        functions.put("cavern", cache(cavern));
        JsonObject out = settings.deepCopy();
        JsonObject router = out.getAsJsonObject("noise_router");
        JsonElement vanillaDensity = router.get("final_density");
        if (!vanillaDensity.toString().contains("\"" + BASE_3D_NOISE + "\"")) {
            throw new IllegalStateException(preset + "'s final density no longer reads " + BASE_3D_NOISE + ": " + vanillaDensity);
        }
        functions.put("final_density", TerrainLibrary.replace(vanillaDensity.getAsJsonObject(), Map.of(BASE_3D_NOISE, prefix + "/cavern")));
        functions.put("depth", cache(add(mul(y, 1 / DEPTH_BLOCKS), ctx.p("floorHeight") / DEPTH_BLOCKS)));
        router.addProperty("final_density", prefix + "/final_density");
        router.addProperty("depth", prefix + "/depth");
        out.remove("debug_functions");
        Map<String, JsonObject> files = new TreeMap<>();
        functions.forEach((key, json) -> files.put(TerrainLibrary.dataPath(prefix + "/" + key, "worldgen/density_function"), json));
        for (String noise : ctx.noises()) {
            files.put(TerrainLibrary.dataPath(namespace + ":terrain/" + noise, "worldgen/noise"), Noises.json(noise));
        }
        return new TerrainLibrary.Compiled(out, files);
    }

    /** Refuses fields a roofed terrain has no use for and heights the preset cannot hold. */
    private static void check(String slug, TerrainSpec spec, JsonObject settings) {
        if (spec.heightMultiplier() != 1) {
            throw new IllegalArgumentException(slug + ": terrain.heightMultiplier is for terrains on a sea; roofed sets its heights with "
                    + "floorRelief, ceilingGap and each feature's depth");
        }
        TerrainShaping.Scales s = spec.shaping().scales();
        if (s.continents() != 1 || s.erosion() != 1 || s.ridges() != 1) {
            throw new IllegalArgumentException(slug + ": terrain.scales takes only climate under a roof: the preset's router has no "
                    + "continents, erosion or ridges to stretch");
        }
        if (spec.shaping().layout() != null && spec.param("pit") <= 0) {
            throw new IllegalArgumentException(slug + ": terrain.layout places a roofed terrain's pits; set terrain.params.pit above 0");
        }
        int minY = settings.getAsJsonObject("noise").get("min_y").getAsInt();
        int maxY = minY + settings.getAsJsonObject("noise").get("height").getAsInt();
        double floor = spec.param("floorHeight");
        if (floor < minY + MARGIN || floor > maxY - MARGIN - 16) {
            throw new IllegalArgumentException(slug + ": terrain.params.floorHeight " + TerrainSpec.fmt(floor) + " leaves no cavern in y "
                    + minY + " to " + maxY + "; keep it from " + (minY + MARGIN) + " to " + (maxY - MARGIN - 16));
        }
        double relief = spec.param("floorRelief");
        double gap = spec.param("ceilingGap");
        if (gap < 1.5 * relief + 8) {
            throw new IllegalArgumentException(slug + ": terrain.params.ceilingGap " + TerrainSpec.fmt(gap) + " lets the ceiling meet the floor; "
                    + "it needs at least 1.5 times floorRelief plus 8, " + TerrainSpec.fmt(1.5 * relief + 8));
        }
    }

    /** The floor in blocks: the relief, then pits, corridors and chasms lowering it, then the ledges stepping it. */
    private static JsonElement floor(Ctx ctx, TerrainShaping shaping) {
        double h = ctx.p("floorHeight");
        double relief = ctx.p("floorRelief");
        JsonElement f = c(h);
        if (relief > 0) {
            JsonElement wave = clamp(add(mul(ctx.noise("altitude", 1, 0), 0.75 / (2 * Noises.sigma("altitude"))),
                    mul(ctx.noise("detail", 1, 0), 0.25 / (2 * Noises.sigma("detail")))), -1, 1);
            if (ctx.p("flats") > 0) {
                double sigma = Noises.sigma("wear");
                double t = Noises.threshold(1 - ctx.p("flats"), sigma);
                wave = mul(wave, ramp(ctx.noise("wear", 1, 0), t, t + 0.5 * sigma));
            }
            f = add(mul(wave, relief), h);
        }
        if (ctx.p("pit") > 0) {
            JsonElement field = mul(ctx.noise("lakes", 1 / ctx.p("pitSize"), 0), 1 / Noises.sigma("lakes"));
            if (shaping.layout() != null) {
                field = add(field, mul(TerrainShaping.shape(shaping.layout()), PIT_LAYOUT * shaping.layout().strength()));
            }
            double t = Noises.inverseNormal(1 - ctx.p("pit"));
            JsonElement wall = spline(field, t, 0, t + PIT_WALL, 1);
            f = lerp(wall, f, min(f, c(h - ctx.p("pitDepth"))));
        }
        if (ctx.p("corridors") > 0) {
            double w = ctx.p("corridors");
            JsonElement channel = abs(ctx.noise("channel", CORRIDOR_FREQUENCY, 0));
            f = lerp(spline(channel, 0, 1, w, 1, 2 * w, 0), f, min(f, c(h - ctx.p("corridorDepth"))));
        }
        if (ctx.p("chasms") > 0) {
            double w = ctx.p("chasms");
            JsonElement gorge = abs(ctx.noise("gorge", 1, 0));
            f = lerp(spline(gorge, 0, 1, 0.6 * w, 1, w, 0), f, add(f, -ctx.p("chasmDepth")));
        }
        if (ctx.p("ledges") > 0) {
            f = TerrainFamily.steps(f, h, ctx.p("ledgeStep"), 1 - 0.9 * ctx.p("ledges"));
        }
        return f;
    }

    /** The ceiling: {@code ceilingGap} over {@code floorHeight}, rising and falling by half the floor's relief. */
    private static JsonElement ceiling(Ctx ctx) {
        double top = ctx.p("floorHeight") + ctx.p("ceilingGap");
        double relief = ctx.p("floorRelief");
        if (relief <= 0) return c(top);
        return add(mul(clamp(mul(ctx.noise("plates", 1, 0), 1 / (2 * Noises.sigma("plates"))), -1, 1), 0.5 * relief), top);
    }

    /** Solid columns where the pillar noise lies in its top {@code pillars} share, half a density unit in at the edge. */
    private static JsonElement pillars(Ctx ctx) {
        double sigma = Noises.sigma("spires");
        double t = Noises.threshold(ctx.p("pillars"), sigma);
        JsonElement column = ramp(ctx.noise("spires", 1 / ctx.p("pillarSize"), 0), t, t + 0.3 * sigma);
        return mul(add(column, -0.5), 4);
    }

    /** The lowest and highest floor and the ceiling, in blocks, and what lies under the sea, for the compile report. */
    static String describe(TerrainSpec spec, double sea, String fluid) {
        double h = spec.param("floorHeight");
        double relief = spec.param("floorRelief");
        double low = h - relief;
        StringBuilder sb = new StringBuilder("floor at y " + TerrainSpec.fmt(h) + " ± " + TerrainSpec.fmt(relief) + ", ceiling at y "
                + TerrainSpec.fmt(h + spec.param("ceilingGap")) + " ± " + TerrainSpec.fmt(relief / 2));
        StringBuilder under = new StringBuilder();
        if (low < sea) under.append(", the floor's hollows below y ").append(TerrainSpec.fmt(sea));
        if (spec.param("pit") > 0 && h - spec.param("pitDepth") < sea) {
            under.append(", pit floors at y ").append(TerrainSpec.fmt(h - spec.param("pitDepth")));
        }
        if (spec.param("corridors") > 0 && h - spec.param("corridorDepth") < sea) {
            under.append(", corridor beds at y ").append(TerrainSpec.fmt(h - spec.param("corridorDepth")));
        }
        if (spec.param("chasms") > 0 && low - spec.param("chasmDepth") < sea) {
            under.append(", chasm floors from y ").append(TerrainSpec.fmt(low - spec.param("chasmDepth")));
        }
        sb.append(under.isEmpty() ? "; the floor stays above the " + fluid + " sea at y " + TerrainSpec.fmt(sea)
                : "; under the " + fluid + " sea at y " + TerrainSpec.fmt(sea) + ":" + under.substring(1));
        return sb.toString();
    }
}
