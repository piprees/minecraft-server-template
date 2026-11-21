package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Set pieces through the compiler against the extracted vanilla data. */
class SetPiecesCompilerTest {

    static final String BODY = """
            "generator": {"type": "noise", "preset": "overworld"},
            "structures": {"age": "ancient", "force": [{"structure": "adventure:set_piece/pits/bone_pit", "x": 0, "z": 0}],
              "sites": {"minecraft:igloo": {"relative": "summit", "water": {"min": 32}}}},
            "setPieces": {
              "bone_pit": {"name": "The Bone Pit", "size": 20, "biomes": ["minecraft:plains", "#minecraft:is_forest"],
                "elements": [{"kind": "template", "id": "minecraft:fossil/skull_2"},
                             {"kind": "chest", "loot": "minecraft:chests/simple_dungeon", "offset": [4, 0, 0]}]},
              "ash_field": {"name": "The Ash Field", "size": 10, "group": "deco", "rarity": "uncommon", "repetition": 3, "age": "recent",
                "elements": [{"kind": "scatter", "count": 8, "radius": 8, "items": [{"block": "minecraft:gravel"}]}]}
            }""";

    static JsonObject json(DatapackCompiler.Output out, String path) {
        String text = out.files().get(path);
        assertTrue(text != null, path + " is not emitted: " + out.files().keySet().stream().filter(k -> k.contains("set_piece")).toList());
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void writesTheStructuresTheirTagAndTheGroupSet() {
        DatapackCompiler.Output out = Fixtures.compile("pits", BODY);
        JsonObject pit = json(out, VanillaForms.runtimePath("data/adventure/worldgen/structure/set_piece/pits/bone_pit.json"));
        assertEquals("customdimensions:set_piece", pit.get("type").getAsString());
        assertEquals("#adventure:set_piece/pits/bone_pit", pit.get("biomes").getAsString());
        assertEquals("bury", pit.get("terrain_adaptation").getAsString(), "the dimension's ancient age buries it");
        assertEquals("ancient", pit.getAsJsonObject("set_piece").get("age").getAsString());
        JsonObject vanilla = json(out, "data/adventure/worldgen/structure/set_piece/pits/bone_pit.json");
        assertEquals("minecraft:buried_treasure", vanilla.get("type").getAsString(), "a vanilla server loads it and places it nowhere");
        assertEquals(0, vanilla.getAsJsonArray("biomes").size());
        JsonObject tag = json(out, "data/adventure/tags/worldgen/biome/set_piece/pits/bone_pit.json");
        assertEquals(2, tag.getAsJsonArray("values").size());
        assertFalse(out.files().containsKey("data/adventure/worldgen/structure_set/set_piece/pits/bone_pit.json"), "force only: no set");

        JsonObject field = json(out, VanillaForms.runtimePath("data/adventure/worldgen/structure/set_piece/pits/ash_field.json"));
        assertEquals(0, field.getAsJsonArray("biomes").size());
        assertEquals("recent", field.getAsJsonObject("set_piece").get("age").getAsString());
        JsonObject set = json(out, "data/adventure/worldgen/structure_set/set_piece/pits/ash_field.json");
        assertEquals("minecraft:random_spread", set.getAsJsonObject("placement").get("type").getAsString());

        JsonObject control = json(out, StructuresCompiler.path("pits"));
        assertEquals(2, control.getAsJsonArray("setPieces").size());
        JsonObject entry = control.getAsJsonArray("setPieces").get(1).getAsJsonObject();
        assertEquals("adventure:set_piece/pits/ash_field", entry.get("set").getAsString());
        assertEquals("deco", entry.get("group").getAsString());
        assertEquals(3, entry.get("repetition").getAsInt());
        assertEquals("summit", control.getAsJsonObject("sites").getAsJsonObject("minecraft:igloo").getAsJsonObject("relative").get("kind").getAsString());
        assertFalse(out.reportText().contains("`adventure:set_piece/pits/bone_pit` is not a vanilla or bridged structure"), out.reportText());
    }

    @Test
    void templateSizesAreReadFromVanillasNbt() {
        int[] skull = SetPiecesCompiler.catalogue(Fixtures.vanilla()).templateSize("minecraft:fossil/skull_2");
        assertEquals(3, skull.length);
        assertTrue(skull[0] > 0 && skull[1] > 0 && skull[2] > 0);
        int[] ship = SetPiecesCompiler.catalogue(Fixtures.vanilla()).templateSize("minecraft:end_city/ship");
        assertTrue(ship[1] > 10 && Math.max(ship[0], ship[2]) > 20, java.util.Arrays.toString(ship));
        assertArrayEquals(null, SetPiecesCompiler.catalogue(Fixtures.vanilla()).templateSize("minecraft:fossil/skull_99"));
    }

    @Test
    void aBadSpecStopsTheCompileWithTheSlugAndName() {
        String noTemplate = BODY.replace("minecraft:fossil/skull_2", "minecraft:fossil/skull_99");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("pits", noTemplate)).getMessage()
                .contains("pits: setPieces.bone_pit.elements[0]: template `minecraft:fossil/skull_99` is not a vanilla structure template"));
        String ship = BODY.replace("minecraft:fossil/skull_2", "minecraft:end_city/ship");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("pits", ship)).getMessage()
                .contains("pits: setPieces.bone_pit: elements[0] (template) reaches"));
        String unknown = BODY.replace("\"size\": 20,", "\"size\": 20, \"glow\": true,");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("pits", unknown)).getMessage()
                .contains("pits: setPieces.bone_pit: glow is not a set-piece field"));
        String badSite = BODY.replace("\"relative\": \"summit\"", "\"relative\": \"crag\"");
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("pits", badSite));
        String badAge = BODY.replace("\"age\": \"ancient\"", "\"age\": \"bronze\"");
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("pits", badAge));
    }

    @Test
    void theLibraryIsMergedUnderTheSpec() {
        Map<String, JsonObject> library = Map.of("crater", JsonParser.parseString(
                "{\"seat\": \"ground\", \"size\": 12, \"elements\": [{\"kind\": \"block\", \"block\": \"minecraft:magma_block\"}]}").getAsJsonObject());
        DatapackCompiler.Output out = DatapackCompiler.compileAll(Fixtures.NAMESPACE, Map.of("pits", Fixtures.definition(
                "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"setPieces\": {\"crater\": {\"like\": \"crater\", \"name\": \"The Crater\"}}")),
                Fixtures.vanilla(), VersionTable.V26_3, library);
        JsonObject s = json(out, VanillaForms.runtimePath("data/" + Fixtures.NAMESPACE + "/worldgen/structure/set_piece/pits/crater.json")).getAsJsonObject("set_piece");
        assertEquals("The Crater", s.get("name").getAsString());
        assertEquals(12, s.get("size").getAsInt());
    }
}
