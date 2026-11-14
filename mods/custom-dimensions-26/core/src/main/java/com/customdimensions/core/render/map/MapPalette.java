package com.customdimensions.core.render.map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * The web map's block colours: each vanilla block's mean top-face texture
 * colour and opacity and how the client tints it, from
 * {@value #RESOURCE} (written by {@code scripts/map-palette.py} from the
 * 26.3 client jar), and the grass, foliage and dry-foliage colormaps as
 * 33 × 33 samples read bilinearly. A block the file does not name has no
 * entry; the caller supplies its own colour (the block's map colour).
 */
public final class MapPalette {
    public static final String RESOURCE = "/customdimensions/map-palette.json";

    public enum Tint { NONE, GRASS, FOLIAGE, DRY_FOLIAGE, WATER, FIXED }

    /** A block's colour before tinting, its opacity 0 to 255, and its tint ({@code fixed} the colour for {@link Tint#FIXED}). */
    public record Entry(int rgb, int alpha, Tint tint, int fixed) {
        public static Entry opaque(int rgb) {
            return new Entry(rgb & 0xFFFFFF, 255, Tint.NONE, 0);
        }
    }

    private static volatile MapPalette vanilla;

    private final Map<String, Entry> blocks;
    private final Map<String, int[]> colormaps;
    private final int step;
    private final String digest;

    private MapPalette(Map<String, Entry> blocks, Map<String, int[]> colormaps, int step, String digest) {
        this.blocks = blocks;
        this.colormaps = colormaps;
        this.step = step;
        this.digest = digest;
    }

    public static MapPalette vanilla() {
        MapPalette p = vanilla;
        if (p == null) {
            synchronized (MapPalette.class) {
                if (vanilla == null) vanilla = load();
                p = vanilla;
            }
        }
        return p;
    }

    private static MapPalette load() {
        try (InputStream in = MapPalette.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            byte[] bytes = in.readAllBytes();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonObject root = JsonParser.parseReader(new InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Tint> tints = new HashMap<>();
            Map<String, Integer> fixed = new HashMap<>();
            for (var e : root.getAsJsonObject("tints").entrySet()) {
                String v = e.getValue().getAsString();
                if (v.startsWith("#")) {
                    tints.put(e.getKey(), Tint.FIXED);
                    fixed.put(e.getKey(), Integer.parseInt(v.substring(1), 16));
                } else {
                    tints.put(e.getKey(), Tint.valueOf(v.toUpperCase(java.util.Locale.ROOT)));
                }
            }
            Map<String, Entry> blocks = new HashMap<>();
            for (var e : root.getAsJsonObject("blocks").entrySet()) {
                long v = Long.parseLong(e.getValue().getAsString(), 16);
                String id = e.getKey();
                blocks.put(id, new Entry((int) (v >>> 8), (int) (v & 0xFF), tints.getOrDefault(id, Tint.NONE), fixed.getOrDefault(id, 0)));
            }
            Map<String, int[]> maps = new HashMap<>();
            for (var e : root.getAsJsonObject("colormaps").entrySet()) {
                if (e.getValue().isJsonNull()) continue;
                String[] parts = e.getValue().getAsString().split(" ");
                int[] samples = new int[parts.length];
                for (int i = 0; i < parts.length; i++) samples[i] = Integer.parseInt(parts[i], 16);
                maps.put(e.getKey(), samples);
            }
            return new MapPalette(blocks, maps, root.get("colormapStep").getAsInt(), digest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The block's entry, or null when the palette does not name it. */
    public Entry entry(String block) {
        return blocks.get(block);
    }

    public int size() {
        return blocks.size();
    }

    /** SHA-256 of the palette file, for cache fingerprints. */
    public String digest() {
        return digest;
    }

    /**
     * The colormap {@code name} ({@code grass}, {@code foliage},
     * {@code dry_foliage}) at a biome's temperature and downfall, as the
     * client indexes it: both clamped to 0 to 1, downfall times temperature.
     */
    public int colormap(String name, double temperature, double downfall) {
        int[] samples = colormaps.get(name);
        if (samples == null) throw new IllegalArgumentException("no colormap " + name);
        double t = Math.clamp(temperature, 0, 1);
        double d = Math.clamp(downfall, 0, 1) * t;
        double x = (1 - t) * 255 / step;
        double y = (1 - d) * 255 / step;
        int n = (int) Math.round(Math.sqrt(samples.length));
        int x0 = Math.min((int) x, n - 2);
        int y0 = Math.min((int) y, n - 2);
        double fx = x - x0;
        double fy = y - y0;
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double a = (samples[y0 * n + x0] >> shift & 0xFF) * (1 - fx) + (samples[y0 * n + x0 + 1] >> shift & 0xFF) * fx;
            double b = (samples[(y0 + 1) * n + x0] >> shift & 0xFF) * (1 - fx) + (samples[(y0 + 1) * n + x0 + 1] >> shift & 0xFF) * fx;
            out |= (int) Math.round(a * (1 - fy) + b * fy) << shift;
        }
        return out;
    }

    /** The entry's colour under {@code tint}: its texture colour multiplied by the tint its kind takes. */
    public static int colour(Entry e, BiomeTint tint) {
        int by = switch (e.tint()) {
            case NONE -> 0xFFFFFF;
            case GRASS -> tint.grass();
            case FOLIAGE -> tint.foliage();
            case DRY_FOLIAGE -> tint.dryFoliage();
            case WATER -> tint.water();
            case FIXED -> e.fixed();
        };
        return multiply(e.rgb(), by);
    }

    public static int multiply(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return r << 16 | g << 8 | bl;
    }
}
