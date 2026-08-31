package com.customdimensions.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configured seeds are SCORED before anything rolls, and drawn alongside
 * whatever rolls next.
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
        assertTrue(src.contains("awaitRenders(server, targets)"),
                "priming must wait for the renders it queued, not just the measurements");
        assertTrue(src.contains("exportReady(server, targets)"),
                "the wait must publish as it goes — exporting only at the end leaves "
                        + "nothing behind when the JVM dies part-way through");
        assertTrue(src.contains("RenderQueue.priming(false)"),
                "the priming flag must be cleared in a finally, or rolls stay locked out");
    }

    @Test
    void aRollsPickIsProtectedByTheWriteRuleNotBySkippingWork() throws IOException {
        String src = read("RollPipeline.java");
        assertTrue(!src.contains("if (draw) {"),
                "nothing in the prime may be gated on a committed pair — the map's card "
                        + "and the viewer's card are different artefacts, and one cannot "
                        + "stand in for the other. A pick is protected by "
                        + "exportMissingThumbnails writing only an absent size.");
    }

    @Test
    void everyDimensionIsMeasuredWhateverItsThumbnails() throws IOException {
        String src = read("RollPipeline.java");
        int targets = src.indexOf("List<DimensionConfig> targets = BankView.rollTargets();");
        assertTrue(targets > 0,
                "targets must be every rollable dimension. A committed thumbnail says what a "
                        + "world looks like, never whether it is any good — gating the "
                        + "measurement on one leaves the viewer a wall of pictures with no "
                        + "scores, which is the one thing the page exists to show.");
        int loop = src.indexOf("for (DimensionConfig def : targets) {", targets);
        int measure = src.indexOf("measureNamed(server, def);", loop);
        int reconcile = src.indexOf("RenderQueue.reconcile(server, def);", loop);
        assertTrue(loop > 0 && measure > 0 && reconcile > measure,
                "every target must be measured AND reconciled. The viewer's card is the "
                        + "bank render reconcile queues; the committed pair is the map's "
                        + "card. Gating the draw on a committed pair leaves every viewer "
                        + "card reading \"render queued\" forever.");
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
    void aHalfWrittenPairIsNotStamped() throws IOException {
        String picker = read("Picker.java");
        int start = picker.indexOf("private static void recordProvenance");
        assertTrue(start > 0, "recordProvenance must exist");
        String body = picker.substring(start, picker.indexOf("\n    }", start));
        assertTrue(body.contains("_low.png\"))")
                        && body.contains("_high.png\"))"),
                "the stamp must require BOTH sizes — thumbnailsPresent wants both, so a "
                        + "low-only stamp claims a pair that is not there and reports a "
                        + "run as finished while every lightbox image is still missing");
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
    void aRollRequestedDuringScoringIsQueuedRatherThanRefused() throws IOException {
        String src = read("RollPipeline.java");
        assertTrue(src.contains("private static boolean awaitPriming()"),
                "start() must hand the wait to the roll thread");
        assertTrue(!src.contains("still scoring the configured seeds"),
                "a caller must not have to poll a refusal and guess when to retry");
        int running = src.indexOf("RUNNING.compareAndSet(false, true)");
        int await = src.indexOf("awaitPriming()");
        assertTrue(running > 0 && await > running,
                "the wait belongs AFTER the RUNNING claim and on the worker thread, or "
                        + "the HTTP caller blocks for the whole scoring pass");
    }

    @Test
    void aRollCancelledWhileQueuedReleasesTheRunFlag() throws IOException {
        String src = read("RollPipeline.java");
        int start = src.indexOf("private static boolean awaitPriming()");
        assertTrue(start > 0, "awaitPriming must exist");
        String body = src.substring(start, src.indexOf("\n    }", start));
        assertTrue(body.contains("CANCEL.get()"),
                "the wait must break on stop(), or a queued roll cannot be cancelled");
        assertTrue(src.contains("finishCancelledBeforeStart()")
                        && src.contains("RUNNING.set(false)"),
                "a roll cancelled before it began must clear RUNNING, or every later "
                        + "roll is rejected with 'a roll is already running'");
    }

    @Test
    void drawingTheConfiguredSeedsDoesNotHoldTheRollShut() throws IOException {
        String src = read("RollPipeline.java");
        int cleared = src.indexOf("PRIMING_MEASURE.set(false);\n                awaitRenders(");
        assertTrue(cleared > 0,
                "the measure flag must be cleared BEFORE awaitRenders, or the roll waits on "
                        + "renders it does not need and the reserved render cores idle through "
                        + "the whole screen");
        assertTrue(src.contains("while (!CANCEL.get() && !RUNNING.get())"),
                "awaitRenders must stand down once a roll owns the queue: pending() is the "
                        + "whole queue, so it never reaches zero while candidates are banking");
    }
}
