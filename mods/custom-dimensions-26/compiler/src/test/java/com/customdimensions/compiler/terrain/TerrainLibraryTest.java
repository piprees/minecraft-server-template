package com.customdimensions.compiler.terrain;

import com.customdimensions.compiler.datapack.VanillaData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each library terrain's emitted JSON, its parameter checks and the schema that describes them. */
class TerrainLibraryTest {

    private static final String P = "data/adventure/worldgen/density_function/terrain/t/";
    private static VanillaData vanilla;

    static synchronized VanillaData vanilla() {
        if (vanilla == null) {
            vanilla = new VanillaData(Path.of(System.getProperty("customdimensions.vanilla")));
        }
        return vanilla;
    }

    static TerrainSpec spec(String json) {
        return TerrainSpec.parse("t", JsonParser.parseString(json).getAsJsonObject());
    }

    static TerrainLibrary.Compiled compile(TerrainSpec spec, String preset, Integer sea) {
        return TerrainLibrary.compile("t", "adventure", "t", spec, preset, vanilla().noiseSettings(preset), sea, vanilla());
    }

    static TerrainLibrary.Compiled compile(String json) {
        return compile(spec(json), "overworld", null);
    }

    private static String text(JsonElement e) {
        return e.toString();
    }

    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void everyTerrainEmitsItsRouterFunctionsAndWiresTheRouter(TerrainFamily family) {
        TerrainLibrary.Compiled c = compile("{\"base\": \"" + family.id() + "\"}");
        for (String name : List.of("continents", "erosion", "ridges", "ridges_folded", "temperature", "vegetation", "height",
                "offset", "factor", "jaggedness", "depth", "sloped_cheese", "final_density", "preliminary_surface_level",
                "chunk_surface_level")) {
            assertTrue(c.files().containsKey(P + name + ".json"), name);
        }
        JsonObject router = c.settings().getAsJsonObject("noise_router");
        for (String key : List.of("erosion", "ridges", "temperature", "vegetation", "depth", "final_density", "chunk_surface_level")) {
            assertEquals("adventure:terrain/t/" + key, router.get(key).getAsString(), key);
        }
        String all = text(c.settings()) + c.files().values();
        assertFalse(all.contains("minecraft:overworld/sloped_cheese"), "vanilla's sloped cheese is replaced");
        assertFalse(all.contains("minecraft:overworld/offset"), "vanilla's offset is replaced");
        assertTrue(text(c.files().get(P + "final_density.json")).contains("adventure:terrain/t/sloped_cheese"));
        assertTrue(text(c.files().get(P + "final_density.json")).contains("minecraft:overworld/caves/entrances"), "caves stay vanilla's");
        assertEquals("{\"type\":\"minecraft:cache\",\"input\":{\"type\":\"minecraft:add\",\"left\":{\"type\":\"minecraft:mul\","
                        + "\"left\":\"adventure:terrain/t/height\",\"right\":0.0078125},\"right\":-1.0}}",
                text(c.files().get(P + "offset.json")));
        assertFalse(c.settings().has("debug_functions"));
        for (String noise : Set.of("altitude", "channel", "detail", "gorge", "islands", "plates", "shatter", "spires", "voids", "wear")) {
            String file = "data/adventure/worldgen/noise/terrain/" + noise + ".json";
            assertEquals(all.contains("\"adventure:terrain/" + noise + "\""), c.files().containsKey(file), file);
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void airborneTerrainsOpenTheFloorAndDropAquifers(TerrainFamily family) {
        TerrainLibrary.Compiled c = compile("{\"base\": \"" + family.id() + "\"}");
        String fd = text(c.files().get(P + "final_density.json"));
        assertEquals(family.airborne(), !c.settings().has("aquifers"));
        assertEquals(family.airborne() ? -64 : 63, c.settings().get("sea_level").getAsInt());
        assertEquals(family.airborne(), fd.contains("\"first\":-0.234375"));
        assertEquals(!family.airborne(), fd.contains("\"first\":0.1171875"));
    }

    @Test
    void theSettingsKeepEverythingButTheRouterFromThePreset() {
        JsonObject vanillaSettings = vanilla().noiseSettings("overworld");
        JsonObject ours = compile("{\"base\": \"continents\"}").settings();
        for (String key : List.of("default_block", "default_fluid", "noise", "sea_level", "surface_rule", "spawn_target")) {
            assertEquals(vanillaSettings.get(key), ours.get(key), key);
        }
        assertEquals("adventure:terrain/t/preliminary_surface_level", ours.getAsJsonObject("aquifers").get("surface_level").getAsString());
    }

    @Test
    void routerContinentsPutTheShoreWhereBiomesExpectIt() {
        JsonObject router = compile("{\"base\": \"continents\", \"params\": {\"coast\": -0.3}}").settings().getAsJsonObject("noise_router");
        assertEquals("{\"type\":\"minecraft:add\",\"left\":\"adventure:terrain/t/continents\",\"right\":0.19}",
                text(router.get("continents")));
    }

    @Test
    void scaleStretchesTheClimateAndTheLibraryNoises() {
        TerrainLibrary.Compiled one = compile("{\"base\": \"canyons\"}");
        TerrainLibrary.Compiled two = compile("{\"base\": \"canyons\", \"scale\": 2}");
        assertEquals(0.25, xzScale(one.files().get(P + "continents.json")));
        assertEquals(0.125, xzScale(two.files().get(P + "continents.json")));
        assertEquals(0.125, two.files().get(P + "temperature.json").get("xz_scale").getAsDouble());
        assertTrue(text(two.files().get(P + "height.json")).contains("\"noise\":\"adventure:terrain/gorge\",\"shift_x\":\"minecraft:shift_x\",\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":0.5"));
    }

    @Test
    void largeBiomesTakesItsOwnClimateNoises() {
        TerrainLibrary.Compiled c = compile(spec("{\"base\": \"continents\"}"), "large_biomes", null);
        assertTrue(text(c.files().get(P + "continents.json")).contains("minecraft:continentalness_large"));
    }

    @Test
    void floatingIslandsPresetBorrowsTheOverworldClimate() {
        TerrainLibrary.Compiled c = compile(spec("{\"base\": \"floating_islands\"}"), "floating_islands", null);
        assertTrue(text(c.files().get(P + "continents.json")).contains("minecraft:continentalness"));
        assertEquals(0, c.settings().getAsJsonObject("noise").get("min_y").getAsInt());
        assertEquals(0, c.settings().get("sea_level").getAsInt());
    }

    @Test
    void heightMultiplierScalesHeightAboutSeaLevel() {
        String h = text(compile("{\"base\": \"continents\", \"heightMultiplier\": 2}").files().get(P + "height.json"));
        assertTrue(h.startsWith("{\"type\":\"minecraft:cache\",\"input\":{\"type\":\"minecraft:add\",\"left\":{\"type\":\"minecraft:mul\",\"left\":{\"type\":\"minecraft:add\""));
        assertTrue(h.endsWith("\"right\":-63.0},\"right\":2.0},\"right\":63.0}}"), h);
        String sea = text(compile(spec("{\"base\": \"continents\", \"heightMultiplier\": 2}"), "overworld", 40).files().get(P + "height.json"));
        assertTrue(sea.endsWith("\"right\":-40.0},\"right\":2.0},\"right\":40.0}}"), sea);
    }

    @Test
    void terracesFloorTheHeightIntoSteps() {
        String h = text(compile("{\"base\": \"terraces\", \"params\": {\"stepHeight\": 8, \"plateauHeight\": 150}}").files().get(P + "height.json"));
        assertTrue(h.contains("\"type\":\"minecraft:floor\""));
        assertTrue(h.contains("\"right\":0.125"), "divides by the step");
        assertTrue(h.contains("\"right\":150.0"), "caps at the plateau");
    }

    @Test
    void canyonFloorsStepDownTowardSeaLevelWithPoolsAtTheGradeNoisesLowestShare() {
        TerrainLibrary.Compiled c = compile("{\"base\": \"canyons\", \"params\": {\"depth\": 20, \"cascadeStep\": 10, \"pools\": 0.1}}");
        String h = text(c.files().get(P + "height.json"));
        String bed = text(c.files().get(P + "canyon_bed.json"));
        assertTrue(h.contains("adventure:terrain/gorge") && h.contains("adventure:terrain/t/canyon_bed")
                && h.contains("adventure:terrain/t/canyon_land"), h);
        assertTrue(text(c.files().get(P + "canyon_grade.json")).contains("adventure:terrain/grade"));
        assertTrue(bed.contains("adventure:terrain/t/canyon_land") && bed.contains("adventure:terrain/t/canyon_grade"), bed);
        assertTrue(h.contains("\"type\":\"minecraft:floor\""), "the floor is stepped");
        assertTrue(h.contains("\"right\":0.1}"), "divides by cascadeStep");
        assertTrue(bed.contains("\"right\":-84.0}"), "the upper reaches lie depth below the land, measured from just above sea level 63");
        assertTrue(bed.contains("\"right\":64.0}") && h.contains("\"right\":64.0}"), "floors bottom out just above sea level 63");
        double low = Noises.threshold(0.9, Noises.sigma("grade"));
        assertTrue(low < 0 && h.contains("\"right\":" + Df.c(-low) + "}") && bed.contains("\"right\":" + Df.c(-low) + "}"),
                "pools and the grade both start at the grade noise's lowest tenth: " + low);
        assertTrue(h.contains("\"right\":4.0}"), "pools sink 4 blocks");
        assertTrue(c.files().containsKey("data/adventure/worldgen/noise/terrain/grade.json"));
        assertTrue(text(c.files().get(P + "factor.json")).contains("\"second\":64.0"), "the ground follows the height inside the channel");
    }

    @Test
    void canyonsWithoutPoolsOrGradeKeepEveryFloorDepthBelowTheLand() {
        String none = text(compile("{\"base\": \"canyons\", \"params\": {\"pools\": 0}}").files().get(P + "height.json"));
        assertFalse(none.contains("\"right\":4.0}"), "no pool term without pools");
        String flat = text(compile("{\"base\": \"canyons\", \"params\": {\"grade\": 0}}").files().get(P + "canyon_bed.json"));
        assertTrue(flat.contains("\"right\":0.0}"), "grade 0 takes nothing off the upper floor");
    }

    @Test
    void stepsFloorAHeightIntoFlatLevelsThatNeverRiseAboveIt() {
        String s = TerrainFamily.steps(Df.c(70.5), 64, 4, 0.25).toString();
        assertTrue(s.startsWith("{\"type\":\"minecraft:add\""), s);
        assertTrue(s.contains("{\"type\":\"minecraft:floor\",\"input\":{\"type\":\"minecraft:mul\",\"left\":{\"type\":\"minecraft:add\","
                + "\"left\":70.5,\"right\":-64.0},\"right\":0.25}}"), "levels count steps above the base");
        assertTrue(s.contains("{\"derivative\":0.0,\"location\":0.75,\"value\":0.0}"), "flat for all but the riser");
        assertTrue(s.endsWith("\"right\":4.0},\"right\":64.0}"), "back to blocks above the base: " + s);
    }

    @Test
    void erosionRaisesHillsAboveTheBasinShareQuantileOverARiseSetBySteepness() {
        double sigma = Noises.sigma("wear");
        String rolling = text(compile("{\"base\": \"erosion\", \"params\": {\"basinShare\": 0.5, \"steepness\": 0}}").files().get(P + "height.json"));
        assertTrue(rolling.contains("\"noise\":\"adventure:terrain/wear\""));
        assertTrue(rolling.contains("{\"derivative\":0.0,\"location\":0.0,\"value\":0.0},{\"derivative\":0.0,\"location\":" + (float) (2.5 * sigma) + ",\"value\":1.0}"), rolling);
        String mesas = text(compile("{\"base\": \"erosion\", \"params\": {\"basinShare\": 0.5, \"steepness\": 1}}").files().get(P + "height.json"));
        assertTrue(mesas.contains("{\"derivative\":0.0,\"location\":0.0,\"value\":0.0},{\"derivative\":0.0,\"location\":" + (float) (0.1 * sigma) + ",\"value\":1.0}"), mesas);
        assertTrue(mesas.contains("\"location\":1.5,\"value\":70.0"), "the basin floor lies at landHeight inland");
    }

    @Test
    void shatteredTiltsBlocksBetweenFaultsAndFloorsItsChasms() {
        TerrainLibrary.Compiled c = compile("{\"base\": \"shattered\", \"params\": {\"faultHeight\": 20, \"chasmDepth\": 10, \"crackScale\": 1}}");
        String h = text(c.files().get(P + "height.json"));
        assertTrue(h.contains("adventure:terrain/plates") && h.contains("\"type\":\"minecraft:floor\""), h);
        assertTrue(h.contains("\"right\":20.0"), "the sawtooth rises by faultHeight");
        String cheese = text(c.files().get(P + "sloped_cheese.json"));
        assertTrue(cheese.contains("\"y_scale\":0.25"), "chasms are four times taller than wide");
        assertTrue(cheese.contains("\"right\":53.0"), "the chasm floor is chasmDepth below sea level");
        assertTrue(c.files().containsKey("data/adventure/worldgen/noise/terrain/plates.json"));
    }

    @Test
    void terracesDampVanillas3dNoiseSoTreadsStayFlat() {
        assertTrue(text(compile("{\"base\": \"terraces\"}").files().get(P + "sloped_cheese.json"))
                .contains("{\"type\":\"minecraft:mul\",\"left\":\"minecraft:overworld/base_3d_noise\",\"right\":0.15}"));
    }

    @Test
    void cutsAndAdditionsLandInSlopedCheese() {
        assertTrue(text(compile("{\"base\": \"voids\"}").files().get(P + "sloped_cheese.json")).contains("\"type\":\"minecraft:min\""));
        assertTrue(text(compile("{\"base\": \"shattered\"}").files().get(P + "sloped_cheese.json")).contains("adventure:terrain/shatter"));
        String fi = text(compile("{\"base\": \"floating_islands\"}").files().get(P + "sloped_cheese.json"));
        assertTrue(fi.contains("\"type\":\"minecraft:max\"") && fi.contains("adventure:terrain/islands"));
        assertFalse(text(compile("{\"base\": \"continents\"}").files().get(P + "sloped_cheese.json")).contains("minecraft:min"));
    }

    /** Every parameter at its minimum, then at its maximum, still compiles: spline points stay in order. */
    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void everyParameterCompilesAtBothEndsOfItsRange(TerrainFamily family) {
        for (boolean high : new boolean[] {false, true}) {
            JsonObject params = new JsonObject();
            for (TerrainParam p : family.params()) {
                params.addProperty(p.name(), high ? p.max() : p.min());
            }
            JsonObject terrain = new JsonObject();
            terrain.addProperty("base", family.id());
            terrain.add("params", params);
            terrain.addProperty("scale", high ? 4 : 0.25);
            terrain.addProperty("heightMultiplier", high ? 3 : 0.25);
            compile(TerrainSpec.parse("t", terrain), "overworld", null);
        }
    }

    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void settingsStayInsideTheParameterRanges(TerrainFamily family) {
        assertEquals(List.of("gentle", "default", "extreme"), List.copyOf(family.presets().keySet()));
        family.presets().forEach((setting, values) -> values.forEach((name, v) -> {
            TerrainParam p = family.param(name);
            assertTrue(p != null, family.id() + " " + setting + " sets unknown " + name);
            assertTrue(v >= p.min() && v <= p.max(), family.id() + " " + setting + " " + name + " = " + v);
        }));
        for (String setting : TerrainFamily.SETTINGS) {
            TerrainSpec s = spec("{\"base\": \"" + family.id() + "\", \"setting\": \"" + setting + "\"}");
            compile(s, "overworld", null);
            family.presets().get(setting).forEach((name, v) -> assertEquals(v, s.param(name)));
        }
    }

    // ---- validation ---------------------------------------------------------------------------------

    private static String error(String json) {
        return assertThrows(IllegalArgumentException.class, () -> spec(json)).getMessage();
    }

    @Test
    void nullOrAbsentBaseKeepsThePresetsTerrain() {
        assertNull(spec("{\"base\": null}"));
        assertNull(spec("{}"));
        assertNull(TerrainSpec.parse("t", null));
    }

    @Test
    void badTerrainsAreErrorsNamingTheDefinition() {
        assertEquals("t: terrain.base \"mountains\" is not a library terrain; one of continents, erosion, ridges, terraces, "
                + "canyons, rivers, floating_islands, islands, voids, shattered, spires", error("{\"base\": \"mountains\"}"));
        assertTrue(error("{\"base\": 3}").startsWith("t: terrain.base 3 is not a library terrain"));
        assertEquals("t: terrain.params.depth = 200 is outside [8, 120]", error("{\"base\": \"canyons\", \"params\": {\"depth\": 200}}"));
        assertEquals("t: terrain.params.depth = 1 is outside [8, 120]", error("{\"base\": \"canyons\", \"params\": {\"depth\": 1}}"));
        assertEquals("t: terrain.params.depth must be a number, got \"deep\"", error("{\"base\": \"canyons\", \"params\": {\"depth\": \"deep\"}}"));
        assertTrue(error("{\"base\": \"canyons\", \"params\": {\"peakHeight\": 10}}")
                .startsWith("t: terrain.params.peakHeight is not a parameter of canyons; parameters: coast, seaFloor"));
        assertEquals("t: terrain.scale = 8 is outside [0.25, 4]", error("{\"base\": \"canyons\", \"scale\": 8}"));
        assertEquals("t: terrain.heightMultiplier = 0 is outside [0.25, 3]", error("{\"base\": \"canyons\", \"heightMultiplier\": 0}"));
        assertEquals("t: terrain.setting \"wild\" is not one of gentle, default, extreme", error("{\"base\": \"canyons\", \"setting\": \"wild\"}"));
        assertEquals("t: terrain.params needs a terrain.base", error("{\"base\": null, \"params\": {\"depth\": 20}}"));
        assertEquals("t: terrain.roughness is not a terrain field; fields: base, heightMultiplier, params, scale, setting",
                error("{\"base\": \"canyons\", \"roughness\": 1}"));
        assertEquals("t: terrain.params must be an object", error("{\"base\": \"canyons\", \"params\": 3}"));
    }

    @Test
    void paramsOverrideTheSettingWhichOverridesTheDefault() {
        TerrainSpec s = spec("{\"base\": \"canyons\", \"setting\": \"extreme\", \"params\": {\"width\": 0.2}}");
        assertEquals(36, s.param("depth"));
        assertEquals(0.2, s.param("width"));
        assertEquals(1, s.param("frequency"));
        assertEquals(0.35, s.param("roughness"), "a terrain's own default for a shared parameter");
    }

    @Test
    void ceilingAndEndPresetsRefuseATerrainWithTheReason() {
        for (String preset : List.of("caves", "nether", "end")) {
            String message = assertThrows(IllegalArgumentException.class,
                    () -> compile(spec("{\"base\": \"continents\"}"), preset, null)).getMessage();
            assertEquals("t: terrain.base continents cannot shape preset " + preset + ": " + TerrainLibrary.REFUSED.get(preset), message);
        }
    }

    @Test
    void coverageBecomesANormalQuantile() {
        assertEquals(0, Noises.inverseNormal(0.5), 1e-9);
        assertEquals(1.959964, Noises.inverseNormal(0.975), 1e-5);
        assertEquals(-2.326348, Noises.inverseNormal(0.01), 1e-5);
        assertEquals(0.3 * 0.841621, Noises.threshold(0.2, 0.3), 1e-5);
        assertThrows(IllegalArgumentException.class, () -> Noises.inverseNormal(1));
    }

    // ---- schema ---------------------------------------------------------------------------------

    @Test
    void theSchemaDescribesEveryTerrainAndParameter() throws Exception {
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of("../schema/definition.schema.json"))).getAsJsonObject();
        JsonObject defs = schema.getAsJsonObject("$defs");
        JsonObject generated = TerrainLibrary.schema();
        for (String key : generated.keySet()) {
            assertEquals(generated.get(key), defs.get(key), key);
        }
        Set<String> inSchema = new TreeSet<>();
        defs.keySet().stream().filter(k -> k.startsWith("terrain_")).forEach(inSchema::add);
        assertEquals(new TreeSet<>(generated.keySet()), inSchema);
        JsonObject terrain = schema.getAsJsonObject("properties").getAsJsonObject("terrain").getAsJsonObject("properties");
        List<String> names = new ArrayList<>();
        terrain.getAsJsonObject("base").getAsJsonArray("enum").forEach(e -> names.add(e.isJsonNull() ? null : e.getAsString()));
        List<String> expected = new ArrayList<>();
        expected.add(null);
        TerrainLibrary.families().forEach(f -> expected.add(f.id()));
        assertEquals(expected, names);
        assertEquals(TerrainLibrary.numberSchema(TerrainSpec.SCALE), terrain.get("scale"));
        assertEquals(TerrainLibrary.numberSchema(TerrainSpec.HEIGHT_MULTIPLIER), terrain.get("heightMultiplier"));
    }

    private static double xzScale(JsonObject f) {
        return f.getAsJsonObject("input").get("xz_scale").getAsDouble();
    }

    static Map<String, JsonObject> files(String json) {
        return compile(json).files();
    }
}
