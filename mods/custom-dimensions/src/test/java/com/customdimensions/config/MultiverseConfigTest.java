package com.customdimensions.config;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime API behaviour of MultiverseConfig over the per-dimension
 * directory format.
 */
class MultiverseConfigTest {

    private MultiverseConfig fromDirectory(Path dir) {
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadAllWithSettings(dir, dir.resolve("overlay")));
        return config;
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

    @Test
    void sharedIgniterReturnsAllCandidatesClickedFrameFirst(@TempDir Path dir) throws IOException {
        // Eight shipped dims share ender_eye; first-match-wins made every
        // portal but the alphabetically first unignitable (2026-07-23).
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_aaa_maw.json"), """
                {"portal":{"frameBlock":"minecraft:sculk_catalyst","igniterItem":"minecraft:ender_eye"}}
                """);
        Files.writeString(dims.resolve("the_starwell.json"), """
                {"portal":{"frameBlock":"minecraft:crying_obsidian","igniterItem":"minecraft:ender_eye"}}
                """);
        Files.writeString(dims.resolve("the_other.json"), """
                {"portal":{"frameBlock":"minecraft:mud_bricks","igniterItem":"minecraft:pink_petals"}}
                """);
        MultiverseConfig config = fromDirectory(dir);

        var all = config.getPortalsByIgniter("minecraft:ender_eye", null);
        assertEquals(2, all.size());

        // Clicking a crying_obsidian frame must put the starwell def first.
        var ordered = config.getPortalsByIgniter("minecraft:ender_eye", "minecraft:crying_obsidian");
        assertEquals("the_starwell", ordered.get(0).getId());
        assertEquals("the_aaa_maw", ordered.get(1).getId());

        assertTrue(config.getPortalsByIgniter("minecraft:torch", null).isEmpty());
        assertEquals("the_other", config.getPortalsByIgniter("minecraft:pink_petals", "x").get(0).getId());
    }

    // --- base worlds --------------------------------------------------------

    @Test
    void baseWorldsResolveByExactDimensionId(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_end.json"), "{\"seed\":5,\"borders\":{\"player\":4096}}");
        Files.writeString(dims.resolve("paradise_lost.json"), "{\"seed\":6}");
        MultiverseConfig config = fromDirectory(dir);

        DimensionConfig end = config.getBaseWorld("minecraft:the_end");
        assertNotNull(end);
        assertEquals(4096, end.getPlayerBorderRadius());
        assertNotNull(config.getBaseWorld("paradise_lost:paradise_lost"));
        assertNull(config.getBaseWorld("minecraft:overworld"));   // not configured
        assertNull(config.getBaseWorld(null));

        // Managing a base world must never widen the namespace set: the
        // lookup behind that gate is by PATH, and these namespaces carry
        // other mods' dimensions.
        assertFalse(config.isManagedNamespace("minecraft"));
        assertFalse(config.isManagedNamespace("paradise_lost"));
        // ...nor make it a custom dimension.
        assertNull(config.getDimension("the_end"));
        assertTrue(config.getDimensions().isEmpty());
    }

    @Test
    void aForeignDimensionWithACollidingPathNeverResolves(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_end.json"), "{\"seed\":5}");
        Files.writeString(dims.resolve("the_claymarsh.json"), "{\"type\":\"overworld\",\"seed\":1}");
        MultiverseConfig config = fromDirectory(dir);

        assertNull(config.getBaseWorld("someothermod:the_end"));
        assertNull(config.getBaseWorld("someothermod:the_claymarsh"));
        assertNull(config.getBaseWorld("minecraft:the_claymarsh"));
    }

    @Test
    void baseWorldsTakeTheirFamilysWorldTypeWithoutConfiguringOne(@TempDir Path dir)
            throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        for (String slug : DimensionConfig.BASE_WORLD_TYPES.keySet()) {
            Files.writeString(dims.resolve(slug + ".json"), "{\"seed\":1}");
        }
        MultiverseConfig config = fromDirectory(dir);
        for (var e : DimensionConfig.BASE_WORLD_TYPES.entrySet()) {
            DimensionConfig def = config.getWorld(e.getKey());
            assertNotNull(def, e.getKey());
            assertEquals(e.getValue(), def.getType(), e.getKey());
            assertFalse(com.customdimensions.dimension.StructureGroupRegistry
                            .groupsForType(def.getType()).isEmpty(),
                    e.getKey() + " type " + def.getType() + " enables no groups");
        }
        // An explicit type still wins — that is how a consumer moves a base
        // world onto another family's group set.
        Files.writeString(dims.resolve("the_end.json"), "{\"seed\":1,\"type\":\"nether\"}");
        assertEquals("nether", fromDirectory(dir).getWorld("the_end").getType());
    }

    @Test
    void baseWorldPortalsAreRegisteredLikeAnyOther(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_nether.json"), """
                {"seed":111,"portal":{"frameBlock":"minecraft:obsidian",
                 "igniterItem":"minecraft:flint_and_steel","scale":8.0,"color":"AA0000"}}
                """);
        MultiverseConfig config = fromDirectory(dir);

        PortalDefinition portal = config.getPortal("the_nether");
        assertNotNull(portal, "a base world's portal must reach the portal registry");
        assertEquals("minecraft:the_nether", portal.getTargetDimension());
        assertEquals(8.0, portal.getScale());
        assertTrue(config.getPortalByIgniter("minecraft:flint_and_steel").isPresent());

        RegistryKey<World> netherKey = RegistryKey.of(RegistryKeys.WORLD,
                Identifier.of("minecraft:the_nether"));
        assertNotNull(config.getPortalFor(netherKey));
    }

    @Test
    void baseWorldSeedOverridesResolveByExactId(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_nether.json"), "{\"seed\":111}");
        Files.writeString(dims.resolve("the_end.json"), "{\"seed\":222}");
        MultiverseConfig config = fromDirectory(dir);
        assertEquals(111L, config.getWorldSeedOverride("minecraft:the_nether"));
        assertEquals(222L, config.getWorldSeedOverride("minecraft:the_end"));
        assertNull(config.getWorldSeedOverride("adventure:nowhere"));
    }

    // This is exactly what PortalHelper.restoreZones() calls to re-stamp a
    // Gson-restored PortalDefinition's transient immersive field — see
    // ImmersiveSettingsTest.immersiveIsLostAcrossGsonRoundTripAndMustBeReStampedOnRestore
    // for why the re-stamp exists at all.
    @Test
    void getImmersiveForResolvesLiveSettingsByTargetDimension(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_glasswood.json"), """
                {"portal":{"frameBlock":"minecraft:amethyst_block","igniterItem":"minecraft:amethyst_shard",
                 "immersive":{"previewDepth":4}}}
                """);
        Files.writeString(dims.resolve("the_other.json"), """
                {"portal":{"frameBlock":"minecraft:mud_bricks","igniterItem":"minecraft:pink_petals",
                 "immersive":false}}
                """);
        Files.writeString(dims.resolve("the_quiet.json"), """
                {"portal":{"frameBlock":"minecraft:tuff","igniterItem":"minecraft:clay_ball"}}
                """);
        MultiverseConfig config = fromDirectory(dir);

        RegistryKey<World> glasswoodKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure:the_glasswood"));
        RegistryKey<World> otherKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure:the_other"));
        RegistryKey<World> unrelatedKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:the_nether"));

        ImmersiveSettings imm = config.getImmersiveFor(glasswoodKey);
        assertNotNull(imm);
        assertEquals(4, imm.previewDepth());

        assertNull(config.getImmersiveFor(otherKey));      // explicit "immersive": false — the opt-out
        assertNull(config.getImmersiveFor(unrelatedKey));  // no portal targets this dimension at all
        assertNull(config.getImmersiveFor(null));

        // Saying nothing means immersive, at every default.
        RegistryKey<World> quietKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure:the_quiet"));
        ImmersiveSettings quiet = config.getImmersiveFor(quietKey);
        assertNotNull(quiet);
        assertEquals(8, quiet.previewDepth());
    }
}
