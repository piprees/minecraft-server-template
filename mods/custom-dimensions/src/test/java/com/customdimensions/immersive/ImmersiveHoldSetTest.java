package com.customdimensions.immersive;

import com.customdimensions.companion.DestinationFeed;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which columns a zone tickets, for whom, and what it refuses to ticket.
 *
 * <p>The block slab needs its preview box; a client drawing the far side
 * itself needs the feed's filled core, or the feed sends the ticketed handful
 * and stops. Neither may cause a load: a ticket sets a chunk's level and the
 * manager generates whatever reaches it, which at a portal into fresh terrain
 * is [K6].
 *
 * <p>Fixtures are the live rig: arrival column 1732,1296 — chunk 108,81 — and
 * a six-column preview box beside it.
 */
class ImmersiveHoldSetTest {

    private static final int ARRIVAL_CHUNK_X = 108;
    private static final int ARRIVAL_CHUNK_Z = 81;

    private static final List<ChunkPos> PREVIEW_BOX = List.of(
            new ChunkPos(107, 80), new ChunkPos(107, 81), new ChunkPos(107, 82),
            new ChunkPos(108, 80), new ChunkPos(108, 81), new ChunkPos(108, 82));

    private static final ImmersiveProjector.ChunkResidency ALL_RESIDENT = (x, z) -> true;
    private static final ImmersiveProjector.ChunkResidency NONE_RESIDENT = (x, z) -> false;

    private static List<ChunkPos> held(boolean localDrawer,
            ImmersiveProjector.ChunkResidency residency) {
        return ImmersiveProjector.holdSet(PREVIEW_BOX, ARRIVAL_CHUNK_X, ARRIVAL_CHUNK_Z,
                localDrawer, residency);
    }

    @Test
    void aLocalDrawerHoldsTheFeedsCoreAsWell() {
        Set<ChunkPos> holding = new HashSet<>(held(true, ALL_RESIDENT));

        int core = DestinationFeed.CORE_RADIUS;
        for (int ox = -core; ox <= core; ox++) {
            for (int oz = -core; oz <= core; oz++) {
                assertTrue(
                        holding.contains(new ChunkPos(ARRIVAL_CHUNK_X + ox, ARRIVAL_CHUNK_Z + oz)),
                        "the feed's core column " + ox + "," + oz + " carries no ticket, so the "
                                + "feed can never send it");
            }
        }
    }

    /**
     * A player on the server-drawn slab needs the six columns the slab already
     * tickets and not one more.
     */
    @Test
    void aSlabViewerHoldsOnlyTheSlabsOwnBox() {
        assertEquals(PREVIEW_BOX, held(false, ALL_RESIDENT),
                "a viewer who draws nothing locally widened the ticket");
    }

    /**
     * The condition that keeps this off [K6]: a ticket drives a chunk to its
     * level and the manager generates to reach it, so an absent column is
     * never asked for.
     */
    @Test
    void anAbsentColumnIsNeverTicketed() {
        assertEquals(PREVIEW_BOX, held(true, NONE_RESIDENT),
                "a ticket was taken on a column that would have to be generated");
    }

    /** One column resident, and only that one joins the box. */
    @Test
    void onlyTheResidentPartOfTheCoreIsHeld() {
        ChunkPos only = new ChunkPos(ARRIVAL_CHUNK_X + 1, ARRIVAL_CHUNK_Z + 1);

        List<ChunkPos> holding = held(true, (x, z) -> x == only.x && z == only.z);

        assertEquals(PREVIEW_BOX.size() + 1, holding.size());
        assertTrue(holding.contains(only));
    }

    /**
     * Everything ticketed is in the one list {@code releaseChunks} iterates,
     * and no column is in it twice — a duplicate would be released once and
     * ticketed twice.
     */
    @Test
    void everythingTicketedIsInTheListThatGetsReleased() {
        List<ChunkPos> holding = held(true, ALL_RESIDENT);

        assertEquals(holding.size(), new HashSet<>(holding).size(), "a column is ticketed twice");
        assertTrue(holding.containsAll(PREVIEW_BOX), "the slab's own box fell out of the hold");
    }

    /** The cost, asserted rather than assumed: the core cannot grow past 5x5. */
    @Test
    void theCoreIsBoundedAtTwentyFiveColumns() {
        int core = 2 * DestinationFeed.CORE_RADIUS + 1;
        assertEquals(25, core * core);

        int added = held(true, ALL_RESIDENT).size() - PREVIEW_BOX.size();

        assertTrue(added <= 25, "the core added " + added + " columns");
        assertFalse(held(true, ALL_RESIDENT).size() > PREVIEW_BOX.size() + 25);
    }
}
