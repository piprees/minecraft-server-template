package com.customdimensions.core.render;

import java.util.List;

/** The layers a render draws: surface biome and surface height, and zone outlines for a dimension with zones. */
public final class RenderLayers {
    /** Pixels whose column holds no solid block. */
    public static final int EMPTY = 0x000000;
    public static final int LAVA = 0xD8581C;
    /** Contour interval of the height layer, in blocks. */
    public static final int BAND = 16;

    private RenderLayers() {}

    public static List<RenderLayer> standard() {
        return List.of(BIOME, HEIGHT);
    }

    /** The standard layers, and {@link #ZONES} when the samples carry zones. */
    public static List<RenderLayer> forSamples(ColumnSamples samples) {
        return samples.hasZones() ? List.of(BIOME, HEIGHT, ZONES) : standard();
    }

    /** Colours zones take in turn on {@link #ZONES}. */
    static final int[] ZONE_COLOURS = {0xE8A33D, 0x4FB3D9, 0xC45AB3, 0x7BC043, 0xF25F5C, 0x9D8DF1};

    /**
     * The height layer tinted by each zone's colour in proportion to its mask,
     * a white line where the mask crosses one half (where its biomes take over)
     * and a black line where it reaches zero (the outer edge of its blend).
     */
    public static final RenderLayer ZONES = new RenderLayer() {
        @Override
        public String name() {
            return "zones";
        }

        @Override
        public int[] paint(ColumnSamples s) {
            int side = s.grid().side();
            int[] range = greyRange(s);
            int span = Math.max(1, range[1] - range[0]);
            int[] out = new int[side * side];
            for (int py = 0; py < side; py++) {
                for (int px = 0; px < side; px++) {
                    int base = shade(s, px, py, range[0], span);
                    int zone = s.zone(px, py);
                    double w = s.zoneWeight(px, py);
                    int colour = zone < 0 ? base : mix(base, ZONE_COLOURS[zone % ZONE_COLOURS.length], 0.55 * w);
                    if (crosses(s, px, py, 0.5)) colour = 0xFFFFFF;
                    else if (crosses(s, px, py, 0.0)) colour = 0x000000;
                    out[py * side + px] = colour;
                }
            }
            return out;
        }
    };

    /** Whether the mask at a pixel is above {@code level} while a right or lower neighbour's is not, or the reverse. */
    static boolean crosses(ColumnSamples s, int px, int py, double level) {
        int side = s.grid().side();
        boolean here = above(s, px, py, level);
        return px + 1 < side && above(s, px + 1, py, level) != here || py + 1 < side && above(s, px, py + 1, level) != here;
    }

    private static boolean above(ColumnSamples s, int px, int py, double level) {
        return s.zone(px, py) >= 0 && (level == 0 ? s.zoneWeight(px, py) > 0 : s.zoneWeight(px, py) >= level);
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    /** Each pixel in its surface biome's {@link BiomePalette} colour. */
    public static final RenderLayer BIOME = new RenderLayer() {
        @Override
        public String name() {
            return "biome";
        }

        @Override
        public int[] paint(ColumnSamples s) {
            int side = s.grid().side();
            int[] out = new int[side * side];
            for (int py = 0; py < side; py++) {
                for (int px = 0; px < side; px++) out[py * side + px] = BiomePalette.colour(s.biome(px, py));
            }
            return out;
        }
    };

    /**
     * Grey from dark to light across the dry surface's 1st to 99th percentile
     * of height, a darker contour line where a {@link #BAND}-block band
     * changes, lit from the north-west; water blue, darker with depth; lava
     * orange; an empty column black.
     */
    public static final RenderLayer HEIGHT = new RenderLayer() {
        @Override
        public String name() {
            return "height";
        }

        @Override
        public int[] paint(ColumnSamples s) {
            int side = s.grid().side();
            int[] range = greyRange(s);
            int span = Math.max(1, range[1] - range[0]);
            int[] out = new int[side * side];
            for (int py = 0; py < side; py++) {
                for (int px = 0; px < side; px++) {
                    out[py * side + px] = shade(s, px, py, range[0], span);
                }
            }
            return out;
        }
    };

    /** The 1st and 99th percentile of the dry surface's height; the full range when nothing is dry. */
    public static int[] greyRange(ColumnSamples s) {
        int side = s.grid().side();
        int[] dry = new int[side * side];
        int n = 0;
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                if (!s.empty(px, py) && s.fluid(px, py) == ColumnSamples.Fluid.NONE) dry[n++] = s.height(px, py);
            }
        }
        if (n == 0) return s.heightRange();
        java.util.Arrays.sort(dry, 0, n);
        return new int[] {dry[(int) ((n - 1) * 0.01)], dry[(int) ((n - 1) * 0.99)]};
    }

    static int shade(ColumnSamples s, int px, int py, int lo, int span) {
        if (s.empty(px, py)) return EMPTY;
        int h = s.height(px, py);
        ColumnSamples.Fluid fluid = s.fluid(px, py);
        if (fluid == ColumnSamples.Fluid.LAVA) return LAVA;
        if (fluid == ColumnSamples.Fluid.WATER) {
            int depth = Math.max(0, s.seaLevel() - h);
            int b = Math.max(90, 230 - depth * 4);
            return (b / 6) << 16 | (b / 2) << 8 | b;
        }
        double grey = 60 + 170.0 * Math.max(0, Math.min(span, h - lo)) / span;
        int north = py > 0 && !s.empty(px, py - 1) ? s.height(px, py - 1) : h;
        int west = px > 0 && !s.empty(px - 1, py) ? s.height(px - 1, py) : h;
        double slope = (h - north) + (h - west);
        grey *= 1 + Math.max(-0.35, Math.min(0.35, slope * 0.06 / s.grid().step()));
        int band = Math.floorDiv(h, BAND);
        if (band != Math.floorDiv(north, BAND) || band != Math.floorDiv(west, BAND)) grey *= 0.8;
        int g = (int) Math.max(0, Math.min(255, Math.round(grey)));
        return g << 16 | g << 8 | g;
    }
}
