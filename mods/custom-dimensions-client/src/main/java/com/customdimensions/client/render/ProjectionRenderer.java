package com.customdimensions.client.render;

import com.customdimensions.client.Repeated;
import com.customdimensions.client.config.RealtimeControls;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws each portal's destination through its own opening.
 *
 * <p>Runs at {@code WorldRenderEvents.BEFORE_BLOCK_OUTLINE}, bytecode 1952 of
 * {@code WorldRenderer.render}: every opaque draw of the source world is in the
 * depth buffer and nothing translucent has been written. Per portal: the
 * backdrop quad opens the aperture (see {@link PortalRenderLayers}), then the
 * meshed destination is drawn through it.
 *
 * <p>The clip is the whole trick, and the opening it clips to is a hole HALF a
 * block deep rather than a plane: four planes run from the camera through the
 * edges of the aperture block's near face and four more through the portal
 * surface that bisects it, and what survives all eight is the intersection of
 * the two cones. The frame's material only reaches as far as the surface —
 * past that a ray is already in the destination — so from beside the frame the
 * two cones are disjoint and nothing is drawn. Every quad, and the backdrop, is
 * cut before it is emitted — nothing is quantised to whole blocks and nothing
 * pops as you walk past.
 */
public final class ProjectionRenderer {

    /** Grepped in the client log for what the emit path did to one portal. */
    public static final String EMIT_MARKER = "companion-client:emit";

    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    /** Emit lines are sampled no more often than this, in milliseconds. */
    private static final long SAMPLE_INTERVAL_MS = 2000L;

    private static final int STRIDE = QuadCapture.STRIDE;

    /** Four corners plus one cut per plane, and the tunnel has eight planes. */
    private static final int MAX_POLY = AperturePlanes.MAX_POLY;

    /** How far past the described slab the backdrop sits, in blocks. */
    private static final double BACKDROP_MARGIN = 2.0;

    /**
     * How much of the half block behind the surface the destination is
     * compressed into. Short of the whole of it so the backdrop still wins
     * against source terrain starting at the aperture block's far face.
     */
    private static final double SLICE_FRACTION = 0.9;

    /** The two clip rectangles' eight planes, shared with the actor draw. */
    private static final AperturePlanes PLANES = new AperturePlanes(8, STRIDE);

    private static final double[] TUNNEL = new double[24];
    private static final float[] POLY_A = new float[STRIDE * MAX_POLY];
    private static final float[] POLY_B = new float[STRIDE * MAX_POLY];

    /** The one place that sets the colour mask; see {@link #withColourMaskOff}. */
    private static final java.util.function.Consumer<Boolean> COLOUR_MASK =
            on -> com.mojang.blaze3d.systems.RenderSystem.colorMask(on, on, on, on);

    private static VertexConsumerProvider.Immediate immediate;
    private static long lastSampleAt;

    /**
     * The pass's own cost since the last emit line. Frame rate cannot measure
     * it — two viewpoints render different scenes, and that difference is
     * larger than anything this pass contributes.
     */
    private static int spanFrames;
    private static long spanNanos;
    private static long spanPeakNanos;

