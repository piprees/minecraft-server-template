package com.customdimensions.core.structure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetPieceSettingTest {

    static SetPieceSpec resolve(String json) {
        return SetPieceSpec.resolve("the_test", "piece", JsonParser.parseString(json).getAsJsonObject(), Map.of(), null, SetPieceSpecTest.VANILLA);
    }

    static String message(String json) {
        return assertThrows(IllegalArgumentException.class, () -> resolve(json)).getMessage();
    }

    /** A terrain of a height function, water where the height is below 62, plains everywhere. */
    record Flat(IntBinaryOperator h) implements SiteFilter.Terrain {
        public int height(int x, int z) {
            return Math.max(62, h.applyAsInt(x, z));
        }

        public int floor(int x, int z) {
            return h.applyAsInt(x, z);
        }

        public boolean inBiomes(int x, int y, int z, List<String> biomes) {
            return biomes.contains("minecraft:plains");
        }

        public boolean overhang(int x, int z, int y) {
            return false;
        }
    }

    @Test
    void shapesSeatsAndLayersResolveAndRoundTrip() {
        SetPieceSpec s = resolve("""
                {"name": "The Pit", "seat": "wall", "rise": 5, "reach": 40, "size": 24, "age": "old",
                 "approach": [{"kind": "path", "to": "spawn"}, {"kind": "ladder", "length": 20}],
                 "elements": [
                   {"kind": "pit", "name": "pit", "radius": 6, "depth": 10, "fill": "minecraft:bone_block", "fillDepth": 2},
                   {"kind": "fall", "offset": [7, 0, 0], "pool": 2},
                   {"kind": "column", "on": "pit", "at": "inside", "height": 3, "radius": 0, "age": "recent"},
                   {"kind": "line", "style": "chain", "from": [0, 0, 0], "to": [4, "roof", 0]}
                 ]}""");
        assertEquals("wall", s.seat());
        assertEquals(5, s.setting().rise());
        assertEquals(40, s.setting().reach());
        assertEquals(2, s.setting().approaches().size());
        assertEquals("none", s.terrainAdaptation(), "a piece with shapes is not bearded");
        assertEquals(0, s.elements().get(2).on());
        JsonObject json = s.toJson();
        assertEquals(json, SetPieceSpec.fromJson(json).toJson());
        List<SetPiecePlan.Placement> plan = SetPiecePlan.plan(s, 7L, 1);
        assertEquals(4, plan.size());
        assertEquals(plan.get(0).dx(), plan.get(2).dx(), "seated on the pit, at its offset");
        assertEquals(Age.RECENT, plan.get(2).age());
        assertEquals(Age.OLD, plan.get(0).age());
    }

    @Test
    void badSettingsStopTheCompile() {
        assertTrue(message("{\"seat\": \"sky\", \"size\": 4, \"elements\": [{\"block\": \"minecraft:stone\"}]}").contains("seat `sky`"));
        assertTrue(message("{\"seat\": \"ground\", \"rise\": 4, \"size\": 4, \"elements\": [{\"block\": \"minecraft:stone\"}]}").contains("rise applies to the wall seat"));
        assertTrue(message("{\"size\": 4, \"elements\": [{\"block\": \"minecraft:stone\", \"on\": \"later\"}, {\"block\": \"minecraft:stone\", \"name\": \"later\"}]}")
                .contains("is not an earlier element"));
        assertTrue(message("{\"size\": 10, \"elements\": [{\"kind\": \"crater\", \"radius\": 12, \"depth\": 3}]}").contains("past its size 10"));
        assertTrue(message("{\"size\": 12, \"lived\": {\"density\": 4}, \"elements\": [{\"block\": \"minecraft:stone\"}]}").contains("size at most 8"));
        assertTrue(message("{\"size\": 4, \"lived\": {\"density\": 4, \"siting\": \"sky\"}, \"elements\": [{\"block\": \"minecraft:stone\"}]}").contains("siting `sky`"));
        assertTrue(message("{\"size\": 4, \"approach\": {\"kind\": \"zipline\"}, \"elements\": [{\"block\": \"minecraft:stone\"}]}").contains("kind must be one of"));
        assertTrue(message("{\"size\": 30, \"elements\": [{\"kind\": \"crater\", \"radius\": 6, \"depth\": 3, \"lining\": \"minecraft:unobtainium\"}]}")
                .contains("not a vanilla block"));
        assertTrue(message("{\"size\": 30, \"elements\": [{\"kind\": \"scatter\", \"count\": 2, \"radius\": 3, \"items\": [{\"kind\": \"pit\", \"radius\": 2, \"depth\": 2}]}]}")
                .contains("not shapes"));
    }

    @Test
    void agesBandByDistanceWithJitteredEdges() {
        SetPieceSetting.AgeBands b = SetPieceSetting.AgeBands.read("x", JsonParser.parseString(
                "{\"recent\": 0, \"old\": 1024, \"ancient\": 3072, \"deep\": 5632}").getAsJsonObject(), 0, 0);
        assertEquals(Age.RECENT, b.at(100, 0, 1));
        assertEquals(Age.OLD, b.at(2000, 0, 1));
        assertEquals(Age.ANCIENT, b.at(0, 4000, 1));
        assertEquals(Age.DEEP, b.at(7000, 7000, 1));
        int old = 0;
        for (int z = -4000; z <= 4000; z += 64) if (b.at(1024, z, 1) == Age.OLD || Math.hypot(1024, z) > 1200) old++;
        assertTrue(old < 126, "the band's edge is jittered, not a ring");
        assertNull(SetPieceSetting.AgeBands.read("x", JsonParser.parseString("\"old\""), 0, 0));
        assertEquals(b, SetPieceSetting.AgeBands.fromJson(b.toJson()));
        assertThrows(IllegalArgumentException.class, () -> SetPieceSetting.AgeBands.read("x", JsonParser.parseString("{\"older\": 4}"), 0, 0));
    }

    @Test
    void theLivedInLayerKeepsItsDensityByDistanceAndSiting() {
        SetPieceSetting.Lived l = SetPieceSetting.Lived.read("x", JsonParser.parseString("{\"density\": 12}"));
        Flat land = new Flat((x, z) -> 70);
        List<int[]> sites = l.sites(42L, 0, 0, 4096, land, List.of(), (a, b) -> true);
        double expected = 12 * Math.PI * 4096 * 4096 / 1e6;
        assertTrue(Math.abs(sites.size() - expected) < expected * 0.15, sites.size() + " sites against " + expected);
        SetPieceSetting.Lived inner = SetPieceSetting.Lived.read("x", JsonParser.parseString("{\"density\": 12, \"radial\": [1, 1, 1, 1, 1, 0, 0, 0, 0, 0]}"));
        List<int[]> in = inner.sites(42L, 0, 0, 4096, land, List.of(), (a, b) -> true);
        assertTrue(in.stream().allMatch(c -> Math.hypot(c[0] * 16 + 8, c[1] * 16 + 8) < 4096 * 5 / 9.0 + 64), "nothing where the radial is 0");
        SetPieceSetting.Lived water = SetPieceSetting.Lived.read("x", JsonParser.parseString("{\"density\": 12, \"siting\": \"water\"}"));
        Flat lake = new Flat((x, z) -> Math.hypot(x - 1000, z) < 300 ? 50 : 70);
        List<int[]> wet = water.sites(42L, 0, 0, 4096, lake, List.of(), (a, b) -> true);
        assertFalse(wet.isEmpty());
        assertTrue(wet.stream().allMatch(c -> Math.hypot(c[0] * 16 + 8 - 1000, c[1] * 16 + 8) < 300 + 32), "by the lake only");
        SetPieceSetting.Lived path = SetPieceSetting.Lived.read("x", JsonParser.parseString("{\"density\": 40, \"siting\": \"path\", \"pathWidth\": 24}"));
        List<int[]> segs = SetPieceSetting.Lived.segments(0, 0, List.of(new int[] {2000, 0}));
        List<int[]> onPath = path.sites(42L, 0, 0, 4096, land, segs, (a, b) -> true);
        assertFalse(onPath.isEmpty());
        assertTrue(onPath.stream().allMatch(c -> SetPieceSetting.Lived.segmentDistance(c[0] * 16 + 8, c[1] * 16 + 8, segs.getFirst()) <= 24
                || SetPieceSetting.Lived.segmentDistance(c[0] * 16 + 8, c[1] * 16 + 8, segs.getLast()) <= 24));
        assertEquals(sites.size(), l.sites(42L, 0, 0, 4096, land, List.of(), (a, b) -> true).size(), "the same seed draws the same sites");
    }

    @Test
    void approachesStepBridgeAndDescend() {
        SetPieceSetting.Approach path = SetPieceSetting.Approach.read("x", JsonParser.parseString("{\"kind\": \"path\", \"length\": 40}"));
        int[] box = {-4, -4, 4, 4};
        List<int[]> flat = path.ops((x, z) -> 64, box, 100, 0, 1L);
        assertTrue(flat.stream().allMatch(o -> o[3] == SetPieceShapes.PATH));
        assertTrue(flat.size() >= 38 && flat.getFirst()[0] == 5, "from the box's edge outward, " + flat.size());
        List<int[]> climb = path.ops((x, z) -> 64 + Math.max(0, x - 10) / 3 * 3, box, 100, 0, 1L);
        assertTrue(climb.stream().anyMatch(o -> o[3] == SetPieceShapes.STAIR), "steps where it climbs 3");
        List<int[]> gap = path.ops((x, z) -> x >= 12 && x <= 20 ? 40 : 64, box, 100, 0, 1L);
        assertTrue(gap.stream().filter(o -> o[3] == SetPieceShapes.DECK).allMatch(o -> o[1] == 63), "a deck at the lip's level");
        assertEquals(9, gap.stream().filter(o -> o[3] == SetPieceShapes.DECK && o[2] == 0).count(), "spanning the nine-block gap");
        List<int[]> cliff = path.ops((x, z) -> x >= 12 ? 30 : 64, box, 100, 0, 1L);
        assertFalse(cliff.stream().anyMatch(o -> o[0] > 12), "a drop that never comes back ends the path");
        SetPieceSetting.Approach ladder = SetPieceSetting.Approach.read("x", JsonParser.parseString("{\"kind\": \"ladder\"}"));
        List<int[]> down = ladder.ops((x, z) -> x >= 10 ? 50 : 64, box, 100, 0, 1L);
        assertEquals(14, down.size(), "a ladder from the foot to the lip");
        assertTrue(down.stream().allMatch(o -> o[0] == 10 && o[3] == SetPieceShapes.LADDER && o[4] == 1));
        SetPieceSetting.Approach debris = SetPieceSetting.Approach.read("x", JsonParser.parseString("{\"kind\": \"debris\", \"count\": 10, \"direction\": [0, 1]}"));
        List<int[]> line = debris.ops((x, z) -> 64, box, 100, 0, 1L);
        assertEquals(10, line.size());
        assertTrue(line.stream().allMatch(o -> o[2] > 4 && Math.abs(o[0]) <= 5));
        assertNotNull(SetPieceSetting.Approach.readAll("x", JsonParser.parseString("[{\"kind\": \"steps\"}, {\"kind\": \"bridge\"}]")));
    }
}
