package com.customdimensions.server.map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The web map's site: its root ({@value #ROOT_PROPERTY}, else
 * {@code map-web} in the server's folder), the viewer shell copied from the
 * mod jar ({@value #SHELL}: {@code index.html}, {@code app.js},
 * {@code app.css}) whenever it differs, and {@value #MANIFEST} listing every
 * dimension, exported or not, with what the viewer reads:
 * {@code slug, name, description, spawn, background, renderedAt, exploredAt,
 * version, thumb, thumbVersion, layers, explored}. A dimension's files are
 * under {@code maps/<slug>/}; {@value #FINISHED} is stamped when an export ends.
 */
public final class MapSite {
    public static final String ROOT_PROPERTY = "customdimensions.map.out";
    public static final String MANIFEST = "manifest.json";
    public static final String FINISHED = ".export-finished";
    public static final String SHELL = "/customdimensions/map-shell/";
    public static final List<String> SHELL_FILES = List.of("index.html", "app.js", "app.css");

    private MapSite() {}

    /** One dimension as the manifest lists it. */
    public record Entry(String slug, String description, int[] spawn) {}

    public static Path root(Path serverDir) {
        String set = System.getProperty(ROOT_PROPERTY);
        return set != null && !set.isBlank() ? Path.of(set) : serverDir.resolve("map-web");
    }

    /** Stamps {@value #FINISHED} with a one-line summary: the idle task's sign that an export ended. */
    public static void finished(Path root, String summary) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve(FINISHED + ".tmp");
        Files.writeString(tmp, summary + "\n");
        Files.move(tmp, root.resolve(FINISHED), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static Path dimension(Path root, String slug) {
        return root.resolve("maps").resolve(slug);
    }

    /** Copies the viewer shell into {@code root} where a file is missing or differs. */
    public static void shell(Path root) throws IOException {
        Files.createDirectories(root);
        for (String name : SHELL_FILES) {
            try (InputStream in = MapSite.class.getResourceAsStream(SHELL + name)) {
                if (in == null) throw new IOException("missing " + SHELL + name);
                byte[] bytes = in.readAllBytes();
                Path out = root.resolve(name);
                if (Files.isRegularFile(out) && Arrays.equals(Files.readAllBytes(out), bytes)) continue;
                Path tmp = out.resolveSibling(name + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    /** Rewrites {@value #MANIFEST} for {@code entries}, in their order, from what each dimension's folder holds. */
    public static JsonObject manifest(Path root, List<Entry> entries) throws IOException {
        JsonArray list = new JsonArray();
        for (Entry e : entries) list.add(entry(root, e));
        JsonObject out = new JsonObject();
        out.addProperty("kind", "customdimensions-map");
        out.addProperty("rule", "every dimension, exported or not; layers names maps/<slug>/layers.json once its generated tiles exist, "
                + "explored is true once it has explored tiles; bytes the PNG bytes each layer wrote, 0 for none");
        out.add("dimensions", list);
        Files.createDirectories(root);
        Path file = root.resolve(MANIFEST);
        Path tmp = file.resolveSibling(MANIFEST + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out) + "\n");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return out;
    }

    static JsonObject entry(Path root, Entry e) throws IOException {
        Path dir = dimension(root, e.slug());
        String base = "/maps/" + e.slug() + "/";
        JsonObject o = new JsonObject();
        o.addProperty("slug", e.slug());
        o.addProperty("name", name(e.slug()));
        if (e.description() != null && !e.description().isBlank()) o.addProperty("description", e.description());
        if (e.spawn() != null) {
            JsonArray s = new JsonArray();
            for (int v : e.spawn()) s.add(v);
            o.add("spawn", s);
        }
        long rendered = read(dir.resolve("layers.json"), "generatedAt");
        long explored = read(dir.resolve(ExploredExport.JSON), "updatedAt");
        if (rendered > 0) {
            o.addProperty("renderedAt", rendered);
            o.addProperty("layers", base + "layers.json");
        }
        long exploredTiles = read(dir.resolve(ExploredExport.JSON), "tiles");
        o.addProperty("explored", explored > 0 && exploredTiles > 0 && Files.isDirectory(dir.resolve(ExploredExport.DIR)));
        if (explored > 0) o.addProperty("exploredAt", explored);
        o.addProperty("version", Math.max(rendered, explored));
        JsonObject bytes = new JsonObject();
        bytes.addProperty("generated", read(dir.resolve("layers.json"), "stats", "bytes"));
        bytes.addProperty("explored", read(dir.resolve(ExploredExport.JSON), "bytes"));
        o.add("bytes", bytes);
        Path thumb = dir.resolve("thumb.png");
        if (Files.isRegularFile(thumb)) {
            o.addProperty("thumb", base + "thumb.png");
            o.addProperty("thumbVersion", rendered);
            Integer bg = background(thumb);
            if (bg != null) o.addProperty("background", String.format("#%06x", bg));
        }
        return o;
    }

    /** The number at {@code path} in a JSON file; 0 when the file or the key is missing. */
    private static long read(Path json, String... path) {
        try {
            if (!Files.isRegularFile(json)) return 0;
            JsonElement v = JsonParser.parseString(Files.readString(json));
            for (String key : path) v = v != null && v.isJsonObject() ? v.getAsJsonObject().get(key) : null;
            return v == null || !v.isJsonPrimitive() ? 0 : v.getAsLong();
        } catch (IOException | RuntimeException ex) {
            return 0;
        }
    }

    /** The thumbnail's mean opaque colour, darkened to a third: the map's backdrop. */
    static Integer background(Path thumb) {
        try {
            BufferedImage img = ImageIO.read(thumb.toFile());
            if (img == null) return null;
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getRGB(x, y);
                    if (p >>> 24 < 128) continue;
                    r += p >> 16 & 0xFF;
                    g += p >> 8 & 0xFF;
                    b += p & 0xFF;
                    n++;
                }
            }
            if (n == 0) return null;
            return (int) (r / n / 3) << 16 | (int) (g / n / 3) << 8 | (int) (b / n / 3);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code the_aviary} reads "The Aviary". */
    public static String name(String slug) {
        StringBuilder out = new StringBuilder();
        for (String w : slug.split("_")) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }
}
