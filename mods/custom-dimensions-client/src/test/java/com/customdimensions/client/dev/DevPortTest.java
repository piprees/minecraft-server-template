package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gate. Every value that is not a positive integer must resolve to 0, which
 * is the only state in which no listener is created — a player's client must
 * not be able to open this port by accident.
 *
 * <p>The system property wins because a Prism instance sets JVM args, not
 * environment variables.
 */
class DevPortTest {

    @Test
    void systemPropertyWinsOverEnvironment() {
        assertEquals(8766, DevPort.portFrom("8766", "9999"));
    }

    @Test
    void environmentIsUsedWhenThePropertyIsAbsent() {
        assertEquals(9999, DevPort.portFrom(null, "9999"));
    }

    @Test
    void blankPropertyFallsThroughToEnvironment() {
        assertEquals(9999, DevPort.portFrom("   ", "9999"));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertEquals(8766, DevPort.portFrom(" 8766 ", null));
    }

    @Test
    void bothUnsetIsDisabled() {
        assertEquals(0, DevPort.portFrom(null, null));
    }

    @Test
    void bothBlankIsDisabled() {
        assertEquals(0, DevPort.portFrom("", "  "));
    }

    @Test
    void nonNumericIsDisabled() {
        assertEquals(0, DevPort.portFrom("yes", null));
    }

    @Test
    void anEnvironmentValueThatIsNotANumberIsDisabled() {
        assertEquals(0, DevPort.portFrom(null, "true"));
    }

    @Test
    void zeroIsDisabled() {
        assertEquals(0, DevPort.portFrom("0", null));
    }

    @Test
    void negativeIsDisabled() {
        assertEquals(0, DevPort.portFrom("-1", null));
    }

    @Test
    void aPortAboveTheLegalRangeIsDisabled() {
        assertEquals(0, DevPort.portFrom("65536", null));
    }

    @Test
    void theTopOfTheLegalRangeIsAccepted() {
        assertEquals(65535, DevPort.portFrom("65535", null));
    }

    /** A property set to a rejected value must not fall through to a live port. */
    @Test
    void aRejectedPropertyDoesNotFallThroughToTheEnvironment() {
        assertEquals(0, DevPort.portFrom("nonsense", "8766"));
    }
}
