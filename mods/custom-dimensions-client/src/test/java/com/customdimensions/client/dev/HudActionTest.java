package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD is an action of its own because F1 is not a key binding — the game
 * handles it in {@code Keyboard.onKey}, so the key-tap path cannot reach it and
 * a screenshot keeps the hand, hotbar, chat and minimap it was meant to lose.
 */
class HudActionTest {

    @Test
    void hudIsAnAction() {
        assertTrue(DevRequest.ACTIONS.contains("hud"), DevRequest.ACTIONS.toString());
    }

    @Test
    void hidingTheHudIsItsOwnActionAndCarriesTheFlag() {
        DevRequest request = DevRequest.parse("{\"hud\":{\"hidden\":true}}");
        assertTrue(request.ok(), request.error());
        assertEquals("hud", request.action());
        assertTrue(request.flag("hidden", false));
    }

    @Test
    void restoringTheHudIsTheSameActionWithTheFlagOff() {
        DevRequest request = DevRequest.parse("{\"hud\":{\"hidden\":false}}");
        assertTrue(request.ok(), request.error());
        assertEquals("hud", request.action());
        assertFalse(request.flag("hidden", true));
    }

    /** No fields is a hide: the caller asking for a HUD action wants a clean frame. */
    @Test
    void aHudActionWithNoFieldsIsStillAnAction() {
        DevRequest request = DevRequest.parse("{\"hud\":{}}");
        assertTrue(request.ok(), request.error());
        assertEquals("hud", request.action());
    }

    @Test
    void aHudFlagHoldingAStringIsRefused() {
        DevRequest request = DevRequest.parse("{\"hud\":{\"hidden\":\"yes\"}}");
        assertThrows(JsonReader.Malformed.class, () -> request.flag("hidden", true));
    }
}
