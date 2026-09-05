package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Grepped in the client log to prove an oversized opening was reported. */
    public static final String BAND_MARKER = "companion-client:band";

    private static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    private static final Map<BlockPos, ClientProjection> PROJECTIONS = new ConcurrentHashMap<>();

    /** One line per opening, not one per resend. */
    private static final Set<BlockPos> WARNED = ConcurrentHashMap.newKeySet();

    private ProjectionStore() {}

    /**
     * A resend that changes nothing keeps the projection already held, mesh
     * included. Replacing it wholesale threw away a built mesh and had it
     * rebuilt from scratch.
     *
     * <p>True when this opening had no projection before. The server resends
     * on its own cadence, so first sight is the bounded event and a resend is
     * not.
     */
    public static boolean accept(CompanionPayloads.Projection payload) {
        boolean[] firstSight = new boolean[1];
        PROJECTIONS.compute(payload.apertureOrigin(), (key, held) -> {
            firstSight[0] = held == null;
            if (sameContent(held == null ? null : held.payload(), payload)) {
                return held;
            }
            ClientProjection made = new ClientProjection(payload);
            if (made.bandOpens() && WARNED.add(key)) {
                LOGGER.warn("{} aperture={} span={}x{} reach={} over {}: seen obliquely, the "
                                + "destination is drawn in front of source terrain at the far "
                                + "side of the opening",
                        BAND_MARKER, key.toShortString(),
                        String.format("%.0f", made.rectMaxA() - made.rectMinA()),
                        String.format("%.0f", made.rectMaxB() - made.rectMinB()),
                        String.format("%.2f", made.bandReach()), ClientProjection.BAND_LIMIT);
            }
            return made;
        });
        return firstSight[0];
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
                && Arrays.equals(a.tintPalette(), b.tintPalette())
                && Arrays.equals(a.columnTints(), b.columnTints())
                && Float.compare(a.ambientLight(), b.ambientLight()) == 0;
    }

    public static void remove(BlockPos apertureOrigin) {
        PROJECTIONS.remove(apertureOrigin);
    }

    public static void clear() {
        PROJECTIONS.clear();
        WARNED.clear();
    }

    public static Collection<ClientProjection> all() {
        return PROJECTIONS.isEmpty() ? List.of() : PROJECTIONS.values();
    }

    public static int count() {
        return PROJECTIONS.size();
    }
}
