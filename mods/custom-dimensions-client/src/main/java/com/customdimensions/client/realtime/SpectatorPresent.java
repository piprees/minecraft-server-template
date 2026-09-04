package com.customdimensions.client.realtime;

/**
 * What is done with a rendered pass, in the order it is done.
 *
 * <p>Two of the three run inside the source world's render, where the depth
 * buffer holds the source world — {@code GameRenderer.renderWorld} clears it
 * between {@code WorldRenderer.render} and the hand, and it has not been
 * written yet at the head of the frame, so neither the query nor the composite
 * can ride the ends of that method.
 *
 * <p>The query is issued whether or not this frame drew anything. A gate that
 * stops asking once it has refused never un-refuses, and a portal that came
 * back into view would stay dark for the rest of the session.
 */
public interface SpectatorPresent {

    /**
     * Draws the opening's quad against the source world's depth inside an
     * occlusion query, painting nothing. Answers the NEXT frame's gate.
     */
    void issueOcclusionQuery();

    /** The offscreen frame sampled in screen space through the opening. */
    void compositeThroughQuad();

    /** The scaffold preview against the left edge. */
    void blitCorner();
}
