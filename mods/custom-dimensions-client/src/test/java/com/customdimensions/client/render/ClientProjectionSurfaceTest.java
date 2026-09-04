package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the destination's near face lands, on every axis and both signs.
 *
 * <p>The shipped rigs are both portal-axis X with the slab running on Z, so
 * they exercise two of the six {@code (axis, sign)} cases. Every fixture here
 * is laid out by {@link #slabOrigin}, which reimplements the server's own rule
 * from {@code ProjectionVolume.computeSourcePositions} — the slab starts one
 * block past the aperture on a positive normal and {@code depth} blocks before
 * it on a negative one. Expected values are read off that layout, so a client
 * that agrees with itself but not with the server still fails.
 */
class ClientProjectionSurfaceTest {

    private static final double TOLERANCE = 1.0e-9;

    /** The aperture block's coordinate on the normal axis, for every fixture. */
    private static final int PLANE = 1500;

    /** Slab depth on the normal axis; distinct from both in-plane spans. */
    private static final int DEPTH = 24;

    /** Lateral padding the server adds around the opening. */
    private static final int PAD = 8;

    @Test
    void theSurfaceBisectsTheApertureBlockOnEveryAxisAndBothSigns() {
        for (Direction normal : Direction.values()) {
            ClientProjection projection = projection(normal);
            assertEquals(PLANE + 0.5, projection.planeCoord(), TOLERANCE,
                    normal + ": the surface is not at the aperture block's mid-plane");
            assertEquals(PLANE, projection.apertureMinCoord(), TOLERANCE,
                    normal + ": the aperture block's low face moved");
            assertEquals(PLANE + 1.0, projection.apertureMaxCoord(), TOLERANCE,
                    normal + ": the aperture block's high face moved");
        }
    }

    /**
     * The invariant the offset exists for, stated without naming a number: the
     * slab's camera-facing end, moved by the offset, is the surface. A sign
     * error on the facing-dependent term satisfies this on one sign and breaks
     * it on the other, which a single-facing assertion cannot see.
     */
    @Test
    void theShiftedSlabsNearFaceLandsOnTheSurfaceOnEveryAxisAndBothSigns() {
        for (Direction normal : Direction.values()) {
            ClientProjection projection = projection(normal);
            assertEquals(projection.planeCoord(),
                    nearFaceWorld(normal, projection) + projection.surfaceOffset(), TOLERANCE,
                    normal + ": the destination's near face missed the portal surface");
        }
    }

    /**
     * Half a block, towards the camera, whichever way the slab runs. The sign
     * follows the normal because the near face is the low end of the slab on a
     * positive normal and the high end on a negative one.
     */
    @Test
    void theOffsetIsHalfABlockTowardsTheCameraOnEveryAxisAndBothSigns() {
        assertEquals(-0.5, projection(Direction.EAST).surfaceOffset(), TOLERANCE);
        assertEquals(0.5, projection(Direction.WEST).surfaceOffset(), TOLERANCE);
        assertEquals(-0.5, projection(Direction.UP).surfaceOffset(), TOLERANCE);
        assertEquals(0.5, projection(Direction.DOWN).surfaceOffset(), TOLERANCE);
        assertEquals(-0.5, projection(Direction.SOUTH).surfaceOffset(), TOLERANCE);
        assertEquals(0.5, projection(Direction.NORTH).surfaceOffset(), TOLERANCE);
    }

    /**
     * The depth the offset reads on a negative normal is the slab's size along
     * the NORMAL, not along whichever axis happens to be biggest. All three
     * fixture sizes differ, so an axis mix-up moves the answer.
     */
    @Test
    void theDepthExtentFollowsTheNormalAxis() {
        for (Direction normal : Direction.values()) {
            assertEquals(DEPTH, projection(normal).depthExtent(),
                    normal + ": the depth extent was read off the wrong axis");
        }
    }

    /** The slab end the camera sees, in world coordinates, before the shift. */
    private static double nearFaceWorld(Direction normal, ClientProjection projection) {
        int originOnNormal = ClientProjection.axisOf(projection.origin(), normal.getAxis());
        return ClientProjection.isPositive(normal)
                ? originOnNormal
                : originOnNormal + projection.depthExtent();
    }

    /**
     * The slab's min corner on the normal axis, by the server's rule: the block
     * past the aperture running forwards, {@code depth} blocks before it
     * running backwards.
     */
    private static int slabOrigin(Direction normal) {
        return ClientProjection.isPositive(normal) ? PLANE + 1 : PLANE - DEPTH;
    }

    /**
     * A 2x3 opening in the plane {@code PLANE} on {@code normal}'s axis, with
     * the slab the server would send for it. In-plane spans are 2 and 3 before
     * padding, so the three sizes are 18, 19 and 24 in some order and no two
     * axes can be confused.
     */
    private static ClientProjection projection(Direction normal) {
        Direction.Axis normalAxis = normal.getAxis();
        Direction.Axis axisA = normalAxis == Direction.Axis.X
                ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis axisB = normalAxis == Direction.Axis.Z
                ? Direction.Axis.Y : Direction.Axis.Z;

        int aLow = 100;
        int bLow = 200;
        List<BlockPos> aperture = new ArrayList<>();
        for (int a = aLow; a < aLow + 2; a++) {
            for (int b = bLow; b < bLow + 3; b++) {
                aperture.add(at(normalAxis, PLANE, axisA, a, axisB, b));
            }
        }

        BlockPos origin = at(normalAxis, slabOrigin(normal), axisA, aLow - PAD, axisB, bLow - PAD);
        int sizeA = 2 + 2 * PAD;
        int sizeB = 3 + 2 * PAD;
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                axisA.ordinal(), normal.ordinal(),
                origin,
                sizeOn(Direction.Axis.X, normalAxis, axisA, axisB, sizeA, sizeB),
                sizeOn(Direction.Axis.Y, normalAxis, axisA, axisB, sizeA, sizeB),
                sizeOn(Direction.Axis.Z, normalAxis, axisA, axisB, sizeA, sizeB),
                new int[0], new byte[0],
                -1, -1, -1, -1, -1));
    }

    private static int sizeOn(Direction.Axis axis, Direction.Axis normalAxis,
            Direction.Axis axisA, Direction.Axis axisB, int sizeA, int sizeB) {
        if (axis == normalAxis) {
            return DEPTH;
        }
        return axis == axisA ? sizeA : sizeB;
    }

    private static BlockPos at(Direction.Axis normalAxis, int normalValue,
            Direction.Axis axisA, int a, Direction.Axis axisB, int b) {
        int[] xyz = new int[3];
        xyz[normalAxis.ordinal()] = normalValue;
        xyz[axisA.ordinal()] = a;
        xyz[axisB.ordinal()] = b;
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }
}
