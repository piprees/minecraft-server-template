package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
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

    /**
     * A resend that changes nothing keeps the projection already held, mesh
     * included. Replacing it wholesale threw away a built mesh and had it
     * rebuilt from scratch.
     */
    public static void accept(CompanionPayloads.Projection payload) {
        PROJECTIONS.compute(payload.apertureOrigin(), (key, held) ->
                sameContent(held == null ? null : held.payload(), payload)
                        ? held
                        : new ClientProjection(payload));
    }

    /**
     * True when two payloads describe the same view. Everything the mesh or the
     * aperture geometry reads is compared, arrays by value — a record's own
     * equals compares {@code states} and {@code light} by reference and answers
     * false for every decoded resend.
     */
    public static boolean sameContent(CompanionPayloads.Projection a, CompanionPayloads.Projection b) {
        if (a == null || b == null) {
            return false;
        }
        return a.destination().equals(b.destination())
                && a.apertureOrigin().equals(b.apertureOrigin())
                && a.aperture().equals(b.aperture())
                && a.portalAxis() == b.portalAxis()
                && a.normal() == b.normal()
                && a.origin().equals(b.origin())
                && a.sizeX() == b.sizeX() && a.sizeY() == b.sizeY() && a.sizeZ() == b.sizeZ()
                && Arrays.equals(a.states(), b.states())
                && Arrays.equals(a.light(), b.light())
                && a.skyColor() == b.skyColor()
                && a.fogColor() == b.fogColor()
                && a.grassColor() == b.grassColor()
                && a.foliageColor() == b.foliageColor()
                && a.waterColor() == b.waterColor();
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
