package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worlds under a roof through the whole compiler: the roofed terrain in a
 * definition and a zone, the climate stretch on the nether, caves and end
 * presets, the ground of Nether biomes off the nether preset and the sunless
 * cave floor, and materials by depth.
 */
class RoofedWorldsTest {

    private static final String DF = "data/adventure/worldgen/density_function/terrain/t/";

    private static JsonObject json(DatapackCompiler.Output out, String path) {
        String s = out.files().get(path);
        if (s == null) throw new AssertionError("no " + path);
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject settings(DatapackCompiler.Output out) {
        return json(out, "data/adventure/worldgen/noise_settings/t.json");
    }

    private static JsonArray rule(DatapackCompiler.Output out) {
        return json(out, "data/adventure/worldgen/material_rule/t.json").getAsJsonArray("sequence");
    }

    private static List<String> notes(DatapackCompiler.Output out) {
        return out.report().entry("t").notes();
    }

    /** The palette rule of {@code biome} in the compiled sequence, or null. */
    private static JsonObject biomeRule(JsonArray sequence, String biome) {
        for (JsonElement e : sequence) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("if_true")) continue;
            JsonElement c = e.getAsJsonObject().get("if_true");
            if (c.isJsonObject() && "minecraft:biome".equals(c.getAsJsonObject().get("type").getAsString())
                    && c.getAsJsonObject().get("biome_is").toString().equals("[\"" + biome + "\"]")) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    private static int indexOf(JsonArray sequence, String fragment) {
        for (int i = 0; i < sequence.size(); i++) if (sequence.get(i).toString().contains(fragment)) return i;
        return -1;
    }

    // ---- roofed terrain -----------------------------------------------------------------------

    @Test
    void aRoofedNetherWiresItsRouterAndReportsItsSea() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"terrain\": {\"base\": \"roofed\", \"params\": {\"floorHeight\": 34, \"flats\": 0.8}}, \"liquids\": {\"seaLevel\": 30}");
        JsonObject router = settings(out).getAsJsonObject("noise_router");
        assertEquals("adventure:terrain/t/final_density", router.get("final_density").getAsString());
        assertEquals("adventure:terrain/t/depth", router.get("depth").getAsString());
        assertEquals(30, settings(out).get("sea_level").getAsInt());
        assertTrue(out.files().containsKey(DF + "cavern.json"));
        assertTrue(notes(out).contains("Terrain `roofed` (default) under `adventure:terrain/t/`: floor at y 34 ± 12, ceiling at y 86 ± 6; "
                + "under the lava sea at y 30: the floor's hollows below y 30"), notes(out).toString());
    }

    @Test
    void aRoofedZoneBlendsIntoTheNethersOwnTerrain() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"zones\": [{\"shape\": \"circle\", \"centre\": [0, 0], \"radius\": 96, \"blend\": 32, "
                + "\"terrain\": {\"base\": \"roofed\", \"params\": {\"pit\": 0.6, \"pitDepth\": 24}}}]");
        String fd = settings(out).getAsJsonObject("noise_router").get("final_density").toString();
        assertTrue(fd.contains("adventure:terrain/t/zone_0/final_density"), fd);
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/t/zone_0/cavern.json"));
        String depth = settings(out).getAsJsonObject("noise_router").get("depth").toString();
        assertTrue(depth.contains("adventure:terrain/t/zone_0/depth"), depth);
    }

    // ---- climate on roofed presets (G2) -------------------------------------------------------

    @Test
    void scalesClimateStretchesTheNethersOwnClimate() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"terrain\": {\"scales\": {\"climate\": 2}}");
        JsonObject router = settings(out).getAsJsonObject("noise_router");
        assertEquals("adventure:terrain/t/temperature", router.get("temperature").getAsString());
        assertEquals("adventure:terrain/t/vegetation", router.get("vegetation").getAsString());
        JsonObject t = json(out, DF + "temperature.json");
        assertEquals("minecraft:nether/temperature", t.get("noise").getAsString());
        assertEquals(0.125, t.get("xz_scale").getAsDouble());
        assertEquals(Fixtures.vanilla().noiseSettings("nether").getAsJsonObject("noise_router").get("final_density"), router.get("final_density"),
                "nothing but the climate changes");
    }

    @Test
    void scalesClimateStretchesTheClimateCavesAndEndBorrowForABiomeList() {
        for (String preset : List.of("caves", "end")) {
            DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"" + preset + "\"}, "
                    + "\"biomes\": [\"minecraft:lush_caves\", \"minecraft:dripstone_caves\"], \"terrain\": {\"scales\": {\"climate\": 1.5}}");
            JsonObject t = json(out, DF + "temperature.json");
            assertEquals("minecraft:temperature", t.get("noise").getAsString(), preset);
            assertEquals(0.25 / 1.5, t.get("xz_scale").getAsDouble(), 1e-12, preset);
        }
        DatapackCompiler.Output end = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, "
                + "\"terrain\": {\"scales\": {\"climate\": 2}}");
        assertTrue(notes(end).contains("terrain.scales.climate has no effect: the end preset's biomes read no climate without a biome list"),
                notes(end).toString());
    }

    @Test
    void scalesClimateStretchesARoofedTerrainsClimate() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"terrain\": {\"base\": \"roofed\", \"scale\": 2, \"scales\": {\"climate\": 1.5}}");
        assertEquals(0.25 / 3, json(out, DF + "temperature.json").get("xz_scale").getAsDouble(), 1e-12);
    }

    @Test
    void theRoofedPresetsOwnTerrainTakesNothingButClimate() {
        for (String terrain : List.of("{\"scale\": 2}", "{\"scales\": {\"erosion\": 2}}", "{\"layout\": {\"type\": \"radial\", \"radius\": 64}}")) {
            String message = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                    "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, \"terrain\": " + terrain)).getMessage();
            assertEquals("t: the nether preset's own terrain takes only terrain.scales.climate, which stretches the climate its biomes are "
                    + "laid out on; terrain.base roofed shapes its floor and ceiling", message, terrain);
        }
    }

    // ---- ground (G6) ----------------------------------------------------------------------------

    @Test
    void netherBiomesOffTheNetherPresetLayTheirOwnGround() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"biomes\": [\"minecraft:plains\", \"minecraft:crimson_forest\", \"minecraft:soul_sand_valley\", \"minecraft:basalt_deltas\"]");
        JsonArray seq = rule(out);
        String crimson = biomeRule(seq, "minecraft:crimson_forest").toString();
        assertTrue(crimson.contains("minecraft:crimson_nylium") && crimson.contains("minecraft:netherrack"), crimson);
        assertTrue(crimson.contains("minecraft:above_preliminary_surface"), "gated as the overworld's surface is: " + crimson);
        String soul = biomeRule(seq, "minecraft:soul_sand_valley").toString();
        assertTrue(soul.contains("minecraft:soul_sand\"") && soul.contains("minecraft:soul_soil"), soul);
        assertTrue(biomeRule(seq, "minecraft:basalt_deltas").toString().contains("minecraft:basalt\""));
        assertNull(biomeRule(seq, "minecraft:plains"));
        assertTrue(notes(out).stream().anyMatch(n -> n.startsWith("Nether ground for `minecraft:crimson_forest`")), notes(out).toString());
        assertEquals(-1, indexOf(seq, "minecraft:moss_block"), "no sunless floor outside the caves preset");
    }

    @Test
    void aDefinitionsOwnPaletteWinsAndTheNetherPresetKeepsVanillasRule() {
        DatapackCompiler.Output own = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, "
                + "\"biomes\": [\"minecraft:crimson_forest\"], \"materials\": {\"biomes\": {\"minecraft:crimson_forest\": {\"top\": \"minecraft:blackstone\"}}}");
        String crimson = biomeRule(rule(own), "minecraft:crimson_forest").toString();
        assertTrue(crimson.contains("minecraft:blackstone") && !crimson.contains("nylium"), crimson);
        DatapackCompiler.Output nether = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"biomes\": [\"minecraft:crimson_forest\", \"minecraft:warped_forest\"]");
        assertFalse(nether.files().containsKey("data/adventure/worldgen/material_rule/t.json"), "the nether's own rule lays its ground");
    }

    @Test
    void theCavesPresetLaysASunlessFloorWhereVanillaLaysGrass() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, "
                + "\"biomes\": [\"minecraft:forest\", \"minecraft:desert\", \"minecraft:warped_forest\"]");
        JsonArray seq = rule(out);
        int floor = indexOf(seq, "minecraft:moss_block");
        assertTrue(floor > indexOf(seq, "minecraft:warped_nylium"), "after the palettes");
        assertTrue(floor < indexOf(seq, "\"minecraft:overworld/surface\""), "before vanilla's surface");
        String rule = seq.get(floor).toString();
        assertTrue(rule.contains("\"type\":\"minecraft:not\"") && rule.contains("minecraft:desert") && rule.contains("minecraft:badlands")
                && rule.contains("minecraft:frozen_ocean"), "biomes with a floor of their own keep it: " + rule);
        assertFalse(rule.contains("\"minecraft:forest\"") || rule.contains("minecraft:swamp\"") || rule.contains("minecraft:plains"),
                "grass biomes take the sunless floor: " + rule);
        assertTrue(rule.contains("minecraft:not_underwater"), rule);
        List<String> own = MaterialsCompiler.ownFloors(Fixtures.vanilla());
        assertTrue(own.contains("minecraft:mushroom_fields") && own.contains("minecraft:wooded_badlands") && !own.contains("minecraft:swamp"), own.toString());
    }

    @Test
    void aDimensionTopOrAPresetWithoutARoofKeepsVanillasFloors() {
        DatapackCompiler.Output top = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, "
                + "\"materials\": {\"top\": \"minecraft:grass_block\"}");
        assertEquals(-1, indexOf(rule(top), "minecraft:moss_block"), "the definition asks for grass");
        DatapackCompiler.Output single = Fixtures.compile("t", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"minecraft:plains\", "
                + "\"preset\": \"caves\"}");
        assertTrue(indexOf(rule(single), "minecraft:moss_block") >= 0, "a single-biome cave world is under the same roof");
    }

    // ---- materials by depth (G15) ---------------------------------------------------------------

    @Test
    void bandsHoldBelowTheirYDeepestFirstAheadOfEveryPalette() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"biomes\": [\"minecraft:crimson_forest\"], \"materials\": {\"top\": \"minecraft:basalt\", "
                + "\"biomes\": {\"minecraft:crimson_forest\": {\"top\": \"minecraft:crimson_nylium\"}}, "
                + "\"bands\": [{\"below\": 24, \"top\": \"minecraft:magma_block\", \"deep\": \"minecraft:blackstone\"}, "
                + "{\"below\": 48, \"top\": \"minecraft:blackstone\"}]}");
        JsonArray seq = rule(out);
        int deep = indexOf(seq, "minecraft:magma_block"), mid = indexOf(seq, "\"absolute\":48");
        assertTrue(deep >= 0 && mid == deep + 1, "deepest band first: " + seq);
        assertTrue(mid < indexOf(seq, "minecraft:crimson_nylium"), "bands ahead of the per-biome palettes");
        String band = seq.get(deep).toString();
        assertTrue(band.startsWith("{\"type\":\"minecraft:condition\",\"if_true\":{\"type\":\"minecraft:not\",\"invert\":{\"type\":\"minecraft:y_above\","
                + "\"anchor\":{\"absolute\":24},\"surface_depth_multiplier\":0,\"add_stone_depth\":false}}") || band.contains("\"absolute\":24"), band);
        assertTrue(band.contains("minecraft:blackstone"), "the band's deep block: " + band);
    }

    @Test
    void badBandsAreErrorsNamingTheField() {
        List<String[]> cases = new ArrayList<>();
        cases.add(new String[]{"[]", "t: materials.bands: must be a non-empty list of palettes, each with `below`, deepest first"});
        cases.add(new String[]{"[{\"top\": \"minecraft:stone\"}]", "t: materials.bands[0].below: must be a whole y, the band's top"});
        cases.add(new String[]{"[{\"below\": 2.5, \"top\": \"minecraft:stone\"}]", "t: materials.bands[0].below: must be a whole y, the band's top"});
        cases.add(new String[]{"[{\"below\": 40, \"top\": \"minecraft:stone\"}, {\"below\": 20, \"top\": \"minecraft:stone\"}]",
                "t: materials.bands[1].below: 20 must lie above the band before it (40): bands run deepest first"});
        cases.add(new String[]{"[{\"below\": 40}]", "t: materials.bands[0]: sets no palette field"});
        cases.add(new String[]{"[{\"below\": 40, \"roof\": \"minecraft:stone\"}]",
                "t: materials.bands[0].roof: is not a palette field; known: top, filler, deep, cliffs, beaches, ceilings"});
        for (String[] c : cases) {
            String message = assertThrows(IllegalArgumentException.class, () -> MaterialsCompiler.read("t",
                    JsonParser.parseString("{\"bands\": " + c[0] + "}").getAsJsonObject())).getMessage();
            assertEquals(c[1], message, c[0]);
        }
    }

    @Test
    void depthBindsABiomeToTheLowFloorsOfARoofedWorld() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"terrain\": {\"base\": \"roofed\", \"params\": {\"floorHeight\": 70, \"pit\": 0.3, \"pitDepth\": 40}}, "
                + "\"biomes\": [\"minecraft:nether_wastes\", {\"id\": \"minecraft:basalt_deltas\", \"climate\": {\"depth\": [0.2, 1]}}]");
        String stem = out.files().get("data/customdimensions/lazy_dimension/t.json");
        assertTrue(stem.contains("minecraft:basalt_deltas"), stem);
        JsonObject depth = json(out, DF + "depth.json");
        assertTrue(depth.toString().contains("0.546875"), "70 / 128 at y 0: " + depth);
    }
}
