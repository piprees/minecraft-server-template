package com.customdimensions.core.render;

import java.util.List;

/**
 * One image of a wave pass: its square layers side by side, each scaled to
 * {@link #PANEL} pixels under a title, {@link #COLUMNS} to a row, then a
 * legend strip of colour swatches with labels. Pure RGB arithmetic, drawn
 * with {@link TinyFont}.
 */
public final class ContactSheet {
    public static final int PANEL = 384;
    public static final int COLUMNS = 4;
    public static final int GAP = 6;
    public static final int TITLE = 18;
    public static final int LINE = 16;
    public static final int BACKGROUND = 0x202124;
    public static final int TEXT = 0xE8E8E8;

    /** A square layer: {@code side × side} RGB under {@code title}. */
    public record Panel(String title, int side, int[] rgb) {}

    /** A legend entry: a swatch, or none when {@code colour} is negative, and its label. */
    public record Entry(int colour, String label) {}

    /** The sheet: {@code width × height} RGB. */
    public record Image(int width, int height, int[] rgb) {}

    private ContactSheet() {}

    public static Image compose(String heading, List<Panel> panels, List<Entry> legend) {
        int columns = Math.max(1, Math.min(COLUMNS, panels.size()));
        int rows = Math.ceilDiv(Math.max(1, panels.size()), columns);
        int width = GAP + columns * (PANEL + GAP);
        int legendColumns = Math.max(1, width / 320);
        int legendRows = Math.ceilDiv(legend.size(), legendColumns);
        int top = GAP + TITLE;
        int height = top + rows * (TITLE + PANEL + GAP) + GAP + legendRows * LINE + GAP;
        int[] rgb = new int[width * height];
        java.util.Arrays.fill(rgb, BACKGROUND);
        TinyFont.draw(rgb, width, height, GAP, GAP, heading, TEXT, 2);
        for (int i = 0; i < panels.size(); i++) {
            Panel p = panels.get(i);
            int x = GAP + (i % columns) * (PANEL + GAP);
            int y = top + (i / columns) * (TITLE + PANEL + GAP);
            TinyFont.draw(rgb, width, height, x, y + 2, p.title(), TEXT, 2);
            blit(rgb, width, x, y + TITLE, scale(p.rgb(), p.side(), PANEL), PANEL);
        }
        int ly = top + rows * (TITLE + PANEL + GAP) + GAP;
        int cell = (width - GAP) / legendColumns;
        for (int i = 0; i < legend.size(); i++) {
            Entry e = legend.get(i);
            int x = GAP + (i % legendColumns) * cell;
            int y = ly + (i / legendColumns) * LINE;
            int tx = x;
            if (e.colour() >= 0) {
                for (int dy = 0; dy < 12; dy++) {
                    for (int dx = 0; dx < 12; dx++) rgb[(y + dy) * width + x + dx] = dy == 0 || dx == 0 || dy == 11 || dx == 11 ? TEXT : e.colour();
                }
                tx += 16;
            }
            TinyFont.draw(rgb, width, height, tx, y + 3, e.label(), TEXT, 1);
        }
        return new Image(width, height, rgb);
    }

    /** {@code rgb} of {@code side × side} as {@code out × out}: the mean of each box when shrinking, the nearest pixel when growing. */
    public static int[] scale(int[] rgb, int side, int out) {
        int[] result = new int[out * out];
        for (int y = 0; y < out; y++) {
            int y0 = (int) ((long) y * side / out);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * side / out));
            for (int x = 0; x < out; x++) {
                int x0 = (int) ((long) x * side / out);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * side / out));
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int c = rgb[sy * side + sx];
                        r += c >> 16 & 0xFF;
                        g += c >> 8 & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                result[y * out + x] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return result;
    }

    private static void blit(int[] into, int width, int x, int y, int[] rgb, int side) {
        for (int row = 0; row < side; row++) System.arraycopy(rgb, row * side, into, (y + row) * width + x, side);
    }
}
