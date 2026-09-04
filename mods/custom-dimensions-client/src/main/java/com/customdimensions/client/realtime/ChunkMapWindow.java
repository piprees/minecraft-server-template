package com.customdimensions.client.realtime;

/**
 * The window a {@code ClientChunkManager} will accept chunks into.
 *
 * <p>Vanilla's chunk map keeps a square around a centre and DISCARDS anything
 * outside it — {@code ClientChunkManager.loadChunkFromPacket} logs "Ignoring
 * chunk since it's not in the view range" and returns null. Uncentred, that
 * silently drops every chunk of a destination whose arrival is nowhere near
 * the origin, which reads as the feed never arriving.
 *
 * <p>Both rules are vanilla's own, copied here so they can be asserted:
 * the radius is {@code max(2, loadDistance) + 3} and the test is Chebyshev,
 * not Euclidean.
 */
public final class ChunkMapWindow {

    private ChunkMapWindow() {}

    /** Vanilla's {@code ClientChunkManager.getChunkMapRadius}. */
    public static int radiusFor(int loadDistance) {
        return Math.max(2, loadDistance) + 3;
    }

    /** Vanilla's {@code ClientChunkMap.isInRadius} — a square, not a circle. */
    public static boolean inRange(int centreChunkX, int centreChunkZ,
            int chunkX, int chunkZ, int loadDistance) {
        int radius = radiusFor(loadDistance);
        return Math.abs(chunkX - centreChunkX) <= radius
                && Math.abs(chunkZ - centreChunkZ) <= radius;
    }

    /**
     * The load distance to stand a destination world up with, given the radius
     * the server is feeding. The map's radius must cover the whole fed disc or
     * its outer ring is thrown away on arrival, so this never shrinks it.
     */
    public static int loadDistanceFor(int feedRadius) {
        return Math.max(2, feedRadius);
    }

    /**
     * The chunk the map is centred on: one source-world block coordinate of
     * the opening, carried onto the far side by the frame's offset. An
     * arithmetic shift floors, so a negative destination coordinate lands on
     * the chunk it is in rather than the one towards zero.
     */
    public static int centreChunk(int sourceCoord, int offset) {
        return (sourceCoord + offset) >> 4;
    }
}
