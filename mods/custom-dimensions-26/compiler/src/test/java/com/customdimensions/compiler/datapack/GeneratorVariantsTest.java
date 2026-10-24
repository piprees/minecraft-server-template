package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each generator, liquids and materials tuning field against its golden diff
 * ({@link GoldenForm}: the change from the vanilla file it starts from), and
 * clone resolution.
 */
class GeneratorVariantsTest {

    private static final String AVIARY = "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, "
            + "\"biomes\": [\"minecraft:cherry_grove\", \"minecraft:meadow\"]";
    private static final String PL_CLONE = "\"generator\": {\"type\": \"clone\", \"of\": \"paradise_lost:paradise_lost\"}";

    /** The golden form of {@code path} in a one-definition compile of {@code body}. */
    private static JsonElement golden(String slug, String body, String path, VersionTable.Version version) {
        DatapackCompiler.Output out = Fixtures.compile(slug, body, version);
        return golden(out, slug, path);
    }

    private static JsonElement golden(DatapackCompiler.Output out, String slug, String path) {
        String text = out.files().get(path);
        assertTrue(text != null, path + " not emitted; emitted " + out.files().keySet());
        return JsonParser.parseString(GoldenForm.of(path, text, out.compiled().get(slug), Fixtures.vanilla()));
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static DatapackCompiler.Output compileAll(Map<String, String> bodies) {
        Map<String, Definition> defs = new LinkedHashMap<>();
        bodies.forEach((slug, body) -> defs.put(slug, Fixtures.definition(body)));
        return DatapackCompiler.compileAll(Fixtures.NAMESPACE, defs, Fixtures.vanilla(), VersionTable.V26_3);
    }

    private static final String SETTINGS = "data/adventure/worldgen/noise_settings/t.json";

    // ---- liquids and materials -----------------------------------------------------------------

    @Test
    void seaLevelIsTheOnlyChangeToTheNoiseSettings() {
        assertEquals(json("{\"base\": \"minecraft:caves\", \"changed\": {\"sea_level\": 20}}"),
                golden("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, \"liquids\": {\"seaLevel\": 20}",
                        SETTINGS, VersionTable.V26_3));
    }

    @Test
    void defaultFluidIsTheOnlyChangeToTheNoiseSettings() {
        assertEquals(json("{\"base\": \"minecraft:overworld\", \"changed\": {\"default_fluid\": \"minecraft:lava\"}}"),
                golden("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                        + "\"liquids\": {\"defaultFluid\": \"minecraft:lava\"}", SETTINGS, VersionTable.V26_3));
    }

    @Test
    void defaultBlockIsTheNoiseSettingsDefaultBlockIn263() {
        assertEquals(json("{\"base\": \"minecraft:nether\", \"changed\": {\"default_block\": \"minecraft:blackstone\"}}"),
                golden("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                        + "\"materials\": {\"defaultBlock\": \"minecraft:blackstone\"}", SETTINGS, VersionTable.V26_3));
    }

    @Test
    void defaultBlockIsTheMaterialRulesFallbackIn264() {
        JsonObject rule = DatapackCompiler.withFallback(json("\"minecraft:nether\""), "minecraft:blackstone");
        JsonObject expected = new JsonObject();
        expected.addProperty("base", "minecraft:nether");
        JsonObject changed = new JsonObject();
        changed.addProperty("material_rule", GoldenForm.sha1(rule));
        expected.add("changed", changed);
        expected.add("removed", json("[\"default_block\"]"));
        assertEquals(expected, golden("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
                + "\"materials\": {\"defaultBlock\": \"minecraft:blackstone\"}", SETTINGS, VersionTable.V26_4));
        assertEquals(json("{\"type\": \"minecraft:sequence\", \"sequence\": [\"minecraft:nether\", "
                + "{\"type\": \"minecraft:block\", \"result_state\": \"minecraft:blackstone\"}]}"), rule);
    }

    // ---- generator fields ----------------------------------------------------------------------

    @Test
    void endIslandFalseReplacesOnlyTheIslandFunction() {
        String body = "\"generator\": {\"type\": \"noise\", \"preset\": \"end\", \"endIsland\": false}";
        DatapackCompiler.Output out = Fixtures.compile("t", body);
        JsonObject diff = golden(out, "t", SETTINGS).getAsJsonObject();
        assertEquals("minecraft:end", diff.get("base").getAsString());
        assertEquals(java.util.Set.of("debug_functions", "noise_router"), diff.getAsJsonObject("changed").keySet());
        assertEquals(json("{\"type\": \"minecraft:cache\", \"input\": {\"type\": \"minecraft:end_outer_islands\"}}"),
                golden(out, "t", "data/adventure/worldgen/density_function/end_without_central_island/islands.json"));
        assertEquals(json("{\"type\": \"minecraft:add\", \"left\": \"adventure:end_without_central_island/islands\", "
                        + "\"right\": \"minecraft:end/base_3d_noise\"}"),
                golden(out, "t", "data/adventure/worldgen/density_function/end_without_central_island/sloped_cheese.json"));
        String router = out.files().get(SETTINGS);
        assertTrue(!router.contains("minecraft:end/islands") && !router.contains("minecraft:end/sloped_cheese"), router);
        assertTrue(out.report().entry("t").unsupported().isEmpty(), out.report().entry("t").unsupported().toString());
    }

    @Test
    void endIslandTrueKeepsTheVanillaEnd() {
        DatapackCompiler.Output out = Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"end\", \"endIsland\": true}");
        assertEquals("minecraft:end", JsonParser.parseString(out.files().get("data/customdimensions/lazy_dimension/t.json"))
                .getAsJsonObject().getAsJsonObject("generator").get("settings").getAsString());
    }

