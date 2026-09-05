package com.customdimensions.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

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
     * One reconstruction, as {@code /state} reports it. {@code distance} is the
     * number a pack compares against its shadow distance and integrates its
     * light shafts to, and {@code farDistance} is what the far stamp's own
     * plane reconstructs to down the same pixel.
     */
    public record Sample(double windowZ, double ndcX, double ndcY,
            double x, double y, double z, double distance,
            int blockX, int blockY, int blockZ,
            double farDistance) {}

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

    /** How far from the eye a camera-relative point is. */
    static double distance(double[] cameraRelative) {
        return Math.sqrt(cameraRelative[0] * cameraRelative[0]
                + cameraRelative[1] * cameraRelative[1]
                + cameraRelative[2] * cameraRelative[2]);
    }
}
