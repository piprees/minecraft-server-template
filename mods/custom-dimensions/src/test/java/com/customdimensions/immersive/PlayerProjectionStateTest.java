package com.customdimensions.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decision behind when a projection refreshes.
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
