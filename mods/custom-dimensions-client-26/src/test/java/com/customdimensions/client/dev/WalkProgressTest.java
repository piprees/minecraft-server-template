package com.customdimensions.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WalkProgressTest {
    @Test
    void aWalkThatCoversItsDistanceArrives() {
        WalkProgress w = new WalkProgress(3, 200, 0, 0);
        int t = 0;
        while (!w.tick(0, 64, 0.2 * ++t)) {
        }
        assertTrue(w.arrived());
        assertFalse(w.stalled());
        assertEquals(15, w.ticks());
        assertNull(w.stalledAt());
    }

    @Test
    void aWalkAgainstAWallStallsAndSaysWhere() {
        WalkProgress w = new WalkProgress(10, 200, 0, 0);
        int t = 0;
        while (!w.tick(0, 64, Math.min(1.0, 0.2 * ++t))) {
        }
        assertTrue(w.stalled());
        assertFalse(w.arrived());
        assertNotNull(w.stalledAt());
        assertEquals(1.0, w.stalledAt()[2], 1e-9);
    }

    @Test
    void aSlowWalkTimesOutNeitherArrivedNorStalled() {
        WalkProgress w = new WalkProgress(100, 50, 0, 0);
        int t = 0;
        while (!w.tick(0.1 * ++t, 64, 0)) {
        }
        assertFalse(w.arrived());
        assertFalse(w.stalled());
        assertTrue(w.reason().startsWith("timed out"));
    }

    @Test
    void anInterruptEndsTheWalkWithItsReason() {
        WalkProgress w = new WalkProgress(10, 200, 0, 0);
        w.tick(0, 64, 0.5);
        w.interrupt("changed dimension");
        assertTrue(w.tick(0, 64, 9));
        assertEquals("changed dimension", w.reason());
        assertFalse(w.arrived());
    }

    @Test
    void aWalkNeedsADistance() {
        assertThrows(IllegalArgumentException.class, () -> new WalkProgress(0, 10, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new WalkProgress(1, 0, 0, 0));
    }
}
