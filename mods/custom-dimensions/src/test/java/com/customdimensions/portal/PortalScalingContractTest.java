package com.customdimensions.portal;

import com.customdimensions.immersive.ProjectionVolume;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The coordinate-scaling CONTRACT, as a behaviour rather than a getter.
 *
 * <p>{@code portal.scale} is the Nether-style travel ratio, stated the way
 * people say it: <b>"8 nether : 1 over"</b>. One block walked in the
 * DESTINATION is worth {@code scale} blocks back home — so entering a
 * dimension DIVIDES and returning MULTIPLIES.
 *
 * <pre>
 *   scale 8, walk 10 blocks in the dim  ->  10 * 8 = 80 overworld blocks
 *   scale 8, portal at overworld 1888   ->  1888 / 8 = 236 in the dim
 * </pre>
 *
 * <p>That is the design promise: a compact dimension is a tunnel network for
 * crossing the overworld fast. Before this
 * class, {@code scale} was tested only as a parsed value
 * ({@code defaultScaleIs1}, {@code portalScaleFeedsScaleGetterForCustomDims})
 * and as a chunk-ticket offset. Nothing asserted the transform itself, and
 * nothing pinned the truncation rule that decides which column a portal maps
 * to — see {@code TEST-COVERAGE-AUDIT.md}.
 *
 * <p>{@code ProjectionVolume.scaledMapping} is the shared implementation:
 * its javadoc states it mirrors {@code ServerWorldMixin}'s
 * integer-accumulate-then-divide exactly, "so preview and arrival never
 * disagree". These tests are what makes that claim falsifiable.
 */
class PortalScalingContractTest {

    /** A 2-wide, 3-tall doorway in the XY plane (zone axis X). */
    private static Set<BlockPos> doorwayX(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        return out;
    }