    /** Portal draws the frustum gate skipped since the last emit line. */
    private static int spanGated;

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
        // Vanilla's own camera-relative stack, empty at this phase — the
        // position matrix is already on the model-view stack, so multiplying it
        // in here would rotate the far side twice.
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        if (immediate == null) {
            immediate = VertexConsumerProvider.immediate(new BufferAllocator(2 * 1024 * 1024));
        }
        // Spent when a line is written, not when one is due: no emit line at
        // all then means drawOne never reached the emit path.
        Matrix4f position = context.positionMatrix();
        Matrix4f projectionMatrix = context.projectionMatrix();
        boolean sample = System.currentTimeMillis() - lastSampleAt >= SAMPLE_INTERVAL_MS;
        long startedAt = System.nanoTime();
        net.minecraft.client.render.Frustum frustum = context.frustum();
        for (ClientProjection projection : ProjectionStore.all()) {
            // Asked for BEFORE the gate: the build is off-thread and costs this
            // frame nothing, and a portal first seen with no mesh draws an empty
            // frame. Gating the request would make every portal's first sight
            // blank.
            projection.requestMesh();
            // Everything drawn for a portal is cut to its aperture cone, so an
            // aperture off screen can contribute no pixel. Without this the whole
            // clip runs for a portal behind the camera.
            if (frustum != null && !frustum.isVisible(apertureBox(projection))) {
                spanGated++;
                continue;
            }
            drawOne(projection, matrices, camera, position, projectionMatrix, sample,
                    context.tickCounter().getTickDelta(true));
        }
        long elapsed = System.nanoTime() - startedAt;
        spanFrames++;
        spanNanos += elapsed;
        spanPeakNanos = Math.max(spanPeakNanos, elapsed);
    }

    /**
     * Mean and peak microseconds per frame over one span, as
     * {@code mean/peak}. Reported on the emit line: read it against the same
     * figure from another stance, which is the only comparison free of what
     * the rest of the scene happens to cost.
     */
    static String costSummary(int frames, long totalNanos, long peakNanos) {
        if (frames <= 0) {
            return "n/a";
        }
        return String.format("%.0f/%.0f", totalNanos / 1000.0 / frames, peakNanos / 1000.0);
    }

    private static void drawOne(ClientProjection projection, MatrixStack matrices, Vec3d camera,
            Matrix4f position, Matrix4f projectionMatrix, boolean sample, float tickDelta) {
        BlockPos origin = projection.origin();
        double camX = camera.x - origin.getX();
        double camY = camera.y - origin.getY();
        double camZ = camera.z - origin.getZ();

        Direction.Axis normalAxis = projection.normalAxis();
        double planeLocal = planeLocal(projection, origin);
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
        String window = windowLabel(projection, origin, faces, TUNNEL);

        // Nothing at all for this portal until its mesh lands: a backdrop with no
        // destination behind it is a hole in the world, and waiting for the
        // build here is the frame that never ends. The request is made in
        // render(), ahead of the gate.
        ProjectionMesh mesh = projection.meshIfReady();
        if (mesh == null) {
            return;
        }

        matrices.push();
        matrices.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        MatrixStack.Entry entry = matrices.peek();

        double[] slice = sliceFor(projection, corners, camX, camY, camZ, facing,
                position, projectionMatrix);

        double surface = projection.surfaceOffset();
        StringBuilder report = sample ? new StringBuilder() : null;
        int stamp = runPass(new PortalPass() {
            @Override
            public void applyDepthRange(double near, double far) {
                GL11.glDepthRange(near, far);
            }

            @Override
            public void restoreDepthRange() {
                GL11.glDepthRange(0.0, 1.0);
            }

            @Override
            public void drawBackdrop(double surfaceLocal) {
                if (!RealtimeControls.settings().apertureBackdrop()) {
                    return;
                }
                drawFlat(PortalRenderLayers.BACKDROP, entry, backdropPolygon(projection, TUNNEL,
                        camX, camY, camZ, surfaceLocal, facing, POLY_A, POLY_B), false);
            }

            @Override
            public void drawDestination(double shiftX, double shiftY, double shiftZ) {
                for (ProjectionMesh.Layer layer :
                        RealtimeControls.settings().apertureTerrain()
                                ? mesh.layers() : java.util.List.<ProjectionMesh.Layer>of()) {
                    VertexConsumer consumer = immediate.getBuffer(layer.layer());
                    int emitted = emitClipped(layer, consumer, entry,
                            (float) shiftX, (float) shiftY, (float) shiftZ);
                    immediate.draw(layer.layer());
                    if (report != null) {
                        report.append(report.isEmpty() ? "" : " | ")
                                .append(layer.layer())
                                .append(" quadsIn=").append(layer.floats() / (STRIDE * 4))
                                .append(" light=[")
                                .append(LightFacts.ofVertices(layer.data(), layer.floats(), STRIDE)
                                        .label())
                                .append(']')
                                .append(" geometry=").append(meshBounds(layer))
                                .append(" highest=").append(highestVertex(layer))
                                .append(" clipVertices=").append(clipVertices)
                                .append(" rejectedBy=").append(java.util.Arrays.toString(rejectedBy))
                                .append(" emitted=").append(emitted)
                                .append(" consumer=").append(consumer.getClass().getName())
                                .append(" drawn=true");
                    }
                }
                // After the terrain and inside the same slice: an actor tests at
                // the window's own depth, against the ground it is standing on.
                DestinationActors.draw(projection, matrices, immediate, TUNNEL, faces,
                        camX, camY, camZ, tickDelta);
            }

            @Override
            public int drawStamp(double surfaceLocal) {
                return drawFlat(PortalRenderLayers.APERTURE_DEPTH, entry,
                        aperturePolygon(projection, TUNNEL, camX, camY, camZ, surfaceLocal,
                                POLY_A, POLY_B), true);
            }
        }, projection, origin, slice);

        // Written after the draws, so a line at all means every draw returned.
        if (report != null) {
            boolean firstEmit = lastSampleAt == 0L;
            lastSampleAt = System.currentTimeMillis();
            Repeated.log(LOGGER, firstEmit,
                    "{} aperture={} camToPlane={} opening={} window={} volume={} surface={} "
                            + "stamp={} slice={} frames={} gated={} renderUs={} layers={} {}",
                    EMIT_MARKER, projection.apertureOrigin().toShortString(),
                    String.format("%.2f", camToPlane), openingBounds(corners), window,
                    volumeBounds(projection), String.format("%.2f", surface), stamp,
                    sliceLabel(slice), spanFrames, spanGated,
                    costSummary(spanFrames, spanNanos, spanPeakNanos),
                    mesh.layers().size(), DestinationActors.summary() + " " + report);
            spanFrames = 0;
            spanNanos = 0;
            spanPeakNanos = 0;
            spanGated = 0;
        }

        matrices.pop();
    }

    /**
     * The aperture block itself, in WORLD space — the opening's rectangle on
     * the two in-plane axes, one block deep on the normal. Everything a portal
     * draws is clipped to the cone through this box, so it is the whole of what
     * can reach the screen.
     */
    static net.minecraft.util.math.Box apertureBox(ClientProjection projection) {
        double minN = projection.apertureMinCoord();
        double maxN = projection.apertureMaxCoord();
        return new net.minecraft.util.math.Box(
                coordOn(Direction.Axis.X, projection, minN,
                        projection.rectMinA(), projection.rectMinB()),
                coordOn(Direction.Axis.Y, projection, minN,
                        projection.rectMinA(), projection.rectMinB()),
                coordOn(Direction.Axis.Z, projection, minN,
                        projection.rectMinA(), projection.rectMinB()),
                coordOn(Direction.Axis.X, projection, maxN,
                        projection.rectMaxA(), projection.rectMaxB()),
                coordOn(Direction.Axis.Y, projection, maxN,
                        projection.rectMaxA(), projection.rectMaxB()),
                coordOn(Direction.Axis.Z, projection, maxN,
                        projection.rectMaxA(), projection.rectMaxB()));
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
     * The clip rectangles bounding the sightline, in the volume's own space,
     * low one first: the aperture block's near face and the portal surface. A
     * face the camera has already crossed frames nothing and its cone lies
     * behind the camera, so it is left out.
     *
     * <p>The block's FAR face is not one of them. The surface has no thickness
     * and everything past it is the destination, so the far half of the block
     * frames nothing. Clipping on it puts the window half a block behind the
     * surface — and because the further of two equal rectangles always subtends
     * the narrower cone, that face binds at every angle, from either side. The
     * image then reads as sitting against a face of the block rather than in
     * its middle.
     */
    static int tunnelFaces(ClientProjection projection, BlockPos origin, double camNormal,
            double[] out) {
        double base = axisOf(origin, projection.normalAxis());
        double facing = ClientProjection.isPositive(projection.normal()) ? 1.0 : -1.0;
        int count = 0;
        for (int face = 0; face < 2; face++) {
            double coord = face == 0 ? projection.apertureMinCoord() : projection.apertureMaxCoord();
            if ((coord - projection.planeCoord()) * facing > 0.0) {
                coord = projection.planeCoord();
            }
            double local = coord - base;
            if ((camNormal - local) * facing >= 0.0) {
                continue;
            }
            faceCorners(out, count * 12, projection, origin, local);
            count++;
        }
        return count;
    }

    /**
     * Where on the normal axis each clip rectangle sits, in WORLD coordinates,
     * as {@code [near, far]}.
     *
     * <p>Read out of the array the clip was built from rather than recomputed,
     * so a rectangle placed on the wrong coordinate prints the wrong number
     * instead of agreeing with itself. It is the only witness to which
     * rectangles bound the window: the aperture block's own faces read half a
     * block apart, the near face and the surface read a quarter of that.
     */
    static String windowLabel(ClientProjection projection, BlockPos origin, int faces,
            double[] cone) {
        if (faces <= 0) {
            return "none";
        }
        double base = axisOf(origin, projection.normalAxis());
        int axis = projection.normalAxis().ordinal();
        StringBuilder out = new StringBuilder("[");
        for (int face = 0; face < faces; face++) {
            out.append(face == 0 ? "" : ", ")
                    .append(String.format("%.2f", cone[face * 12 + axis] + base));
        }
        return out.append(']').toString();
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
     * The sightline through a hole half a block deep: four planes per clip
     * rectangle, so what survives is the INTERSECTION of the two cones. From
     * beside the frame the two are disjoint and the whole destination is cut,
     * which is what the frame's own block does to a ray before it reaches the
     * surface.
     */
    static boolean buildTunnelPlanes(double[] rects, int count,
            double camX, double camY, double camZ) {
        return PLANES.build(rects, count, camX, camY, camZ);
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
        for (int plane = 0; plane < PLANES.count() && count >= 3; plane++) {
            count = clip(poly, count, scratch, plane);
            System.arraycopy(scratch, 0, poly, 0, count * STRIDE);
        }
        return count < 3 ? 0 : count;
    }

    /**
     * Runs a depth-only draw with the colour mask off. {@code mask} takes the
     * write flag; the restore has to survive a throw from inside the draw,
     * because {@code RenderLayer.draw} has no exception table and a colour mask
     * left off is a black frame for the rest of the frame.
     */
    static void withColourMaskOff(java.util.function.Consumer<Boolean> mask, Runnable draw) {
        withGlState(() -> mask.accept(Boolean.FALSE), () -> mask.accept(Boolean.TRUE), () -> {
            draw.run();
            return null;
        });
    }

    /**
     * One portal pass, in order: the destination inside the applied depth
     * range, the stamp after it is restored.
     *
     * <p>The stamp goes outside because {@code glDepthRange} remaps it along
     * with everything else and lands it at the band's far edge, leaving
     * anything inside the band occluded by nothing. A null slice makes no
     * range calls at all. A draw that throws still restores the range, and the
     * stamp is not drawn.
     *
     * <p>Returns the stamp's corner count.
     */
    static int runPass(PortalPass pass, ClientProjection projection, BlockPos origin,
            double[] slice) {
        double planeLocal = planeLocal(projection, origin);
        double[] shift = meshShift(projection);
        if (slice != null) {
            pass.applyDepthRange(slice[0], slice[1]);
        }
        try {
            pass.drawBackdrop(planeLocal);
            pass.drawDestination(shift[0], shift[1], shift[2]);
        } finally {
            if (slice != null) {
                pass.restoreDepthRange();
            }
        }
        return pass.drawStamp(planeLocal);
    }

    /**
     * The portal surface on the normal axis, in the volume's own space. The
     * backdrop is cast from it and the stamp is drawn on it, so reading it off
     * a FACE of the aperture block instead moves both half a block.
     *
     * <p>Computed here rather than by the caller: {@link #drawOne} needs a
     * client to run and no test can reach it, so a value it works out for
     * itself is a value nothing can check.
     */
    static double planeLocal(ClientProjection projection, BlockPos origin) {
        return projection.planeCoord() - axisOf(origin, projection.normalAxis());
    }

    /**
     * How far the mesh moves for its near face to land on the surface, split
     * over the three axes. Zero on the two the opening spans, so a shift that
     * leaks onto an in-plane axis slides the image sideways.
     */
    static double[] meshShift(ClientProjection projection) {
        Direction.Axis axis = projection.normalAxis();
        double surface = projection.surfaceOffset();
        return new double[] {
            axis == Direction.Axis.X ? surface : 0.0,
            axis == Direction.Axis.Y ? surface : 0.0,
            axis == Direction.Axis.Z ? surface : 0.0,
        };
    }

    /**
     * Sets GL state, draws, and restores it even when the draw throws.
     * {@code RenderLayer} has no exception table, so state set around a draw
     * and restored after it is state left set for the rest of the frame when
     * anything fails. The depth range gets the same guarantee from
     * {@link #runPass}.
     */
    static <T> T withGlState(Runnable apply, Runnable restore,
            java.util.function.Supplier<T> draw) {
        apply.run();
        try {
            return draw.get();
        } finally {
            restore.run();
        }
    }

    /**
     * Window-space depth of a camera-relative point, or NaN behind the eye.
     */
    static double windowDepth(Matrix4f position, Matrix4f projectionMatrix,
            double x, double y, double z) {
        Vector4f point = new Vector4f((float) x, (float) y, (float) z, 1.0f);
        position.transform(point);
        projectionMatrix.transform(point);
        if (!(point.w > 0.0f)) {
            return Double.NaN;
        }
        return (point.z / point.w + 1.0) / 2.0;
    }

    /**
     * The depth range the destination is compressed into, as
     * {@code {near, far}}, or null when it cannot be formed.
     *
     * <p>{@code surfaceDepth} is the NEAREST point of the portal surface and
     * {@code halfBlockDepth} the nearest point half a block behind it. Squeezed
     * between them, every fragment of the destination tests and writes at the
     * WINDOW's depth instead of at the source-world coordinates it borrowed —
     * which is what lets a real block in front of the frame survive a pass that
     * used to overwrite it.
     *
     * <p>The surface's depth is not constant across the opening seen
     * obliquely, so taking the nearest point leaves a residual band the size of
     * that spread, in which something in front of the frame is still lost.
     */
    static double[] depthSlice(double surfaceDepth, double halfBlockDepth) {
        if (Double.isNaN(surfaceDepth) || Double.isNaN(halfBlockDepth)) {
            return null;
        }
        if (surfaceDepth < 0.0 || halfBlockDepth > 1.0 || halfBlockDepth <= surfaceDepth) {
            return null;
        }
        return new double[] {
            surfaceDepth,
            surfaceDepth + (halfBlockDepth - surfaceDepth) * SLICE_FRACTION,
        };
    }

    /**
     * The slice bounds as applied, or {@code none} when none could be formed
     * and the pass fell back to an ordinary depth range.
     *
     * <p>Formatted from the array the draws were made under, not recomputed:
     * a slice built from the wrong corner prints different numbers, which is
     * the only way that mistake is visible at all. No test in this module
     * reaches {@code sliceFor}.
     */
    static String sliceLabel(double[] slice) {
        return slice == null ? "none" : String.format("%.6f..%.6f", slice[0], slice[1]);
    }

    /** The slice for one portal, from its opening's four corners. */
    private static double[] sliceFor(ClientProjection projection, double[] corners,
            double camX, double camY, double camZ, double facing,
            Matrix4f position, Matrix4f projectionMatrix) {
        Direction.Axis axis = projection.normalAxis();
        double surface = Double.MAX_VALUE;
        double behind = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double x = corners[i * 3] - camX;
            double y = corners[i * 3 + 1] - camY;
            double z = corners[i * 3 + 2] - camZ;
            double near = windowDepth(position, projectionMatrix, x, y, z);
            double half = windowDepth(position, projectionMatrix,
                    x + (axis == Direction.Axis.X ? facing * 0.5 : 0.0),
                    y + (axis == Direction.Axis.Y ? facing * 0.5 : 0.0),
                    z + (axis == Direction.Axis.Z ? facing * 0.5 : 0.0));
            if (Double.isNaN(near) || Double.isNaN(half)) {
                return null;
            }
            surface = Math.min(surface, near);
            behind = Math.min(behind, half);
        }
        return depthSlice(surface, behind);
    }

    /**
     * Draws a clipped polygon left in {@link #POLY_A} on one flat layer and
     * flushes it. Returns the corner count, 0 when there was nothing to draw.
     */
    private static int drawFlat(net.minecraft.client.render.RenderLayer layer,
            MatrixStack.Entry entry, int corners, boolean depthOnly) {
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
        if (depthOnly) {
            withColourMaskOff(COLOUR_MASK, () -> immediate.draw(layer));
        } else {
            immediate.draw(layer);
        }
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
            for (int plane = 0; plane < PLANES.count() && count >= 3; plane++) {
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
        return PLANES.clip(in, count, out, plane);
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
