package com.customdimensions.core.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetPieceSpecTest {

    /** Vanilla's fossil and ship sizes; nothing else is a template. */
    static final SetPieceSpec.Catalogue VANILLA = new SetPieceSpec.Catalogue() {
        public int[] templateSize(String id) {
            return switch (id) {
                case "minecraft:fossil/skull_2" -> new int[] {5, 5, 7};
                case "minecraft:end_city/ship" -> new int[] {13, 24, 29};
                default -> null;
            };
        }

        public boolean block(String id) {
            return !id.equals("minecraft:unobtainium");
        }

        public boolean entity(String id) {
            return id.equals("minecraft:skeleton");
        }

        public boolean lootTable(String id) {
            return id.startsWith("minecraft:chests/");
        }

        public boolean processorList(String id) {
            return id.equals("minecraft:zombie_plains");
        }
    };

    static final String BONE_PIT = """
            {"name": "The Bone Pit", "seat": "ground", "size": 24, "age": "old", "group": "landmarks", "rarity": "rare", "repetition": 2,
             "site": {"relative": "valley"}, "slope": 6,
             "palette": {"bone": ["minecraft:bone_block", {"block": "minecraft:bone_block[axis=x]", "weight": 3}]},
             "elements": [
               {"kind": "template", "id": "minecraft:fossil/skull_2", "integrity": 0.8, "offset": [2, 0, -3]},
               {"kind": "scatter", "count": 12, "radius": 10, "spacing": 2, "items": [{"block": "bone", "weight": 3}, {"block": "minecraft:skeleton_skull"}]},
               {"kind": "chest", "loot": "minecraft:chests/simple_dungeon", "offset": [0, 0, 4]},
               {"kind": "spawner", "entity": "minecraft:skeleton", "delay": 200, "range": 4, "offset": [0, -2, 0]}
             ]}""";

    static SetPieceSpec resolve(String json) {
        return SetPieceSpec.resolve("the_test", "pit", JsonParser.parseString(json).getAsJsonObject(), Map.of(), null, VANILLA);
    }

    static String message(String json) {
        return assertThrows(IllegalArgumentException.class, () -> resolve(json)).getMessage();
    }

    @Test
    void theSpecRoundTrips() {
        SetPieceSpec s = resolve(BONE_PIT);
        assertEquals("the_test/pit", s.key());
        assertEquals("The Bone Pit", s.name());
        assertEquals(Age.OLD, s.age());
        assertEquals(6, s.site().slope());
        assertEquals(24, s.site().radius());
        assertEquals(4, s.elements().size());
        assertEquals("beard_thin", s.terrainAdaptation());
        JsonObject json = s.toJson();
        SetPieceSpec back = SetPieceSpec.fromJson(json);
        assertEquals(json, back.toJson());
        assertEquals(s.site(), back.site());
        assertEquals(s.palette(), back.palette());
    }

    @Test
    void anUnknownFieldStopsTheCompileWithTheSlugAndName() {
        String m = message(BONE_PIT.replace("\"seat\": \"ground\"", "\"seat\": \"ground\", \"marker\": true"));
        assertTrue(m.startsWith("the_test: setPieces.pit: marker is not a set-piece field"), m);
        String e = message(BONE_PIT.replace("\"integrity\": 0.8", "\"integrity\": 0.8, \"tint\": 3"));
        assertTrue(e.startsWith("the_test: setPieces.pit.elements[0]: tint is not an element field"), e);
    }

    @Test
    void aTemplateVanillaLacksStopsTheCompile() {
        String m = message(BONE_PIT.replace("minecraft:fossil/skull_2", "minecraft:fossil/skull_9"));
        assertTrue(m.contains("the_test: setPieces.pit.elements[0]: template `minecraft:fossil/skull_9` is not a vanilla structure template"), m);
    }

    @Test
    void elementsReachingPastTheSizeStopTheCompile() {
        String m = message(BONE_PIT.replace("\"size\": 24", "\"size\": 9"));
        assertTrue(m.contains("the_test: setPieces.pit: elements[1] (scatter) reaches 10 blocks across"), m);
        String ship = message("{\"size\": 16, \"elements\": [{\"kind\": \"template\", \"id\": \"minecraft:end_city/ship\"}]}");
        assertTrue(ship.contains("elements[0] (template) reaches 15 blocks across and 24 up, past its size 16"), ship);
        assertTrue(message(BONE_PIT.replace("\"size\": 24", "\"size\": 65")).contains("size must be 1 to 64"));
    }

    @Test
    void otherBadValuesStopTheCompile() {
        assertTrue(message(BONE_PIT.replace("minecraft:skeleton\"", "minecraft:skeletor\"")).contains("entity `minecraft:skeletor`"));
        assertTrue(message(BONE_PIT.replace("chests/simple_dungeon", "gameplay/fishing")).contains("loot `minecraft:gameplay/fishing`"));
        assertTrue(message(BONE_PIT.replace("\"seat\": \"ground\"", "\"seat\": \"roof\"")).contains("seat `roof`"));
        assertTrue(message(BONE_PIT.replace("minecraft:skeleton_skull", "minecraft:unobtainium")).contains("`minecraft:unobtainium` is not a vanilla block"));
        assertTrue(message(BONE_PIT.replace("\"block\": \"bone\"", "\"block\": \"marrow\"")).contains("`marrow` is neither a palette name"));
        assertTrue(message(BONE_PIT.replace("\"age\": \"old\"", "\"age\": \"medieval\"")).contains("age `medieval`"));
        assertTrue(message(BONE_PIT.replace("\"kind\": \"chest\"", "\"kind\": \"crater\"")).contains("kind `crater`"));
        assertTrue(message("{\"elements\": [{\"kind\": \"chest\", \"loot\": \"minecraft:chests/x\"}]}").contains("size is required"));
    }

    @Test
    void likeMergesTheLibraryUnderTheSpec() {
        Map<String, JsonObject> library = Map.of("bone_pit", JsonParser.parseString(BONE_PIT).getAsJsonObject(),
                "deep_pit", JsonParser.parseString("{\"like\": \"bone_pit\", \"age\": \"ancient\", \"palette\": {\"rubble\": [\"minecraft:gravel\"]}}")
                        .getAsJsonObject());
        SetPieceSpec s = SetPieceSpec.resolve("the_test", "mine", JsonParser.parseString(
                "{\"like\": \"deep_pit\", \"name\": \"My Pit\", \"palette\": {\"bone\": [\"minecraft:bone_block\"]}}").getAsJsonObject(),
                library, null, VANILLA);
        assertEquals("My Pit", s.name());
        assertEquals(Age.ANCIENT, s.age());
        assertEquals("bury", s.terrainAdaptation(), "an ancient piece is buried by default");
        assertEquals(List.of(new SetPieceSpec.Weighted("minecraft:bone_block", 1)), s.palette().get("bone"));
        assertEquals(List.of(new SetPieceSpec.Weighted("minecraft:gravel", 1)), s.palette().get("rubble"));
        assertEquals(4, s.elements().size());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SetPieceSpec.resolve("the_test", "x",
                JsonParser.parseString("{\"like\": \"nope\"}").getAsJsonObject(), library, null, VANILLA)).getMessage()
                .contains("like `nope` is not in the set-piece library"));
    }

    @Test
    void theDimensionAgeIsTheDefault() {
        SetPieceSpec s = SetPieceSpec.resolve("the_test", "pit", JsonParser.parseString(BONE_PIT.replace("\"age\": \"old\", ", ""))
                .getAsJsonObject(), Map.of(), Age.DEEP, VANILLA);
        assertEquals(Age.DEEP, s.age());
    }

    /** The plan written into a region: every position and what lands there. */
    static Map<String, String> region(SetPieceSpec spec, long seed, int rotation) {
        Map<String, String> out = new TreeMap<>();
        List<SetPiecePlan.Placement> plan = SetPiecePlan.plan(spec, seed, rotation);
        SetPiecePlan.write(plan, 100, -50, (x, z) -> 64 + Math.floorMod(x * 7 + z * 3, 5), (x, y, z, what) -> out.put(x + "," + y + "," + z, what));
        return out;
    }

    @Test
    void theSameSeedWritesTheSameBlocksAndAnotherSeedOthers() {
        SetPieceSpec s = resolve(BONE_PIT);
        Map<String, String> a = region(s, 42L, 1);
        Map<String, String> b = region(s, 42L, 1);
        assertEquals(a.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(a, b);
        assertNotEquals(a, region(s, 43L, 1));
        assertTrue(a.values().stream().anyMatch(v -> v.startsWith("template:minecraft:fossil/skull_2|")));
        assertTrue(a.values().stream().anyMatch(v -> v.equals("minecraft:chest|minecraft:chests/simple_dungeon")));
        assertTrue(a.values().stream().anyMatch(v -> v.equals("minecraft:spawner|minecraft:skeleton")));
        long scattered = a.values().stream().filter(v -> v.contains("bone_block") || v.contains("skeleton_skull")).count();
        assertTrue(scattered >= 8 && scattered <= 12, "scatter items " + scattered);
    }

    @Test
    void scatterKeepsItsSpacingAndRadius() {
        SetPieceSpec s = resolve(BONE_PIT);
        List<int[]> items = new ArrayList<>();
        for (SetPiecePlan.Placement p : SetPiecePlan.plan(s, 7L, 0)) {
            if (p.element() == 1) items.add(new int[] {p.dx(), p.dz()});
        }
        for (int i = 0; i < items.size(); i++) {
            assertTrue(Math.hypot(items.get(i)[0], items.get(i)[1]) <= 10.5);
            for (int j = i + 1; j < items.size(); j++) {
                assertTrue(Math.max(Math.abs(items.get(i)[0] - items.get(j)[0]), Math.abs(items.get(i)[1] - items.get(j)[1])) >= 2);
            }
        }
    }

    @Test
    void rotationTurnsOffsetsClockwise() {
        assertEquals(5, SetPiecePlan.rotate(5, 0, 1)[1]);
        assertEquals(-3, SetPiecePlan.rotate(2, 3, 1)[0]);
        assertEquals(-2, SetPiecePlan.rotate(2, 3, 2)[0]);
        assertEquals(3, SetPiecePlan.rotate(2, 3, 3)[0]);
    }
}
