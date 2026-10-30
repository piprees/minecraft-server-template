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

/** Materials, liquids and ores: {@link MaterialsCompiler}, {@link LiquidsCompiler}, {@link OresCompiler}, {@link RuntimeWorldgen}. */
class WorldgenLayersTest {

    private static final String OVERWORLD = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
    private static final String SETTINGS = "data/adventure/worldgen/noise_settings/t.json";
    private static final String RULE = "data/adventure/worldgen/material_rule/t.json";
    private static final String RUNTIME = "data/customdimensions/worldgen_runtime/t.json";

    private static JsonObject file(DatapackCompiler.Output out, String path) {
        String text = out.files().get(path);
        assertTrue(text != null, path + " not emitted; files: " + out.files().keySet());
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static DatapackCompiler.Output compile(String body) {
        return Fixtures.compile("t", body);
    }

    private static String failure(String body) {
        return assertThrows(IllegalArgumentException.class, () -> compile(body)).getMessage();
    }

    private static List<JsonElement> sequence(JsonObject rule) {
        List<JsonElement> out = new ArrayList<>();
        rule.getAsJsonArray("sequence").forEach(out::add);
        return out;
    }

    // ---- liquids ---------------------------------------------------------------------------

    @Test
    void aquifersOnKeepsThePresetsObject() {
        JsonObject s = file(compile(OVERWORLD + ", \"liquids\": {\"aquifers\": \"on\"}"), SETTINGS);
        assertEquals(Fixtures.vanilla().noiseSettings("overworld").get("aquifers"), s.get("aquifers"));
    }

    @Test
    void aquifersOnNeedsAPresetWithAquifers() {
        String m = failure("\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, \"liquids\": {\"aquifers\": \"on\"}");
        assertTrue(m.startsWith("t: liquids.aquifers: "), m);
        assertTrue(m.contains("`caves`"), m);
    }

    @Test
    void offAndFloodedRemoveTheAquifers() {
        for (String mode : List.of("off", "flooded")) {
            JsonObject s = file(compile(OVERWORLD + ", \"liquids\": {\"aquifers\": \"" + mode + "\"}"), SETTINGS);
            assertFalse(s.has("aquifers"), mode);
            assertEquals("minecraft:water", s.get("default_fluid").getAsString(), mode);
        }
    }

    @Test
    void floodedRefusesAir() {
        assertTrue(failure(OVERWORLD + ", \"liquids\": {\"aquifers\": \"flooded\", \"defaultFluid\": \"minecraft:air\"}")
                .startsWith("t: liquids.defaultFluid: "));
        assertTrue(failure("\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"liquids\": {\"aquifers\": \"flooded\"}")
                .startsWith("t: liquids.aquifers: "));
    }

    @Test
    void dryRemovesAquifersMakesAirTheFluidAndDropsWaterSprings() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"liquids\": {\"aquifers\": \"dry\"}");
        JsonObject s = file(out, SETTINGS);
        assertFalse(s.has("aquifers"));
        assertEquals("minecraft:air", s.get("default_fluid").getAsString());
        JsonObject runtime = file(out, RUNTIME);
        assertEquals("adventure:t", runtime.get("level").getAsString());
        assertEquals("[\"minecraft:spring_water\",\"minecraft:dripstone_cluster\"]", runtime.get("remove_features").toString());
    }

    @Test
    void dryRefusesAnotherFluid() {
        assertTrue(failure(OVERWORLD + ", \"liquids\": {\"aquifers\": \"dry\", \"defaultFluid\": \"minecraft:water\"}")
                .startsWith("t: liquids.defaultFluid: "));
    }

    @Test
    void anUnknownAquiferModeIsAnError() {
        assertTrue(failure(OVERWORLD + ", \"liquids\": {\"aquifers\": \"soggy\"}").startsWith("t: liquids.aquifers: `soggy`"));
    }

    @Test
    void lavaLevelGoesToTheRuntimeFileAndLeavesTheSettingsAlone() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"liquids\": {\"lavaLevel\": -30}");
        assertEquals(-30, file(out, RUNTIME).get("lava_level").getAsInt());
        assertFalse(out.files().containsKey(SETTINGS));
        assertEquals("minecraft:overworld", file(out, "data/customdimensions/lazy_dimension/t.json")
                .getAsJsonObject("generator").get("settings").getAsString());
    }

    @Test
    void aBaseWorldsRuntimeFileNamesItsStem() {
        DatapackCompiler.Output out = Fixtures.compile("overworld", OVERWORLD + ", \"liquids\": {\"lavaLevel\": -40}");
        assertEquals("minecraft:overworld", file(out, "data/customdimensions/worldgen_runtime/overworld.json")
                .get("level").getAsString());
    }

    @Test
    void levelsOutsideTheDimensionTypeAreErrors() {
        String lava = failure(OVERWORLD + ", \"liquids\": {\"lavaLevel\": -100}");
        assertEquals("t: liquids.lavaLevel: -100 is outside the dimension type's range, y -64 to 320", lava);
        String sea = failure(OVERWORLD + ", \"liquids\": {\"seaLevel\": 400}");
        assertEquals("t: liquids.seaLevel: 400 is outside the dimension type's range, y -64 to 320", sea);
        String custom = failure(OVERWORLD + ", \"dimensionType\": {\"minY\": 0, \"height\": 128}, \"liquids\": {\"lavaLevel\": 200}");
        assertTrue(custom.contains("y 0 to 128"), custom);
    }

    @Test
    void aSeaLevelBelowTheFloorIsANote() {
        DatapackCompiler.Output out = compile("\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"liquids\": {\"seaLevel\": -60}");
        assertTrue(out.report().entry("t").notes().stream().anyMatch(n -> n.contains("has no sea")),
                out.report().entry("t").notes().toString());
    }

    // ---- materials -------------------------------------------------------------------------

    @Test
    void aPaletteBecomesTheDimensionsOwnMaterialRule() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"materials\": {\"top\": \"minecraft:moss_block\", "
                + "\"filler\": \"minecraft:packed_mud\", \"deep\": \"minecraft:smooth_basalt\"}");
        assertEquals("adventure:t", file(out, SETTINGS).get("material_rule").getAsString());
        List<JsonElement> seq = sequence(file(out, RULE));
        assertEquals("minecraft:bedrock_floor", seq.get(0).getAsString());
        assertEquals("minecraft:overworld/copper_ore_vein", seq.get(1).getAsString());
        assertEquals("minecraft:overworld/iron_ore_vein", seq.get(2).getAsString());
        JsonObject palette = seq.get(3).getAsJsonObject();
        List<JsonElement> parts = sequence(palette);
        JsonObject gated = parts.get(0).getAsJsonObject();
        assertEquals("minecraft:above_preliminary_surface", gated.getAsJsonObject("if_true").get("type").getAsString());
        assertTrue(gated.toString().contains("minecraft:moss_block") && gated.toString().contains("minecraft:packed_mud"));
        assertEquals("{\"result_state\":\"minecraft:smooth_basalt\",\"type\":\"minecraft:block\"}", parts.get(1).toString());
        assertEquals("minecraft:overworld/underground", seq.getLast().getAsString());
    }

    @Test
    void floorFieldsAreGatedOnlyWhereThePresetGates() {
        DatapackCompiler.Output out = compile("\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"materials\": {\"top\": \"minecraft:moss_block\"}");
        String rule = out.files().get(RULE);
        assertFalse(rule.contains("above_preliminary_surface"), rule);
        assertTrue(rule.contains("minecraft:on_floor"));
    }

    @Test
    void perBiomePalettesComeFirstUnderABiomeCondition() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"materials\": {\"top\": \"minecraft:moss_block\", "
                + "\"biomes\": {\"minecraft:desert\": {\"top\": \"minecraft:red_sand\"}, \"#minecraft:is_badlands\": {\"ceilings\": \"minecraft:glowstone\"}}}");
        List<JsonElement> seq = sequence(file(out, RULE));
        JsonObject desert = seq.get(3).getAsJsonObject().getAsJsonObject("if_true");
        assertEquals("minecraft:biome", desert.get("type").getAsString());
        assertEquals("[\"minecraft:desert\"]", desert.get("biome_is").toString());
        assertEquals("#minecraft:is_badlands", seq.get(4).getAsJsonObject().getAsJsonObject("if_true").get("biome_is").getAsString());
        assertTrue(seq.get(5).toString().contains("minecraft:moss_block"));
    }

    @Test
    void beachesAndCliffsUseSeaLevelAndSteepness() {
        String rule = compile(OVERWORLD + ", \"liquids\": {\"seaLevel\": 40}, \"materials\": {\"beaches\": \"minecraft:gravel\", "
                + "\"cliffs\": \"minecraft:tuff\"}").files().get(RULE);
        assertTrue(rule.contains("\"absolute\": 36") && rule.contains("\"absolute\": 43"), rule);
        assertTrue(rule.contains("minecraft:steep"));
    }

    @Test
    void aDefinitionWithOnlyADefaultBlockKeepsThePresetsRule() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"materials\": {\"defaultBlock\": \"minecraft:deepslate\"}");
        assertFalse(out.files().containsKey(RULE));
        assertEquals("minecraft:overworld", file(out, SETTINGS).get("material_rule").getAsString());
    }

    @Test
    void the264FallbackWrapsTheDimensionsRule() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"materials\": {\"defaultBlock\": \"minecraft:deepslate\", "
                + "\"top\": \"minecraft:moss_block\"}", VersionTable.V26_4);
        JsonObject rule = file(out, SETTINGS).getAsJsonObject("material_rule");
        assertEquals("adventure:t", rule.getAsJsonArray("sequence").get(0).getAsString());
    }

    @Test
    void badMaterialsAreErrorsNamingTheField() {
        assertEquals("t: materials.roof: is not a materials field; known: defaultBlock, top, filler, deep, cliffs, beaches, ceilings, biomes",
                failure(OVERWORLD + ", \"materials\": {\"roof\": \"minecraft:stone\"}"));
        assertTrue(failure(OVERWORLD + ", \"materials\": {\"top\": \"Stone\"}").startsWith("t: materials.top: must be a block id"));
        assertTrue(failure(OVERWORLD + ", \"materials\": {\"biomes\": {\"minecraft:desert\": {\"floor\": \"minecraft:sand\"}}}")
                .startsWith("t: materials.biomes[minecraft:desert].floor: "));
        assertTrue(failure(OVERWORLD + ", \"materials\": {\"biomes\": {\"desert\": {\"top\": \"minecraft:sand\"}}}")
                .startsWith("t: materials.biomes[desert]: "));
        assertTrue(failure(OVERWORLD + ", \"materials\": {\"biomes\": {\"minecraft:desert\": {}}}").contains("sets no palette field"));
    }

    @Test
    void aFlatGeneratorReportsPalettesAndOres() {
        DatapackCompiler.Output out = compile("\"generator\": {\"type\": \"flat\"}, \"materials\": {\"top\": \"minecraft:moss_block\"}, "
                + "\"ores\": {\"veins\": false}");
        List<String> unsupported = out.report().entry("t").unsupported();
        assertTrue(unsupported.stream().anyMatch(u -> u.startsWith("`materials.top`")), unsupported.toString());
        assertTrue(unsupported.stream().anyMatch(u -> u.startsWith("`ores`")), unsupported.toString());
    }

    @Test
    void aCloneTakesItsSourcesPaletteUnderItsOwn() {
        DatapackCompiler.Output out = DatapackCompiler.compileAll("adventure", java.util.Map.of(
                "src", Fixtures.definition(OVERWORLD + ", \"materials\": {\"top\": \"minecraft:moss_block\", \"deep\": \"minecraft:tuff\"}"),
                "copy", Fixtures.definition("\"generator\": {\"type\": \"clone\", \"of\": \"adventure:src\"}, "
                        + "\"materials\": {\"top\": \"minecraft:red_sand\"}, \"liquids\": {\"aquifers\": \"dry\"}")),
                Fixtures.vanilla(), VersionTable.V26_3);
        String rule = out.files().get("data/adventure/worldgen/material_rule/copy.json");
        assertTrue(rule.contains("minecraft:red_sand") && rule.contains("minecraft:tuff") && !rule.contains("minecraft:moss_block"), rule);
        assertTrue(out.files().containsKey("data/customdimensions/worldgen_runtime/copy.json"));
    }

    // ---- ores ------------------------------------------------------------------------------

    @Test
    void veinsFalseRemovesThePresetsVeins() {
        String rule = compile(OVERWORLD + ", \"ores\": {\"veins\": false}").files().get(RULE);
        assertFalse(rule.contains("ore_vein"), rule);
        assertTrue(rule.contains("minecraft:bedrock_floor"));
    }

    @Test
    void veinsTrueChangesNothing() {
        assertNull(compile(OVERWORLD + ", \"ores\": {\"veins\": true}").files().get(SETTINGS));
    }

    @Test
    void aVeinListReplacesThemWithVanillasShape() {
        List<JsonElement> seq = sequence(file(compile(OVERWORLD + ", \"ores\": {\"veins\": [{\"like\": \"iron\", "
                + "\"ore\": \"minecraft:gold_ore\", \"filler\": \"minecraft:calcite\", \"rawOreChance\": 0.1}]}"), RULE));
        JsonObject vein = seq.get(1).getAsJsonObject();
        assertEquals("minecraft:ore_vein", vein.get("type").getAsString());
        assertEquals("minecraft:gold_ore", vein.get("ore_block").getAsString());
        assertEquals("minecraft:gold_ore", vein.get("raw_ore_block").getAsString());
        assertEquals("minecraft:calcite", vein.get("filler_block").getAsString());
        assertEquals(0.1, vein.get("raw_ore_chance").getAsDouble(), 1e-9);
        assertEquals("minecraft:overworld/ore_vein/iron_density", vein.get("density").getAsString());
        assertEquals(4, seq.size(), "bedrock, one vein, the surface gate, underground");
    }

    @Test
    void addedOresBecomeFeaturesAndRemovedOnesGoToTheRuntime() {
        DatapackCompiler.Output out = compile(OVERWORLD + ", \"materials\": {\"deep\": \"minecraft:smooth_basalt\"}, "
                + "\"ores\": {\"add\": [{\"id\": \"gilded\", \"block\": \"minecraft:nether_gold_ore\", \"size\": 6, \"count\": 12, "
                + "\"minY\": -32, \"maxY\": 48, \"shape\": \"trapezoid\"}], \"remove\": [\"minecraft:ore_diamond\"]}");
        JsonObject feature = file(out, "data/adventure/worldgen/feature/ores/t/gilded.json");
        assertEquals("minecraft:ore", feature.get("type").getAsString());
        assertEquals(6, feature.get("size").getAsInt());
        JsonArray targets = feature.getAsJsonArray("targets");
        assertEquals(3, targets.size());
        assertEquals("minecraft:smooth_basalt", targets.get(2).getAsJsonObject().getAsJsonObject("target").get("block").getAsString());
        JsonObject placed = file(out, "data/adventure/worldgen/placed_feature/ores/t/gilded.json");
        assertEquals("adventure:ores/t/gilded", placed.get("feature").getAsString());
        assertTrue(placed.toString().contains("minecraft:trapezoid") && placed.toString().contains("12"));
        JsonObject runtime = file(out, RUNTIME);
        assertEquals("[\"adventure:ores/t/gilded\"]", runtime.get("add_underground_ores").toString());
        assertEquals("[\"minecraft:ore_diamond\"]", runtime.get("remove_features").toString());
    }

    @Test
    void badOresAreErrorsNamingTheField() {
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"remove\": [\"minecraft:ore_unobtainium\"]}")
                .startsWith("t: ores.remove[0]: `minecraft:ore_unobtainium` is not a vanilla placed feature"));
        String ore = "{\"id\": \"a\", \"block\": \"minecraft:stone\", \"count\": 1, \"minY\": 0, \"maxY\": 10}";
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"add\": [" + ore + ", " + ore + "]}").startsWith("t: ores.add[1].id: `a` is used twice"));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"add\": [{\"id\": \"a\", \"block\": \"minecraft:stone\", \"count\": 1, \"minY\": 20, \"maxY\": 10}]}")
                .startsWith("t: ores.add[0].minY: 20 is above maxY 10"));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"add\": [{\"id\": \"a\", \"block\": \"minecraft:stone\", \"minY\": 0, \"maxY\": 10}]}")
                .startsWith("t: ores.add[0].count: is required"));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"veins\": [{\"like\": \"gold\", \"ore\": \"minecraft:gold_ore\"}]}")
                .startsWith("t: ores.veins[0].like: `gold` is not one of copper, iron"));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"veins\": \"some\"}").startsWith("t: ores.veins: "));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"extra\": 1}").startsWith("t: ores.extra: "));
        assertTrue(failure(OVERWORLD + ", \"ores\": {\"add\": [{\"id\": \"a\", \"block\": \"minecraft:stone\", \"count\": 300, \"minY\": 0, \"maxY\": 1}]}")
                .startsWith("t: ores.add[0].count: 300 is outside 0 to 256"));
    }

    @Test
    void aDefinitionWithoutTheseLayersEmitsNoneOfTheirFiles() {
        DatapackCompiler.Output out = compile(OVERWORLD);
        assertFalse(out.files().keySet().stream().anyMatch(p -> p.contains("material_rule") || p.contains("worldgen_runtime")
                || p.contains("/ores/")), out.files().keySet().toString());
    }
}
