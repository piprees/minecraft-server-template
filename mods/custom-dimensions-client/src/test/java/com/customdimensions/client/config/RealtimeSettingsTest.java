package com.customdimensions.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

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
        assertFalse(new RealtimeSettings(true, 16, true, true, false, true, true, false, false, false).effectiveServerSide());
        assertFalse(new RealtimeSettings(true, 16, true, false, false, true, true, false, false, false).effectiveServerSide());
    }

    @Test
    void clientSideOffLetsTheStoredServerSideSettingTakeEffect() {
        assertTrue(new RealtimeSettings(false, 16, true, true, false, true, true, false, false, false).effectiveServerSide());
        assertFalse(new RealtimeSettings(false, 16, true, false, false, true, true, false, false, false).effectiveServerSide(),
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
        RealtimeSettings written = new RealtimeSettings(true, 24, false, false, false, true, true, false, false, false);
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
        RealtimeSettings on = new RealtimeSettings(false, 24, false, true, false, true, true, false, false, false).toggled();
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
        String stamped = "{\"configVersion\":" + RealtimeSettings.CONFIG_VERSION
                + ",\"renderClientSidePortals\":false,\"renderServerSidePortals\":false}";
        assertFalse(RealtimeSettings.needsMigration(stamped));
        assertFalse(RealtimeSettings.parse(stamped).renderServerSidePortals());
    }

    /**
     * The defect this schema exists for: a file that names every key can never
     * be reached by a changed default, because the per-field fallback only
     * fires on an absent key. What the player set is kept; what the writer
     * filled in from a default that has since moved is not.
     */
    @Test
    void aDefaultThatMovedReachesAKeyNobodyChoseAndLeavesTheChosenOneAlone() {
        String below = "{\"configVersion\":" + (RealtimeSettings.CONFIG_VERSION - 1)
                + ",\"apertureTerrain\":false,\"apertureBackdrop\":false,"
                + "\"chosen\":[\"apertureTerrain\"]}";

        RealtimeSettings read = RealtimeSettings.parse(below);

        assertFalse(read.apertureTerrain(),
                "a key the file records as chosen was overwritten by the default");
        assertEquals(RealtimeSettings.DEFAULT_APERTURE_BACKDROP, read.apertureBackdrop(),
                "a key nobody chose kept the default in force when the file was written");
        assertEquals(Set.of("apertureTerrain"), read.chosen(),
                "reading must not invent a choice for a key that only held a default");
    }

    /**
     * A stamped file still wins on a key it names but does not record, so
     * hand-editing the file does what it looks like it does. Only a schema
     * below the current one lets the default through.
     */
    @Test
    void aHandEditedKeyIsHonouredWhileTheStampIsCurrent() {
        String current = "{\"configVersion\":" + RealtimeSettings.CONFIG_VERSION
                + ",\"apertureBackdrop\":false,\"chosen\":[]}";

        assertFalse(RealtimeSettings.parse(current).apertureBackdrop());
    }

    /**
     * A file from before choices were recorded cannot say which of its values
     * anybody picked, so every key it names is read as one. It keeps what the
     * player has at the cost of never following a default again.
     */
    @Test
    void aFileThatRecordsNoChoicesHasEveryKeyItNamesTreatedAsOne() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"configVersion\":1,\"apertureTerrain\":false,\"spectatorPass\":true}");

        assertFalse(read.apertureTerrain());
        assertTrue(read.spectatorPass());
        assertEquals(Set.of("apertureTerrain", "spectatorPass"), read.chosen());
        assertTrue(RealtimeSettings.needsMigration(
                "{\"configVersion\":1,\"apertureTerrain\":false}"),
                "a file below the current schema must be rewritten so the record lands");
    }

    /** Setting a field records it; re-asserting the value it already holds does not. */
    @Test
    void onlyAChangeCountsAsAChoice() {
        assertEquals(Set.of(), RealtimeSettings.DEFAULTS
                .withApertureTerrain(RealtimeSettings.DEFAULT_APERTURE_TERRAIN).chosen(),
                "the dev bridge re-asserts every field on every call, which would pin the lot");

        RealtimeSettings off = RealtimeSettings.DEFAULTS.withApertureTerrain(false);
        assertEquals(Set.of("apertureTerrain"), off.chosen());
        assertEquals(Set.of("apertureTerrain"), off.withApertureTerrain(true).chosen(),
                "turning it back on is still a choice, and stays recorded as one");
    }

    /** What was chosen survives the file, or the next schema forgets it. */
    @Test
    void theChosenKeysRoundTripThroughTheFile() {
        RealtimeSettings set = RealtimeSettings.DEFAULTS
                .withSpectatorPass(true)
                .withMaxRenderDistance(24);

        assertEquals(set, RealtimeSettings.parse(set.toJson()));
        assertEquals(Set.of("spectatorPass", "maxRenderDistance"),
                RealtimeSettings.parse(set.toJson()).chosen());
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

    /**
     * The three depth stages ship on, and ship together: the flat stamps on
     * their own read below the unstamped baseline and the mesh depth is what
     * repairs them, so any one of the three going off is a regression.
     */
    @Test
    void theThreeDepthStagesShipOnAndShipTogether() {
        assertTrue(RealtimeSettings.DEFAULT_APERTURE_FAR_STAMP);
        assertTrue(RealtimeSettings.DEFAULT_APERTURE_FAR_STAMP_EARLY);
        assertTrue(RealtimeSettings.DEFAULT_APERTURE_MESH_DEPTH);

        assertTrue(RealtimeSettings.DEFAULTS.apertureFarStamp(),
                "the composite passes must read the far end of the captured volume");
        assertTrue(RealtimeSettings.DEFAULTS.apertureFarStampEarly(),
                "the deferred programs must read it too, and they read earlier");
        assertTrue(RealtimeSettings.DEFAULTS.apertureMeshDepth(),
                "without the per-pixel depth the flat stamps fog the near field");
    }

    /** A file from before these keys existed gets them, same as a fresh install. */
    @Test
    void aFileThatDoesNotMentionTheDepthStagesGetsThemOn() {
        RealtimeSettings stamped = RealtimeSettings.parse("{\"configVersion\":1}");
        assertTrue(stamped.apertureFarStamp());
        assertTrue(stamped.apertureFarStampEarly());
        assertTrue(stamped.apertureMeshDepth());

        RealtimeSettings migrated = RealtimeSettings.parse("{\"enabled\":true}");
        assertTrue(migrated.apertureFarStamp());
        assertTrue(migrated.apertureFarStampEarly());
        assertTrue(migrated.apertureMeshDepth());
    }

    /** Turning one off is a choice, and it survives the defaults moving. */
    @Test
    void aPlayerWhoTurnedADepthStageOffKeepsItOff() {
        RealtimeSettings read = RealtimeSettings.parse(
                "{\"configVersion\":1,\"apertureFarStamp\":false,"
                        + "\"apertureFarStampEarly\":false,\"apertureMeshDepth\":false}");
        assertFalse(read.apertureFarStamp());
        assertFalse(read.apertureFarStampEarly());
        assertFalse(read.apertureMeshDepth());

        RealtimeSettings off = RealtimeSettings.DEFAULTS.withApertureMeshDepth(false);
        assertFalse(RealtimeSettings.parse(off.toJson()).apertureMeshDepth());
        assertTrue(off.apertureFarStamp(), "setting one stage must not clear another");
    }

    /**
     * The gain is an attenuation and nothing else: above 1 it can only make a
     * saturating backdrop worse, and 1 is the value that changes no pixel.
     */
    @Test
    void theBackdropGainDefaultsToNoChangeAndClampsToZeroOne() {
        assertEquals(1.0, RealtimeSettings.DEFAULTS.apertureBackdropGain(), 0.0);
        assertEquals(1.0,
                RealtimeSettings.DEFAULTS.withApertureBackdropGain(4.0).apertureBackdropGain(),
                0.0);
        assertEquals(0.0,
                RealtimeSettings.DEFAULTS.withApertureBackdropGain(-2.0).apertureBackdropGain(),
                0.0);
        assertEquals(0.35,
                RealtimeSettings.DEFAULTS.withApertureBackdropGain(0.35).apertureBackdropGain(),
                1.0e-9);
    }

    @Test
    void theBackdropGainSurvivesAWriteAndARead() {
        RealtimeSettings chosen = RealtimeSettings.DEFAULTS.withApertureBackdropGain(0.4);
        assertEquals(0.4, RealtimeSettings.parse(chosen.toJson()).apertureBackdropGain(), 1.0e-9);
    }

    @Test
    void aFileThatNamesNoGainTakesTheDefault() {
        assertEquals(1.0,
                RealtimeSettings.parse("{\"configVersion\":1}").apertureBackdropGain(), 0.0);
    }

    /**
     * One flip must not move the other half: unshaded terrain is shippable on
     * its own, the backdrop carries a trade that terrain does not.
     */
    @Test
    void theTwoUnshadedHalvesAreIndependent() {
        RealtimeSettings terrain = RealtimeSettings.DEFAULTS.withApertureUnshadedDestination(true);
        assertTrue(terrain.apertureUnshadedDestination());
        assertFalse(terrain.apertureUnshadedBackdrop());

        RealtimeSettings backdrop = RealtimeSettings.DEFAULTS.withApertureUnshadedBackdrop(true);
        assertTrue(backdrop.apertureUnshadedBackdrop());
        assertFalse(backdrop.apertureUnshadedDestination());
    }

    @Test
    void bothUnshadedHalvesDefaultOffAndSurviveAWriteAndARead() {
        assertFalse(RealtimeSettings.DEFAULTS.apertureUnshadedBackdrop());
        RealtimeSettings both = RealtimeSettings.DEFAULTS
                .withApertureUnshadedDestination(true).withApertureUnshadedBackdrop(true);
        RealtimeSettings read = RealtimeSettings.parse(both.toJson());
        assertTrue(read.apertureUnshadedDestination());
        assertTrue(read.apertureUnshadedBackdrop());
    }
}
