package com.customdimensions.core.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Writes a render: {@code <dir>/<base>-<layer>.png} for each layer and the
 * sidecar {@code <dir>/<base>.json}, each through a temporary file so a reader
 * never sees half of one. The base is {@code <seed>-r<radius>}, with
 * {@code -at<x>_<z>} after it for a render centred off 0, 0.
 */
public final class RenderFiles {
    private RenderFiles() {}

    /** What the sidecar records besides the samples. */
    public record Meta(String dimension, long seed, String seedSource, String surface, String biomeY,
                       Map<String, Long> timingsMillis, int threads) {}

    public record Written(Map<String, Path> images, Path sidecar) {}

    public static String baseName(long seed, int radius) {
        return seed + "-r" + radius;
    }

    /** {@link #baseName(long, int)}, with the centre after it when the grid is centred off 0, 0. */
    public static String baseName(long seed, RenderGrid grid) {
        String base = baseName(seed, grid.radius());
        return grid.centreX() == 0 && grid.centreZ() == 0 ? base : base + "-at" + grid.centreX() + "_" + grid.centreZ();
    }

    public static Written write(Path dir, ColumnSamples samples, List<RenderLayer> layers, Meta meta) throws IOException {
        return write(dir, baseName(meta.seed(), samples.grid().radius()), samples, layers, meta);
    }

    public static Written write(Path dir, String base, ColumnSamples samples, List<RenderLayer> layers, Meta meta) throws IOException {
        Files.createDirectories(dir);
        RenderGrid grid = samples.grid();
        long started = System.nanoTime();
        Map<String, Path> images = new LinkedHashMap<>();
        for (RenderLayer layer : layers) {
            Path out = dir.resolve(base + "-" + layer.name() + ".png");
            png(out, grid.side(), layer.paint(samples));
            images.put(layer.name(), out);
        }
        Map<String, Long> timings = new LinkedHashMap<>(meta.timingsMillis());
        timings.put("images", (System.nanoTime() - started) / 1_000_000);
        Meta written = new Meta(meta.dimension(), meta.seed(), meta.seedSource(), meta.surface(), meta.biomeY(),
                timings, meta.threads());
        Path sidecar = dir.resolve(base + ".json");
        atomic(sidecar, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(sidecar(samples, written, images)) + "\n");
        return new Written(images, sidecar);
    }

    public static void png(Path out, int side, int[] rgb) throws IOException {
        if (rgb.length != side * side) throw new IllegalArgumentException(rgb.length + " pixels for side " + side);
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, side, side, rgb, 0, side);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", tmp.toFile())) throw new IOException("no PNG writer");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void atomic(Path out, String text) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static JsonObject sidecar(ColumnSamples samples, Meta meta, Map<String, Path> images) {
        RenderGrid grid = samples.grid();
        JsonObject root = new JsonObject();
        root.addProperty("kind", "render");
        root.addProperty("dimension", meta.dimension());
        root.addProperty("seed", meta.seed());
        root.addProperty("seedSource", meta.seedSource());
        root.addProperty("radius", grid.radius());
        root.addProperty("step", grid.step());
        root.addProperty("side", grid.side());
        JsonArray centre = new JsonArray();
        centre.add(grid.centreX());
        centre.add(grid.centreZ());
        root.add("centre", centre);
        root.addProperty("pixel", "the column at the north-west corner of each step x step cell; north is up");
        root.addProperty("surface", meta.surface());
        root.addProperty("biomeY", meta.biomeY());
        root.addProperty("seaLevel", samples.seaLevel());
        JsonObject files = new JsonObject();
        images.forEach((name, path) -> files.addProperty(name, path.getFileName().toString()));
        root.add("files", files);
        JsonObject timings = new JsonObject();
        meta.timingsMillis().forEach(timings::addProperty);
        long sampleMs = meta.timingsMillis().getOrDefault("sample", 0L);
        timings.addProperty("columns", grid.columns());
        timings.addProperty("threads", meta.threads());
        timings.addProperty("columnsPerSecond", sampleMs <= 0 ? 0 : Math.round(grid.columns() * 1000.0 / sampleMs));
        root.add("timings", timings);

        JsonObject legend = new JsonObject();
        JsonArray biomes = new JsonArray();
        List<Map.Entry<String, Integer>> counts = new ArrayList<>(samples.biomeCounts().entrySet());
        counts.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Integer> e : counts) {
            JsonObject b = new JsonObject();
            b.addProperty("id", e.getKey());
            b.addProperty("colour", BiomePalette.hex(BiomePalette.colour(e.getKey())));
            b.addProperty("fixed", BiomePalette.fixed(e.getKey()));
            b.addProperty("pixels", e.getValue());
            biomes.add(b);
        }
        legend.add("biomes", biomes);
        int[] range = samples.heightRange();
        JsonObject height = new JsonObject();
        height.addProperty("lowest", range[0]);
        height.addProperty("highest", range[1]);
        int[] grey = RenderLayers.greyRange(samples);
        height.addProperty("greyFrom", grey[0]);
        height.addProperty("greyTo", grey[1]);
        height.addProperty("grey", "60 at greyFrom to 230 at greyTo (the dry surface's 1st and 99th percentile), lit from the north-west");
        height.addProperty("contourEvery", RenderLayers.BAND);
        height.addProperty("water", "blue, darker by depth below sea level");
        height.addProperty("lava", BiomePalette.hex(RenderLayers.LAVA));
        height.addProperty("empty", BiomePalette.hex(RenderLayers.EMPTY));
        legend.add("height", height);
        root.add("legend", legend);
        return root;
    }
}
