package com.customdimensions.client.realtime;

/**
 * Where the spectator pass's offscreen frame lands on screen, in GL pixels.
 *
 * <p>Against the left edge, vertically centred — not in a screen corner.
 * Xaero's minimap panel covers the whole top-left square (measured 290x270
 * against a 288 preview on a 1708x960 screen) and the HUD draws after the
 * world, so a preview up there is hidden outright rather than overlapped:
 * cropping the shot finds the minimap and nothing else. The middle of the
 * left edge is clear of the minimap above and the hotbar below.
 *
 * <p>GL's window origin is the BOTTOM left, so Y here counts up from the
 * bottom. Treating it as a top-down coordinate puts the preview under the
 * hotbar, which draws over it and reads as "nothing rendered".
 */
public final class SpectatorCorner {

    /** The preview's share of the shorter screen axis. */
    public static final double SHARE = 0.3;

    private SpectatorCorner() {}

    /** {@code {x0, y0, x1, y1}} for {@code glBlitFramebuffer}'s destination. */
    public static int[] preview(int width, int height) {
        int side = side(width, height);
        int bottom = Math.max(0, (Math.max(height, 0) - side) / 2);
        return new int[] {0, bottom, side, bottom + side};
    }

    /** The square preview's edge, never larger than either screen axis. */
    public static int side(int width, int height) {
        int shorter = Math.min(Math.max(width, 0), Math.max(height, 0));
        return Math.max(1, (int) Math.round(shorter * SHARE));
    }
}
