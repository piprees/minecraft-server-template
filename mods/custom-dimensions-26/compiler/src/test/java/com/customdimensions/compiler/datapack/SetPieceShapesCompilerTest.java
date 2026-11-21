package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shapes, seats, footings, approaches, the lived-in layer and age by distance through the compiler. */
class SetPieceShapesCompilerTest {

    static final String BODY = """
            "generator": {"type": "noise", "preset": "overworld"},
            "spawn": [100, 64, -100],
            "structures": {"age": {"recent": 0, "old": 1024, "ancient": 3072},
              "terrainAdaptation": {"minecraft:village_plains": "terrace"},
              "approaches": {"minecraft:village_plains": {"kind": "path", "to": "spawn"}},
              "force": [{"structure": "adventure:set_piece/marks/crater", "x": 0, "z": 0}]},
            "setPieces": {
              "crater": {"name": "The Crater", "size": 24, "approach": {"kind": "path"},
                "elements": [{"kind": "crater", "radius": 12, "depth": 5, "rim": 2, "lining": "minecraft:basalt"}]},
              "house": {"name": "The House", "size": 12, "seat": "pinned", "age": "old", "terrainAdaptation": "footing",
                "elements": [{"kind": "template", "id": "minecraft:fossil/skull_2"}]},
              "cairn": {"name": "A Cairn", "size": 3, "lived": {"density": 8, "siting": ["path", "view"]},
                "elements": [{"kind": "column", "height": 2, "radius": 0}]}
            }""";

    @Test
    void writesShapesFootingsApproachesLivedAndBands() {
        DatapackCompiler.Output out = Fixtures.compile("marks", BODY);
        JsonObject crater = SetPiecesCompilerTest.json(out, VanillaForms.runtimePath("data/adventure/worldgen/structure/set_piece/marks/crater.json"));
        assertEquals("none", crater.get("terrain_adaptation").getAsString(), "a piece with shapes is not bearded");
        JsonObject spec = crater.getAsJsonObject("set_piece");
        assertFalse(spec.has("age"), "no age of its own: it takes the bands");
        assertEquals(1024, spec.getAsJsonObject("bands").get("old").getAsInt());
        assertEquals(100, spec.getAsJsonObject("bands").get("centreX").getAsInt());
        assertEquals("path", spec.getAsJsonArray("approach").get(0).getAsJsonObject().get("kind").getAsString());
        JsonObject house = SetPiecesCompilerTest.json(out, VanillaForms.runtimePath("data/adventure/worldgen/structure/set_piece/marks/house.json"));
        assertEquals("none", house.get("terrain_adaptation").getAsString(), "vanilla sees none; our footing pass fills under it");
        assertEquals("footing", house.getAsJsonObject("set_piece").get("terrainAdaptation").getAsString());
        assertEquals("old", house.getAsJsonObject("set_piece").get("age").getAsString());
        JsonObject control = SetPiecesCompilerTest.json(out, StructuresCompiler.path("marks"));
        assertTrue(control.getAsJsonArray("setPieces").get(2).getAsJsonObject().get("lived").getAsBoolean());
        assertEquals("terrace", control.getAsJsonObject("terrainAdaptation").get("minecraft:village_plains").getAsString());
        assertEquals("spawn", control.getAsJsonObject("approaches").getAsJsonArray("minecraft:village_plains").get(0).getAsJsonObject()
                .get("to").getAsString());
    }

    @Test
    void badShapesAndSettingsStopTheCompileWithTheSlug() {
        String m = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("marks", BODY.replace("\"radius\": 12, \"depth\": 5", "\"radius\": 30, \"depth\": 5")))
                .getMessage();
        assertTrue(m.contains("marks: setPieces.crater") && m.contains("past its size"), m);
        m = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("marks", BODY.replace("\"jitter\"", "\"x\"")
                .replace("\"ancient\": 3072}", "\"ancient\": 3072, \"older\": 9000}"))).getMessage();
        assertTrue(m.contains("structures.age") && m.contains("older"), m);
        m = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("marks", BODY.replace("{\"kind\": \"path\", \"to\": \"spawn\"}",
                "{\"kind\": \"rope\"}"))).getMessage();
        assertTrue(m.contains("structures.approaches"), m);
    }
}
