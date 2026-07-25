package com.customdimensions.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decision behind Phase 4c: when a projection refreshes.
 *
 * <p>This class used to also cover 4e's depth auto-scaling — a heuristic that
 * shrank the preview to two blocks when the first depth layer sampled as
 * mostly air. Those tests were correct about the code and useless about the
 * feature: the first layer is the slab just above the destination's SURFACE,
 * so "mostly air" is the healthy case for a portal onto open terrain, and
 * whether a portal ran deep or shallow came down to how much of the
 * previewRadius padding happened to land in a hillside. It shipped a tester a
 * 6x7x2 preview he described as having "no window effect at all". The shrink
 * is gone; the reasoning is preserved in {@code PlayerProjectionState}'s class
 * comment so it is not rediscovered as a good idea.
 */
class PlayerProjectionStateTest {

    // ---- 4c: refresh throttling ----------------------------------------

    @Test
    void aMovingPlayerRefreshesOnTheConfiguredInterval() {
        // Moved a full block since the last pass: base interval applies.
        assertFalse(PlayerProjectionState.shouldRefresh(103, 100, 1.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, 1.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(140, 100, 1.0, 4));
    }

    @Test
    void aStationaryPlayerRefreshesFourTimesLessOften() {
        assertFalse(PlayerProjectionState.shouldRefresh(104, 100, 0.0, 4));
        assertFalse(PlayerProjectionState.shouldRefresh(115, 100, 0.0, 4));
        assertTrue(PlayerProjectionState.shouldRefresh(116, 100, 0.0, 4));
    }

    @Test
    void theStationaryThresholdIsHalfABlock() {
        // 0.25 squared distance == 0.5 blocks: still stationary.
        assertFalse(PlayerProjectionState.shouldRefresh(108, 100, 0.25, 4));
        // A hair beyond it counts as movement and takes the base interval.
        assertTrue(PlayerProjectionState.shouldRefresh(108, 100, 0.2501, 4));
    }

    @Test
    void aProjectionWithNoBaselineRefreshesImmediately() {
        // needsRefresh passes MAX_VALUE when there is no last eye position —
        // the base interval, never the stretched one.
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, Double.MAX_VALUE, 4));
        assertFalse(PlayerProjectionState.shouldRefresh(103, 100, Double.MAX_VALUE, 4));
    }

    @Test
    void aDegenerateIntervalStillMakesProgress() {
        // ImmersiveSettings clamps refreshInterval to >= 2, but a throttle
        // that could return false forever is not worth the risk.
        assertTrue(PlayerProjectionState.shouldRefresh(101, 100, 1.0, 0));
        assertTrue(PlayerProjectionState.shouldRefresh(104, 100, 0.0, 0));
        assertFalse(PlayerProjectionState.shouldRefresh(100, 100, 1.0, 0));
    }
}
