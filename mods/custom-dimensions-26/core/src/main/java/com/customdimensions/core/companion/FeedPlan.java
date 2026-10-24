package com.customdimensions.core.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which destination chunks a companion drawing the far side is sent, and in
 * what order.
 *
 * <p>A renderer builds a chunk only once it and all eight neighbours have
 * arrived, so a cone of columns alone builds nothing. A filled core around the
 * arrival gives the renderer somewhere to start; beyond it a column is fed only
 * if the line from the eye to it crosses the opening, reduced to the horizontal
 * plane. Chunks go out nearest-first so the view warms up outward.
 */
public final class FeedPlan {
    /** Ceiling on the fed radius, in chunks; the most a client may declare. */
    public static final int MAX_RADIUS = ViewPreference.MAX_RENDER_DISTANCE;

    /** Chunks each side of the arrival fed whatever the cone says; 2 makes the 3x3 interior buildable. */
    public static final int CORE_RADIUS = 2;

    /** Which world axis the opening's normal runs along; {@code Y} is a flat opening, fed as a disc. */
    public enum Normal { X, Y, Z }

    private FeedPlan() {
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /**
     * Chunk columns the core runs forward from the arrival for a client whose
     * local view reaches {@code viewDepthBlocks} past the opening, plus one
     * because the aperture sits anywhere inside its own chunk.
     */
    public static int coreDepth(int viewDepthBlocks) {
        return Math.ceilDiv(Math.max(0, viewDepthBlocks), 16) + 1;
    }

    /** The opening's surface on the normal axis: the aperture block's far face. */
    public static double surface(double planeBlock, boolean towardsHigh) {
        return planeBlock + (towardsHigh ? 1.0 : 0.0);
    }

    /**
     * Whether a column is seen through the opening from an eye, in the
     * horizontal plane. {@code A} is the opening's in-plane horizontal axis and
     * {@code N} its normal axis. An eye level with the plane sees nothing.
     */
    public static boolean throughOpening(double eyeA, double eyeN, double a0, double a1,
                                         double planeN, double colA, double colN) {
        double eyeToPlane = planeN - eyeN;
        if (Math.abs(eyeToPlane) < 1.0e-6) {
            return false;
        }
        double t = eyeToPlane / (colN - eyeN);
        // t in (0, 1) is the only case the column is beyond the plane.
        if (!(t > 0.0) || !(t < 1.0)) {
            return false;
        }
        double crossing = eyeA + (colA - eyeA) * t;
        return crossing >= a0 && crossing <= a1;
    }

    /**
     * Whether an offset from the arrival chunk is in the core: {@code coreDepth}
     * columns forward along the far side, {@link #CORE_RADIUS} either side and
     * {@code CORE_RADIUS} back. A flat opening keeps the square.
     */
    public static boolean inCore(int ox, int oz, Normal normal, boolean towardsHigh, int coreDepth) {
        if (normal == Normal.Y) {
            return Math.abs(ox) <= CORE_RADIUS && Math.abs(oz) <= CORE_RADIUS;
        }
        int along = normal == Normal.X ? ox : oz;
        int across = normal == Normal.X ? oz : ox;
        int forward = towardsHigh ? along : -along;
        return forward >= -CORE_RADIUS && forward < coreDepth && Math.abs(across) <= CORE_RADIUS;
    }

    /** One opening seen from one eye, in source-world block coordinates. */
    public record Sight(double eyeA, double eyeN, double a0, double a1, double planeN, int dx, int dz,
                        Normal normal, boolean towardsHigh) {
    }

    /**
     * The next chunks to send, nearest-first, skipping {@code sent}. The core
     * around the arrival is always a candidate; everything beyond it must be
     * within {@code radius} and in the cone. {@code dx}/{@code dz} map a source
     * column to its destination column, so a destination chunk maps back by
     * subtracting them.
     */
    public static List<Long> nextChunks(int centreChunkX, int centreChunkZ, int radius, Sight sight,
                                        int coreDepth, Set<Long> sent, int budget) {
        List<Long> picked = new ArrayList<>();
        if (budget <= 0 || radius <= 0 || sight == null) {
            return picked;
        }
        int r = Math.min(MAX_RADIUS, radius);
        List<long[]> candidates = new ArrayList<>();
        int span = Math.max(r, Math.max(CORE_RADIUS, coreDepth));
        for (int ox = -span; ox <= span; ox++) {
            for (int oz = -span; oz <= span; oz++) {
                boolean core = inCore(ox, oz, sight.normal(), sight.towardsHigh(), coreDepth);
                if (!core && ox * ox + oz * oz > r * r) {
                    continue;
                }
                long key = chunkKey(centreChunkX + ox, centreChunkZ + oz);
                if (sent.contains(key)) {
                    continue;
                }
                if (!core && !chunkThroughOpening(centreChunkX + ox, centreChunkZ + oz, sight)) {
                    continue;
                }
                candidates.add(new long[]{(long) ox * ox + (long) oz * oz, key});
            }
        }
        candidates.sort((l, rr) -> l[0] != rr[0] ? Long.compare(l[0], rr[0]) : Long.compare(l[1], rr[1]));
        for (long[] c : candidates) {
            if (picked.size() >= budget) {
                break;
            }
            picked.add(c[1]);
        }
        return picked;
    }

    /** A chunk is in the wedge if any of its four corners is. */
    static boolean chunkThroughOpening(int chunkX, int chunkZ, Sight s) {
        if (s.normal() == Normal.Y) {
            return true;
        }
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                double blockX = ((chunkX + cx) << 4) - s.dx();
                double blockZ = ((chunkZ + cz) << 4) - s.dz();
                double colA = s.normal() == Normal.Z ? blockX : blockZ;
                double colN = s.normal() == Normal.Z ? blockZ : blockX;
                if (throughOpening(s.eyeA(), s.eyeN(), s.a0(), s.a1(), s.planeN(), colA, colN)) {
                    return true;
                }
            }
        }
        return false;
    }
}
