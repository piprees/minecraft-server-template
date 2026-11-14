package com.customdimensions.client.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MotionTest {
    static final double ARROW_GRAVITY = 0.05;

    /** Vanilla's arrow tick, written out: move by the velocity, then drag, then gravity. */
    static double[] vanilla(double[] p, double[] v, int ticks) {
        double[] at = p.clone();
        double[] vel = v.clone();
        for (int t = 0; t < ticks; t++) {
            for (int k = 0; k < 3; k++) {
                at[k] += vel[k];
                vel[k] *= 0.99;
            }
            vel[1] -= ARROW_GRAVITY;
        }
        return at;
    }

    @Test
    void aProjectileFollowsVanillasTickForWholeTicks() {
        double[] p = {10, 70, -3};
        double[] v = {1.2, 0.4, -0.7};
        for (int t = 0; t <= Motion.REACH; t++) {
            assertArrayEquals(vanilla(p, v, t), Motion.projectile(p, v, t, ARROW_GRAVITY, Motion.DRAG), 1e-9,
                    "tick " + t);
        }
    }

    @Test
    void aProjectileMovesBetweenTicksAndStopsAtItsReach() {
        double[] p = {0, 64, 0};
        double[] v = {2, 0, 0};
        double[] half = Motion.projectile(p, v, 0.5, 0, 1);
        assertEquals(1.0, half[0], 1e-9, "half a tick is half a step");
        double[] far = Motion.projectile(p, v, 100, 0, 1);
        assertEquals(2.0 * Motion.REACH, far[0], 1e-9, "carried for at most REACH ticks");
        assertArrayEquals(p, Motion.projectile(p, v, -3, 0, 1), 1e-9, "nothing before the snapshot");
    }

    @Test
    void aGapClosesOverOneInterval() {
        assertEquals(1, Motion.gapWeight(0));
        assertEquals(0.5, Motion.gapWeight(Motion.INTERVAL / 2.0), 1e-9);
        assertEquals(0, Motion.gapWeight(Motion.INTERVAL));
        assertEquals(0, Motion.gapWeight(99));
    }

    @Test
    void anglesTurnTheShortWay() {
        assertEquals(360, Motion.lerpAngle(350, 10, 0.5), 1e-4, "through north, not round the back");
        assertEquals(370, Motion.lerpAngle(350, 10, 1), 1e-4);
        assertEquals(190, Motion.lerpAngle(170, -170, 1), 1e-4);
        assertEquals(180, Motion.lerpAngle(170, -170, 0.5), 1e-4);
        assertEquals(30, Motion.lerpAngle(30, 90, -1), 1e-4, "clamped below");
    }

    @Test
    void aMobIsDrawnAtItsFirstSnapshotAndGlidesToTheNext() {
        Motion.Track t = new Motion.Track(false);
        assertFalse(t.started());
        t.update(new double[] {0, 64, 0}, new double[] {0, -0.08, 0}, 0.08, 0, 0, 0, 100);
        assertTrue(t.started());
        assertArrayEquals(new double[] {0, 64, 0}, t.position(102), 1e-9, "a mob is never extrapolated");
        t.update(new double[] {1, 64, 0}, new double[] {0, 0, 0}, 0.08, 90, 0, 90, 104);
        assertArrayEquals(new double[] {0, 64, 0}, t.position(104), 1e-9, "no jump on a new snapshot");
        assertArrayEquals(new double[] {0.5, 64, 0}, t.position(106), 1e-9, "half way at half an interval");
        assertArrayEquals(new double[] {1, 64, 0}, t.position(108), 1e-9, "there after one interval");
        assertEquals(45, t.yaw(106), 1e-4);
        assertEquals(90, t.head(110), 1e-4);
    }

    @Test
    void aProjectileFliesOnAndANewSnapshotDoesNotJump() {
        Motion.Track t = new Motion.Track(true);
        double[] v = {1, 0, 0};
        t.update(new double[] {0, 70, 0}, v, 0, 0, 0, 0, 0);
        assertEquals(vanilla(new double[] {0, 70, 0}, v, 3)[0], t.position(3)[0], 1e-9, "flies on along its velocity");
        double[] before = t.position(4);
        t.update(new double[] {4.3, 70, 0}, v, 0, 0, 0, 0, 4);
        assertArrayEquals(before, t.position(4), 1e-9, "continuous across the snapshot");
        assertEquals(vanilla(new double[] {4.3, 70, 0}, v, 4)[0], t.position(8)[0], 1e-9,
                "on the new snapshot's line once the gap has closed");
    }

    @Test
    void aTeleportIsDrawnWhereItLands() {
        Motion.Track t = new Motion.Track(false);
        t.update(new double[] {0, 64, 0}, new double[3], 0, 0, 0, 0, 0);
        t.update(new double[] {50, 64, 0}, new double[3], 0, 0, 0, 0, 4);
        assertArrayEquals(new double[] {50, 64, 0}, t.position(4), 1e-9);
    }
}
