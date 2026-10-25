package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the compiler wires {@code terrain} into each generator, and the errors it stops on. */
class TerrainWiringTest {

    private static final String OVERWORLD = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, ";

    private static JsonObject settings(DatapackCompiler.Output out, String slug) {
        return JsonParser.parseString(out.files().get("data/adventure/worldgen/noise_settings/" + slug + ".json")).getAsJsonObject();
    }

    @Test
    void aNoiseDefinitionWithATerrainGetsItsOwnSettingsAndAReportNote() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + "\"terrain\": {\"base\": \"ridges\", \"setting\": \"gentle\"}");
        String stem = out.files().get("data/customdimensions/lazy_dimension/t.json");
        assertTrue(stem.contains("\"settings\": \"adventure:t\""), stem);
        assertEquals("adventure:terrain/t/final_density", settings(out, "t").getAsJsonObject("noise_router").get("final_density").getAsString());
        assertTrue(out.report().entry("t").notes().stream().anyMatch(n -> n.equals("Terrain `ridges` (gentle) under `adventure:terrain/t/`")));
        assertTrue(out.report().entry("t").unsupported().stream().noneMatch(u -> u.contains("terrain")));
    }

    @Test
    void liquidsApplyOverAnAirborneTerrain() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + "\"terrain\": {\"base\": \"floating_islands\"}, "
                + "\"liquids\": {\"seaLevel\": 20}");
        assertEquals(20, settings(out, "t").get("sea_level").getAsInt());
        assertFalse(settings(out, "t").has("aquifers"));
        assertTrue(out.report().entry("t").notes().stream().anyMatch(n -> n.endsWith(", over a void with no aquifers")));
    }

    @Test
    void aNullBaseKeepsTheVanillaSettings() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + "\"terrain\": {\"base\": null}");
        assertTrue(out.files().get("data/customdimensions/lazy_dimension/t.json").contains("\"settings\": \"minecraft:overworld\""));
        assertFalse(out.files().keySet().stream().anyMatch(p -> p.contains("/terrain/")));
    }

    @Test
    void checkerboardTakesATerrainOnOverworldNoiseAndFlatReportsIt() {
        DatapackCompiler.Output board = Fixtures.compile("t", "\"generator\": {\"type\": \"checkerboard\"}, \"terrain\": {\"base\": \"terraces\"}");
        assertTrue(board.files().containsKey("data/adventure/worldgen/density_function/terrain/t/height.json"));
        DatapackCompiler.Output flat = Fixtures.compile("t", "\"generator\": {\"type\": \"flat\"}, \"terrain\": {\"base\": \"terraces\"}");
        assertTrue(flat.report().entry("t").unsupported().contains("`terrain`: a flat generator has no noise terrain"));
    }

    @Test
    void aCloneTakesItsSourcesTerrainUnderItsOwnName() {
        Map<String, com.customdimensions.core.definition.Definition> defs = new LinkedHashMap<>();
        defs.put("a", Fixtures.definition(OVERWORLD + "\"terrain\": {\"base\": \"canyons\"}"));
        defs.put("b", Fixtures.definition("\"generator\": {\"type\": \"clone\", \"of\": \"adventure:a\"}"));
        DatapackCompiler.Output out = DatapackCompiler.compileAll(Fixtures.NAMESPACE, defs, Fixtures.vanilla(), VersionTable.V26_3);
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/b/height.json"));
        assertEquals(out.files().get("data/adventure/worldgen/density_function/terrain/a/factor.json"),
                out.files().get("data/adventure/worldgen/density_function/terrain/b/factor.json"));
    }

    @Test
    void badTerrainsStopTheCompileNamingTheDefinition() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Fixtures.compile("bad_one", OVERWORLD + "\"terrain\": {\"base\": \"canyons\", \"params\": {\"depth\": 999}}"))
                .getMessage().startsWith("bad_one: terrain.params.depth = 999 is outside [8, 120]"));
        assertEquals("nether_one: terrain.base spires cannot shape preset nether: its terrain is a cavern between a floor and a ceiling, not a surface",
                assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("nether_one",
                        "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, \"terrain\": {\"base\": \"spires\"}")).getMessage());
    }
}
