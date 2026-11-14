package com.customdimensions.compiler.terrain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code terrain.climateShift} and {@code terrain.climateWarp} on the router's temperature and vegetation. */
class ClimateShiftTest {
    private static final String P = "data/adventure/worldgen/density_function/terrain/t/";
    private static final String WARP_X = "data/adventure/worldgen/noise/terrain/warp_x.json";

    @Test
    void aShiftAddsToTemperatureAndVegetationAlone() {
        TerrainLibrary.Compiled c = TerrainLibraryTest.compile("{\"base\": \"continents\", \"climateShift\": {\"temperature\": 0.3}}");
        JsonObject t = c.files().get(P + "temperature.json");
        assertEquals("minecraft:add", t.get("type").getAsString());
        assertEquals(0.3, t.get("right").getAsDouble(), 1e-12);
        assertEquals("minecraft:temperature", t.getAsJsonObject("left").get("noise").getAsString());
        assertEquals(TerrainLibraryTest.compile("{\"base\": \"continents\"}").files().get(P + "vegetation.json"), c.files().get(P + "vegetation.json"),
                "vegetation keeps its own with no shift of its own");
        assertFalse(c.files().containsKey(WARP_X), "no warp, no warp noises");
    }

    @Test
    void theShapedPresetTakesTheShiftToo() {
        TerrainLibrary.Compiled c = TerrainShapingTest.preset("{\"scales\": {\"climate\": 2}, \"climateShift\": {\"temperature\": -0.2, "
                + "\"vegetation\": 0.1}}", "overworld");
        JsonObject t = c.files().get(P + "temperature.json");
        assertEquals(-0.2, t.get("right").getAsDouble(), 1e-12);
        assertEquals(0.125, t.getAsJsonObject("left").get("xz_scale").getAsDouble(), 1e-12, "the stretch still applies under the shift");
        assertEquals(0.1, c.files().get(P + "vegetation.json").get("right").getAsDouble(), 1e-12);
        assertTrue(TerrainSpec.presetShaping("t", TerrainShapingTest.terrain("{\"climateShift\": {\"temperature\": 0.1}}")) != null,
                "a shift alone shapes the preset");
    }

    @Test
    void aWarpMovesEveryShiftedNoisesSamplingPointByBlocksTimesItsScale() {
        TerrainLibrary.Compiled c = TerrainLibraryTest.compile("{\"base\": \"continents\", \"scales\": {\"climate\": 4}, "
                + "\"climateWarp\": {\"blocks\": 200, \"wavelength\": 1024}}");
        JsonObject t = c.files().get(P + "temperature.json");
        double xz = t.get("xz_scale").getAsDouble();
        assertEquals(0.25 / 4, xz, 1e-12);
        for (String axis : new String[] {"x", "z"}) {
            JsonObject shift = t.getAsJsonObject("shift_" + axis);
            assertEquals("minecraft:add", shift.get("type").getAsString());
            assertEquals("minecraft:shift_" + axis, shift.get("left").getAsString(), "vanilla's own shift stays");
            JsonObject move = shift.getAsJsonObject("right");
            assertEquals(200 / (2 * Noises.sigma("warp_x")) * xz, move.get("right").getAsDouble(), 1e-5,
                    "two deviations of the warp noise move the point 200 blocks");
            JsonObject noise = move.getAsJsonObject("left");
            assertEquals("adventure:terrain/warp_" + axis, noise.get("noise").getAsString());
            assertEquals(256.0 / 1024, noise.get("xz_scale").getAsDouble(), 1e-12, "features a wavelength across");
            assertFalse(noise.has("shift_x"), "the warp noise is sampled where it stands");
        }
        assertTrue(c.files().containsKey(WARP_X) && c.files().containsKey("data/adventure/worldgen/noise/terrain/warp_z.json"));
        assertTrue(c.files().get(P + "vegetation.json").has("shift_x") && c.files().get(P + "vegetation.json").get("shift_x").isJsonObject(),
                "vegetation is warped alike");
    }

    @Test
    void aClimateOnlyPresetIsShiftedAndWarpedThroughItsStretch() {
        TerrainShaping s = TerrainSpec.presetShaping("t", TerrainShapingTest.terrain("{\"climateShift\": {\"temperature\": 0.4}, "
                + "\"climateWarp\": {\"blocks\": 64}}"));
        JsonObject settings = TerrainLibraryTest.vanilla().noiseSettings("caves");
        JsonObject overworld = TerrainLibraryTest.vanilla().noiseSettings("overworld").getAsJsonObject("noise_router");
        for (String key : new String[] {"temperature", "vegetation"}) {
            settings.getAsJsonObject("noise_router").add(key, overworld.get(key).deepCopy());
        }
        TerrainLibrary.Climate c = TerrainLibrary.stretchClimate("adventure", "t", settings, s.stretch("temperature"), s.climate(),
                TerrainLibraryTest.vanilla());
        assertTrue(c.stretched());
        JsonObject t = c.files().get(P + "temperature.json");
        assertEquals(0.4, t.get("right").getAsDouble(), 1e-12);
        assertTrue(t.getAsJsonObject("left").get("shift_x").isJsonObject());
        assertTrue(c.files().containsKey(WARP_X));
        assertEquals("adventure:terrain/t/temperature", settings.getAsJsonObject("noise_router").get("temperature").getAsString());
        TerrainLibrary.checkClimateOnly("t", "caves", s);
    }

    @Test
    void noShiftAndNoWarpChangeNothing() {
        assertEquals(TerrainLibraryTest.compile("{\"base\": \"continents\"}").files(),
                TerrainLibraryTest.compile("{\"base\": \"continents\", \"climateShift\": {\"temperature\": 0}, \"climateWarp\": {\"wavelength\": 900}}").files());
        assertNull(TerrainSpec.presetShaping("t", TerrainShapingTest.terrain("{\"climateWarp\": {\"blocks\": 0}}")));
    }

    @Test
    void badClimateFieldsAreErrorsNamingTheDefinition() {
        assertEquals("t: terrain.climateShift.temperature = 2 is outside [-1, 1]",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"continents\", \"climateShift\": {\"temperature\": 2}}"))
                        .getMessage());
        assertEquals("t: terrain.climateShift.humidity is not a field; fields: temperature, vegetation",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"continents\", \"climateShift\": {\"humidity\": 0.1}}"))
                        .getMessage());
        assertEquals("t: terrain.climateWarp.wavelength = 10 is outside [64, 4096]",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"continents\", \"climateWarp\": {\"wavelength\": 10}}"))
                        .getMessage());
        assertEquals("t: terrain.climateWarp must be an object",
                assertThrows(IllegalArgumentException.class, () -> TerrainLibraryTest.spec("{\"base\": \"continents\", \"climateWarp\": 3}")).getMessage());
    }
}
