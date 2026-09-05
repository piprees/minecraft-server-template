package com.customdimensions.client.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The position a shader pack computes for one of our fragments, inverted from
 * the depth the draw writes for it.
 *
 * <p>Vanilla's near plane at 0.05 and a 70-degree vertical field of view on
 * 16:9, with no camera rotation — so camera-relative space is already view
 * space and a point in front of the eye has a negative Z.
 */
class DepthReconstructionTest {

    private static final Matrix4f PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1024.0f);

    private static final Matrix4f POSITION = new Matrix4f();

    /**
     * The inverse against the forward transform the draw actually uses, rather
     * than against a second derivation of the same arithmetic.
     */
    @Test
    void aPointRoundTripsThroughTheDepthTheDrawWritesForIt() {
        double[] point = {0.4, -0.3, -2.0};
        double windowZ = ProjectionRenderer.windowDepth(POSITION, PROJECTION,
                point[0], point[1], point[2]);
        double[] ndc = ndcOf(point);

        double[] back = DepthReconstruction.unproject(POSITION, PROJECTION,
                ndc[0], ndc[1], windowZ);

        assertArrayEquals(point, back, 1.0e-3,
                "the inverse disagrees with the transform the draw writes depth through");
    }

    /**
     * The defect, as one assertion. Destination terrain forty blocks out, drawn
     * inside a slice pinned to the portal surface two blocks from the eye,
     * reconstructs to the SURFACE. Every screen-space quantity a pack computes
     * from a fragment's depth — its shadow lookup, its fog, the end of its
     * light-shaft ray — is then computed for the doorway rather than for the
     * view through it.
     *
     * <p>The surface point is the terrain point scaled onto the same ray, so
     * the expected answer comes from the fixture and not from the code.
     */
    @Test
    void terrainSeenThroughTheOpeningReconstructsToTheSurfaceInstead() {
        double[] terrain = {6.0, -3.0, -40.0};
        double[] surface = {terrain[0] / 20.0, terrain[1] / 20.0, terrain[2] / 20.0};
        double sliceNear = ProjectionRenderer.windowDepth(POSITION, PROJECTION,
                surface[0], surface[1], surface[2]);

        double[] back = DepthReconstruction.unproject(POSITION, PROJECTION,
                ndcOf(terrain)[0], ndcOf(terrain)[1], sliceNear);

        assertArrayEquals(surface, back, 1.0e-3,
                "a fragment written at the surface's depth did not reconstruct to the surface");
        assertTrue(DepthReconstruction.distance(back)
                        < DepthReconstruction.distance(terrain) / 10.0,
                "the reconstructed distance is not a small fraction of the terrain's own");
    }

    /**
     * A far window depth reconstructs far. This is the whole lever: the same
     * pixel, the same matrices, a depth near 1, and the pack is handed a
     * distance in the tens of blocks instead of two.
     */
    @Test
    void aFarWindowDepthReconstructsFarDownTheSamePixel() {
        double[] terrain = {6.0, -3.0, -40.0};
        double[] ndc = ndcOf(terrain);
        double near = ProjectionRenderer.windowDepth(POSITION, PROJECTION, 0.3, -0.15, -2.0);
        double far = ProjectionRenderer.windowDepth(POSITION, PROJECTION,
                terrain[0], terrain[1], terrain[2]);

        double atNear = DepthReconstruction.distance(
                DepthReconstruction.unproject(POSITION, PROJECTION, ndc[0], ndc[1], near));
        double atFar = DepthReconstruction.distance(
                DepthReconstruction.unproject(POSITION, PROJECTION, ndc[0], ndc[1], far));

        assertEquals(DepthReconstruction.distance(terrain), atFar, 1.0e-2,
                "the far depth did not reconstruct to the terrain's own distance");
        assertTrue(atFar > atNear * 10.0, "the two depths reconstruct to comparable distances");
    }

    /** Four corners about one ray average to that ray. */
    @Test
    void theCentreOfTheOpeningIsTheAverageOfItsCorners() {
        double[] corners = {
            -1.0, -1.0, -3.0,
            -1.0, 1.0, -3.0,
            1.0, 1.0, -3.0,
            1.0, -1.0, -3.0,
        };

        double[] centre = DepthReconstruction.centreNdc(POSITION, PROJECTION, corners,
                0.0, 0.0, 0.0);

        assertArrayEquals(new double[] {0.0, 0.0}, centre, 1.0e-6,
                "the opening's centre is not on the ray through the middle of its corners");
    }

    /**
     * A corner behind the eye is refused rather than folded through the
     * projection's own singularity, which would put the opening's centre
     * somewhere plausible and wrong.
     */
    @Test
    void anOpeningWithACornerBehindTheEyeIsRefused() {
        double[] corners = {
            -1.0, -1.0, -3.0,
            -1.0, 1.0, -3.0,
            1.0, 1.0, -3.0,
            1.0, -1.0, 2.0,
        };

        assertNull(DepthReconstruction.centreNdc(POSITION, PROJECTION, corners, 0.0, 0.0, 0.0),
                "a corner behind the eye was averaged in");
    }

    /** A matrix that cannot be inverted yields no position rather than NaN. */
    @Test
    void aSingularTransformYieldsNoPositionRatherThanNaN() {
        assertNull(DepthReconstruction.unproject(POSITION, new Matrix4f().zero(),
                0.0, 0.0, 0.5));
    }

    /**
     * The far stamp's depth says "a long way off", not "nothing here".
     *
     * <p>Strictly under 1.0, because 1.0 is what a cleared depth buffer holds —
     * the value of a pixel nothing opaque was drawn to, which is how the sky
     * reads — and the portal did draw something. The distance bound is an order
     * of magnitude past the sixteen-block capture volume, which is the reach
     * the stamp exists to escape.
     */
    @Test
    void theFarStampIsShortOfThePlaneAndReconstructsWellBeyondTheVolume() {
        double depth = ProjectionRenderer.FAR_STAMP_DEPTH;

        assertTrue(depth < 1.0,
                "the far stamp writes the value of a pixel nothing was drawn to");

        double[] point = DepthReconstruction.unproject(POSITION, PROJECTION, 0.0, 0.0, depth);

        assertNotNull(point, "the far stamp's depth has no reconstructed position at all");
        assertTrue(DepthReconstruction.distance(point) > 160.0,
                "the far stamp reconstructs within reach of the captured volume");
    }

    /** One point's window coordinates, through the same forward transform. */
    private static double[] ndcOf(double[] point) {
        double[] corners = new double[12];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(point, 0, corners, i * 3, 3);
        }
        return DepthReconstruction.centreNdc(POSITION, PROJECTION, corners, 0.0, 0.0, 0.0);
    }
}
