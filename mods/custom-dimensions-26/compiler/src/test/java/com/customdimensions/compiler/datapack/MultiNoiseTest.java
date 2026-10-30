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
