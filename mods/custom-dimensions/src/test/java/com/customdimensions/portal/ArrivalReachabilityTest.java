package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ArrivalReachability} decides whether a portal a player can build can
 * actually be arrived at. It shipped fully written with zero callers and zero
 * tests — the class existed, the check never ran.
 *
 * <p>The arithmetic it encodes is the one that cost two sessions on
 * 2026-07-25: entering DIVIDES by scale, so a source portal at radius R
 * arrives at R / scale and must land inside the destination's PLAYER border
 * (with room for its frame ring and egress pocket). Outside that border
 * vanilla forbids breaking AND placing every block, and the symptom — "I
 * cannot break anything" — points nowhere near the cause.
 */
class ArrivalReachabilityTest {

    private static final int MARGIN = 8;   // exercise the margin arithmetic itself

    // === usableSourceRadius ==============================================

    @Test
    void dividingOnEntryMakesAScaledDimensionMoreReachableNotLess() {
        // The direction that matters. A scale-8 dimension with a 1024 border
        // absorbs source portals out to ~8k, because entering COMPACTS them.
        // Under the old multiply reading the same pair allowed only 128
        // blocks, which is what the (now corrected) PHASE-9 table recorded.
        assertEquals((1024 - MARGIN) * 8, ArrivalReachability.usableSourceRadius(8.0, 1024, MARGIN));
    }

    @Test
    void marginComesOffTheDestinationBeforeScaling() {
        // The frame ring and egress pocket must be inside too, not just the
        // centre cell — so the margin is destination-side, and scaling it up
        // by `scale` afterwards is exactly right.
        assertEquals(1016 * 8, ArrivalReachability.usableSourceRadius(8.0, 1024, MARGIN));
        assertEquals(1024 * 8, ArrivalReachability.usableSourceRadius(8.0, 1024, 0));
    }

    @Test
    void scaleOneIsTheIdentityApartFromTheMargin() {
        assertEquals(248, ArrivalReachability.usableSourceRadius(1.0, 256, MARGIN));
    }

    @Test
    void anUnboundedDestinationAbsorbsEverything() {
        assertEquals(Integer.MAX_VALUE,
                ArrivalReachability.usableSourceRadius(4.0, ArrivalReachability.UNBOUNDED, MARGIN));
    }

    @Test
    void aMarginBiggerThanTheBorderCollapsesToZeroRatherThanNegative() {
        assertEquals(0, ArrivalReachability.usableSourceRadius(8.0, 4, MARGIN));
    }

    @Test
    void aNonPositiveScaleIsTreatedAsUnboundedNotDividedByZero() {
        assertEquals(Integer.MAX_VALUE, ArrivalReachability.usableSourceRadius(0.0, 1024, MARGIN));
        assertEquals(Integer.MAX_VALUE, ArrivalReachability.usableSourceRadius(-2.0, 1024, MARGIN));
    }

    // === allArrivalsReachable ============================================

    @Test
    void theShippedEmberFieldsPairIsReachableUnderDivideOnEntry() {
        // scale 8, border 1024, overworld 8192. This is the exact pair that
        // stranded a player under the multiply bug; with the corrected
        // transform it is comfortably fine, and the boot must stay quiet
        // about it. Encoding the OLD behaviour here would immortalise the bug
        // (see PLAN.md — the first PortalScalingContractTest did exactly that).
        //
        // Margin 0 here on purpose: 8192 / 8 is EXACTLY 1024, which is how the
        // whole dimension set is authored, so this pair sits precisely on the
        // boundary and any margin at all fails it. That is why the boot check
        // runs at margin 0 too (PortalSafetyValidator.ARRIVAL_MARGIN).
        assertTrue(ArrivalReachability.allArrivalsReachable(8.0, 8192, 1024, 0));
        assertFalse(ArrivalReachability.allArrivalsReachable(8.0, 8192, 1023, 0),
                "one block under the exact quotient must fail");
    }

    @Test
    void aTightBorderAtScaleOneIsNotReachable() {
        // scale 1 means the destination needs the source's whole radius.
        assertFalse(ArrivalReachability.allArrivalsReachable(1.0, 8192, 256, MARGIN));
    }

    @Test
    void exactlyEnoughBorderIsReachable() {
        int required = ArrivalReachability.requiredDestBorderRadius(8.0, 8192, MARGIN);
        assertTrue(ArrivalReachability.allArrivalsReachable(8.0, 8192, required, MARGIN));
        assertFalse(ArrivalReachability.allArrivalsReachable(8.0, 8192, required - 1, MARGIN),
                "one block under must fail, or the boundary is not being tested");
    }

    @Test
    void anUnboundedSourceIntoABoundedDestinationCanAlwaysStrand() {
        assertFalse(ArrivalReachability.allArrivalsReachable(
                8.0, ArrivalReachability.UNBOUNDED, 1024, MARGIN));
    }

    @Test
    void anUnboundedDestinationIsAlwaysReachable() {
        assertTrue(ArrivalReachability.allArrivalsReachable(
                8.0, ArrivalReachability.UNBOUNDED, ArrivalReachability.UNBOUNDED, MARGIN));
    }

    // === requiredDestBorderRadius ========================================

    @Test
    void requiredBorderIsTheNumberToPutInConfig() {
        // 8192 / 8 = 1024, plus the margin.
        assertEquals(1024 + MARGIN, ArrivalReachability.requiredDestBorderRadius(8.0, 8192, MARGIN));
    }

    @Test
    void requiredBorderRoundsUpNeverDown() {
        // 8192 / 12 = 682.67 — rounding down would authorise a border that
        // strands the outermost ring of source portals.
        assertEquals(683 + MARGIN, ArrivalReachability.requiredDestBorderRadius(12.0, 8192, MARGIN));
    }

    @Test
    void requiredBorderOfAnUnboundedSourceIsUnbounded() {
        assertEquals(ArrivalReachability.UNBOUNDED,
                ArrivalReachability.requiredDestBorderRadius(8.0, ArrivalReachability.UNBOUNDED, MARGIN));
    }

    @Test
    void requiredBorderRoundTripsThroughUsableSourceRadius() {
        // The two halves must agree, or config authored against one is
        // rejected by the other.
        for (double scale : new double[]{1.0, 4.0, 8.0, 12.0, 16.0}) {
            int required = ArrivalReachability.requiredDestBorderRadius(scale, 8192, MARGIN);
            assertTrue(ArrivalReachability.usableSourceRadius(scale, required, MARGIN) >= 8192,
                    "scale " + scale + ": required border must actually be enough");
        }
    }
}
