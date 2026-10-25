package com.customdimensions.core.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a render sampled, one entry per pixel of its {@link RenderGrid}:
 * surface height, the fluid over the surface and the surface biome. Workers
 * write disjoint pixels from any thread; biome ids are interned once each.
 */
public final class ColumnSamples {
    /** The fluid directly above a column's surface. */
    public enum Fluid { NONE, WATER, LAVA, OTHER }

    private final RenderGrid grid;
    private final int minY;
    private final int maxY;
    private final int seaLevel;
    private final int[] height;
    private final byte[] fluid;
    private final int[] biome;
    private final Map<String, Integer> biomeIndex = new ConcurrentHashMap<>();
    private final List<String> biomeIds = new ArrayList<>();

    /** {@code minY} and {@code maxY} bound the dimension; a column at {@code minY} has no solid block. */
    public ColumnSamples(RenderGrid grid, int minY, int maxY, int seaLevel) {
        this.grid = grid;
        this.minY = minY;
        this.maxY = maxY;
        this.seaLevel = seaLevel;
        int n = Math.toIntExact(grid.columns());
        this.height = new int[n];
        this.fluid = new byte[n];
        this.biome = new int[n];
    }

    public void set(int px, int py, int surface, Fluid over, String biomeId) {
        int i = py * grid.side() + px;
        height[i] = surface;
        fluid[i] = (byte) over.ordinal();
        biome[i] = biomeIndex.computeIfAbsent(biomeId, this::intern);
    }

    private synchronized int intern(String id) {
        biomeIds.add(id);
        return biomeIds.size() - 1;
    }

    public RenderGrid grid() {
        return grid;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int seaLevel() {
        return seaLevel;
    }

    public int height(int px, int py) {
        return height[py * grid.side() + px];
    }

    public Fluid fluid(int px, int py) {
        return Fluid.values()[fluid[py * grid.side() + px]];
    }

    /** True when the column holds no solid block. */
    public boolean empty(int px, int py) {
        return height(px, py) <= minY;
    }

    public String biome(int px, int py) {
        return biomeId(biome[py * grid.side() + px]);
    }

    public synchronized String biomeId(int index) {
        return biomeIds.get(index);
    }

    /** Pixels per biome id, in first-seen order. */
    public synchronized Map<String, Integer> biomeCounts() {
        int[] counts = new int[biomeIds.size()];
        for (int b : biome) counts[b]++;
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < counts.length; i++) out.put(biomeIds.get(i), counts[i]);
        return out;
    }

    /** The lowest and highest surface of the non-empty columns, or {@code minY, minY} when every column is empty. */
    public int[] heightRange() {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int h : height) {
            if (h <= minY) continue;
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        return lo > hi ? new int[] {minY, minY} : new int[] {lo, hi};
    }
}
