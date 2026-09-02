package com.customdimensions.client;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.2. The flag decides whether a player sees the loading screen or empty
 * void, so every way it can stay armed when it should not is a case here:
 * never armed, consumed twice, expired, cleared.
 */
class PendingTransferTest {
    private static final Identifier DESTINATION = Identifier.of("adventure", "the_violet_spire");

    private long nowMs;

    @BeforeEach
    void freezeClock() {
        nowMs = 1_000_000L;
        PendingTransfer.setClock(() -> nowMs);
        PendingTransfer.clear();
    }

    @AfterEach
    void restoreClock() {
        PendingTransfer.clear();
        PendingTransfer.setClock(null);
    }

    @Test
    void neverArmedDoesNotSuppress() {
        assertFalse(PendingTransfer.consume());
    }

    @Test
    void armedSuppressesOnce() {
        PendingTransfer.arm(DESTINATION);
        assertTrue(PendingTransfer.consume());
    }

    @Test
    void armIsOneShot() {
        PendingTransfer.arm(DESTINATION);
        assertTrue(PendingTransfer.consume());
        assertFalse(PendingTransfer.consume(), "a second join reused the same arm");
    }

    @Test
    void expiresAfterFiveSeconds() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_001L;
        assertFalse(PendingTransfer.consume());
    }

    @Test
    void survivesToTheEdgeOfTheWindow() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_000L;
        assertTrue(PendingTransfer.consume());
    }

    @Test
    void clearDisarms() {
        PendingTransfer.arm(DESTINATION);
        PendingTransfer.clear();
        assertFalse(PendingTransfer.consume());
    }

    @Test
    void consumeReportsTheDestinationForTheLogMarker() {
        PendingTransfer.arm(DESTINATION);
        assertEquals(DESTINATION, PendingTransfer.consumeDestination());
    }

    @Test
    void expiredConsumeReportsNoDestination() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_001L;
        assertNull(PendingTransfer.consumeDestination());
    }

    /** Tier 3 greps this exact string; the plan and the code must not drift. */
    @Test
    void suppressionMarkerIsTheStringTheTestPlanGreps() {
        assertEquals("companion-suppress:arrival-screen", CustomDimensionsClient.SUPPRESS_MARKER);
    }
}
