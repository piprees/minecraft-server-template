package com.customdimensions.core.render;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structure starts as 5-pixel squares, one colour per structure, over the
 * biome layer at a third of its brightness.
 */
public final class StructureLayer implements RenderLayer {

    /** A structure start at a block position. */
    public record Marker(String structure, int x, int z) {}

    private final List<Marker> markers;

    public StructureLayer(List<Marker> markers) {
        this.markers = List.copyOf(markers);
    }

    @Override
    public String name() {
        return "structures";
    }

    @Override
    public int[] paint(ColumnSamples s) {
        RenderGrid grid = s.grid();
        int side = grid.side();
        int[] out = RenderLayers.BIOME.paint(s);
        for (int i = 0; i < out.length; i++) {
            int c = out[i];
            out[i] = ((c >> 16 & 0xFF) / 3) << 16 | ((c >> 8 & 0xFF) / 3) << 8 | (c & 0xFF) / 3;
        }
        for (Marker m : markers) {
            int px = Math.floorDiv(m.x() - (grid.centreX() - grid.radius()), grid.step());
            int py = Math.floorDiv(m.z() - (grid.centreZ() - grid.radius()), grid.step());
            int colour = colour(m.structure());
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int x = px + dx;
                    int y = py + dy;
                    if (x < 0 || y < 0 || x >= side || y >= side) continue;
                    boolean rim = Math.abs(dx) == 2 || Math.abs(dy) == 2;
                    out[y * side + x] = rim ? 0xFFFFFF : colour;
                }
            }
        }
        return out;
    }

    /** Each structure's colour, by id. */
    public Map<String, String> legend() {
        Map<String, String> out = new TreeMap<>();
        for (Marker m : markers) out.put(m.structure(), BiomePalette.hex(colour(m.structure())));
        return out;
    }

    static int colour(String structure) {
        int h = structure.hashCode();
        float hue = ((h >>> 8) & 0xFFFF) / 65536f;
        return java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) & 0xFFFFFF;
    }
}
