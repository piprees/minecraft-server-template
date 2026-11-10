package com.customdimensions.compiler.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The roofed terrain's compiled density functions, sampled at columns whose library noises are given. */
class RoofedTerrainTest {

    private static final String P = "data/adventure/worldgen/density_function/terrain/t/";
    private static final String NS = "adventure:terrain/";

    private static TerrainLibrary.Compiled compile(String json, String preset) {
        return TerrainLibraryTest.compile(TerrainLibraryTest.spec(json), preset, null);
    }

    private static TerrainLibrary.Compiled nether(String params) {
        return compile("{\"base\": \"roofed\", \"params\": {" + params + "}}", "nether");
    }

    /** A column: each library noise's value (absent ones 0), vanilla's 3D noise and the block x, z. */
    private record Column(Map<String, Double> noises, double base3d, double x, double z) {
        Column(Map<String, Double> noises) {
            this(noises, 0, 0, 0);
        }
    }

    /** {@code f} at height {@code y} in {@code column}; ids resolve to the compiled files. */
    private static double eval(TerrainLibrary.Compiled c, JsonElement f, Column column, double y) {
        if (f.isJsonPrimitive() && f.getAsJsonPrimitive().isNumber()) return f.getAsDouble();
        if (f.isJsonPrimitive()) {
            String id = f.getAsString();
            if (id.equals(RoofedTerrain.BASE_3D_NOISE)) return column.base3d();
            JsonObject file = c.files().get(TerrainLibrary.dataPath(id, "worldgen/density_function"));
            if (file == null) throw new IllegalArgumentException("no function " + id);
            return eval(c, file, column, y);
        }
        JsonObject o = f.getAsJsonObject();
        String type = o.get("type").getAsString();
        return switch (type) {
            case "minecraft:add" -> eval(c, o.get("left"), column, y) + eval(c, o.get("right"), column, y);
            case "minecraft:sub" -> eval(c, o.get("left"), column, y) - eval(c, o.get("right"), column, y);
            case "minecraft:mul" -> eval(c, o.get("left"), column, y) * eval(c, o.get("right"), column, y);
            case "minecraft:min" -> Math.min(eval(c, o.get("left"), column, y), eval(c, o.get("right"), column, y));
            case "minecraft:max" -> Math.max(eval(c, o.get("left"), column, y), eval(c, o.get("right"), column, y));
            case "minecraft:abs" -> Math.abs(eval(c, o.get("input"), column, y));
            case "minecraft:sqrt" -> Math.sqrt(eval(c, o.get("input"), column, y));
            case "minecraft:square" -> Math.pow(eval(c, o.get("input"), column, y), 2);
            case "minecraft:floor" -> Math.floor(eval(c, o.get("input"), column, y));
            case "minecraft:cache" -> eval(c, o.get("input"), column, y);
            case "minecraft:clamp" -> Math.max(o.get("min").getAsDouble(), Math.min(o.get("max").getAsDouble(), eval(c, o.get("input"), column, y)));
            case "minecraft:lerp" -> {
                double a = eval(c, o.get("alpha"), column, y);
                yield eval(c, o.get("first"), column, y) + a * (eval(c, o.get("second"), column, y) - eval(c, o.get("first"), column, y));
            }
            case "minecraft:noise" -> column.noises().getOrDefault(o.get("noise").getAsString(), 0.0);
            case "minecraft:gradient" -> {
                String axis = o.get("axis").getAsString();
                double v = axis.equals("y") ? y : axis.equals("x") ? column.x() : column.z();
                double from = o.get("from_coordinate").getAsDouble(), to = o.get("to_coordinate").getAsDouble();
                double t = Math.max(0, Math.min(1, (v - from) / (to - from)));
                yield o.get("from_value").getAsDouble() + t * (o.get("to_value").getAsDouble() - o.get("from_value").getAsDouble());
            }
            case "minecraft:spline" -> spline(c, o.getAsJsonObject("spline"), column, y);
            default -> throw new IllegalArgumentException("unexpected " + type);
        };
    }

