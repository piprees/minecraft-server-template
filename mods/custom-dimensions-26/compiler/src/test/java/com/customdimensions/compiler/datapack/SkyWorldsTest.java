package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The End and the sky: End materials and terrain, islands laid out, biomes over islands, spires off the land, terrain on clones. */
class SkyWorldsTest {

    private static final String DF = "data/adventure/worldgen/density_function/terrain/t/";

    private static JsonObject json(DatapackCompiler.Output out, String path) {
        String s = out.files().get(path);
        if (s == null) throw new AssertionError("no " + path + " in " + out.files().keySet());
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject settings(DatapackCompiler.Output out, String slug) {
        return json(out, "data/adventure/worldgen/noise_settings/" + slug + ".json");
    }

    private static JsonObject router(DatapackCompiler.Output out) {
        return settings(out, "t").getAsJsonObject("noise_router");
    }

    private static JsonArray rule(DatapackCompiler.Output out) {
        return json(out, "data/adventure/worldgen/material_rule/t.json").getAsJsonArray("sequence");
    }

    private static String file(DatapackCompiler.Output out, String name) {
        return json(out, DF + name + ".json").toString();
    }

    private static JsonObject regions(DatapackCompiler.Output out, String slug) {
        String dim = out.files().get("data/adventure/dimension/" + slug + ".json");
        JsonObject source = dim != null ? JsonParser.parseString(dim).getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source")
                : json(out, "data/customdimensions/lazy_dimension/" + slug + ".json").getAsJsonObject("generator").getAsJsonObject("biome_source");
        return source.getAsJsonObject("regions");
    }

    // ---- End materials (G5) -------------------------------------------------------------------

    @Test
    void anEndDefaultBlockReplacesTheEndsEndStoneFillAfterThePalettes() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"biomes\": [\"minecraft:nether_wastes\", \"minecraft:end_barrens\"], \"materials\": {\"defaultBlock\": \"minecraft:netherrack\"}");
        JsonArray sequence = rule(out);
        JsonObject last = sequence.get(sequence.size() - 1).getAsJsonObject();
        assertEquals("{\"result_state\":\"minecraft:netherrack\",\"type\":\"minecraft:block\"}", last.toString());
        assertFalse(sequence.toString().contains("minecraft:end_stone"), "no End stone is laid: " + sequence);
        assertTrue(sequence.get(0).toString().contains("minecraft:nether_wastes"), "the Nether ground comes first: " + sequence);
        assertEquals("minecraft:netherrack", settings(out, "t").get("default_block").getAsString());
    }

    @Test
    void anEndDefaultBlockAloneStillWritesTheRule() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:blackstone\"}");
        assertEquals("adventure:t", settings(out, "t").get("material_rule").getAsString());
        assertEquals("{\"result_state\":\"minecraft:blackstone\",\"type\":\"minecraft:block\"}",
                json(out, "data/adventure/worldgen/material_rule/t.json").getAsJsonArray("sequence").get(0).toString());
    }

    @Test
    void theEndsOwnDefaultBlockKeepsTheVanillaRule() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:end_stone\"}");
        assertEquals("minecraft:end", settings(out, "t").get("material_rule").getAsString());
    }

    @Test
    void aNetherDefaultBlockReplacesTheNethersNetherrackFill() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:blackstone\"}");
        JsonArray sequence = rule(out);
        assertEquals("{\"result_state\":\"minecraft:blackstone\",\"type\":\"minecraft:block\"}", sequence.get(sequence.size() - 1).toString());
        assertTrue(sequence.toString().contains("minecraft:soul_sand"), "the Nether's biome rules stay: " + sequence);
    }

    @Test
    void anOverworldDefaultBlockLeavesTheRuleAlone() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:deepslate\"}");
        assertEquals("minecraft:overworld", settings(out, "t").get("material_rule").getAsString());
    }

    // ---- End terrain (G4) ---------------------------------------------------------------------

    @Test
    void anEndTerrainShapesTheIslandsTheBiomeSourceReads() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\", \"endIsland\": false}, "
                + "\"terrain\": {\"base\": \"end\", \"params\": {\"coverage\": 0.1, \"islandHeight\": 24, \"peaks\": 0.5}, "
                + "\"layout\": {\"type\": \"ring\", \"radius\": 600, \"falloff\": 200, \"strength\": 1}}");
        JsonObject router = router(out);
        assertEquals("adventure:terrain/t/islands", router.get("erosion").getAsString());
        assertEquals("adventure:terrain/t/final_density", router.get("final_density").getAsString());
        String islands = file(out, "islands");
        assertTrue(islands.contains("minecraft:end_outer_islands") && !islands.contains("minecraft:end/islands"), islands);
        assertTrue(islands.contains("0.1") && islands.contains("\"axis\":\"x\""), "coverage and the ring: " + islands);
        String fd = file(out, "final_density");
        assertTrue(fd.contains("\"from_coordinate\":80") && fd.contains("\"to_coordinate\":336"), "the top slide starts 24 higher: " + fd);
        assertTrue(fd.contains("adventure:terrain/t/sloped_cheese") && !fd.contains("minecraft:end/sloped_cheese"), fd);
        String cheese = file(out, "sloped_cheese");
        assertTrue(cheese.contains("adventure:terrain/detail") && cheese.contains("minecraft:end/base_3d_noise"), cheese);
        assertFalse(out.files().containsKey("data/adventure/worldgen/density_function/no_central_island/islands.json"));
        JsonObject dim = json(out, "data/customdimensions/lazy_dimension/t.json");
        assertEquals("minecraft:the_end", dim.getAsJsonObject("generator").getAsJsonObject("biome_source").get("type").getAsString());
    }

    @Test
    void anEndTerrainAtItsDefaultsReadsVanillasIslands() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"terrain\": {\"base\": \"end\"}");
        assertEquals("{\"input\":\"minecraft:end/islands\",\"type\":\"minecraft:cache\"}", file(out, "islands"));
        assertTrue(file(out, "final_density").contains("\"from_coordinate\":56"));
    }

    @Test
    void anEndTerrainIsRefusedOffTheEndPresetAndWithLandScales() {
        String off = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": {\"base\": \"end\"}")).getMessage();
        assertTrue(off.contains("terrain.base end shapes the end preset's islands, not overworld"), off);
        String scales = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"terrain\": {\"base\": \"end\", \"scales\": {\"erosion\": 2}}")).getMessage();
        assertTrue(scales.contains("terrain.scales takes only climate on the end preset"), scales);
        String other = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"terrain\": {\"base\": \"ridges\"}")).getMessage();
        assertTrue(other.contains("terrain.base end shapes it"), other);
    }

    @Test
    void anEndTerrainStretchesTheBorrowedClimate() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"terrain\": {\"base\": \"end\", \"scales\": {\"climate\": 2}}, \"biomes\": [\"minecraft:end_highlands\", \"minecraft:plains\"]");
        assertEquals("adventure:terrain/t/temperature", router(out).get("temperature").getAsString());
    }

    // ---- shaped airborne masses (G10) ---------------------------------------------------------

    @Test
    void aLayoutOnFloatingIslandsMovesTheirCoverage() {
        DatapackCompiler.Output plain = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"floating_islands\"}");
        DatapackCompiler.Output radial = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"floating_islands\", \"layout\": {\"type\": \"radial\", \"radius\": 100, \"falloff\": 200, \"strength\": 1}}");
        assertFalse(file(plain, "sloped_cheese").contains("\"axis\":\"x\""), "no layout, no distance");
        String cheese = file(radial, "sloped_cheese");
        assertTrue(cheese.contains("\"axis\":\"x\"") && cheese.contains("adventure:terrain/islands"), cheese);
        assertTrue(cheese.contains("0.879"), "three deviations of the islands noise (0.293): " + cheese);
    }

    @Test
    void aRingOnBasinIslandsMovesTheLakeMassesAndTheIslands() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"basin_islands\", \"layout\": {\"type\": \"ring\", \"radius\": 400, \"falloff\": 200}}");
        assertTrue(file(out, "lakes").contains("\"axis\":\"x\""), file(out, "lakes"));
        assertTrue(file(out, "sloped_cheese").contains("\"axis\":\"x\""));
    }

    // ---- spires off the land (G12) ------------------------------------------------------------

    @Test
    void spiresOverAVoidStandFromTheBottomOfAnOpenWorld() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"terrain\": {\"base\": \"spires\", \"floor\": \"void\"}");
        JsonObject s = settings(out, "t");
        assertFalse(s.has("aquifers"));
        assertEquals(-64, s.get("sea_level").getAsInt());
        assertTrue(file(out, "final_density").contains("-0.234375"), "the floor is open");
        assertTrue(file(out, "sloped_cheese").contains("adventure:terrain/spires"));
    }

    @Test
    void spiresOutOfASeaKeepTheSeaAndPutLandBiomesOnTheColumns() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"terrain\": {\"base\": \"spires\", \"floor\": \"sea\"}");
        JsonObject s = settings(out, "t");
        assertTrue(s.has("aquifers"));
        assertEquals(63, s.get("sea_level").getAsInt());
        String continents = s.getAsJsonObject("noise_router").get("continents").toString();
        assertTrue(continents.contains("-0.6") && continents.contains("0.2"), continents);
    }

    @Test
    void spiresOnIslandsAreAirborne() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"spires\", \"floor\": \"islands\", \"params\": {\"landHeight\": 120}}");
        assertFalse(settings(out, "t").has("aquifers"));
        assertTrue(file(out, "sloped_cheese").contains("adventure:terrain/spires"));
    }

    @Test
    void aFloorIsForSpiresOnly() {
        String m = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"overworld\"}, \"terrain\": {\"base\": \"ridges\", \"floor\": \"void\"}")).getMessage();
        assertTrue(m.contains("terrain.floor is for spires"), m);
        String bad = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"overworld\"}, \"terrain\": {\"base\": \"spires\", \"floor\": \"lava\"}")).getMessage();
        assertTrue(bad.contains("terrain.floor must be one of land, void, sea, islands"), bad);
    }

    // ---- biomes over islands (G3, G11) --------------------------------------------------------

    @Test
    void endRegionsOverLandRankOnTheIslandDensity() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"biomes\": [\"minecraft:end_highlands\", \"minecraft:end_midlands\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}");
        assertEquals("{\"axis\":\"erosion\",\"min\":-0.0625}", regions(out, "t").get("land").toString());
    }

    @Test
    void regionsOverAllRankEveryCellAsBefore() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"biomes\": [\"minecraft:end_highlands\", \"minecraft:end_midlands\"], \"biomeLayout\": {\"mode\": \"regions\"}");
        assertNull(regions(out, "t").get("land"));
    }

    @Test
    void floatingRegionsOverLandReadThePlaceCueAndAnUndersideTakesTheIslandsBelowTheirTops() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"floating_islands\"}, \"biomes\": [\"minecraft:meadow\", \"minecraft:cherry_grove\", "
                + "\"minecraft:lush_caves\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\", \"roles\": {\"minecraft:lush_caves\": \"underside\"}}");
        JsonObject router = router(out);
        assertEquals("adventure:terrain/t/place", router.get("continents").getAsString());
        assertEquals("adventure:terrain/t/island_depth", router.get("depth").getAsString());
        assertTrue(file(out, "island_depth").contains("adventure:terrain/t/island_top"));
        JsonObject regions = regions(out, "t");
        assertEquals("{\"axis\":\"continentalness\",\"min\":-0.7}", regions.get("land").toString());
        JsonArray areas = regions.getAsJsonArray("areas");
        assertEquals("underside", areas.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("[0.0625,2.0]", areas.get(0).getAsJsonObject().get("depth").toString());
        assertEquals("land", areas.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("[-2.0,0.0625]", areas.get(1).getAsJsonObject().get("depth").toString());
    }

    @Test
    void anOceanOverLandOnBasinIslandsTakesTheBasins() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"basin_islands\"}, \"liquids\": {\"basins\": {}}, \"biomes\": [\"minecraft:jungle\", "
                + "\"minecraft:warm_ocean\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}");
        assertEquals("adventure:terrain/t/place", router(out).get("continents").getAsString());
        assertFalse(router(out).get("depth").getAsString().endsWith("island_depth"), "no underside, no island depth");
        JsonArray areas = regions(out, "t").getAsJsonArray("areas");
        JsonObject basin = areas.get(0).getAsJsonObject();
        assertEquals("basin", basin.get("role").getAsString());
        assertEquals("[-0.7,0.05]", basin.get("continentalness").toString());
        String place = file(out, "place");
        assertTrue(place.contains("adventure:terrain/t/lakes"), place);
    }

    @Test
    void aBasinRoleWithoutOverLandStillReadsThePlaceCue() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
                + "\"terrain\": {\"base\": \"basin_islands\"}, \"biomes\": [\"minecraft:jungle\", \"minecraft:warm_ocean\"], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:warm_ocean\": \"basin\"}}");
        assertEquals("adventure:terrain/t/place", router(out).get("continents").getAsString());
        assertNull(regions(out, "t").get("land"));
    }

    @Test
    void islandRolesAndOverLandAreRefusedOnTheGround() {
        String over = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"overworld\"}, \"biomes\": [\"minecraft:plains\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}")).getMessage();
        assertTrue(over.contains("biomeLayout.over land ranks regions over the islands"), over);
        String basin = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"floating_islands\"}, \"terrain\": {\"base\": \"floating_islands\"}, \"biomes\": [\"minecraft:plains\", "
                + "\"minecraft:ocean\"], \"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:ocean\": \"basin\"}}")).getMessage();
        assertTrue(basin.contains("is basin, which is the open water of basin_islands"), basin);
        String under = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"end\"}, \"biomes\": [\"minecraft:end_highlands\", \"minecraft:end_barrens\"], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:end_barrens\": \"underside\"}}")).getMessage();
        assertTrue(under.contains("is underside"), under);
        String bad = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"end\"}, \"biomes\": [\"minecraft:end_highlands\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"sea\"}")).getMessage();
        assertTrue(bad.contains("biomeLayout.over must be one of [all, land]"), bad);
    }

    // ---- terrain on clones (G9) ---------------------------------------------------------------

    private static DatapackCompiler.Output compileAll(Map<String, String> bodies) {
        Map<String, com.customdimensions.core.definition.Definition> defs = new LinkedHashMap<>();
        bodies.forEach((slug, body) -> defs.put(slug, Fixtures.definition(body)));
        return DatapackCompiler.compileAll(Fixtures.NAMESPACE, defs, Fixtures.vanilla(), VersionTable.V26_3);
    }

    @Test
    void aCloneWithTerrainCompilesItsOwnAndOneWithoutTakesItsSources() {
        String source = "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, \"terrain\": {\"base\": \"floating_islands\"}";
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("src", source);
        bodies.put("own", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:src\"}, \"terrain\": {\"base\": \"basin_islands\"}");
        bodies.put("same", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:src\"}");
        DatapackCompiler.Output out = compileAll(bodies);
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/own/lakes.json"), "the clone's own basins");
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/same/sloped_cheese.json"), "its source's terrain");
        assertFalse(out.files().containsKey("data/adventure/worldgen/density_function/terrain/same/lakes.json"));
        JsonElement same = JsonParser.parseString(out.files().get("data/customdimensions/lazy_dimension/same.json")).getAsJsonObject()
                .getAsJsonObject("generator").get("settings");
        assertEquals("adventure:same", same.getAsString());
        JsonElement own = JsonParser.parseString(out.files().get("data/customdimensions/lazy_dimension/own.json")).getAsJsonObject()
                .getAsJsonObject("generator").get("settings");
        assertEquals("adventure:own", own.getAsString());
    }
}
