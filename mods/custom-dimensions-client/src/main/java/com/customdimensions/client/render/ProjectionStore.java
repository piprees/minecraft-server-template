package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every portal this client is currently rendering a destination for, keyed by
 * the opening's minimum corner — the same key the server clears with.
 *
 * <p>Cleared on world change and disconnect: a projection describes source
 * coordinates in one world, and the same numbers address somewhere else in
 * the next one.
 */
public final class ProjectionStore {

    /** Grepped in the client log to prove a description arrived and decoded. */
    public static final String RECEIVE_MARKER = "companion-client:projection";

    private static final Map<BlockPos, ClientProjection> PROJECTIONS = new ConcurrentHashMap<>();

    private ProjectionStore() {}

    public static void accept(CompanionPayloads.Projection payload) {
        PROJECTIONS.put(payload.apertureOrigin(), new ClientProjection(payload));
    }

    public static void remove(BlockPos apertureOrigin) {
        PROJECTIONS.remove(apertureOrigin);
    }

    public static void clear() {
        PROJECTIONS.clear();
    }

    public static Collection<ClientProjection> all() {
        return PROJECTIONS.isEmpty() ? List.of() : PROJECTIONS.values();
    }

    public static int count() {
        return PROJECTIONS.size();
    }
}
