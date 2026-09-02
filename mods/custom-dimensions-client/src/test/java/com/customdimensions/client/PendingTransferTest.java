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
 * never armed, expired, cleared — and every way it can go dark too early,
 * which is what leaves the second screen of a crossing on display.
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
        assertFalse(PendingTransfer.isArmed());
    }

    @Test
    void armedSuppresses() {
        PendingTransfer.arm(DESTINATION);
        assertTrue(PendingTransfer.isArmed());
    }

    /**
     * onPlayerRespawn installs a terrain screen twice: joinWorld's reset(Screen)
     * and startWorldLoading's setScreen(Screen). Both must be suppressed.
     */
    @Test
    void oneCrossingSuppressesBothScreenSites() {
        PendingTransfer.arm(DESTINATION);
        assertEquals(DESTINATION, PendingTransfer.peekDestination(),
                "joinWorld -> reset(Screen) was not suppressed");
        assertEquals(DESTINATION, PendingTransfer.peekDestination(),
                "startWorldLoading -> setScreen(Screen) was not suppressed");
    }

    @Test
    void arrivalEndsTheCrossing() {
        PendingTransfer.arm(DESTINATION);
        PendingTransfer.peekDestination();
        PendingTransfer.clear();
        assertNull(PendingTransfer.peekDestination(), "a later join reused the same arm");
    }

    @Test
    void expiresAfterFiveSeconds() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_001L;
        assertFalse(PendingTransfer.isArmed());
    }

    @Test
    void survivesToTheEdgeOfTheWindow() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_000L;
        assertTrue(PendingTransfer.isArmed());
    }

    @Test
    void clearDisarms() {
        PendingTransfer.arm(DESTINATION);
        PendingTransfer.clear();
        assertFalse(PendingTransfer.isArmed());
    }

    @Test
    void peekReportsTheDestinationForTheLogMarker() {
        PendingTransfer.arm(DESTINATION);
        assertEquals(DESTINATION, PendingTransfer.peekDestination());
    }

    @Test
    void expiredPeekReportsNoDestination() {
        PendingTransfer.arm(DESTINATION);
        nowMs += 5_001L;
        assertNull(PendingTransfer.peekDestination());
    }
}
