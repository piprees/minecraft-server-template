package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half-spaces the opening frames, over the same fixture
 * {@link ProjectionRendererClipTest} uses: a 2x2 opening in the plane
 * {@code z = 0} with the camera at {@code (1, 1, -5)}, so the cone widens by
 * {@code (z + 5) / 5} about {@code x = y = 1}.
 */
class AperturePlanesTest {

    private static final double[] OPENING = {
        0.0, 0.0, 0.0,
        0.0, 2.0, 0.0,
        2.0, 2.0, 0.0,
        2.0, 0.0, 0.0,
    };

    private static final int STRIDE = 4;
    private static final float TOLERANCE = 1.0e-4f;

    @Test
    void oneRectangleBuildsFourPlanes() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);

        assertTrue(planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0));
        assertEquals(4, planes.count());
    }

    @Test
    void aCameraOnACornerDegeneratesAndLeavesNoPlanes() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0);

        assertFalse(planes.build(OPENING.clone(), 1, 0.0, 0.0, 0.0));
        assertEquals(0, planes.count(), "a degenerate build left planes behind");
    }

    @Test
    void aQuadStraddlingTheConeIsCutOnIt() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0);

        // At z = 5 the cone spans -1..3, so the cut lands on x = 3 exactly.
        float[] poly = quad(5.0f, 2.0f, 4.0f, 0.0f, 1.0f);
        int corners = planes.clipAll(poly, 4, new float[STRIDE * AperturePlanes.MAX_POLY]);

        assertEquals(4, corners);
        assertEquals(3.0f, max(poly, corners, 0), TOLERANCE);
        assertEquals(2.0f, min(poly, corners, 0), TOLERANCE);
    }

    @Test
    void aQuadOutsideTheConeIsLeftWithNoCorners() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0);

        assertEquals(0, planes.clipAll(quad(5.0f, 10.0f, 11.0f, 10.0f, 11.0f), 4,
                new float[STRIDE * AperturePlanes.MAX_POLY]));
    }

    /**
     * The cone alone reaches back to the portal surface and no further, because
     * every plane runs through the camera. Destination geometry standing on the
     * camera's side of the surface is inside that cone and must still be cut, or
     * a mob on the near side of the far portal is drawn through the opening.
     */
    @Test
    void theSurfacePlaneCutsWhatStandsShortOfTheOpening() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0);
        float[] shortOfIt = quad(-1.0f, 0.5f, 1.5f, 0.5f, 1.5f);
        assertEquals(4, planes.clipAll(shortOfIt, 4, new float[STRIDE * AperturePlanes.MAX_POLY]),
                "the cone alone already cut it, so this test proves nothing");

        assertTrue(planes.addAxisPlane(2, 0.0, 1.0));
        assertEquals(5, planes.count());

        assertEquals(0, planes.clipAll(quad(-1.0f, 0.5f, 1.5f, 0.5f, 1.5f), 4,
                new float[STRIDE * AperturePlanes.MAX_POLY]));
        assertEquals(4, planes.clipAll(quad(1.0f, 0.5f, 1.5f, 0.5f, 1.5f), 4,
                new float[STRIDE * AperturePlanes.MAX_POLY]),
                "the surface plane cut geometry beyond the opening as well");
    }

    /** The other facing: the destination lies towards lower coordinates. */
    @Test
    void theSurfacePlaneFollowsTheNormalsDirection() {
        AperturePlanes planes = new AperturePlanes(8, STRIDE);
        planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0);
        planes.addAxisPlane(2, 0.0, -1.0);

        assertEquals(0, planes.clipAll(quad(1.0f, 0.5f, 1.5f, 0.5f, 1.5f), 4,
                new float[STRIDE * AperturePlanes.MAX_POLY]));
    }

    /**
     * A set already full keeps what it has rather than overwriting a cone plane
     * — the failure that would silently open one edge of the opening.
     */
    @Test
    void aFullSetRefusesAnotherPlaneRatherThanOverwritingOne() {
        AperturePlanes planes = new AperturePlanes(4, STRIDE);
        assertTrue(planes.build(OPENING.clone(), 1, 1.0, 1.0, -5.0));

        assertFalse(planes.addAxisPlane(2, 0.0, 1.0));
        assertEquals(4, planes.count());
    }

    private static float min(float[] poly, int corners, int axis) {
        float out = Float.MAX_VALUE;
        for (int i = 0; i < corners; i++) {
            out = Math.min(out, poly[i * STRIDE + axis]);
        }
        return out;
    }

    private static float max(float[] poly, int corners, int axis) {
        float out = -Float.MAX_VALUE;
        for (int i = 0; i < corners; i++) {
            out = Math.max(out, poly[i * STRIDE + axis]);
        }
        return out;
    }

    /** One axis-aligned quad at depth {@code z}, four floats per vertex. */
    private static float[] quad(float z, float x0, float x1, float y0, float y1) {
        float[][] corners = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
        float[] data = new float[STRIDE * AperturePlanes.MAX_POLY];
        for (int i = 0; i < 4; i++) {
            data[i * STRIDE] = corners[i][0];
            data[i * STRIDE + 1] = corners[i][1];
            data[i * STRIDE + 2] = z;
            data[i * STRIDE + 3] = 1.0f;
        }
        return data;
    }
}
