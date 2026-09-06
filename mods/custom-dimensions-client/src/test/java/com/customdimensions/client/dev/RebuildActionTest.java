package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forcing a box rebuild is an action of its own because the alternative is
 * moving the player out of the portal's activation band and back.
 *
 * <p>That the name is in {@link DevRequest#ACTIONS} is held in
 * {@code RebuildSeamTest}. What is here is the rest of the path: the request
 * parses to the action, and the gate refuses a name it cannot dispatch.
 */
class RebuildActionTest {

    @Test
    void aRebuildRequestWithNoFieldsIsStillAnAction() {
        DevRequest request = DevRequest.parse("{\"rebuild\":{}}");
        assertTrue(request.ok(), request.error());
        assertEquals("rebuild", request.action());
    }

    /** Without this the check above is vacuous: the gate has to refuse something. */
    @Test
    void anActionOutsideTheListIsRefused() {
        DevRequest request = DevRequest.parse("{\"rebulid\":{}}");
        assertFalse(request.ok(), "the gate accepted an action it does not dispatch");
        assertTrue(request.error().contains("rebulid"), request.error());
    }
}
