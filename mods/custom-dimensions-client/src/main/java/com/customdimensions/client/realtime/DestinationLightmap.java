package com.customdimensions.client.realtime;

import net.minecraft.client.world.ClientWorld;

/**
 * The world the lightmap describes while a destination pass runs.
 *
 * <p>{@code LightmapTextureManager.update} reads {@code client.world} and takes
 * no world argument, and the pass does not write that field
 * ({@code TROUBLESHOOTING.md#t92}). Held across the pass's own call to
 * {@code update} and nothing else, written and read on the render thread only.
 */
public final class DestinationLightmap {

    private static ClientWorld held;

    private DestinationLightmap() {}

    public static void hold(ClientWorld destination) {
        held = destination;
    }

    public static void release() {
        held = null;
    }

    /** Null outside a destination pass, which is every other lightmap update. */
    public static ClientWorld held() {
        return held;
    }
}
