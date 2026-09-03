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

import java.util.ArrayList;
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

    /** Nothing cold: the residency gate has its own tests below. */
    private static final PortalAdoption.ColumnResidency ALL_RESIDENT = (x, z) -> true;

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

    /** As {@link #def}, with an explicit approach range on the immersive block. */
    private static PortalDefinition def(String slug, String frameBlock,
                                        boolean vanillaManaged, int activationRange) {
        String json = String.format(
                "{\"portal\":{\"frameBlock\":\"%s\",\"igniterItem\":\"minecraft:flint_and_steel\"%s,"
                        + "\"immersive\":{\"activationRange\":%d}}}",
                frameBlock, vanillaManaged ? ",\"vanillaManaged\":true" : "", activationRange);
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

    // --- what approach offers -------------------------------------------------

    @Test
    void aPortalInRangeAndNotYetCoveredIsOffered() {
        BlockPos near = new BlockPos(10, 64, 10);
        BlockPos far = new BlockPos(500, 64, 500);
        BlockPos covered = new BlockPos(12, 64, 10);

        assertEquals(List.of(near), PortalAdoption.dueForPresentation(
                List.of(near, far, covered), new BlockPos(0, 64, 0), 64, Set.of(covered), ALL_RESIDENT));
    }

    /**
     * The same predicate the projector's own activation test uses
     * ({@code isWithinDistance}, strictly less than), so a portal the preview
     * would not draw is never offered for a zone either.
     */
    @Test
    void theRangeEdgeIsExclusive() {
        BlockPos player = new BlockPos(0, 64, 0);
        BlockPos justInside = new BlockPos(9, 64, 0);
        BlockPos exactlyAtRange = new BlockPos(10, 64, 0);

        assertEquals(List.of(justInside), PortalAdoption.dueForPresentation(
                List.of(justInside, exactlyAtRange), player, 10, Set.of(), ALL_RESIDENT));
    }

    @Test
    void heightCountsTowardsTheRange() {
        BlockPos player = new BlockPos(0, 64, 0);
        BlockPos overhead = new BlockPos(0, 84, 0);

        assertEquals(List.of(), PortalAdoption.dueForPresentation(
                List.of(overhead), player, 10, Set.of(), ALL_RESIDENT));
        assertEquals(List.of(overhead), PortalAdoption.dueForPresentation(
                List.of(overhead), player, 21, Set.of(), ALL_RESIDENT));
    }

    @Test
    void everyUncoveredPortalInRangeIsOfferedInTheOrderItWasFound() {
        BlockPos first = new BlockPos(5, 64, 0);
        BlockPos second = new BlockPos(-3, 64, 4);
        BlockPos third = new BlockPos(0, 70, 0);

        assertEquals(List.of(first, second, third), PortalAdoption.dueForPresentation(
                List.of(first, second, third), new BlockPos(0, 64, 0), 32, Set.of(), ALL_RESIDENT));
    }

    @Test
    void nothingIsOfferedWhenEveryPortalIsAlreadyCovered() {
        BlockPos one = new BlockPos(5, 64, 0);
        BlockPos two = new BlockPos(6, 64, 0);

        assertEquals(List.of(), PortalAdoption.dueForPresentation(
                List.of(one, two), new BlockPos(0, 64, 0), 32, Set.of(one, two), ALL_RESIDENT));
    }

    // --- what approach refuses to touch ---------------------------------------

    /**
     * Adoption turns a hit into a flood fill and a frame-ring walk, both
     * through World.getBlockState — which resolves via getChunk(create = true)
     * and generates terrain on the calling thread. On contact the player IS
     * the chunk ticket; on approach nobody is near the portal, so one cold
     * neighbour parks the world tick ([K1]/[K6]).
     */
    @Test
    void aPortalWhoseFootprintIsNotResidentIsNeverOffered() {
        BlockPos hit = new BlockPos(10, 64, 10);

        assertEquals(List.of(), PortalAdoption.dueForPresentation(
                List.of(hit), new BlockPos(0, 64, 0), 64, Set.of(), (x, z) -> false));
    }

    @Test
    void oneColdChunkAnywhereOnTheFootprintIsEnoughToRefuse() {
        // The hit's own chunk is loaded — the POI index only reports resident
        // chunks — and the fill still reaches chunk -1, which is not.
        BlockPos hit = new BlockPos(10, 64, 10);
        PortalAdoption.ColumnResidency coldToTheNorthWest = (x, z) -> (x >> 4) >= 0 && (z >> 4) >= 0;

        assertTrue(coldToTheNorthWest.isResident(hit.getX(), hit.getZ()));
        assertEquals(List.of(), PortalAdoption.dueForPresentation(
                List.of(hit), new BlockPos(0, 64, 0), 64, Set.of(), coldToTheNorthWest));
    }

    @Test
    void aPortalWithEveryFootprintChunkResidentIsStillOffered() {
        BlockPos hit = new BlockPos(10, 64, 10);

        assertEquals(List.of(hit), PortalAdoption.dueForPresentation(
                List.of(hit), new BlockPos(0, 64, 0), 64, Set.of(), ALL_RESIDENT));
    }

    /**
     * Vanilla's widest frame is 23 blocks across including its ring, so a fill
     * from any one of its blocks reaches a chunk either side whatever the
     * portal's axis. The gate asks about all of them, not just the hit's.
     */
    @Test
    void theFootprintIsEveryChunkAFillCouldReach() {
        List<String> asked = new ArrayList<>();
        PortalAdoption.footprintResident(new BlockPos(8, 64, 8), (x, z) -> {
            asked.add((x >> 4) + "," + (z >> 4));
            return true;
        });

        assertEquals(List.of("-1,-1", "-1,0", "-1,1", "0,-1", "0,0", "0,1", "1,-1", "1,0", "1,1"),
                asked.stream().sorted().toList());
    }

    @Test
    void theFirstColdColumnStopsTheProbe() {
        // A refusal costs one lookup, not the whole square.
        List<String> asked = new ArrayList<>();
        assertFalse(PortalAdoption.footprintResident(new BlockPos(8, 64, 8), (x, z) -> {
            asked.add((x >> 4) + "," + (z >> 4));
            return false;
        }));
        assertEquals(1, asked.size());
    }

    /**
     * The approach pass has no zone to read a range off — that is the gap it
     * exists to close — so the range comes from the definitions that could
     * produce one: vanilla-managed AND immersive, the widest of them.
     */
    @Test
    void theApproachRangeComesFromTheVanillaManagedImmersiveDefinitions() {
        assertEquals(24, PortalAdoption.presentationRange(List.of(
                def("the_nether", "minecraft:obsidian", true, true))));
    }

    @Test
    void aDefinitionThatCanNeverBePresentedContributesNoRange() {
        assertEquals(0, PortalAdoption.presentationRange(List.of(
                // Vanilla-managed but not immersive: nothing to draw.
                def("the_nether", "minecraft:obsidian", true, false),
                // Immersive but mod-managed: adopted on ignition, not on approach.
                def("the_crucible", "minecraft:copper_block", false, true))));
    }

    @Test
    void theWidestPresentableRangeWins() {
        assertEquals(48, PortalAdoption.presentationRange(List.of(
                def("the_nether", "minecraft:obsidian", true, 16),
                def("the_end", "minecraft:bedrock", true, 48))));
    }

    @Test
    void aModManagedDefinitionNeverWidensTheApproachRange() {
        assertEquals(16, PortalAdoption.presentationRange(List.of(
                def("the_nether", "minecraft:obsidian", true, 16),
                def("the_crucible", "minecraft:copper_block", false, 64))));
    }

    @Test
    void noVanillaManagedPortalsMeansNoApproachPassAtAll() {
        assertEquals(0, PortalAdoption.presentationRange(List.of()));
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
