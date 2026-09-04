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

    /**
     * Both views off is a request for a plain portal, and it is honoured.
     * Streaming the slab anyway would hand back the view the player just
     * switched off.
     */
    @Test
    void bothViewsOffGetsAPlainPortal() {
        assertFalse(new PortalViewPreference(false, false, 16).streamsSlab());
    }

    /**
     * A vanilla client never declares, so it is only ever
     * {@link PortalViewPreference#SERVER_DRAWN}. It must keep streaming
     * whatever the rule is.
     */
    @Test
    void aVanillaClientStreamsTheSlab() {
        assertTrue(PortalViewPreference.SERVER_DRAWN.streamsSlab());
        assertTrue(PortalViewPreference.SERVER_DRAWN.keepSlab());
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
