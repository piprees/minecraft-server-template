package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalPresentationTest {
    private static final double FAR = Double.POSITIVE_INFINITY;

    @Test
    void theFirstApproachTitles() {
        PortalPresentation.Step s = PortalPresentation.next(null, FAR, "z", 3.0);
        assertTrue(s.title());
        assertEquals("z", s.held());
    }

    @Test
    void stayingNearTheHeldZoneDoesNotTitleAgain() {
        PortalPresentation.Step s = PortalPresentation.next("z", 3.0, "z", 3.0);
        assertFalse(s.title());
        assertEquals("z", s.held());
    }

    @Test
    void leavingTheHeldZoneLetsItGoEvenBesideAnotherZone() {
        // The rig's Z frame: its far point stands 7.5 blocks from the X frame and 20 from Z.
        PortalPresentation.Step away = PortalPresentation.next("z", 20.0, "x", 7.5);
        assertFalse(away.title());
        assertNull(away.held());
        PortalPresentation.Step back = PortalPresentation.next(away.held(), FAR, "z", 3.0);
        assertTrue(back.title());
        assertEquals("z", back.held());
    }

    @Test
    void withinLeaveOfTheHeldZoneItIsKept() {
        PortalPresentation.Step s = PortalPresentation.next("z", 11.0, "x", 7.5);
        assertFalse(s.title());
        assertEquals("z", s.held());
    }

    @Test
    void aNearerOtherZoneTitles() {
        PortalPresentation.Step s = PortalPresentation.next("z", 9.0, "x", 4.0);
        assertTrue(s.title());
        assertEquals("x", s.held());
    }

    @Test
    void aGoneHeldZoneIsLetGo() {
        PortalPresentation.Step s = PortalPresentation.next("z", FAR, null, Double.MAX_VALUE);
        assertFalse(s.title());
        assertNull(s.held());
    }
}
