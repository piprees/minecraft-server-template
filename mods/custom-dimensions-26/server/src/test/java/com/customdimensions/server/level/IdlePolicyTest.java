package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdlePolicyTest {
    private static final long MINUTE = 60_000;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final IdlePolicy policy = IdlePolicy.ofMinutes(5, now::get);

    @Test
    void unloadsAfterTheIdleTimeEmpty() {
        assertFalse(policy.observe("a", false), "first empty sighting starts the clock");
        now.addAndGet(5 * MINUTE - 1);
        assertFalse(policy.observe("a", false));
        now.addAndGet(1);
        assertTrue(policy.observe("a", false), "exactly the idle time");
        now.addAndGet(MINUTE);
        assertTrue(policy.observe("a", false), "still due until forgotten");
    }

    @Test
    void aPlayerRestartsTheClock() {
        policy.observe("a", false);
        now.addAndGet(4 * MINUTE);
        assertFalse(policy.observe("a", true));
        assertEquals(OptionalLong.empty(), policy.emptyMillis("a"));
        now.addAndGet(MINUTE);
        assertFalse(policy.observe("a", false), "empty again: the clock starts over");
        now.addAndGet(4 * MINUTE);
        assertFalse(policy.observe("a", false));
        assertEquals(OptionalLong.of(4 * MINUTE), policy.emptyMillis("a"));
        now.addAndGet(MINUTE);
        assertTrue(policy.observe("a", false));
    }

    @Test
    void levelsAreTimedApart() {
        policy.observe("a", false);
        now.addAndGet(3 * MINUTE);
        policy.observe("b", false);
        now.addAndGet(2 * MINUTE);
        assertTrue(policy.observe("a", false));
        assertFalse(policy.observe("b", false));
    }

    @Test
    void forgettingALevelStartsItAfresh() {
        policy.observe("a", false);
        now.addAndGet(5 * MINUTE);
        policy.forget("a");
        assertFalse(policy.observe("a", false));
    }

    @Test
    void nullOrNonPositiveMinutesNeverUnload() {
        for (Integer minutes : new Integer[] {null, 0, -3}) {
            IdlePolicy off = IdlePolicy.ofMinutes(minutes, now::get);
            assertFalse(off.enabled());
            off.observe("a", false);
            now.addAndGet(1_000 * MINUTE);
            assertFalse(off.observe("a", false), "minutes " + minutes);
        }
    }
}
