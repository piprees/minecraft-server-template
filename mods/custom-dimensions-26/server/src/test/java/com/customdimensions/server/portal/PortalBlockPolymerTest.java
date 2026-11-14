package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import org.junit.jupiter.api.Test;

class PortalBlockPolymerTest {
    @Test
    void theTintedStateIsNotMappedBackToString() {
        // polymer-blocks' BlockExtBlockMapper passes a PolymerTexturedBlock's state through;
        // any other block's claimed tripwire state becomes the plain string on the client.
        assertTrue(PolymerTexturedBlock.class.isAssignableFrom(PortalBlock.class));
    }
}
