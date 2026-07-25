package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PortalSite} owns the two behaviours that can strand a player:
 * where an arrival goes, and whether they can step out of it once they are
 * there. It shipped with no tests at all and produced a live trap on
 * 2026-07-25 — see {@code TEST-COVERAGE-AUDIT.md}.
 *
 * <p>Everything here drives the pure cores with injected probes, in the same
 * style as {@code ProjectionVolumeTest}. No Minecraft runtime, no world.
 */
class PortalSiteTest {

    // === probes ==========================================================

    /** Solid everywhere — the entombed case. */
    private static final Predicate<BlockPos> ALL_SOLID = p -> true;
    private static final Predicate<BlockPos> NONE_SOLID = p -> false;

    /** Open above {@code groundY}, solid at and below it. */
    private static Predicate<BlockPos> openAbove(int groundY) {
        return p -> p.getY() > groundY;
    }

    private static Set<BlockPos> setOf(BlockPos... positions) {
        return new HashSet<>(Set.of(positions));
    }

    // === standardInterior ================================================

    @Test
    void standardInteriorIsTwoWideThreeTallOnAxisX() {
        Set<BlockPos> interior = PortalSite.standardInterior(10, 64, -5, Direction.Axis.X);

        assertEquals(6, interior.size(), "2 wide x 3 tall");
        // Varies in X and Y, fixed in Z — the plane a zone-axis-X portal sits in.
        assertTrue(interior.stream().allMatch(p -> p.getZ() == -5));
        assertTrue(interior.contains(new BlockPos(10, 64, -5)));
        assertTrue(interior.contains(new BlockPos(11, 66, -5)));
        assertFalse(interior.contains(new BlockPos(12, 64, -5)), "never 3 wide");
    }

    @Test
    void standardInteriorIsTwoWideThreeTallOnAxisZ() {
        Set<BlockPos> interior = PortalSite.standardInterior(10, 64, -5, Direction.Axis.Z);

        assertEquals(6, interior.size());
        assertTrue(interior.stream().allMatch(p -> p.getX() == 10), "fixed in X");
        assertTrue(interior.contains(new BlockPos(10, 66, -4)));
    }

