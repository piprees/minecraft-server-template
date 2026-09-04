package com.customdimensions.client.realtime;

/**
 * The field of view the destination pass draws and culls at.
 *
 * <p>The composite samples the offscreen frame at {@code gl_FragCoord.xy /
 * ScreenSize}, so the destination lands under the opening only while its
 * projection matches the source frame's. Vanilla's effective field of view
 * carries the spyglass and bow-draw multiplier, the death squeeze and the
 * water and lava submersion effect on top of the option; the option carries
 * none of them.
 *
 * <p>Drawing and culling take different answers. Vanilla culls the source at
 * the wider of the unzoomed view and the option, so the chunks a spyglass is
 * about to be lowered from are already built.
 */
public final class SpectatorProjection {

    private SpectatorProjection() {}

    /** The field of view the destination is drawn at. */
    public static double render(ViewFov view) {
        return view.fov(true);
    }

    /** The field of view the destination is culled at. */
    public static double frustum(ViewFov view) {
        return Math.max(view.fov(false), view.option());
    }

    /** Vanilla's field-of-view answers for the frame being drawn. */
    public interface ViewFov {

        /**
         * {@code GameRenderer.getFov}. {@code changing} folds the zoom, bow
         * and sprint multiplier into the answer.
         */
        double fov(boolean changing);

        /** The raw field-of-view option, with no effect applied. */
        double option();
    }
}
