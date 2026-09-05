package com.customdimensions.client.render;

/**
 * The GL operations one portal pass makes, in the order it makes them.
 *
 * <p>Production drives raw GL and the render layers; a test drives a recorder
 * and asserts the script. The order is the invariant: the destination inside
 * the applied depth range, the stamp after it is restored.
 *
 * <p>Nothing here mentions {@code RenderLayer}. That is the point —
 * {@link PortalRenderLayers} cannot be loaded outside a bootstrapped client,
 * so an ordering expressed in terms of layers is an ordering no test can read.
 */
public interface PortalPass {

    /** Which of a portal's draws one pass makes. */
    enum Stage {

        /** The destination inside its depth slice, then the surface's own depth. */
        DESTINATION,

        /** The same, closed with the volume's far depth instead of the surface's. */
        DESTINATION_FAR,

        /** The surface's own depth, and nothing else. */
        NEAR_DEPTH,

        /** The far end of the captured volume, depth only, and nothing else. */
        FAR_DEPTH;

        /** Whether this stage draws the destination as well as a stamp. */
        public boolean drawsDestination() {
            return this == DESTINATION || this == DESTINATION_FAR;
        }
    }

    void applyDepthRange(double near, double far);

    void restoreDepthRange();

    /**
     * The destination's sky and fog, behind everything the mesh draws.
     * {@code planeLocal} is the portal surface on the normal axis, in the
     * volume's own space, and the backdrop is cast from it.
     */
    void drawBackdrop(double planeLocal);

    /**
     * Every layer of the meshed destination, moved by the offset that lands its
     * near face on the surface. Zero on the two in-plane axes.
     */
    void drawDestination(double shiftX, double shiftY, double shiftZ);

    /**
     * The opening's own depth, on the surface at {@code planeLocal}. Returns
     * the corner count, 0 when nothing was drawn.
     */
    int drawStamp(double planeLocal);

    /**
     * The far end of the captured volume, cast from the surface at
     * {@code planeLocal}, depth only. Returns the corner count, 0 when nothing
     * was drawn.
     */
    int drawFarStamp(double planeLocal);
}
