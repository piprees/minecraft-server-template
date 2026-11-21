package com.customdimensions.core.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BorderClampTest {
    @Test
    void aColumnInsideTheBorderStays() {
        assertEquals(100, PortalScaling.insideBorder(100, -512, 512));
    }

    @Test
    void aColumnOutsideIsBroughtInsideByTheMargin() {
        // The matrix bay at x -6797 into e2e_four, whose player border is 512 across.
        assertEquals(-256 + PortalScaling.BORDER_MARGIN, PortalScaling.insideBorder(-6797, -256, 256));
        assertEquals(256 - PortalScaling.BORDER_MARGIN - 1, PortalScaling.insideBorder(6797, -256, 256));
    }

    @Test
    void aBorderNarrowerThanTheMarginGivesItsCentre() {
        assertEquals(0, PortalScaling.insideBorder(500, -5, 5));
    }
}
