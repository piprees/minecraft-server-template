package com.customdimensions.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime API behaviour of MultiverseConfig over both load paths: the v4
 * directory format and the deprecated monolithic multiverse_config.json.
 */
class MultiverseConfigTest {

    private MultiverseConfig fromLegacy(Path dir, String json) throws IOException {
        Path legacy = dir.resolve("multiverse_config.json");
        Files.writeString(legacy, json);
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadLegacyWithSettings(legacy));
        return config;
    }

    private MultiverseConfig fromDirectory(Path dir) {
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadAllWithSettings(dir, dir.resolve("overlay")));
        return config;
    }

    @Test
    void legacyDimensionsResolveThroughPublicApi(@TempDir Path dir) throws IOException {
        MultiverseConfig config = fromLegacy(dir, """
                {"namespace":"adventure",
                 "dimensions":[{"name":"test_world","type":"overworld","dimensionId":"adventure:test_world",
                                "seed":42,"biome":"minecraft:plains","hostileSpawning":false,
                                "noiseSettings":"adventure:wide"}],
                 "portals":[],"worlds":[]}
                """);
        assertEquals(1, config.getDimensions().size());
        DimensionConfig dim = config.getDimension("test_world");
        assertNotNull(dim);
        assertEquals("test_world", dim.getName());
        assertEquals("overworld", dim.getType());
        assertEquals(42L, dim.getSeed());
        assertEquals("minecraft:plains", dim.getBiome());
        assertFalse(dim.isHostileSpawningEnabled());
        assertEquals("adventure:wide", dim.getNoiseSettings());
        assertTrue(config.getDimensionNames().contains("test_world"));
    }

    @Test
    void legacyPortalsBecomePortalViews(@TempDir Path dir) throws IOException {
        MultiverseConfig config = fromLegacy(dir, """
                {"dimensions":[{"name":"nether_gate","type":"nether","dimensionId":"adventure:nether_gate"}],
                 "portals":[{"id":"nether_gate","frameBlock":"minecraft:obsidian",
                             "igniterItem":"minecraft:flint_and_steel","targetDimension":"adventure:nether_gate",
                             "color":"AA0000","lightLevel":11,"scale":0.125,"cooldown":80}],
                 "worlds":[]}
                """);
        assertEquals(1, config.getPortals().size());
        PortalDefinition portal = config.getPortal("nether_gate");
        assertNotNull(portal);
        assertEquals("minecraft:obsidian", portal.getFrameBlock());
        assertEquals(0.125, portal.getScale());
        assertEquals(80, portal.getCooldown());
        assertTrue(config.getPortalByIgniter("minecraft:flint_and_steel").isPresent());
        assertFalse(config.getPortalByIgniter("minecraft:stick").isPresent());
        // default sounds fill in
        assertEquals("block.portal.trigger", portal.getIgniteSound());
        assertEquals("block.portal.travel", portal.getEnterSound());
    }

    @Test
    void frameAndIdleDefaultsSurviveEmptyConfig(@TempDir Path dir) throws IOException {
        MultiverseConfig config = fromLegacy(dir, "{\"dimensions\":[],\"portals\":[],\"worlds\":[]}");
        assertEquals("minecraft:mossy_stone_bricks", config.getFrameOverworld());
        assertEquals("minecraft:obsidian", config.getFrameNether());
        assertEquals("minecraft:end_stone_bricks", config.getFrameEnd());
        assertEquals(5, config.getIdleUnloadMinutes());
        assertNotNull(config.getDefaultPortalForFrameBlock("minecraft:mossy_stone_bricks"));
        assertNull(config.getDefaultPortalForFrameBlock("minecraft:dirt"));
    }

    @Test
    void worldSeedOverridesResolveByDimensionId(@TempDir Path dir) throws IOException {
        MultiverseConfig config = fromLegacy(dir, """
                {"worldSeed":4955897124001752590,
                 "dimensions":[],"portals":[],
                 "worlds":[{"name":"overworld","dimensionId":"minecraft:overworld"},
                           {"name":"the_nether","dimensionId":"minecraft:the_nether","seed":111},
                           {"name":"the_end","dimensionId":"minecraft:the_end"}]}
                """);
        assertEquals(4955897124001752590L, config.getWorldSeedOverride("minecraft:overworld"));
        assertEquals(111L, config.getWorldSeedOverride("minecraft:the_nether"));
        assertNull(config.getWorldSeedOverride("minecraft:the_end"));
        assertNull(config.getWorldSeedOverride("adventure:nowhere"));
        assertNotNull(config.getWorld("overworld"));
        assertNull(config.getWorld("the_claymarsh"));
        // base worlds never appear as custom dimensions
        assertNull(config.getDimension("overworld"));
        assertTrue(config.getDimensions().isEmpty());
    }

    @Test
    void directoryFormatLoadsAndTracksNamespaces(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("dimensions"));
        Files.writeString(dir.resolve("settings.json"),
                "{\"namespace\":\"adventure\",\"idleUnloadMinutes\":9}");
        Files.writeString(dir.resolve("dimensions/the_claymarsh.json"),
                "{\"type\":\"overworld\",\"seed\":1,\"portal\":{\"frameBlock\":\"minecraft:clay\",\"igniterItem\":\"minecraft:stick\"}}");
        Files.writeString(dir.resolve("dimensions/overworld.json"),
                "{\"seed\":77,\"spawn\":[1,64,2]}");
        Files.createDirectories(dir.resolve("overlay/dimensions"));
        Files.writeString(dir.resolve("overlay/dimensions/consumer_dim.json"),
                "{\"type\":\"overworld\",\"seed\":5}");

        MultiverseConfig config = fromDirectory(dir);
        assertEquals(9, config.getIdleUnloadMinutes());
        assertEquals(2, config.getDimensions().size());
        assertEquals(77L, config.getWorldSeedOverride("minecraft:overworld"));
        assertArrayEquals(new int[]{1, 64, 2}, config.getWorld("overworld").getSpawn());
        assertEquals(1, config.getPortals().size());
        assertEquals("adventure:the_claymarsh", config.getPortal("the_claymarsh").getTargetDimension());
        assertTrue(config.isManagedNamespace("adventure"));
        assertFalse(config.isManagedNamespace("minecraft"));
    }

    @Test
    void envSeedSentinelFlowsThroughWorldSeedOverride(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("dimensions"));
        Files.writeString(dir.resolve("dimensions/overworld.json"), "{\"seed\":\"env\"}");
        MultiverseConfig config = fromDirectory(dir);
        // Resolution depends on the SEED env var; with it unset the override is null.
        String env = System.getenv("SEED");
        Long expected = null;
        if (env != null && !env.isBlank()) {
            try {
                expected = Long.parseLong(env.trim());
            } catch (NumberFormatException e) {
                expected = (long) env.trim().hashCode();
            }
        }
        assertEquals(expected, config.getWorldSeedOverride("minecraft:overworld"));
    }
}
