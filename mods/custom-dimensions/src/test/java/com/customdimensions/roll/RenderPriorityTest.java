package com.customdimensions.roll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The render queue's ordering rule: every thumbnail anywhere before any detail
 * map anywhere, including one already running.
 *
 * <p>{@code RenderQueue} itself is static state wired to a live server, so the
 * ordering is pinned here over the same two structures it uses — a priority
 * queue keyed on (kind, sequence) and the abandon predicate. The regression:
 * a detail map of an 8192-block world held the single consumer for minutes
 * while 142 thumbnails waited behind it.
 */
class RenderPriorityTest {

    private static final int LOWRES = 0;
    private static final int HIGHRES = 1;

    private record Job(int priority, long sequence, String name) {
    }

    private static PriorityBlockingQueue<Job> queue() {
        return new PriorityBlockingQueue<>(16,
                Comparator.comparingInt(Job::priority).thenComparingLong(Job::sequence));
    }

    @Test
    void everyThumbnailComesOutBeforeEveryDetailMap() {
        PriorityBlockingQueue<Job> q = queue();
        AtomicLong seq = new AtomicLong();
        // Queued the way reconcile() queues them: a dimension's thumbnails,
        // then its detail maps, dimension after dimension.
        for (String dim : new String[] {"a", "b"}) {
            q.add(new Job(LOWRES, seq.incrementAndGet(), dim + "-low"));
            q.add(new Job(HIGHRES, seq.incrementAndGet(), dim + "-high"));
        }
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            order.add(q.poll().name());
        }
        assertEquals(List.of("a-low", "b-low", "a-high", "b-high"), order);
    }

    @Test
    void aThumbnailQueuedLaterStillOvertakesDetailWorkAlreadyWaiting() {
        PriorityBlockingQueue<Job> q = queue();
        AtomicLong seq = new AtomicLong();
        q.add(new Job(HIGHRES, seq.incrementAndGet(), "old-high"));
        q.add(new Job(LOWRES, seq.incrementAndGet(), "new-low"));
        assertEquals("new-low", q.poll().name(), "a board that moves mid-roll goes first");
        assertEquals("old-high", q.poll().name());
    }

    @Test
    void anAbandonedDetailMapGoesBehindTheThumbnailsThatDisplacedIt() {
        PriorityBlockingQueue<Job> q = queue();
        AtomicLong seq = new AtomicLong();
        Job running = new Job(HIGHRES, seq.incrementAndGet(), "high");
        q.add(new Job(LOWRES, seq.incrementAndGet(), "low-1"));
        q.add(new Job(LOWRES, seq.incrementAndGet(), "low-2"));
        // Re-queued with a FRESH sequence, so it sits behind its own class too
        // rather than jumping back to where it was.
        q.add(new Job(HIGHRES, seq.incrementAndGet(), running.name()));
        q.add(new Job(HIGHRES, seq.incrementAndGet(), "high-2"));

        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            order.add(q.poll().name());
        }
        assertEquals(List.of("low-1", "low-2", "high", "high-2"), order);
    }

    /** The predicate RenderQueue hands a detail render. */
    private static boolean abandons(boolean thumbnail, AtomicInteger thumbnailsPending) {
        return !thumbnail && thumbnailsPending.get() > 0;
    }

    @Test
    void aThumbnailNeverAbandonsItself() {
        assertFalse(abandons(true, new AtomicInteger(5)));
    }

    @Test
    void aDetailMapYieldsWhileAnyThumbnailIsOwed() {
        assertTrue(abandons(false, new AtomicInteger(1)));
    }

    @Test
    void aDetailMapCannotLivelock() {
        // It is only ever taken when no thumbnail is queued, and the counter
        // is incremented at enqueue — so at the moment it runs, the condition
        // that would abandon it is false. Without this the queue would spin
        // take/abandon/re-queue forever on a lone detail job.
        assertFalse(abandons(false, new AtomicInteger(0)));
    }
}
