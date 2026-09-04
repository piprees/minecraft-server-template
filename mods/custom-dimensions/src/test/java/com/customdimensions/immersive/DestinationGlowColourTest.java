package com.customdimensions.immersive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which colour a portal's destination tint comes from: the dimension config's
 * authored {@code environment.fogColor} when there is one, the destination
 * biome otherwise.
 *
 * <p>{@code -1} is the only sentinel for "absent", so an authored
 * {@code #000000} parses to 0 and must win rather than fall through to the
 * biome. {@code DestinationGlow.sample} itself needs a {@code ServerWorld} and
 * is not unit-testable, which is why the rule is extracted.
 */
class DestinationGlowColourTest {

    @Test
    void authoredColourWins() {
        assertEquals(0x8B0000, DestinationGlow.preferConfigured(0x8B0000, 0x3A0E0E));
    }

    @Test
    void biomeIsTheFallback() {
        assertEquals(0x3A0E0E, DestinationGlow.preferConfigured(-1, 0x3A0E0E));
    }

    @Test
    void absentOnBothSidesStaysAbsent() {
        assertEquals(-1, DestinationGlow.preferConfigured(-1, -1));
    }

    @Test
    void authoredBlackIsAColourNotAnAbsence() {
        assertEquals(0x000000, DestinationGlow.preferConfigured(0x000000, 0x3A0E0E),
                "#000000 parses to 0, and 0 >= 0 — only -1 means absent");
    }
}
