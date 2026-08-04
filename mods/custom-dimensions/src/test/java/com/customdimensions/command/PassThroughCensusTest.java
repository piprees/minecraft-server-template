package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pass-through census emission helper.
 *
 * Bootstrap-free: tests the pure toJson serialisation and SetCensus record,
 * not the live calculator (that requires a running server). The live
 * getStartChunk parity is tested by test_passthrough_parity.py against
 * dumped fixtures.
 */
class PassThroughCensusTest {

    @Test
    void toJsonEmptyMap() {
        String json = PassThroughCensus.toJson(Map.of());
        assertEquals("{}", json);
    }

    @Test
    void toJsonSingleSet() {
        var census = new TreeMap<String, PassThroughCensus.SetCensus>();
        census.put("test:village", new PassThroughCensus.SetCensus(
                "test:village",
                "minecraft:random_spread",
                34, 8, 10387312, 1.0f,
                List.of(new int[]{5, 10}, new int[]{-3, 7})));

        String json = PassThroughCensus.toJson(census);
        assertTrue(json.contains("\"test:village\""), "set id missing");
        assertTrue(json.contains("\"placementType\": \"minecraft:random_spread\""),
                "placement type missing");
        assertTrue(json.contains("\"spacing\": 34"), "spacing missing");
        assertTrue(json.contains("\"separation\": 8"), "separation missing");
        assertTrue(json.contains("\"salt\": 10387312"), "salt missing");
        assertTrue(json.contains("\"frequency\": 1.0"), "frequency missing");
        assertTrue(json.contains("[5, 10]"), "first position missing");
        assertTrue(json.contains("[-3, 7]"), "second position missing");
    }

    @Test
    void toJsonMultipleSetsAreSorted() {
        var census = new TreeMap<String, PassThroughCensus.SetCensus>();
        census.put("b:second", new PassThroughCensus.SetCensus(
                "b:second", "minecraft:random_spread",
                20, 5, 100, 1.0f, List.of()));
        census.put("a:first", new PassThroughCensus.SetCensus(
                "a:first", "yungs:yung_random_spread",
                40, 10, 200, 0.5f, List.of(new int[]{1, 2})));

        String json = PassThroughCensus.toJson(census);
        int posFirst = json.indexOf("a:first");
        int posSecond = json.indexOf("b:second");
        assertTrue(posFirst >= 0 && posSecond >= 0, "both set ids present");
        assertTrue(posFirst < posSecond, "entries are sorted by set id");
    }

    @Test
    void toJsonEmptyPositions() {
        var census = new TreeMap<String, PassThroughCensus.SetCensus>();
        census.put("empty:set", new PassThroughCensus.SetCensus(
                "empty:set", "minecraft:random_spread",
                32, 8, 0, 1.0f, List.of()));

        String json = PassThroughCensus.toJson(census);
        assertTrue(json.contains("\"positions\": []"), "empty positions array");
    }

    @Test
    void setCensusRecordFieldAccess() {
        var sc = new PassThroughCensus.SetCensus(
                "ns:id", "type:spread", 32, 8, 999, 0.75f,
                List.of(new int[]{0, 0}));
        assertEquals("ns:id", sc.setId());
        assertEquals("type:spread", sc.placementType());
        assertEquals(32, sc.spacing());
        assertEquals(8, sc.separation());
        assertEquals(999, sc.salt());
        assertEquals(0.75f, sc.frequency());
        assertEquals(1, sc.positions().size());
    }

    @Test
    void toJsonProducesValidStructure() {
        var census = new TreeMap<String, PassThroughCensus.SetCensus>();
        census.put("mod:structures", new PassThroughCensus.SetCensus(
                "mod:structures", "mod:custom_spread",
                24, 6, 42, 0.8f,
                List.of(new int[]{10, -20}, new int[]{30, 40}, new int[]{-5, -5})));

        String json = PassThroughCensus.toJson(census);
        // Starts and ends with braces
        String trimmed = json.trim();
        assertTrue(trimmed.startsWith("{"), "JSON starts with {");
        assertTrue(trimmed.endsWith("}"), "JSON ends with }");
        // All three position pairs are present
        assertTrue(json.contains("[10, -20]"), "first position");
        assertTrue(json.contains("[30, 40]"), "second position");
        assertTrue(json.contains("[-5, -5]"), "third position");
    }

    @Test
    void frequencySerialisation() {
        // Verify that sub-1.0 frequency values are preserved
        var census = new TreeMap<String, PassThroughCensus.SetCensus>();
        census.put("rare:set", new PassThroughCensus.SetCensus(
                "rare:set", "minecraft:random_spread",
                64, 16, 12345, 0.01f, List.of()));

        String json = PassThroughCensus.toJson(census);
        assertTrue(json.contains("\"frequency\": 0.01"), "sub-1.0 frequency: " + json);
    }
}
