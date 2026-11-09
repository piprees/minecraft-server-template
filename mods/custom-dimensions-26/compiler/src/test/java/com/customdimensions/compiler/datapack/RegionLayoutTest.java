package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code biomeLayout} {@code "regions"}: rank-space cells, bound boxes first, the runtime's {@code regions} object. */
class RegionLayoutTest {

    private static final String GENERATOR = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
    private static final List<String> NINE = List.of("minecraft:plains", "minecraft:forest", "minecraft:taiga", "minecraft:savanna",
            "minecraft:desert", "minecraft:jungle", "minecraft:swamp", "minecraft:meadow", "minecraft:grove");

    private static String list(List<String> ids) {
        StringBuilder b = new StringBuilder("\"biomes\": [");
        for (int i = 0; i < ids.size(); i++) b.append(i == 0 ? "" : ", ").append('"').append(ids.get(i)).append('"');
        return b.append(']').toString();
    }

    private static JsonObject source(String slug, String body) {
        DatapackCompiler.Output out = Fixtures.compile(slug, body);
        for (Map.Entry<String, String> e : out.files().entrySet()) {
            if (e.getKey().endsWith("dimension/" + slug + ".json")) {
                return JsonParser.parseString(e.getValue()).getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source");
            }
        }
        throw new AssertionError("no stem for " + slug + " in " + out.files().keySet());
    }

