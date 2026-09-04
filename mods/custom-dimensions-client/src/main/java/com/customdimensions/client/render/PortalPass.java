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

    void applyDepthRange(double near, double far);

    void restoreDepthRange();

    /** The destination's sky and fog, behind everything the mesh draws. */
    void drawBackdrop();

    /** Every layer of the meshed destination. */
    void drawDestination();

    /** The opening's own depth. Returns the corner count, 0 when nothing was drawn. */
    int drawStamp();
}
