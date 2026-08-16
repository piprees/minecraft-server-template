package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pin behind a forced {@code y}. Chunk generation runs on c2me worker
 * threads, so the width of this state is the whole point: one thread's pin
 * must not reach another's start attempt.
 */
class ForcedGroundLevelTest {

    @Test
    void unarmedReadsNull() {
        assertNull(ForcedGroundLevel.pinned());
    }

    @Test
    void armThenDisarmLeavesNothingBehind() {
        ForcedGroundLevel.arm(130);
        assertEquals(130, ForcedGroundLevel.pinned());
        ForcedGroundLevel.disarm();
        assertNull(ForcedGroundLevel.pinned());
    }

    @Test
    void negativeAndZeroPinsAreRealValues() {
        ForcedGroundLevel.arm(0);
        assertEquals(0, ForcedGroundLevel.pinned());
        ForcedGroundLevel.arm(-59);
        assertEquals(-59, ForcedGroundLevel.pinned());
        ForcedGroundLevel.disarm();
    }

    /** A pin on one thread is invisible to another generating concurrently. */
    @Test
    void pinDoesNotLeakAcrossThreads() throws Exception {
        ForcedGroundLevel.arm(200);
        AtomicReference<Integer> seen = new AtomicReference<>(-1);
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            seen.set(ForcedGroundLevel.pinned());
            done.countDown();
        });
        other.start();
        done.await();
        other.join();

        assertNull(seen.get(), "a worker thread must not see another thread's pin");
        assertEquals(200, ForcedGroundLevel.pinned());
        ForcedGroundLevel.disarm();
    }

    /** disarm() in a finally must clear even when the attempt throws. */
    @Test
    void disarmInFinallyClearsAfterAnException() {
        assertThrows(IllegalStateException.class, () -> {
            ForcedGroundLevel.arm(64);
            try {
                throw new IllegalStateException("structure blew up mid-start");
            } finally {
                ForcedGroundLevel.disarm();
            }
        });
        assertNull(ForcedGroundLevel.pinned());
    }
}
