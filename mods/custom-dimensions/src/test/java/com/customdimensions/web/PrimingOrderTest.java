package com.customdimensions.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configured seeds are drawn before anything rolls.
 *
 * <p>Both invariants live inside a lambda and a guard clause with no seam to
 * inject, so they are pinned against the source. The failure they guard is
 * silent: high-res renders starve, {@code Picker} then deletes the
 * {@code _high.png} it cannot find a source for, and every card loses its
 * lightbox image with nothing logged.
 */
class PrimingOrderTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "customdimensions", "web");

    private static String read(String name) throws IOException {
        Path p = SRC.resolve(name);
        assertTrue(Files.exists(p), "missing source: " + p);
        return Files.readString(p);
    }

    @Test
    void aDetailMapNeverYieldsWhileTheConfiguredSeedsAreBeingDrawn() throws IOException {
        String src = read("RenderQueue.java");
        assertTrue(src.contains("!PRIMING.get() && THUMBNAILS_PENDING.get() > 0"),
                "the abandon rule must exempt priming, or a priming pass banks a low "
                        + "render for every dimension and a high one for none");
    }

    @Test
    void primingBlocksUntilTheQueueDrains() throws IOException {
        String src = read("RollPipeline.java");
        assertTrue(src.contains("awaitRenders()"),
                "priming must wait for the renders it queued, not just the measurements");
        assertTrue(src.contains("RenderQueue.priming(false)"),
                "the priming flag must be cleared in a finally, or rolls stay locked out");
    }

    @Test
    void aDimensionWithCommittedThumbnailsIsSkipped() throws IOException {
        String src = read("RollPipeline.java");
        assertTrue(src.contains("Picker.thumbnailsPresent("),
                "priming must skip a dimension that already has both thumbnails, or it "
                        + "redraws a rolled pick from the configured seed");
    }

    @Test
    void aThumbnailCommittedInThePlatformRepoCountsAsPresent() throws IOException {
        String picker = read("Picker.java");
        int start = picker.indexOf("public static boolean thumbnailsPresent");
        assertTrue(start > 0, "thumbnailsPresent must exist");
        String body = picker.substring(start, picker.indexOf("\n    }", start));
        assertTrue(body.contains("overlayDimensionsDir()") && body.contains("dir(\"dimensions\")"),
                "both places a pair can be committed must count — a dimension configured "
                        + "only in the platform repo would otherwise be redrawn every prime");
    }

    @Test
    void aPairDrawnUnderADifferentConfigDoesNotCountAsPresent() throws IOException {
        String picker = read("Picker.java");
        int start = picker.indexOf("private static boolean currentPairIn");
        assertTrue(start > 0, "the presence check must compare provenance, not just existence");
        String body = picker.substring(start, picker.indexOf("\n    }", start));
        assertTrue(body.contains("hash.equals(recordedHash("),
                "a slug does not identify a world — InputHash covers the whole config bar "
                        + "the seed, so the same slug and seed draw a different picture "
                        + "across a biome edit or a consumer overlay");
        assertTrue(picker.contains("recordProvenance(overlayDir, dimensionSlug, inputHash, seed)"),
                "pick must stamp too, or a picked pair reads as unstamped and is re-primed");
    }

    @Test
    void primingPublishesBesideTheJsonWithoutClearingAnything() throws IOException {
        assertTrue(read("RollPipeline.java").contains("Picker.exportMissingThumbnails("),
                "priming must publish the renders it drew, or they stay in the bank");
        String picker = read("Picker.java");
        int start = picker.indexOf("public static void exportMissingThumbnails");
        assertTrue(start > 0, "exportMissingThumbnails must exist");
        String body = picker.substring(start, picker.indexOf("\n    }", start));
        assertTrue(body.contains("if (!Files.isRegularFile(low))")
                        && body.contains("if (!Files.isRegularFile(high))"),
                "each size must be written only when absent — writeThumbnail clears a "
                        + "target it has no source for, and a committed PNG is the only copy");
    }

    @Test
    void aRollIsRefusedWhilePrimingIsStillRunning() throws IOException {
        String src = read("RollPipeline.java");
        int gate = src.indexOf("RenderQueue.priming()");
        int running = src.indexOf("RUNNING.compareAndSet(false, true)");
        assertTrue(gate > 0, "start() must check the priming flag");
        assertTrue(running > 0, "start() must still claim the RUNNING flag");
        assertTrue(gate < running,
                "the priming check belongs BEFORE the RUNNING claim, or a refused roll "
                        + "leaves RUNNING set and every later roll is rejected");
    }
}
