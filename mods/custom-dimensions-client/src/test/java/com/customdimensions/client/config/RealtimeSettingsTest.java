package com.customdimensions.client.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeSettingsTest {

    @Test
    void theRealtimeViewIsOffUntilItCanRender() {
        assertFalse(RealtimeSettings.DEFAULTS.enabled(),
                "a default-on real-time view would suppress the slab before anything replaces it");
        assertTrue(RealtimeSettings.DEFAULTS.fallbackToSlab(),
                "the fallback is what keeps a portal showing something at all");
    }

    @Test
    void aRenderDistanceOutsideTheRangeIsClampedRatherThanRefused() {
        assertEquals(RealtimeSettings.MIN_RENDER_DISTANCE,
                RealtimeSettings.DEFAULTS.withMaxRenderDistance(0).maxRenderDistance());
        assertEquals(RealtimeSettings.MAX_RENDER_DISTANCE,
                RealtimeSettings.DEFAULTS.withMaxRenderDistance(9999).maxRenderDistance());
        assertEquals(8, RealtimeSettings.DEFAULTS.withMaxRenderDistance(8).maxRenderDistance());
    }

    @Test
    void everyFieldSurvivesAJsonRoundTrip() {
        RealtimeSettings written = new RealtimeSettings(true, 24, false, false);
        RealtimeSettings read = RealtimeSettings.parse(written.toJson());
        assertEquals(written, read);
    }

    @Test
    void aFileWrittenByAnOlderVersionKeepsTheDefaultsItDoesNotMention() {
        RealtimeSettings read = RealtimeSettings.parse("{\"enabled\":true}");
        assertTrue(read.enabled());
        assertEquals(RealtimeSettings.DEFAULTS.maxRenderDistance(), read.maxRenderDistance());
        assertEquals(RealtimeSettings.DEFAULTS.distantHorizons(), read.distantHorizons());
        assertEquals(RealtimeSettings.DEFAULTS.fallbackToSlab(), read.fallbackToSlab());
    }

    @Test
    void aKeyThisVersionDoesNotKnowIsIgnoredRatherThanFatal() {
        RealtimeSettings read = RealtimeSettings.parse("{\"enabled\":true,\"fromTheFuture\":[1,2]}");
        assertTrue(read.enabled());
    }

    @Test
    void aClampedValueOnDiskIsClampedOnTheWayBackIn() {
        assertEquals(RealtimeSettings.MAX_RENDER_DISTANCE,
                RealtimeSettings.parse("{\"maxRenderDistance\":900}").maxRenderDistance());
    }

    /**
     * A config file that will not parse must not stop the mod loading. Every
     * other failure mode here is visible; this one would be a client that
     * silently has no portals at all.
     */
    @Test
    void aMalformedFileFallsBackToTheDefaultsInsteadOfThrowing() {
        assertSame(RealtimeSettings.DEFAULTS, RealtimeSettings.parse("{not json"));
        assertSame(RealtimeSettings.DEFAULTS, RealtimeSettings.parse(""));
        assertSame(RealtimeSettings.DEFAULTS, RealtimeSettings.parse(null));
        assertSame(RealtimeSettings.DEFAULTS, RealtimeSettings.parse("[1,2,3]"));
    }

    @Test
    void aFieldOfTheWrongTypeFallsBackToItsOwnDefaultAndKeepsTheRest() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"enabled\":\"yes\",\"maxRenderDistance\":24}");
        assertEquals(RealtimeSettings.DEFAULTS.enabled(), read.enabled());
        assertEquals(24, read.maxRenderDistance());
    }

    @Test
    void toggleFlipsOnlyTheEnabledFlag() {
        RealtimeSettings on = new RealtimeSettings(false, 24, false, false).toggled();
        assertTrue(on.enabled());
        assertEquals(24, on.maxRenderDistance());
        assertFalse(on.distantHorizons());
        assertFalse(on.fallbackToSlab());
        assertFalse(on.toggled().enabled());
    }
}