    /** An arbitrary w-wide, 3-tall doorway (real portals are player-built). */
    private static Set<BlockPos> doorwayX(int x, int y, int z, int width) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        return out;
    }

    // === the design promise ==============================================

    @Test
    void distanceBetweenPortalsScalesByTheDimensionScale() {
        // THE promise a compact dimension makes: walk 100 blocks between two
        // portals in a scale-8 dimension and you have covered 800 at home.
        var near = ProjectionVolume.scaledMapping(doorwayX(100, 64, 0), 8.0);
        var far = ProjectionVolume.scaledMapping(doorwayX(200, 64, 0), 8.0);

        assertEquals(12, far.arrivalX() - near.arrivalX(),
                "100 source blocks apart collapses to ~12 in a scale-8 dim — "
                        + "which is exactly what makes crossing it 8x faster");
    }

    @Test
    void distanceScalesOnBothHorizontalAxesEqually() {
        var origin = ProjectionVolume.scaledMapping(doorwayX(0, 64, 0), 4.0);
        var alongX = ProjectionVolume.scaledMapping(doorwayX(50, 64, 0), 4.0);
        var alongZ = ProjectionVolume.scaledMapping(doorwayX(0, 64, 50), 4.0);

        int stepX = alongX.arrivalX() - origin.arrivalX();
        int stepZ = alongZ.arrivalZ() - origin.arrivalZ();
        assertEquals(stepX, stepZ, "X and Z must scale identically");
        assertEquals(13, stepX, "50 source blocks at scale 4 -> 12.5, rounded to 13");
    }

    @Test
    void aScaleBelowOneStretchesInstead() {
        // The inverse of compaction: a SPRAWLING dimension where a short hop
        // at home is a long walk on the far side. Legal, and unused by the
        // shipped set — every dimension there is 1, 4, 8, 12 or 16.
        var near = ProjectionVolume.scaledMapping(doorwayX(0, 64, 0), 0.125);
        var far = ProjectionVolume.scaledMapping(doorwayX(100, 64, 0), 0.125);

        assertEquals(800, far.arrivalX() - near.arrivalX());
    }

    @Test
    void scaleOneIsTheIdentityTransform() {
        var mapping = ProjectionVolume.scaledMapping(doorwayX(1234, 64, -567), 1.0);

        assertEquals(0, mapping.dx(), "no horizontal offset at scale 1");
        assertEquals(0, mapping.dz());
        assertEquals(1234, mapping.arrivalX());
        assertEquals(-567, mapping.arrivalZ());
    }

    // === the live regression case ========================================

    @Test
    void emberFieldsArrivalLandsInsideItsOwnWorldBorder() {
        // The live defect, as a test. The overworld source portal is a
        // 4-wide doorway at x=235..238, z=-453 into adventure:the_ember_fields
        // (scale 8.0, player border radius 1024).
        //
        // Multiplying produced (1888, -3624) — 3624 blocks out, far OUTSIDE
        // that border, where vanilla forbids breaking or placing ANY block.
        // The player could not mine the calcite around them, the frame, or
        // the portal itself. Dividing lands them at (30, -57).
        // The live 2026-07-25 defect, pinned. Overworld portal at
        // x=235..238, z=-453 into adventure:the_ember_fields (scale 8.0,
        // player border radius 1024).
        //
        // Multiplying produced (1888, -3624) — 3624 blocks out, far OUTSIDE
        // that border, where vanilla forbids breaking or placing ANY block.
        // The reporter could not mine the calcite around them, the frame, or
        // the portal itself. Dividing lands them at (30, -57).
        int borderRadius = 1024;
        var mapping = ProjectionVolume.scaledMapping(doorwayX(235, 64, -453, 4), 8.0);

        assertEquals(30, mapping.arrivalX());
        assertEquals(-57, mapping.arrivalZ());
        assertTrue(Math.max(Math.abs(mapping.arrivalX()), Math.abs(mapping.arrivalZ())) <= borderRadius,
                "an arrival outside the destination border is a stranded player");
    }

    // === the truncation trap =============================================

    @Test
    void centreTruncatesTowardsZeroNotDownwards() {
        // Documented in scaledMapping: the int accumulate-then-divide
        // truncates towards zero rather than flooring. A 2-wide doorway at
        // x has centre x+0.5, so the positive case lands on x...
        var positive = ProjectionVolume.scaledMapping(doorwayX(100, 64, 0), 1.0);
        assertEquals(100, positive.arrivalX());

        // ...and the negative case lands on x+1, NOT x. This asymmetry is
        // real behaviour that preview and arrival must share; PLAN.md calls
        // negative-coordinate rounding out as a trap that has already
        // produced one false regression report.
        var negative = ProjectionVolume.scaledMapping(doorwayX(-100, 64, 0), 1.0);
        assertEquals(-99, negative.arrivalX(),
                "truncation towards zero: -99.5 -> -99, not -100");
    }

    @Test
    void negativeCoordinatesStillScaleLinearly() {
        var near = ProjectionVolume.scaledMapping(doorwayX(-100, 64, -100), 8.0);
        var far = ProjectionVolume.scaledMapping(doorwayX(-200, 64, -100), 8.0);

        // 100 source blocks at scale 8 is 12.5 destination blocks; integer
        // truncation of the interior centre lands it on 12 or 13 depending on
        // sign. Assert the PROPERTY (linear, correct magnitude, correct sign)
        // rather than a magic number that would drift with the rounding rule.
        // 100 source blocks at scale 8 is 12.5; integer truncation of the
        // interior centre lands it on 12 or 13 depending on sign. Assert the
        // PROPERTY, not a number that drifts with the rounding rule.
        int step = far.arrivalX() - near.arrivalX();
        assertTrue(step < 0, "moving -X at the source must move -X at the arrival");
        assertTrue(Math.abs(Math.abs(step) - 100.0 / 8.0) <= 1.0,
                "expected ~12.5 blocks, got " + Math.abs(step));
    }

    @Test
    void theOffsetIsTheDifferenceBetweenScaledAndSourceColumn() {
        // dx/dz are what the projector adds to a source position to reach the
        // far side; they must agree with arrivalX/arrivalZ by construction or
        // the preview samples a different column from the one the player
        // lands in.
        Set<BlockPos> interior = doorwayX(300, 64, -700);
        var mapping = ProjectionVolume.scaledMapping(interior, 8.0);

        int centreX = 0;
        int centreZ = 0;
        for (BlockPos p : interior) {
            centreX += p.getX();
            centreZ += p.getZ();
        }
        centreX /= interior.size();
        centreZ /= interior.size();

        assertEquals(mapping.arrivalX() - centreX, mapping.dx());
        assertEquals(mapping.arrivalZ() - centreZ, mapping.dz());
    }

    // === round trip ======================================================

    @Test
    void scalingOutAndBackReturnsToTheSameNeighbourhood() {
        // A chained trip: overworld -> scale-8 dim -> back. The return leg is
        // the inverse scale, so the player should land within rounding
        // distance of where they started, not kilometres away.
        var out = ProjectionVolume.scaledMapping(doorwayX(236, 64, -453, 4), 8.0);
        var back = ProjectionVolume.scaledMapping(
                doorwayX(out.arrivalX(), 64, out.arrivalZ(), 1), 1.0 / 8.0);

        assertTrue(Math.abs(back.arrivalX() - 236) <= 8,
                "round trip drifted in X: " + back.arrivalX());
        assertTrue(Math.abs(back.arrivalZ() - (-453)) <= 8,
                "round trip drifted in Z: " + back.arrivalZ());
    }

    @Test
    void theArrivalInteriorIsBuiltAtTheCOLUMNTHEPLAYERLANDSIN() {
        // The bug this pins, live 2026-07-25: ServerWorldMixin built the
        // arrival at `targetCentre + dx` while teleporting the player to
        // `targetCentre`. dx is the PROJECTION offset (what you add to a
        // SOURCE position to reach its target counterpart), so adding it to an
        // already-scaled centre applies the shift twice.
        //
        // Real numbers: a source portal at (63, -619) into a scale-8
        // dimension teleported the player to (8, -77) and built the portal at
        // (-47, 465) — ~600 blocks away. The player arrived in raw terrain
        // with no portal anywhere, which reads as "the return portal was
        // never created".
        Set<BlockPos> sourceInterior = doorwayX(63, 116, -619, 1);
        var mapping = ProjectionVolume.scaledMapping(sourceInterior, 8.0);

        int landingX = mapping.arrivalX();
        int landingZ = mapping.arrivalZ();
        assertEquals(8, landingX);
        assertEquals(-77, landingZ);

        // The interior must CONTAIN the block the player lands in.
        Set<BlockPos> interior = PortalSite.standardInterior(
                landingX, 192, landingZ, net.minecraft.util.math.Direction.Axis.X);
        assertTrue(interior.contains(new BlockPos(landingX, 192, landingZ)),
                "the arrival interior must contain the landing column");

        // And the double-applied form must NOT — the guard on the actual bug.
        int dx = landingX - 63;
        int dz = landingZ - (-619);
        Set<BlockPos> doubleApplied = PortalSite.standardInterior(
                landingX + dx, 192, landingZ + dz, net.minecraft.util.math.Direction.Axis.X);
        assertFalse(doubleApplied.contains(new BlockPos(landingX, 192, landingZ)),
                "centre+offset builds the portal away from the player — this is the regression");
    }

    @Test
    void interiorMinYIsTheFloorRowNotTheCentre() {
        // The vertical remap anchors on the interior's LOWEST row — the row a
        // player's feet occupy. Anchoring on the centre would sink every
        // arrival by a block.
        var mapping = ProjectionVolume.scaledMapping(doorwayX(0, 70, 0), 1.0);

        assertEquals(70, mapping.interiorMinY());
    }

    @Test
    void anEmptyInteriorDoesNotDivideByZero() {
        assertDoesNotThrow(() -> ProjectionVolume.scaledMapping(Set.of(), 8.0));
    }
}
