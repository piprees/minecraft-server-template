package com.customdimensions.client.realtime;

/**
 * The work one spectator pass does, in the order it does it.
 *
 * <p>Production drives GL and a second {@code WorldRenderer}; a test drives a
 * recorder and asserts the script. The order is the invariant: nothing is
 * allocated, cleared or rendered until {@link #visible()} has answered, so an
 * opening hidden behind terrain costs one quad rather than a world render.
 *
 * <p>Nothing here mentions a framebuffer, a shader or a render layer. That is
 * the point: those classes need a bootstrapped client, so a script expressed
 * in terms of them is a script no test can read.
 */
public interface SpectatorSteps {

    /**
     * Whether the opening can reach the screen at all: the cheap camera-side
     * and distance tests, and the GPU's verdict on the last query.
     */
    boolean visible();

    /** Allocates or resizes the offscreen target. */
    void prepareTarget();

    void clearTarget();

    /** The destination world, through its own renderer, into the target. */
    void renderDestination();
}
