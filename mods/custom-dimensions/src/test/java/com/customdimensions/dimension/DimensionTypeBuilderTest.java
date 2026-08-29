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
            {"effects": "minecraft:the_nether",
             "infiniburn": "#minecraft:infiniburn_nether",
             "monsterSpawnLightLevel": [0, 7],
             "monsterSpawnBlockLightLimit": 15}""");
        assertEquals("minecraft:the_nether", e.effects);
        assertEquals("#minecraft:infiniburn_nether", e.infiniburn);
        assertNotNull(e.monsterSpawnLightLevel);
        assertEquals(15, e.monsterSpawnBlockLightLimit);
    }

    @Test
    void unsetFieldsStayNull() {
        DimensionConfig.Environment e = env("{\"ambientLight\": 0.5}");
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

    @Test
    void aCaveWorldIsRoofedAndEveryOtherTypeLeavesItToTheBase() {
        // cave builds on minecraft:caves — a bedrock roof and no sky — while
        // taking the OVERWORLD's dimension type, which says hasCeiling false.
        // Everything downstream then reads the column as open, and the highest
        // solid block in an open column of a roofed world is the roof: the same
        // in every column. the_luminous_caverns and the_emberglass_foundry drew
        // as one flat colour, height spread zero, all three sources agreeing.
        assertEquals(Boolean.TRUE, DimensionTypeBuilder.ceilingForWorldType("cave"));
        assertEquals(Boolean.TRUE, DimensionTypeBuilder.ceilingForWorldType("CAVE"));

        // null is "no opinion, the base type decides" — NOT false, which would
        // strip the ceiling off a nether that its base type correctly roofs.
        for (String t : new String[] {"multi_biome", "nether", "nether_islands",
                                      "end", "sky_islands", "void", "superflat", null}) {
            assertNull(DimensionTypeBuilder.ceilingForWorldType(t),
                    t + " must leave the ceiling to its base type");
        }
    }
}
