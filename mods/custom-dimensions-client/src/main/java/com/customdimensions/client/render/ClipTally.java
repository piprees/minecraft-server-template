package com.customdimensions.client.render;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the aperture clip did to one portal on the frame it last sampled: the
 * camera in the volume's own space, and per layer how many quads went in, how
 * many vertices came out, and which edge of the opening cut the rest.
 *
 * <p>The emit line carries the same figures, but {@link
 * com.customdimensions.client.Repeated} prints only the first of a session at
 * INFO, so a second pose never reaches the log. The camera's position is here
 * and not on that line at all, and without it no reader can work out which
 * half-space each edge stands for.
 *
 * <p>Written on the emit line's own two-second cadence, so the clip itself
 * pays nothing for it.
 */
public final class ClipTally {

    /**
     * One layer's outcome. {@code rejectedBy} is indexed by the opening's
     * edges in build order: low A, high B, high A, low B. A quad leaves the
     * loop at the first edge that cuts it, so a later edge reads low when an
     * earlier one already took everything.
     */
    public record Layer(String layer, int quadsIn, int emitted, int[] rejectedBy) {}

    /**
     * One portal's outcome. {@code cam} is the camera in the volume's own
     * space, which is the frame {@code opening} and {@code volume} on the emit
     * line are already in.
     */
    public record Portal(double[] cam, double camToPlane, int planes, List<Layer> layers) {}

    private static final Map<BlockPos, Portal> BY_APERTURE = new HashMap<>();

    private ClipTally() {}

    /** Starts one portal's entry, discarding the previous frame's layers. */
    public static void open(BlockPos aperture, double camX, double camY, double camZ,
            double camToPlane, int planes) {
        BY_APERTURE.put(aperture, new Portal(new double[] {camX, camY, camZ},
                camToPlane, planes, new ArrayList<>()));
    }

    /** Appends one layer to the entry {@link #open} left standing. */
    public static void layer(BlockPos aperture, String name, int quadsIn, int emitted,
            int[] rejectedBy) {
        Portal portal = BY_APERTURE.get(aperture);
        if (portal == null) {
            return;
        }
        portal.layers().add(new Layer(name, quadsIn, emitted, rejectedBy.clone()));
    }

    /** One portal's last sampled outcome, or null when it has not drawn. */
    public static Portal of(BlockPos aperture) {
        return BY_APERTURE.get(aperture);
    }

    public static void clear() {
        BY_APERTURE.clear();
    }
}
