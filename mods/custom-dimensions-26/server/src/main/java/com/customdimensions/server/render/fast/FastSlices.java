package com.customdimensions.server.render.fast;

import com.customdimensions.core.render.BiomePalette;
import com.customdimensions.core.render.SliceChoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * What each pixel shows at each fixed height of a wave ({@link SliceChoice}):
 * solid ground by its block, a fluid by its block, open space under ground
 * (a cave) or under open sky, and the biome at that height. Workers write
 * disjoint pixels.
 */
public final class FastSlices {
    public static final byte CAVE = 0;
    public static final byte SKY = 1;
    public static final byte SOLID = 2;
    public static final byte FLUID = 3;
    /** Open space with ground over it. */
    public static final int CAVE_COLOUR = 0x000000;
    /** Open space with nothing solid over it. */
    public static final int SKY_COLOUR = 0x2B3442;
    /** Blocks of ground assumed over and under a slice outside the scanned window, for the material rule. */
    public static final int ASSUMED_DEPTH = 32;

    final List<SliceChoice.Slice> slices;
    final int side;
    final byte[][] kind;
    final short[][] block;
    final int[][] biome;
    private final Map<String, Integer> biomeIndex = new ConcurrentHashMap<>();
    private final List<String> biomeIds = new ArrayList<>();
    private final AtomicLongArray differs;
    private final AtomicLongArray solid;
    final LongAdder rowNanos = new LongAdder();
    final LongAdder columnNanos = new LongAdder();
    final LongAdder rowsSampled = new LongAdder();
    final LongAdder rowsFromRuns = new LongAdder();

    FastSlices(int side, List<SliceChoice.Slice> slices) {
        this.slices = List.copyOf(slices);
        this.side = side;
        int n = side * side;
        kind = new byte[slices.size()][n];
        block = new short[slices.size()][n];
        biome = new int[slices.size()][n];
        differs = new AtomicLongArray(slices.size());
        solid = new AtomicLongArray(slices.size());
        for (short[] b : block) java.util.Arrays.fill(b, FastPixels.NONE);
    }

    public int size() {
        return slices.size();
    }

    void set(int s, int i, byte k, short b, String biomeId, boolean differsFromSurface) {
        kind[s][i] = k;
        block[s][i] = b;
        biome[s][i] = biomeIndex.computeIfAbsent(biomeId, this::intern);
        if (differsFromSurface) differs.incrementAndGet(s);
        if (k == SOLID) solid.incrementAndGet(s);
    }

    private synchronized int intern(String id) {
        biomeIds.add(id);
        return biomeIds.size() - 1;
    }

    synchronized List<String> biomeIds() {
        return List.copyOf(biomeIds);
    }

    /** The share of pixels whose biome at slice {@code s} is not their surface biome. */
    double differShare(int s) {
        return (double) differs.get(s) / Math.max(1, kind[s].length);
    }

    double solidShare(int s) {
        return (double) solid.get(s) / Math.max(1, kind[s].length);
    }

    /** Slice {@code s} top-down: blocks by their map colour from {@code colours}, open space as {@link #CAVE_COLOUR} or {@link #SKY_COLOUR}. */
    int[] paint(int s, int[] colours) {
        int[] out = new int[side * side];
        for (int i = 0; i < out.length; i++) {
            byte k = kind[s][i];
            short b = block[s][i];
            if ((k == SOLID || k == FLUID) && b >= 0) out[i] = colours[b];
            else out[i] = k == SKY ? SKY_COLOUR : CAVE_COLOUR;
        }
        return out;
    }

    /** The biome at slice {@code s}, each in its {@link BiomePalette} colour. */
    int[] paintBiome(int s) {
        List<String> ids = biomeIds();
        int[] colour = new int[ids.size()];
        for (int i = 0; i < colour.length; i++) colour[i] = BiomePalette.colour(ids.get(i));
        int[] out = new int[side * side];
        for (int i = 0; i < out.length; i++) out[i] = colour[biome[s][i]];
        return out;
    }

    /** Pixels per biome at slice {@code s}. */
    Map<String, Integer> biomeCounts(int s) {
        List<String> ids = biomeIds();
        int[] n = new int[ids.size()];
        for (int b : biome[s]) n[b]++;
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n.length; i++) if (n[i] > 0) out.put(ids.get(i), n[i]);
        return out;
    }
}
