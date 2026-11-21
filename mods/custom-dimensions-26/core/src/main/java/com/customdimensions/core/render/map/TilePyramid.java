package com.customdimensions.core.render.map;

import java.io.IOException;

/**
 * Cuts one layer's finest image, handed over in pieces, into tiles for every
 * zoom of a {@link MapTiles}. Each piece's finest tiles go out at once; the
 * piece is also halved into a whole-border image one zoom up, which
 * {@link #finish} halves on to zoom 0. Pixels are ARGB. A continuous layer
 * halves by the alpha-weighted mean of each 2 × 2 block (pixels past the
 * border do not count); a categorical one (biomes) keeps the north-west
 * pixel, whose column is the coarse pixel's own. A tile with no opaque pixel
 * is not written.
 */
public final class TilePyramid {
    /** Receives a finished tile: {@value MapTiles#TILE} square, ARGB, row-major. */
    @FunctionalInterface
    public interface Sink {
        void tile(int zoom, int x, int y, int[] argb) throws IOException;
    }

    private final MapTiles plan;
    private final boolean categorical;
    private final Sink sink;
    private final int[] mosaic;
    private final int mosaicSide;
    private long written;

    public TilePyramid(MapTiles plan, boolean categorical, Sink sink) {
        this.plan = plan;
        this.categorical = categorical;
        this.sink = sink;
        this.mosaicSide = plan.maxZoom() > 0 ? plan.side(plan.maxZoom() - 1) : 0;
        this.mosaic = plan.maxZoom() > 0 ? new int[mosaicSide * mosaicSide] : null;
    }

    /**
     * A piece of the finest image, {@code w × h} pixels with its north-west
     * pixel at {@code (x0, y0)}; {@code x0} and {@code y0} are multiples of
     * {@value MapTiles#TILE}, and a piece ends on a tile edge or at the border.
     */
    public synchronized void piece(int x0, int y0, int w, int h, int[] argb) throws IOException {
        int side = plan.side(plan.maxZoom());
        if (x0 % MapTiles.TILE != 0 || y0 % MapTiles.TILE != 0) throw new IllegalArgumentException("piece at " + x0 + ", " + y0);
        if (x0 + w > side || y0 + h > side) throw new IllegalArgumentException("piece past the border");
        if (((x0 + w) % MapTiles.TILE != 0 && x0 + w != side) || ((y0 + h) % MapTiles.TILE != 0 && y0 + h != side)) {
            throw new IllegalArgumentException("piece ends inside a tile");
        }
        cut(plan.maxZoom(), x0, y0, w, h, argb);
        if (mosaic != null) halveInto(argb, w, h, mosaic, mosaicSide, x0 / 2, y0 / 2);
    }

    /** Writes every zoom above the finest from the halved pieces. */
    public synchronized void finish() throws IOException {
        if (mosaic == null) return;
        int[] image = mosaic;
        int side = mosaicSide;
        for (int z = plan.maxZoom() - 1; z >= 0; z--) {
            cut(z, 0, 0, side, side, image);
            if (z == 0) break;
            int next = plan.side(z - 1);
            int[] half = new int[next * next];
            halveInto(image, side, side, half, next, 0, 0);
            image = half;
            side = next;
        }
    }

    public long written() {
        return written;
    }

    private void cut(int zoom, int x0, int y0, int w, int h, int[] argb) throws IOException {
        int t = MapTiles.TILE;
        for (int ty = y0 / t; ty * t < y0 + h; ty++) {
            for (int tx = x0 / t; tx * t < x0 + w; tx++) {
                int[] tile = new int[t * t];
                boolean any = false;
                for (int y = 0; y < t; y++) {
                    int sy = ty * t + y - y0;
                    if (sy < 0 || sy >= h) continue;
                    for (int x = 0; x < t; x++) {
                        int sx = tx * t + x - x0;
                        if (sx < 0 || sx >= w) continue;
                        int p = argb[sy * w + sx];
                        tile[y * t + x] = p;
                        if (p >>> 24 != 0) any = true;
                    }
                }
                if (any) {
                    sink.tile(zoom, tx, ty, tile);
                    written++;
                }
            }
        }
    }

    private void halveInto(int[] src, int w, int h, int[] dst, int dstSide, int dx0, int dy0) {
        int hw = Math.ceilDiv(w, 2);
        int hh = Math.ceilDiv(h, 2);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int out;
                if (categorical) {
                    out = src[2 * y * w + 2 * x];
                } else {
                    long a = 0, r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int j = 0; j < 2; j++) {
                        int sy = 2 * y + j;
                        if (sy >= h) continue;
                        for (int i = 0; i < 2; i++) {
                            int sx = 2 * x + i;
                            if (sx >= w) continue;
                            int p = src[sy * w + sx];
                            int pa = p >>> 24;
                            a += pa;
                            r += (long) ((p >> 16) & 0xFF) * pa;
                            g += (long) ((p >> 8) & 0xFF) * pa;
                            b += (long) (p & 0xFF) * pa;
                            n++;
                        }
                    }
                    out = a == 0 ? 0 : (int) Math.round((double) a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                }
                int px = dx0 + x;
                int py = dy0 + y;
                if (px < dstSide && py < dstSide) dst[py * dstSide + px] = out;
            }
        }
    }
}
