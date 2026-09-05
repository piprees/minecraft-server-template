package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window the destination is seen through, and where it sits on the normal
 * axis.
 *
 * <p>A cone through the further of two parallel rectangles of the same size is
 * always the narrower, so the far one always binds. The window is the aperture
 * block's two faces — the hole's two real mouths — and the far mouth is the
 * portal surface seen from in front of the frame.
 */
class ProjectionWindowTest {

    private static final double TOLERANCE = 1.0e-9;

    private static final int PLANE = 1500;
    private static final int DEPTH = 24;
    private static final int PAD = 8;

    /**
     * The two clip rectangles are the aperture block's own faces, a whole block
     * apart, whichever direction the opening faces. The destination-side one is
     * the portal surface.
     */
    @Test
    void theWindowIsTheApertureBlocksTwoFaces() {
        for (Direction normal : Direction.values()) {
            ClientProjection projection = projection(normal);
            double[] out = new double[24];
            int faces = ProjectionRenderer.tunnelFaces(projection, projection.origin(),
                    camNormalLocal(projection, normal), out);

            assertEquals(2, faces, normal + ": a camera in front of the opening lost a face");
            double base = ClientProjection.axisOf(projection.origin(), normal.getAxis());
            double[] coords = {
                out[normal.getAxis().ordinal()] + base,
                out[12 + normal.getAxis().ordinal()] + base,
            };
            assertEquals(PLANE, Math.min(coords[0], coords[1]),
                    TOLERANCE, normal + ": the low clip rectangle is not the block's low face");
            assertEquals(PLANE + 1.0, Math.max(coords[0], coords[1]),
                    TOLERANCE, normal + ": the high clip rectangle is not the block's high face");
            assertEquals(projection.planeCoord(),
                    ClientProjection.isPositive(normal)
                            ? Math.max(coords[0], coords[1]) : Math.min(coords[0], coords[1]),
                    TOLERANCE, normal + ": the destination-side rectangle is not the surface");
        }
    }

    /**
     * The emit line's own witness. No pixel measurement separates the clip from
     * the destination's content, so this is the only reading that says which
     * rectangles bound the window: two entries a whole block apart are the
     * aperture block's own faces.
     */
    @Test
    void theEmitLineReportsTheRectanglesTheClipWasBuiltFrom() {
        for (Direction normal : Direction.values()) {
            ClientProjection projection = projection(normal);
            double[] out = new double[24];
            int faces = ProjectionRenderer.tunnelFaces(projection, projection.origin(),
                    camNormalLocal(projection, normal), out);
            String label = ProjectionRenderer.windowLabel(projection, projection.origin(),
                    faces, out);

            assertEquals("[1500.00, 1501.00]", label,
                    normal + ": the emit line names the wrong rectangles");
        }
    }

    /**
     * The consequence, stated in what a viewer sees: an eye off to one side
     * still sees the destination right up to the surface's own edge, because
     * the surface is one of the two clip rectangles rather than something behind
     * them.
     *
     * <p>The probe is a point on the surface, one hundredth of a block inside
     * the opening's high-A edge, seen from an eye three blocks towards low A.
     * The near face's cone is the wider at that depth, so this fails only if
     * the near face binds where the surface should.
     */
    @Test
    void anObliqueEyeSeesTheDestinationUpToTheSurfacesOwnEdge() {
        ClientProjection projection = projection(Direction.SOUTH);
        BlockPos origin = projection.origin();
        double camA = projection.rectMinA() - 3.0 - origin.getX();
        double camB = (projection.rectMinB() + projection.rectMaxB()) / 2.0 - origin.getY();
        double camNormal = camNormalLocal(projection, Direction.SOUTH);

        double[] out = new double[24];
        int faces = ProjectionRenderer.tunnelFaces(projection, origin, camNormal, out);
        assertTrue(ProjectionRenderer.buildTunnelPlanes(out, faces, camA, camB, camNormal),
                "the tunnel degenerated for an eye three blocks off to one side");

        double insideA = projection.rectMaxA() - 0.01 - origin.getX();
        assertTrue(kept(insideA, camB, projection.planeCoord() - origin.getZ(), faces * 4),
                "the window stops short of the surface's own edge");
    }

    /** True when a point survives every plane the last tunnel build wrote. */
    private static boolean kept(double a, double b, double normal, int planes) {
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];
        // A quad flat against the surface, small enough to sit wholly inside or
        // wholly outside: a partial cut would read as kept and prove nothing.
        for (int v = 0; v < 4; v++) {
            int at = v * QuadCapture.STRIDE;
            poly[at] = (float) (a + (v == 1 || v == 2 ? 0.001 : 0.0));
            poly[at + 1] = (float) (b + (v >= 2 ? 0.001 : 0.0));
            poly[at + 2] = (float) normal;
        }
        int count = 4;
        for (int plane = 0; plane < planes && count >= 3; plane++) {
            count = ProjectionRenderer.clip(poly, count, scratch, plane);
            System.arraycopy(scratch, 0, poly, 0, count * QuadCapture.STRIDE);
        }
        return count >= 3;
    }

    /** An eye five blocks in front of the opening, in the volume's own space. */
    private static double camNormalLocal(ClientProjection projection, Direction normal) {
        double base = ClientProjection.axisOf(projection.origin(), normal.getAxis());
        double facing = ClientProjection.isPositive(normal) ? 1.0 : -1.0;
        return projection.planeCoord() - facing * 5.0 - base;
    }

    private static int slabOrigin(Direction normal) {
        return ClientProjection.isPositive(normal) ? PLANE + 1 : PLANE - DEPTH;
    }

    /** The nexus rig's opening, laid out on whichever axis the normal names. */
    private static ClientProjection projection(Direction normal) {
        Direction.Axis normalAxis = normal.getAxis();
        Direction.Axis axisA = normalAxis == Direction.Axis.X
                ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis axisB = normalAxis == Direction.Axis.Z
                ? Direction.Axis.Y : Direction.Axis.Z;

        int aLow = 1500;
        int bLow = 101;
        List<BlockPos> aperture = new ArrayList<>();
        for (int a = aLow; a < aLow + 2; a++) {
            for (int b = bLow; b < bLow + 3; b++) {
                aperture.add(at(normalAxis, PLANE, axisA, a, axisB, b));
            }
        }
        BlockPos origin = at(normalAxis, slabOrigin(normal), axisA, aLow - PAD, axisB, bLow - PAD);
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                axisA.ordinal(), normal.ordinal(),
                origin,
                sizeOn(Direction.Axis.X, normalAxis, axisA),
                sizeOn(Direction.Axis.Y, normalAxis, axisA),
                sizeOn(Direction.Axis.Z, normalAxis, axisA),
                new int[0], new byte[0],
                -1, -1, -1, -1, -1, -1.0f));
    }

    private static int sizeOn(Direction.Axis axis, Direction.Axis normalAxis, Direction.Axis axisA) {
        if (axis == normalAxis) {
            return DEPTH;
        }
        return axis == axisA ? 2 + 2 * PAD : 3 + 2 * PAD;
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
