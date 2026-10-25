package com.customdimensions.core.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneSpecTest {
    private static final BlockPos SPAWN = new BlockPos(500, 70, -300);

    private static ZoneSpec read(String json) {
        return ZoneSpec.read("the_x", 2, JsonParser.parseString(json).getAsJsonObject(), SPAWN);
    }

    @Test
    void convertedBiomePatchReadsAsACircleStamp() {
        ZoneSpec zone = read("{\"shape\": \"circle\", \"centre\": [287, -64], \"radius\": 160, \"blend\": 32, \"biomes\": [\"minecraft:crimson_forest\"]}");
        assertInstanceOf(ZoneShape.Circle.class, zone.mask().shape());
        assertEquals(287, zone.mask().centreX());
        assertEquals(List.of("minecraft:crimson_forest"), zone.biomes().stream().map(b -> b.id()).toList());
        assertTrue(zone.changesBiomes());
        assertNull(zone.terrain());
    }

    @Test
    void spawnCentresOnTheDefinitionsSpawn() {
        ZoneSpec ring = read("{\"shape\": \"ring\", \"centre\": \"spawn\", \"inner\": 2000, \"outer\": 3000, \"blend\": 200, "
                + "\"edgeJitter\": 64, \"terrain\": \"floating_islands\", \"liquids\": {\"aquifers\": \"dry\"}}");
        assertEquals(500, ring.mask().centreX());
        assertEquals(-300, ring.mask().centreZ());
        assertEquals(64, ring.mask().jitter());
        assertEquals("floating_islands", ring.terrain().get("base").getAsString());
        assertEquals("dry", ring.aquifers());
        assertFalse(ring.changesBiomes());
        ZoneSpec noSpawn = ZoneSpec.read("s", 0, JsonParser.parseString("{\"centre\": \"spawn\", \"radius\": 5}").getAsJsonObject(), null);
        assertEquals(0, noSpawn.mask().centreX());
    }

    @Test
    void everyShapeReads() {
        assertInstanceOf(ZoneShape.Box.class, read("{\"shape\": \"square\", \"radius\": 400}").mask().shape());
        assertEquals(new ZoneShape.Box(300, 100), read("{\"shape\": \"box\", \"halfSize\": [300, 100]}").mask().shape());
        assertEquals(new ZoneShape.Sector(0, 900, 10, 80), read("{\"shape\": \"sector\", \"radius\": 900, \"angles\": [10, 80]}").mask().shape());
        assertInstanceOf(ZoneShape.Polygon.class, read("{\"shape\": \"polygon\", \"points\": [[0, 0], [10, 0], [0, 10]]}").mask().shape());
        ZoneSpec swap = read("{\"shape\": \"all\", \"swap\": {\"minecraft:plains\": \"minecraft:meadow\"}}");
        assertTrue(swap.globalSwap());
    }

    @Test
    void errorsNameTheSlugAndZoneIndex() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read("{\"shape\": \"hexagon\"}"));
        assertTrue(e.getMessage().startsWith("the_x: zones[2]: shape hexagon"), e.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read("{\"shape\": \"ring\", \"inner\": 5}"))
                .getMessage().contains("outer is required"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read("{\"radius\": 5, \"colour\": 1}"))
                .getMessage().contains("unknown field colour"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read("{\"radius\": 5, \"liquids\": {\"aquifers\": \"flooded\"}}"))
                .getMessage().contains("aquifers flooded"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read("{\"radius\": 5, \"liquids\": {\"seaLevel\": 3}}"))
                .getMessage().contains("one per dimension"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> read("{\"shape\": \"all\", \"terrain\": \"canyons\"}"))
                .getMessage().contains("shape all"));
        assertThrows(IllegalArgumentException.class, () -> read("{\"centre\": \"origin\", \"radius\": 5}"));
        assertThrows(IllegalArgumentException.class, () -> read("{\"radius\": 5, \"blend\": -1}"));
    }

    @Test
    void queryReturnsCoveringZonesAndTheBiomeZoneOnTop() {
        JsonObject a = JsonParser.parseString("{\"radius\": 100, \"blend\": 50, \"biomes\": [\"minecraft:desert\"]}").getAsJsonObject();
        JsonObject b = JsonParser.parseString("{\"centre\": [80, 0], \"radius\": 40, \"blend\": 20, \"biomes\": [\"minecraft:jungle\"]}").getAsJsonObject();
        JsonObject t = JsonParser.parseString("{\"radius\": 1000, \"terrain\": \"canyons\"}").getAsJsonObject();
        ZoneQuery query = new ZoneQuery(List.of(ZoneSpec.read("q", 0, a, null), ZoneSpec.read("q", 1, b, null), ZoneSpec.read("q", 2, t, null)));
        assertEquals(List.of(0, 1, 2), query.containing(80, 0).stream().map(ZoneQuery.Hit::index).toList());
        assertEquals(1, query.biomeZone(80, 0).index());
        assertEquals(0, query.biomeZone(-50, 0).index());
        assertEquals(0.5, query.containing(125, 0).get(0).weight(), 1e-9);
        assertEquals(1, query.biomeZone(125, 0).index());
        assertEquals(0, query.biomeZone(-125, 0).index());
        assertNull(query.biomeZone(-130, 0));
        assertNull(query.biomeZone(500, 0));
        assertEquals(List.of(2), query.containing(500, 0).stream().map(ZoneQuery.Hit::index).toList());
    }
}
