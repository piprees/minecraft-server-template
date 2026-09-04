package com.customdimensions.client.realtime;

/**
 * Where the spectator pass's offscreen frame lands on screen, in GL pixels.
 *
 * <p>GL's window origin is the BOTTOM left and the corner wanted is the TOP
 * left, so the destination rectangle's Y runs from {@code height - side} to
 * {@code height}. Getting that inverted puts the preview under the hotbar,
 * where the hotbar draws over it and the run reads as "nothing rendered".
 */
public final class SpectatorCorner {

    /** The preview's share of the shorter screen axis. */
    public static final double SHARE = 0.3;

    private SpectatorCorner() {}

    /** {@code {x0, y0, x1, y1}} for {@code glBlitFramebuffer}'s destination. */
    public static int[] topLeft(int width, int height) {
        int side = side(width, height);
        return new int[] {0, height - side, side, height};
    }

    /** The square preview's edge, never larger than either screen axis. */
    public static int side(int width, int height) {
        int shorter = Math.min(Math.max(width, 0), Math.max(height, 0));
        return Math.max(1, (int) Math.round(shorter * SHARE));
    }
}
