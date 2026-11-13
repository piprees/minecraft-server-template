package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Biomes on a roofed floor through the whole compiler: sea and shore roles under a {@code liquids.seaLevel} over
 * part of the floor, regions ranked over the floor above it, and biomes bound below the floor.
 */
class RoofedBiomesTest {

    /** A roofed Nether whose floor at 70 ± 18 lies partly under a lava sea at 74, as a buried world's. */
    private static final String DROWNED = "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
            + "\"terrain\": {\"base\": \"roofed\", \"params\": {\"floorHeight\": 70, \"floorRelief\": 18, \"ceilingGap\": 40}}, "
            + "\"liquids\": {\"seaLevel\": 74, \"defaultFluid\": \"minecraft:lava\"}, ";
    private static final String BIOMES = "\"biomes\": [\"minecraft:basalt_deltas\", \"adventure:quartz_flats\", \"minecraft:soul_sand_valley\", "
            + "\"minecraft:nether_wastes\"], ";

    private static JsonObject json(DatapackCompiler.Output out, String path) {
        String s = out.files().get(path);
        if (s == null) throw new AssertionError("no " + path + " in " + out.files().keySet());
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject router(DatapackCompiler.Output out) {
        return json(out, "data/adventure/worldgen/noise_settings/t.json").getAsJsonObject("noise_router");
    }

    private static JsonObject source(DatapackCompiler.Output out) {
        return json(out, "data/customdimensions/lazy_dimension/t.json").getAsJsonObject("generator").getAsJsonObject("biome_source");
    }

    private static JsonObject regions(DatapackCompiler.Output out) {
        return source(out).getAsJsonObject("regions");
    }

    private static JsonArray entries(DatapackCompiler.Output out) {
        return source(out).getAsJsonObject("base").getAsJsonArray("biomes");
    }

    /** Each entry of {@code biome}'s {@code axis} range as {@code [lo, hi]}. */
    private static List<double[]> ranges(DatapackCompiler.Output out, String biome, String axis) {
        List<double[]> out2 = new ArrayList<>();
        for (JsonElement e : entries(out)) {
            JsonObject o = e.getAsJsonObject();
            if (!biome.equals(o.get("biome").getAsString())) continue;
            JsonElement r = o.getAsJsonObject("parameters").get(axis);
            out2.add(r.isJsonArray() ? new double[]{r.getAsJsonArray().get(0).getAsDouble(), r.getAsJsonArray().get(1).getAsDouble()}
                    : new double[]{r.getAsDouble(), r.getAsDouble()});
        }
        return out2;
    }

    private static List<String> notes(DatapackCompiler.Output out) {
        return out.report().entry("t").notes();
    }

    // ---- sea and shore roles ------------------------------------------------------------------

    @Test
    void anOceanRoleTakesTheFloorUnderTheSeaAndTheLandBiomesTheFloorAboveIt() {
        DatapackCompiler.Output out = Fixtures.compile("t", DROWNED + BIOMES
                + "\"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:basalt_deltas\": \"ocean\"}}");
        assertEquals("adventure:terrain/t/coast", router(out).get("continents").getAsString());
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/t/coast.json"));
        for (double[] r : ranges(out, "minecraft:basalt_deltas", "continentalness")) {
            assertTrue(r[1] <= 0, "the sea biome stays under the sea line: " + r[0] + ".." + r[1]);
        }
        for (String land : List.of("adventure:quartz_flats", "minecraft:soul_sand_valley", "minecraft:nether_wastes")) {
            List<double[]> rs = ranges(out, land, "continentalness");
            assertFalse(rs.isEmpty(), land);
            for (double[] r : rs) assertTrue(r[0] >= 0, land + " stays on the dry floor: " + r[0] + ".." + r[1]);
        }
        JsonArray areas = regions(out).getAsJsonArray("areas");
        List<String> roles = new ArrayList<>();
        areas.forEach(a -> roles.add(a.getAsJsonObject().get("role").getAsString()));
        assertEquals(List.of("land", "ocean"), roles);
        assertEquals("[0.0,2.0]", areas.get(0).getAsJsonObject().get("continentalness").toString());
        assertEquals("[-2.0,0.0]", areas.get(1).getAsJsonObject().get("continentalness").toString());
        assertNull(regions(out).get("land"), "the role areas already rank each group over its own floor");
        assertTrue(notes(out).stream().anyMatch(n -> n.startsWith("The router's continentalness is the floor against the sea at y 74")),
                notes(out).toString());
    }

    @Test
    void aShoreRoleTakesABandRoundTheSeaLine() {
        DatapackCompiler.Output out = Fixtures.compile("t", DROWNED + BIOMES
                + "\"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:basalt_deltas\": \"ocean\", \"minecraft:nether_wastes\": \"shore\"}}");
        JsonObject shore = regions(out).getAsJsonArray("areas").get(0).getAsJsonObject();
        assertEquals("shore", shore.get("role").getAsString());
        assertEquals("[-0.046875,0.046875]", shore.get("continentalness").toString());
        for (double[] r : ranges(out, "minecraft:nether_wastes", "continentalness")) {
            assertTrue(r[0] >= -3 / 64.0 - 1e-9 && r[1] <= 3 / 64.0 + 1e-9, "3 blocks either side of the sea: " + r[0] + ".." + r[1]);
        }
    }

    @Test
    void overLandRanksTheRegionsOverTheFloorAboveTheSea() {
        DatapackCompiler.Output out = Fixtures.compile("t", DROWNED + BIOMES + "\"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}");
        assertEquals("adventure:terrain/t/coast", router(out).get("continents").getAsString());
        assertEquals("{\"axis\":\"continentalness\",\"min\":0.0}", regions(out).get("land").toString());
    }

    @Test
    void withoutASeaRoleOrOverLandTheLayoutIsAsBefore() {
        DatapackCompiler.Output out = Fixtures.compile("t", DROWNED + BIOMES + "\"biomeLayout\": {\"mode\": \"regions\"}");
        assertFalse(router(out).has("continents") && router(out).get("continents").isJsonPrimitive()
                && router(out).get("continents").getAsJsonPrimitive().isString()
                && router(out).get("continents").getAsString().endsWith("/coast"));
        assertFalse(out.files().containsKey("data/adventure/worldgen/density_function/terrain/t/coast.json"));
        assertNull(regions(out).get("areas"));
    }

    @Test
    void aRoofedFloorRefusesRiversAndOverLandWithoutASeaOverIt() {
        String river = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", DROWNED + BIOMES
                + "\"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:basalt_deltas\": \"ocean\", \"minecraft:nether_wastes\": \"river\"}}"))
                .getMessage();
        assertTrue(river.contains("a roofed floor has no rivers"), river);
        String dry = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"nether\"}, \"terrain\": {\"base\": \"roofed\", \"params\": {\"floorHeight\": 70}}, \"liquids\": {\"seaLevel\": 40}, "
                + BIOMES + "\"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}")).getMessage();
        assertTrue(dry.contains("or over the floor of a roofed terrain above its liquids.seaLevel"), dry);
    }

    // ---- below the floor ----------------------------------------------------------------------

    /** A roofed Nether battlefield with chasms, no sea over its floor. */
    private static final String BATTLEFIELD = "\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}, "
            + "\"terrain\": {\"base\": \"roofed\", \"params\": {\"floorHeight\": 48, \"floorRelief\": 14, \"ceilingGap\": 50, \"chasms\": 0.03, "
            + "\"chasmDepth\": 30}}, \"liquids\": {\"seaLevel\": 20}, ";

    @Test
    void aBiomeBelowTheFloorHoldsTheBandAndNoneOfTheFloor() {
        DatapackCompiler.Output out = Fixtures.compile("t", BATTLEFIELD + "\"biomes\": [\"minecraft:soul_sand_valley\", \"adventure:ash_barrens\", "
                + "\"minecraft:deep_dark\"], \"biomeLayout\": {\"mode\": \"regions\", \"belowFloor\": {\"minecraft:deep_dark\": 8}}");
        List<double[]> dark = ranges(out, "minecraft:deep_dark", "depth");
        assertEquals(1, dark.size());
        assertEquals(1.5, dark.get(0)[0], 1e-9);
        assertEquals(2.0, dark.get(0)[1], 1e-9);
        for (String land : List.of("minecraft:soul_sand_valley", "adventure:ash_barrens")) {
            for (double[] r : ranges(out, land, "depth")) assertTrue(r[1] <= 1.5, land + " stays above the band: " + r[0] + ".." + r[1]);
        }
        String depth = json(out, "data/adventure/worldgen/density_function/terrain/t/depth.json").toString();
        assertTrue(depth.contains("adventure:terrain/t/floor"), "the depth reads the floor: " + depth);
        assertTrue(notes(out).stream().anyMatch(n -> n.contains("bound below the floor (blocks): {minecraft:deep_dark=8}")), notes(out).toString());
    }

    @Test
    void deeperBandsStackUnderTheShallowerOnes() {
        DatapackCompiler.Output out = Fixtures.compile("t", BATTLEFIELD + "\"biomes\": [\"minecraft:soul_sand_valley\", \"adventure:magma_land\", "
                + "\"minecraft:basalt_deltas\"], \"biomeLayout\": {\"mode\": \"regions\", \"belowFloor\": {\"minecraft:basalt_deltas\": 40, "
                + "\"adventure:magma_land\": 8}}");
        double[] shallow = ranges(out, "adventure:magma_land", "depth").get(0);
        double[] deep = ranges(out, "minecraft:basalt_deltas", "depth").get(0);
        assertEquals(1.5, shallow[0], 1e-9);
        assertEquals(RegionLayout.band(40, 8), shallow[1], 1e-9);
        assertEquals(shallow[1], deep[0], 1e-9);
        assertEquals(2.0, deep[1], 1e-9);
        assertEquals(1.5625, RegionLayout.band(40, 8), 1e-9, "32 blocks deeper at 0.5 per 256");
    }

    @Test
    void belowFloorRefusesWhatItCannotPlace() {
        String noTerrain = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", "\"generator\": {\"type\": \"noise\", "
                + "\"preset\": \"nether\"}, \"biomes\": [\"minecraft:soul_sand_valley\", \"minecraft:deep_dark\"], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"belowFloor\": {\"minecraft:deep_dark\": 8}}")).getMessage();
        assertTrue(noTerrain.contains("terrain.base roofed, which this definition does not have"), noTerrain);
        String climate = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", BATTLEFIELD + "\"biomes\": "
                + "[\"minecraft:soul_sand_valley\", {\"id\": \"minecraft:deep_dark\", \"climate\": {\"depth\": [0.2, 1]}}], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"belowFloor\": {\"minecraft:deep_dark\": 8}}")).getMessage();
        assertTrue(climate.contains("give one or the other"), climate);
        String same = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", BATTLEFIELD + "\"biomes\": "
                + "[\"minecraft:soul_sand_valley\", \"minecraft:deep_dark\", \"adventure:magma_land\"], \"biomeLayout\": {\"mode\": \"regions\", "
                + "\"belowFloor\": {\"minecraft:deep_dark\": 8, \"adventure:magma_land\": 8}}")).getMessage();
        assertTrue(same.contains("give each its own depth"), same);
        String shallow = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", BATTLEFIELD + "\"biomes\": "
                + "[\"minecraft:soul_sand_valley\", \"minecraft:deep_dark\"], \"biomeLayout\": {\"mode\": \"regions\", "
                + "\"belowFloor\": {\"minecraft:deep_dark\": 2}}")).getMessage();
        assertTrue(shallow.contains("from 4 to 256"), shallow);
        String unlisted = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t", BATTLEFIELD + "\"biomes\": "
                + "[\"minecraft:soul_sand_valley\"], \"biomeLayout\": {\"mode\": \"regions\", \"belowFloor\": {\"minecraft:deep_dark\": 8}}"))
                .getMessage();
        assertTrue(unlisted.contains("names no listed biome"), unlisted);
    }

    @Test
    void theSeaRolesAndTheBandsWorkTogether() {
        DatapackCompiler.Output out = Fixtures.compile("t", DROWNED + "\"biomes\": [\"minecraft:basalt_deltas\", \"adventure:quartz_flats\", "
                + "\"minecraft:deep_dark\"], \"biomeLayout\": {\"mode\": \"regions\", \"roles\": {\"minecraft:basalt_deltas\": \"ocean\"}, "
                + "\"belowFloor\": {\"minecraft:deep_dark\": 6}}");
        assertEquals("adventure:terrain/t/coast", router(out).get("continents").getAsString());
        assertEquals(1.5, ranges(out, "minecraft:deep_dark", "depth").get(0)[0], 1e-9);
        for (double[] r : ranges(out, "minecraft:basalt_deltas", "depth")) assertTrue(r[1] <= 1.5);
    }
}