    /** Vanilla's spline with derivatives 0: smoothstep between points, held beyond the ends. */
    private static double spline(TerrainLibrary.Compiled c, JsonObject spline, Column column, double y) {
        double x = eval(c, spline.get("coordinate"), column, y);
        JsonArray points = spline.getAsJsonArray("points");
        int n = points.size();
        if (x <= loc(points, 0)) return val(points, 0);
        if (x >= loc(points, n - 1)) return val(points, n - 1);
        for (int i = 0; i + 1 < n; i++) {
            if (x <= loc(points, i + 1)) {
                double t = (x - loc(points, i)) / (loc(points, i + 1) - loc(points, i));
                return val(points, i) + (val(points, i + 1) - val(points, i)) * t * t * (3 - 2 * t);
            }
        }
        throw new IllegalStateException();
    }

    private static double loc(JsonArray points, int i) {
        return points.get(i).getAsJsonObject().get("location").getAsDouble();
    }

    private static double val(JsonArray points, int i) {
        return points.get(i).getAsJsonObject().get("value").getAsDouble();
    }

    private static JsonObject file(TerrainLibrary.Compiled c, String name) {
        JsonObject f = c.files().get(P + name + ".json");
        if (f == null) throw new AssertionError("no " + name + " in " + new TreeSet<>(c.files().keySet()));
        return f;
    }

    private static double floor(TerrainLibrary.Compiled c, Column column) {
        return eval(c, file(c, "floor"), column, 0);
    }

    private static double cavern(TerrainLibrary.Compiled c, Column column, double y) {
        return eval(c, file(c, "cavern"), column, y);
    }

    /** The lowest y at which the cavern opens (density at or below 0), in steps of a tenth of a block. */
    private static double openFrom(TerrainLibrary.Compiled c, Column column, int from, int to) {
        for (double y = from; y <= to; y += 0.1) {
            if (cavern(c, column, y) <= 0) return y;
        }
        return Double.NaN;
    }

