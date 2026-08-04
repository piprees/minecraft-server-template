package com.customdimensions.command;

import java.util.List;

/**
 * Pure replay of vanilla's multi-entry weighted selection from
 * {@code ChunkGenerator.setStructureStarts}: the carver-seed draw that
 * decides which structure vanilla would TRY first at a noise site.
 *
 * This is a read-only instrument -- it does not generate anything, does not
 * simulate rejection fall-through, and does not touch game state. It answers
 * one question: "which structure would vanilla's first nextInt(totalWeight)
 * draw select at this chunk?" -- so the harness can compare that against the
 * noise-managed assignment and identify carver-divergent sites.
 *
 * The algorithm is verified against the decompiled Yarn 1.21.1 source of
 * ChunkGenerator.setStructureStarts (line-for-line: new ChunkRandom(new
 * CheckedRandom(0L)); setCarverSeed(structureSeed, chunkX, chunkZ);
 * j = nextInt(totalWeight); walk entries in LIST ORDER subtracting weights).
 *
 * Pure, Bootstrap-free, unit-testable.
 */
public final class CarverDraw {

    private CarverDraw() {
    }

    /** A pool entry in vanilla's list order (NOT sorted -- vanilla uses
     *  registry iteration order, which is insertion order). */
    public record Entry(String structureId, int weight) {
    }

    /** The result of a single carver draw. */
    public record DrawResult(String vanillaDraw, int drawnIndex, int totalWeight, int j) {
    }

    /**
     * Replays vanilla's first-draw selection exactly.
     *
     * <p>Algorithm (from bytecode-verified Yarn 1.21.1 ChunkGenerator):
     * <pre>
     *   ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
     *   random.setCarverSeed(structureSeed, chunkX, chunkZ);
     *   int totalWeight = sum(entry.weight for entry in entries);
     *   int j = random.nextInt(totalWeight);
     *   for each entry in entries (LIST ORDER):
     *       j -= entry.weight;
     *       if j &lt; 0: selected = entry; break;
     * </pre>
     *
     * <p>This method mirrors that logic using a standalone LCG implementation
     * (same constants as java.util.Random / CheckedRandom) so it is
     * Bootstrap-free and unit-testable without Minecraft classes.
     *
     * @param entries     the set's structures() list IN THEIR ORIGINAL ORDER
     *                    (registry iteration order -- NOT sorted by id)
     * @param structureSeed the calculator's structure seed (world seed in
     *                      vanilla; our mixin passes the same value)
     * @param chunkX      chunk X coordinate
     * @param chunkZ      chunk Z coordinate
     * @return the draw result, or null if the pool is empty or has zero total weight
     */
    public static DrawResult draw(List<Entry> entries, long structureSeed,
                                  int chunkX, int chunkZ) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        int totalWeight = 0;
        for (Entry e : entries) {
            totalWeight += e.weight();
        }
        if (totalWeight <= 0) {
            return null;
        }

        // Replay: ChunkRandom(new CheckedRandom(0L)).setCarverSeed(...)
        long lcgState = carverSeed(structureSeed, chunkX, chunkZ);

        // nextInt(totalWeight): java.util.Random LCG
        int j = lcgNextInt(lcgState, totalWeight);

        // Walk entries in list order, subtracting weights
        int k = weightWalk(entries, j);
        return new DrawResult(entries.get(k).structureId(), k, totalWeight, j);
    }

    /**
     * Pure weight-walk given a fixed j value. Extracted for unit testing
     * without the LCG chain.
     *
     * @param entries the entries in list order
     * @param j       the random value (0 &lt;= j &lt; totalWeight)
     * @return the index of the selected entry
     */
    public static int weightWalk(List<Entry> entries, int j) {
        int k = 0;
        int remaining = j;
        for (Entry e : entries) {
            remaining -= e.weight();
            if (remaining < 0) {
                break;
            }
            k++;
        }
        if (k >= entries.size()) {
            k = entries.size() - 1;
        }
        return k;
    }

    // --- LCG chain mirroring java.util.Random / CheckedRandom ---------------

    private static final long LCG_MULTIPLIER = 0x5DEECE66DL;
    private static final long LCG_INCREMENT = 0xBL;
    private static final long SEED_MASK = (1L << 48) - 1;

    /**
     * Computes the LCG seed state after ChunkRandom(new CheckedRandom(0L))
     * followed by setCarverSeed(worldSeed, chunkX, chunkZ).
     *
     * <pre>
     * // ChunkRandom constructor: super(0L) -> CheckedRandom(0L) -> setSeed(0)
     * // but ChunkRandom.setSeed delegates to baseRandom.setSeed when baseRandom != null
     * // In the constructor call super(0L), baseRandom is null, so super.setSeed(0) runs.
     * // Then baseRandom is assigned.
     * //
     * // setCarverSeed(worldSeed, chunkX, chunkZ):
     * //   this.setSeed(worldSeed)  -> baseRandom.setSeed(worldSeed)
     * //   long l = this.nextLong() -> baseRandom.next(32) twice (via next() override)
     * //   long m = this.nextLong()
     * //   long n = chunkX * l ^ chunkZ * m ^ worldSeed
     * //   this.setSeed(n)          -> baseRandom.setSeed(n)
     * </pre>
     *
     * Returns the internal LCG state AFTER the final setSeed(n), ready for
     * nextInt to consume.
     */
    static long carverSeed(long structureSeed, int chunkX, int chunkZ) {
        // setSeed(worldSeed)
        long state = initSeed(structureSeed);

        // nextLong() = (next(32) << 32) + next(32)
        // next(32) advances then extracts bits
        long s1 = advance(state);
        int hi1 = (int) (s1 >>> 16);
        long s2 = advance(s1);
        int lo1 = (int) (s2 >>> 16);
        long l = ((long) hi1 << 32) + lo1;
        state = s2;

        // nextLong() again
        s1 = advance(state);
        hi1 = (int) (s1 >>> 16);
        s2 = advance(s1);
        lo1 = (int) (s2 >>> 16);
        long m = ((long) hi1 << 32) + lo1;

        // n = chunkX * l ^ chunkZ * m ^ worldSeed
        long n = (long) chunkX * l ^ (long) chunkZ * m ^ structureSeed;

        // setSeed(n)
        return initSeed(n);
    }

    /** java.util.Random.setSeed: internal = (seed ^ MULTIPLIER) & MASK */
    private static long initSeed(long seed) {
        return (seed ^ LCG_MULTIPLIER) & SEED_MASK;
    }

    /** One LCG step: (state * MULT + INC) & MASK */
    private static long advance(long state) {
        return (state * LCG_MULTIPLIER + LCG_INCREMENT) & SEED_MASK;
    }

    /**
     * nextInt(bound) from java.util.Random: rejection sampling to avoid
     * modulo bias when bound is not a power of 2.
     *
     * The state passed is the CURRENT internal state; next(31) advances
     * it once and returns the top 31 bits.
     */
    static int lcgNextInt(long state, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        // next(31): advance, then extract top 31 bits of the 48-bit state
        if ((bound & (bound - 1)) == 0) {
            // Power of 2 fast path: (int)((bound * (long)next(31)) >> 31)
            long s1 = advance(state);
            int bits = (int) (s1 >>> 17);   // 31 bits
            return (int) ((bound * (long) bits) >> 31);
        }
        // General case with rejection
        long s = state;
        int bits, val;
        do {
            s = advance(s);
            bits = (int) (s >>> 17);        // next(31)
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }
}
