package com.customdimensions.companion;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of the destination's chunks a client needs, and in what order.
 *
 * <p>A filled square around the arrival, then a wedge. The square is what the
 * client's renderer builds from; beyond it a 2-wide opening subtends a narrow
 * wedge, and feeding the whole disc would send an order of magnitude more
 * chunks than can ever be seen through the frame.
 *
 * <p>Every fixture is the live nexus rig: opening at x 1500..1502 on the
 * z=1500 plane, eye to the south at z=1505, destination offset -750 on both
 * horizontal axes, arrival column 750,750 — chunk 46,46.
 */
class DestinationFeedTest {

    private static final double PLANE = 1500.0;
    private static final double A0 = 1500.0;
    private static final double A1 = 1502.0;
    private static final double EYE_A = 1501.0;
    private static final double EYE_N = 1505.0;
    private static final int DX = -750;
    private static final int DZ = -750;
    private static final int ARRIVAL_CHUNK = 46;

    private static boolean sees(double eyeA, double eyeN, double colA, double colN) {
        return DestinationFeed.throughOpening(eyeA, eyeN, A0, A1, PLANE, colA, colN);
    }

    /**
     * The wedge's apex plane is the aperture block's destination-side face —
     * the same face the client clips against. A mid-plane reads alike for both
     * directions and admits columns the client's clip then cuts.
     */
    @Test
    void theSurfaceIsTheApertureBlocksDestinationSideFace() {
        assertEquals(1501.0, DestinationFeed.surface(1500.0, true), 1.0e-9);
        assertEquals(1500.0, DestinationFeed.surface(1500.0, false), 1.0e-9);
    }

    private static List<Long> pick(int radius, int budget, Set<Long> sent) {
        return DestinationFeed.nextChunks(ARRIVAL_CHUNK, ARRIVAL_CHUNK, radius,
                EYE_A, EYE_N, A0, A1, PLANE, DX, DZ, sent, budget, DestinationFeed.Normal.Z);
    }

    @Test
    void aColumnDeadAheadOfTheOpeningIsSeen() {
        assertTrue(sees(EYE_A, EYE_N, 1501.0, 1400.0));
    }

    @Test
    void aColumnBehindTheEyeIsNotSeen() {
        assertFalse(sees(EYE_A, EYE_N, 1501.0, 1600.0),
                "a column on the eye's own side of the plane was fed");
    }

    @Test
    void aColumnInThePlaneItselfIsNotSeen() {
        assertFalse(sees(EYE_A, EYE_N, 1501.0, PLANE));
    }

    /**
     * The wedge widens with distance, so the same lateral offset is outside it
     * near the plane and inside it far away. This is the property that makes a
     * wedge worth having over a disc.
     */
    @Test
    void theWedgeWidensWithDistance() {
        assertFalse(sees(EYE_A, EYE_N, 1480.0, 1495.0),
                "20 blocks off axis, 5 past the plane, cannot be seen through a 2-wide opening");
        assertTrue(sees(EYE_A, EYE_N, 1480.0, 1000.0),
                "the same offset 500 blocks out is well inside the wedge");
    }

    /**
     * From an eye well to one side, the wedge points ACROSS the opening, not
     * straight ahead. Eye at A 1520, 5 blocks back from the plane: the
     * through-line to a column 100 blocks past the plane crosses the opening
     * only for A around 1100..1142, so 1120 is seen and dead ahead is not.
     */
    @Test
    void anEyeOffToOneSideSeesAcrossTheOpeningNotStraightAhead() {
        assertTrue(sees(1520.0, 1505.0, 1120.0, 1400.0));
        assertFalse(sees(1520.0, 1505.0, 1520.0, 1400.0),
                "the wedge pointed straight ahead instead of across the opening");
        assertFalse(sees(1520.0, 1505.0, 1600.0, 1400.0),
                "the wedge was mirrored — it opened away from the opening");
    }

    @Test
    void anEyeInThePlaneSeesNothingRatherThanDividingByZero() {
        assertFalse(sees(EYE_A, PLANE, 1501.0, 1400.0));
    }

    // ---- selection ------------------------------------------------------

