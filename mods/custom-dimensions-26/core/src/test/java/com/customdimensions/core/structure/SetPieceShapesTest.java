package com.customdimensions.core.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetPieceShapesTest {

    /** Stone below a height function, air above it, and what a shape writes. */
    static final class Fake implements SetPieceShapes.World {
        final IntBinaryOperator height;
        final Map<String, String> writes = new TreeMap<>();
        final Map<String, String> blocks = new HashMap<>();

        Fake(IntBinaryOperator height) {
            this.height = height;
        }

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public int ground(int x, int z) {
            int h = height.applyAsInt(x, z);
            if (h == SetPieceShapes.NO_GROUND) return h;
            for (int y = h + 40; y > h - 80; y--) if (!SetPieceShapes.air(block(x, y - 1, z))) return y;
            return SetPieceShapes.NO_GROUND;
        }

        @Override
        public String block(int x, int y, int z) {
            String b = blocks.get(key(x, y, z));
            if (b != null) return b;
            int h = height.applyAsInt(x, z);
            return h != SetPieceShapes.NO_GROUND && y < h ? "minecraft:stone" : "minecraft:air";
        }

        @Override
        public void set(int x, int y, int z, String state) {
            writes.put(key(x, y, z), state);
            blocks.put(key(x, y, z), state.contains("[") ? state.substring(0, state.indexOf('[')) : state);
        }

        @Override
        public String stone() {
            return "minecraft:deepslate";
        }

        long count(String state) {
            return writes.values().stream().filter(state::equals).count();
        }
    }

    static SetPieceShapes.Shape shape(String kind, String params, int y, List<int[]> points) {
        JsonObject p = JsonParser.parseString(params).getAsJsonObject();
        return new SetPieceShapes.Shape(kind, 0, y, 0, 0, 1234L, p, Map.of(), null, points);
    }

    static Fake draw(SetPieceShapes.Shape s, IntBinaryOperator height) {
        Fake f = new Fake(height);
        SetPieceShapes.draw(s, -200, -200, 200, 200, f);
        return f;
    }

    /** Air carved below {@code y} on flat ground at {@code y}. */
    static long carvedBelow(Fake f, int y) {
        return f.writes.entrySet().stream().filter(e -> e.getValue().equals("minecraft:air") && Integer.parseInt(e.getKey().split(",")[1]) < y).count();
    }

    @Test
    void aCraterCarvesItsParaboloid() {
        Fake f = draw(shape("crater", "{\"radius\": 14, \"depth\": 5, \"rim\": 2}", 64, List.of()), (x, z) -> 64);
        double formula = Math.PI * 14 * 14 * 5 / 2;
        long carved = carvedBelow(f, 64);
        assertTrue(Math.abs(carved - formula) <= formula * 0.1, carved + " carved against " + formula);
        long rim = f.writes.entrySet().stream().filter(e -> Integer.parseInt(e.getKey().split(",")[1]) >= 64).count();
        assertTrue(rim > 50, "a raised rim, " + rim + " blocks");
        int[] box = SetPieceShapes.box(shape("crater", "{\"radius\": 14, \"depth\": 5, \"rim\": 2}", 64, List.of()));
        for (String k : f.writes.keySet()) {
            String[] c = k.split(",");
            int x = Integer.parseInt(c[0]);
            int z = Integer.parseInt(c[2]);
            assertTrue(x >= box[0] && x <= box[3] && z >= box[2] && z <= box[5], k + " outside its box");
        }
    }

    @Test
    void aCraterLinesItsBowlAndHoldsItsCore() {
        Fake f = draw(shape("crater", "{\"radius\": 8, \"depth\": 4, \"rim\": 0, \"lining\": \"minecraft:basalt\", \"core\": \"minecraft:magma_block\", \"coreRadius\": 1}",
                64, List.of()), (x, z) -> 64);
        assertTrue(f.count("minecraft:basalt") > 150, "lining " + f.count("minecraft:basalt"));
        assertTrue(f.count("minecraft:magma_block") >= 5, "core " + f.count("minecraft:magma_block"));
        assertEquals("minecraft:magma_block", f.writes.get(Fake.key(0, 60, 0)));
    }

    @Test
    void aPitIsItsCylinderLessItsFill() {
        Fake f = draw(shape("pit", "{\"radius\": 6, \"depth\": 12, \"fill\": \"minecraft:bone_block\", \"fillDepth\": 2, \"walls\": \"minecraft:blackstone\", \"ledges\": 2}",
                64, List.of()), (x, z) -> 64);
        double formula = Math.PI * 36 * 10;
        long carved = f.writes.entrySet().stream().filter(e -> e.getValue().equals("minecraft:air")).count();
        long ledges = f.writes.entrySet().stream().filter(e -> {
            String[] c = e.getKey().split(",");
            return e.getValue().equals("minecraft:blackstone") && Math.hypot(Integer.parseInt(c[0]), Integer.parseInt(c[2])) <= 6;
        }).count();
        assertTrue(Math.abs(carved + ledges - formula) <= formula * 0.1, (carved + ledges) + " open against " + formula);
        assertTrue(f.count("minecraft:bone_block") > 200, "fill " + f.count("minecraft:bone_block"));
        assertTrue(ledges > 0, "ledges");
        assertTrue(f.count("minecraft:blackstone") > ledges, "walls dressed");
    }

    @Test
    void aRiftIsLongAndNarrow() {
        Fake f = draw(shape("pit", "{\"radius\": 12, \"shape\": \"rift\", \"width\": 2, \"depth\": 6}", 64, List.of()), (x, z) -> 64);
        double formula = Math.PI * 12 * 2 * 6;
        long carved = carvedBelow(f, 64);
        assertTrue(Math.abs(carved - formula) <= formula * 0.15, carved + " against " + formula);
        assertEquals("minecraft:air", f.writes.get(Fake.key(11, 60, 0)));
        assertFalse(f.writes.containsKey(Fake.key(0, 60, 4)));
    }

    @Test
    void aWallFollowsSteppedGroundWithoutGaps() {
        IntBinaryOperator stepped = (x, z) -> 64 + 3 * Math.floorDiv(x, 5);
        Fake f = draw(shape("wall", "{\"length\": 30, \"height\": 5, \"thickness\": 1, \"palette\": \"minecraft:cobblestone\"}", 64, List.of()), stepped);
        for (int x = -15; x <= 15; x++) {
            int g = stepped.applyAsInt(x, 0);
            for (int y = g; y < g + 5; y++) assertEquals("minecraft:cobblestone", f.writes.get(Fake.key(x, y, 0)), "wall at " + x + "," + y);
            assertFalse(f.writes.containsKey(Fake.key(x, g + 5, 0)));
        }
        assertEquals(31 * 5, f.writes.size());
    }

    @Test
    void wallTowersRiseAndBreachesBreak() {
        Fake f = draw(shape("wall", "{\"length\": 40, \"height\": 4, \"thickness\": 2, \"towers\": 10, \"breaches\": 2}", 64, List.of()), (x, z) -> 64);
        assertTrue(f.writes.containsKey(Fake.key(10, 70, 0)), "a tower 3 above the wall at 10");
        assertFalse(f.writes.containsKey(Fake.key(5, 70, 0)));
        long full = 0;
        for (int x = -20; x <= 20; x++) if (f.writes.containsKey(Fake.key(x, 67, 0))) full++;
        assertTrue(full < 41, "breaches lower the wall");
    }

    @Test
    void aShapeAcrossAChunkBorderIsContinuous() {
        IntBinaryOperator hills = (x, z) -> 64 + (int) Math.round(4 * Math.sin(x / 7.0) + 3 * Math.cos(z / 5.0));
        List<SetPieceShapes.Shape> shapes = List.of(
                shape("crater", "{\"radius\": 10, \"depth\": 4, \"rim\": 2, \"scorch\": 14, \"lining\": \"minecraft:basalt\"}", 64, List.of()),
                shape("wall", "{\"length\": 30, \"height\": 4, \"towers\": 8, \"breaches\": 1}", 64, List.of()),
                shape("field", "{\"radius\": 12, \"edge\": 0.4, \"cover\": \"minecraft:soul_soil\", \"over\": \"minecraft:soul_fire\"}", 64, List.of()),
                shape("line", "{\"style\": \"path\", \"width\": 2}", 64, List.of(new int[] {-20, 64, -5}, new int[] {20, 64, 7})),
                shape("column", "{\"height\": 9, \"radius\": 2, \"style\": \"chimney\"}", 64, List.of()));
        for (SetPieceShapes.Shape s : shapes) {
            Fake whole = new Fake(hills);
            SetPieceShapes.draw(s, -64, -64, 63, 63, whole);
            Fake split = new Fake(hills);
            SetPieceShapes.draw(s, -64, -64, -1, 63, split);
            SetPieceShapes.draw(s, 0, -64, 63, 63, split);
            assertEquals(whole.writes, split.writes, s.kind() + " drawn in two chunks matches one draw");
            assertTrue(whole.writes.keySet().stream().anyMatch(k -> k.startsWith("-1,")) && whole.writes.keySet().stream().anyMatch(k -> k.startsWith("0,")),
                    s.kind() + " crosses the border");
        }
    }

    @Test
    void aShapeAtTheWorldBorderClipsWithoutError() {
        Fake f = new Fake((x, z) -> 64);
        SetPieceShapes.draw(shape("crater", "{\"radius\": 14, \"depth\": 5}", 64, List.of()), 5, -100, 100, 100, f);
        assertFalse(f.writes.isEmpty());
        assertTrue(f.writes.keySet().stream().allMatch(k -> Integer.parseInt(k.split(",")[0]) >= 5));
        Fake v = new Fake((x, z) -> x < 0 ? SetPieceShapes.NO_GROUND : 64);
        SetPieceShapes.draw(shape("field", "{\"radius\": 6, \"cover\": \"minecraft:snow_block\"}", 64, List.of()), -100, -100, 100, 100, v);
        assertTrue(v.writes.keySet().stream().allMatch(k -> Integer.parseInt(k.split(",")[0]) >= 0), "nothing over the void");
    }

    @Test
    void aFallSetsItsSourceAndPool() {
        Fake f = draw(shape("fall", "{\"liquid\": \"lava\", \"width\": 3, \"pool\": 2}", 70, List.<int[]>of(new int[] {1, 0})), (x, z) -> x <= 0 ? 70 : 58);
        assertEquals("minecraft:lava", f.writes.get(Fake.key(0, 68, 0)));
        assertEquals("minecraft:lava", f.writes.get(Fake.key(0, 68, 1)));
        assertEquals("minecraft:lava", f.writes.get(Fake.key(0, 68, -1)));
        assertEquals("minecraft:lava", f.writes.get(Fake.key(3, 57, 0)), "pool at the foot");
        assertEquals(3 + 13, f.count("minecraft:lava"));
    }

    @Test
    void crownTopsAreLocalMaxima() {
        IntBinaryOperator spires = (x, z) -> {
            int best = 60;
            for (int[] p : new int[][] {{0, 0}, {10, 4}, {-8, -10}}) best = Math.max(best, 80 - 2 * (Math.abs(x - p[0]) + Math.abs(z - p[1])));
            return best;
        };
        List<int[]> tops = SetPieceShapes.tops(spires::applyAsInt, 0, 0, 16, 6, 4, 2);
        assertEquals(3, tops.size(), "three spires");
        for (int[] t : tops) {
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                assertTrue(spires.applyAsInt(t[0] + dx, t[1] + dz) <= spires.applyAsInt(t[0], t[1]));
            }
        }
        Fake f = draw(shape("crown", "{\"radius\": 16, \"growth\": \"minecraft:amethyst_block\"}", 80, tops), spires);
        assertTrue(f.writes.size() >= 3);
        for (String k : f.writes.keySet()) {
            String[] c = k.split(",");
            assertTrue(tops.stream().anyMatch(t -> t[0] == Integer.parseInt(c[0]) && t[1] == Integer.parseInt(c[2])), k + " is on a top");
        }
        assertTrue(SetPieceShapes.tops((x, z) -> 64, 0, 0, 16, 6, 4, 2).isEmpty(), "flat ground has no tops");
    }

    @Test
    void aLineRunsBetweenItsEndsAndSags() {
        Fake f = draw(shape("line", "{\"style\": \"chain\", \"sag\": 4}", 80, List.of(new int[] {-10, 80, 0}, new int[] {10, 80, 0})), (x, z) -> 40);
        assertEquals("minecraft:iron_chain[axis=x]", f.writes.get(Fake.key(-10, 80, 0)));
        assertTrue(f.writes.containsKey(Fake.key(0, 76, 0)), "sags 4 at its middle");
        Fake b = draw(shape("line", "{\"style\": \"bridge\"}", 70, List.of(new int[] {0, 70, -8}, new int[] {0, 70, 8})), (x, z) -> 50);
        assertEquals(17 * 3, b.count("minecraft:spruce_planks"));
        assertEquals(17 * 2, b.count("minecraft:spruce_fence"));
        Fake p = draw(shape("line", "{\"style\": \"pipe\"}", 70, List.of(new int[] {0, 70, 0}, new int[] {12, 70, 0})), (x, z) -> 64);
        assertEquals(13, p.count("minecraft:waxed_cut_copper"));
        assertEquals(3 * 6, p.count("minecraft:spruce_fence"), "trestles every six blocks down to the ground");
    }

    @Test
    void aColumnStandsOnFootingsAndAChimneyIsHollow() {
        Fake f = draw(shape("column", "{\"height\": 10, \"radius\": 0, \"top\": \"minecraft:lantern\"}", 70, List.of()), (x, z) -> 64);
        assertEquals(10, f.count("minecraft:stone_bricks"));
        assertEquals(6, f.count("minecraft:deepslate"), "footing in the dimension's own stone down to the ground");
        assertEquals("minecraft:lantern", f.writes.get(Fake.key(0, 80, 0)));
        Fake c = draw(shape("column", "{\"height\": 6, \"radius\": 2, \"style\": \"chimney\"}", 64, List.of()), (x, z) -> 64);
        assertEquals("minecraft:campfire", c.writes.get(Fake.key(0, 64, 0)));
        assertEquals("minecraft:air", c.writes.get(Fake.key(0, 66, 0)));
        assertEquals("minecraft:bricks", c.writes.get(Fake.key(2, 66, 0)));
        Fake s = draw(shape("column", "{\"height\": -5, \"radius\": 0, \"style\": \"spire\"}", 90, List.of()), (x, z) -> 40);
        assertTrue(s.writes.containsKey(Fake.key(0, 89, 0)) && s.writes.containsKey(Fake.key(0, 85, 0)), "hangs down from its anchor");
    }

    @Test
    void aFieldCoversItsDisc() {
        Fake f = draw(shape("field", "{\"radius\": 10, \"edge\": 0, \"cover\": \"minecraft:soul_soil\"}", 64, List.of()), (x, z) -> 64);
        double formula = Math.PI * 100;
        assertTrue(Math.abs(f.count("minecraft:soul_soil") - formula) <= formula * 0.1, f.count("minecraft:soul_soil") + " against " + formula);
        assertTrue(f.writes.keySet().stream().allMatch(k -> k.split(",")[1].equals("63")));
    }

    @Test
    void aPlatformExistsWhateverTheGround() {
        String spec = "{\"width\": 5, \"depth\": 4, \"stairs\": true}";
        Fake f = draw(shape("platform", spec, 90, List.of()), (x, z) -> 80);
        assertEquals(20, f.count("minecraft:stone_bricks") - f.writes.entrySet().stream()
                .filter(e -> e.getValue().equals("minecraft:stone_bricks") && !e.getKey().split(",")[1].equals("89")).count());
        assertTrue(f.count("minecraft:stone_brick_wall") >= 13, "a rail round its edge");
        assertEquals(10, f.writes.values().stream().filter(v -> v.startsWith("minecraft:stone_brick_stairs[facing=north]")).count(), "stairs down 10 to the ground");
        Fake v = draw(shape("platform", spec, 90, List.of()), (x, z) -> SetPieceShapes.NO_GROUND);
        assertEquals(20, v.count("minecraft:stone_bricks"), "the deck stands over the void");
        assertEquals(0, v.writes.values().stream().filter(s -> s.contains("stairs")).count());
    }

    @Test
    void theSameShapeAndSeedDrawTheSameBlocks() {
        SetPieceShapes.Shape s = shape("crater", "{\"radius\": 9, \"depth\": 3, \"rim\": 3, \"scorch\": 12, \"lining\": \"minecraft:basalt\"}", 64, List.of());
        assertEquals(draw(s, (x, z) -> 64).writes, draw(s, (x, z) -> 64).writes);
        SetPieceShapes.Shape other = new SetPieceShapes.Shape("crater", 0, 64, 0, 0, 99L, s.params(), Map.of(), null, List.of());
        assertFalse(draw(s, (x, z) -> 64).writes.equals(draw(other, (x, z) -> 64).writes));
        assertEquals(s.toJson(), SetPieceShapes.Shape.fromJson(s.toJson()).toJson());
    }

    @Test
    void badShapeParametersStopTheCompile() {
        for (String[] c : new String[][] {
                {"crater", "{\"depth\": 3}", "radius is required"},
                {"pit", "{\"radius\": 3, \"depth\": 2, \"fillDepth\": 2, \"fill\": \"minecraft:sand\"}", "fillDepth must be less than depth"},
                {"fall", "{\"liquid\": \"milk\"}", "liquid must be one of"},
                {"line", "{\"style\": \"rope\", \"from\": [0, 0, 0], \"to\": [1, 0, 0]}", "style must be one of"},
                {"line", "{\"style\": \"chain\", \"from\": [0, \"sky\", 0], \"to\": [1, 0, 0]}", "\"ground\" or \"roof\""},
                {"column", "{\"height\": 0}", "height is required"},
                {"crown", "{\"radius\": 4}", "growth is required"},
                {"wall", "{\"length\": 5, \"height\": 2, \"towers\": 1}", "towers is the spacing"}}) {
            String m = assertThrows(IllegalArgumentException.class,
                    () -> SetPieceShapes.validate("x", c[0], JsonParser.parseString(c[1]).getAsJsonObject(), b -> {})).getMessage();
            assertTrue(m.contains(c[2]), m);
        }
    }
}
