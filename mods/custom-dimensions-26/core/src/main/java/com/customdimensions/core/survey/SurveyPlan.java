package com.customdimensions.core.survey;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where a survey samples: square patches of whole chunks, stratified over the
 * sampled area (one patch in each of a shuffled grid of strata, at a
 * seeded position within it), then adaptive patches centred on what the coarse
 * map found rare or rough.
 */
public final class SurveyPlan {
    public static final int DEFAULT_PATCHES = 24;
    public static final int DEFAULT_SIZE = 3;
    public static final int MAX_PATCHES = 96;
    public static final int MAX_SIZE = 8;

    /** A patch: its origin chunk, side in chunks, and why it was chosen ({@code stratified} or {@code adaptive:<reason>}). */
    public record Patch(int index, int chunkX, int chunkZ, int size, String origin) {
        public int blockX() {
            return chunkX * 16;
        }

        public int blockZ() {
            return chunkZ * 16;
        }

        public int side() {
            return size * 16;
        }

        boolean overlaps(Patch o) {
            return chunkX < o.chunkX + o.size && o.chunkX < chunkX + size && chunkZ < o.chunkZ + o.size && o.chunkZ < chunkZ + size;
        }
    }

    private SurveyPlan() {}

    /**
     * The seed patches are drawn from: the level's seed and the sampled area's radius, never the dimension's name,
     * so two dimensions of one terrain and seed sample the same ground.
     */
    public static long seed(long levelSeed, int radius) {
        long h = levelSeed * 0x9E3779B97F4A7C15L + radius;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** {@code count} stratified patches of {@code size} chunks inside the square of half-side {@code radius} blocks. */
    public static List<Patch> stratified(long seed, int radius, int count, int size) {
        int strata = (int) Math.ceil(Math.sqrt(count));
        int lowChunk = Math.floorDiv(-radius, 16);
        int highChunk = Math.floorDiv(radius, 16) - size;
        int span = Math.max(1, highChunk - lowChunk + 1);
        Random random = new Random(seed);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < strata * strata; i++) order.add(i);
        java.util.Collections.shuffle(order, random);
        List<Patch> out = new ArrayList<>();
        for (int i = 0; i < count && i < order.size(); i++) {
            int sx = order.get(i) % strata, sz = order.get(i) / strata;
            int x0 = lowChunk + sx * span / strata, x1 = lowChunk + (sx + 1) * span / strata - 1;
            int z0 = lowChunk + sz * span / strata, z1 = lowChunk + (sz + 1) * span / strata - 1;
            int cx = x0 + (x1 > x0 ? random.nextInt(x1 - x0 + 1) : 0);
            int cz = z0 + (z1 > z0 ? random.nextInt(z1 - z0 + 1) : 0);
            out.add(new Patch(out.size(), cx, cz, size, "stratified"));
        }
        return out;
    }

    /** Adds a patch centred on each block point unless it overlaps one already planned; returns the added ones. */
    public static List<Patch> addAdaptive(List<Patch> plan, List<Landforms.Pick> picks, int radius, int size, int max) {
        List<Patch> added = new ArrayList<>();
        int lowChunk = Math.floorDiv(-radius, 16);
        int highChunk = Math.floorDiv(radius, 16) - size;
        for (Landforms.Pick p : picks) {
            if (added.size() >= max) break;
            int cx = Math.max(lowChunk, Math.min(highChunk, Math.floorDiv(p.x(), 16) - size / 2));
            int cz = Math.max(lowChunk, Math.min(highChunk, Math.floorDiv(p.z(), 16) - size / 2));
            Patch candidate = new Patch(plan.size(), cx, cz, size, "adaptive:" + p.reason());
            if (plan.stream().anyMatch(candidate::overlaps)) continue;
            plan.add(candidate);
            added.add(candidate);
        }
        return added;
    }
}
