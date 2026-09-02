package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A presentation zone is geometry and nothing else: the projector sees it,
 * ownership and traversal do not. Every consumer that decides who performs a
 * traversal reads getSourceZones, so a presentation zone being absent from
 * that list is what keeps vanilla in charge — the two End suppression mixins
 * and PortalHelper.isManagedPortal all consult it.
 */
class PresentationZoneTest {

    private static RegistryKey<World> world(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    /** Built the way the loader builds one, so the parse is under test too. */
    private static PortalDefinition def(String slug, String frameBlock,
                                        boolean vanillaManaged, boolean immersive) {
        String json = String.format(
                "{\"portal\":{\"frameBlock\":\"%s\",\"igniterItem\":\"minecraft:flint_and_steel\"%s%s}}",
                frameBlock,
                vanillaManaged ? ",\"vanillaManaged\":true" : "",
                // Absent means ON in this mod, so opting out has to be explicit.
                immersive ? ",\"immersive\":true" : ",\"immersive\":false");
        com.customdimensions.config.DimensionConfig config =
                new com.google.gson.Gson().fromJson(
                        json, com.customdimensions.config.DimensionConfig.class);
        config.setName(slug);
        return config.toPortalDefinition();
    }

    private static PortalHelper.PortalZone zone(PortalDefinition def, Set<BlockPos> interior) {
        return new PortalHelper.PortalZone(interior, def, Direction.Axis.X,
                world("minecraft:overworld"), def.getTargetKey());
    }

    @BeforeEach
    @AfterEach
    void clear() {
        PortalHelper.clearPresentationZones();
        PortalAdoption.resetAttempts();
    }

    // --- what adoption offers -------------------------------------------------

    @Test
    void aVanillaManagedDefinitionIsAPresentationCandidateAndNeverATraversalOne() {
        List<PortalDefinition> portals = List.of(
                def("the_nether", "minecraft:obsidian", true, true),
                def("the_crucible", "minecraft:copper_block", false, true));

        assertEquals(List.of(), PortalAdoption.candidates(portals, List.of("minecraft:obsidian"))
                .stream().map(PortalDefinition::getId).toList());
        assertEquals(List.of("the_nether"),
                PortalAdoption.presentationCandidates(portals, List.of("minecraft:obsidian"))
                        .stream().map(PortalDefinition::getId).toList());
    }

    @Test
    void withoutImmersiveThereIsNothingToPresent() {
        List<PortalDefinition> portals = List.of(
                def("the_nether", "minecraft:obsidian", true, false));

        assertEquals(List.of(),
                PortalAdoption.presentationCandidates(portals, List.of("minecraft:obsidian")));
    }

    @Test
    void anOrdinaryDefinitionIsNeverAPresentationCandidate() {
        List<PortalDefinition> portals = List.of(
                def("the_crucible", "minecraft:copper_block", false, true));

        assertEquals(List.of(),
                PortalAdoption.presentationCandidates(portals, List.of("minecraft:copper_block")));
    }

    // --- what the registry does with it ---------------------------------------

    @Test
    void theProjectorSeesItAndTheTraversalPathDoesNot() {
        RegistryKey<World> overworld = world("minecraft:overworld");
        PortalHelper.PortalZone presented = zone(
                def("the_nether", "minecraft:obsidian", true, true),
                Set.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10)));

        assertTrue(PortalHelper.registerPresentationZone(presented));

        // The projector's list carries it; the traversal, ownership and End
        // suppression list stays empty.
        assertEquals(List.of(presented), PortalHelper.getProjectionZones(overworld));
        assertEquals(List.of(presented), PortalHelper.getPresentationZones(overworld));
        assertEquals(List.of(), PortalHelper.getSourceZones(overworld));
        assertNotNull(presented.definition.getImmersive());
    }

    @Test
    void registeringTheSameGeometryTwiceAddsOneZone() {
        Set<BlockPos> interior = Set.of(new BlockPos(10, 64, 10));
        PortalDefinition d = def("the_nether", "minecraft:obsidian", true, true);

        assertTrue(PortalHelper.registerPresentationZone(zone(d, interior)));
        assertFalse(PortalHelper.registerPresentationZone(zone(d, interior)));
        assertEquals(1, PortalHelper.getPresentationZones(world("minecraft:overworld")).size());
    }

    @Test
    void droppingOneLeavesTheRegistryEmpty() {
        RegistryKey<World> overworld = world("minecraft:overworld");
        PortalHelper.PortalZone presented = zone(
                def("the_nether", "minecraft:obsidian", true, true),
                Set.of(new BlockPos(10, 64, 10)));
        PortalHelper.registerPresentationZone(presented);

        PortalHelper.removePresentationZone(presented);

        assertEquals(List.of(), PortalHelper.getPresentationZones(overworld));
        assertEquals(List.of(), PortalHelper.getProjectionZones(overworld));
    }

    /**
     * The rollback guarantee. savePortalLinks serialises PORTAL_ZONES and
     * PENDING_ZONES and nothing else, so a presentation zone reaching neither
     * is what stops a jar predating this change reading one back as an
     * ordinary source zone and claiming a vanilla portal's traversal.
     */
    @Test
    void nothingAPresentationZoneTouchesIsPersisted() {
        RegistryKey<World> overworld = world("minecraft:overworld");
        PortalHelper.registerPresentationZone(zone(
                def("the_nether", "minecraft:obsidian", true, true),
                Set.of(new BlockPos(10, 64, 10))));

        assertEquals(List.of(), PortalHelper.getSourceZones(overworld));
    }

    @Test
    void aCellInsideOneIsStillNotAManagedPortal() {
        // isManagedPortal reads source zones through isInSourceZone, so the
        // preview never turns a vanilla portal into one the mod answers for.
        RegistryKey<World> overworld = world("minecraft:overworld");
        BlockPos cell = new BlockPos(10, 64, 10);
        PortalHelper.registerPresentationZone(zone(
                def("the_nether", "minecraft:obsidian", true, true),
                Set.of(cell)));

        assertFalse(PortalHelper.getSourceZones(overworld).stream()
                .anyMatch(z -> z.interior.contains(cell)));
    }
}