    /** Each biome's share of the rank square [-1, 1]², the axis bounds clipped to it. */
    private static Map<String, Double> shares(JsonArray entries, String a, String b) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (JsonElement e : entries) {
            JsonObject p = e.getAsJsonObject().getAsJsonObject("parameters");
            double area = extent(p.get(a)) * (b == null ? 2 : extent(p.get(b))) / 4;
            out.merge(e.getAsJsonObject().get("biome").getAsString(), area, Double::sum);
        }
        return out;
    }

    private static double extent(JsonElement range) {
        JsonArray r = range.getAsJsonArray();
        return Math.min(1, r.get(1).getAsDouble()) - Math.max(-1, r.get(0).getAsDouble());
    }

    private static double[][] box(JsonObject entry, String a, String b) {
        JsonObject p = entry.getAsJsonObject("parameters");
        JsonArray x = p.getAsJsonArray(a), y = p.getAsJsonArray(b);
        return new double[][]{{x.get(0).getAsDouble(), x.get(1).getAsDouble()}, {y.get(0).getAsDouble(), y.get(1).getAsDouble()}};
    }

    @Test
    void columnsGroupSharesNearAnEqualWidth() {
        assertArrayEquals(new int[]{3, 3, 3}, RegionLayout.columns(new double[]{1 / 9.0, 1 / 9.0, 1 / 9.0, 1 / 9.0, 1 / 9.0, 1 / 9.0,
                1 / 9.0, 1 / 9.0, 1 / 9.0}, 3));
        assertArrayEquals(new int[]{1, 2}, RegionLayout.columns(new double[]{0.5, 0.25, 0.25}, 2));
        assertArrayEquals(new int[]{1, 1}, RegionLayout.columns(new double[]{0.5, 0.5}, 5));
    }

    @Test
    void equalWeightsTakeEqualSharesOfRankSpace() {
        JsonObject source = source("r", GENERATOR + ", " + list(NINE) + ", \"biomeLayout\": {\"mode\": \"regions\"}");
        assertEquals("customdimensions:zoned", source.get("type").getAsString());
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        assertEquals(9, entries.size());
        shares(entries, "temperature", "humidity").forEach((id, share) -> assertEquals(1 / 9.0, share, 1e-3, id));
        for (JsonElement e : entries) {
            JsonObject p = e.getAsJsonObject().getAsJsonObject("parameters");
            for (String axis : List.of("continentalness", "erosion", "weirdness", "depth")) {
                assertEquals("[-2.0,2.0]", p.get(axis).toString(), axis);
            }
        }
        JsonObject regions = source.getAsJsonObject("regions");
        assertEquals("[\"temperature\",\"humidity\"]", regions.get("axes").toString());
        assertEquals("[-0.3333,0.3333]", regions.get("columns").toString());
        assertEquals(RegionLayout.DEFAULT_RADIUS, regions.get("radius").getAsInt());
    }

    @Test
    void weightsSetTheShares() {
        JsonObject source = source("r", GENERATOR + ", " + list(List.of("minecraft:plains", "minecraft:forest", "minecraft:taiga"))
                + ", \"biomeLayout\": {\"mode\": \"regions\", \"weights\": {\"minecraft:plains\": 2, \"minecraft:taiga\": 0.5}}");
        Map<String, Double> shares = shares(source.getAsJsonObject("base").getAsJsonArray("biomes"), "temperature", "humidity");
        assertEquals(2 / 3.5, shares.get("minecraft:plains"), 1e-3);
        assertEquals(1 / 3.5, shares.get("minecraft:forest"), 1e-3);
        assertEquals(0.5 / 3.5, shares.get("minecraft:taiga"), 1e-3);
    }

    @Test
    void consecutiveBiomesShareABorder() {
        JsonArray entries = source("r", GENERATOR + ", " + list(NINE) + ", \"biomeLayout\": {\"mode\": \"regions\"}")
                .getAsJsonObject("base").getAsJsonArray("biomes");
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        entries.forEach(e -> byId.put(e.getAsJsonObject().get("biome").getAsString(), e.getAsJsonObject()));
        for (int i = 0; i + 1 < NINE.size(); i++) {
            double[][] a = box(byId.get(NINE.get(i)), "temperature", "humidity");
            double[][] b = box(byId.get(NINE.get(i + 1)), "temperature", "humidity");
            boolean sideBySide = (a[0][1] == b[0][0] || b[0][1] == a[0][0]) && a[1][0] < b[1][1] && b[1][0] < a[1][1];
            boolean stacked = (a[1][1] == b[1][0] || b[1][1] == a[1][0]) && a[0][0] < b[0][1] && b[0][0] < a[0][1];
            assertTrue(sideBySide || stacked, NINE.get(i) + " and " + NINE.get(i + 1) + " share no border");
        }
    }

    @Test
    void boundBiomesKeepTheirBoxAndWin() {
        String body = GENERATOR + ", \"biomes\": [{\"id\": \"minecraft:ocean\", \"climate\": {\"continentalness\": [-2, -0.2]}}, "
                + "\"minecraft:plains\", \"minecraft:forest\", "
                + "{\"id\": \"minecraft:dripstone_caves\", \"climate\": {\"depth\": [0.2, 2]}}], "
                + "\"borders\": {\"player\": 4096, \"generation\": 2048}, \"biomeLayout\": {\"mode\": \"regions\"}";
        JsonObject source = source("r", body);
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        assertEquals("minecraft:ocean", entries.get(0).getAsJsonObject().get("biome").getAsString());
        assertEquals("[-2.0,-0.2]", entries.get(0).getAsJsonObject().getAsJsonObject("parameters").get("continentalness").toString());
        assertEquals("minecraft:dripstone_caves", entries.get(1).getAsJsonObject().get("biome").getAsString());
        assertNull(MultiNoise.firstOverlap(entries));
        for (JsonElement e : entries) {
            String id = e.getAsJsonObject().get("biome").getAsString();
            JsonObject p = e.getAsJsonObject().getAsJsonObject("parameters");
            if (id.equals("minecraft:plains") || id.equals("minecraft:forest")) {
                assertTrue(p.getAsJsonArray("continentalness").get(0).getAsDouble() >= -0.2, id + " reaches the ocean");
                assertTrue(p.getAsJsonArray("depth").get(1).getAsDouble() <= 0.2, id + " reaches the caves");
            }
        }
        assertEquals(4096, source.getAsJsonObject("regions").get("radius").getAsInt());
        assertEquals(2048, RegionLayout.radius(new com.customdimensions.core.definition.Borders(null, 2048)));
    }

    @Test
    void oneAxisCutsBandsAtTheShares() {
        JsonObject source = source("r", GENERATOR + ", " + list(List.of("minecraft:plains", "minecraft:forest", "minecraft:taiga", "minecraft:desert"))
                + ", \"biomeLayout\": {\"mode\": \"regions\", \"axes\": [\"weirdness\"]}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        shares(entries, "weirdness", null).forEach((id, share) -> assertEquals(0.25, share, 1e-3, id));
        for (JsonElement e : entries) {
            assertEquals("[-2.0,2.0]", e.getAsJsonObject().getAsJsonObject("parameters").get("temperature").toString());
        }
        assertFalse(source.getAsJsonObject("regions").has("columns"));
        assertEquals("[\"weirdness\"]", source.getAsJsonObject("regions").get("axes").toString());
    }

    @Test
    void zonesKeepTheirChannelsUnderTheRegions() {
        String body = GENERATOR + ", " + list(List.of("minecraft:plains", "minecraft:forest"))
                + ", \"zones\": [{\"shape\": \"circle\", \"centre\": [0, 0], \"radius\": 64, \"biomes\": [\"minecraft:desert\"]}]"
                + ", \"biomeLayout\": {\"mode\": \"regions\"}";
        JsonObject source = source("r", body);
        assertEquals("customdimensions:zoned", source.get("type").getAsString());
        assertEquals("minecraft:multi_noise", source.getAsJsonObject("base").get("type").getAsString());
        assertEquals(1, source.getAsJsonArray("zones").size());
        assertTrue(source.has("regions"));
    }

    @Test
    void withoutTheFieldTheSourceIsUnchanged() {
        JsonObject source = source("r", GENERATOR + ", " + list(NINE));
        assertEquals("minecraft:multi_noise", source.get("type").getAsString());
        assertFalse(source.has("regions"));
    }

    @Test
    void malformedLayoutsAreRefused() {
        List<String[]> cases = new ArrayList<>();
        cases.add(new String[]{"{\"mode\": \"bands\"}", "biomeLayout.mode"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"axes\": [\"depth\"]}", "biomeLayout.axes"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"axes\": [\"temperature\", \"temperature\"]}", "biomeLayout.axes"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"axes\": [\"temperature\", \"humidity\", \"erosion\"]}", "biomeLayout.axes"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"weights\": {\"minecraft:plains\": 0}}", "above 0"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"weights\": {\"minecraft:ocean\": 1}}", "names no listed biome"});
        cases.add(new String[]{"{\"mode\": \"regions\", \"size\": 3}", "biomeLayout.size is not a field"});
        for (String[] c : cases) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> Fixtures.compile("r", GENERATOR + ", " + list(List.of("minecraft:plains")) + ", \"biomeLayout\": " + c[0]), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + ": " + e.getMessage());
        }
        RuntimeException noList = assertThrows(RuntimeException.class,
                () -> Fixtures.compile("r", GENERATOR + ", \"biomeLayout\": {\"mode\": \"regions\"}"));
        assertTrue(noList.getMessage().contains("no biome list"), noList.getMessage());
        RuntimeException flat = assertThrows(RuntimeException.class, () -> Fixtures.compile("r",
                "\"generator\": {\"type\": \"checkerboard\"}, " + list(List.of("minecraft:plains")) + ", \"biomeLayout\": {\"mode\": \"regions\"}"));
        assertTrue(flat.getMessage().contains("checkerboard generator"), flat.getMessage());
    }

    @Test
    void matchesTheCommittedOutput() throws IOException {
        TerrainGoldenTest.matchesBody("regions", GENERATOR + ", \"biomes\": [{\"id\": \"minecraft:ocean\", \"climate\": "
                + "{\"continentalness\": [-2, -0.2]}}, " + list(NINE).substring("\"biomes\": [".length())
                .replace("]", "") + ", {\"id\": \"minecraft:dripstone_caves\", \"climate\": {\"depth\": [0.2, 2]}}], "
                + "\"borders\": {\"player\": 4096, \"generation\": 2048}, \"biomeLayout\": {\"mode\": \"regions\", "
                + "\"weights\": {\"minecraft:plains\": 3, \"minecraft:desert\": 2}}");
    }
}
