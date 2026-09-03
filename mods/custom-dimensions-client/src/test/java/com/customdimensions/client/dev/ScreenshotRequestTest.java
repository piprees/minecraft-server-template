package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a screenshot is written. A relative path resolves against the game's
 * working directory, so a caller that asked for {@code shots/before.png} and
 * got a file somewhere inside the Prism instance would read as a success and
 * be unfindable.
 */
class ScreenshotRequestTest {

    @Test
    void anAbsolutePngPathIsAccepted() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"/tmp/e2e/before.png\"}");
        assertTrue(request.ok(), request.error());
        assertEquals("/tmp/e2e/before.png", request.path());
    }

    @Test
    void anUppercaseExtensionIsAccepted() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"/tmp/A.PNG\"}");
        assertTrue(request.ok(), request.error());
        assertEquals("/tmp/A.PNG", request.path());
    }

    @Test
    void aRelativePathIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"shots/before.png\"}");
        assertFalse(request.ok());
        assertEquals("path must be absolute", request.error());
        assertNull(request.path());
    }

    @Test
    void aPathThatIsNotAPngIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"/tmp/before.jpg\"}");
        assertFalse(request.ok());
        assertEquals("path must end in .png", request.error());
    }

    @Test
    void aPathThatIsJustADirectoryIsRefused() {
        assertFalse(ScreenshotRequest.parse("{\"path\":\"/tmp/e2e/\"}").ok());
    }

    @Test
    void aMissingPathIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("{}");
        assertFalse(request.ok());
        assertEquals("no path given", request.error());
    }

    @Test
    void aBlankPathIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"   \"}");
        assertFalse(request.ok());
        assertEquals("no path given", request.error());
    }

    @Test
    void anEmptyBodyIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("");
        assertFalse(request.ok());
        assertEquals("no path given", request.error());
    }

    @Test
    void aMalformedBodyIsRefusedRatherThanThrown() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\": }");
        assertFalse(request.ok());
        assertTrue(request.error().contains("offset"), request.error());
    }

    @Test
    void aPathThatIsNotAStringIsRefused() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":7}");
        assertFalse(request.ok());
        assertTrue(request.error().contains("string"), request.error());
    }

    @Test
    void surroundingWhitespaceIsTrimmedFromThePath() {
        ScreenshotRequest request = ScreenshotRequest.parse("{\"path\":\"  /tmp/a.png \"}");
        assertTrue(request.ok(), request.error());
        assertEquals("/tmp/a.png", request.path());
    }
}
