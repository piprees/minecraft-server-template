package com.customdimensions.companion;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block change in a destination has to reach every client already holding
 * that chunk. Nothing but a disconnect or a world change dropped a
 * sent record, so the feed skipped the changed chunk forever and the view
 * through the frame stayed as it was when it first arrived.
 *
 * <p>Fixtures are the live rig: destination offset (-1732, -1296), arrival
 * column 1732,1296 — chunk 108,81.
 */
class DestinationInvalidationTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Identifier FAR_SIDE = Identifier.of("adventure", "the_amplified_reaches");
    private static final Identifier NEAR_SIDE = Identifier.of("minecraft", "overworld");

    private static final int CHANGED_X = 108;
    private static final int CHANGED_Z = 81;
    private static final int UNTOUCHED_X = 109;
    private static final int UNTOUCHED_Z = 81;

    @BeforeEach
    void emptyRegistry() {
        CompanionNetwork.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        CompanionNetwork.clear();
    }

    @Test
    void theChangedChunkIsDroppedForEveryPlayerHoldingIt() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, changed(), untouched());
        DestinationFeed.remember(OTHER, FAR_SIDE, changed(), untouched());

        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);

        for (UUID player : Set.of(PLAYER, OTHER)) {
            Set<Long> held = DestinationFeed.heldFor(player).get(FAR_SIDE);
            assertFalse(held.contains(changed()),
                    "the changed chunk survived for " + player + ", so it is never resent");
            assertTrue(held.contains(untouched()),
                    "an unchanged chunk was dropped for " + player + " and will be resent for nothing");
        }
    }

    @Test
    void anotherDimensionsChunkOfTheSameCoordinatesIsUntouched() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, changed());
        DestinationFeed.remember(PLAYER, NEAR_SIDE, changed());

        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);

        assertFalse(DestinationFeed.heldFor(PLAYER).get(FAR_SIDE).contains(changed()));
        assertTrue(DestinationFeed.heldFor(PLAYER).get(NEAR_SIDE).contains(changed()),
                "one dimension's block change dropped another dimension's chunk");
    }

    @Test
    void aChunkNobodyHoldsCostsNothingAndCountsNothing() {
        long before = DestinationFeed.invalidations();
        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);

        DestinationFeed.remember(PLAYER, FAR_SIDE, untouched());
        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);
        DestinationFeed.invalidate(null, CHANGED_X, CHANGED_Z);

        assertEquals(before, DestinationFeed.invalidations(),
                "a chunk nobody holds was counted as dropped");
    }

    @Test
    void repeatedChangesToOneChunkCollapseToOneDrop() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, changed());
        long before = DestinationFeed.invalidations();

        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);
        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);
        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);

        assertEquals(1, DestinationFeed.invalidations() - before,
                "every block in a chunk counted as its own resend");
    }

    @Test
    void aDroppedChunkIsOfferedAgainOnTheNextPass() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, changed());
        Set<Long> sent = DestinationFeed.heldFor(PLAYER).get(FAR_SIDE);
        assertFalse(offered(sent).contains(changed()), "a sent chunk was offered before any change");

        DestinationFeed.invalidate(FAR_SIDE, CHANGED_X, CHANGED_Z);

        assertTrue(offered(DestinationFeed.heldFor(PLAYER).get(FAR_SIDE)).contains(changed()),
                "the changed chunk was dropped but never re-offered to the feed");
    }

    /** The whole core, so the arrival chunk is a candidate whatever the wedge says. */
    private static Set<Long> offered(Set<Long> sent) {
        return Set.copyOf(DestinationFeed.nextChunks(CHANGED_X, CHANGED_Z, 16,
                0.0, 0.0, 0.0, 0.0, 0.0, -1732, -1296, sent, 64,
                DestinationFeed.Normal.Y, false,
                DestinationFeed.coreDepth(PortalViewPreference.DEFAULT_VIEW_DEPTH)));
    }

    private static long changed() {
        return DestinationFeed.chunkKey(CHANGED_X, CHANGED_Z);
    }

    private static long untouched() {
        return DestinationFeed.chunkKey(UNTOUCHED_X, UNTOUCHED_Z);
    }
}
