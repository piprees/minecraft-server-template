package com.customdimensions.compiler.terrain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainShapingTest {
    private static final String P = "data/adventure/worldgen/density_function/terrain/t/";

    static JsonObject terrain(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    static double xzScale(JsonObject f) {
        return f.getAsJsonObject("input").get("xz_scale").getAsDouble();
    }

    static TerrainLibrary.Compiled preset(String json, String preset) {
        TerrainShaping s = TerrainSpec.presetShaping("t", terrain(json));
        return TerrainLibrary.shapePreset("t", "adventure", "t", s, preset, TerrainLibraryTest.vanilla().noiseSettings(preset),
                TerrainLibraryTest.vanilla());
    }

    private static String error(String json) {
        return assertThrows(IllegalArgumentException.class, () -> {
            JsonObject t = terrain(json);
            if (t.has("base") && !t.get("base").isJsonNull()) TerrainSpec.parse("t", t);
            else TerrainSpec.presetShaping("t", t);
        }).getMessage();
    }

    @Test
    void scalesMultiplyScalePerRouterNoise() {
        TerrainLibrary.Compiled c = TerrainLibraryTest.compile("{\"base\": \"canyons\", \"scale\": 2, "
                + "\"scales\": {\"continents\": 1.5, \"erosion\": 2, \"ridges\": 0.5, \"climate\": 4}}");
        assertEquals(0.25 / 3, xzScale(c.files().get(P + "continents.json")), 1e-12);
        assertEquals(0.25 / 4, xzScale(c.files().get(P + "erosion.json")), 1e-12);
        assertEquals(0.25, xzScale(c.files().get(P + "ridges.json")), 1e-12);
        assertEquals(0.25 / 8, c.files().get(P + "temperature.json").get("xz_scale").getAsDouble(), 1e-12);
        assertEquals(0.25 / 8, c.files().get(P + "vegetation.json").get("xz_scale").getAsDouble(), 1e-12);
        assertTrue(c.files().get(P + "height.json").toString().contains("\"noise\":\"adventure:terrain/gorge\",\"shift_x\":\"minecraft:shift_x\","
                + "\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":0.5"), "library noises follow scale only");
    }

    @Test
    void aShapedPresetsBiomesReadTheDepthBelowItsTop() {
        TerrainLibrary.Compiled c = preset("{\"scales\": {\"climate\": 3}}", "overworld");
        assertEquals("adventure:terrain/t/biome_depth", c.settings().getAsJsonObject("noise_router").get("depth").getAsString());
        String top = c.files().get(P + "surface_top.json").toString();
        assertTrue(top.contains("minecraft:find_top_surface") && top.contains("adventure:terrain/t/offset")
                && top.contains("adventure:terrain/t/sloped_cheese") && !top.contains("interpolated"), top);
        assertTrue(c.files().get(P + "sloped_cheese.json").toString().contains("adventure:terrain/t/depth"), "the shape keeps the nominal depth");
    }

    @Test
    void absentShapingChangesNothing() {
        TerrainLibrary.Compiled plain = TerrainLibraryTest.compile("{\"base\": \"ridges\"}");
        TerrainLibrary.Compiled ones = TerrainLibraryTest.compile("{\"base\": \"ridges\", \"scales\": {\"continents\": 1, \"erosion\": 1, "
                + "\"ridges\": 1, \"climate\": 1}, \"layout\": {\"type\": \"none\"}}");
        assertEquals(plain.files(), ones.files());
        assertEquals(plain.settings(), ones.settings());
        assertNull(TerrainSpec.presetShaping("t", terrain("{}")));
        assertNull(TerrainSpec.presetShaping("t", terrain("{\"scales\": {\"erosion\": 1}}")), "a shaping of ones is none");
        assertNull(TerrainSpec.presetShaping("t", terrain("{\"base\": \"ridges\", \"scales\": {\"erosion\": 2}}")), "a base takes it");
        assertNull(TerrainSpec.presetShaping("t", null));
    }

    @Test
    void aRadialLayoutAddsAContinentToTheContinentalness() {
        TerrainLibrary.Compiled c = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"radial\", "
                + "\"centre\": [100, -50], \"radius\": 600, \"falloff\": 300, \"strength\": 0.4}}");
        JsonObject cont = c.files().get(P + "continents.json");
        assertEquals("minecraft:cache", cont.get("type").getAsString());
        JsonObject add = cont.getAsJsonObject("input");
        assertEquals("minecraft:add", add.get("type").getAsString());
        assertEquals(0.25, xzScale(add.getAsJsonObject("left")), "the noise is kept: the layout adds to it");
        String shape = add.getAsJsonObject("right").toString();
        assertTrue(shape.contains("\"right\":0.4"), shape);
        assertTrue(shape.contains("{\"derivative\":0.0,\"location\":600.0,\"value\":1.0}"), shape);
        assertTrue(shape.contains("{\"derivative\":0.0,\"location\":900.0,\"value\":-1.0}"), shape);
        assertTrue(shape.contains("\"from_coordinate\":-999900") && shape.contains("\"from_coordinate\":-1000050"), "centred on 100, -50: " + shape);
        assertTrue(c.files().get(P + "offset.json").toString().contains("adventure:terrain/t/height"));
    }

    /** Every distance-from-centre ({@code minecraft:sqrt}) in {@code files} as JSON text. */
    private static List<String> distances(java.util.Map<String, JsonObject> files) {
        List<String> out = new java.util.ArrayList<>();
        files.values().forEach(f -> collect(f, out));
        return out;
    }

    private static void collect(com.google.gson.JsonElement e, List<String> out) {
        if (e.isJsonArray()) e.getAsJsonArray().forEach(v -> collect(v, out));
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("type") && "minecraft:sqrt".equals(o.get("type").getAsString())) out.add(o.toString());
        o.entrySet().forEach(kv -> collect(kv.getValue(), out));
    }

    @Test
    void aWarpedLayoutIsReadAtTheWarpedPointAndShipsItsNoises() {
        TerrainLibrary.Compiled c = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"ring\", \"radius\": 700, "
                + "\"falloff\": 200, \"warp\": {\"blocks\": 60, \"wavelength\": 512}}}");
        String ring = c.files().get(P + "continents.json").toString();
        assertTrue(ring.contains("adventure:terrain/warp_x") && ring.contains("adventure:terrain/warp_z"), ring);
        assertTrue(ring.contains("\"xz_scale\":0.5"), "256 / 512: " + ring);
        assertTrue(ring.contains("\"right\":109.489051"), "two deviations (0.274 each) are 60 blocks: " + ring);
        assertTrue(c.files().containsKey("data/adventure/worldgen/noise/terrain/warp_x.json"), c.files().keySet().toString());
        String still = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"ring\", \"radius\": 700, \"falloff\": 200}}")
                .files().get(P + "continents.json").toString();
        assertFalse(still.contains("warp_x"), "no warp, the shape is read where it stands: " + still);
        String linear = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"linear\", \"direction\": 90, "
                + "\"falloff\": 1000, \"warp\": {\"blocks\": 40}}}").files().get(P + "continents.json").toString();
        assertTrue(linear.contains("warp_z") && linear.contains("\"xz_scale\":0.5"), "the default wavelength is 512: " + linear);
    }

    @Test
    void aWarpedRingMovesItsHollowAndItsCoverageAlike() {
        String hollow = "{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 30, \"basinDepth\": 8, \"rimHeight\": 4}, "
                + "\"layout\": {\"type\": \"ring\", \"radius\": 400, \"falloff\": 160, \"strength\": 0.8, \"warp\": {\"blocks\": 70, \"wavelength\": 600}}}";
        List<String> d = distances(TerrainLibraryTest.compile(hollow).files());
        assertTrue(d.size() >= 2, "the coverage shift and the hollow's lake both read the distance: " + d.size());
        for (String s : d) assertTrue(s.contains("adventure:terrain/warp_x"), "every distance from the ring's centre is warped: " + s);
        for (String s : distances(TerrainLibraryTest.compile(hollow.replace(", \"warp\": {\"blocks\": 70, \"wavelength\": 600}", "")).files())) {
            assertFalse(s.contains("warp_x"), s);
        }
    }

    @Test
    void aLayoutWarpIsCappedWhereItCouldSplitTheShape() {
        assertEquals("t: terrain.layout.warp moves 77 blocks over a 512-block wavelength; above 0.15 of the wavelength the warp can fold the map "
                + "and split the ring's shape", error("{\"layout\": {\"type\": \"ring\", \"radius\": 700, \"warp\": {\"blocks\": 77, \"wavelength\": 512}}}"));
        assertTrue(error("{\"layout\": {\"type\": \"ring\", \"radius\": 700, \"warp\": {\"size\": 7}}}")
                .startsWith("t: terrain.layout.warp.size is not a field"));
        assertEquals("t: terrain.layout.warp.wavelength = 32 is outside [64, 4096]",
                error("{\"layout\": {\"type\": \"radial\", \"radius\": 300, \"warp\": {\"blocks\": 4, \"wavelength\": 32}}}"));
        TerrainSpec.presetShaping("t", terrain("{\"layout\": {\"type\": \"ring\", \"radius\": 700, \"warp\": {\"blocks\": 76, \"wavelength\": 512}}}"));
    }

    @Test
    void ringAndLinearLayouts() {
        String ring = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"ring\", \"radius\": 700, \"falloff\": 200}}")
                .files().get(P + "continents.json").toString();
        assertTrue(ring.contains("\"location\":500.0,\"value\":-1.0") && ring.contains("\"location\":700.0,\"value\":1.0")
                && ring.contains("\"location\":900.0,\"value\":-1.0"), ring);
        assertTrue(ring.contains("\"right\":0.5"), "strength defaults to 0.5: " + ring);
        String linear = TerrainLibraryTest.compile("{\"base\": \"continents\", \"layout\": {\"type\": \"linear\", \"direction\": 90, "
                + "\"falloff\": 1000, \"strength\": 1}}").files().get(P + "continents.json").toString();
        assertTrue(linear.contains("\"location\":-500.0,\"value\":-1.0") && linear.contains("\"location\":500.0,\"value\":1.0"), linear);
        assertTrue(linear.contains("\"axis\":\"z\"") && linear.contains("\"axis\":\"x\""), linear);
        assertTrue(linear.contains("\"right\":1.0"), "sin 90 on z: " + linear);
    }

    @Test
    void aShapedPresetCopiesVanillasLandOntoStretchedNoises() {
        TerrainLibrary.Compiled c = preset("{\"scales\": {\"continents\": 1.3, \"erosion\": 2, \"ridges\": 1.4, \"climate\": 1.5}}", "overworld");
        assertEquals(0.25 / 1.3, xzScale(c.files().get(P + "continents.json")), 1e-12);
        assertEquals(0.25 / 2, xzScale(c.files().get(P + "erosion.json")), 1e-12);
        for (String n : List.of("offset", "factor", "jaggedness", "depth", "sloped_cheese", "final_density", "preliminary_surface_level",
                "chunk_surface_level", "ridges_folded")) {
            String f = c.files().get(P + n + ".json").toString();
            assertFalse(f.contains("\"minecraft:overworld/continents\"") || f.contains("\"minecraft:overworld/erosion\"")
                    || f.contains("\"minecraft:overworld/ridges_folded\"") || f.contains("\"minecraft:overworld/offset\""), n + " reads vanilla's: " + f);
        }
        assertTrue(c.files().get(P + "offset.json").toString().contains("adventure:terrain/t/continents"));
        JsonObject router = c.settings().getAsJsonObject("noise_router");
        for (String key : List.of("continents", "erosion", "ridges", "temperature", "vegetation", "depth", "final_density", "chunk_surface_level")) {
            assertEquals("adventure:terrain/t/" + (key.equals("depth") ? "biome_depth" : key), router.get(key).getAsString(), key);
        }
        JsonObject vanilla = TerrainLibraryTest.vanilla().noiseSettings("overworld");
        for (String key : List.of("noise", "sea_level", "default_block", "surface_rule")) {
            if (vanilla.has(key)) assertEquals(vanilla.get(key), c.settings().get(key), key);
        }
        assertEquals("adventure:terrain/t/preliminary_surface_level", c.settings().getAsJsonObject("aquifers").get("surface_level").getAsString(),
                "aquifers read the shaped land");
    }

    @Test
    void aShapedAmplifiedPresetKeepsItsOwnLand() {
        TerrainLibrary.Compiled c = preset("{\"scales\": {\"erosion\": 2}}", "amplified");
        String vanillaFactor = TerrainLibraryTest.vanilla().densityFunction("overworld_amplified/factor").toString()
                .replace("minecraft:overworld/", "adventure:terrain/t/").replace("minecraft:overworld_amplified/", "adventure:terrain/t/");
        assertEquals(vanillaFactor, c.files().get(P + "factor.json").toString());
    }

    @Test
    void shapingIsRefusedWhereItCannotApply() {
        String m = assertThrows(IllegalArgumentException.class, () -> preset("{\"scales\": {\"erosion\": 2}}", "nether")).getMessage();
        assertEquals("t: terrain.scales and terrain.layout shape the presets amplified, large_biomes, overworld without a terrain.base, not nether", m);
        assertEquals("t: terrain.scales.width is not a scale; scales: continents, erosion, ridges, climate", error("{\"scales\": {\"width\": 2}}"));
        assertEquals("t: terrain.scales.erosion = 5 is outside [0.25, 4]", error("{\"base\": \"ridges\", \"scales\": {\"erosion\": 5}}"));
        assertEquals("t: terrain.scales must be an object", error("{\"scales\": 2}"));
        assertEquals("t: terrain.layout.type must be one of radial, ring, linear, none", error("{\"layout\": {\"type\": \"spiral\"}}"));
        assertEquals("t: terrain.layout.radius is required for radial", error("{\"layout\": {\"type\": \"radial\"}}"));
        assertEquals("t: terrain.layout.radius is for radial and ring; linear takes direction",
                error("{\"layout\": {\"type\": \"linear\", \"radius\": 3}}"));
        assertEquals("t: terrain.layout.direction is for linear; ring takes radius",
                error("{\"layout\": {\"type\": \"ring\", \"radius\": 300, \"direction\": 3}}"));
        assertEquals("t: terrain.layout.strength = 2 is outside [0, 1]", error("{\"layout\": {\"type\": \"radial\", \"radius\": 300, \"strength\": 2}}"));
        assertEquals("t: terrain.layout.falloff = 0 is outside [16, 8192]", error("{\"layout\": {\"type\": \"radial\", \"radius\": 300, \"falloff\": 0}}"));
        assertEquals("t: terrain.layout.centre must be [x, z]", error("{\"layout\": {\"type\": \"radial\", \"radius\": 300, \"centre\": [1]}}"));
        assertTrue(error("{\"layout\": {\"type\": \"radial\", \"radius\": 300, \"size\": 1}}")
                .startsWith("t: terrain.layout.size is not a layout field"));
        assertEquals("t: terrain.heightMultiplier needs a terrain.base", error("{\"heightMultiplier\": 2, \"scales\": {\"erosion\": 2}}"));
    }
}
