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
 * <p>The clip is the whole trick. Four planes run from the camera through the
 * four edges of the opening; every quad of the destination is cut against them
 * before it is emitted, so the geometry ends exactly at the frame no matter
 * where the camera is. That is per-fragment-accurate in the only sense that
 * matters — nothing is quantised to whole blocks, and nothing pops as you walk
 * past, which is what the server-side mask could never avoid.
 */
public final class ProjectionRenderer {

    /** Grepped in the client log for what the emit path did to one portal. */
    public static final String EMIT_MARKER = "companion-client:emit";

    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    /** Emit lines are sampled no more often than this, in milliseconds. */
    private static final long SAMPLE_INTERVAL_MS = 2000L;

    private static final int STRIDE = QuadCapture.STRIDE;
    private static final int MAX_POLY = 12;

    /** How far past the described slab the backdrop sits, in blocks. */
    private static final double BACKDROP_MARGIN = 2.0;

    private static final double[] PLANES = new double[16];
    private static final float[] POLY_A = new float[STRIDE * MAX_POLY];
    private static final float[] POLY_B = new float[STRIDE * MAX_POLY];

    private static VertexConsumerProvider.Immediate immediate;
    private static long lastSampleAt;

    /** Vertices the clip left standing in the last {@link #emitClipped}. */
    static int clipVertices;

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
        if (!buildPlanes(corners, camX, camY, camZ)) {
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

        drawBackdrop(projection, corners, camX, camY, camZ, planeLocal, facing, entry);
        StringBuilder report = sample ? new StringBuilder() : null;
        for (ProjectionMesh.Layer layer : mesh.layers()) {
            VertexConsumer consumer = immediate.getBuffer(layer.layer());
            int emitted = emitClipped(layer, consumer, entry);
            immediate.draw(layer.layer());
            if (report != null) {
                report.append(report.isEmpty() ? "" : " | ")
                        .append(layer.layer())
                        .append(" quadsIn=").append(layer.floats() / (STRIDE * 4))
                        .append(" clipVertices=").append(clipVertices)
                        .append(" emitted=").append(emitted)
                        .append(" consumer=").append(consumer.getClass().getName())
                        .append(" drawn=true");
            }
        }
        // Written after the draws, so a line at all means every draw returned.
        if (report != null) {
            lastSampleAt = System.currentTimeMillis();
            LOGGER.info("{} aperture={} camToPlane={} opening={} volume={} layers={} {}", EMIT_MARKER,
                    projection.apertureOrigin().toShortString(), String.format("%.2f", camToPlane),
                    openingBounds(corners), volumeBounds(projection), mesh.layers().size(), report);
        }

        matrices.pop();
    }

    /**
     * The opening's four corners, in the volume's own space, walked in order
     * so consecutive pairs are edges.
     */
    static double[] apertureCorners(ClientProjection projection, BlockPos origin) {
        Direction.Axis normalAxis = projection.normalAxis();
        Direction.Axis axisA = projection.axisA();
        Direction.Axis axisB = projection.axisB();
        double n = projection.planeCoord() - axisOf(origin, normalAxis);
        double a0 = projection.rectMinA() - axisOf(origin, axisA);
        double a1 = projection.rectMaxA() - axisOf(origin, axisA);
        double b0 = projection.rectMinB() - axisOf(origin, axisB);
        double b1 = projection.rectMaxB() - axisOf(origin, axisB);

        double[] out = new double[12];
        putCorner(out, 0, projection, n, a0, b0);
        putCorner(out, 1, projection, n, a0, b1);
        putCorner(out, 2, projection, n, a1, b1);
        putCorner(out, 3, projection, n, a1, b0);
        return out;
    }

    private static void putCorner(double[] out, int index, ClientProjection projection,
            double normalCoord, double a, double b) {
        int at = index * 3;
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
     * Four planes through the camera and each edge of the opening, oriented so
     * the opening's own centre is on the kept side. False when the camera is
     * level with the plane of a corner and the frustum degenerates.
     */
    static boolean buildPlanes(double[] corners, double camX, double camY, double camZ) {
        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (int i = 0; i < 4; i++) {
            cx += corners[i * 3] / 4.0;
            cy += corners[i * 3 + 1] / 4.0;
            cz += corners[i * 3 + 2] / 4.0;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            double ax = corners[i * 3] - camX;
            double ay = corners[i * 3 + 1] - camY;
            double az = corners[i * 3 + 2] - camZ;
            double bx = corners[j * 3] - camX;
            double by = corners[j * 3 + 1] - camY;
            double bz = corners[j * 3 + 2] - camZ;
            double nx = ay * bz - az * by;
            double ny = az * bx - ax * bz;
            double nz = ax * by - ay * bx;
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1.0e-9) {
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
            PLANES[i * 4] = nx;
            PLANES[i * 4 + 1] = ny;
            PLANES[i * 4 + 2] = nz;
            PLANES[i * 4 + 3] = d;
        }
        return true;
    }

    /**
     * The destination's own sky behind its geometry, drawn on a plane past the
     * far end of the described slab so it never intersects it.
     */
    private static void drawBackdrop(ClientProjection projection, double[] corners,
            double camX, double camY, double camZ, double planeLocal, double facing,
            MatrixStack.Entry entry) {
        int colour = projection.payload().fogColor() >= 0
                ? projection.payload().fogColor()
                : projection.payload().skyColor();
        if (colour < 0) {
            colour = 0;
        }
        Direction.Axis axis = projection.normalAxis();
        double target = planeLocal + facing * (projection.depthExtent() + BACKDROP_MARGIN);
        double[] far = new double[12];
        for (int i = 0; i < 4; i++) {
            double dx = corners[i * 3] - camX;
            double dy = corners[i * 3 + 1] - camY;
            double dz = corners[i * 3 + 2] - camZ;
            double along = axisOf(dx, dy, dz, axis);
            if (Math.abs(along) < 1.0e-5) {
                return;
            }
            double t = (target - axisOf(camX, camY, camZ, axis)) / along;
            if (t <= 0.0) {
                return;
            }
            far[i * 3] = camX + dx * t;
            far[i * 3 + 1] = camY + dy * t;
            far[i * 3 + 2] = camZ + dz * t;
        }
        int red = (colour >> 16) & 0xFF;
        int green = (colour >> 8) & 0xFF;
        int blue = colour & 0xFF;
        VertexConsumer consumer = immediate.getBuffer(PortalRenderLayers.BACKDROP);
        for (int i = 0; i < 4; i++) {
            consumer.vertex(entry, (float) far[i * 3], (float) far[i * 3 + 1], (float) far[i * 3 + 2])
                    .color(red, green, blue, 255);
        }
        immediate.draw(PortalRenderLayers.BACKDROP);
    }

    /**
     * Clips every quad of one layer against the opening and emits what
     * survives. Returns the vertex count handed to {@code consumer}, and leaves
     * the clip's own survivor count in {@link #clipVertices}.
     */
    static int emitClipped(ProjectionMesh.Layer layer, VertexConsumer consumer,
            MatrixStack.Entry entry) {
        float[] data = layer.data();
        int survived = 0;
        int emitted = 0;
        for (int quad = 0; quad + STRIDE * 4 <= layer.floats(); quad += STRIDE * 4) {
            System.arraycopy(data, quad, POLY_A, 0, STRIDE * 4);
            int count = 4;
            for (int plane = 0; plane < 4 && count >= 3; plane++) {
                count = clip(POLY_A, count, POLY_B, plane);
                System.arraycopy(POLY_B, 0, POLY_A, 0, count * STRIDE);
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
