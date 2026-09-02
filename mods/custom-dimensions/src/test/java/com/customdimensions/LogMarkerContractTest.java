package com.customdimensions;

import com.customdimensions.companion.CompanionNetwork;
import com.customdimensions.immersive.ImmersivePreloader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * These strings are API. TESTS.md greps them to turn Tier 2 into assertions,
 * and a headless run cannot tell a marker that changed from a behaviour that
 * stopped happening — both read as zero matches.
 *
 * <p>One file per module, so changing a marker fails a test whose name says
 * why. Update the literal here and in TESTS.md together, never one alone.
 */
class LogMarkerContractTest {

    /** T2.2: zero matches is the vanilla-degradation proof, so the string must hold. */
    @Test
    void sendMarker() {
        assertEquals("companion-send:preloaded-transfer", CompanionNetwork.SEND_MARKER);
    }

    /** T2.4: a send is only honest if this precedes it for the same dimension. */
    @Test
    void preloadMarker() {
        assertEquals("immersive: proximity pre-load triggered", ImmersivePreloader.PRELOAD_MARKER);
    }

    /** The runbook's positive control: without it, silence is undiagnosable. */
    @Test
    void acceptMarker() {
        assertEquals("companion-accept:handshake", CompanionNetwork.ACCEPT_MARKER);
    }
}
