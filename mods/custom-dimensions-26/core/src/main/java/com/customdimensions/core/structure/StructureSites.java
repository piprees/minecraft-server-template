package com.customdimensions.core.structure;

import com.customdimensions.core.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BiPredicate;

/**
 * Chooses the chunk a forced start or a want lands in. Candidates are every
 * chunk whose centre lies in the ring, in an order fixed by the seed; the
 * first the predicate accepts (the structure's biome is there) wins, else the
 * first candidate, which then places with the biome check bypassed. A forced
 * start or a want tests up to {@link #PLACED_TESTS} candidates, so a structure
 * whose biomes are rare in its ring still finds one; a noise site's settling
 * tests {@link #MAX_TESTS}.
 */
public final class StructureSites {

    /** At most this many candidates are tested against the predicate. */
    public static final int MAX_TESTS = 96;
    /** At most this many candidates are tested for a forced start or a want ({@link #choosePlaced}). */
    public static final int PLACED_TESTS = 20000;

    /** The chosen chunk and whether a candidate passed the predicate. */
    public record Site(ChunkPos chunk, boolean matched) {}

    private StructureSites() {
    }

    /**
     * A chunk whose centre lies between {@code minBlocks} and {@code maxBlocks}
     * of ({@code centreX}, {@code centreZ}); {@code maxBlocks} 0 is the centre's own chunk.
     */
    public static Site choose(long seed, int centreX, int centreZ, int minBlocks, int maxBlocks,
                              BiPredicate<Integer, Integer> accepts) {
        return choose(seed, centreX, centreZ, minBlocks, maxBlocks, accepts, MAX_TESTS);
    }

    /** As {@link #choose}, testing up to {@link #PLACED_TESTS} candidates: a forced start's or a want's site. */
    public static Site choosePlaced(long seed, int centreX, int centreZ, int minBlocks, int maxBlocks,
                                    BiPredicate<Integer, Integer> accepts) {
        return choose(seed, centreX, centreZ, minBlocks, maxBlocks, accepts, PLACED_TESTS);
    }

    private static Site choose(long seed, int centreX, int centreZ, int minBlocks, int maxBlocks,
                               BiPredicate<Integer, Integer> accepts, int maxTests) {
        if (maxBlocks <= 0) {
            ChunkPos c = new ChunkPos(Math.floorDiv(centreX, 16), Math.floorDiv(centreZ, 16));
            return new Site(c, accepts.test(c.x(), c.z()));
        }
        List<ChunkPos> ring = ring(centreX, centreZ, minBlocks, maxBlocks);
        if (ring.isEmpty()) {
            ChunkPos c = new ChunkPos(Math.floorDiv(centreX + (minBlocks + maxBlocks) / 2, 16), Math.floorDiv(centreZ, 16));
            return new Site(c, false);
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = ring.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            ChunkPos t = ring.get(i);
            ring.set(i, ring.get(j));
            ring.set(j, t);
        }
        int tests = Math.min(maxTests, ring.size());
        for (int i = 0; i < tests; i++) {
            ChunkPos c = ring.get(i);
            if (accepts.test(c.x(), c.z())) {
                return new Site(c, true);
            }
        }
        return new Site(ring.get(0), false);
    }

    /**
     * A noise group's sites settled where they can be filled: a site the
     * predicate accepts (a pooled structure's biome is there) stays; any
     * other moves to the chunk {@link #choose} finds within {@code maxBlocks}
     * of it, or stays when none is found. On a world of islands in a void the
     * field's sites otherwise fall mostly in the void, where nothing starts.
     * Order kept; two sites settling on one chunk are one.
     */
    public static List<ChunkPos> settle(List<ChunkPos> sites, long seed, int maxBlocks, BiPredicate<Integer, Integer> accepts) {
        java.util.LinkedHashSet<ChunkPos> out = new java.util.LinkedHashSet<>();
        for (ChunkPos site : sites) {
            if (accepts.test(site.x(), site.z())) {
                out.add(site);
                continue;
            }
            Site moved = choose(seed ^ (31L * site.x() + site.z()) * 0x9E3779B97F4A7C15L, site.x() * 16 + 8, site.z() * 16 + 8, 0,
                    maxBlocks, accepts);
            out.add(moved.matched() ? moved.chunk() : site);
        }
        return List.copyOf(out);
    }

    /** Every chunk whose centre lies within the ring, row by row. */
    static List<ChunkPos> ring(int centreX, int centreZ, int minBlocks, int maxBlocks) {
        List<ChunkPos> out = new ArrayList<>();
        long min2 = (long) minBlocks * minBlocks;
        long max2 = (long) maxBlocks * maxBlocks;
        int lo = Math.floorDiv(centreX - maxBlocks, 16);
        int hi = Math.floorDiv(centreX + maxBlocks, 16);
        int loZ = Math.floorDiv(centreZ - maxBlocks, 16);
        int hiZ = Math.floorDiv(centreZ + maxBlocks, 16);
        for (int cz = loZ; cz <= hiZ; cz++) {
            for (int cx = lo; cx <= hi; cx++) {
                long dx = cx * 16L + 8 - centreX;
                long dz = cz * 16L + 8 - centreZ;
                long d2 = dx * dx + dz * dz;
                if (d2 >= min2 && d2 <= max2) {
                    out.add(new ChunkPos(cx, cz));
                }
            }
        }
        return out;
    }

    /** Distance in blocks from a chunk's centre to a point. */
    public static double distance(ChunkPos chunk, int x, int z) {
        return Math.hypot(chunk.x() * 16.0 + 8 - x, chunk.z() * 16.0 + 8 - z);
    }

    /** A per-purpose seed from the level seed and a name. */
    public static long seed(long levelSeed, String name) {
        long h = levelSeed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < name.length(); i++) {
            h = (h ^ name.charAt(i)) * 0x100000001B3L;
        }
        return h ^ (h >>> 29);
    }
}
