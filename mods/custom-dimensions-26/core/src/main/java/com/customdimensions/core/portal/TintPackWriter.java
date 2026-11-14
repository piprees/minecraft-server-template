package com.customdimensions.core.portal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the server resource pack that tints portals for unmodded clients:
 * the vanilla Nether portal texture recoloured per dye, a pane model per dye
 * and plane, and a tripwire blockstate file that shows those panes on the
 * states {@link PortalTints} owns and vanilla's tripwire on every other.
 * The zip's entries are sorted and timestamped alike, so its bytes depend on
 * the inputs only.
 * <p>
 * Usage: {@code TintPackWriter <minecraft client jar> <out zip>}.
 */
public final class TintPackWriter {
    static final String PORTAL_TEXTURE = "assets/minecraft/textures/block/nether_portal.png";
    static final String PORTAL_META = PORTAL_TEXTURE + ".mcmeta";
    static final String TRIPWIRE = "assets/minecraft/blockstates/tripwire.json";
    static final String DESCRIPTION = "Custom Dimensions portal colours";
    /** Resource pack format of the client this pack is built for (its version.json). */
    static final int FORMAT = 97;
    private static final LocalDateTime STAMP = LocalDateTime.of(1980, 2, 1, 0, 0);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private TintPackWriter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: TintPackWriter <minecraft client jar> <out zip>");
        }
        Path out = Path.of(args[1]);
        try (ZipFile client = new ZipFile(args[0])) {
            byte[] texture = read(client, PORTAL_TEXTURE);
            byte[] meta = read(client, PORTAL_META);
            JsonObject tripwire = JsonParser.parseString(new String(read(client, TRIPWIRE), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (OutputStream os = Files.newOutputStream(out)) {
                write(texture, meta, tripwire, os);
            }
        }
    }

    /** The pack's entries, by path. */
    public static Map<String, byte[]> entries(byte[] portalTexture, byte[] portalMeta, JsonObject vanillaTripwire)
            throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", json(packMeta()));
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(portalTexture));
        if (source == null) {
            throw new IOException("the portal texture is not an image");
        }
        for (int tint = 0; tint < PortalTints.COUNT; tint++) {
            String texture = "assets/" + PortalTints.NAMESPACE + "/textures/" + path(PortalTints.texture(tint)) + ".png";
            files.put(texture, png(recolour(source, PortalTints.rgb(tint))));
            files.put(texture + ".mcmeta", portalMeta);
            for (PortalTints.Plane plane : PortalTints.Plane.values()) {
                files.put("assets/" + PortalTints.NAMESPACE + "/models/" + path(PortalTints.model(plane, tint)) + ".json",
                        json(model(plane, PortalTints.texture(tint))));
            }
        }
        files.put(TRIPWIRE, json(tripwire(vanillaTripwire)));
        return files;
    }

    public static void write(byte[] portalTexture, byte[] portalMeta, JsonObject vanillaTripwire, OutputStream out)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries(portalTexture, portalMeta, vanillaTripwire).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTimeLocal(STAMP);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }

    static JsonObject packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", DESCRIPTION);
        pack.addProperty("min_format", FORMAT);
        pack.addProperty("max_format", FORMAT);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /** Every tripwire state, explicit: our pane where the table owns the state, vanilla's model elsewhere. */
    static JsonObject tripwire(JsonObject vanilla) {
        JsonObject variants = vanilla.getAsJsonObject("variants");
        if (variants == null) {
            throw new IllegalArgumentException("vanilla tripwire.json has no variants");
        }
        Map<String, JsonElement> ours = new TreeMap<>();
        for (int tint = 0; tint < PortalTints.COUNT; tint++) {
            for (PortalTints.Plane plane : PortalTints.Plane.values()) {
                JsonObject model = new JsonObject();
                model.addProperty("model", PortalTints.model(plane, tint));
                if (ours.put(PortalTints.state(plane, tint).variant(), model) != null) {
                    throw new IllegalStateException("two tints share " + PortalTints.state(plane, tint).variant());
                }
            }
        }
        JsonObject out = new JsonObject();
        for (int bits = 0; bits < 128; bits++) {
            boolean attached = (bits & 64) != 0;
            boolean disarmed = (bits & 32) != 0;
            boolean east = (bits & 16) != 0;
            boolean north = (bits & 8) != 0;
            boolean powered = (bits & 4) != 0;
            boolean south = (bits & 2) != 0;
            boolean west = (bits & 1) != 0;
            String key = "attached=" + attached + ",disarmed=" + disarmed + ",east=" + east + ",north=" + north
                    + ",powered=" + powered + ",south=" + south + ",west=" + west;
            JsonElement model = ours.get(key);
            if (model == null) {
                String vanillaKey = "attached=" + attached + ",east=" + east + ",north=" + north + ",south=" + south
                        + ",west=" + west;
                model = variants.get(vanillaKey);
                if (model == null) {
                    throw new IllegalArgumentException("vanilla tripwire.json has no variant " + vanillaKey);
                }
            }
            out.add(key, model.deepCopy());
        }
        JsonObject root = new JsonObject();
        root.add("variants", out);
        return root;
    }

    /** A pane on {@code plane}: the vanilla portal's own slab for upright ones, a flat one at the block's top for Y. */
    static JsonObject model(PortalTints.Plane plane, String texture) {
        JsonObject textures = new JsonObject();
        textures.addProperty("particle", texture);
        textures.addProperty("portal", texture);
        JsonObject element = new JsonObject();
        JsonObject faces = new JsonObject();
        switch (plane) {
            case X -> {
                element.add("from", vec(0, 0, 6));
                element.add("to", vec(16, 16, 10));
                faces.add("north", face());
                faces.add("south", face());
            }
            case Z -> {
                element.add("from", vec(6, 0, 0));
                element.add("to", vec(10, 16, 16));
                faces.add("east", face());
                faces.add("west", face());
            }
            case Y -> {
                element.add("from", vec(0, 11, 0));
                element.add("to", vec(16, 13, 16));
                faces.add("up", face());
                faces.add("down", face());
            }
        }
        element.addProperty("light_emission", PortalTints.LIGHT);
        element.addProperty("shade", false);
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);
        JsonObject root = new JsonObject();
        root.add("textures", textures);
        root.add("elements", elements);
        return root;
    }

    /** The source's detail at {@code rgb}'s hue: each pixel's luminance over the mean, times the colour; alpha kept. */
    static BufferedImage recolour(BufferedImage source, int rgb) {
        int w = source.getWidth();
        int h = source.getHeight();
        double sum = 0;
        int counted = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                if ((argb >>> 24) != 0) {
                    sum += luminance(argb);
                    counted++;
                }
            }
        }
        double mean = counted == 0 || sum == 0 ? 1 : sum / counted;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                double k = luminance(argb) / mean;
                int r = clamp(((rgb >> 16) & 0xFF) * k);
                int g = clamp(((rgb >> 8) & 0xFF) * k);
                int b = clamp((rgb & 0xFF) * k);
                out.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static double luminance(int argb) {
        return 0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static JsonObject face() {
        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        for (int v : new int[]{0, 0, 16, 16}) {
            uv.add(v);
        }
        face.add("uv", uv);
        face.addProperty("texture", "#portal");
        return face;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    /** {@code ns:block/x} -> {@code block/x}. */
    private static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    private static byte[] json(JsonObject o) {
        return (GSON.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            throw new IOException(zip.getName() + " has no " + name);
        }
        try (var in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }
}
