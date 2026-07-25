package com.customdimensions.immersive;

import com.customdimensions.immersive.PlayerProjectionState.DepthDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure decisions behind Phase 4: when a projection refreshes (4c) and
 * how deep it goes (4e).
 *
 * The 4e tests are the ones that matter. "No block at this position" has two
 * completely different causes — the target dimension is empty there, or its
 * chunk simply is not loaded yet — and the projector meets the second one
 * constantly: the initial full send routinely covers 294 of 336 positions
 * because the arrival chunk ticket loads asynchronously. If unknown counted
 * as air, every portal would conclude "void dimension" on activation and
 * shrink to {@link PlayerProjectionState#SHALLOW_DEPTH} permanently, with a
 * green build, green tests and no log line. These tests pin the distinction.
 */
class PlayerProjectionStateTest {

    // ---- 4e: air / solid / unknown -------------------------------------

    @Test
    void anEntirelyUnloadedLayerIsUndecidable() {
        // The measured live case: the far chunk missed this tick, so the
        // whole first layer reads "no block". That is not a void dimension.
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(0, 0, 42));
        // Nothing sampled at all (degenerate volume) is equally undecidable.
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(0, 0, 0));
    }

    @Test
    void mostlyUnknownDoesNotShrinkTheProjection() {
        // 8 air + 2 solid of a 40-position layer. Counting the 30 unloaded
        // positions as air would read as 38/40 = 95% empty and shrink the
        // preview; only a quarter of the layer is actually known, so the
        // honest answer is "ask again later".
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(8, 2, 30));
        // Even an all-air known sample stays pending while most of the
        // layer is missing — unknown is never evidence of emptiness.
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(10, 0, 32));
        // A single known solid block among unknowns decides nothing either.
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(0, 1, 41));
    }

    @Test
    void threeQuartersKnownIsTheDecisionThreshold() {
        // Exactly 75% known decides; one position less does not.
        assertNotEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(30, 0, 10));
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(29, 0, 11));
        // Small layers use the same ratio, not an absolute floor.
        assertNotEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(3, 0, 1));
        assertEquals(DepthDecision.PENDING, PlayerProjectionState.decideDepth(2, 0, 2));
    }

    @Test
    void anEmptyFarSideShrinksAndASolidOneDoesNot() {
        assertEquals(DepthDecision.SHALLOW, PlayerProjectionState.decideDepth(42, 0, 0));
        assertEquals(DepthDecision.FULL, PlayerProjectionState.decideDepth(0, 42, 0));
        // Terrain with a bit of sky above it is still terrain.
        assertEquals(DepthDecision.FULL, PlayerProjectionState.decideDepth(20, 22, 0));
    }

    @Test
    void theAirThresholdIsMeasuredAgainstKnownSamplesOnly() {
        // Exactly 80% air is not MORE than 80%: full depth.
        assertEquals(DepthDecision.FULL, PlayerProjectionState.decideDepth(8, 2, 0));
        assertEquals(DepthDecision.SHALLOW, PlayerProjectionState.decideDepth(9, 1, 0));
        // With unknowns present, the ratio uses the known 30 (24 air = 80%),
        // never the full 40 — which would make it 60% and change the answer.
        assertEquals(DepthDecision.FULL, PlayerProjectionState.decideDepth(24, 6, 10));
        assertEquals(DepthDecision.SHALLOW, PlayerProjectionState.decideDepth(25, 5, 10));
    }

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
        // needsRefresh passes MAX_VALUE when there is no last position (or
        // while the 4e depth question is still open) — the base interval,
        // never the stretched one.
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
