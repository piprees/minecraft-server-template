package com.customdimensions.client.realtime;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * The opening as a quad on the portal surface, in source-world coordinates.
 *
 * <p>The surface bisects the aperture block, which is the plane
 * {@code ClientProjection.planeCoord} puts it on and where the meshed path
 * already draws — a quad read off a FACE of the block instead sits half a block
 * out and reads as the image being stuck to the frame.
 *
 * <p>Corners are walked so consecutive pairs are edges. The composite layer
 * draws with culling off, so the winding carries no meaning of its own.
 */
public final class CompositeQuad {

    private CompositeQuad() {}

    /** The two axes the opening spans, in X, Y, Z order. */
    public static Direction.Axis axisA(Direction.Axis normal) {
        return normal == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
    }

    public static Direction.Axis axisB(Direction.Axis normal) {
        return normal == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
    }

    /** The surface on the normal axis, from the aperture block's own coordinate. */
    public static double surface(double planeBlock) {
        return planeBlock + 0.5;
    }

    /**
     * {@code {minA, maxA + 1, minB, maxB + 1, planeBlock}} over the aperture
     * cells: the opening's outer rectangle in block units. Null for an empty
     * aperture, which describes no opening at all.
     */
    public static double[] rect(List<BlockPos> aperture, Direction normal) {
        if (aperture == null || aperture.isEmpty()) {
            return null;
        }
        Direction.Axis normalAxis = normal.getAxis();
        Direction.Axis axisA = axisA(normalAxis);
        Direction.Axis axisB = axisB(normalAxis);
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        int plane = 0;
        for (BlockPos cell : aperture) {
            plane = on(cell, normalAxis);
            minA = Math.min(minA, on(cell, axisA));
            maxA = Math.max(maxA, on(cell, axisA));
            minB = Math.min(minB, on(cell, axisB));
            maxB = Math.max(maxB, on(cell, axisB));
        }
        return new double[] {minA, maxA + 1.0, minB, maxB + 1.0, plane};
    }

    /**
     * Four corners in world coordinates, x, y and z each, on the surface.
     * Null when the aperture describes no opening.
     */
    public static double[] corners(List<BlockPos> aperture, Direction normal) {
        double[] rect = rect(aperture, normal);
        if (rect == null) {
            return null;
        }
        Direction.Axis normalAxis = normal.getAxis();
        Direction.Axis axisA = axisA(normalAxis);
        Direction.Axis axisB = axisB(normalAxis);
        double plane = surface(rect[4]);
        double[] out = new double[12];
        putCorner(out, 0, normalAxis, axisA, axisB, plane, rect[0], rect[2]);
        putCorner(out, 3, normalAxis, axisA, axisB, plane, rect[0], rect[3]);
        putCorner(out, 6, normalAxis, axisA, axisB, plane, rect[1], rect[3]);
        putCorner(out, 9, normalAxis, axisA, axisB, plane, rect[1], rect[2]);
        return out;
    }

    /** The opening's own centre on the surface, for the distance test. */
    public static double[] centre(List<BlockPos> aperture, Direction normal) {
        double[] rect = rect(aperture, normal);
        if (rect == null) {
            return null;
        }
        Direction.Axis normalAxis = normal.getAxis();
        double[] out = new double[3];
        putCorner(out, 0, normalAxis, axisA(normalAxis), axisB(normalAxis), surface(rect[4]),
                (rect[0] + rect[1]) / 2.0, (rect[2] + rect[3]) / 2.0);
        return out;
    }

    public static boolean towardsHigh(Direction normal) {
        return normal.getOffsetX() + normal.getOffsetY() + normal.getOffsetZ() > 0;
    }

    /** One coordinate of a point, by axis. */
    public static double on(double x, double y, double z, Direction.Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            default:
                return z;
        }
    }

    private static void putCorner(double[] out, int at, Direction.Axis normalAxis,
            Direction.Axis axisA, Direction.Axis axisB, double plane, double a, double b) {
        out[normalAxis.ordinal() + at] = plane;
        out[axisA.ordinal() + at] = a;
        out[axisB.ordinal() + at] = b;
    }

    private static int on(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            default:
                return pos.getZ();
        }
    }
}
