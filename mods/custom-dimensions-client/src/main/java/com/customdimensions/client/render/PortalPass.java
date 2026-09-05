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
     *
     * <p>{@code writeDepth} false paints it and leaves the depth buffer alone.
     * Inside the slice it otherwise writes ≈the doorway across the whole
     * opening, and a destination drawn afterwards at its own depth then fails
     * {@code LEQUAL} on every pixel and draws nothing at all.
     */
    void drawBackdrop(double planeLocal, boolean writeDepth);

    /**
     * Every layer of the meshed destination, moved by the offset that lands its
     * near face on the surface. Zero on the two in-plane axes.
     */
    void drawDestination(double shiftX, double shiftY, double shiftZ);

    /**
     * Whether {@link #drawDestination} goes before the far stamp at its own
     * depth instead of inside the slice.
     *
     * <p>The mesh is submitted on entity layers, so a pack shades it in the
     * forward pass from its own fragment's {@code gl_FragCoord.z} exactly as it
     * shades an actor, and inside {@code glDepthRange(slice)} that value says
     * "at the doorway" for the whole opening whatever the terrain behind it
     * holds ({@code TROUBLESHOOTING.md#t100}). No stamp reaches it: the stamps
     * repair the depth BUFFER, which a forward pass never reads.
     *
     * <p>Only {@link Stage#DESTINATION_FAR} can honour it, and it goes BEFORE
     * the far stamp: that stamp writes with an always-pass test, so a colour
     * draw after it tests against a buffer with every near occluder erased and
     * paints the destination through solid walls. Before it, the colour draw
     * tests against the source world's own depth — at the cost of source
     * terrain beyond the opening winning where it is nearer than the mesh.
     */
    boolean destinationAtTrueDepth();

    /**
     * The far side's mobs, players and block entities, through the same opening.
     *
     * <p>Drawn on its own rather than with the mesh because an actor has to
     * test against the mesh's own depth, which only exists once the mesh has
     * been drawn. Both are shaded in the forward pass from their own fragment's
     * {@code gl_FragCoord.z} and both go outside the slice for it
     * ({@code TROUBLESHOOTING.md#t100}).
     */
    void drawActors();

    /**
     * Whether {@link #drawActors} goes after the destination's own per-pixel
     * depth instead of inside the slice.
     *
     * <p>Only meaningful for {@link Stage#DESTINATION_FAR}: it is the one stage
     * that leaves the mesh's true depth in the buffer, which is what an actor
     * at its true depth must test against. Anywhere else the actor is behind
     * the surface stamp and draws nothing.
     */
    boolean actorsAtTrueDepth();

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

    /**
     * The meshed destination again, depth only, at its own true depth.
     *
     * <p>Always after {@link #drawFarStamp}: that one writes with an always-pass
     * test, so drawn the other way round it erases this.
     */
    void drawDestinationDepth(double shiftX, double shiftY, double shiftZ);
}
