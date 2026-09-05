package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How far a sightline reaches past an opening, and which openings the depth
 * slice can no longer cover.
 *
 * <p>The slice is anchored on the nearest point of the aperture block's near
 * face and is 0.9 blocks of view depth deep. Seen obliquely, an opening spans
 * far more depth than that, and the destination is then drawn in front of
 * source terrain visible through the far side.
 */
class ClientProjectionBandTest {

    /**
     * A three-block opening in a one-block frame is the case with an exact
     * answer: {@code t^3 + 2t = 3} gives {@code t = 1}, so the optimum is 45
     * degrees and the reach is {@code 2 * sin(45) = sqrt(2)}. Anything that
     * gets the cubic or the sine wrong misses this.
     */
    @Test
    void aThreeBlockOpeningReachesExactlyRootTwo() {
        assertEquals(Math.sqrt(2.0), ClientProjection.sightlineReach(3.0, 1.0), 1e-12);
    }

    @Test
    void theRigsTwoBlockSpanStaysUnderTheLimit() {
        assertEquals(0.750416, ClientProjection.sightlineReach(2.0, 1.0), 1e-6);
        assertTrue(ClientProjection.sightlineReach(2.0, 1.0) < ClientProjection.BAND_LIMIT);
    }

    @Test
    void theLimitIsCrossedJustShortOfTwoAndAQuarter() {
        assertTrue(ClientProjection.sightlineReach(2.22, 1.0) < ClientProjection.BAND_LIMIT);
        assertTrue(ClientProjection.sightlineReach(2.25, 1.0) > ClientProjection.BAND_LIMIT);
    }

    /** Only the ratio matters: a 4-wide opening in a 2-thick frame is 2x a 2-in-1. */
    @Test
    void reachScalesWithTheFrame() {
        assertEquals(2.0 * ClientProjection.sightlineReach(2.0, 1.0),
                ClientProjection.sightlineReach(4.0, 2.0), 1e-12);
    }

    @Test
    void reachGrowsWithTheSpan() {
        double previous = -1.0;
        for (double span = 0.5; span <= 8.0; span += 0.5) {
            double reach = ClientProjection.sightlineReach(span, 1.0);
            assertTrue(reach > previous, "reach fell at span " + span);
            previous = reach;
        }
    }

    @Test
    void anEmptyOrFlatOpeningReachesNothing() {
        assertEquals(0.0, ClientProjection.sightlineReach(0.0, 1.0));
        assertEquals(0.0, ClientProjection.sightlineReach(-2.0, 1.0));
        assertEquals(0.0, ClientProjection.sightlineReach(2.0, 0.0));
    }

    /**
     * The rig at 1500, 101, 1500: two wide and three tall. The width is safe
     * and the height is not, so the opening as a whole is not.
     */
    @Test
    void theMeasuredRigOpensOnItsHeightNotItsWidth() {
        ClientProjection rig = opening(2, 3);
        assertEquals(Math.sqrt(2.0), rig.bandReach(), 1e-12);
        assertTrue(rig.bandOpens());
    }

    @Test
    void aTwoByTwoOpeningStaysClosed() {
        ClientProjection small = opening(2, 2);
        assertEquals(0.750416, small.bandReach(), 1e-6);
        assertFalse(small.bandOpens());
    }

    @Test
    void aSingleBlockOpeningStaysClosed() {
        assertFalse(opening(1, 1).bandOpens());
    }

    @Test
    void aWideOpeningOpensOnItsWidth() {
        ClientProjection wide = opening(5, 2);
        assertEquals(ClientProjection.sightlineReach(5.0, 1.0), wide.bandReach(), 1e-12);
        assertTrue(wide.bandOpens());
    }

    /** An opening {@code wide} blocks across and {@code tall} high, plane Z = 1500. */
    private static ClientProjection opening(int wide, int tall) {
        List<BlockPos> aperture = new ArrayList<>();
        for (int x = 1500; x < 1500 + wide; x++) {
            for (int y = 101; y < 101 + tall; y++) {
                aperture.add(new BlockPos(x, y, 1500));
            }
        }
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                Direction.Axis.X.ordinal(), Direction.SOUTH.ordinal(),
                new BlockPos(1492, 93, 1501), 18, 19, 24,
                new int[0], new byte[0],
                -1, -1, -1, -1, -1, -1.0f));
    }
}
