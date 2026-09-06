package com.customdimensions.client.realtime;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinationChunksTest {

    private static final Identifier NEXUS = Identifier.of("adventure:the_crimson_nexus");
    private static final Identifier CRUCIBLE = Identifier.of("adventure:the_crucible");

    @BeforeEach
    void empty() {
        DestinationChunks.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        DestinationChunks.clear();
    }

    @Test
    void anEmptyStoreCountsZeroForEverything() {
        assertEquals(0, DestinationChunks.total());
        assertEquals(0, DestinationChunks.count(NEXUS));
        assertEquals(0, DestinationChunks.count(null));
        assertTrue(DestinationChunks.counts().isEmpty());
    }

    @Test
    void aChunkIsCountedOnceHoweverOftenItArrives() {
        assertTrue(DestinationChunks.accept(NEXUS, 46, 46));
        assertFalse(DestinationChunks.accept(NEXUS, 46, 46),
                "a resent chunk was counted as a new one, so the count cannot be trusted");

        assertEquals(1, DestinationChunks.count(NEXUS));
        assertEquals(1, DestinationChunks.total());
    }

    /**
     * Negative chunk coordinates are the normal case on the far side of a
     * scaled portal, and packing them wrongly makes two distinct chunks
     * collide — which reads as the feed stalling.
     */
    @Test
    void negativeChunkCoordinatesDoNotCollide() {
        assertTrue(DestinationChunks.accept(NEXUS, -750, 46));
        assertTrue(DestinationChunks.accept(NEXUS, 46, -750));
        assertTrue(DestinationChunks.accept(NEXUS, -750, -750));

        assertEquals(3, DestinationChunks.count(NEXUS));
    }

    @Test
    void aChunkKeyRoundTripsThroughItsTwoCoordinates() {
        long key = DestinationChunks.chunkKey(-750, 46);
        assertEquals(-750, DestinationChunks.chunkX(key));
        assertEquals(46, DestinationChunks.chunkZ(key));
    }

    @Test
    void destinationsAreCountedApart() {
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.accept(NEXUS, 46, 47);
        DestinationChunks.accept(CRUCIBLE, 46, 46);

        assertEquals(2, DestinationChunks.count(NEXUS));
        assertEquals(1, DestinationChunks.count(CRUCIBLE));
        assertEquals(3, DestinationChunks.total());
        assertEquals(2, DestinationChunks.counts().size());
    }

    @Test
    void droppingOneDestinationLeavesTheOthers() {
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.accept(CRUCIBLE, 46, 46);

        DestinationChunks.drop(NEXUS);

        assertEquals(0, DestinationChunks.count(NEXUS));
        assertEquals(1, DestinationChunks.count(CRUCIBLE));
    }

    @Test
    void aNullDestinationIsIgnoredRatherThanStored() {
        assertFalse(DestinationChunks.accept(null, 46, 46));
        assertEquals(0, DestinationChunks.total());
        assertEquals(0, DestinationChunks.revision(null));
        DestinationChunks.drop(null);
    }

    /**
     * The case the count misses: a resent chunk replaces one already
     * held, so the size holds and only the revision moves.
     */
    @Test
    void aResentChunkBumpsTheRevisionWhileTheCountHolds() {
        DestinationChunks.accept(NEXUS, 46, 46);
        int afterFirst = DestinationChunks.revision(NEXUS);

        assertFalse(DestinationChunks.accept(NEXUS, 46, 46), "the resend read as a new chunk");

        assertEquals(1, DestinationChunks.count(NEXUS), "the count moved, so it is not the case in hand");
        assertEquals(afterFirst + 1, DestinationChunks.revision(NEXUS),
                "a resent chunk left the revision alone, so no rebuild would ever run");
    }

    @Test
    void eachDestinationCarriesItsOwnRevision() {
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.accept(NEXUS, 46, 47);
        DestinationChunks.accept(CRUCIBLE, 46, 46);

        assertEquals(2, DestinationChunks.revision(NEXUS));
        assertEquals(1, DestinationChunks.revision(CRUCIBLE));
        assertEquals(0, DestinationChunks.revision(Identifier.of("adventure:unfed")));
    }

    /** A restarted revision could match a bookmark taken before the drop. */
    @Test
    void aDroppedDestinationDoesNotRestartItsRevision() {
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.drop(NEXUS);
        DestinationChunks.accept(NEXUS, 46, 46);

        assertEquals(1, DestinationChunks.count(NEXUS));
        assertEquals(2, DestinationChunks.revision(NEXUS), "the revision restarted behind a drop");
    }

    @Test
    void acceptsCountEveryArrivalIncludingAResend() {
        long before = DestinationChunks.accepts();
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationChunks.accept(null, 46, 46);

        assertEquals(2, DestinationChunks.accepts() - before);
    }
}
