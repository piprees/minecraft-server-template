package com.customdimensions.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link RenderQueue#flag} decides whether a render gate is on. */
class RenderQueueFlagTest {

    @Test
    void anUnsetOrBlankValueTakesTheFallback() {
        assertTrue(RenderQueue.flag(null, true));
        assertTrue(RenderQueue.flag("   ", true));
        assertFalse(RenderQueue.flag(null, false));
    }

    @Test
    void onlyTheFourNegativeSpellingsTurnItOff() {
        for (String off : new String[] {"false", "FALSE", " False ", "0", "no", "NO", "off", "Off"}) {
            assertFalse(RenderQueue.flag(off, true), off);
        }
    }

    @Test
    void anythingElseIsOn() {
        for (String on : new String[] {"true", "1", "yes", "on", "banana"}) {
            assertTrue(RenderQueue.flag(on, false), on);
        }
    }
}
