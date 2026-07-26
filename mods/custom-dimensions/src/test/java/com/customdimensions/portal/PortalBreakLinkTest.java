package com.customdimensions.portal;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Symmetric breaking (Phase 9c): mining one end of a portal takes the other.
 *
 * <p>Every case here is about the two ends AGREEING. The linkage is an exact
 * match on the source column each arrival cell was stamped with, not a
 * geometric search back through the scale transform — so the tests that matter
 * most are the ones proving it matches nothing when it should not: anchor
 * arrivals shared by many sources, exit portals, and records too old to carry
 * a source column.
 */
class PortalBreakLinkTest {

    private static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> BONEYARD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_boneyard"));
    private static final RegistryKey<World> EMBER =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_ember_fields"));

    private static PortalHelper.PortalReturnTarget arrival(RegistryKey<World> sourceWorld,
            Integer sourceX, Integer sourceZ, String exitMode) {
        PortalHelper.PortalReturnTarget t = new PortalHelper.PortalReturnTarget(
                sourceWorld, 64, 0x8844FF, 40, null, exitMode);
        t.sourceX = sourceX;
        t.sourceZ = sourceZ;
        return t;
    }

    private static Map<BlockPos, PortalHelper.PortalReturnTarget> registry(
            Map<BlockPos, PortalHelper.PortalReturnTarget> seed) {
        return new HashMap<>(seed);
    }

    // === centreColumn — the shared definition =============================

    @Test
    void centreColumnMatchesTheTraversalPathsAverage() {
        // ServerWorldMixin computes the arrival column from this exact
        // expression and setSourceColumn stamps the result. If the two ever
        // disagreed, symmetric breaking would match nothing — which looks
        // exactly like the bug it fixes.
        Set<BlockPos> interior = new LinkedHashSet<>(Set.of(
                new BlockPos(10, 64, 20), new BlockPos(11, 64, 20),
                new BlockPos(10, 65, 20), new BlockPos(11, 65, 20),
                new BlockPos(10, 66, 20), new BlockPos(11, 66, 20)));

        assertArrayEquals(new int[]{10, 20}, PortalBreakLink.centreColumn(interior),
                "integer average, truncating — 63/6 = 10");
    }

    @Test
    void centreColumnIsNullForAnEmptyInterior() {
        assertNull(PortalBreakLink.centreColumn(Set.of()));
        assertNull(PortalBreakLink.centreColumn(null));
    }

    @Test
    void centreColumnTruncatesTowardZeroOnNegativeCoordinates() {
        // Portal coordinates are frequently negative on a real world and this
        // codebase has a documented history of sign mistakes (floor(-453.5) is
        // -454, not -453). Java's `/` truncates TOWARD ZERO, so the average of
        // -47 and -46 is -46, not -47 and not -47.5 rounded down.
        //
        // The value being "wrong" in a mathematical sense does not matter — it
        // only has to be the SAME wrong on both ends, which is exactly why
        // ServerWorldMixin and symmetric breaking now share this one function
        // instead of each averaging the interior themselves.
        Set<BlockPos> interior = PortalSite.standardInterior(-47, 192, -465, Direction.Axis.X);

        int[] centre = PortalBreakLink.centreColumn(interior);
        assertEquals(-46, centre[0], "-279 / 6 truncates toward zero");
        assertEquals(-465, centre[1], "fixed in Z for an axis-X portal");
    }

    // === arrivalCellsFor — source end broken ==============================

