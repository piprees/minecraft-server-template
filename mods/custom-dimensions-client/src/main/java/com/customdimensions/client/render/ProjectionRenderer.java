package com.customdimensions.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws each portal's destination through its own opening.
 *
 * <p>Runs after the opaque terrain and before entities, which is the one
 * moment the depth buffer holds the source world and nothing else. Per portal:
 * the backdrop quad opens the aperture (see {@link PortalRenderLayers}), then
 * the meshed destination is drawn through it.
 *
 * <p>The clip is the whole trick, and the opening it clips to is a hole one
 * block deep rather than a plane: four planes run from the camera through the
 * edges of each face of the aperture block, and what survives all eight is the
 * intersection of the two cones. That is what the frame itself does to a
 * sightline, so from beside the frame the two cones are disjoint and nothing is
 * drawn. Every quad, and the backdrop, is cut before it is emitted — nothing is
 * quantised to whole blocks and nothing pops as you walk past.
 */
public final class ProjectionRenderer {

    /** Grepped in the client log for what the emit path did to one portal. */
    public static final String EMIT_MARKER = "companion-client:emit";

    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    /** Emit lines are sampled no more often than this, in milliseconds. */
    private static final long SAMPLE_INTERVAL_MS = 2000L;

    private static final int STRIDE = QuadCapture.STRIDE;

    /** Four corners plus one cut per plane, and the tunnel has eight planes. */
    private static final int MAX_POLY = 16;

    /** How far past the described slab the backdrop sits, in blocks. */
    private static final double BACKDROP_MARGIN = 2.0;

    private static final double[] PLANES = new double[32];
    private static final double[] TUNNEL = new double[24];
    private static final float[] POLY_A = new float[STRIDE * MAX_POLY];
    private static final float[] POLY_B = new float[STRIDE * MAX_POLY];

    /** Planes {@link #buildTunnelPlanes} last wrote into {@link #PLANES}. */
    private static int planeCount;

    private static VertexConsumerProvider.Immediate immediate;
    private static long lastSampleAt;

    /** Vertices the clip left standing in the last {@link #emitClipped}. */
    static int clipVertices;

    /**
     * Quads the last {@link #emitClipped} lost at each plane, indexed the way
     * {@link #apertureCorners} walks the opening: 0 is the low edge on axis A,
     * 1 the high edge on axis B, 2 the high edge on axis A, 3 the low edge on
     * axis B. For an upright portal that reads left, top, right, bottom — so
     * everything landing on 3 means the destination's geometry sits below the
     * line of sight, which is a view, not a fault.
     */
    static final int[] rejectedBy = new int[4];

    private ProjectionRenderer() {}

