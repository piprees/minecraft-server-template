package com.customdimensions.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one client has said it will draw for itself, and the one question the
 * projection pass asks of it.
 */
class PortalViewPreferenceTest {

    /**
     * A client that has never declared anything is every vanilla client and
     * every companion built before this existed. The slab is what they get.
     */
    @Test
    void theUndeclaredDefaultIsTheSlab() {
        assertTrue(PortalViewPreference.SERVER_DRAWN.streamsSlab());
        assertFalse(PortalViewPreference.SERVER_DRAWN.rendersLocally());
    }

    @Test
    void aClientRenderingLocallyAndRefusingTheSlabIsSentNoBlocks() {
        assertFalse(new PortalViewPreference(true, false, 16).streamsSlab());
    }

    /**
     * The fallback is the whole reason the flag exists: a client that renders
     * locally but has asked to keep the slab still gets it, and draws it
     * wherever its own view has nothing yet.
     */
    @Test
    void aClientKeepingTheFallbackStillGetsTheSlab() {
        assertTrue(new PortalViewPreference(true, true, 16).streamsSlab());
    }

    @Test
    void refusingTheSlabWithoutRenderingLocallyStillGetsIt() {
        assertTrue(new PortalViewPreference(false, false, 16).streamsSlab(),
                "a client that draws nothing and is sent nothing would show an empty frame");
    }

    @Test
    void aRenderDistanceOutsideTheRangeIsClampedRatherThanTrusted() {
        assertEquals(PortalViewPreference.MIN_RENDER_DISTANCE,
                new PortalViewPreference(true, false, -5).maxRenderDistance());
        assertEquals(PortalViewPreference.MAX_RENDER_DISTANCE,
                new PortalViewPreference(true, false, 4096).maxRenderDistance());
        assertEquals(16, new PortalViewPreference(true, false, 16).maxRenderDistance());
    }
}
