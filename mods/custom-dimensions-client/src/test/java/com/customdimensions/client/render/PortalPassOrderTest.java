package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of one portal pass, recorded.
 *
 * <p>Two invariants live here that nothing else in this module can reach. The
 * stamp must be drawn AFTER the depth range is restored, or {@code
 * glDepthRange} remaps it to the band's far edge and it occludes nothing
 * inside the band. The backdrop and the destination must be drawn INSIDE the
 * range, or the backdrop tests at its own distance twenty-odd blocks behind
 * the plane and loses to everything.
 *
 * <p>Both were previously provable only by screenshot, because expressing
 * either in terms of {@link PortalRenderLayers} means loading a class whose
 * static initialiser needs a bootstrapped client.
 */
class PortalPassOrderTest {

    private static final double[] SLICE = {0.25, 0.75};

    @Test
    void theStampIsDrawnAfterTheRangeIsRestored() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, SLICE);
        assertEquals(List.of(
                "applyDepthRange 0.25 0.75",
                "drawBackdrop",
                "drawDestination",
                "restoreDepthRange",
                "drawStamp"), pass.script);
    }

    @Test
    void theBackdropAndDestinationAreDrawnInsideTheRange() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, SLICE);
        int applied = pass.script.indexOf("applyDepthRange 0.25 0.75");
        int restored = pass.script.indexOf("restoreDepthRange");
        assertTrue(applied >= 0 && restored > applied, "the range was never applied then restored");
        assertTrue(pass.script.indexOf("drawBackdrop") > applied
                        && pass.script.indexOf("drawBackdrop") < restored,
                "the backdrop was drawn outside the applied range: " + pass.script);
        assertTrue(pass.script.indexOf("drawDestination") > applied
                        && pass.script.indexOf("drawDestination") < restored,
                "the destination was drawn outside the applied range: " + pass.script);
    }

    @Test
    void theStampIsDrawnOutsideTheRange() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, SLICE);
        assertTrue(pass.script.indexOf("drawStamp") > pass.script.indexOf("restoreDepthRange"),
                "the stamp was drawn while the range was still applied: " + pass.script);
    }

    /** No slice formed: the pass runs, and nothing touches the depth range at all. */
    @Test
    void noSliceMakesNoRangeCalls() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, null);
        assertEquals(List.of("drawBackdrop", "drawDestination", "drawStamp"), pass.script);
    }

    @Test
    void theStampsCornerCountIsReturned() {
        Recorder pass = new Recorder();
        pass.stampCorners = 4;
        assertEquals(4, ProjectionRenderer.runPass(pass, SLICE));
    }

    /**
     * A raw {@code glDepthRange} is tracked by no RenderSystem cache and reset
     * by no vanilla phase, so a range left applied corrupts every later draw in
     * the frame.
     */
    @Test
    void aThrowingDestinationStillRestoresTheRange() {
        Recorder pass = new Recorder();
        pass.throwFrom = "drawDestination";
        assertThrows(IllegalStateException.class, () -> ProjectionRenderer.runPass(pass, SLICE));
        assertEquals(List.of(
                "applyDepthRange 0.25 0.75",
                "drawBackdrop",
                "drawDestination",
                "restoreDepthRange"), pass.script);
    }

    @Test
    void aThrowingBackdropStillRestoresTheRange() {
        Recorder pass = new Recorder();
        pass.throwFrom = "drawBackdrop";
        assertThrows(IllegalStateException.class, () -> ProjectionRenderer.runPass(pass, SLICE));
        assertEquals(List.of(
                "applyDepthRange 0.25 0.75",
                "drawBackdrop",
                "restoreDepthRange"), pass.script);
    }

    private static final class Recorder implements PortalPass {

        private final List<String> script = new ArrayList<>();
        private int stampCorners;
        private String throwFrom;

        private void record(String call) {
            script.add(call);
            if (call.equals(throwFrom)) {
                throw new IllegalStateException(call + " failed");
            }
        }

        @Override
        public void applyDepthRange(double near, double far) {
            script.add("applyDepthRange " + near + " " + far);
        }

        @Override
        public void restoreDepthRange() {
            script.add("restoreDepthRange");
        }

        @Override
        public void drawBackdrop() {
            record("drawBackdrop");
        }

        @Override
        public void drawDestination() {
            record("drawDestination");
        }

        @Override
        public int drawStamp() {
            record("drawStamp");
            return stampCorners;
        }
    }
}
