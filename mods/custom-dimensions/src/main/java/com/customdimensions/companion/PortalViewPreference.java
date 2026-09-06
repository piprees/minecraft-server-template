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
        int maxRenderDistance,
        int viewDepth) {

    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;

    /**
     * How far past the opening a local view reaches, in blocks. The client's
     * own setting, declared here because the feed sizes its chunk core from it;
     * a client that declares none holds the default the client also defaults to.
     */
    public static final int DEFAULT_VIEW_DEPTH = 64;
    public static final int MIN_VIEW_DEPTH = 32;
    public static final int MAX_VIEW_DEPTH = 192;

    /**
     * Every vanilla client, every companion built before this existed, and
     * every player who has not declared yet. The server draws for them.
     */
    public static final PortalViewPreference SERVER_DRAWN =
            new PortalViewPreference(false, true, MIN_RENDER_DISTANCE, DEFAULT_VIEW_DEPTH);

    public PortalViewPreference {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE,
                Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
        viewDepth = Math.max(MIN_VIEW_DEPTH, Math.min(MAX_VIEW_DEPTH, viewDepth));
    }

    /** A client that declared no depth draws at the default. */
    public PortalViewPreference(boolean rendersLocally, boolean keepSlab,
            int maxRenderDistance) {
        this(rendersLocally, keepSlab, maxRenderDistance, DEFAULT_VIEW_DEPTH);
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
