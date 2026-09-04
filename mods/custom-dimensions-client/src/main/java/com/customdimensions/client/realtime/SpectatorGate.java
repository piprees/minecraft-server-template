package com.customdimensions.client.realtime;

/**
 * The cheap tests that run before a portal is worth a GPU query.
 *
 * <p>All of it is arithmetic in source-world coordinates, so all of it is
 * tested. A portal behind the camera or past the range costs two comparisons
 * here; the query costs a quad, a driver round trip and a result to read.
 */
public final class SpectatorGate {

    /** How far a portal is drawn from, in blocks. */
    public static final double RANGE = 128.0;

    /**
     * Level with the surface is not in front of it. Past the opening the far
     * side is no longer something seen THROUGH a frame, and the same margin is
     * what the meshed path refuses on.
     */
    static final double PLANE_MARGIN = 0.05;

    private SpectatorGate() {}

    /**
     * Whether the camera is on the side the opening is seen from. The normal
     * points into the destination, so the viewer's side is the negative one.
     */
    public static boolean inFront(double surface, boolean towardsHigh, double cameraOnNormal) {
        double facing = towardsHigh ? 1.0 : -1.0;
        return (cameraOnNormal - surface) * facing < -PLANE_MARGIN;
    }

    public static double distanceSquared(double ax, double ay, double az,
            double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean withinRange(double distanceSquared) {
        return distanceSquared <= RANGE * RANGE;
    }

    /**
     * Index of the nearest allowed candidate, or -1 when none is allowed. Ties
     * take the earlier index, so a set that arrives in a different order
     * answers the same portal.
     */
    public static int nearest(double[] distancesSquared, boolean[] allowed) {
        int best = -1;
        for (int i = 0; i < distancesSquared.length && i < allowed.length; i++) {
            if (allowed[i] && (best < 0 || distancesSquared[i] < distancesSquared[best])) {
                best = i;
            }
        }
        return best;
    }
}
