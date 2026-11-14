package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalParticlesTest {
    @Test
    void aPlayerStandingInTheOpeningIsNotSentTheBurstAtItsEye() {
        // The rig's arrival: the eye at 20.5, -58.38, 48.5 in a 2x3 opening at z 48.
        assertTrue(Portals.nearEye(20.5, -58.38, 48.5, 20.5, -58.5, 48.5));
        assertTrue(Portals.nearEye(20.5, -58.38, 48.5, 21.5, -58.5, 48.5));
        assertTrue(Portals.nearEye(20.5, -58.38, 48.5, 20.5, -59.5, 48.5));
    }

    @Test
    void aPlayerAtTheApproachStandIsSentItAll() {
        // Four blocks in front of the frame, where the approach title is shown.
        assertFalse(Portals.nearEye(21.0, -58.38, 44.5, 20.5, -58.5, 48.5));
        assertFalse(Portals.nearEye(21.0, -58.38, 46.5, 21.5, -59.5, 48.5));
    }
}
