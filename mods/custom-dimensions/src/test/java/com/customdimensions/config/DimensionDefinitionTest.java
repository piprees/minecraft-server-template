package com.customdimensions.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionDefinitionTest {

    @Test
    void defaultsAreCorrect() {
        DimensionDefinition def = new DimensionDefinition("test", "overworld", "minecraft:test");
        assertEquals("test", def.getName());
        assertEquals("overworld", def.getType());
        assertEquals("minecraft:test", def.getDimensionId());
        assertNull(def.getSeed());
        assertNull(def.getBiome());
        assertTrue(def.isHostileSpawningEnabled());
    }

    @Test
    void seedSetterWorks() {
        DimensionDefinition def = new DimensionDefinition("test", "overworld", "minecraft:test");
        assertNull(def.getSeed());

        def.setSeed(12345L);
        assertEquals(12345L, def.getSeed());

        def.setSeed(null);
        assertNull(def.getSeed());
    }

    @Test
    void biomeSetterWorks() {
        DimensionDefinition def = new DimensionDefinition("test", "single_biome", "minecraft:test");
        assertNull(def.getBiome());

        def.setBiome("minecraft:cherry_grove");
        assertEquals("minecraft:cherry_grove", def.getBiome());
    }

    @Test
    void hostileSpawningDefaultsToTrue() {
        DimensionDefinition def = new DimensionDefinition("test", "overworld", "minecraft:test");
        assertTrue(def.isHostileSpawningEnabled());
    }

    @Test
    void hostileSpawningCanBeDisabled() {
        DimensionDefinition def = new DimensionDefinition("test", "overworld", "minecraft:test");
        def.setHostileSpawning(false);
        assertFalse(def.isHostileSpawningEnabled());
    }

    @Test
    void hostileSpawningNullFallsBackToTrue() {
        DimensionDefinition def = new DimensionDefinition("test", "overworld", "minecraft:test");
        def.setHostileSpawning(false);
        assertFalse(def.isHostileSpawningEnabled());

        def.setHostileSpawning(null);
        assertTrue(def.isHostileSpawningEnabled());
    }

    @Test
    void dimensionIdIsLowercased() {
        DimensionDefinition def = new DimensionDefinition("Test", "overworld", "Minecraft:Test");
        assertEquals("minecraft:test", def.getDimensionId());
    }

    @Test
    void noArgConstructorCreatesEmptyDefinition() {
        DimensionDefinition def = new DimensionDefinition();
        assertNull(def.getName());
        assertNull(def.getType());
        assertNull(def.getDimensionId());
        assertNull(def.getSeed());
        assertNull(def.getBiome());
        assertTrue(def.isHostileSpawningEnabled());
    }
}
