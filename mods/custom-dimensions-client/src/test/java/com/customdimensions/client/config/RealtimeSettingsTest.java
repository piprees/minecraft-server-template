package com.customdimensions.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeSettingsTest {

    @Test
    void theEnhancedViewIsWhatTheModDoesWithoutBeingAsked() {
        assertTrue(RealtimeSettings.DEFAULTS.renderClientSidePortals(),
                "installing the mod and changing nothing must give the enhanced portal");
        assertTrue(RealtimeSettings.DEFAULTS.renderServerSidePortals(),
                "server-side must already be on, so the slab resumes the moment the local one stops");
        assertFalse(RealtimeSettings.DEFAULTS.effectiveServerSide(),
                "the local render is on, so the server must not also be describing the far side");
    }

    /**
     * The fresh-install case: no config file at all. The store writes the
     * defaults and returns them, so this is what a player gets on first run.
     */
    @Test
    void aClientWithNoConfigFileGetsTheEnhancedPath(@TempDir Path dir) {
        RealtimeSettings first = new RealtimeSettingsStore(
                dir.resolve("customdimensions-client.json")).load();

        assertTrue(first.renderClientSidePortals());
        assertFalse(first.effectiveServerSide());
    }

    @Test
    void clientSideOnMakesTheServerSideSettingIrrelevant() {
        assertFalse(new RealtimeSettings(true, 16, true, true, false, true, true).effectiveServerSide());
        assertFalse(new RealtimeSettings(true, 16, true, false, false, true, true).effectiveServerSide());
    }

    @Test
    void clientSideOffLetsTheStoredServerSideSettingTakeEffect() {
        assertTrue(new RealtimeSettings(false, 16, true, true, false, true, true).effectiveServerSide());
        assertFalse(new RealtimeSettings(false, 16, true, false, false, true, true).effectiveServerSide(),
                "both off is a plain portal, not a preview nobody asked for");
    }

    /** Both opt-outs survive the defaults moving underneath them. */
    @Test
    void aWrittenConfigWinsOverTheDefaultsInBothDirections() {
        RealtimeSettings optedOut = RealtimeSettings.parse(
                "{\"configVersion\":1,\"renderClientSidePortals\":false,"
                        + "\"renderServerSidePortals\":false}");
        assertFalse(optedOut.renderClientSidePortals(), "a player who turned the local render off got it back");
        assertFalse(optedOut.renderServerSidePortals(), "a player who turned the slab off was given it anyway");

        RealtimeSettings optedIn = RealtimeSettings.parse(
                "{\"configVersion\":1,\"renderClientSidePortals\":true,"
                        + "\"renderServerSidePortals\":true}");
        assertTrue(optedIn.renderClientSidePortals());
        assertTrue(optedIn.renderServerSidePortals());
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
        RealtimeSettings written = new RealtimeSettings(true, 24, false, false, false, true, true);
        RealtimeSettings read = RealtimeSettings.parse(written.toJson());
        assertEquals(written, read);
        assertFalse(RealtimeSettings.needsMigration(written.toJson()),
                "what this version writes must not read back as a file to migrate");
    }

    @Test
    void aFileWrittenByAnOlderVersionKeepsTheDefaultsItDoesNotMention() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"configVersion\":1,\"renderClientSidePortals\":true}");
        assertTrue(read.renderClientSidePortals());
        assertEquals(RealtimeSettings.DEFAULTS.maxRenderDistance(), read.maxRenderDistance());
        assertEquals(RealtimeSettings.DEFAULTS.distantHorizons(), read.distantHorizons());
        assertEquals(RealtimeSettings.DEFAULTS.renderServerSidePortals(),
                read.renderServerSidePortals());
    }

    @Test
    void aKeyThisVersionDoesNotKnowIsIgnoredRatherThanFatal() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"configVersion\":1,\"renderClientSidePortals\":true,\"fromTheFuture\":[1,2]}");
        assertTrue(read.renderClientSidePortals());
    }

    @Test
    void aClampedValueOnDiskIsClampedOnTheWayBackIn() {
        assertEquals(RealtimeSettings.MAX_RENDER_DISTANCE,
                RealtimeSettings.parse("{\"configVersion\":1,\"maxRenderDistance\":900}")
                        .maxRenderDistance());
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
        assertFalse(RealtimeSettings.needsMigration("{not json"),
                "a file nobody can read must be left exactly as the player left it");
    }

    @Test
    void aFieldOfTheWrongTypeFallsBackToItsOwnDefaultAndKeepsTheRest() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"configVersion\":1,\"renderClientSidePortals\":\"yes\",\"maxRenderDistance\":24}");
        assertEquals(RealtimeSettings.DEFAULTS.renderClientSidePortals(),
                read.renderClientSidePortals());
        assertEquals(24, read.maxRenderDistance());
    }

    /**
     * The key flips the local render only. Leaving server-side alone is what
     * makes the slab resume on its own rather than needing a special case.
     */
    @Test
    void toggleFlipsTheLocalRenderAndNothingElse() {
        RealtimeSettings on = new RealtimeSettings(false, 24, false, true, false, true, true).toggled();
        assertTrue(on.renderClientSidePortals());
        assertEquals(24, on.maxRenderDistance());
        assertFalse(on.distantHorizons());
        assertTrue(on.renderServerSidePortals(), "the key must not touch the player's slab setting");

        RealtimeSettings off = on.toggled();
        assertFalse(off.renderClientSidePortals());
        assertTrue(off.effectiveServerSide(), "turning the local render off must resume the slab");
    }

    /**
     * Every player who ran the previous version has a file on disk holding
     * {@code enabled} and {@code fallbackToSlab}, so the new defaults reach
     * nobody without this.
     */
    @Test
    void anUnstampedFileIsMigratedFromTheOldFieldNames() {
        assertTrue(RealtimeSettings.needsMigration("{\"enabled\":true,\"fallbackToSlab\":false}"));

        RealtimeSettings migrated = RealtimeSettings.parse(
                "{\"enabled\":true,\"fallbackToSlab\":false,\"maxRenderDistance\":24,"
                        + "\"distantHorizons\":false}");
        assertTrue(migrated.renderClientSidePortals());
        assertEquals(24, migrated.maxRenderDistance());
        assertFalse(migrated.distantHorizons());
        assertTrue(migrated.renderServerSidePortals(),
                "fallbackToSlab was written by a default nobody chose; server-side comes back on");
    }

    /** A deliberate opt-out is a choice; the migration carries it over. */
    @Test
    void aPlayerWhoTurnedTheLocalRenderOffKeepsItOff() {
        RealtimeSettings migrated = RealtimeSettings.parse(
                "{\"enabled\":false,\"fallbackToSlab\":false}");
        assertFalse(migrated.renderClientSidePortals());
        assertTrue(migrated.effectiveServerSide(),
                "an old file with the local render off was served the slab and still must be");
    }

    /**
     * The stamp, not the keys: a player who turns server-side off AFTER
     * migrating must not be migrated back on the next boot.
     */
    @Test
    void aStampedFileIsNeverMigratedAgain() {
        String stamped = "{\"configVersion\":1,\"renderClientSidePortals\":false,"
                + "\"renderServerSidePortals\":false}";
        assertFalse(RealtimeSettings.needsMigration(stamped));
        assertFalse(RealtimeSettings.parse(stamped).renderServerSidePortals());
    }

    /**
     * The second-render path is off unless a file asks for it. On, it calls
     * {@code WorldRenderer.render} twice in one frame and drives a shader
     * pack's whole pipeline a second time.
     */
    @Test
    void theSpectatorPassIsOffByDefault() {
        assertFalse(RealtimeSettings.DEFAULTS.spectatorPass());
        assertFalse(RealtimeSettings.parse("{\"configVersion\": 1}").spectatorPass(),
                "a file that does not mention it turned it on");
    }

    @Test
    void theSpectatorPassRoundTripsThroughTheFile() {
        RealtimeSettings on = RealtimeSettings.DEFAULTS.withSpectatorPass(true);

        assertTrue(RealtimeSettings.parse(on.toJson()).spectatorPass());
        assertEquals(on, RealtimeSettings.parse(on.toJson()));
    }

    /**
     * Setting one field leaves the rest alone. Without this the measurement
     * switch is silently cleared by any other write, which reads as the path
     * having been changed rather than the setting.
     */
    @Test
    void settingOneFieldLeavesTheSpectatorPassWhereItWas() {
        RealtimeSettings on = RealtimeSettings.DEFAULTS.withSpectatorPass(true);

        assertTrue(on.withRenderClientSidePortals(false).spectatorPass());
        assertTrue(on.withMaxRenderDistance(8).spectatorPass());
        assertTrue(on.withDistantHorizons(false).spectatorPass());
        assertTrue(on.withRenderServerSidePortals(false).spectatorPass());
        assertTrue(on.toggled().spectatorPass());
    }
}