    @Test
    void endIslandOutsideTheEndPresetIsRefusedByTheReader() {
        assertThrows(com.customdimensions.core.definition.DefinitionFormatException.class, () -> Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\", \"endIsland\": false}"));
    }

    @Test
    void cellScaleIsTheCheckerboardScale() {
        assertEquals(json("{\"type\": \"adventure:t\", \"generator\": {\"type\": \"minecraft:noise\", \"biome_source\": "
                        + "{\"type\": \"minecraft:checkerboard\", \"biomes\": [\"minecraft:desert\", \"minecraft:snowy_plains\"], "
                        + "\"scale\": 4}, \"settings\": \"minecraft:overworld\"}}"),
                golden("t", "\"generator\": {\"type\": \"checkerboard\", \"cellScale\": 4}, "
                        + "\"biomes\": [\"minecraft:desert\", \"minecraft:snowy_plains\"]",
                        "data/customdimensions/lazy_dimension/t.json", VersionTable.V26_3));
    }

    @Test
    void flatLayersAndBiomeAreTheFlatSettings() {
        assertEquals(json("{\"type\": \"adventure:t\", \"generator\": {\"type\": \"minecraft:flat\", \"settings\": "
                        + "{\"biome\": \"minecraft:desert\", \"features\": false, \"lakes\": false, \"layers\": "
                        + "[{\"block\": \"minecraft:bedrock\", \"height\": 1}, {\"block\": \"minecraft:sandstone\", \"height\": 3}], "
                        + "\"structure_overrides\": []}}}"),
                golden("t", "\"generator\": {\"type\": \"flat\", \"biome\": \"minecraft:desert\", \"layers\": "
                        + "[{\"block\": \"minecraft:bedrock\", \"height\": 1}, {\"block\": \"minecraft:sandstone\", \"height\": 3}]}",
                        "data/customdimensions/lazy_dimension/t.json", VersionTable.V26_3));
    }

    @Test
    void singleBiomeIsAFixedSourceOnOverworldTerrain() {
        assertEquals(json("{\"type\": \"adventure:t\", \"generator\": {\"type\": \"minecraft:noise\", \"biome_source\": "
                        + "{\"type\": \"minecraft:fixed\", \"biome\": \"minecraft:badlands\"}, \"settings\": \"minecraft:overworld\"}}"),
                golden("t", "\"generator\": {\"type\": \"single_biome\", \"biome\": \"minecraft:badlands\"}",
                        "data/customdimensions/lazy_dimension/t.json", VersionTable.V26_3));
    }

    // ---- clones --------------------------------------------------------------------------------

    @Test
    void aParadiseLostCloneTakesTheAviarysGenerator() {
        DatapackCompiler.Output out = compileAll(Map.of("the_aviary", AVIARY, "c", PL_CLONE + ", \"seed\": 7"));
        JsonObject aviary = JsonParser.parseString(out.files().get("data/adventure/dimension/the_aviary.json")).getAsJsonObject();
        JsonObject clone = JsonParser.parseString(out.files().get("data/customdimensions/lazy_dimension/c.json")).getAsJsonObject();
        assertEquals(aviary.get("generator"), clone.get("generator"));
        assertEquals("adventure:c", clone.get("type").getAsString());
        assertEquals(CompileReport.Kind.LAZY, out.report().entry("c").kind());
        assertEquals("adventure:the_aviary", out.report().entry("c").cloneOf());
        assertTrue(out.reportText().contains("clone of `adventure:the_aviary`, which stands in for `paradise_lost:paradise_lost`"));
        assertEquals(1, out.report().totals(out.files()).get("Clones compiled"));
        assertEquals(0, out.report().totals(out.files()).get("Clones (not compiled)"));
    }

    @Test
    void aClonesOwnLiquidsTuneItsSourcesGeneratorAndItsBiomesAreReported() {
        DatapackCompiler.Output out = compileAll(Map.of("the_aviary", AVIARY,
                "c", PL_CLONE + ", \"liquids\": {\"seaLevel\": 90}, \"biomes\": [\"minecraft:jungle\"]"));
        assertEquals(json("{\"base\": \"minecraft:floating_islands\", \"changed\": {\"sea_level\": 90}}"),
                golden(out, "c", "data/adventure/worldgen/noise_settings/c.json"));
        assertTrue(out.report().entry("c").unsupported().stream().anyMatch(u -> u.startsWith("`biomes`") && u.contains("1 biome")));
    }

    @Test
    void aCloneOfACloneResolvesTransitivelyAndAVanillaStemByItsSlug() {
        DatapackCompiler.Output out = compileAll(Map.of(
                "the_nether", "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}",
                "a", "\"generator\": {\"type\": \"clone\", \"of\": \"minecraft:the_nether\"}",
                "b", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:a\"}"));
        assertEquals("adventure:the_nether", out.report().entry("b").cloneOf());
        assertTrue(out.reportText().contains("clone of `adventure:the_nether` via a"));
        assertEquals("the_nether", DatapackCompiler.baseDimensionType(out.compiled().get("b").generator()));
        assertEquals(json("{\"base\": \"minecraft:the_nether\", \"changed\": {}}"),
                golden(out, "b", "data/adventure/dimension_type/b.json"));
    }

    @Test
    void aCloneCycleIsACompileErrorNamingIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> compileAll(Map.of(
                "a", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:b\"}",
                "b", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:a\"}")));
        assertEquals("clone cycle: a -> b -> a", e.getMessage());
    }

    @Test
    void aCloneOfAnUnknownDimensionStaysReported() {
        DatapackCompiler.Output out = compileAll(Map.of("the_aviary", AVIARY,
                "c", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:the_c\"}",
                "d", "\"generator\": {\"type\": \"clone\", \"of\": \"adventure:c\"}"));
        assertEquals(CompileReport.Kind.CLONE, out.report().entry("c").kind());
        assertEquals(CompileReport.Kind.CLONE, out.report().entry("d").kind());
        assertNull(out.report().entry("d").cloneOf());
        assertTrue(out.reportText().contains("copies `adventure:c`, which no definition provides (via c)"), out.reportText());
        assertTrue(out.files().keySet().stream().noneMatch(p -> p.endsWith("/c.json") || p.endsWith("/d.json")));
    }

    // ---- Bedrock heights -----------------------------------------------------------------------

    @Test
    void vanillaHeightsFitTheirBedrockDimensions() {
        for (String type : new String[] {"overworld", "overworld_caves", "the_nether", "the_end"}) {
            assertNull(BedrockHeights.check(Fixtures.vanilla().dimensionType(type)), type);
        }
        assertEquals(BedrockHeights.NETHER, BedrockHeights.rangeOf(Fixtures.vanilla().dimensionType("the_nether")));
        assertEquals(BedrockHeights.END, BedrockHeights.rangeOf(Fixtures.vanilla().dimensionType("the_end")));
        assertEquals(BedrockHeights.OVERWORLD, BedrockHeights.rangeOf(Fixtures.vanilla().dimensionType("overworld")));
    }

    @Test
    void anEndSkyOnOverworldHeightsIsFlaggedNotRefused() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"atmosphere\": {\"effects\": \"minecraft:the_end\"}");
        assertEquals(java.util.List.of("y -64 to 320 shows as the Bedrock end (y 0 to 256); blocks below y 0 and blocks from "
                + "y 256 are invisible to Bedrock players"), out.report().entry("t").bedrock());
        assertEquals(1, out.report().totals(out.files()).get("Bedrock height warnings"));
        assertTrue(out.files().containsKey("data/customdimensions/lazy_dimension/t.json"));
    }

    @Test
    void heightsBeyondTheOverworldAreFlaggedAndTheNetherTopIsNot() {
        assertTrue(BedrockHeights.check(json("{\"min_y\": -128, \"height\": 384}").getAsJsonObject()).contains("below y -64"));
        assertTrue(BedrockHeights.check(json("{\"min_y\": -64, \"height\": 512}").getAsJsonObject()).contains("from y 320"));
        assertNull(BedrockHeights.check(json("{\"skybox\": \"none\", \"min_y\": 0, \"height\": 384}").getAsJsonObject()));
        assertTrue(BedrockHeights.check(json("{\"skybox\": \"none\", \"min_y\": -64, \"height\": 384}").getAsJsonObject())
                .contains("Bedrock nether"));
    }
}
