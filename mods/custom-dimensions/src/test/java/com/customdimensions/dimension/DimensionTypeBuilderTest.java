package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tier 1 environment fields: parsing + validation (the DimensionType
 * assembly itself is exercised by the live boot oracle, not unit tests). */
class DimensionTypeBuilderTest {
    private static final Gson GSON = new Gson();

    private DimensionConfig.Environment env(String json) {
        return GSON.fromJson(json, DimensionConfig.Environment.class);
    }

    @Test
    void newFieldsDeserialise() {
        DimensionConfig.Environment e = env("""
            {"coordinateScale": 8.0, "effects": "minecraft:the_nether",
             "infiniburn": "#minecraft:infiniburn_nether",
             "monsterSpawnLightLevel": [0, 7],
             "monsterSpawnBlockLightLimit": 15}""");
        assertEquals(8.0, e.coordinateScale);
        assertEquals("minecraft:the_nether", e.effects);
        assertEquals("#minecraft:infiniburn_nether", e.infiniburn);
        assertNotNull(e.monsterSpawnLightLevel);
        assertEquals(15, e.monsterSpawnBlockLightLimit);
    }

    @Test
    void unsetFieldsStayNull() {
        DimensionConfig.Environment e = env("{\"ambientLight\": 0.5}");
        assertNull(e.coordinateScale);
        assertNull(e.effects);
        assertNull(e.infiniburn);
        assertNull(e.monsterSpawnLightLevel);
        assertNull(e.monsterSpawnBlockLightLimit);
    }

    @Test
    void spawnLightConstant() {
        assertArrayEquals(new int[] {7, 7},
                DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("7"), "t"));
    }

    @Test
    void spawnLightUniformRange() {
        assertArrayEquals(new int[] {0, 15},
                DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("[0, 15]"), "t"));
    }

    @Test
    void spawnLightEqualRange() {
        assertArrayEquals(new int[] {3, 3},
                DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("[3, 3]"), "t"));
    }

    @Test
    void spawnLightRejectsInvalid() {
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("16"), "t"));
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("-1"), "t"));
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("[7, 3]"), "t"));
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("[0, 16]"), "t"));
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("\"bright\""), "t"));
        assertNull(DimensionTypeBuilder.validateSpawnLight(JsonParser.parseString("[1, 2, 3]"), "t"));
    }
}
