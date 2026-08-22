package com.customdimensions.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The viewer has no authentication and can teleport a player, so every value
 * it cannot read as a deliberate port must resolve to "off".
 */
class SeedServerPortTest {

    @Test
    void anUnsetValueDisablesTheListener() {
        assertEquals(0, SeedServer.portFrom(null));
    }

    @Test
    void aBlankValueDisablesTheListener() {
        assertEquals(0, SeedServer.portFrom(""));
        assertEquals(0, SeedServer.portFrom("   "));
    }

    @Test
    void anUnparseableValueDisablesTheListener() {
        assertEquals(0, SeedServer.portFrom("yes"));
        assertEquals(0, SeedServer.portFrom("8765x"));
    }

    @Test
    void anExplicitZeroDisablesTheListener() {
        assertEquals(0, SeedServer.portFrom("0"));
    }

    @Test
    void anExplicitPortIsHonoured() {
        assertEquals(8765, SeedServer.portFrom("8765"));
        assertEquals(8765, SeedServer.portFrom(" 8765 "));
    }
}