    /**
     * Nearest first. A client that gets the far edge of the wedge before the
     * chunk the frame is standing on shows a hole at the opening while the
     * horizon fills in.
     */
    @Test
    void chunksArriveNearestTheArrivalFirst() {
        List<Long> picked = pick(6, 6, Set.of());

        assertEquals(6, picked.size(), "the wedge fed fewer chunks than the budget allowed");
        double previous = -1.0;
        for (long key : picked) {
            double distance = Math.hypot(DestinationFeed.chunkX(key) - ARRIVAL_CHUNK,
                    DestinationFeed.chunkZ(key) - ARRIVAL_CHUNK);
            assertTrue(distance >= previous - 1.0e-9, "chunk order is not nearest-first");
            previous = distance;
        }
    }

    @Test
    void chunksAlreadySentAreNotSentAgain() {
        Set<Long> sent = new HashSet<>();
        List<Long> first = pick(6, 3, sent);
        sent.addAll(first);

        List<Long> second = pick(6, 3, sent);

        assertEquals(3, second.size());
        for (long key : second) {
            assertFalse(first.contains(key), "a chunk was fed twice");
        }
    }

    @Test
    void theBudgetIsAHardCeilingOnOnePass() {
        assertEquals(2, pick(8, 2, Set.of()).size());
        assertTrue(pick(8, 0, Set.of()).isEmpty());
    }

    /**
     * The saving is the reason this exists, so it is asserted rather than
     * assumed: the wedge through a 2-wide opening must be a small fraction of
     * the disc it sits in.
     */
    @Test
    void theWedgeIsFarSmallerThanTheDiscAroundIt() {
        int radius = 8;
        int wedge = pick(radius, Integer.MAX_VALUE, Set.of()).size();
        int disc = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    disc++;
                }
            }
        }
        assertTrue(wedge > 0, "the wedge fed nothing at all");
        assertTrue(wedge * 3 < disc,
                "the wedge (" + wedge + ") is not meaningfully smaller than the disc (" + disc + ")");
    }

    /**
     * A horizontal portal is looked THROUGH downwards, so its wedge is not a
     * horizontal shape at all. The disc is the honest answer there, and the
     * radius is what bounds it.
     */
    @Test
    void aHorizontalPortalFeedsTheWholeDisc() {
        int radius = 4;
        int fed = DestinationFeed.nextChunks(ARRIVAL_CHUNK, ARRIVAL_CHUNK, radius,
                EYE_A, EYE_N, A0, A1, PLANE, DX, DZ, Set.of(), Integer.MAX_VALUE,
                DestinationFeed.Normal.Y).size();
        int disc = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    disc++;
                }
            }
        }
        assertEquals(disc, fed);
    }

    /** The two upright cases are mirror images, so neither may be hardcoded. */
    @Test
    void anOpeningWhoseNormalRunsAlongXIsFedToo() {
        int alongZ = DestinationFeed.nextChunks(ARRIVAL_CHUNK, ARRIVAL_CHUNK, 6,
                EYE_A, EYE_N, A0, A1, PLANE, DX, DZ, Set.of(), Integer.MAX_VALUE,
                DestinationFeed.Normal.Z).size();
        int alongX = DestinationFeed.nextChunks(ARRIVAL_CHUNK, ARRIVAL_CHUNK, 6,
                EYE_A, EYE_N, A0, A1, PLANE, DX, DZ, Set.of(), Integer.MAX_VALUE,
                DestinationFeed.Normal.X).size();

        assertTrue(alongX > 0, "an X-normal opening fed nothing");
        assertEquals(alongZ, alongX,
                "the mirrored case does not match; one of the two axes is hardcoded");
    }

    /**
     * A renderer builds a chunk's geometry only once that chunk and all eight
     * of its neighbours have arrived. A cone of columns has no interior, so
     * without a filled core nothing is ever buildable however much is fed.
     */
    @Test
    void theFilledCoreGivesTheRendererAThreeByThreeItCanBuild() {
        Set<Long> fed = new HashSet<>(pick(6, Integer.MAX_VALUE, Set.of()));
        int buildable = 0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (hasEveryNeighbour(fed, ARRIVAL_CHUNK + ox, ARRIVAL_CHUNK + oz)) {
                    buildable++;
                }
            }
        }
        assertEquals(9, buildable,
                "the 3x3 around the arrival cannot be built; the feed is a bare cone again");
    }

    private static boolean hasEveryNeighbour(Set<Long> fed, int chunkX, int chunkZ) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (!fed.contains(DestinationFeed.chunkKey(chunkX + ox, chunkZ + oz))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void aChunkKeyRoundTripsThroughItsTwoCoordinates() {
        long key = DestinationFeed.chunkKey(-750, 46);
        assertEquals(-750, DestinationFeed.chunkX(key));
        assertEquals(46, DestinationFeed.chunkZ(key));
    }
}
