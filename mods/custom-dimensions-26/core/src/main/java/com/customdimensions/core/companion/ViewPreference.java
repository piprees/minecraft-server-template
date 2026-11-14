package com.customdimensions.core.companion;

/**
 * What one companion client has declared it draws for itself. The wire
 * carries {@code keepSlab} for the client's own setting; there is no
 * server-drawn slab to keep, so the server reads only {@link #feeds()}.
 */
public record ViewPreference(boolean rendersLocally, boolean keepSlab, int maxRenderDistance, int viewDepth) {
    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;
    public static final int DEFAULT_VIEW_DEPTH = 64;
    public static final int MIN_VIEW_DEPTH = 32;
    public static final int MAX_VIEW_DEPTH = 192;

    /** A companion that has not declared yet: fed nothing until it does. */
    public static final ViewPreference UNDECLARED =
            new ViewPreference(false, true, MIN_RENDER_DISTANCE, DEFAULT_VIEW_DEPTH);

    public ViewPreference {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
        viewDepth = Math.max(MIN_VIEW_DEPTH, Math.min(MAX_VIEW_DEPTH, viewDepth));
    }

    /** Whether this client draws the far side and so wants the feed. */
    public boolean feeds() {
        return rendersLocally;
    }

    /** The handshake admits a client built from exactly this release; any other is served as vanilla. */
    public static boolean admits(String serverVersion, String clientVersion) {
        return serverVersion != null && serverVersion.equals(clientVersion);
    }
}
