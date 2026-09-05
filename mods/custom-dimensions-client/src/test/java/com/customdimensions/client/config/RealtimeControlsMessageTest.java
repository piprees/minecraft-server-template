package com.customdimensions.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The chat line the key prints. Three settings states, three keys — a player
 * who turned both off must not be told the server is previewing for them.
 */
class RealtimeControlsMessageTest {

    @Test
    void theLocalRenderNamesItself() {
        assertEquals("message.customdimensionsclient.portal_view_client",
                RealtimeControls.messageKey(new RealtimeSettings(true, 16, true, true, false, true, true)));
        assertEquals("message.customdimensionsclient.portal_view_client",
                RealtimeControls.messageKey(new RealtimeSettings(true, 16, true, false, false, true, true)));
    }

    @Test
    void theSlabNamesItselfOnlyWhenItIsWhatTheServerWillSend() {
        assertEquals("message.customdimensionsclient.portal_view_server",
                RealtimeControls.messageKey(new RealtimeSettings(false, 16, true, true, false, true, true)));
        assertEquals("message.customdimensionsclient.portal_view_none",
                RealtimeControls.messageKey(new RealtimeSettings(false, 16, true, false, false, true, true)));
    }
}
