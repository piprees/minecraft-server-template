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
