package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision a held walk is judged by. The harness this serves could not
 * previously tell "did not move" from "moved and something else broke", so a
 * verdict of STALLED, and the position it stalled at, are the deliverable —
 * not a convenience.
 *
 * <p>Every distance here is a literal, never a value derived from the class's
 * own constants: a fixture computed from the threshold it pins cannot fail when
 * the threshold moves.
 */
class WalkTrackerTest {

    private static final int STALL_TICKS = 20;
    private static final int TIMEOUT_TICKS = 400;

    private static WalkTracker walking(double blocks) {
        return new WalkTracker(0, 64, 0, 0, blocks, STALL_TICKS, TIMEOUT_TICKS);
    }

    // ------------------------------------------------------------- arrival

    @Test
    void aWalkThatHasNotCoveredTheDistanceContinues() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.CONTINUE, tracker.accept(3, 64, 0, 15));
    }

    @Test
    void exactlyTheRequestedDistanceArrives() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.ARRIVED, tracker.accept(12, 64, 0, 60));
    }

    @Test
    void aHairShortOfTheRequestedDistanceContinues() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.CONTINUE, tracker.accept(11.999, 64, 0, 60));
    }

    @Test
    void distanceIsMeasuredDiagonallyAcrossXAndZ() {
        WalkTracker tracker = walking(5);
        assertEquals(WalkTracker.Verdict.ARRIVED, tracker.accept(3, 64, 4, 30));
    }

    @Test
    void zeroBlocksArrivesOnTheFirstSample() {
        WalkTracker tracker = walking(0);
        assertEquals(WalkTracker.Verdict.ARRIVED, tracker.accept(0, 64, 0, 0));
    }

    @Test
    void travelledIsTheDistanceFromTheStart() {
        WalkTracker tracker = walking(12);
        tracker.accept(3, 64, 4, 30);
        assertEquals(5.0, tracker.travelled(), 1e-9);
    }

    @Test
    void ticksCountFromTheStartingTick() {
        WalkTracker tracker = new WalkTracker(0, 64, 0, 100, 12, STALL_TICKS, TIMEOUT_TICKS);
        tracker.accept(3, 64, 0, 143);
        assertEquals(43, tracker.ticks());
    }

    // --------------------------------------------------------------- falling

    /** A fall is not travel, and a player falling while forward is held is stuck. */
    @Test
    void fallingStraightDownCountsAsNoDistance() {
        WalkTracker tracker = walking(12);
        tracker.accept(0, 4, 0, 19);
        assertEquals(0.0, tracker.travelled(), 1e-9);
    }

    @Test
    void fallingStraightDownStalls() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(0, 4, 0, 20));
    }

    // ----------------------------------------------------------------- stall

    @Test
    void standingStillForTheStallWindowStalls() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(0, 64, 0, 20));
    }

    @Test
    void oneTickShortOfTheStallWindowContinues() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.CONTINUE, tracker.accept(0, 64, 0, 19));
    }

    @Test
    void movingResetsTheStallClock() {
        WalkTracker tracker = walking(12);
        tracker.accept(0.5, 64, 0, 10);
        assertEquals(WalkTracker.Verdict.CONTINUE, tracker.accept(0.5, 64, 0, 29));
    }

    @Test
    void theStallClockRunsFromTheLastRealMovement() {
        WalkTracker tracker = walking(12);
        tracker.accept(0.5, 64, 0, 10);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(0.5, 64, 0, 30));
    }

    /** Jitter below the movement threshold is not movement and must not reset it. */
    @Test
    void movementTooSmallToMatterDoesNotResetTheStallClock() {
        WalkTracker tracker = walking(12);
        tracker.accept(0.01, 64, 0, 10);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(0.02, 64, 0, 20));
    }

    @Test
    void aStalledWalkReportsWhereItStopped() {
        WalkTracker tracker = walking(12);
        tracker.accept(7.25, 63, -3.5, 10);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(7.25, 63, -3.5, 30));
        assertEquals(7.25, tracker.stalledX(), 1e-9);
        assertEquals(63.0, tracker.stalledY(), 1e-9);
        assertEquals(-3.5, tracker.stalledZ(), 1e-9);
    }

    @Test
    void aWalkThatIsNotStalledHasNoStallPosition() {
        WalkTracker tracker = walking(12);
        tracker.accept(3, 64, 0, 15);
        assertNull(tracker.stalledAt());
    }

    // --------------------------------------------------------------- timeout

    @Test
    void movingTooSlowlyToArriveTimesOut() {
        WalkTracker tracker = walking(120);
        // A sample every 10 ticks, always moving, so the stall clock never runs out.
        for (int tick = 10; tick < 400; tick += 10) {
            assertEquals(WalkTracker.Verdict.CONTINUE, tracker.accept(tick * 0.05, 64, 0, tick));
        }
        assertEquals(WalkTracker.Verdict.TIMED_OUT, tracker.accept(20, 64, 0, 400));
    }

    @Test
    void arrivalOnTheTimeoutTickIsAnArrival() {
        WalkTracker tracker = walking(12);
        assertEquals(WalkTracker.Verdict.ARRIVED, tracker.accept(12, 64, 0, 400));
    }

    /** Stall is the diagnosis; a timeout that is also a stall reports the stall. */
    @Test
    void aStallThatReachesTheTimeoutIsReportedAsAStall() {
        WalkTracker tracker = new WalkTracker(0, 64, 0, 0, 12, 20, 20);
        assertEquals(WalkTracker.Verdict.STALLED, tracker.accept(0, 64, 0, 20));
    }

    // ------------------------------------------------------------ conclusion

    @Test
    void aDecidedWalkKeepsItsVerdict() {
        WalkTracker tracker = walking(12);
        tracker.accept(12, 64, 0, 60);
        tracker.accept(30, 64, 0, 120);
        assertEquals(WalkTracker.Verdict.ARRIVED, tracker.verdict());
        assertEquals(60, tracker.ticks());
        assertEquals(12.0, tracker.travelled(), 1e-9);
    }

    @Test
    void onlyArrivedCountsAsArrival() {
        WalkTracker tracker = walking(12);
        tracker.accept(0, 64, 0, 20);
        assertTrue(tracker.stalled());
        assertEquals(false, tracker.arrived());
    }

    // ---------------------------------------------------------------- reason

    @Test
    void aStallReasonNamesTheStallWindow() {
        WalkTracker tracker = walking(12);
        tracker.accept(0, 64, 0, 20);
        assertEquals("position unchanged for 20 ticks while forward was held",
                tracker.reason());
    }

    @Test
    void aTimeoutReasonNamesTheDistanceThatWasNotCovered() {
        WalkTracker tracker = new WalkTracker(0, 64, 0, 0, 120, 20, 40);
        tracker.accept(1, 64, 0, 20);
        tracker.accept(2, 64, 0, 40);
        assertEquals("timed out after 40 ticks having travelled 2 of 120 blocks",
                tracker.reason());
    }

    @Test
    void anArrivalReasonSaysSo() {
        WalkTracker tracker = walking(12);
        tracker.accept(12, 64, 0, 60);
        assertEquals("arrived", tracker.reason());
    }

    // ------------------------------------------------------- millisecond edge

    @Test
    void twentyThousandMillisecondsIsFourHundredTicks() {
        assertEquals(400, WalkTracker.ticksFromMillis(20000));
    }

    @Test
    void aTimeoutShorterThanOneTickIsStillOneTick() {
        assertEquals(1, WalkTracker.ticksFromMillis(1));
    }

    @Test
    void aZeroOrNegativeTimeoutIsStillOneTick() {
        assertEquals(1, WalkTracker.ticksFromMillis(0));
        assertEquals(1, WalkTracker.ticksFromMillis(-5000));
    }
}
