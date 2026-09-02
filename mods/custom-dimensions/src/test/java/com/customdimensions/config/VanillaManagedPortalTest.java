package com.customdimensions.config;

import com.customdimensions.portal.PortalAdoption;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A "vanillaManaged" portal is documented in the dimension's list and claimed
 * by nothing: no ignition candidate, no source zone, no claim on vanilla's
 * destination. It still states its scale and its immersive settings — both
 * describe the dimension and the view through the frame, not who travels. A
 * sibling entry targeting the same dimension is a normal mod portal.
 */
class VanillaManagedPortalTest {

    private static final Path SHIPPED_DIMENSIONS =
            Path.of("../../config/custom-dimensions/dimensions");

    private static final String RESERVED_AND_SIBLING = """
            {"portal":[
              {"frameBlock":"minecraft:obsidian","igniterItem":"minecraft:flint_and_steel",
               "vanillaManaged":true,"scale":8.0,"immersive":true},
              {"frameBlock":"minecraft:crying_obsidian","igniterItem":"minecraft:fire_charge",
               "scale":4.0,"immersive":true,"aura":{"subsume":"everything"}}
            ]}
            """;

    private static RegistryKey<World> world(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    /** A MultiverseConfig over one written dimension file, off the singleton. */
    private static MultiverseConfig configOf(Path dir, String slug, String json) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve(slug + ".json"), json);
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadAllWithSettings(dir, dir.resolve("overlay")));
        return config;
    }

    @AfterEach
    void clearSingleton(@TempDir Path empty) throws IOException {
        Files.createDirectories(empty.resolve("dimensions"));
        MultiverseConfig.getInstance().applyLoadResult(
                DimensionConfigLoader.loadAllWithSettings(empty, empty.resolve("overlay")));
    }

    // --- parsing --------------------------------------------------------------

    @Test
    void theFlagRidesFromConfigIntoTheDefinition() {
        DimensionConfig config = new com.google.gson.Gson().fromJson(
                RESERVED_AND_SIBLING, DimensionConfig.class);
        config.setName("the_nether");
        List<PortalDefinition> defs = config.toPortalDefinitions();

        assertEquals(2, defs.size());
        assertTrue(defs.get(0).isVanillaManaged());
        assertFalse(defs.get(1).isVanillaManaged());
    }

    @Test
    void aVanillaManagedPortalStatesItsScaleAndItsProjection() {
        // scale is the DIMENSION's coordinate ratio and immersive is a
        // rendering effect in the frame — neither describes who performs the
        // traversal, so both survive the flag.
        DimensionConfig config = new com.google.gson.Gson().fromJson(
                RESERVED_AND_SIBLING, DimensionConfig.class);
        config.setName("the_nether");
        List<PortalDefinition> defs = config.toPortalDefinitions();

        assertEquals(8.0, defs.get(0).getScale());
        assertNotNull(defs.get(0).getImmersive());
        assertEquals(4.0, defs.get(1).getScale());
        assertNotNull(defs.get(1).getImmersive());
    }

    // --- the mod stands back --------------------------------------------------

    @Test
    void ignitionOffersNoVanillaManagedCandidate(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_nether", RESERVED_AND_SIBLING);

        assertTrue(config.getPortalsByIgniter("minecraft:flint_and_steel", "minecraft:obsidian").isEmpty());
        assertEquals(List.of("the_nether#2"),
                config.getPortalsByIgniter("minecraft:fire_charge", "minecraft:crying_obsidian")
                        .stream().map(PortalDefinition::getId).toList());
    }

    @Test
    void theFrameFallbackStandsBackWithIt(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_nether", RESERVED_AND_SIBLING);

        assertNull(config.getDefaultPortalForFrameBlock("minecraft:obsidian"));
        assertNotNull(config.getDefaultPortalForFrameBlock("minecraft:mossy_stone_bricks"));
    }

    @Test
    void adoptionSkipsAVanillaManagedDefinition(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_nether", RESERVED_AND_SIBLING);

        assertEquals(List.of(), PortalAdoption.candidates(
                config.getPortals(), List.of("minecraft:obsidian"))
                .stream().map(PortalDefinition::getId).toList());
        assertEquals(List.of("the_nether#2"), PortalAdoption.candidates(
                config.getPortals(), List.of("minecraft:crying_obsidian"))
                .stream().map(PortalDefinition::getId).toList());
    }

    @Test
    void vanillaPicksTheDestinationForAnUnzonedCell(@TempDir Path dir) throws IOException {
        // A slug nothing else registers a zone for, so the only thing under
        // test is the config decision.
        RegistryKey<World> probe = world("adventure:vanilla_probe");
        BlockPos pos = new BlockPos(9001, 64, 9001);

        loadSingleton(dir, "vanilla_probe", RESERVED_AND_SIBLING);
        assertTrue(MultiverseConfig.getInstance().hasVanillaManagedPortals());
        assertFalse(PortalHelper.isManagedPortal(probe, pos));
    }

    @Test
    void anOrdinaryConfigStillOwnsEveryPortalInItsDimension(@TempDir Path dir) throws IOException {
        RegistryKey<World> probe = world("adventure:vanilla_probe");
        BlockPos pos = new BlockPos(9001, 64, 9001);

        loadSingleton(dir, "vanilla_probe", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                 "igniterItem":"minecraft:flint_and_steel","scale":8.0}}
                """);
        assertFalse(MultiverseConfig.getInstance().hasVanillaManagedPortals());
        assertTrue(PortalHelper.isManagedPortal(probe, pos));
    }

    // --- the sibling answers for the dimension --------------------------------

    @Test
    void aSiblingEntryOwnsTheRouteIntoTheSameDimension(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_nether", RESERVED_AND_SIBLING);
        RegistryKey<World> nether = world("minecraft:the_nether");

        assertEquals("the_nether#2", config.getPortalFor(nether).getId());
        assertNotNull(config.getImmersiveFor(nether));
        assertEquals("everything", config.getAuraSubsumeFor(nether));
    }

    @Test
    void aLoneVanillaManagedEntryLeavesTheseLookupsEmpty(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                 "igniterItem":"minecraft:flint_and_steel","vanillaManaged":true}}
                """);
        RegistryKey<World> nether = world("minecraft:the_nether");

        assertNull(config.getPortalFor(nether));
        assertNull(config.getImmersiveFor(nether));
        assertNull(config.getAuraSubsumeFor(nether));
        assertEquals(List.of("the_nether"), config.getPortalIds());
    }

    // --- everything without the flag is untouched -----------------------------

    @Test
    void anExistingSinglePortalConfigIsUnaffected(@TempDir Path dir) throws IOException {
        MultiverseConfig config = configOf(dir, "the_crucible", """
                {"portal":{"frameBlock":"minecraft:copper_block",
                 "igniterItem":"minecraft:diamond","scale":4.0,"immersive":true}}
                """);
        RegistryKey<World> crucible = world("adventure:the_crucible");

        PortalDefinition def = config.getPortalFor(crucible);
        assertNotNull(def);
        assertFalse(def.isVanillaManaged());
        assertEquals(4.0, def.getScale());
        assertNotNull(def.getImmersive());
        assertEquals(List.of("the_crucible"),
                config.getPortalsByIgniter("minecraft:diamond", "minecraft:copper_block")
                        .stream().map(PortalDefinition::getId).toList());
    }

    // --- the shipped set ------------------------------------------------------

    @Test
    void theClassicRoutesShipVanillaManagedAndTheOverworldDoesNot() {
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadAllWithSettings(
                SHIPPED_DIMENSIONS.getParent(), SHIPPED_DIMENSIONS.getParent().resolve("overlay")));

        assertTrue(config.getPortal("the_nether").isVanillaManaged());
        assertTrue(config.getPortal("the_end").isVanillaManaged());
        assertFalse(config.getPortal("overworld").isVanillaManaged());
        assertNull(config.getDefaultPortalForFrameBlock("minecraft:obsidian"));
        // Vanilla moves the Nether at 1:8 whatever the file says, and both
        // classic routes ship a preview through the frame.
        assertEquals(8.0, config.getPortal("the_nether").getScale());
        assertNotNull(config.getPortal("the_nether").getImmersive());
        assertNotNull(config.getPortal("the_end").getImmersive());
    }

    /** Same file, applied to the singleton PortalHelper reads through. */
    private static void loadSingleton(Path dir, String slug, String json) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve(slug + ".json"), json);
        MultiverseConfig.getInstance().applyLoadResult(
                DimensionConfigLoader.loadAllWithSettings(dir, dir.resolve("overlay")));
    }
}
