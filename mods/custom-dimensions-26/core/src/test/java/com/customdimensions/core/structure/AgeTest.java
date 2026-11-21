package com.customdimensions.core.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The table in lore/placement.md § Age. */
class AgeTest {

    @Test
    void integrityStaysInEachAgesRange() {
        for (Age a : Age.values()) {
            assertTrue(a.integrity(0) >= a.integrityMin && a.integrity(1) <= a.integrityMax);
        }
        assertEquals(0.9, Age.RECENT.integrity(0), 1e-9);
        assertEquals(0.3, Age.DEEP.integrity(0), 1e-9);
        assertTrue(Age.OLD.integrity(1) <= 0.85 && Age.ANCIENT.integrity(0) >= 0.5);
    }

    @Test
    void burialDeepensWithAge() {
        assertEquals(0, Age.RECENT.embed(12, 0.3));
        assertEquals(1, Age.OLD.embed(12, 0.3));
        assertEquals(2, Age.OLD.embed(12, 0.7));
        assertEquals(4, Age.ANCIENT.embed(12, 0.3));
        assertEquals(8, Age.DEEP.embed(12, 0.3));
    }

    @Test
    void stoneMossesAndCracks() {
        assertEquals("minecraft:mossy_stone_bricks", Age.OLD.weather("minecraft:stone_bricks", 0.1, 0.9));
        assertEquals("minecraft:cracked_stone_bricks", Age.OLD.weather("minecraft:stone_bricks", 0.9, 0.1));
        assertEquals("minecraft:mossy_cobblestone_wall", Age.ANCIENT.weather("minecraft:cobblestone_wall", 0.1, 0.9));
        assertEquals("minecraft:cracked_deepslate_tiles", Age.DEEP.weather("minecraft:deepslate_tiles", 0.95, 0.1));
        assertEquals("minecraft:stone_bricks", Age.RECENT.weather("minecraft:stone_bricks", 0.1, 0.5));
        assertEquals("minecraft:andesite", Age.DEEP.weather("minecraft:andesite", 0.0, 0.0));
    }

    @Test
    void copperOxidisesToItsAgesStage() {
        assertEquals("minecraft:exposed_copper", Age.RECENT.weather("minecraft:copper_block", 0.5, 0.5));
        assertEquals("minecraft:weathered_cut_copper_stairs", Age.OLD.weather("minecraft:cut_copper_stairs", 0.5, 0.5));
        assertEquals("minecraft:oxidized_copper", Age.ANCIENT.weather("minecraft:exposed_copper", 0.5, 0.5));
        assertEquals("minecraft:oxidized_copper_bulb", Age.DEEP.weather("minecraft:weathered_copper_bulb", 0.1, 0.5));
        assertEquals("minecraft:weathered_copper_bulb", Age.RECENT.weather("minecraft:weathered_copper_bulb", 0.1, 0.5), "never younger");
        assertEquals("minecraft:waxed_copper_block", Age.DEEP.weather("minecraft:waxed_copper_block", 0.1, 0.5));
        assertEquals("minecraft:copper_ore", Age.DEEP.weather("minecraft:copper_ore", 0.1, 0.5));
    }

    @Test
    void planksRotAndWoolGoes() {
        assertEquals(Age.AIR, Age.OLD.weather("minecraft:oak_planks", 0.1, 0.5));
        assertEquals("minecraft:oak_planks", Age.OLD.weather("minecraft:oak_planks", 0.5, 0.5));
        assertEquals(Age.AIR, Age.OLD.weather("minecraft:spruce_stairs", 0.2, 0.5));
        assertEquals("minecraft:oak_planks", Age.RECENT.weather("minecraft:oak_planks", 0.0, 0.0));
        assertEquals(Age.AIR, Age.OLD.weather("minecraft:white_wool", 0.99, 0.5));
        assertEquals(Age.AIR, Age.ANCIENT.weather("minecraft:red_carpet", 0.5, 0.5));
        assertEquals("minecraft:red_carpet", Age.RECENT.weather("minecraft:red_carpet", 0.5, 0.5));
        assertEquals("minecraft:stone_slab", Age.DEEP.weather("minecraft:stone_slab", 0.0, 0.9));
    }

    @Test
    void otherNamespacesAreLeftAlone() {
        assertEquals("othermod:stone_bricks", Age.DEEP.weather("othermod:stone_bricks", 0.0, 0.0));
    }

    @Test
    void namesParse() {
        assertEquals(Age.ANCIENT, Age.parse("ancient"));
        assertEquals("deep", Age.DEEP.id());
        assertThrows(IllegalArgumentException.class, () -> Age.parse("Ancient"));
    }
}