    @Test
    void standardInteriorIsAThreeByThreePadOnAxisY() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 70, 0, Direction.Axis.Y);

        assertEquals(9, interior.size(), "3x3 pad");
        assertTrue(interior.stream().allMatch(p -> p.getY() == 70), "flat");
    }

    @Test
    void standardInteriorIsDeterministic() {
        // Load-bearing: the immersive projector reproduces arrival geometry
        // independently and the two must agree.
        assertEquals(PortalSite.standardInterior(3, 9, 27, Direction.Axis.X),
                PortalSite.standardInterior(3, 9, 27, Direction.Axis.X));
    }

    // === findArrivalY ====================================================

    @Test
    void findsTheFirstOpenSiteWalkingDown() {
        // Ground at y=62: the lowest fitting floor row is y=63 (clear, and
        // sitting on the solid block at 62).
        int y = PortalSite.findArrivalY(100, 200, Direction.Axis.X, 120, 0,
                openAbove(62), p -> p.getY() <= 62);

        assertEquals(63, y);
    }

    @Test
    void entombedColumnYieldsNoSite() {
        // THE EMBER FIELDS CASE. Every candidate Y is solid rock, so there is
        // no open pocket anywhere in the scan range and the caller must carve.
        int y = PortalSite.findArrivalY(1888, -3624, Direction.Axis.X, 250, 200,
                NONE_SOLID, ALL_SOLID);

        assertEquals(PortalSite.NO_SITE, y,
                "a solid column must report NO_SITE so the caller carves, never a buried Y");
    }

    @Test
    void openAirWithNoFloorYieldsNoSite() {
        // Clear all the way down but nothing solid to stand on: an arrival
        // hanging over a drop is its own kind of trap.
        int y = PortalSite.findArrivalY(0, 0, Direction.Axis.X, 100, 50,
                ALL_SOLID, NONE_SOLID);

        assertEquals(PortalSite.NO_SITE, y);
    }

    @Test
    void walksDownwardsNotUpwards() {
        // Two valid sites; the scan must return the HIGHER one it meets first
        // walking down from the ceiling, not climb past terrain above it.
        Predicate<BlockPos> clear = p -> p.getY() > 90 || (p.getY() > 62 && p.getY() < 70);
        Predicate<BlockPos> opaque = p -> p.getY() == 90 || p.getY() == 62;

        int y = PortalSite.findArrivalY(0, 0, Direction.Axis.X, 120, 0, clear, opaque);

        assertEquals(91, y, "first fit walking down");
    }

    @Test
    void respectsTheLowestBound() {
        int y = PortalSite.findArrivalY(0, 0, Direction.Axis.X, 100, 80,
                openAbove(10), p -> p.getY() <= 10);

        assertEquals(PortalSite.NO_SITE, y, "the only fit is below `lowest`");
    }

    // === fits ============================================================

    @Test
    void fitsRequiresEveryInteriorCellClear() {
        // One obstructed cell in the 2x3 is enough to reject the whole site.
        BlockPos blocked = new BlockPos(11, 65, 0);
        assertFalse(PortalSite.fits(10, 64, 0, Direction.Axis.X,
                p -> !p.equals(blocked), p -> p.getY() < 64));
    }

    @Test
    void fitsRequiresSolidSupportUnderTheFloorRow() {
        assertTrue(PortalSite.fits(10, 64, 0, Direction.Axis.X,
                p -> p.getY() >= 64, p -> p.getY() == 63), "supported");
        assertFalse(PortalSite.fits(10, 64, 0, Direction.Axis.X,
                p -> p.getY() >= 64, NONE_SOLID), "unsupported");
    }

    // === egressCells =====================================================

    @Test
    void egressCoversBothFacesOfThePlane() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Set<BlockPos> cells = PortalSite.egressCells(interior, Direction.Axis.X, 1);

        assertEquals(12, cells.size(), "6 interior cells x 2 faces");
        assertTrue(cells.contains(new BlockPos(0, 64, 1)), "positive face");
        assertTrue(cells.contains(new BlockPos(0, 64, -1)), "negative face");
    }

    @Test
    void egressNeverIncludesAnInteriorCell() {
        // The frame ring lies IN the plane, so carving the normals can never
        // touch it — but a deeper carve must not eat the portal either.
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Set<BlockPos> cells = PortalSite.egressCells(interior, Direction.Axis.X, 3);

        for (BlockPos p : interior) {
            assertFalse(cells.contains(p), "egress must never clear the portal itself");
        }
    }

    @Test
    void egressUsesTheNormalOfTheZoneAxis() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.Z);
        Set<BlockPos> cells = PortalSite.egressCells(interior, Direction.Axis.Z, 1);

        // Axis Z plane => normal is X.
        assertTrue(cells.stream().allMatch(p -> p.getX() == 1 || p.getX() == -1));
    }

    @Test
    void egressOfAnEmptyInteriorIsEmpty() {
        assertTrue(PortalSite.egressCells(Set.of(), Direction.Axis.X, 1).isEmpty());
        assertTrue(PortalSite.egressCells(null, Direction.Axis.X, 1).isEmpty());
    }

    // === hasEgress — the invariant that was missing ======================

    @Test
    void entombedArrivalHasNoEgress() {
        // The live defect: a 2x3 arrival with solid rock pressed against both
        // faces. The player arrives and cannot move.
        Set<BlockPos> interior = PortalSite.standardInterior(1887, 248, -3624, Direction.Axis.X);

        assertFalse(PortalSite.hasEgress(interior, Direction.Axis.X, NONE_SOLID),
                "solid on both faces must report NO egress");
    }

    @Test
    void openArrivalHasEgress() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);

        assertTrue(PortalSite.hasEgress(interior, Direction.Axis.X, ALL_SOLID));
    }

    @Test
    void oneOpenFaceIsEnough() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        // Hillside pressed against the negative face only.
        Predicate<BlockPos> passable = p -> p.getZ() > 0;

        assertTrue(PortalSite.hasEgress(interior, Direction.Axis.X, passable),
                "you only need one way out");
    }

    @Test
    void aHeadHeightGapIsNotAWayOut() {
        // A single hole at head height over a solid floor cell is not egress —
        // a player needs a body-height gap. This is the case a naive
        // "any neighbour is air" check gets wrong.
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Predicate<BlockPos> passable = p -> p.getY() >= 65;

        assertFalse(PortalSite.hasEgress(interior, Direction.Axis.X, passable));
    }

    @Test
    void footAndHeadMustBothBeClearOnTheSameColumn() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        // Foot clear on the +Z face, head clear only on the -Z face: neither
        // side is actually walkable.
        Predicate<BlockPos> passable = p ->
                (p.getZ() == 1 && p.getY() == 64) || (p.getZ() == -1 && p.getY() == 65);

        assertFalse(PortalSite.hasEgress(interior, Direction.Axis.X, passable));
    }

    @Test
    void horizontalPortalNeedsClearanceAbove() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.Y);

        assertTrue(PortalSite.hasEgress(interior, Direction.Axis.Y, p -> p.getY() > 64));
        assertFalse(PortalSite.hasEgress(interior, Direction.Axis.Y, NONE_SOLID));
    }

    @Test
    void egressIsCheckedFromTheFloorRowNotTheTop() {
        // Regression guard: checking the TOP row would call an arrival with a
        // clear gap at head height but blocked feet "fine".
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Predicate<BlockPos> onlyTopClear = p -> p.getY() >= 66;

        assertFalse(PortalSite.hasEgress(interior, Direction.Axis.X, onlyTopClear));
    }

    @Test
    void carvedEgressCellsSatisfyHasEgress() {
        // The contract between the two halves: whatever egressCells clears
        // must be enough to make hasEgress true. If this ever fails, a carve
        // can "succeed" and still leave someone trapped.
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.X);
        Set<BlockPos> carved = PortalSite.egressCells(interior, Direction.Axis.X, PortalSite.EGRESS_DEPTH);

        assertTrue(PortalSite.hasEgress(interior, Direction.Axis.X, carved::contains),
                "carveEgress must produce a pocket hasEgress accepts");
    }
}
