package com.customdimensions.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

/**
 * Where a screen-space pass puts one of our fragments.
 *
 * <p>A shader pack reconstructs a fragment's position from its window
 * coordinates and its depth and nothing else — it never reads the matrices the
 * draw was submitted with — so the depth range the destination is compressed
 * into decides the position the pack computes for the whole opening. This
 * inverts the transform {@link ProjectionRenderer#windowDepth} applies, which
 * turns that position into a distance and a block anyone can look at.
 *
 * <p>Camera-relative throughout, the same as {@code windowDepth}: add the
 * camera for a world position.
 */
public final class DepthReconstruction {

    private DepthReconstruction() {}

    /**
     * One reconstruction, as {@code /state} reports it. {@code windowZ} is READ
     * back from the depth buffer at the opening's centre the moment the
     * destination's colour draw finishes, so {@code distance} is the number a
     * pack compares against its shadow distance and integrates its light shafts
     * to. {@code farDistance} is what the far stamp's own plane reconstructs to
     * down the same pixel.
     *
     * <p>{@code sliceZ} and {@code sliceDistance} are the same reconstruction at
     * the near edge of the compressed slice — where the draw lands when it is
     * made inside the range rather than at its own depth. The two sit side by
     * side because the difference between them is the whole defect.
     */
    public record Sample(double windowZ, double ndcX, double ndcY,
            double x, double y, double z, double distance,
            int blockX, int blockY, int blockZ,
            double farDistance, double sliceZ, double sliceDistance) {}

    private static volatile Sample last;

    static void record(Sample sample) {
        last = sample;
    }

    /** The last opening sampled, or null before any portal has been drawn. */
    public static Sample last() {
        return last;
    }

    /**
     * The centre of four camera-relative points in normalised device
     * coordinates, or null when any of them sits behind the eye.
     *
     * <p>{@code corners} is the opening in the volume's own space and
     * {@code camX/camY/camZ} the camera in that space, exactly as
     * {@code ProjectionRenderer.sliceFor} takes them.
     */
    static double[] centreNdc(Matrix4f position, Matrix4f projection, double[] corners,
            double camX, double camY, double camZ) {
        double x = 0.0;
        double y = 0.0;
        for (int i = 0; i < 4; i++) {
            Vector4f point = new Vector4f((float) (corners[i * 3] - camX),
                    (float) (corners[i * 3 + 1] - camY),
                    (float) (corners[i * 3 + 2] - camZ), 1.0f);
            position.transform(point);
            projection.transform(point);
            if (!(point.w > 0.0f)) {
                return null;
            }
            x += point.x / point.w;
            y += point.y / point.w;
        }
        return new double[] {x / 4.0, y / 4.0};
    }

    /**
     * The camera-relative point a fragment at {@code (ndcX, ndcY)} writing
     * window depth {@code windowZ} reconstructs to, or null behind the eye.
     *
     * <p>The homogeneous {@code w} is the reciprocal of the forward transform's
     * own, so the guard is the same one {@code windowDepth} makes rather than a
     * different rule that happens to agree.
     */
    static double[] unproject(Matrix4f position, Matrix4f projection,
            double ndcX, double ndcY, double windowZ) {
        Vector4f point = new Vector4f((float) ndcX, (float) ndcY,
                (float) (windowZ * 2.0 - 1.0), 1.0f);
        new Matrix4f(projection).mul(position).invert().transform(point);
        if (!(point.w > 0.0f) || !Float.isFinite(point.w)) {
            return null;
        }
        double[] out = {point.x / point.w, point.y / point.w, point.z / point.w};
        for (double value : out) {
            if (!Double.isFinite(value)) {
                return null;
            }
        }
        return out;
    }

    /**
     * The depth the bound framebuffer holds at one point in normalised device
     * coordinates, or NaN off screen. This is the value itself, not a model of
     * it: a screen-space pass reads the same texel.
     *
     * <p>The viewport is asked for rather than the window size, because under a
     * shader pack the framebuffer being drawn into is the pack's own and need
     * not match either the window or vanilla's main target.
     *
     * <p>A one-pixel read synchronises the pipeline, so it belongs on the emit
     * line's cadence and nowhere near every frame.
     */
    static double readWindowDepth(double ndcX, double ndcY) {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            return Double.NaN;
        }
        int x = (int) Math.round((ndcX + 1.0) / 2.0 * viewport[2]);
        int y = (int) Math.round((ndcY + 1.0) / 2.0 * viewport[3]);
        if (x < 0 || y < 0 || x >= viewport[2] || y >= viewport[3]) {
            return Double.NaN;
        }
        float[] depth = new float[1];
        GL11.glReadPixels(viewport[0] + x, viewport[1] + y, 1, 1,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        return depth[0];
    }

    /** How far from the eye a camera-relative point is. */
    static double distance(double[] cameraRelative) {
        return Math.sqrt(cameraRelative[0] * cameraRelative[0]
                + cameraRelative[1] * cameraRelative[1]
                + cameraRelative[2] * cameraRelative[2]);
    }
}
