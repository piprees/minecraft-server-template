package com.customdimensions.core.score;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollSpecTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void readsEveryField() {
        RollSpec s = RollSpec.parse(json("{\"skip\": true, \"mood\": \"Serene\", \"spawnFilter\": [\"minecraft:meadow\"], "
                + "\"water\": \"sea\", \"terrain\": \"hilly\", \"heightRange\": [120, 40], \"wants\": {\"village\": \"near_spawn\"}, "
                + "\"shuns\": [\"monument\"], \"allowHazardousSpawn\": true}"), null);
        assertTrue(s.skip());
        assertEquals("serene", s.mood());
        assertEquals(List.of("minecraft:meadow"), s.spawnFilter());
        assertEquals("sea", s.water());
        assertEquals("hilly", s.terrain());
        assertArrayEquals(new int[] {40, 120}, s.heightRange());
        assertEquals("near_spawn", s.wants().get("village"));
        assertEquals(List.of("monument"), s.shuns());
        assertTrue(s.allowHazardousSpawn());
    }

    @Test
    void anAbsentFieldIsEmptyAndAMissingRollIsNone() {
        RollSpec s = RollSpec.parse(null, null);
        assertFalse(s.skip());
        assertNull(s.terrain());
        assertNull(s.heightRange());
        assertTrue(s.wants().isEmpty());
        assertTrue(s.shuns().isEmpty());
    }

    @Test
    void aWantWithAnUnknownWordIsListedAndNotScored() {
        RollSpec s = RollSpec.parse(json("{\"wants\": {\"village\": \"spread\", \"tower\": \"sideways\", \"hut\": 3}}"), null);
        assertEquals(1, s.wants().size());
        assertEquals(List.of("tower", "hut"), s.unknownWants());
    }

    @Test
    void shunsJoinTheRollListTheRollObjectAndTheStructuresBlock() {
        RollSpec list = RollSpec.parse(json("{\"shuns\": [\"monument\", \" \"]}"), json("{\"shuns\": {\"coliseum\": {}, \"monument\": {}}}"));
        assertEquals(List.of("monument", "coliseum"), list.shuns());
        RollSpec object = RollSpec.parse(json("{\"shuns\": {\"igloo\": {}}}"), null);
        assertEquals(List.of("igloo"), object.shuns());
    }

    @Test
    void aFlatOrMalformedHeightRangeIsNone() {
        assertNull(RollSpec.parse(json("{\"heightRange\": [64, 64]}"), null).heightRange());
        assertNull(RollSpec.parse(json("{\"heightRange\": [64]}"), null).heightRange());
    }

    @Test
    void theTargetTakesThePlayerBorderOrTheDefault() {
        assertEquals(RollTarget.DEFAULT_RADIUS, RollTarget.of("x", null).playableRadius());
    }
}
