package com.customdimensions.companion;

import com.customdimensions.immersive.ImmersiveProjector;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticket and the feed must name the same core.
 *
 * <p>{@link ImmersiveProjector#holdSet} decides which columns carry a ticket
 * and {@link DestinationFeed#nextChunks} decides which columns bypass the
 * wedge. A column one of them leaves out is a column the other can never send:
 * the feed's nearest-first order puts an untickable column at the head of the
 * queue, it is never resident, it is never recorded as sent, and it occupies
 * the budget on every pass thereafter.
 *
 * <p>This asserts AGREEMENT, not a shape. Change the core and both sides move
 * together or this fails.
 *
 * <p>Fixtures are the live rig: arrival chunk 108,81, viewer west of the plane
 * so the far side is {@link Direction#EAST}.
 */
class CoreAgreementTest {

    private static final int ARRIVAL_X = 108;
    private static final int ARRIVAL_Z = 81;

    /**
     * An eye level with the plane sees nothing rather than dividing by zero
     * ({@link DestinationFeed#throughOpening}), so every non-core column is
     * refused and what {@code nextChunks} returns is exactly its own core.
     */
    private static final double PLANE = 0.0;

    private static Set<String> ticketedCore(Direction farSide) {
        return names(ImmersiveProjector.holdSet(List.of(), ARRIVAL_X, ARRIVAL_Z,
                Set.of(farSide)));
    }

    private static Set<String> fedCore(DestinationFeed.Normal normal, boolean towardsHigh) {
        List<Long> keys = DestinationFeed.nextChunks(ARRIVAL_X, ARRIVAL_Z, 16,
                0.0, PLANE, -1.0, 1.0, PLANE, 0, 0, new HashSet<>(), 1000, normal,
                towardsHigh);
        Set<String> out = new TreeSet<>();
        for (long key : keys) {
            out.add(DestinationFeed.chunkX(key) + "," + DestinationFeed.chunkZ(key));
        }
        return out;
    }

    private static Set<String> names(List<ChunkPos> chunks) {
        Set<String> out = new TreeSet<>();
        for (ChunkPos pos : chunks) {
            out.add(pos.x + "," + pos.z);
        }
        return out;
    }

    @Test
    void theTicketAndTheFeedNameTheSameCoreLookingEast() {
        assertEquals(ticketedCore(Direction.EAST), fedCore(DestinationFeed.Normal.X, true),
                "the ticket and the feed disagree about the core, so the feed's budget "
                        + "goes to columns nothing tickets and they can never be sent");
    }

    @Test
    void theTicketAndTheFeedNameTheSameCoreLookingWest() {
        assertEquals(ticketedCore(Direction.WEST), fedCore(DestinationFeed.Normal.X, false));
    }

    @Test
    void theTicketAndTheFeedNameTheSameCoreLookingSouth() {
        assertEquals(ticketedCore(Direction.SOUTH), fedCore(DestinationFeed.Normal.Z, true));
    }

    @Test
    void theTicketAndTheFeedNameTheSameCoreLookingNorth() {
        assertEquals(ticketedCore(Direction.NORTH), fedCore(DestinationFeed.Normal.Z, false));
    }

    /**
     * A floor portal's box runs down, so the core is the square around the
     * arrival. Asserted against {@link DestinationFeed#inCore} rather than
     * against {@code nextChunks}: a Y-normal opening deliberately feeds the
     * whole disc ({@code chunkThroughOpening} returns true for it), so the
     * feed has no isolable core to compare — the agreement it needs is with
     * the predicate, which is what both sides read.
     */
    @Test
    void theTicketNamesThePredicatesCoreLookingDown() {
        Set<String> predicate = new TreeSet<>();
        int span = Math.max(DestinationFeed.CORE_RADIUS, DestinationFeed.CORE_DEPTH);
        for (int ox = -span; ox <= span; ox++) {
            for (int oz = -span; oz <= span; oz++) {
                if (DestinationFeed.inCore(ox, oz, DestinationFeed.Normal.Y, false)) {
                    predicate.add((ARRIVAL_X + ox) + "," + (ARRIVAL_Z + oz));
                }
            }
        }

        assertEquals(predicate, ticketedCore(Direction.DOWN));
    }

    /**
     * The boundary that broke it. The core reaches back exactly
     * {@link DestinationFeed#CORE_RADIUS} so the arrival's own 3x3 has a
     * filled neighbourhood; one column further back is core for neither. A
     * column either side calls core alone is nearest in the queue, never
     * ticketed, never resident, and holds the budget for good.
     */
    @Test
    void theBackwardBoundaryIsTheSameForBoth() {
        int last = ARRIVAL_X - DestinationFeed.CORE_RADIUS;
        int past = last - 1;

        assertTrue(ticketedCore(Direction.EAST).contains(last + "," + ARRIVAL_Z));
        assertTrue(fedCore(DestinationFeed.Normal.X, true).contains(last + "," + ARRIVAL_Z));
        assertFalse(ticketedCore(Direction.EAST).contains(past + "," + ARRIVAL_Z));
        assertFalse(fedCore(DestinationFeed.Normal.X, true).contains(past + "," + ARRIVAL_Z),
                "the feed calls a column core that nothing tickets, so it heads the "
                        + "nearest-first queue and can never be sent");
    }
}
