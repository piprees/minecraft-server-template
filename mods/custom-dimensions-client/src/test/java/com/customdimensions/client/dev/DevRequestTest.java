package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One action per call, named by the body's single top-level key. A body that
 * cannot be read must be refused with a reason — the whole point of this
 * surface is that a request either does what it says or explains itself.
 */
class DevRequestTest {

    // --------------------------------------------------------------- actions

    @Test
    void aWalkCarriesItsDistanceAndTimeout() {
        DevRequest request = DevRequest.parse("{\"walk\":{\"blocks\":12,\"timeoutMs\":20000}}");
        assertTrue(request.ok(), request.error());
        assertEquals("walk", request.action());
        assertEquals(12.0, request.number("blocks", 0));
        assertEquals(20000.0, request.number("timeoutMs", 0));
    }

    @Test
    void anAbsentFieldFallsBackToItsDefault() {
        DevRequest request = DevRequest.parse("{\"walk\":{\"blocks\":12}}");
        assertEquals(20000.0, request.number("timeoutMs", 20000));
    }

    @Test
    void anActionWithAnEmptyBodyIsStillAnAction() {
        DevRequest request = DevRequest.parse("{\"use\":{}}");
        assertTrue(request.ok(), request.error());
        assertEquals("use", request.action());
    }

    @Test
    void aLookCarriesYawAndPitch() {
        DevRequest request = DevRequest.parse("{\"look\":{\"yaw\":-179.5,\"pitch\":90}}");
        assertEquals("look", request.action());
        assertEquals(-179.5, request.number("yaw", 0));
        assertEquals(90.0, request.number("pitch", 0));
    }

    @Test
    void aSneakCarriesItsTickCount() {
        DevRequest request = DevRequest.parse("{\"sneak\":{\"ticks\":20}}");
        assertEquals("sneak", request.action());
        assertEquals(20.0, request.number("ticks", 0));
    }

    @Test
    void anActionWhoseValueIsAStringExposesIt() {
        DevRequest request = DevRequest.parse("{\"key\":\"escape\"}");
        assertTrue(request.ok(), request.error());
        assertEquals("key", request.action());
        assertEquals("escape", request.value());
    }

    @Test
    void anActionWhoseValueIsAnObjectHasNoBareValue() {
        assertNull(DevRequest.parse("{\"use\":{}}").value());
    }

    // -------------------------------------------------------------- refusals

    @Test
    void anEmptyBodyIsRefused() {
        DevRequest request = DevRequest.parse("");
        assertFalse(request.ok());
        assertEquals("empty body", request.error());
        assertNull(request.action());
    }

    @Test
    void aNullBodyIsRefused() {
        DevRequest request = DevRequest.parse(null);
        assertFalse(request.ok());
        assertEquals("empty body", request.error());
    }

    @Test
    void anObjectWithNoKeysNamesNoAction() {
        DevRequest request = DevRequest.parse("{}");
        assertFalse(request.ok());
        assertEquals("no action named", request.error());
    }

    @Test
    void twoActionsInOneBodyAreRefusedAndBothAreNamed() {
        DevRequest request = DevRequest.parse("{\"walk\":{},\"look\":{}}");
        assertFalse(request.ok());
        assertEquals("one action per call, got walk, look", request.error());
    }

    @Test
    void anUnknownActionIsRefusedByName() {
        DevRequest request = DevRequest.parse("{\"jump\":{}}");
        assertFalse(request.ok());
        assertEquals("unknown action: jump", request.error());
    }

    @Test
    void aMalformedBodyIsRefusedRatherThanThrown() {
        DevRequest request = DevRequest.parse("{\"walk\": }");
        assertFalse(request.ok());
        assertTrue(request.error().contains("offset"), request.error());
    }

    @Test
    void aTopLevelArrayIsRefused() {
        DevRequest request = DevRequest.parse("[\"walk\"]");
        assertFalse(request.ok());
        assertTrue(request.error().contains("object"), request.error());
    }

    // ------------------------------------------------------------ field types

    /**
     * A number field holding a string is a refusal, not a default — walking 5
     * blocks because the caller typed "twelve" is the silent wrong answer this
     * whole surface exists to stop.
     */
    @Test
    void aNumberFieldHoldingAStringIsRefused() {
        DevRequest request = DevRequest.parse("{\"walk\":{\"blocks\":\"twelve\"}}");
        assertThrows(JsonReader.Malformed.class, () -> request.number("blocks", 5));
    }

    @Test
    void aTextFieldHoldingANumberIsRefused() {
        DevRequest request = DevRequest.parse("{\"key\":{\"name\":7}}");
        assertThrows(JsonReader.Malformed.class, () -> request.text("name", "escape"));
    }

    @Test
    void anAbsentTextFieldFallsBackToItsDefault() {
        DevRequest request = DevRequest.parse("{\"key\":{}}");
        assertEquals("escape", request.text("name", "escape"));
    }

    @Test
    void readingAFieldOfAnActionWithNoObjectBodyFallsBack() {
        DevRequest request = DevRequest.parse("{\"key\":\"escape\"}");
        assertEquals(3.0, request.number("ticks", 3));
    }
}
