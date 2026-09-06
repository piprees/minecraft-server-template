package com.customdimensions.immersive;

import com.customdimensions.companion.DestinationFeed;
import com.customdimensions.companion.PortalViewPreference;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which columns a zone tickets, for whom, and where they sit.
 *
 * <p>The block slab needs its preview box; a client drawing the far side itself
 * needs the columns its own box reads, or the feed sends the ticketed handful
 * and stops. The core runs {@link DestinationFeed#coreDepth} columns FORWARD
 * from the arrival along the viewer's far side and {@link
 * DestinationFeed#CORE_RADIUS} back of it — forward for the box, back so the
 * arrival's own 3x3 has the filled neighbourhood a section build needs.
 *
 * <p>The shape itself lives in {@link DestinationFeed#inCore}, which the feed
 * reads too; {@code CoreAgreementTest} is what holds the two together.
 *
 * <p>Fixtures are the live rig: arrival column 1732,1296 — chunk 108,81 — a
 * six-column preview box beside it, and a viewer west of the plane, so the far
 * side is {@link Direction#EAST}.
 */
class ImmersiveHoldSetTest {

    private static final int ARRIVAL_CHUNK_X = 108;
    private static final int ARRIVAL_CHUNK_Z = 81;

    /** The core the default declared view sizes. */
    private static final int CORE_DEPTH =
            DestinationFeed.coreDepth(PortalViewPreference.DEFAULT_VIEW_DEPTH);

    /** Columns one far side's core holds: its depth and its full width. */
    private static final int CORE_COLUMNS =
            (DestinationFeed.CORE_RADIUS + CORE_DEPTH)
                    * (2 * DestinationFeed.CORE_RADIUS + 1);

    private static final List<ChunkPos> PREVIEW_BOX = List.of(
            new ChunkPos(107, 80), new ChunkPos(107, 81), new ChunkPos(107, 82),
            new ChunkPos(108, 80), new ChunkPos(108, 81), new ChunkPos(108, 82));

    private static List<ChunkPos> held(Direction... farSides) {
        return ImmersiveProjector.holdSet(PREVIEW_BOX, ARRIVAL_CHUNK_X, ARRIVAL_CHUNK_Z,
                Set.of(farSides), CORE_DEPTH);
    }

    @Test
    void aLocalDrawerHoldsTheColumnsItsOwnBoxReads() {
        Set<ChunkPos> holding = new HashSet<>(held(Direction.EAST));

        for (int forward = 0; forward < CORE_DEPTH; forward++) {
            for (int side = -DestinationFeed.CORE_RADIUS; side <= DestinationFeed.CORE_RADIUS;
                    side++) {
                ChunkPos want = new ChunkPos(ARRIVAL_CHUNK_X + forward, ARRIVAL_CHUNK_Z + side);
                assertTrue(holding.contains(want),
                        "column " + want + " carries no ticket, so the feed can never send it");
            }
        }
    }

    /**
     * The default view's core covers the client's 64-block
     * box wherever the aperture sits inside its own chunk. Stopping at the
     * feed's tangential radius would leave the far half of every view empty.
     */
    @Test
    void theCoreReachesTheLocalBoxesFullDepth() {
        Set<ChunkPos> holding = new HashSet<>(held(Direction.EAST));

        assertTrue(
                holding.contains(new ChunkPos(
                        ARRIVAL_CHUNK_X + CORE_DEPTH - 1, ARRIVAL_CHUNK_Z)),
                "the core stops short of the box's far end");
        assertFalse(
                holding.contains(new ChunkPos(
                        ARRIVAL_CHUNK_X + CORE_DEPTH, ARRIVAL_CHUNK_Z)),
                "the core reaches past the box, ticketing a column nothing reads");
    }

    /**
     * The core reaches back exactly far enough to fill the arrival's own
     * neighbourhood, and no further. The box never reads behind the plane, so
     * every column back of {@link DestinationFeed#CORE_RADIUS} is spent on
     * nothing.
     */
    @Test
    void theCoreReachesBackOnlyAsFarAsTheNeighbourhoodNeeds() {
        Set<ChunkPos> holding = new HashSet<>(held(Direction.EAST));

        assertTrue(holding.contains(new ChunkPos(
                        ARRIVAL_CHUNK_X - DestinationFeed.CORE_RADIUS, ARRIVAL_CHUNK_Z)),
                "the arrival's own 3x3 has no filled neighbourhood behind it");
        assertFalse(holding.contains(new ChunkPos(
                        ARRIVAL_CHUNK_X - DestinationFeed.CORE_RADIUS - 1, ARRIVAL_CHUNK_Z)),
                "the core reaches further behind the plane than a section build needs");
    }

    /**
     * The behaviour this replaces. Filtering the core to what was already
     * resident made the hold set a function of itself: the columns drained,
     * the filter found nothing left to hold, and the set collapsed to the
     * slab's preview box with no path back. A ticket is how the far side comes
     * to exist, so an absent column is exactly the one worth taking.
     */
    @Test
    void aColumnThatHasToBeGeneratedIsStillTicketed() {
        assertEquals(CORE_COLUMNS, held(Direction.EAST).size(),
                "the core no longer holds every column, resident or not");
    }

    /**
     * A player on the server-drawn slab needs the six columns the slab already
     * tickets and not one more.
     */
    @Test
    void aSlabViewerHoldsOnlyTheSlabsOwnBox() {
        assertEquals(PREVIEW_BOX, held(),
                "a viewer who draws nothing locally widened the ticket");
    }

    /**
     * Both mouths are real. Two viewers on opposite sides read two different
     * volumes, so the hold runs forward from the arrival in both directions
     * rather than picking one and leaving the other looking at void.
     */
    @Test
    void opposedViewersHoldBothSides() {
        Set<ChunkPos> holding = new HashSet<>(held(Direction.EAST, Direction.WEST));

        assertTrue(holding.contains(new ChunkPos(
                ARRIVAL_CHUNK_X + CORE_DEPTH - 1, ARRIVAL_CHUNK_Z)));
        assertTrue(holding.contains(new ChunkPos(
                ARRIVAL_CHUNK_X - CORE_DEPTH + 1, ARRIVAL_CHUNK_Z)));
    }

    /** A portal in the floor: the box runs down, so its footprint is the square. */
    @Test
    void aHorizontalPortalHoldsTheSquareAroundTheArrival() {
        Set<ChunkPos> holding = new HashSet<>(held(Direction.DOWN));

        int core = DestinationFeed.CORE_RADIUS;
        for (int ox = -core; ox <= core; ox++) {
            for (int oz = -core; oz <= core; oz++) {
                assertTrue(holding.contains(new ChunkPos(ARRIVAL_CHUNK_X + ox,
                        ARRIVAL_CHUNK_Z + oz)),
                        "a floor portal's own footprint column " + ox + "," + oz + " is unheld");
            }
        }
    }

    /**
     * Everything ticketed is in the one list {@code releaseChunks} iterates,
     * and no column is in it twice — a duplicate would be released once and
     * ticketed twice.
     */
    @Test
    void everythingTicketedIsInTheListThatGetsReleased() {
        List<ChunkPos> holding = held(Direction.EAST);

        assertEquals(holding.size(), new HashSet<>(holding).size(), "a column is ticketed twice");
        assertTrue(holding.containsAll(PREVIEW_BOX), "the slab's own box fell out of the hold");
    }

    /** The cost, asserted rather than assumed. */
    @Test
    void oneFarSideCostsItsOwnCoreAndNoMore() {
        assertEquals(35, CORE_COLUMNS);

        assertTrue(held(Direction.EAST).size() <= PREVIEW_BOX.size() + CORE_COLUMNS,
                "one far side ticketed more than its own core");
        assertTrue(held(Direction.EAST, Direction.WEST).size()
                        <= PREVIEW_BOX.size() + 2 * CORE_COLUMNS,
                "two opposed far sides ticketed more than the cores they share");
    }
}
