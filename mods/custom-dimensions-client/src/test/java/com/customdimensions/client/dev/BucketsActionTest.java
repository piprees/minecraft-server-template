package com.customdimensions.client.dev;

import com.customdimensions.client.render.ProjectionRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning bucket rejection off is how the saving is measured: the same pose
 * and the same content, clipped with the rejection and without it. Comparing
 * two eye distances instead changes the content as well, which measures
 * nothing.
 */
class BucketsActionTest {

    @AfterEach
    void restoreTheDefault() {
        ProjectionRenderer.bucketRejection(true);
    }

    /** A dispatch case is dead until the name is in the list. */
    @Test
    void bucketsIsAnAction() {
        assertTrue(DevRequest.ACTIONS.contains("buckets"), DevRequest.ACTIONS.toString());
    }

    @Test
    void aBucketsRequestCarriesItsFlag() {
        DevRequest request = DevRequest.parse("{\"buckets\":{\"reject\":false}}");
        assertTrue(request.ok(), request.error());
        assertEquals("buckets", request.action());
        assertFalse(request.flag("reject", true));
    }

    /** No flag means restore, so a measurement cannot leave it off by omission. */
    @Test
    void aBucketsRequestWithNoFlagRestoresRejection() {
        DevRequest request = DevRequest.parse("{\"buckets\":{}}");
        assertTrue(request.ok(), request.error());
        assertTrue(request.flag("reject", true));
    }

    /** Rejection is on unless a measurement turns it off. */
    @Test
    void rejectionIsOnByDefault() {
        assertTrue(ProjectionRenderer.bucketRejection());
    }

    @Test
    void theSeamTurnsOffAndBackOn() {
        ProjectionRenderer.bucketRejection(false);
        assertFalse(ProjectionRenderer.bucketRejection(), "the seam did not take");
        ProjectionRenderer.bucketRejection(true);
        assertTrue(ProjectionRenderer.bucketRejection());
    }
}
