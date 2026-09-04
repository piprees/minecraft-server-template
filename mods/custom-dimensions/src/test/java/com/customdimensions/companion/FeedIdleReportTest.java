package com.customdimensions.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed that writes nothing has to say so.
 *
 * <p>A destination with a live portal frame sending zero chunks is either
 * fully fed or starved, and both used to produce no log line at all — the
 * state and the healthy one were indistinguishable from a snapshot.
 */
class FeedIdleReportTest {

    @Test
    void aPumpThatWroteNothingIsReportedTheFirstTime() {
        assertTrue(DestinationFeed.reportsIdle(DestinationFeed.nextIdle(0, 0)),
                "a feed going quiet said nothing at all");
    }

    @Test
    void theSecondSilentPumpDoesNotRepeatIt() {
        assertFalse(DestinationFeed.reportsIdle(2), "every pump would log at the projection cadence");
    }

    /** A snapshot taken long after the feed went quiet still finds it. */
    @Test
    void aBoundedRepeatKeepsItInALaterSnapshot() {
        assertTrue(DestinationFeed.reportsIdle(DestinationFeed.IDLE_REPEAT_PUMPS));
        assertTrue(DestinationFeed.reportsIdle(DestinationFeed.IDLE_REPEAT_PUMPS * 3));
    }

    @Test
    void aPumpThatWroteSomethingIsNotIdle() {
        assertEquals(0, DestinationFeed.nextIdle(40, 4));
        assertFalse(DestinationFeed.reportsIdle(0), "a writing pump was reported as gone quiet");
    }

    @Test
    void theCountResumesFromOneAfterAWrite() {
        int afterWrite = DestinationFeed.nextIdle(40, 4);

        assertEquals(1, DestinationFeed.nextIdle(afterWrite, 0));
        assertTrue(DestinationFeed.reportsIdle(DestinationFeed.nextIdle(afterWrite, 0)),
                "a feed that went quiet a second time said nothing");
    }
}
