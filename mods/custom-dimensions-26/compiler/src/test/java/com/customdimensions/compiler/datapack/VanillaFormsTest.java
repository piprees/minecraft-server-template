package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files vanilla reads hold vanilla forms; the compiled forms sit under {@code runtime_registry/} for the runtime to bind. */
class VanillaFormsTest {

    private static final String OVERWORLD = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
    private static final String CONTINENTS = "data/adventure/worldgen/density_function/terrain/overworld/continents.json";
    private static final String STEM = "data/minecraft/dimension/overworld.json";

    private static void noTypeOfOursWhereVanillaReads(DatapackCompiler.Output out) {
        out.files().forEach((path, text) -> {
            if (VanillaForms.vanillaVisible(path)) assertFalse(text.contains("\"customdimensions:"), path);
        });
    }

    @Test
    void aBaseWorldWithALandShareKeepsTheShiftForTheRuntimeOnly() {
        DatapackCompiler.Output out = Fixtures.compile("overworld", OVERWORLD + ", \"terrain\": {\"landShare\": 0.65}");
        noTypeOfOursWhereVanillaReads(out);
        String runtime = VanillaForms.runtimePath(CONTINENTS);
        assertEquals("data/customdimensions/runtime_registry/adventure/worldgen/density_function/terrain/overworld/continents.json", runtime);
        JsonObject bound = JsonParser.parseString(out.files().get(runtime)).getAsJsonObject();
        assertEquals("customdimensions:land_share", bound.getAsJsonObject("input").get("type").getAsString(), bound.toString());
        assertEquals(0.65, bound.getAsJsonObject("input").get("share").getAsDouble());
        JsonObject unshifted = JsonParser.parseString(out.files().get(CONTINENTS)).getAsJsonObject();
        assertEquals(bound.getAsJsonObject("input").get("argument"), unshifted, "vanilla reads the argument the shift wraps");
        assertTrue(out.report().entry("overworld").emitted().contains(runtime));
        assertFalse(VanillaForms.vanillaVisible(runtime));
    }

    @Test
    void theVanillaFormIsTheCompileWithoutTheField() {
        DatapackCompiler.Output with = Fixtures.compile("overworld", OVERWORLD + ", \"terrain\": {\"landShare\": 0.65, \"scales\": {\"continents\": 2}}");
        DatapackCompiler.Output without = Fixtures.compile("overworld", OVERWORLD + ", \"terrain\": {\"scales\": {\"continents\": 2}}");
        Map<String, String> vanilla = new TreeMap<>(with.files());
        vanilla.keySet().removeIf(p -> p.startsWith("data/customdimensions/" + VanillaForms.FOLDER + "/"));
        assertEquals(without.files(), vanilla);
    }

    @Test
    void aBaseWorldWithoutALandShareHasNoRuntimeForms() {
        DatapackCompiler.Output out = Fixtures.compile("overworld", OVERWORLD + ", \"terrain\": {\"scales\": {\"continents\": 2}}");
        noTypeOfOursWhereVanillaReads(out);
        assertTrue(out.files().keySet().stream().noneMatch(p -> p.contains("/" + VanillaForms.FOLDER + "/")), out.files().keySet().toString());
    }

    @Test
    void aZonedBaseWorldGivesVanillaItsBaseBiomeSource() {
        DatapackCompiler.Output out = Fixtures.compile("overworld", OVERWORLD
                + ", \"zones\": [{\"shape\": \"all\", \"swap\": {\"minecraft:forest\": \"minecraft:birch_forest\"}}]");
        noTypeOfOursWhereVanillaReads(out);
        JsonObject bound = JsonParser.parseString(out.files().get(VanillaForms.runtimePath(STEM))).getAsJsonObject()
                .getAsJsonObject("generator").getAsJsonObject("biome_source");
        assertEquals("customdimensions:zoned", bound.get("type").getAsString());
        JsonObject vanilla = JsonParser.parseString(out.files().get(STEM)).getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source");
        assertEquals(bound.get("base"), vanilla);
    }

    @Test
    void aLazyStemKeepsItsTypesWhereVanillaDoesNotRead() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD
                + ", \"zones\": [{\"shape\": \"all\", \"swap\": {\"minecraft:forest\": \"minecraft:birch_forest\"}}]");
        assertTrue(out.files().get("data/customdimensions/lazy_dimension/t.json").contains("customdimensions:zoned"));
        assertFalse(out.files().containsKey(VanillaForms.runtimePath("data/customdimensions/lazy_dimension/t.json")));
    }

    @Test
    void aTypeOfOursWithoutAVanillaFormIsRefusedWithItsFile() {
        Map<String, String> files = new TreeMap<>(Map.of(
                "data/adventure/worldgen/density_function/x.json", "{\"type\": \"minecraft:cache\", \"input\": {\"type\": \"customdimensions:new_shape\"}}",
                "data/customdimensions/lazy_dimension/x.json", "{\"type\": \"customdimensions:new_shape\"}"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> VanillaForms.check(files));
        assertTrue(e.getMessage().contains("data/adventure/worldgen/density_function/x.json")
                && e.getMessage().contains("customdimensions:new_shape"), e.getMessage());
        IllegalStateException split = assertThrows(IllegalStateException.class, () -> VanillaForms.split(new TreeMap<>(files), new TreeMap<>()));
        assertTrue(split.getMessage().contains("customdimensions:new_shape"), split.getMessage());
        files.remove("data/adventure/worldgen/density_function/x.json");
        VanillaForms.check(files);
    }

    @Test
    void everyTypeOfOursHasAVanillaFormVanillaParses() {
        assertEquals(JsonParser.parseString("0"), VanillaForms.vanillaForm(JsonParser.parseString("{\"type\": \"customdimensions:checker_cells\"}")));
        assertEquals(1_000_000, VanillaForms.vanillaForm(JsonParser.parseString("{\"type\": \"customdimensions:zone_distance\"}")).getAsInt());
        assertEquals(JsonParser.parseString("{\"type\": \"minecraft:cache\", \"input\": 1}"), VanillaForms.vanillaForm(JsonParser.parseString(
                "{\"type\": \"minecraft:cache\", \"input\": {\"type\": \"customdimensions:land_share\", \"argument\": "
                        + "{\"type\": \"minecraft:cache\", \"input\": 1}}}")), "a cache of a cache folds to one");
    }

    @Test
    void theShippedPackHasNoTypeOfOursWhereVanillaReads() {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        noTypeOfOursWhereVanillaReads(out);
        for (String path : new String[]{CONTINENTS, STEM.replace("overworld", "the_nether"), "data/adventure/dimension/the_aviary.json"}) {
            assertTrue(out.files().get(VanillaForms.runtimePath(path)).contains("\"customdimensions:"), path);
        }
    }
}
