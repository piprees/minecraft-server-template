package com.customdimensions.client.realtime;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composite quad's geometry.
 *
 * <p>The measured portal: 2 wide, 3 tall, its aperture blocks at z 1500, so
 * the surface is z 1500.5. Every number here is read off that fixture — a
 * corner taken from a FACE of the aperture block instead reads 1500.0 or
 * 1501.0 and puts the image half a block off the opening.
 */
class CompositeQuadTest {

    private static final List<BlockPos> APERTURE = aperture();

    @Test
    void theRectangleSpansTheApertureCells() {
        double[] rect = CompositeQuad.rect(APERTURE, Direction.SOUTH);
        assertArrayEquals(new double[] {1500.0, 1502.0, 101.0, 104.0, 1500.0}, rect, 1.0e-9);
    }

    @Test
    void theSurfaceBisectsTheApertureBlock() {
        assertEquals(1500.5, CompositeQuad.surface(1500.0), 1.0e-9);
    }

    @Test
    void everyCornerSitsOnTheSurface() {
        for (Direction normal : Direction.values()) {
            double[] corners = CompositeQuad.corners(aperture(normal), normal);
            double[] rect = CompositeQuad.rect(aperture(normal), normal);
            double surface = CompositeQuad.surface(rect[4]);
            for (int i = 0; i < 4; i++) {
                assertEquals(surface,
                        CompositeQuad.on(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2],
                                normal.getAxis()),
                        1.0e-9, normal + ": corner " + i + " is off the surface");
            }
        }
    }

    @Test
    void theFourCornersAreTheRectanglesOwn() {
        double[] corners = CompositeQuad.corners(APERTURE, Direction.SOUTH);
        assertArrayEquals(new double[] {
            1500.0, 101.0, 1500.5,
            1500.0, 104.0, 1500.5,
            1502.0, 104.0, 1500.5,
            1502.0, 101.0, 1500.5,
        }, corners, 1.0e-9);
    }

    /** Consecutive pairs are edges: exactly one coordinate moves between them. */
    @Test
    void consecutiveCornersAreEdgesRatherThanDiagonals() {
        double[] corners = CompositeQuad.corners(APERTURE, Direction.SOUTH);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            int moved = 0;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(corners[i * 3 + axis] - corners[j * 3 + axis]) > 1.0e-9) {
                    moved++;
                }
            }
            assertEquals(1, moved, "corners " + i + " and " + j + " are a diagonal, not an edge");
        }
    }

    @Test
    void theCentreIsOnTheSurfaceInTheMiddleOfTheOpening() {
        double[] centre = CompositeQuad.centre(APERTURE, Direction.SOUTH);
        assertArrayEquals(new double[] {1501.0, 102.5, 1500.5}, centre, 1.0e-9);
    }

    @Test
    void anEmptyApertureDescribesNoOpening() {
        assertNull(CompositeQuad.rect(List.of(), Direction.SOUTH));
        assertNull(CompositeQuad.corners(List.of(), Direction.SOUTH));
        assertNull(CompositeQuad.centre(null, Direction.SOUTH));
    }

    @Test
    void theInPlaneAxesAreDerivedFromTheNormal() {
        assertEquals(Direction.Axis.Y, CompositeQuad.axisA(Direction.Axis.X));
        assertEquals(Direction.Axis.Z, CompositeQuad.axisB(Direction.Axis.X));
        assertEquals(Direction.Axis.X, CompositeQuad.axisA(Direction.Axis.Y));
        assertEquals(Direction.Axis.Z, CompositeQuad.axisB(Direction.Axis.Y));
        assertEquals(Direction.Axis.X, CompositeQuad.axisA(Direction.Axis.Z));
        assertEquals(Direction.Axis.Y, CompositeQuad.axisB(Direction.Axis.Z));
    }

    @Test
    void theFacingFollowsTheNormalsOwnSign() {
        assertTrue(CompositeQuad.towardsHigh(Direction.SOUTH));
        assertTrue(CompositeQuad.towardsHigh(Direction.EAST));
        assertTrue(CompositeQuad.towardsHigh(Direction.UP));
        assertFalse(CompositeQuad.towardsHigh(Direction.NORTH));
        assertFalse(CompositeQuad.towardsHigh(Direction.WEST));
        assertFalse(CompositeQuad.towardsHigh(Direction.DOWN));
    }

    private static List<BlockPos> aperture() {
        return aperture(Direction.SOUTH);
    }

    /** The same 2x3 opening, laid in the plane the normal spans. */
    private static List<BlockPos> aperture(Direction normal) {
        List<BlockPos> cells = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 3; b++) {
                int[] at = new int[3];
                at[normal.getAxis().ordinal()] = 1500;
                at[CompositeQuad.axisA(normal.getAxis()).ordinal()] = 1500 + a;
                at[CompositeQuad.axisB(normal.getAxis()).ordinal()] = 101 + b;
                cells.add(new BlockPos(at[0], at[1], at[2]));
            }
        }
        return cells;
    }
}
