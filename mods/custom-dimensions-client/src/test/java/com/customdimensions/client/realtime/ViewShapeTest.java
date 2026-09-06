package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cone the walk reads, and the near field it must not narrow.
 *
 * <p>The box is cubic in depth because a prism widens at every depth to the
 * width only the far edge needs. The cone spends that width where the
 * sightline actually needs it, floored so nothing near the opening is lost.
 */
class ViewShapeTest {

    private static final int NEAR = RealtimeView.NEAR_RADIUS;
    private static final int RATIO = RealtimeView.CONE_RATIO;

    /**
     * The whole reason the floor exists. An eye close to the opening sees
     * wider than the cone at that depth allows, so the first
     * {@code NEAR * RATIO} blocks keep the full width they have today.
     */
    @Test
    void theNearFieldKeepsTodaysFullWidth() {
        for (int depthIndex = 0; depthIndex < NEAR * RATIO; depthIndex++) {
            assertEquals(NEAR, ViewShape.halfWidthAt(depthIndex, NEAR, RATIO),
                    "the cone narrowed the near field at depth " + (depthIndex + 1));
        }
    }

    /** Past the near field the cone widens, which is what buys the depth. */
    @Test
    void theConeWidensPastTheNearField() {
        int justPast = NEAR * RATIO;
        assertTrue(ViewShape.halfWidthAt(justPast, NEAR, RATIO) > NEAR,
                "the cone never widens, so the far field is a prism again");
        assertEquals(NEAR * 2, ViewShape.halfWidthAt(NEAR * RATIO * 2 - 1, NEAR, RATIO));
    }

    /** The cone must reach the array's edge at the far face, or the box is wasted. */
    @Test
    void theConeFillsTheArrayAtFullDepth() {
        assertEquals(RealtimeView.RADIUS,
                ViewShape.halfWidthAt(RealtimeView.DEPTH - 1, NEAR, RATIO),
                "the cone stops short of RADIUS, so the array is wider than anything read");
    }

    @Test
    void anAxisHoldsTheApertureAndItsHalfWidthEitherSide() {
        int lead = 22;
        int span = 2;
        assertTrue(ViewShape.withinAxis(lead, lead, span, 4));
        assertTrue(ViewShape.withinAxis(lead - 4, lead, span, 4));
        assertTrue(ViewShape.withinAxis(lead + span + 3, lead, span, 4));
        assertFalse(ViewShape.withinAxis(lead - 5, lead, span, 4));
        assertFalse(ViewShape.withinAxis(lead + span + 4, lead, span, 4));
    }

    /** The saving, stated: the cone reads well under the prism it sits in. */
    @Test
    void theConeCostsLessThanThePrismItSitsIn() {
        int spanA = 2;
        int spanB = 3;
        int depth = RealtimeView.DEPTH;
        int radius = RealtimeView.RADIUS;
        int prism = (spanA + 2 * radius) * (spanB + 2 * radius) * depth;
        int cone = ViewShape.cells(spanA, spanB, depth, NEAR, RATIO);
        assertTrue(cone < prism,
                "the cone reads " + cone + " cells against the prism's " + prism);
    }
}
