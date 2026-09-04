package com.customdimensions.client.realtime;

/**
 * Where the camera stands on the far side, and what it may not see.
 *
 * <h2>It is a translation, and nothing else</h2>
 * The server's own mapping is
 * {@code ProjectionVolume.toTarget: target = source + (dx, dy, dz)} — a rigid
 * translation with no scale and no rotation in it. A dimension's {@code scale}
 * is spent server-side DERIVING those three numbers (the arrival column is the
 * portal's column divided by the scale, rounded); it never reaches the view.
 * That is deliberate and `ProjectionVolume` says so in its own words: a preview
 * is never scaled, because a block walked on the far side has to be a block
 * here or the destination reads as a model rather than a place.
 *
 * <p><b>So never divide by scale here.</b> A camera derived from the scale
 * factor looks right at scale 1 and wrong at scale 4, which is exactly the
 * symptom that would send someone hunting the camera when the bug was in
 * having used the scale at all.
 *
 * <h2>The near plane</h2>
 * Whatever stands between the destination camera and the destination portal
 * would occlude the view through the opening, so the scene is clipped to the
 * far side of the plane. In destination space the plane sits at the source
 * plane plus the offset on the normal axis.
 *
 * <p>No Minecraft types: all of it is arithmetic and all of it is tested.
 */
public final class PortalCamera {

    private PortalCamera() {}

    /** X, Y and Z of the viewer's corresponding position on the far side. */
    public static double[] destinationEye(double x, double y, double z, int dx, int dy, int dz) {
        return new double[] {x + dx, y + dy, z + dz};
    }

    /** One coordinate of it, for a caller that wants a single axis. */
    public static double translate(double sourceCoord, int offset) {
        return sourceCoord + offset;
    }

    /**
     * The portal plane in destination space, on the opening's normal axis.
     * The opening's own block spans {@code [coord, coord + 1)}, and the
     * surface bisects it, which is where the source renderer puts it too.
     */
    public static double destinationPlane(int sourcePlaneBlock, int offsetOnNormal) {
        return sourcePlaneBlock + offsetOnNormal + 0.5;
    }

    /**
     * Whether a point is on the side of the plane the camera is looking INTO
     * — the only side worth drawing. A point exactly in the plane is not:
     * it is the surface itself, and drawing it fights the mask for the same
     * pixels.
     */
    public static boolean beyondPlane(double point, double plane, double camera) {
        double toPoint = point - plane;
        double toCamera = camera - plane;
        if (toCamera == 0.0) {
            return false;
        }
        return toPoint * toCamera < 0.0;
    }

    /**
     * The signed distance from the plane, positive on the far side. This is
     * the value a clip plane wants: negative is culled.
     */
    public static double depthBeyondPlane(double point, double plane, double camera) {
        double toCamera = camera - plane;
        if (toCamera == 0.0) {
            return 0.0;
        }
        return toCamera > 0.0 ? plane - point : point - plane;
    }
}
