package com.customdimensions.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PlaceMap}: places read from a compiled biome source's place columns. */
class PlaceMapTest {
    /** A two-place sky world as the compiler writes it: underside in both columns, a basin shared out, a grouped land biome. */
    static final String SKY = """
            {"type": "customdimensions:zoned", "base": {"type": "minecraft:multi_noise", "biomes": [
              {"biome": "adventure:hanging_gardens", "parameters": {"temperature": [-2.0, 0.0], "humidity": [-2.0, 2.0]}},
              {"biome": "adventure:hanging_gardens", "parameters": {"temperature": [0.0, 2.0], "humidity": [-2.0, 2.0]}},
              {"biome": "minecraft:warm_ocean", "parameters": {"temperature": [-2.0, 0.0], "humidity": [-2.0, 2.0]}},
              {"biome": "minecraft:lukewarm_ocean", "parameters": {"temperature": [0.0, 2.0], "humidity": [-2.0, 0.0]}},
              {"biome": "minecraft:jungle", "parameters": {"temperature": [-2.0, 0.0], "humidity": [-2.0, 2.0]}},
              {"biome": "adventure:sky_highlands", "parameters": {"temperature": [0.0, 2.0], "humidity": [-2.0, 0.5]}},
              {"biome": "minecraft:meadow", "parameters": {"temperature": [0.0, 2.0], "humidity": [0.5, 2.0]}}]},
             "regions": {"axes": ["temperature", "humidity"], "areas": [{"role": "underside", "columns": [0.0]},
               {"role": "land", "columns": [0.0]}], "radius": 2048, "places": true}}""";

    @Test
    void eachColumnIsAPlaceNamedForItsGroup() {
        PlaceMap m = PlaceMap.of(JsonParser.parseString(SKY), Map.of("coast", List.of("minecraft:jungle", "minecraft:warm_ocean"),
                "highlands", List.of("adventure:sky_highlands", "minecraft:meadow")), id -> id.contains("ocean") || id.contains("gardens"));
        assertEquals(2, m.count());
        assertEquals(List.of("coast", "highlands"), m.names());
        assertEquals("temperature", m.axis());
        assertEquals(0, m.of("minecraft:jungle"));
        assertEquals(0, m.of("minecraft:warm_ocean"));
        assertEquals(1, m.of("minecraft:meadow"));
        assertEquals(1, m.of("minecraft:lukewarm_ocean"), "a shared biome in one column only is that place's");
        assertEquals(-1, m.of("adventure:hanging_gardens"), "a biome in every column is no place's");
        assertEquals(-1, m.of("minecraft:plains"));
        assertEquals(0, m.index(fieldValue(0, 2)));
        assertEquals(1, m.index(fieldValue(1, 2)));
    }

    @Test
    void anUngroupedPlaceIsNamedForItsBiomeNotTheRoleItShares() {
        PlaceMap m = PlaceMap.of(JsonParser.parseString(SKY), Map.of(), Set.of("minecraft:warm_ocean", "minecraft:lukewarm_ocean")::contains);
        assertEquals(List.of("minecraft:jungle", "adventure:sky_highlands"), m.names());
    }

    @Test
    void columnsAtTheRegionsLevelAndThreePlaces() {
        JsonObject o = JsonParser.parseString("""
                {"type": "customdimensions:zoned", "base": {"type": "minecraft:multi_noise", "biomes": [
                  {"biome": "a", "parameters": {"humidity": [-2.0, -0.3333]}},
                  {"biome": "b", "parameters": {"humidity": [-0.3333, 0.3333]}},
                  {"biome": "c", "parameters": {"humidity": [0.3333, 2.0]}},
                  {"biome": "d", "parameters": {"humidity": 0.5}}]},
                 "regions": {"axes": ["humidity"], "columns": [-0.3333, 0.3333], "radius": 512, "places": true}}""").getAsJsonObject();
        PlaceMap m = PlaceMap.of(o, Map.of(), id -> false);
        assertEquals(List.of("a", "b", "c"), m.names());
        assertEquals(2, m.of("d"), "a point parameter lies in its column");
        assertEquals(1, m.index(fieldValue(1, 3)));
        assertEquals(2, m.index(fieldValue(2, 3)));
    }

    @Test
    void aSourceWithoutPlacesHasNone() {
        assertNull(PlaceMap.of(JsonParser.parseString("{\"type\": \"minecraft:multi_noise\", \"biomes\": []}"), Map.of(), id -> false));
        assertNull(PlaceMap.of(JsonParser.parseString(SKY.replace("\"places\": true", "\"places\": false")), Map.of(), id -> false));
        assertNull(PlaceMap.of(null, Map.of(), id -> false));
    }

    /** The value the compiled field takes in place {@code i} of {@code n}. */
    private static double fieldValue(int i, int n) {
        return -1 + (2.0 * i + 1) / n;
    }
}
