package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiNoiseTest {

    @Test
    void rangesThatOnlyShareAnEndpointDoNotOverlap() {
        assertFalse(MultiNoise.overlapAxis(new double[] {-1, 0}, new double[] {0, 1}));
        assertTrue(MultiNoise.overlapAxis(new double[] {-1, 0.1}, new double[] {0, 1}));
    }

    @Test
    void pointsOverlapWhenEqualOrStrictlyInsideARange() {
        assertTrue(MultiNoise.overlapAxis(new double[] {0, 0}, new double[] {0, 0}));
        assertFalse(MultiNoise.overlapAxis(new double[] {0, 0}, new double[] {1, 1}));
        assertTrue(MultiNoise.overlapAxis(new double[] {0.5, 0.5}, new double[] {0, 1}));
        assertFalse(MultiNoise.overlapAxis(new double[] {0, 0}, new double[] {0, 1}));
    }

    @Test
    void entriesOverlapOnlyWhenEveryAxisDoes() {
        JsonObject a = entry("[-1, 0]", "0.0");
        JsonObject b = entry("[-0.5, 0.5]", "0.0");
        JsonObject c = entry("[-0.5, 0.5]", "1.0");
        assertTrue(MultiNoise.overlap(a, b));
        assertFalse(MultiNoise.overlap(a, c));
        JsonArray list = new JsonArray();
        list.add(a);
        list.add(c);
        list.add(b);
        assertArrayEquals(new int[] {0, 2}, MultiNoise.firstOverlap(list));
    }

    @Test
    void fullRangeCoversEveryAxis() {
        JsonObject p = MultiNoise.fullRange("minecraft:plains").getAsJsonObject("parameters");
        for (String axis : MultiNoise.AXES) {
            assertEquals(-2.0, p.getAsJsonArray(axis).get(0).getAsDouble());
            assertEquals(2.0, p.getAsJsonArray(axis).get(1).getAsDouble());
        }
        assertEquals(0.0, p.get("offset").getAsDouble());
    }

    /** Measured: 26.3's Nether list is disjoint; its overworld list overlaps only among the cave entries. */
    @Test
    void vanillaOverlapsAreTheCaveEntries() {
        assertNull(MultiNoise.firstOverlap(Fixtures.vanilla().biomeParameters("nether")));
        JsonArray overworld = Fixtures.vanilla().biomeParameters("overworld");
        int[] first = MultiNoise.firstOverlap(overworld);
        assertEquals("minecraft:dripstone_caves", overworld.get(first[0]).getAsJsonObject().get("biome").getAsString());
        assertEquals("minecraft:lush_caves", overworld.get(first[1]).getAsJsonObject().get("biome").getAsString());
    }

    @Test
    void carvingMakesTheVanillaOverworldListDisjointAndKeepsEveryBiome() {
        JsonArray overworld = Fixtures.vanilla().biomeParameters("overworld");
        JsonArray carved = MultiNoise.carve(overworld);
        assertNull(MultiNoise.firstOverlap(carved));
        assertEquals(biomes(overworld), biomes(carved));
        // Entries before the first overlap are untouched.
        for (int i = 0; i < 7590; i++) {
            assertEquals(overworld.get(i), carved.get(i));
        }
    }

    @Test
    void carvingKeepsTheEarlierEntrysSpace() {
        JsonObject a = entry("[-1, 0]", "0.0");
        JsonObject b = entry("[-0.5, 0.5]", "0.0");
        JsonArray list = new JsonArray();
        list.add(a);
        list.add(b);
        JsonArray carved = MultiNoise.carve(list);
        assertEquals(2, carved.size());
        assertEquals(a, carved.get(0));
        JsonArray temperature = carved.get(1).getAsJsonObject().getAsJsonObject("parameters").getAsJsonArray("temperature");
        assertEquals(0.0, temperature.get(0).getAsDouble());
        assertEquals(0.5, temperature.get(1).getAsDouble());
    }

    @Test
    void disjointEntriesComeBackUnchanged() {
        JsonArray list = new JsonArray();
        list.add(entry("[-1, 0]", "0.0"));
        list.add(entry("[0, 1]", "0.0"));
        assertEquals(list, MultiNoise.carve(list));
    }

    /** Grid boxes with points, shared endpoints and a missing axis carve exactly as cutting the first overlap of a rescan does. */
    @Test
    void carvingMatchesRepeatedlyCuttingTheFirstOverlap() {
        java.util.Random random = new java.util.Random(26_3);
        double[] grid = {-2.0, -1.0, -0.5, 0.0, 0.25, 0.5, 1.0, 2.0};
        for (int round = 0; round < 400; round++) {
            JsonArray list = new JsonArray();
            int n = 2 + random.nextInt(24);
            for (int i = 0; i < n; i++) {
                JsonObject p = new JsonObject();
                for (String axis : MultiNoise.AXES) {
                    if (axis.equals("depth") && random.nextInt(8) == 0) {
                        continue;
                    }
                    double a = grid[random.nextInt(grid.length)];
                    double b = grid[random.nextInt(grid.length)];
                    if (a == b || random.nextInt(4) == 0) {
                        p.addProperty(axis, a);
                    } else {
                        JsonArray r = new JsonArray();
                        r.add(Math.min(a, b));
                        r.add(Math.max(a, b));
                        p.add(axis, r);
                    }
                }
                p.addProperty("offset", 0.0);
                JsonObject e = new JsonObject();
                e.addProperty("biome", "minecraft:b" + i);
                e.add("parameters", p);
                list.add(e);
            }
            JsonArray expected = rescanCarve(list);
            JsonArray carved = MultiNoise.carve(list);
            assertEquals(expected.toString(), carved.toString(), "round " + round);
            assertNull(MultiNoise.firstOverlap(carved), "round " + round);
        }
    }

    private static JsonArray rescanCarve(JsonArray entries) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        entries.forEach(e -> out.add(e.getAsJsonObject()));
        while (true) {
            JsonArray current = new JsonArray();
            out.forEach(current::add);
            int[] pair = MultiNoise.firstOverlap(current);
            if (pair == null) {
                return current;
            }
            JsonObject later = out.remove(pair[1]);
            JsonArray two = new JsonArray();
            two.add(out.get(pair[0]));
            two.add(later);
            JsonArray cut = MultiNoise.carve(two);
            for (int i = cut.size() - 1; i >= 1; i--) {
                out.add(pair[1], cut.get(i).getAsJsonObject());
            }
        }
    }

    private static java.util.Set<String> biomes(JsonArray entries) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        entries.forEach(e -> out.add(e.getAsJsonObject().get("biome").getAsString()));
        return out;
    }

    private static JsonObject entry(String temperature, String depth) {
        return JsonParser.parseString("{\"biome\": \"minecraft:plains\", \"parameters\": {\"temperature\": " + temperature
                + ", \"humidity\": 0.0, \"continentalness\": 0.0, \"erosion\": 0.0, \"weirdness\": 0.0, \"depth\": "
                + depth + ", \"offset\": 0.0}}").getAsJsonObject();
    }
}
