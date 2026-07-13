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
        String json = """
                {"dimensions":[{"name":"test_world","type":"overworld","dimensionId":"minecraft:test_world","seed":42,"biome":"minecraft:plains","hostileSpawning":false}]}
                """;
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
        String json = """
                {"portals":[{"id":"nether_gate","frameBlock":"minecraft:obsidian","igniterItem":"minecraft:flint_and_steel","targetDimension":"minecraft:the_nether","color":"AA0000","lightLevel":11,"scale":0.125,"cooldown":80}]}
                """;
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
        String json = """
                {"portals":[{"id":"sound_test","frameBlock":"minecraft:stone","igniterItem":"minecraft:stick","targetDimension":"minecraft:overworld","color":"FFFFFF","lightLevel":0}]}
                """;
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

        String json = """
                {"dimensions":[{"name":"dim1","type":"void","dimensionId":"minecraft:dim1"}],"portals":[{"id":"p1","frameBlock":"minecraft:gold_block","igniterItem":"minecraft:blaze_rod","targetDimension":"minecraft:dim1","color":"FFD700","lightLevel":8}]}
                """;
        try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
            writer.write(json);
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
    void getDimensionNamesReturnsAllNames() {
        String json = """
                {"dimensions":[{"name":"alpha","type":"overworld"},{"name":"beta","type":"nether"}]}
                """;
        MultiverseConfig config = GSON.fromJson(json, MultiverseConfig.class);

        var names = config.getDimensionNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
    }

    @Test
    void getPortalByIgniterFindsMatch() {
        String json = """
                {"portals":[{"id":"p1","frameBlock":"minecraft:obsidian","igniterItem":"minecraft:blaze_rod","targetDimension":"minecraft:the_nether","color":"AA0000","lightLevel":0}]}
                """;
        MultiverseConfig config = GSON.fromJson(json, MultiverseConfig.class);

        assertTrue(config.getPortalByIgniter("minecraft:blaze_rod").isPresent());
        assertFalse(config.getPortalByIgniter("minecraft:stick").isPresent());
    }

    @Test
    void nullSeedSurvivesRoundTrip() {
        String json = """
                {"dimensions":[{"name":"test","type":"overworld","dimensionId":"minecraft:test"}]}
                """;
        MultiverseConfig loaded = GSON.fromJson(json, MultiverseConfig.class);

        assertNull(loaded.getDimension("test").getSeed());
    }

    @Test
    void namespaceFieldDeserializes() {
        String json = """
                {"namespace":"custom_ns","dimensions":[]}
                """;
        MultiverseConfig config = GSON.fromJson(json, MultiverseConfig.class);

        assertNotNull(config);
    }
}
