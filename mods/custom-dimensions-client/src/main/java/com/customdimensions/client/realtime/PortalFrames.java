package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every portal this client is drawing the destination for itself, keyed by the
 * opening's minimum corner — the same key the server clears with, and the same
 * key {@code ProjectionStore} uses for the server-drawn description.
 *
 * <p><b>A portal is in one store or the other, never both.</b> They describe
 * the same opening, and a client holding both would draw the far side twice —
 * once live and once as of a moment ago. The server guarantees it by sending
 * only one; the receivers enforce it anyway.
 *
 * <p>Cleared on world change and disconnect: a frame addresses source-world
 * block positions, and the same numbers mean somewhere else in the next world.
 */
public final class PortalFrames {

    /** Grepped in the client log to prove a frame arrived and decoded. */
    public static final String RECEIVE_MARKER = "companion-client:portal-frame";

    private static final Map<BlockPos, CompanionPayloads.PortalFrame> FRAMES =
            new ConcurrentHashMap<>();

    private PortalFrames() {}

    /**
     * A resend that changes nothing keeps what is held, so anything built from
     * a frame survives. Returns true when the held value actually changed.
     */
    public static boolean accept(CompanionPayloads.PortalFrame frame) {
        if (frame == null) {
            return false;
        }
        CompanionPayloads.PortalFrame held = FRAMES.put(frame.apertureOrigin(), frame);
        return !frame.equals(held);
    }

    public static CompanionPayloads.PortalFrame get(BlockPos apertureOrigin) {
        return apertureOrigin == null ? null : FRAMES.get(apertureOrigin);
    }

    public static void remove(BlockPos apertureOrigin) {
        if (apertureOrigin != null) {
            FRAMES.remove(apertureOrigin);
        }
    }

    public static void clear() {
        FRAMES.clear();
    }

    public static Collection<CompanionPayloads.PortalFrame> all() {
        return FRAMES.isEmpty() ? List.of() : FRAMES.values();
    }

    public static int count() {
        return FRAMES.size();
    }

    /**
     * The destinations currently framed. A world is stood up per destination,
     * not per portal, so two portals onto one dimension share it.
     */
    public static java.util.Set<net.minecraft.util.Identifier> destinations() {
        java.util.Set<net.minecraft.util.Identifier> out = new java.util.HashSet<>();
        for (CompanionPayloads.PortalFrame frame : FRAMES.values()) {
            out.add(frame.destination());
        }
        return out;
    }
}
