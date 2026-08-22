package com.customdimensions.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The viewer has no authentication and can teleport a player, so every value
 * it cannot read as a deliberate port must resolve to "off".
 */
class SeedServerPortTest {

    @Test
    void anUnsetPortDisablesTheListener() {
        // The suite runs without SEED_VIEWER_PORT set, which is the unset case.
        assertEquals(0, SeedServer.configuredPort(),
                "an unset SEED_VIEWER_PORT must disable the viewer, not default it on");
    }
}