    private static Map<String, Double> noises(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(NS + kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static final Column FLAT = new Column(Map.of());

    // ---- floor and ceiling ----------------------------------------------------------------------

    @Test
    void theFloorAndCeilingBoundTheOpenSpace() {
        TerrainLibrary.Compiled c = nether("");
        assertTrue(cavern(c, FLAT, 38) > 0, "solid under the floor at 40");
        assertTrue(cavern(c, FLAT, 42) < 0, "open over it");
        assertTrue(cavern(c, FLAT, 90) < 0, "open under the ceiling at 92");
        assertTrue(cavern(c, FLAT, 94) > 0, "solid over it");
        assertEquals(40, openFrom(c, FLAT, 0, 120), 0.11);
        assertEquals(-0.25, cavern(c, FLAT, 42), 1e-9, "a block of distance is an eighth of density");
    }

    @Test
    void floorReliefRaisesAndLowersTheFloorAndHalfAsMuchTheCeiling() {
        TerrainLibrary.Compiled c = nether("\"floorRelief\": 20, \"ceilingGap\": 60");
        double high = 2 * Noises.sigma("altitude"), detail = 2 * Noises.sigma("detail");
        assertEquals(40 + 20, floor(c, new Column(noises("altitude", high, "detail", detail))), 1e-6);
        assertEquals(40 - 20, floor(c, new Column(noises("altitude", -high, "detail", -detail))), 1e-6);
        assertEquals(40 + 0.75 * 20, floor(c, new Column(noises("altitude", high))), 1e-6);
        assertEquals(100 + 10, eval(c, file(c, "ceiling"), new Column(noises("plates", 2 * Noises.sigma("plates"))), 0), 1e-6);
        TerrainLibrary.Compiled plain = nether("\"floorRelief\": 0");
        assertEquals(40, floor(plain, new Column(noises("altitude", high))), 1e-9, "no relief: a flat plain");
        assertFalse(plain.files().containsKey("data/adventure/worldgen/noise/terrain/altitude.json"));
    }

    @Test
    void flatsWearTheReliefAwayOverTheirShare() {
        TerrainLibrary.Compiled c = nether("\"floorRelief\": 20, \"flats\": 0.5");
        double high = 2 * Noises.sigma("altitude");
        assertEquals(40, floor(c, new Column(noises("altitude", high, "wear", -0.01))), 1e-6, "below the median wear: flat");
        assertEquals(40 + 15, floor(c, new Column(noises("altitude", high, "wear", 0.5 * Noises.sigma("wear")))), 1e-6);
        assertFalse(nether("\"floorRelief\": 20").files().containsKey("data/adventure/worldgen/noise/terrain/wear.json"));
    }

    // ---- feature terms ----------------------------------------------------------------------------

    @Test
    void aPitSinksTheFloorToAFlatBottomPitDepthBelowFloorHeight() {
        TerrainLibrary.Compiled c = nether("\"floorRelief\": 10, \"pit\": 0.1, \"pitDepth\": 30");
        double s = Noises.sigma("lakes");
        double t = Noises.inverseNormal(0.9);
        assertEquals(10, floor(c, new Column(noises("lakes", (t + 1) * s, "altitude", 0.3))), 1e-6, "the pit floor is flat at 40 - 30");
        assertEquals(40, floor(c, new Column(noises("lakes", (t - 0.1) * s))), 1e-6, "outside the pit the floor is the plain's");
        double wall = floor(c, new Column(noises("lakes", (t + 0.3) * s)));
        assertTrue(wall > 10 && wall < 40, "the wall falls across " + RoofedTerrain.PIT_WALL + " sigma: " + wall);
    }

    @Test
    void aRadialLayoutPutsOnePitRoundItsCentre() {
        TerrainLibrary.Compiled c = compile("{\"base\": \"roofed\", \"params\": {\"pit\": 0.02, \"pitDepth\": 40}, "
                + "\"layout\": {\"type\": \"radial\", \"centre\": [100, 0], \"radius\": 64, \"falloff\": 96, \"strength\": 1}}", "nether");
        assertEquals(0, floor(c, new Column(Map.of(), 0, 100, 0)), 1e-6, "at the centre the pit is full depth");
        assertEquals(0, floor(c, new Column(Map.of(), 0, 140, 0)), 1e-6, "within the radius too");
        assertEquals(40, floor(c, new Column(Map.of(), 0, 400, 0)), 1e-6, "past the falloff no pit, whatever the noise");
        assertEquals(40, floor(c, new Column(noises("lakes", 2.5 * Noises.sigma("lakes")), 0, 400, 0)), 1e-6);
    }

    @Test
    void corridorsSinkLongPassesToAFlatBed() {
        TerrainLibrary.Compiled c = nether("\"corridors\": 0.08, \"corridorDepth\": 12");
        assertEquals(28, floor(c, new Column(noises("channel", 0.0))), 1e-6);
        assertEquals(28, floor(c, new Column(noises("channel", -0.08))), 1e-6, "the bed is flat across the corridor's width");
        assertEquals(40, floor(c, new Column(noises("channel", 0.2))), 1e-6);
        String channel = c.files().get(P + "floor.json").toString();
        assertTrue(channel.contains("\"noise\":\"adventure:terrain/channel\",\"shift_x\":\"minecraft:shift_x\",\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":0.5"),
                "corridors read the channel noise at half its frequency: " + channel);
    }

    @Test
    void chasmsCutChasmDepthBelowTheFloorRoundThem() {
        TerrainLibrary.Compiled c = nether("\"floorRelief\": 10, \"chasms\": 0.05, \"chasmDepth\": 40, \"floorHeight\": 60, \"ceilingGap\": 40");
        double alt = 2 * Noises.sigma("altitude");
        assertEquals(60 + 7.5 - 40, floor(c, new Column(noises("gorge", 0.0, "altitude", alt))), 1e-6);
        assertEquals(60 + 7.5, floor(c, new Column(noises("gorge", 0.06, "altitude", alt))), 1e-6);
        double prev = Double.MAX_VALUE, steepest = 0;
        for (double g = 0.03; g <= 0.05; g += 0.002) {
            double f = floor(c, new Column(noises("gorge", g)));
            steepest = Math.max(steepest, f - (prev == Double.MAX_VALUE ? f : prev));
            prev = f;
        }
        assertTrue(steepest > 4, "the walls rise in a narrow band of the chasm noise: " + steepest);
    }

    @Test
    void ledgesStepTheFloorIntoShelvesLedgeStepApart() {
        TerrainLibrary.Compiled c = nether("\"pit\": 0.1, \"pitDepth\": 32, \"ledges\": 1, \"ledgeStep\": 8");
        double s = Noises.sigma("lakes");
        double t = Noises.inverseNormal(0.9);
        int onShelf = 0, samples = 0;
        for (double u = t; u <= t + RoofedTerrain.PIT_WALL; u += 0.01) {
            double f = floor(c, new Column(noises("lakes", u * s)));
            double over = ((f - 40) % 8 + 8) % 8;
            samples++;
            if (over < 1e-6 || over > 8 - 1e-6) onShelf++;
        }
        assertTrue(onShelf > samples * 0.8, "most of the wall is shelves at 8-block steps: " + onShelf + " of " + samples);
        assertFalse(nether("").files().get(P + "floor.json").toString().contains("minecraft:floor"), "no ledges, no steps");
    }

    @Test
    void pillarsAreSolidFromFloorToCeiling() {
        TerrainLibrary.Compiled c = nether("\"pillars\": 0.05");
        double s = Noises.sigma("spires");
        Column in = new Column(noises("spires", Noises.threshold(0.05, s) + 0.3 * s));
        for (int y = 41; y < 92; y++) assertTrue(cavern(c, in, y) > 0, "inside a pillar at y " + y);
        assertTrue(cavern(c, new Column(noises("spires", 0.0)), 60) < 0, "open beside it");
        assertFalse(nether("").files().containsKey("data/adventure/worldgen/noise/terrain/spires.json"));
    }

    @Test
    void roughnessWeighsVanillasNetherNoise() {
        TerrainLibrary.Compiled rough = nether("\"roughness\": 1");
        assertEquals(cavern(rough, FLAT, 60) + 3, cavern(rough, new Column(Map.of(), 1, 0, 0), 60), 1e-9);
        assertFalse(nether("\"roughness\": 0").files().get(P + "cavern.json").toString().contains(RoofedTerrain.BASE_3D_NOISE));
    }

    // ---- wiring ---------------------------------------------------------------------------------

    @Test
    void theRouterKeepsThePresetsSlidesClimateAndSea() {
        for (String preset : List.of("nether", "caves")) {
            TerrainLibrary.Compiled c = compile("{\"base\": \"roofed\"}", preset);
            JsonObject router = c.settings().getAsJsonObject("noise_router");
            assertEquals("adventure:terrain/t/final_density", router.get("final_density").getAsString(), preset);
            assertEquals("adventure:terrain/t/depth", router.get("depth").getAsString(), preset);
            JsonObject vanillaRouter = TerrainLibraryTest.vanilla().noiseSettings(preset).getAsJsonObject("noise_router");
            for (String key : List.of("temperature", "vegetation", "continents", "erosion", "ridges", "chunk_surface_level")) {
                assertEquals(vanillaRouter.get(key), router.get(key), preset + " " + key);
            }
            String fd = c.files().get(P + "final_density.json").toString();
            String vanillaFd = vanillaRouter.get("final_density").toString();
            assertEquals(vanillaFd.replace("\"" + RoofedTerrain.BASE_3D_NOISE + "\"", "\"adventure:terrain/t/cavern\""), fd,
                    "vanilla's slides round the cavern: " + preset);
            assertFalse(c.settings().has("debug_functions"));
            for (String key : List.of("sea_level", "default_fluid", "default_block", "noise", "material_rule")) {
                assertEquals(TerrainLibraryTest.vanilla().noiseSettings(preset).get(key), c.settings().get(key), preset + " " + key);
            }
        }
    }

    @Test
    void theDepthIsBlocksBelowFloorHeightOver128() {
        TerrainLibrary.Compiled c = nether("\"floorHeight\": 48, \"ceilingGap\": 40");
        assertEquals(0, eval(c, file(c, "depth"), FLAT, 48), 1e-9);
        assertEquals(0.25, eval(c, file(c, "depth"), FLAT, 16), 1e-9);
        assertEquals(-0.25, eval(c, file(c, "depth"), FLAT, 80), 1e-9);
    }

    @Test
    void theCavesPresetTakesItsOwnRangeAndSlides() {
        TerrainLibrary.Compiled c = compile("{\"base\": \"roofed\", \"params\": {\"floorHeight\": -30, \"ceilingGap\": 80}}", "caves");
        assertEquals(-30, openFrom(c, FLAT, -64, 40), 0.11);
        assertTrue(c.files().get(P + "final_density.json").toString().contains("\"from_coordinate\":-72"), "the caves bottom slide");
    }

    @Test
    void scaleStretchesTheTerrainNoises() {
        String floor = nether("\"floorRelief\": 10").files().get(P + "floor.json").toString();
        String wide = compile("{\"base\": \"roofed\", \"scale\": 2, \"params\": {\"floorRelief\": 10}}", "nether").files().get(P + "floor.json").toString();
        assertTrue(floor.contains("\"noise\":\"adventure:terrain/altitude\",\"shift_x\":\"minecraft:shift_x\",\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":1.0"), floor);
        assertTrue(wide.contains("\"noise\":\"adventure:terrain/altitude\",\"shift_x\":\"minecraft:shift_x\",\"shift_z\":\"minecraft:shift_z\",\"xz_scale\":0.5"), wide);
    }

    @Test
    void zonesBlendARoofedTerrainOnTheNetherPreset() {
        assertEquals("nether", TerrainLibrary.settingsPreset("nether", TerrainFamily.ROOFED));
        assertEquals("overworld", TerrainLibrary.settingsPreset("nether", TerrainFamily.CANYONS));
    }

    // ---- refusals -------------------------------------------------------------------------------

    private static String refusal(String json, String preset) {
        return assertThrows(IllegalArgumentException.class, () -> compile(json, preset)).getMessage();
    }

    @Test
    void refusesWhatARoofedTerrainCannotHold() {
        assertEquals("t: terrain.base roofed shapes the caves and nether presets, a floor under a ceiling, not overworld",
                refusal("{\"base\": \"roofed\"}", "overworld"));
        assertEquals("t: terrain.base roofed shapes the caves and nether presets, a floor under a ceiling, not end",
                refusal("{\"base\": \"roofed\"}", "end"));
        assertTrue(refusal("{\"base\": \"roofed\", \"heightMultiplier\": 2}", "nether").startsWith("t: terrain.heightMultiplier is for terrains on a sea"));
        assertTrue(refusal("{\"base\": \"roofed\", \"scales\": {\"continents\": 2}}", "nether").startsWith("t: terrain.scales takes only climate under a roof"));
        assertEquals("t: terrain.layout places a roofed terrain's pits; set terrain.params.pit above 0",
                refusal("{\"base\": \"roofed\", \"layout\": {\"type\": \"radial\", \"radius\": 64}}", "nether"));
        assertEquals("t: terrain.params.floorHeight -30 leaves no cavern in y 0 to 128; keep it from 4 to 108",
                refusal("{\"base\": \"roofed\", \"params\": {\"floorHeight\": -30}}", "nether"));
        assertEquals("t: terrain.params.ceilingGap 20 lets the ceiling meet the floor; it needs at least 1.5 times floorRelief plus 8, 26",
                refusal("{\"base\": \"roofed\", \"params\": {\"ceilingGap\": 20}}", "nether"));
        assertEquals("t: terrain.params.pit = 0.7 is outside [0, 0.6]",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"roofed\", \"params\": {\"pit\": 0.7}}")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"roofed\", \"params\": {\"coast\": 0}}"))
                .getMessage().startsWith("t: terrain.params.coast is not a parameter of roofed; parameters: roughness, floorHeight"));
    }

    @Test
    void theReportStatesWhatLiesUnderTheSea() {
        TerrainSpec plain = TerrainLibraryTest.spec("{\"base\": \"roofed\", \"params\": {\"floorHeight\": 60, \"floorRelief\": 10, \"ceilingGap\": 40}}");
        assertEquals("floor at y 60 ± 10, ceiling at y 100 ± 5; the floor stays above the lava sea at y 32",
                RoofedTerrain.describe(plain, 32, "lava"));
        TerrainSpec cut = TerrainLibraryTest.spec("{\"base\": \"roofed\", \"params\": {\"floorHeight\": 40, \"pit\": 0.1, \"pitDepth\": 20, "
                + "\"chasms\": 0.05, \"chasmDepth\": 30, \"corridors\": 0.05, \"corridorDepth\": 4}}");
        assertEquals("floor at y 40 ± 12, ceiling at y 92 ± 6; under the lava sea at y 32: the floor's hollows below y 32, pit floors at y 20, "
                + "chasm floors from y -2", RoofedTerrain.describe(cut, 32, "lava"));
    }

    @Test
    void settingsKeepTheCeilingClearOfTheFloor() {
        for (String setting : TerrainFamily.SETTINGS) {
            TerrainSpec s = TerrainLibraryTest.spec("{\"base\": \"roofed\", \"setting\": \"" + setting + "\"}");
            assertTrue(s.param("ceilingGap") >= 1.5 * s.param("floorRelief") + 8, setting);
            compile("{\"base\": \"roofed\", \"setting\": \"" + setting + "\"}", "caves");
        }
    }
}
