package com.customdimensions.server.render.fast;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderGrid;
import java.util.List;

/**
 * The fast render's extra images, row-major RGB, {@code side × side}. Block
 * colours come from a palette of map colours by index; a fluid is drawn in
 * its colour, darker with depth. Light falls from the north-west.
 */
public final class FastLayers {
    /** Where a cut passes through solid ground. */
    public static final int CUT_FACE = 0x2A2A2A;
    public static final int EMPTY = 0x000000;

    /** A structure start's bounding box in blocks, for the structures layer. */
    public record Footprint(String structure, int minX, int minZ, int maxX, int maxZ) {}

    private FastLayers() {}

    /** Grey relief of the surface heights: Lambert shading, light from the north-west at 45 degrees; an empty column black. */
    public static int[] hillshade(ColumnSamples s) {
        int side = s.grid().side();
        int step = s.grid().step();
        int[] out = new int[side * side];
        double lx = -0.5;
        double lz = -0.5;
        double ly = Math.sqrt(0.5);
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                if (s.empty(px, py)) {
                    out[py * side + px] = EMPTY;
                    continue;
                }
                int h = s.height(px, py);
                double dx = (height(s, px + 1, py, h) - height(s, px - 1, py, h)) / (2.0 * step);
                double dz = (height(s, px, py + 1, h) - height(s, px, py - 1, h)) / (2.0 * step);
                double len = Math.sqrt(dx * dx + dz * dz + 1);
                double lambert = (-dx * lx - dz * lz + ly) / len;
                int g = (int) Math.round(Math.max(0, Math.min(1, lambert)) * 235 + 20);
                out[py * side + px] = g << 16 | g << 8 | g;
            }
        }
        return out;
    }

    private static int height(ColumnSamples s, int px, int py, int fallback) {
        int side = s.grid().side();
        if (px < 0 || py < 0 || px >= side || py >= side || s.empty(px, py)) return fallback;
        return s.height(px, py);
    }

    /** What is seen from above: the topmost run's top block or the fluid over it, shaded by the step to the north-west neighbour. */
    public static int[] map(FastPixels p, int[] palette) {
        return blocks(p, palette, p.top, p.topBlock, p.topDepth);
    }

    /** The surface block (under a roof, the floor's), shaded by the surface heights. */
    public static int[] floor(FastPixels p, int[] palette, ColumnSamples s) {
        int side = p.side;
        short[] heights = new short[side * side];
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                heights[py * side + px] = s.empty(px, py) ? FastPixels.NO_Y : (short) (s.height(px, py) - 1);
            }
        }
        return blocks(p, palette, heights, p.floorBlock, p.floorDepth);
    }

    /** Run {@code tier + 1} from the top (the second, third, ...), black where a column has fewer runs. */
    public static int[] tier(FastPixels p, int[] palette, int tier) {
        return blocks(p, palette, p.tierTop[tier], p.tierBlock[tier], null);
    }

    /**
     * A horizontal cut at {@code cutY}: the cut face where the column is
     * solid there, else the highest run below it, darker the further below.
     */
    public static int[] cut(FastPixels p, int[] palette, int cutY) {
        int side = p.side;
        int[] out = new int[side * side];
        for (int i = 0; i < out.length; i++) {
            short y = p.cutTop[i];
            short b = p.cutBlock[i];
            if (y == FastPixels.NO_Y) out[i] = EMPTY;
            else if (y >= cutY) out[i] = CUT_FACE;
            else out[i] = scale(b < 0 ? EMPTY : palette[b], Math.max(0.35, 1 - (cutY - y) / 96.0));
        }
        return out;
    }

    private static int[] blocks(FastPixels p, int[] palette, short[] heights, short[] blocks, byte[] depths) {
        int side = p.side;
        int[] out = new int[side * side];
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                int i = py * side + px;
                short b = blocks[i];
                if (b < 0 || heights[i] == FastPixels.NO_Y) {
                    out[i] = EMPTY;
                    continue;
                }
                int rgb = palette[b];
                int depth = depths == null ? 0 : depths[i] & 0xFF;
                if (depth > 0) {
                    out[i] = scale(rgb, Math.max(0.45, 1 - depth / 40.0));
                    continue;
                }
                int h = heights[i];
                int north = py > 0 && heights[i - side] != FastPixels.NO_Y ? heights[i - side] : h;
                int west = px > 0 && heights[i - 1] != FastPixels.NO_Y ? heights[i - 1] : h;
                int rise = (h - north) + (h - west);
                out[i] = scale(rgb, rise > 0 ? 1.1 : rise < 0 ? 0.82 : 0.96);
            }
        }
        return out;
    }

    /** {@code base} at half brightness with each footprint outlined and its centre marked in the structure's colour. */
    public static int[] structures(int[] base, RenderGrid grid, List<Footprint> footprints) {
        int side = grid.side();
        int[] out = new int[base.length];
        for (int i = 0; i < out.length; i++) out[i] = scale(base[i], 0.5);
        int ox = grid.centreX() - grid.radius();
        int oz = grid.centreZ() - grid.radius();
        for (Footprint f : footprints) {
            int colour = colour(f.structure());
            int x0 = Math.floorDiv(f.minX() - ox, grid.step());
            int x1 = Math.floorDiv(f.maxX() - ox, grid.step());
            int z0 = Math.floorDiv(f.minZ() - oz, grid.step());
            int z1 = Math.floorDiv(f.maxZ() - oz, grid.step());
            for (int x = x0; x <= x1; x++) {
                plot(out, side, x, z0, colour);
                plot(out, side, x, z1, colour);
            }
            for (int z = z0; z <= z1; z++) {
                plot(out, side, x0, z, colour);
                plot(out, side, x1, z, colour);
            }
            int cx = (x0 + x1) / 2;
            int cz = (z0 + z1) / 2;
            for (int d = -1; d <= 1; d++) {
                plot(out, side, cx + d, cz, 0xFFFFFF);
                plot(out, side, cx, cz + d, 0xFFFFFF);
            }
        }
        return out;
    }

    /** A place the plan names, in blocks: a forced start or a want ({@code structure} its id), or the spawn. */
    public record Marker(String kind, String structure, int x, int z) {}

    /** A marker's half side on the image: {@value #MARKER_BLOCKS} blocks, at least {@value #MARKER_PIXELS} pixels. */
    static final int MARKER_BLOCKS = 24;
    static final int MARKER_PIXELS = 3;

    /** {@code base} at half brightness with each footprint outlined, and each marker no footprint holds drawn over it. */
    public static int[] structures(int[] base, RenderGrid grid, List<Footprint> footprints, List<Marker> markers) {
        int[] out = structures(base, grid, footprints);
        List<Marker> loose = markers.stream().filter(m -> footprints.stream()
                .noneMatch(f -> m.x() >= f.minX() && m.x() <= f.maxX() && m.z() >= f.minZ() && m.z() <= f.maxZ())).toList();
        return markers(out, grid, loose);
    }

    /**
     * {@code base} with each marker drawn: a square outline in the
     * structure's colour round a white cross, the spawn a white diamond.
     */
    public static int[] markers(int[] base, RenderGrid grid, List<Marker> markers) {
        int side = grid.side();
        int[] out = base.clone();
        int ox = grid.centreX() - grid.radius();
        int oz = grid.centreZ() - grid.radius();
        int half = Math.max(MARKER_PIXELS, MARKER_BLOCKS / grid.step());
        for (Marker m : markers) {
            int cx = Math.floorDiv(m.x() - ox, grid.step());
            int cz = Math.floorDiv(m.z() - oz, grid.step());
            if ("spawn".equals(m.kind())) {
                for (int d = -half; d <= half; d++) {
                    int w = half - Math.abs(d);
                    plot(out, side, cx - w, cz + d, 0xFFFFFF);
                    plot(out, side, cx + w, cz + d, 0xFFFFFF);
                }
                continue;
            }
            int colour = colour(m.structure());
            for (int d = -half; d <= half; d++) {
                for (int t = 0; t < 2; t++) {
                    plot(out, side, cx + d, cz - half + t, colour);
                    plot(out, side, cx + d, cz + half - t, colour);
                    plot(out, side, cx - half + t, cz + d, colour);
                    plot(out, side, cx + half - t, cz + d, colour);
                }
            }
            for (int d = -half / 2; d <= half / 2; d++) {
                plot(out, side, cx + d, cz, 0xFFFFFF);
                plot(out, side, cx, cz + d, 0xFFFFFF);
            }
        }
        return out;
    }

    /** Whether an image holds nothing but {@link #EMPTY}: a world with no ground in view. */
    public static boolean bare(int[] rgb) {
        for (int p : rgb) if ((p & 0xFFFFFF) != EMPTY) return false;
        return true;
    }

    private static void plot(int[] out, int side, int x, int z, int colour) {
        if (x >= 0 && z >= 0 && x < side && z < side) out[z * side + x] = colour;
    }

    /** A structure's colour, by id. */
    public static int colour(String structure) {
        int h = structure.hashCode();
        float hue = ((h >>> 8) & 0xFFFF) / 65536f;
        return java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) & 0xFFFFFF;
    }

    static int scale(int rgb, double f) {
        int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = (int) Math.min(255, Math.round((rgb & 0xFF) * f));
        return r << 16 | g << 8 | b;
    }
}
