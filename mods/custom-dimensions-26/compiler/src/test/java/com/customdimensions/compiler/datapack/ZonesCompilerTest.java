package com.customdimensions.compiler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ZonesCompilerTest {
    private static final String OVERWORLD = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"spawn\": [100, 64, -50]";
    private static final String RING = "{\"shape\": \"ring\", \"centre\": \"spawn\", \"inner\": 2000, \"outer\": 3000, \"blend\": 200, "
            + "\"edgeJitter\": 64, \"terrain\": {\"base\": \"floating_islands\"}, \"biomes\": [\"minecraft:cherry_grove\", \"minecraft:meadow\"], "
            + "\"liquids\": {\"aquifers\": \"dry\"}}";

    private static JsonObject file(DatapackCompiler.Output out, String path) {
        String text = out.files().get(path);
        if (text == null) throw new AssertionError("no " + path + " in " + out.files().keySet());
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject settings(DatapackCompiler.Output out, String slug) {
        return file(out, "data/adventure/worldgen/noise_settings/" + slug + ".json");
    }

    private static JsonObject source(DatapackCompiler.Output out, String slug) {
        return file(out, "data/customdimensions/lazy_dimension/" + slug + ".json").getAsJsonObject("generator").getAsJsonObject("biome_source");
    }

    @Test
    void aTerrainRingBlendsTheRouterAndCarriesItsBiomes() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"zones\": [" + RING + "]");
        JsonObject router = settings(out, "t").getAsJsonObject("noise_router");
        JsonObject cheese = file(out, "data/adventure/worldgen/density_function/zones/t/0/sloped_cheese.json");
        assertEquals("minecraft:overworld/sloped_cheese", cheese.get("first").getAsString());
        assertEquals("adventure:terrain/t/zone_0/sloped_cheese", cheese.get("second").getAsString());
        assertTrue(router.getAsJsonObject("final_density").get("first").toString().contains("adventure:zones/t/0/sloped_cheese"));
        for (String key : new String[] {"final_density", "depth", "chunk_surface_level"}) {
            JsonObject lerp = router.getAsJsonObject(key);
            assertEquals("minecraft:lerp", lerp.get("type").getAsString(), key);
            assertTrue(lerp.get("alpha").toString().contains("adventure:zones/t/0/mask"), key);
            assertEquals("adventure:terrain/t/zone_0/" + key, lerp.get("second").getAsString());
        }
        assertEquals("adventure:zones/t/temperature", router.get("temperature").getAsString());
        JsonObject aquifers = settings(out, "t").getAsJsonObject("aquifers");
        assertEquals("minecraft:lerp", aquifers.getAsJsonObject("exclusion").get("type").getAsString());
        assertEquals(1.0, aquifers.getAsJsonObject("exclusion").get("second").getAsDouble());
        JsonObject surface = aquifers.getAsJsonObject("surface_level");
        assertEquals(320.0 + ZonesCompiler.DRY_SURFACE_ABOVE, surface.get("second").getAsDouble());
        assertEquals("adventure:terrain/t/zone_0/preliminary_surface_level", surface.getAsJsonObject("first").get("second").getAsString());

        JsonObject mask = file(out, "data/adventure/worldgen/density_function/zones/t/0/mask.json");
        assertEquals("minecraft:cache", mask.get("type").getAsString());
        assertTrue(mask.toString().contains("adventure:zone_jitter"));
        assertTrue(out.files().containsKey("data/adventure/worldgen/noise/zone_jitter.json"));
        assertTrue(out.files().containsKey("data/adventure/worldgen/density_function/terrain/t/zone_0/final_density.json"));

        JsonObject channel = file(out, "data/adventure/worldgen/density_function/zones/t/temperature.json");
        assertEquals("minecraft:range_choice", channel.get("type").getAsString());
        assertEquals(0.5, channel.get("min_inclusive").getAsDouble());
        assertEquals(ZonesCompiler.CHANNEL, channel.getAsJsonObject("when_in_range").get("right").getAsDouble());
        assertEquals("minecraft:overworld/temperature", channel.get("when_out_of_range").getAsString());

        JsonObject src = source(out, "t");
        assertEquals(ZonesCompiler.SOURCE_TYPE, src.get("type").getAsString());
        assertEquals("minecraft:multi_noise", src.getAsJsonObject("base").get("type").getAsString());
        JsonArray zones = src.getAsJsonArray("zones");
        assertEquals(1, zones.size());
        Set<String> biomes = new HashSet<>();
        zones.get(0).getAsJsonObject().getAsJsonArray("biomes").forEach(e -> biomes.add(e.getAsJsonObject().get("biome").getAsString()));
        assertEquals(Set.of("minecraft:cherry_grove", "minecraft:meadow"), biomes);
    }

    @Test
    void everyShapeCompilesAndChannelsFollowZoneOrder() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"zones\": ["
                + "{\"shape\": \"box\", \"halfSize\": [3000, 300], \"blend\": 96, \"terrain\": \"canyons\", \"biomes\": [\"minecraft:badlands\", \"minecraft:desert\"]},"
                + "{\"shape\": \"sector\", \"radius\": 800, \"angles\": [0, 90], \"biomes\": [\"minecraft:jungle\"]},"
                + "{\"shape\": \"polygon\", \"centre\": [0, 0], \"points\": [[0, 0], [100, 0], [0, 100]], \"swap\": {\"minecraft:plains\": \"minecraft:sunflower_plains\"}},"
                + "{\"shape\": \"circle\", \"radius\": 50, \"terrain\": \"spires\"},"
                + "{\"shape\": \"all\", \"swap\": {\"minecraft:forest\": \"minecraft:birch_forest\", \"minecraft:river\": \"betterend:nowhere\"}}]");
        JsonObject sector = file(out, "data/adventure/worldgen/density_function/zones/t/1/mask.json");
        assertTrue(sector.toString().contains("customdimensions:zone_distance"));
        JsonObject box = file(out, "data/adventure/worldgen/density_function/zones/t/0/mask.json");
        assertFalse(box.toString().contains("customdimensions:"));
        assertFalse(out.files().containsKey("data/adventure/worldgen/noise/zone_jitter.json"));
        JsonObject src = source(out, "t");
        JsonArray zones = src.getAsJsonArray("zones");
        assertEquals(3, zones.size());
        assertEquals("minecraft:sunflower_plains", zones.get(2).getAsJsonObject().getAsJsonObject("swap").get("minecraft:plains").getAsString());
        assertNull(zones.get(2).getAsJsonObject().get("biomes"));
        assertEquals("{\"minecraft:forest\":\"minecraft:birch_forest\"}", src.getAsJsonObject("swap").toString());
        assertTrue(out.report().entry("t").skippedBiomes().stream().anyMatch(s -> s.contains("betterend:nowhere")));
        JsonObject channel = file(out, "data/adventure/worldgen/density_function/zones/t/temperature.json");
        assertEquals("adventure:zones/t/2/mask", channel.get("input").getAsString());
        assertEquals(3 * ZonesCompiler.CHANNEL, channel.getAsJsonObject("when_in_range").get("right").getAsDouble());
        JsonObject finalDensity = settings(out, "t").getAsJsonObject("noise_router").getAsJsonObject("final_density");
        assertTrue(finalDensity.get("alpha").toString().contains("adventure:zones/t/3/mask"));
        JsonObject cheese = file(out, "data/adventure/worldgen/density_function/zones/t/3/sloped_cheese.json");
        assertEquals("adventure:zones/t/0/sloped_cheese", cheese.get("first").getAsString());
        assertEquals("adventure:terrain/t/zone_3/sloped_cheese", cheese.get("second").getAsString());
    }

    @Test
    void aStampOfAMissingBiomeIsAWarningNotAFailure() {
        DatapackCompiler.Output out = Fixtures.compile("t", OVERWORLD + ", \"zones\": [{\"centre\": [10, 10], \"radius\": 96, "
                + "\"biomes\": [\"terralith:moonlight_grove\"]}]");
        assertTrue(out.report().entry("t").skippedBiomes().stream().anyMatch(s -> s.contains("terralith:moonlight_grove")));
        assertEquals("minecraft:multi_noise", source(out, "t").get("type").getAsString());
    }

    @Test
    void zonesWrapAnyNoiseBiomeSource() {
        DatapackCompiler.Output end = Fixtures.compile("e", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"zones\": "
                + "[{\"centre\": [1180, -860], \"radius\": 200, \"blend\": 40, \"biomes\": [\"minecraft:deep_dark\"]}]");
        JsonObject src = source(end, "e");
        assertEquals("minecraft:the_end", src.getAsJsonObject("base").get("type").getAsString());
        assertEquals("adventure:zones/e/temperature", settings(end, "e").getAsJsonObject("noise_router").get("temperature").getAsString());
        DatapackCompiler.Output tiles = Fixtures.compile("c", "\"generator\": {\"type\": \"checkerboard\", \"cellScale\": 3}, "
                + "\"biomes\": [\"minecraft:desert\"], \"zones\": [{\"shape\": \"square\", \"radius\": 1050, \"blend\": 0, \"biomes\": [\"minecraft:basalt_deltas\"]}]");
        assertEquals("minecraft:checkerboard", source(tiles, "c").getAsJsonObject("base").get("type").getAsString());
    }

    @Test
    void aZoneTerrainThePresetRefusesNamesTheZone() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                "\"generator\": {\"type\": \"noise\", \"preset\": \"caves\"}, \"zones\": [{\"radius\": 100, \"terrain\": \"canyons\"}]"));
        assertTrue(e.getMessage().contains("t: zones[0]"), e.getMessage());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("t",
                OVERWORLD + ", \"zones\": [{\"radius\": 100, \"terrain\": {\"base\": \"canyons\", \"params\": {\"depth\": 9999}}}]"));
        assertTrue(bad.getMessage().contains("t: zones[0]"), bad.getMessage());
    }

    @Test
    void aFlatGeneratorReportsItsZones() {
        DatapackCompiler.Output out = Fixtures.compile("t", "\"generator\": {\"type\": \"flat\"}, \"zones\": [{\"radius\": 10, \"biomes\": [\"minecraft:desert\"]}]");
        assertTrue(out.report().entry("t").unsupported().stream().anyMatch(u -> u.startsWith("`zones`")));
    }

    static Set<String> types(JsonElement e, Set<String> into) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive()) into.add(o.get("type").getAsString());
            o.entrySet().forEach(kv -> types(kv.getValue(), into));
        } else if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(v -> types(v, into));
        }
        return into;
    }
}
