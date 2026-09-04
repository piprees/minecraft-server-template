package com.customdimensions.companion;

/**
 * What one client has told the server it will draw for itself.
 *
 * <p>Separate from the handshake on purpose: the handshake asks "can you speak
 * this protocol" once and never changes, while this is a setting the player
 * flips at runtime. It arrives again every time they do.
 *
 * <p>No Minecraft types, so the one question the projection pass asks —
 * {@link #streamsSlab()} — is testable on its own.
 */
public record PortalViewPreference(
        boolean rendersLocally,
        boolean keepSlab,
        int maxRenderDistance) {

    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;

    /**
     * Every vanilla client, every companion built before this existed, and
     * every player who has not declared yet. The server draws for them.
     */
    public static final PortalViewPreference SERVER_DRAWN =
            new PortalViewPreference(false, true, MIN_RENDER_DISTANCE);

    public PortalViewPreference {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE,
                Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
    }

    /**
     * Whether the server still has to describe this portal's far side.
     *
     * <p>Only a client that both renders locally AND has turned the fallback
     * off is sent nothing. Refusing the slab without rendering anything would
     * be a client asking for an empty frame, so it is not honoured.
     */
    public boolean streamsSlab() {
        return !this.rendersLocally || this.keepSlab;
    }
}