    @Test
    void everyCellOfTheLinkedArrivalIsFound() {
        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(5, 70, 9), arrival(OVERWORLD, 40, 72, null),
                new BlockPos(6, 70, 9), arrival(OVERWORLD, 40, 72, null),
                new BlockPos(5, 71, 9), arrival(OVERWORLD, 40, 72, null)));

        assertEquals(3, PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, 40, 72).size());
    }

    @Test
    void anotherPortalsArrivalIsNeverTouched() {
        // Two source portals in the same world, arrivals near each other in
        // the destination. Breaking one must leave the other completely alone.
        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(5, 70, 9), arrival(OVERWORLD, 40, 72, null),
                new BlockPos(9, 70, 9), arrival(OVERWORLD, 800, 16, null)));

        Set<BlockPos> mine = PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, 40, 72);
        assertEquals(Set.of(new BlockPos(5, 70, 9)), mine);
    }

    @Test
    void aSameColumnArrivalFromADifferentWORLDIsNotLinked() {
        // Chained dimensions can produce the same (x, z) from two different
        // source worlds. The world is part of the identity.
        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(5, 70, 9), arrival(EMBER, 40, 72, null)));

        assertTrue(PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, 40, 72).isEmpty());
    }

    @Test
    void recordsWithNoSourceColumnMatchNothing() {
        // Written before sourceX/sourceZ existed. Guessing which source built
        // them would take down a portal somebody else made.
        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(5, 70, 9), arrival(OVERWORLD, null, null, null)));

        assertTrue(PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, 40, 72).isEmpty());
    }

    @Test
    void anEmptyOrAbsentRegistryIsSafe() {
        assertTrue(PortalBreakLink.arrivalCellsFor(null, OVERWORLD, 0, 0).isEmpty());
        assertTrue(PortalBreakLink.arrivalCellsFor(Map.of(), OVERWORLD, 0, 0).isEmpty());
    }

    // === the exemptions — the load-bearing negatives ======================

    @Test
    void anAnchorArrivalSharedByManySourcesSurvivesOneOfThemBreaking() {
        // THE case 9c must not get wrong. An anchor dimension lands every
        // source portal at one arrival; taking it down because one player
        // mined their own frame would strand everyone else.
        //
        // Two defences, both tested: the anchor path never stamps a source
        // column (so there is nothing to match), and an anchor arrival carries
        // an exitMode (so it is refused outright).
        PortalHelper.PortalReturnTarget anchorArrival = arrival(OVERWORLD, null, null, "origin");
        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(0, 70, 0), anchorArrival));

        assertFalse(PortalBreakLink.breaksSymmetrically(anchorArrival));
        assertTrue(PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, 40, 72).isEmpty());
    }

    @Test
    void anAnchorArrivalIsRefusedEvenIfSomethingStampedAColumnOnIt() {
        // Belt to the braces above: if a future change ever did stamp a source
        // column on an anchor arrival, exitMode must still refuse it.
        PortalHelper.PortalReturnTarget stamped = arrival(OVERWORLD, 40, 72, "origin");

        assertFalse(PortalBreakLink.isLinkedTo(stamped, OVERWORLD, 40, 72),
                "exitMode is the second, independent guard on the shared-arrival case");
    }

    @Test
    void exitPortalsAndShrinesDoNotBreakSymmetrically() {
        // The mod's own guaranteed way home, not one end of a player-built
        // pair. They carry an exitMode for the same reason anchors do.
        assertFalse(PortalBreakLink.breaksSymmetrically(arrival(OVERWORLD, 1, 2, "bed")));
        assertFalse(PortalBreakLink.breaksSymmetrically(arrival(OVERWORLD, 1, 2, "worldSpawn")));
        assertTrue(PortalBreakLink.breaksSymmetrically(arrival(OVERWORLD, 1, 2, null)),
                "an ordinary player-built arrival is the only thing that does");
    }

    @Test
    void aNullTargetIsNotLinkedToAnything() {
        assertFalse(PortalBreakLink.breaksSymmetrically(null));
        assertFalse(PortalBreakLink.isLinkedTo(null, OVERWORLD, 0, 0));
    }

    // === zoneMatchesColumn — arrival end broken ===========================

    @Test
    void theSourceZoneThatBuiltThisArrivalIsIdentified() {
        Set<BlockPos> interior = PortalSite.standardInterior(40, 64, 72, Direction.Axis.X);

        assertTrue(PortalBreakLink.zoneMatchesColumn(interior, BONEYARD, BONEYARD, 40, 72));
    }

    @Test
    void aZoneTargetingADIFFERENTDimensionIsNotTheOtherEnd() {
        // Two portals built at the same spot on different days, into different
        // dimensions: same column, different destination. Only one is the pair.
        Set<BlockPos> interior = PortalSite.standardInterior(40, 64, 72, Direction.Axis.X);

        assertFalse(PortalBreakLink.zoneMatchesColumn(interior, EMBER, BONEYARD, 40, 72));
    }

    @Test
    void aZoneAtADifferentColumnIsNotTheOtherEnd() {
        Set<BlockPos> interior = PortalSite.standardInterior(41, 64, 72, Direction.Axis.X);

        assertFalse(PortalBreakLink.zoneMatchesColumn(interior, BONEYARD, BONEYARD, 40, 72));
    }

    @Test
    void anArrivalWithNoRecordedColumnMatchesNoZone() {
        Set<BlockPos> interior = PortalSite.standardInterior(40, 64, 72, Direction.Axis.X);

        assertFalse(PortalBreakLink.zoneMatchesColumn(interior, BONEYARD, BONEYARD, null, null));
        assertFalse(PortalBreakLink.zoneMatchesColumn(interior, BONEYARD, BONEYARD, 40, null));
    }

    @Test
    void theTwoDirectionsAgreeOnTheSamePair() {
        // Round trip: stamp an arrival from a zone's centre column, then prove
        // each end finds the other. This is the contract — if it ever fails,
        // one direction of symmetric breaking is silently a no-op.
        Set<BlockPos> zoneInterior = PortalSite.standardInterior(236, 64, -453, Direction.Axis.Z);
        int[] column = PortalBreakLink.centreColumn(zoneInterior);

        Map<BlockPos, PortalHelper.PortalReturnTarget> targets = registry(Map.of(
                new BlockPos(29, 70, -57), arrival(OVERWORLD, column[0], column[1], null)));

        Set<BlockPos> cells = PortalBreakLink.arrivalCellsFor(targets, OVERWORLD, column[0], column[1]);
        assertEquals(1, cells.size(), "source end finds the arrival");

        PortalHelper.PortalReturnTarget found = targets.get(cells.iterator().next());
        assertTrue(PortalBreakLink.zoneMatchesColumn(zoneInterior, BONEYARD, BONEYARD,
                found.sourceX, found.sourceZ), "arrival end finds the source zone");
    }
}
