package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.DefinitionReader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Void generators that place their listed biomes, and checkerboard cells that carry no terrain. */
class VoidWorldsTest {

    private static JsonObject generator(DatapackCompiler.Output out, String slug) {
        String stem = out.files().entrySet().stream().filter(e -> e.getKey().endsWith("/" + slug + ".json") && e.getKey().contains("dimension/")
                && !e.getKey().contains("dimension_type")).map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
        return JsonParser.parseString(stem).getAsJsonObject().getAsJsonObject("generator");
    }

    private static JsonObject settings(DatapackCompiler.Output out, String slug) {
        return JsonParser.parseString(out.files().get("data/adventure/worldgen/noise_settings/" + slug + ".json")).getAsJsonObject();
    }

    private static Set<String> biomes(JsonObject source) {
        Set<String> out = new TreeSet<>();
        JsonArray list = source.has("base") ? source.getAsJsonObject("base").getAsJsonArray("biomes") : source.getAsJsonArray("biomes");
        list.forEach(e -> out.add(e.getAsJsonObject().get("biome").getAsString()));
        return out;
    }

    @Test
    void aVoidWithSeveralBiomesPlacesThemOverNoBlocks() {
        DatapackCompiler.Output out = Fixtures.compile("v", "\"generator\": {\"type\": \"void\"}, \"biomes\": ["
                + "{\"id\": \"minecraft:snowy_plains\", \"climate\": {\"weirdness\": [-2.0, 0.0]}}, "
                + "{\"id\": \"minecraft:ice_spikes\", \"climate\": {\"weirdness\": [0.0, 2.0]}}]");
        JsonObject g = generator(out, "v");
        assertEquals("minecraft:noise", g.get("type").getAsString());
        assertEquals("adventure:v", g.get("settings").getAsString());
        assertEquals(Set.of("minecraft:ice_spikes", "minecraft:snowy_plains"), biomes(g.getAsJsonObject("biome_source")));
        JsonObject s = settings(out, "v");
        assertEquals(-1.0, s.getAsJsonObject("noise_router").get("final_density").getAsDouble());
        assertFalse(s.has("aquifers"));
        assertEquals("minecraft:air", s.get("default_fluid").getAsString());
        assertEquals(s.getAsJsonObject("noise").get("min_y").getAsInt(), s.get("sea_level").getAsInt());
        assertTrue(out.reportText().contains("Void world placing its biomes"), out.reportText());
    }

    @Test
    void aVoidLaysOutRegions() {
        DatapackCompiler.Output out = Fixtures.compile("v", "\"generator\": {\"type\": \"void\"}, \"biomes\": [\"minecraft:plains\", "
                + "\"minecraft:desert\", \"minecraft:taiga\"], \"biomeLayout\": {\"mode\": \"regions\"}");
        JsonObject source = generator(out, "v").getAsJsonObject("biome_source");
        assertEquals("customdimensions:zoned", source.get("type").getAsString());
        assertTrue(source.has("regions"));
        assertEquals(Set.of("minecraft:desert", "minecraft:plains", "minecraft:taiga"), biomes(source));
    }

    @Test
    void aVoidWithOneBiomeStaysFlat() {
        for (String biomes : new String[]{"", ", \"biomes\": [\"minecraft:plains\"]", ", \"biomes\": [\"minecraft:plains\", \"minecraft:plains\"]"}) {
            JsonObject g = generator(Fixtures.compile("v", "\"generator\": {\"type\": \"void\"}" + biomes), "v");
            assertEquals("minecraft:flat", g.get("type").getAsString(), biomes);
        }
    }

    @Test
    void checkerboardVoidCellsHoldTheFinalDensityBelowZero() {
        DatapackCompiler.Output out = Fixtures.compile("c", "\"generator\": {\"type\": \"checkerboard\", \"cellScale\": 3, "
                + "\"voidBiomes\": [\"minecraft:the_end\"]}, \"biomes\": [\"minecraft:plains\", \"minecraft:the_end\", \"minecraft:desert\"]");
        assertEquals("adventure:c", generator(out, "c").get("settings").getAsString());
        JsonObject fd = settings(out, "c").getAsJsonObject("noise_router").getAsJsonObject("final_density");
        assertEquals("minecraft:min", fd.get("type").getAsString());
        String cells = fd.toString();
        assertTrue(cells.contains("\"type\":\"customdimensions:checker_cells\"") && cells.contains("\"cells\":[1]")
                && cells.contains("\"count\":3") && cells.contains("\"scale\":3"), cells);
        assertTrue(out.reportText().contains("128-block cells"), out.reportText());
    }

    @Test
    void checkerboardWithoutVoidCellsIsUnchanged() {
        DatapackCompiler.Output out = Fixtures.compile("c", "\"generator\": {\"type\": \"checkerboard\"}, \"biomes\": [\"minecraft:plains\"]");
        assertEquals("minecraft:overworld", generator(out, "c").get("settings").getAsString());
    }

    @Test
    void voidBiomesAreCheckedAgainstTheBoard() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("c", "\"generator\": {\"type\": \"checkerboard\", "
                + "\"voidBiomes\": [\"minecraft:desert\"]}, \"biomes\": [\"minecraft:plains\", \"minecraft:the_end\"]"));
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("c", "\"generator\": {\"type\": \"checkerboard\", "
                + "\"voidBiomes\": [\"minecraft:plains\"]}, \"biomes\": [\"minecraft:plains\"]"));
        assertThrows(RuntimeException.class, () -> DefinitionReader.read("{\"schemaVersion\": 2, \"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"overworld\", \"voidBiomes\": [\"minecraft:plains\"]}}", "x"));
    }
}