    public static void render(WorldRenderContext context) {
        if (context.world() == null || ProjectionStore.count() == 0) {
            return;
        }
        // BEFORE_ENTITIES supplies no matrix stack, so the world transform is
        // rebuilt from the position matrix the context always carries.
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            matrices = new MatrixStack();
            matrices.multiplyPositionMatrix(context.positionMatrix());
        }
        Vec3d camera = context.camera().getPos();
        if (immediate == null) {
            immediate = VertexConsumerProvider.immediate(new BufferAllocator(2 * 1024 * 1024));
        }
        // Spent when a line is written, not when one is due: no emit line at
        // all then means drawOne never reached the emit path.
        boolean sample = System.currentTimeMillis() - lastSampleAt >= SAMPLE_INTERVAL_MS;
        for (ClientProjection projection : ProjectionStore.all()) {
            drawOne(projection, matrices, camera, sample);
        }
    }

    private static void drawOne(ClientProjection projection, MatrixStack matrices, Vec3d camera,
            boolean sample) {
        BlockPos origin = projection.origin();
        double camX = camera.x - origin.getX();
        double camY = camera.y - origin.getY();
        double camZ = camera.z - origin.getZ();

        Direction.Axis normalAxis = projection.normalAxis();
        double planeLocal = projection.planeCoord() - axisOf(origin, normalAxis);
        double facing = ClientProjection.isPositive(projection.normal()) ? 1.0 : -1.0;
        double camToPlane = (axisOf(camX, camY, camZ, normalAxis) - planeLocal) * facing;
        // Past the opening, or exactly in its plane: the far side is no longer
        // something seen THROUGH a frame. The server flips the slab within a
        // refresh; until then there is nothing correct to draw.
        if (camToPlane > -0.05) {
            return;
        }

        double[] corners = apertureCorners(projection, origin);
        int faces = tunnelFaces(projection, origin, axisOf(camX, camY, camZ, normalAxis), TUNNEL);
        if (!buildTunnelPlanes(TUNNEL, faces, camX, camY, camZ)) {
            return;
        }

        // Nothing at all for this portal until its mesh lands: a backdrop with no
        // destination behind it is a hole in the world, and waiting for the
        // build here is the frame that never ends.
        ProjectionMesh mesh = projection.meshIfReady();
        if (mesh == null) {
            projection.requestMesh();
            return;
        }

        matrices.push();
        matrices.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        MatrixStack.Entry entry = matrices.peek();

        drawFlat(PortalRenderLayers.BACKDROP, entry, backdropPolygon(projection, TUNNEL,
                camX, camY, camZ, planeLocal, facing, POLY_A, POLY_B));
        double surface = projection.surfaceOffset();
        float shiftX = normalAxis == Direction.Axis.X ? (float) surface : 0.0f;
        float shiftY = normalAxis == Direction.Axis.Y ? (float) surface : 0.0f;
        float shiftZ = normalAxis == Direction.Axis.Z ? (float) surface : 0.0f;
        StringBuilder report = sample ? new StringBuilder() : null;
        for (ProjectionMesh.Layer layer : mesh.layers()) {
            VertexConsumer consumer = immediate.getBuffer(layer.layer());
            int emitted = emitClipped(layer, consumer, entry, shiftX, shiftY, shiftZ);
            immediate.draw(layer.layer());
            if (report != null) {
                report.append(report.isEmpty() ? "" : " | ")
                        .append(layer.layer())
                        .append(" quadsIn=").append(layer.floats() / (STRIDE * 4))
                        .append(" geometry=").append(meshBounds(layer))
                        .append(" highest=").append(highestVertex(layer))
                        .append(" clipVertices=").append(clipVertices)
                        .append(" rejectedBy=").append(java.util.Arrays.toString(rejectedBy))
                        .append(" emitted=").append(emitted)
                        .append(" consumer=").append(consumer.getClass().getName())
                        .append(" drawn=true");
            }
        }
        // Last, so it replaces the destination's own depth: everything vanilla
        // draws after this composites against the window, not against the
        // source-world coordinates the destination borrowed.
        int stamp = drawFlat(PortalRenderLayers.APERTURE_DEPTH, entry,
                aperturePolygon(projection, TUNNEL, camX, camY, camZ, planeLocal, POLY_A, POLY_B));

        // Written after the draws, so a line at all means every draw returned.
        if (report != null) {
            lastSampleAt = System.currentTimeMillis();
            LOGGER.info("{} aperture={} camToPlane={} opening={} volume={} surface={} stamp={} "
                            + "layers={} {}",
                    EMIT_MARKER, projection.apertureOrigin().toShortString(),
                    String.format("%.2f", camToPlane), openingBounds(corners),
                    volumeBounds(projection), String.format("%.2f", surface), stamp,
                    mesh.layers().size(), report);
        }

        matrices.pop();
    }

    /**
     * The opening's four corners at the portal surface, in the volume's own
     * space, walked in order so consecutive pairs are edges.
     */
    static double[] apertureCorners(ClientProjection projection, BlockPos origin) {
        double[] out = new double[12];
        faceCorners(out, 0, projection, origin,
                projection.planeCoord() - axisOf(origin, projection.normalAxis()));
        return out;
    }

    /**
     * The aperture block's faces as clip rectangles, in the volume's own space,
     * low face first. A face the camera has already crossed frames nothing and
     * its cone lies behind the camera, so it is left out.
     */
    static int tunnelFaces(ClientProjection projection, BlockPos origin, double camNormal,
            double[] out) {
        double base = axisOf(origin, projection.normalAxis());
        double facing = ClientProjection.isPositive(projection.normal()) ? 1.0 : -1.0;
        int count = 0;
        for (int face = 0; face < 2; face++) {
            double local = (face == 0 ? projection.apertureMinCoord()
                    : projection.apertureMaxCoord()) - base;
            if ((camNormal - local) * facing >= 0.0) {
                continue;
            }
            faceCorners(out, count * 12, projection, origin, local);
            count++;
        }
        return count;
    }

    private static void faceCorners(double[] out, int at, ClientProjection projection,
            BlockPos origin, double normalCoord) {
        double a0 = projection.rectMinA() - axisOf(origin, projection.axisA());
        double a1 = projection.rectMaxA() - axisOf(origin, projection.axisA());
        double b0 = projection.rectMinB() - axisOf(origin, projection.axisB());
        double b1 = projection.rectMaxB() - axisOf(origin, projection.axisB());
        putCorner(out, at, projection, normalCoord, a0, b0);
        putCorner(out, at + 3, projection, normalCoord, a0, b1);
        putCorner(out, at + 6, projection, normalCoord, a1, b1);
        putCorner(out, at + 9, projection, normalCoord, a1, b0);
    }

    private static void putCorner(double[] out, int at, ClientProjection projection,
            double normalCoord, double a, double b) {
        out[at] = coordOn(Direction.Axis.X, projection, normalCoord, a, b);
        out[at + 1] = coordOn(Direction.Axis.Y, projection, normalCoord, a, b);
        out[at + 2] = coordOn(Direction.Axis.Z, projection, normalCoord, a, b);
    }

    private static double coordOn(Direction.Axis axis, ClientProjection projection,
            double normalCoord, double a, double b) {
        if (axis == projection.normalAxis()) {
            return normalCoord;
        }
        return axis == projection.axisA() ? a : b;
    }

    /**
     * Four planes through the camera and each edge of one rectangle, oriented so
     * that rectangle's own centre is on the kept side. False when the camera is
     * level with the plane of a corner and the frustum degenerates.
     */
    static boolean buildPlanes(double[] corners, double camX, double camY, double camZ) {
        return buildTunnelPlanes(corners, 1, camX, camY, camZ);
    }

    /**
     * The sightline through a hole one block deep: four planes per face of the
     * aperture block, so what survives is the INTERSECTION of the two cones.
     * From beside the frame the two are disjoint and the whole destination is
     * cut, which is what the frame's own block does in the world.
     */
    static boolean buildTunnelPlanes(double[] rects, int count,
            double camX, double camY, double camZ) {
        planeCount = 0;
        if (count <= 0) {
            return false;
        }
        for (int rect = 0; rect < count; rect++) {
            int base = rect * 12;
            double cx = 0.0;
            double cy = 0.0;
            double cz = 0.0;
            for (int i = 0; i < 4; i++) {
                cx += rects[base + i * 3] / 4.0;
                cy += rects[base + i * 3 + 1] / 4.0;
                cz += rects[base + i * 3 + 2] / 4.0;
            }
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                double ax = rects[base + i * 3] - camX;
                double ay = rects[base + i * 3 + 1] - camY;
                double az = rects[base + i * 3 + 2] - camZ;
                double bx = rects[base + j * 3] - camX;
                double by = rects[base + j * 3 + 1] - camY;
                double bz = rects[base + j * 3 + 2] - camZ;
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 1.0e-9) {
                    planeCount = 0;
                    return false;
                }
                nx /= length;
                ny /= length;
                nz /= length;
                double d = -(nx * camX + ny * camY + nz * camZ);
                if (nx * cx + ny * cy + nz * cz + d < 0.0) {
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                    d = -d;
                }
                int at = (rect * 4 + i) * 4;
                PLANES[at] = nx;
                PLANES[at + 1] = ny;
                PLANES[at + 2] = nz;
                PLANES[at + 3] = d;
            }
        }
        planeCount = count * 4;
        return true;
    }

    /**
     * The destination's own sky behind its geometry: a quad on a plane past the
     * far end of the described slab, cut against the same tunnel the mesh is.
     * Returns the corner count left in {@code poly}, 0 for nothing to draw.
     *
     * <p>The clip is not optional. This quad writes depth with the test forced
     * to always pass ({@link PortalRenderLayers}), so uncut it paints the
     * opening's shape over whatever stands in front of the frame.
     */
    static int backdropPolygon(ClientProjection projection, double[] cone,
            double camX, double camY, double camZ, double planeLocal, double facing,
            float[] poly, float[] scratch) {
        int colour = projection.payload().fogColor() >= 0
                ? projection.payload().fogColor()
                : projection.payload().skyColor();
        if (colour < 0) {
            colour = 0;
        }
        double target = planeLocal + facing * (projection.depthExtent() + BACKDROP_MARGIN);
        return projectOntoPlane(projection, cone, camX, camY, camZ, target, colour, poly, scratch);
    }

    /**
     * The opening itself, on the portal surface, cut against the same tunnel.
     * Drawn depth-only through {@link PortalRenderLayers#APERTURE_DEPTH}.
     */
    static int aperturePolygon(ClientProjection projection, double[] cone,
            double camX, double camY, double camZ, double planeLocal,
            float[] poly, float[] scratch) {
        return projectOntoPlane(projection, cone, camX, camY, camZ, planeLocal, 0, poly, scratch);
    }

    /**
     * The cone's first rectangle cast from the camera onto the plane at
     * {@code target} on the normal axis, then cut by every tunnel plane. Returns
     * the corner count left in {@code poly}, 0 for nothing to draw.
     */
    private static int projectOntoPlane(ClientProjection projection, double[] cone,
            double camX, double camY, double camZ, double target, int colour,
            float[] poly, float[] scratch) {
        Direction.Axis axis = projection.normalAxis();
        for (int i = 0; i < 4; i++) {
            double dx = cone[i * 3] - camX;
            double dy = cone[i * 3 + 1] - camY;
            double dz = cone[i * 3 + 2] - camZ;
            double along = axisOf(dx, dy, dz, axis);
            if (Math.abs(along) < 1.0e-5) {
                return 0;
            }
            double t = (target - axisOf(camX, camY, camZ, axis)) / along;
            if (t <= 0.0) {
                return 0;
            }
            int at = i * STRIDE;
            java.util.Arrays.fill(poly, at, at + STRIDE, 0.0f);
            poly[at] = (float) (camX + dx * t);
            poly[at + 1] = (float) (camY + dy * t);
            poly[at + 2] = (float) (camZ + dz * t);
            poly[at + 3] = ((colour >> 16) & 0xFF) / 255.0f;
            poly[at + 4] = ((colour >> 8) & 0xFF) / 255.0f;
            poly[at + 5] = (colour & 0xFF) / 255.0f;
            poly[at + 6] = 1.0f;
        }
        int count = 4;
        for (int plane = 0; plane < planeCount && count >= 3; plane++) {
            count = clip(poly, count, scratch, plane);
            System.arraycopy(scratch, 0, poly, 0, count * STRIDE);
        }
        return count < 3 ? 0 : count;
    }

    /**
     * Draws a clipped polygon left in {@link #POLY_A} on one flat layer and
     * flushes it. Returns the corner count, 0 when there was nothing to draw.
     */
    private static int drawFlat(net.minecraft.client.render.RenderLayer layer,
            MatrixStack.Entry entry, int corners) {
        if (corners < 3) {
            return 0;
        }
        VertexConsumer consumer = immediate.getBuffer(layer);
        if (corners == 4) {
            for (int v = 0; v < 4; v++) {
                emitFlat(consumer, entry, POLY_A, v * STRIDE);
            }
        } else {
            // A fan of degenerate quads renders the polygon without a second
            // draw mode, the way emitClipped does.
            for (int v = 1; v + 1 < corners; v++) {
                emitFlat(consumer, entry, POLY_A, 0);
                emitFlat(consumer, entry, POLY_A, v * STRIDE);
                emitFlat(consumer, entry, POLY_A, (v + 1) * STRIDE);
                emitFlat(consumer, entry, POLY_A, (v + 1) * STRIDE);
            }
        }
        immediate.draw(layer);
        return corners;
    }

    /** Position and colour only: {@link PortalRenderLayers#BACKDROP} takes no more. */
    private static void emitFlat(VertexConsumer consumer, MatrixStack.Entry entry, float[] poly,
            int at) {
        consumer.vertex(entry, poly[at], poly[at + 1], poly[at + 2])
                .color(poly[at + 3], poly[at + 4], poly[at + 5], poly[at + 6]);
    }

    /** The unshifted layer, for a fixture that describes its own geometry. */
    static int emitClipped(ProjectionMesh.Layer layer, VertexConsumer consumer,
            MatrixStack.Entry entry) {
        return emitClipped(layer, consumer, entry, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Clips every quad of one layer against the opening and emits what
     * survives. Returns the vertex count handed to {@code consumer}, and leaves
     * the clip's own survivor count in {@link #clipVertices}.
     *
     * <p>The offset moves the slab onto the portal surface and is applied
     * BEFORE the clip: the cone narrows towards the opening, so geometry drawn
     * half a block closer has to be cut where it now stands or it spills past
     * the frame's edge.
     */
    static int emitClipped(ProjectionMesh.Layer layer, VertexConsumer consumer,
            MatrixStack.Entry entry, float offsetX, float offsetY, float offsetZ) {
        float[] data = layer.data();
        int survived = 0;
        int emitted = 0;
        java.util.Arrays.fill(rejectedBy, 0);
        for (int quad = 0; quad + STRIDE * 4 <= layer.floats(); quad += STRIDE * 4) {
            System.arraycopy(data, quad, POLY_A, 0, STRIDE * 4);
            for (int v = 0; v < 4; v++) {
                POLY_A[v * STRIDE] += offsetX;
                POLY_A[v * STRIDE + 1] += offsetY;
                POLY_A[v * STRIDE + 2] += offsetZ;
            }
            int count = 4;
            for (int plane = 0; plane < planeCount && count >= 3; plane++) {
                count = clip(POLY_A, count, POLY_B, plane);
                System.arraycopy(POLY_B, 0, POLY_A, 0, count * STRIDE);
                if (count < 3) {
                    // Both faces charge the same edge of the opening: which of
                    // the two cut it is not a distinction a viewer can make.
                    rejectedBy[plane & 3]++;
                }
            }
            if (count < 3) {
                continue;
            }
            survived += count;
            if (count == 4) {
                for (int v = 0; v < 4; v++) {
                    emit(consumer, entry, POLY_A, v * STRIDE);
                }
                emitted += 4;
                continue;
            }
            // A clipped polygon has up to MAX_POLY corners; a fan of degenerate
            // quads renders it as triangles without a second draw mode.
            for (int v = 1; v + 1 < count; v++) {
                emit(consumer, entry, POLY_A, 0);
                emit(consumer, entry, POLY_A, v * STRIDE);
                emit(consumer, entry, POLY_A, (v + 1) * STRIDE);
                emit(consumer, entry, POLY_A, (v + 1) * STRIDE);
                emitted += 4;
            }
        }
        clipVertices = survived;
        return emitted;
    }

    /** Sutherland-Hodgman against one plane; returns the new vertex count. */
    static int clip(float[] in, int count, float[] out, int plane) {
        double nx = PLANES[plane * 4];
        double ny = PLANES[plane * 4 + 1];
        double nz = PLANES[plane * 4 + 2];
        double d = PLANES[plane * 4 + 3];
        int written = 0;
        for (int i = 0; i < count; i++) {
            int a = i * STRIDE;
            int b = ((i + 1) % count) * STRIDE;
            double da = nx * in[a] + ny * in[a + 1] + nz * in[a + 2] + d;
            double db = nx * in[b] + ny * in[b + 1] + nz * in[b + 2] + d;
            if (da >= 0.0 && written < MAX_POLY) {
                System.arraycopy(in, a, out, written * STRIDE, STRIDE);
                written++;
            }
            if ((da >= 0.0) != (db >= 0.0) && written < MAX_POLY) {
                float t = (float) (da / (da - db));
                for (int e = 0; e < STRIDE; e++) {
                    out[written * STRIDE + e] = in[a + e] + (in[b + e] - in[a + e]) * t;
                }
                written++;
            }
        }
        return written;
    }

    private static void emit(VertexConsumer consumer, MatrixStack.Entry entry, float[] poly, int at) {
        consumer.vertex(entry, poly[at], poly[at + 1], poly[at + 2])
                .color(poly[at + 3], poly[at + 4], poly[at + 5], poly[at + 6])
                .texture(poly[at + 7], poly[at + 8])
                .overlay((int) poly[at + 9], (int) poly[at + 10])
                .light(((int) poly[at + 11]) << 4, ((int) poly[at + 12]) << 4)
                .normal(entry, poly[at + 13], poly[at + 14], poly[at + 15]);
    }

    /**
     * The opening's own local box. Read against {@link #volumeBounds} in the
     * emit line: an opening outside the volume means the two are not in one
     * coordinate frame, and the clip then discards the whole mesh.
     */
    private static String openingBounds(double[] corners) {
        StringBuilder out = new StringBuilder("[");
        for (int axis = 0; axis < 3; axis++) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                min = Math.min(min, corners[i * 3 + axis]);
                max = Math.max(max, corners[i * 3 + axis]);
            }
            out.append(axis == 0 ? "" : ", ").append(String.format("%.1f..%.1f", min, max));
        }
        return out.append(']').toString();
    }

    /**
     * One layer's own geometry box, in the same local space as
     * {@link #openingBounds}. This is what separates a layer that legitimately
     * clips to nothing — its blocks are not behind the opening — from one whose
     * vertices were written somewhere the opening could never see.
     */
    static String meshBounds(ProjectionMesh.Layer layer) {
        if (layer.floats() < STRIDE) {
            return "[empty]";
        }
        float[] data = layer.data();
        StringBuilder out = new StringBuilder("[");
        for (int axis = 0; axis < 3; axis++) {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (int at = axis; at < layer.floats(); at += STRIDE) {
                min = Math.min(min, data[at]);
                max = Math.max(max, data[at]);
            }
            out.append(axis == 0 ? "" : ", ").append(String.format("%.1f..%.1f", min, max));
        }
        return out.append(']').toString();
    }

    /**
     * Where the layer's highest vertex sits. A geometry box is a union over
     * thousands of quads, so its top can belong to a corner of the volume the
     * opening never sees; this says whether the top is in the middle or at an
     * edge, which the box on its own cannot.
     */
    static String highestVertex(ProjectionMesh.Layer layer) {
        if (layer.floats() < STRIDE) {
            return "[empty]";
        }
        float[] data = layer.data();
        int top = 0;
        for (int at = 0; at < layer.floats(); at += STRIDE) {
            if (data[at + 1] > data[top + 1]) {
                top = at;
            }
        }
        return String.format("[%.1f, %.1f, %.1f]", data[top], data[top + 1], data[top + 2]);
    }

    private static String volumeBounds(ClientProjection projection) {
        return "[0.0.." + projection.sizeX() + ".0, 0.0.." + projection.sizeY()
                + ".0, 0.0.." + projection.sizeZ() + ".0]";
    }

    private static double axisOf(double x, double y, double z, Direction.Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            default:
                return z;
        }
    }

    private static double axisOf(BlockPos pos, Direction.Axis axis) {
        return ClientProjection.axisOf(pos, axis);
    }
}
