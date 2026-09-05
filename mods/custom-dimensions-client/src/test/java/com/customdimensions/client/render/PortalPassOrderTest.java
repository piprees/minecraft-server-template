package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *
 * <p>The VALUES are recorded here too. {@code drawOne} needs a client to run,
 * so anything it works out for itself is unreachable: switching the surface
 * offset or the plane coordinate off there used to redden nothing at all.
 * {@link ProjectionRenderer#runPass} derives both from the projection instead,
 * which is what puts them under a test.
 */
class PortalPassOrderTest {

    private static final double[] SLICE = {0.25, 0.75};

    /** The measured portal: 2 wide, 3 tall, plane Z = 1500, slab running +Z. */
    private static final BlockPos ORIGIN = new BlockPos(1492, 93, 1501);
    private static final ClientProjection PROJECTION = projection();

    /**
     * The surface is world {@code z = 1501} and the slab starts at {@code 1501},
     * so in the volume's own space the surface is {@code 0} and the mesh does
     * not move. Read off the fixture: the surface taken from the block's other
     * face is a whole block out, and a mid-plane is half of one.
     */
    @Test
    void thePassIsGivenTheSurfaceAndTheOffsetTheProjectionAsksFor() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);

        assertEquals(List.of(
                "drawBackdrop 0.0",
                "drawDestination 0.0 0.0 0.0",
                "drawStamp 0.0"), pass.values);
    }

    /**
     * The same, with the slab running the other way: the surface is world
     * {@code z = 1500} against a slab starting at {@code 1476}, so it is local
     * {@code 24} and the mesh again does not move.
     */
    @Test
    void thePassIsGivenTheOffsetForASlabRunningTheOtherWay() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, projection(Direction.NORTH, new BlockPos(1492, 93, 1476)),
                new BlockPos(1492, 93, 1476), SLICE, PortalPass.Stage.DESTINATION);

        assertEquals(List.of(
                "drawBackdrop 24.0",
                "drawDestination 0.0 0.0 0.0",
                "drawStamp 24.0"), pass.values);
    }

    /** Nothing moves on the axes the opening spans. */
    @Test
    void theOffsetIsZeroOnBothInPlaneAxes() {
        for (Direction normal : Direction.values()) {
            double[] shift = ProjectionRenderer.meshShift(projection(normal, ORIGIN));
            int axis = normal.getAxis().ordinal();
            for (int i = 0; i < 3; i++) {
                if (i != axis) {
                    assertEquals(0.0, shift[i], normal + ": the offset leaked onto an in-plane axis");
                }
            }
            assertEquals(projection(normal, ORIGIN).surfaceOffset(), shift[axis],
                    normal + ": the offset on the normal axis is not the surface offset");
        }
    }

    @Test
    void theStampIsDrawnAfterTheRangeIsRestored() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);
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
        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);
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
        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);
        assertTrue(pass.script.indexOf("drawStamp") > pass.script.indexOf("restoreDepthRange"),
                "the stamp was drawn while the range was still applied: " + pass.script);
    }

    /** No slice formed: the pass runs, and nothing touches the depth range at all. */
    @Test
    void noSliceMakesNoRangeCalls() {
        Recorder pass = new Recorder();
        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, null, PortalPass.Stage.DESTINATION);
        assertEquals(List.of("drawBackdrop", "drawDestination", "drawStamp"), pass.script);
    }

    @Test
    void theStampsCornerCountIsReturned() {
        Recorder pass = new Recorder();
        pass.stampCorners = 4;
        assertEquals(4, ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION));
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
        assertThrows(IllegalStateException.class, () -> ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION));
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
        assertThrows(IllegalStateException.class, () -> ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION));
        assertEquals(List.of(
                "applyDepthRange 0.25 0.75",
                "drawBackdrop",
                "restoreDepthRange"), pass.script);
    }

    /**
     * The far stamp draws alone. It runs after every draw in the frame that
     * depth-tests, so a backdrop or a destination drawn again there would land
     * on top of the source world's translucents, its particles, its clouds and
     * its weather.
     */
    @Test
    void theFarDepthStageDrawsTheFarStampAndNothingElse() {
        Recorder pass = new Recorder();

        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.FAR_DEPTH);

        assertEquals(List.of("drawFarStamp", "drawDestinationDepth"), pass.script);
    }

    /**
     * No depth range either. The range is restored before the destination stage
     * returns, and applying the slice again would remap the far stamp back into
     * the band it exists to escape.
     */
    @Test
    void theFarDepthStageTouchesTheDepthRangeNotAtAll() {
        Recorder pass = new Recorder();

        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.FAR_DEPTH);

        assertFalse(pass.script.stream().anyMatch(call -> call.startsWith("applyDepthRange")),
                "the far stamp was drawn inside an applied depth range: " + pass.script);
        assertFalse(pass.script.contains("restoreDepthRange"),
                "the far stamp restored a range it never applied: " + pass.script);
    }

    /** Both stamps are cast from the same surface the destination is drawn against. */
    @Test
    void theFarStampIsGivenTheSameSurfaceAsTheNearOne() {
        Recorder near = new Recorder();
        Recorder far = new Recorder();

        ProjectionRenderer.runPass(near, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);
        ProjectionRenderer.runPass(far, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.FAR_DEPTH);

        assertTrue(far.values.contains("drawFarStamp 0.0"),
                "the far stamp is cast from the wrong surface: " + far.values);
        assertTrue(near.values.contains("drawStamp 0.0"),
                "the two stamps are cast from different surfaces: " + near.values);
    }

    /**
     * The near-depth stage draws one stamp and nothing else. It runs at the
     * translucent terrain draw, with the destination already on screen, so
     * anything else it drew would land on top of the frame so far.
     */
    @Test
    void theNearDepthStageDrawsTheSurfaceStampAndNothingElse() {
        Recorder pass = new Recorder();

        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.NEAR_DEPTH);

        assertEquals(List.of("drawStamp"), pass.script);
    }

    /**
     * The far variant of the destination stage changes what the DEPTH BUFFER
     * reports and nothing a viewer sees. Every colour draw is identical and in
     * the same order; only the stamp that closes the pass differs.
     */
    @Test
    void theFarVariantOfTheDestinationStageDiffersOnlyInItsStamp() {
        Recorder near = new Recorder();
        Recorder far = new Recorder();

        ProjectionRenderer.runPass(near, PROJECTION, ORIGIN, SLICE, PortalPass.Stage.DESTINATION);
        ProjectionRenderer.runPass(far, PROJECTION, ORIGIN, SLICE,
                PortalPass.Stage.DESTINATION_FAR);

        assertEquals(colour(near.script), colour(far.script),
                "the two variants draw different colour: " + near.script + " vs " + far.script);
        assertEquals("drawStamp", near.script.get(colour(near.script).size()));
        assertEquals("drawFarStamp", far.script.get(colour(far.script).size()));
    }

    /** Only the two destination stages draw the destination. */
    @Test
    void onlyTheDestinationStagesDrawTheDestination() {
        for (PortalPass.Stage stage : PortalPass.Stage.values()) {
            Recorder pass = new Recorder();
            ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, stage);
            assertEquals(stage.drawsDestination(), pass.script.contains("drawDestination"),
                    stage + ": drawsDestination() disagrees with what the pass drew");
        }
    }

    /**
     * The far stamp writes with an always-pass test, so the destination's own
     * depth has to follow it. Reversed, the stamp erases the per-pixel depth it
     * exists to be the fallback for, and every pixel in the opening reports one
     * distance again.
     */
    @Test
    void theDestinationsOwnDepthIsDrawnAfterTheFarStampInBothFarStages() {
        for (PortalPass.Stage stage :
                List.of(PortalPass.Stage.FAR_DEPTH, PortalPass.Stage.DESTINATION_FAR)) {
            Recorder pass = new Recorder();

            ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, stage);

            int stamp = pass.script.indexOf("drawFarStamp");
            int depth = pass.script.indexOf("drawDestinationDepth");
            assertTrue(stamp >= 0, stage + ": no far stamp was drawn");
            assertTrue(depth > stamp,
                    stage + ": the destination's depth was drawn before the stamp that erases it: "
                            + pass.script);
        }
    }

    /** The depth pass is handed the same offset the destination was drawn with. */
    @Test
    void theDestinationsOwnDepthUsesTheSameOffsetAsItsColour() {
        Recorder pass = new Recorder();

        ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE,
                PortalPass.Stage.DESTINATION_FAR);

        assertTrue(pass.values.contains("drawDestination 0.0 0.0 0.0"), pass.values.toString());
        assertTrue(pass.values.contains("drawDestinationDepth 0.0 0.0 0.0"),
                "the depth pass moved the mesh somewhere the colour pass did not: " + pass.values);
    }

    /** Neither near stage draws it. */
    @Test
    void theNearStagesDoNotDrawTheDestinationsOwnDepth() {
        for (PortalPass.Stage stage :
                List.of(PortalPass.Stage.DESTINATION, PortalPass.Stage.NEAR_DEPTH)) {
            Recorder pass = new Recorder();
            ProjectionRenderer.runPass(pass, PROJECTION, ORIGIN, SLICE, stage);
            assertFalse(pass.script.contains("drawDestinationDepth"),
                    stage + ": drew a far-depth step");
        }
    }

    /** Everything a pass draws before it closes with a stamp. */
    private static List<String> colour(List<String> script) {
        for (int i = 0; i < script.size(); i++) {
            if (script.get(i).equals("drawStamp") || script.get(i).equals("drawFarStamp")) {
                return script.subList(0, i);
            }
        }
        return script;
    }

    private static ClientProjection projection() {
        return projection(Direction.SOUTH, ORIGIN);
    }

    private static ClientProjection projection(Direction normal, BlockPos origin) {
        List<BlockPos> aperture = new ArrayList<>();
        for (int x = 1500; x <= 1501; x++) {
            for (int y = 101; y <= 103; y++) {
                aperture.add(new BlockPos(x, y, 1500));
            }
        }
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                Direction.Axis.X.ordinal(), normal.ordinal(),
                origin, 18, 19, 24,
                new int[0], new byte[0],
                -1, -1, -1, -1, -1));
    }

    private static final class Recorder implements PortalPass {

        /** Call names in order, for the ordering assertions. */
        private final List<String> script = new ArrayList<>();

        /** The same calls with the values they were handed. */
        private final List<String> values = new ArrayList<>();

        private int stampCorners;
        private String throwFrom;

        private void record(String call, String detail) {
            script.add(call);
            values.add(call + " " + detail);
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
        public void drawBackdrop(double planeLocal) {
            record("drawBackdrop", String.valueOf(planeLocal));
        }

        @Override
        public void drawDestination(double shiftX, double shiftY, double shiftZ) {
            record("drawDestination", shiftX + " " + shiftY + " " + shiftZ);
        }

        @Override
        public int drawStamp(double planeLocal) {
            record("drawStamp", String.valueOf(planeLocal));
            return stampCorners;
        }

        @Override
        public int drawFarStamp(double planeLocal) {
            record("drawFarStamp", String.valueOf(planeLocal));
            return stampCorners;
        }

        @Override
        public void drawDestinationDepth(double shiftX, double shiftY, double shiftZ) {
            record("drawDestinationDepth", shiftX + " " + shiftY + " " + shiftZ);
        }
    }
}
