package com.customdimensions.core.render.map;

import com.customdimensions.core.render.RenderGrid;

/**
 * The web map's tile pyramid over a square border of {@code radius} blocks
 * round 0, 0: zoom 0 holds the whole border in one {@value #TILE}-pixel tile,
 * each next zoom halves the blocks per pixel, down to {@link #finest} at
 * {@link #maxZoom} ({@value #FINEST} blocks a pixel unless the border is too
 * wide for one {@link RenderGrid#WIDE_MAX_SIDE}-pixel render). Pixel {@code i}
 * of zoom {@code z} is the column at {@code -radius + i * blocksPerPixel(z)},
 * the north-west corner of its cell, so a coarse pixel's column is the fine
 * pixel {@code i << (maxZoom - z)}'s. Tiles run west to east ({@code x}) and
 * north to south ({@code y}); tiles past the border are transparent.
 */
public record MapTiles(int radius, int finest, int maxZoom) {
    public static final int TILE = 256;
    public static final int FINEST = 4;

    public MapTiles {
        if (radius < 1) throw new IllegalArgumentException("radius " + radius);
    }

    public static MapTiles of(int radius) {
        int finest = FINEST;
        while (Math.ceilDiv(2 * radius, finest) > RenderGrid.WIDE_MAX_SIDE) finest *= 2;
        int zoom = 0;
        while ((long) TILE * finest << zoom < 2L * radius) zoom++;
        return new MapTiles(radius, finest, zoom);
    }

    public int blocksPerPixel(int zoom) {
        check(zoom);
        return finest << (maxZoom - zoom);
    }

    /** Pixels across the border at {@code zoom}. */
    public int side(int zoom) {
        return Math.ceilDiv(2 * radius, blocksPerPixel(zoom));
    }

    /** Tiles across the border at {@code zoom}. */
    public int tiles(int zoom) {
        return Math.ceilDiv(side(zoom), TILE);
    }

    /** The block coordinate of pixel {@code i} at {@code zoom} (x west to east, z north to south alike). */
    public int block(int zoom, int i) {
        return -radius + i * blocksPerPixel(zoom);
    }

    /** The finest pixel holding block coordinate {@code b}, which may lie outside 0 to side - 1. */
    public int pixel(int b) {
        return Math.floorDiv(b + radius, finest);
    }

    public long tileCount() {
        long n = 0;
        for (int z = 0; z <= maxZoom; z++) n += (long) tiles(z) * tiles(z);
        return n;
    }

    private void check(int zoom) {
        if (zoom < 0 || zoom > maxZoom) throw new IllegalArgumentException("zoom " + zoom + " outside 0 to " + maxZoom);
    }
}
