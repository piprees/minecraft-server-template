package com.customdimensions.client.realtime;

/**
 * The box of the destination this client draws for one opening, in SOURCE
 * coordinates.
 *
 * <p>The same shape the server's slab describes, decided here instead: it
 * starts one block past the aperture on the side being looked into and runs
 * {@code depth} blocks, widened by {@code radius} on the two in-plane axes so
 * an oblique sightline still lands on something.
 *
 * <p>Nothing here divides by a portal's scale. The scale is spent server-side
 * deriving the offset; a box that spent it again would be right at scale 1 and
 * wrong everywhere else. See {@link PortalCamera}.
 *
 * <p>No Minecraft types: all of it is arithmetic and all of it is tested.
 */
public record LocalVolume(int originA, int originB, int originN,
        int sizeA, int sizeB, int sizeN) {

    /**
     * @param minA        aperture's low bound on the first in-plane axis
     * @param maxA        aperture's high bound on that axis, inclusive
     * @param planeBlock  the aperture block's coordinate on the normal axis
     * @param towardsHigh whether the side being drawn is the high side of it
     */
    public static LocalVolume of(int minA, int maxA, int minB, int maxB,
            int planeBlock, boolean towardsHigh, int depth, int radius) {
        int safeDepth = Math.max(1, depth);
        int safeRadius = Math.max(0, radius);
        return new LocalVolume(
                minA - safeRadius,
                minB - safeRadius,
                towardsHigh ? planeBlock + 1 : planeBlock - safeDepth,
                (maxA - minA + 1) + safeRadius * 2,
                (maxB - minB + 1) + safeRadius * 2,
                safeDepth);
    }

    public int cells() {
        return this.sizeA * this.sizeB * this.sizeN;
    }
}
