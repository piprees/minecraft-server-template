package com.customdimensions.core.survey;

import com.customdimensions.core.survey.PatchGrid.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The survey's layer maps: the sampled patches tiled in plan order, two
 * pixels a block, a two-pixel gap between them. {@code top.png} shades the top
 * surface from the 1st to the 99th percentile of its heights; {@code second.png}
 * the second surface below it where one exists (black where none);
 * {@code classes.png} the column classes; {@code biomes.png} the surface biome;
 * {@code slice_<y>.png} what each cell is at that y; {@code landforms.png} the
 * coarse map's classes over the whole border.
 */
final class LayerMaps {
    static final int SCALE = 2;
    static final int GAP = 2;
    static final int[] CLASS_COLOURS = {0x000000, 0x8a2be2, 0xff8c00, 0xe03c3c, 0x3cb4e0, 0x8fbc8f};
    static final int[] KIND_COLOURS = {0xf4f4f4, 0x6e6e6e, 0x2f6fd6, 0x7cc464, 0x1f5e1f};
    static final int[] FORM_COLOURS = {0x9acd6a, 0xc8b45a, 0x8c6e50, 0xd2a46e, 0x5a8c8c, 0xe6d27a, 0x2f6fd6, 0x000000};

    private LayerMaps() {}

    static JsonObject write(Path dir, List<PatchAnalysis> patches, Landforms landforms) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("layout", "patches tiled in plan order, " + SCALE + " px a block, " + GAP + " px gaps; the legend is in the dossier");
        if (!patches.isEmpty()) {
            List<Double> tops = new ArrayList<>();
            for (PatchAnalysis p : patches) for (int v : p.top) if (v != PatchAnalysis.NONE) tops.add((double) v);
            double[] t = tops.stream().mapToDouble(Double::doubleValue).toArray();
            double lo = Stats.percentile(t, 1), hi = Stats.percentile(t, 99);
            o.addProperty("top.png", "top surface, black " + lo + " to white " + hi);
            mosaic(dir.resolve("top.png"), patches, p -> c -> shade(p.top[c], lo, hi));
            o.addProperty("second.png", "second surface below the top, same scale; black where none");
            mosaic(dir.resolve("second.png"), patches, p -> c -> p.second[c] == PatchAnalysis.NONE ? 0 : shade(p.second[c], lo, hi));
            o.addProperty("classes.png", "void black, roofed violet, stacked orange, overhang red, cave blue, single green");
            mosaic(dir.resolve("classes.png"), patches, p -> c -> CLASS_COLOURS[p.columnClass[c]]);
            o.addProperty("biomes.png", "surface biome in the render's palette");
            mosaic(dir.resolve("biomes.png"), patches, p -> c -> Survey.biomeColour(p.biome[c] == null ? "" : p.biome[c]));
            TreeSet<Integer> ys = new TreeSet<>();
            for (PatchAnalysis p : patches) ys.addAll(p.slices.keySet());
            JsonArray slices = new JsonArray();
            for (int y : ys) {
                String name = "slice_" + y + ".png";
                mosaic(dir.resolve(name), patches, p -> {
                    byte[] s = p.slices.get(y);
                    return c -> s == null ? 0 : KIND_COLOURS[s[c]];
                });
                slices.add(name);
            }
            o.add("slices", slices);
            o.addProperty("slicesLegend", "air white, terrain grey, liquid blue, plants light green, wood and leaves dark green");
        }
        if (landforms != null) {
            int side = landforms.side();
            int[] rgb = new int[side * side];
            for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) rgb[z * side + x] = FORM_COLOURS[landforms.form(x, z).ordinal()];
            Survey.png(dir.resolve("landforms.png"), side, side, rgb);
            o.addProperty("landforms.png", "one pixel a coarse cell (" + landforms.step() + " blocks): plains, hills, mountains, plateaus, basins, "
                    + "islands, water, void in that order of colour " + hexes(FORM_COLOURS));
        }
        return o;
    }

    private static String hexes(int[] c) {
        StringBuilder b = new StringBuilder();
        for (int v : c) b.append(b.isEmpty() ? "" : " ").append(String.format("#%06x", v));
        return b.toString();
    }

    private static int shade(int v, double lo, double hi) {
        if (v == PatchAnalysis.NONE) return 0x000000;
        double f = hi <= lo ? 0.5 : Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
        int g = (int) Math.round(30 + f * 225);
        return (g << 16) | (g << 8) | g;
    }

    interface Colour {
        int at(int column);
    }

    private static void mosaic(Path out, List<PatchAnalysis> patches, java.util.function.Function<PatchAnalysis, Colour> colour) throws IOException {
        int side = patches.get(0).side;
        int cols = (int) Math.ceil(Math.sqrt(patches.size()));
        int rows = (int) Math.ceil(patches.size() / (double) cols);
        int tile = side * SCALE + GAP;
        int w = cols * tile, h = rows * tile;
        int[] rgb = new int[w * h];
        java.util.Arrays.fill(rgb, 0x202020);
        for (int i = 0; i < patches.size(); i++) {
            PatchAnalysis p = patches.get(i);
            Colour c = colour.apply(p);
            int ox = (i % cols) * tile, oy = (i / cols) * tile;
            for (int z = 0; z < p.side && z < side; z++) {
                for (int x = 0; x < p.side && x < side; x++) {
                    int v = c.at(z * p.side + x);
                    for (int dy = 0; dy < SCALE; dy++) for (int dx = 0; dx < SCALE; dx++) rgb[(oy + z * SCALE + dy) * w + ox + x * SCALE + dx] = v;
                }
            }
        }
        Survey.png(out, w, h, rgb);
    }

    static Map<Kind, Integer> legend() {
        return Map.of(Kind.AIR, KIND_COLOURS[0], Kind.SOLID, KIND_COLOURS[1], Kind.FLUID, KIND_COLOURS[2], Kind.PLANT, KIND_COLOURS[3],
                Kind.FOLIAGE, KIND_COLOURS[4]);
    }
}
