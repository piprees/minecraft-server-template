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
    /** A terrain with no sea, so roles take no part. */
    private static final String SKY = "\"terrain\": {\"base\": \"floating_islands\"}";
    private static final String ISLANDS = "\"terrain\": {\"base\": \"islands\"}";
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
        JsonObject source = source("r", GENERATOR + ", " + SKY + ", " + list(NINE) + ", \"biomeLayout\": {\"mode\": \"regions\"}");
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

    // ---- roles ---------------------------------------------------------------------------------

    private static List<JsonObject> of(JsonArray entries, String biome) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : entries) {
            if (e.getAsJsonObject().get("biome").getAsString().equals(biome)) out.add(e.getAsJsonObject().getAsJsonObject("parameters"));
        }
        return out;
    }

    @Test
    void rolesComeFromVanillaTagsPlacementsAndTheLibrary() {
        BiomeRoles roles = BiomeRoles.of(Fixtures.vanilla(), BiomeLibrary.shipped(), "adventure");
        Map<String, String> none = Map.of();
        assertEquals(BiomeRoles.OCEAN, roles.role("minecraft:warm_ocean", none));
        assertEquals(BiomeRoles.OCEAN, roles.role("minecraft:deep_cold_ocean", none));
        assertEquals(BiomeRoles.RIVER, roles.role("minecraft:frozen_river", none));
        assertEquals(BiomeRoles.SHORE, roles.role("minecraft:beach", none));
        assertEquals(BiomeRoles.SHORE, roles.role("minecraft:stony_shore", none));
        assertEquals(BiomeRoles.PEAK, roles.role("minecraft:jagged_peaks", none));
        assertEquals(BiomeRoles.CAVE, roles.role("minecraft:lush_caves", none));
        assertEquals(BiomeRoles.CAVE, roles.role("minecraft:deep_dark", none));
        assertEquals(BiomeRoles.LAND, roles.role("minecraft:plains", none));
        assertEquals(BiomeRoles.LAND, roles.role("minecraft:mushroom_fields", none));
        assertEquals(BiomeRoles.SHORE, roles.role("adventure:tropical_shores", none));
        assertEquals(BiomeRoles.RIVER, roles.role("adventure:muddy_river", none));
        assertEquals(BiomeRoles.WETLAND, roles.role("adventure:orchid_marsh", none));
        assertEquals(BiomeRoles.CAVE, roles.role("adventure:crystal_hollows", none));
        assertEquals(BiomeRoles.LAND, roles.role("adventure:lavender_fields", none));
        assertEquals(BiomeRoles.OCEAN, roles.role("minecraft:plains", Map.of("minecraft:plains", BiomeRoles.OCEAN)));
        assertEquals("minecraft:frozen_ocean", BiomeRoles.sea(0.0));
        assertEquals("minecraft:ocean", BiomeRoles.sea(0.8));
        assertEquals("minecraft:warm_ocean", BiomeRoles.sea(2.0));
    }

    @Test
    void landRegionsStayOnLandAndTheSeaTakesAnOcean() {
        JsonObject source = source("r", GENERATOR + ", " + list(NINE) + ", \"biomeLayout\": {\"mode\": \"regions\"}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        for (String id : NINE) {
            for (JsonObject p : of(entries, id)) assertEquals(-0.19, p.getAsJsonArray("continentalness").get(0).getAsDouble(), 1e-9, id);
        }
        List<JsonObject> sea = of(entries, "minecraft:ocean");
        assertEquals(1, sea.size(), entries.toString());
        assertEquals("[-2.0,-0.19]", sea.get(0).get("continentalness").toString());
        assertEquals("[-2.0,2.0]", sea.get(0).get("temperature").toString());
        JsonArray areas = source.getAsJsonObject("regions").getAsJsonArray("areas");
        assertEquals(2, areas.size());
        assertEquals("land", areas.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("[-0.3333,0.3333]", areas.get(0).getAsJsonObject().get("columns").toString());
        assertEquals("ocean", areas.get(1).getAsJsonObject().get("role").getAsString());
        assertNull(source.getAsJsonObject("regions").get("columns"));
        Map<String, Double> land = shares(entries, "temperature", "humidity");
        land.remove("minecraft:ocean");
        land.forEach((id, share) -> assertEquals(1 / 9.0, share, 1e-3, id));
    }

    @Test
    void oceanBiomesTakeTheSeaAndWeightsHoldPerRole() {
        JsonObject source = source("r", GENERATOR + ", " + list(List.of("minecraft:warm_ocean", "minecraft:plains", "minecraft:lukewarm_ocean",
                "minecraft:desert")) + ", \"biomeLayout\": {\"mode\": \"regions\", \"weights\": {\"minecraft:warm_ocean\": 3, "
                + "\"minecraft:plains\": 1, \"minecraft:desert\": 3}}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        assertTrue(of(entries, "minecraft:ocean").isEmpty(), "a listed ocean leaves no room for a default sea");
        for (String id : List.of("minecraft:warm_ocean", "minecraft:lukewarm_ocean")) {
            for (JsonObject p : of(entries, id)) assertEquals(-0.19, p.getAsJsonArray("continentalness").get(1).getAsDouble(), 1e-9, id);
        }
        Map<String, Double> s = shares(entries, "temperature", "humidity");
        assertEquals(0.75, s.get("minecraft:warm_ocean"), 1e-3);
        assertEquals(0.25, s.get("minecraft:lukewarm_ocean"), 1e-3);
        assertEquals(0.75, s.get("minecraft:desert"), 1e-3);
        assertEquals(0.25, s.get("minecraft:plains"), 1e-3);
    }

    @Test
    void shoresAndRiversTakeTheTerrainsBands() {
        JsonObject source = source("r", GENERATOR + ", " + ISLANDS + ", " + list(List.of("minecraft:plains", "minecraft:beach",
                "adventure:tropical_shores", "minecraft:river", "minecraft:warm_ocean")) + ", \"biomeLayout\": {\"mode\": \"regions\"}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        for (String id : List.of("minecraft:beach", "adventure:tropical_shores")) {
            List<JsonObject> cells = of(entries, id);
            assertFalse(cells.isEmpty(), id);
            for (JsonObject p : cells) {
                JsonArray c = p.getAsJsonArray("continentalness");
                assertTrue(c.get(0).getAsDouble() >= -0.19 - 1e-9 && c.get(1).getAsDouble() <= -0.09 + 1e-9, id + " " + c);
            }
        }
        Map<String, Double> s = shares(entries, "temperature", "humidity");
        assertEquals(s.get("minecraft:beach"), s.get("adventure:tropical_shores"), 1e-3, "shores divide their band by weight");
        for (JsonObject p : of(entries, "minecraft:river")) {
            assertEquals("[-0.05,0.05]", p.get("weirdness").toString());
            assertEquals("[-0.24,-0.14]", p.get("continentalness").toString());
        }
        for (JsonObject p : of(entries, "minecraft:plains")) assertTrue(p.getAsJsonArray("continentalness").get(0).getAsDouble() >= -0.16);
        JsonArray areas = source.getAsJsonObject("regions").getAsJsonArray("areas");
        assertEquals("river", areas.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("shore", areas.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void cavesStayUnderLandAtDepth() {
        JsonObject source = source("r", GENERATOR + ", \"biomes\": [\"minecraft:plains\", \"minecraft:dripstone_caves\", "
                + "{\"id\": \"minecraft:lush_caves\", \"climate\": {\"depth\": [0.3, 2]}}], \"biomeLayout\": {\"mode\": \"regions\"}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        for (JsonObject p : of(entries, "minecraft:dripstone_caves")) {
            assertEquals(0.2, p.getAsJsonArray("depth").get(0).getAsDouble(), 1e-9);
            assertEquals(-0.19, p.getAsJsonArray("continentalness").get(0).getAsDouble(), 1e-9);
        }
        for (JsonObject p : of(entries, "minecraft:plains")) assertEquals("[-2.0,0.2]", p.get("depth").toString());
        for (JsonObject p : of(entries, "minecraft:lush_caves")) assertEquals(-0.19, p.getAsJsonArray("continentalness").get(0).getAsDouble(), 1e-9);
    }

    @Test
    void rolesAndTheSeaCanBeGiven() {
        JsonObject source = source("r", GENERATOR + ", " + list(List.of("minecraft:plains", "minecraft:swamp")) + ", \"biomeLayout\": "
                + "{\"mode\": \"regions\", \"roles\": {\"minecraft:swamp\": \"ocean\"}}");
        JsonArray entries = source.getAsJsonObject("base").getAsJsonArray("biomes");
        for (JsonObject p : of(entries, "minecraft:swamp")) assertEquals(-0.19, p.getAsJsonArray("continentalness").get(1).getAsDouble(), 1e-9);
        JsonObject sea = source("s", GENERATOR + ", " + list(List.of("minecraft:plains")) + ", \"biomeLayout\": "
                + "{\"mode\": \"regions\", \"sea\": \"minecraft:deep_ocean\"}");
        assertEquals(1, of(sea.getAsJsonObject("base").getAsJsonArray("biomes"), "minecraft:deep_ocean").size());
        for (String bad : List.of("\"roles\": {\"minecraft:plains\": \"lake\"}", "\"roles\": {\"minecraft:desert\": \"land\"}",
                "\"sea\": \"minecraft:nowhere\"")) {
            assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("b", GENERATOR + ", " + list(List.of("minecraft:plains"))
                    + ", \"biomeLayout\": {\"mode\": \"regions\", " + bad + "}"), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("b", GENERATOR + ", " + SKY + ", " + list(List.of("minecraft:plains"))
                + ", \"biomeLayout\": {\"mode\": \"regions\", \"sea\": \"minecraft:ocean\"}"));
    }

    @Test
    void withoutACoastRolesTakeNoPart() {
        JsonObject source = source("r", GENERATOR + ", " + SKY + ", " + list(List.of("minecraft:plains", "minecraft:warm_ocean", "minecraft:beach"))
                + ", \"biomeLayout\": {\"mode\": \"regions\"}");
        for (JsonElement e : source.getAsJsonObject("base").getAsJsonArray("biomes")) {
            assertEquals("[-2.0,2.0]", e.getAsJsonObject().getAsJsonObject("parameters").get("continentalness").toString());
        }
        assertNull(source.getAsJsonObject("regions").get("areas"));
    }
}
