package com.customdimensions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * These strings are API. TESTS.md and TIER3-RUNBOOK.md grep them, and a
 * headless run cannot tell a marker that changed from a behaviour that stopped
 * happening — both read as zero matches.
 *
 * <p>Update a literal here and in both documents together, never one alone.
 */
class ClientLogMarkerContractTest {

    /** Proves the entrypoint ran; its absence means the mod never loaded. */
    @Test
    void initMarker() {
        assertEquals("companion-client:initialised", CustomDimensionsClient.INIT_MARKER);
    }

    /** Present with no server accept means the server dropped it, not the client. */
    @Test
    void helloMarker() {
        assertEquals("companion-client:hello-sent", CustomDimensionsClient.HELLO_MARKER);
    }

    /** Tier 3: the screen check is a grep for this. */
    @Test
    void suppressMarker() {
        assertEquals("companion-suppress:arrival-screen", CustomDimensionsClient.SUPPRESS_MARKER);
    }
}
