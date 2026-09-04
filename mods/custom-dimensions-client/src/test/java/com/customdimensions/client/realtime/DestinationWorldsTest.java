package com.customdimensions.client.realtime;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link DestinationWorlds} answers with nothing standing.
 *
 * <p>Standing a world up needs a bootstrapped client, so what is asserted here
 * is every path that must answer WITHOUT one: an absent destination, a null
 * argument, and the counts a caller reads to decide whether the feed arrived.
 * Each of those returning a wrong-but-plausible zero is what would make a
 * broken feed read as an empty one.
 */
class DestinationWorldsTest {

    private static final Identifier NEXUS = Identifier.of("adventure:the_crimson_nexus");

    @BeforeEach
    @AfterEach
    void nothingStanding() {
        DestinationWorlds.clear();
        DestinationChunks.clear();
    }

    @Test
    void anAbsentDestinationHasNoWorldAndNoChunks() {
        assertNull(DestinationWorlds.get(NEXUS));
        assertEquals(0, DestinationWorlds.count());
        assertEquals(0, DestinationWorlds.loadedChunks(NEXUS));
        assertTrue(DestinationWorlds.loadedCounts().isEmpty());
    }

    @Test
    void aNullDestinationIsAnswered_notThrown() {
        assertNull(DestinationWorlds.get(null));
        assertEquals(0, DestinationWorlds.loadedChunks(null));
        assertFalse(DestinationWorlds.load(null, null));
        DestinationWorlds.drop(null);
        assertEquals(0, DestinationWorlds.count());
    }

    /**
     * A chunk for a destination that was never stood up is refused rather than
     * dropped silently into nothing — the caller's {@code loaded=false} is how
     * a mis-centred or unregistered destination shows up at all.
     */
    @Test
    void aChunkForAnUnknownDestinationIsRefused() {
        assertFalse(DestinationWorlds.load(NEXUS, null));
    }

    @Test
    void droppingSomethingNeverHeldChangesNothing() {
        DestinationChunks.accept(NEXUS, 46, 46);
        DestinationWorlds.drop(NEXUS);
        // The chunk record is the world's to clear; no world, nothing cleared.
        assertEquals(1, DestinationChunks.count(NEXUS));
    }

    @Test
    void clearLeavesEveryCountAtZero() {
        DestinationWorlds.clear();
        assertEquals(0, DestinationWorlds.count());
        assertTrue(DestinationWorlds.loadedCounts().isEmpty());
    }
}
