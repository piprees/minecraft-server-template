package com.customdimensions.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MultiverseConfigTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void gsonRoundTripPreservesDimensions() {
        MultiverseConfig config = new MultiverseConfig();
        DimensionDefinition dim = new DimensionDefinition("test_world", "overworld", "minecraft:test_world");
        dim.setSeed(42L);
        dim.setBiome("minecraft:plains");
        dim.setHostileSpawning(false);
        config.addDimension(dim);

        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertEquals(1, loaded.getDimensions().size());
        DimensionDefinition loadedDim = loaded.getDimension("test_world");
        assertNotNull(loadedDim);
        assertEquals("test_world", loadedDim.getName());
        assertEquals("overworld", loadedDim.getType());
        assertEquals(42L, loadedDim.getSeed());
        assertEquals("minecraft:plains", loadedDim.getBiome());
        assertFalse(loadedDim.isHostileSpawningEnabled());
    }

    @Test
    void gsonRoundTripPreservesPortals() {
        MultiverseConfig config = new MultiverseConfig();
        PortalDefinition portal = new PortalDefinition("nether_gate", "minecraft:obsidian", "minecraft:flint_and_steel", "minecraft:the_nether", "AA0000", 11);
        portal.setScale(0.125);
        portal.setCooldown(80);
        config.addPortal(portal);

        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertEquals(1, loaded.getPortals().size());
        PortalDefinition loadedPortal = loaded.getPortal("nether_gate");
        assertNotNull(loadedPortal);
        assertEquals("nether_gate", loadedPortal.getId());
        assertEquals("minecraft:obsidian", loadedPortal.getFrameBlock());
        assertEquals("minecraft:flint_and_steel", loadedPortal.getIgniterItem());
        assertEquals("minecraft:the_nether", loadedPortal.getTargetDimension());
        assertEquals("AA0000", loadedPortal.getColor());
        assertEquals(11, loadedPortal.getLightLevel());
        assertEquals(0.125, loadedPortal.getScale());
        assertEquals(80, loadedPortal.getCooldown());
    }

    @Test
    void gsonRoundTripPreservesSoundFields() {
        MultiverseConfig config = new MultiverseConfig();
        PortalDefinition portal = new PortalDefinition("sound_test", "minecraft:stone", "minecraft:stick", "minecraft:overworld", "FFFFFF", 0);
        config.addPortal(portal);

        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        PortalDefinition loadedPortal = loaded.getPortal("sound_test");
        assertNotNull(loadedPortal);
        assertEquals("block.portal.trigger", loadedPortal.getIgniteSound());
        assertEquals("block.portal.travel", loadedPortal.getEnterSound());
        assertEquals("block.portal.travel", loadedPortal.getExitSound());
    }

    @Test
    void gsonRoundTripPreservesFrameBlocks() {
        MultiverseConfig config = new MultiverseConfig();
        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertEquals("minecraft:crying_obsidian", loaded.getFrameOverworld());
        assertEquals("minecraft:obsidian", loaded.getFrameNether());
        assertEquals("minecraft:iron_block", loaded.getFrameEnd());
    }

    @Test
    void gsonRoundTripPreservesIdleUnloadMinutes() {
        MultiverseConfig config = new MultiverseConfig();
        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertEquals(5, loaded.getIdleUnloadMinutes());
    }

    @Test
    void fileRoundTripWorks(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("test_config.json");

        MultiverseConfig config = new MultiverseConfig();
        config.addDimension(new DimensionDefinition("dim1", "void", "minecraft:dim1"));
        config.addPortal(new PortalDefinition("p1", "minecraft:gold_block", "minecraft:blaze_rod", "minecraft:dim1", "FFD700", 8));

        try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(config, writer);
        }

        MultiverseConfig loaded;
        try (BufferedReader reader = Files.newBufferedReader(configFile)) {
            loaded = GSON.fromJson(reader, MultiverseConfig.class);
        }

        assertNotNull(loaded.getDimension("dim1"));
        assertEquals("void", loaded.getDimension("dim1").getType());
        assertNotNull(loaded.getPortal("p1"));
        assertEquals("minecraft:gold_block", loaded.getPortal("p1").getFrameBlock());
    }

    @Test
    void addDimensionReplacesByName() {
        MultiverseConfig config = new MultiverseConfig();
        config.addDimension(new DimensionDefinition("test", "overworld", "minecraft:test"));
        config.addDimension(new DimensionDefinition("test", "nether", "minecraft:test"));

        assertEquals(1, config.getDimensions().size());
        assertEquals("nether", config.getDimension("test").getType());
    }

    @Test
    void removeDimensionReturnsFalseForUnknown() {
        MultiverseConfig config = new MultiverseConfig();
        assertFalse(config.removeDimension("nonexistent"));
    }

    @Test
    void removePortalReturnsFalseForUnknown() {
        MultiverseConfig config = new MultiverseConfig();
        assertFalse(config.removePortal("nonexistent"));
    }

    @Test
    void getDimensionNamesReturnsAllNames() {
        MultiverseConfig config = new MultiverseConfig();
        config.addDimension(new DimensionDefinition("alpha", "overworld", "minecraft:alpha"));
        config.addDimension(new DimensionDefinition("beta", "nether", "minecraft:beta"));

        var names = config.getDimensionNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
    }

    @Test
    void getPortalByIgniterFindsMatch() {
        MultiverseConfig config = new MultiverseConfig();
        config.addPortal(new PortalDefinition("p1", "minecraft:obsidian", "minecraft:blaze_rod", "minecraft:the_nether", "AA0000", 0));

        assertTrue(config.getPortalByIgniter("minecraft:blaze_rod").isPresent());
        assertFalse(config.getPortalByIgniter("minecraft:stick").isPresent());
    }

    @Test
    void nullSeedSurvivesRoundTrip() {
        MultiverseConfig config = new MultiverseConfig();
        DimensionDefinition dim = new DimensionDefinition("test", "overworld", "minecraft:test");
        config.addDimension(dim);

        String json = GSON.toJson(config);
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertNull(loaded.getDimension("test").getSeed());
    }
}
