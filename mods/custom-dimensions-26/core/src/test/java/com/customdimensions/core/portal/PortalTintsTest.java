package com.customdimensions.core.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The tint table and the server pack built from it. */
class PortalTintsTest {

    @Test
    void everyTintAndPlaneOwnsItsOwnState() {
        Set<String> seen = new HashSet<>();
        for (int tint = 0; tint < PortalTints.COUNT; tint++) {
            for (PortalTints.Plane plane : PortalTints.Plane.values()) {
                assertTrue(seen.add(PortalTints.state(plane, tint).variant()), plane + " " + tint);
            }
        }
        assertEquals(48, seen.size());
    }

    @Test
    void theFirstEightOfEachTypeStayFree() {
        for (int index = 0; index < 8; index++) {
            for (boolean attached : new boolean[]{false, true}) {
                String free = PortalTints.Tripwire.at(attached, index).variant();
                for (int tint = 0; tint < PortalTints.COUNT; tint++) {
                    for (PortalTints.Plane plane : PortalTints.Plane.values()) {
                        assertNotEquals(free, PortalTints.state(plane, tint).variant());
                    }
                }
            }
        }
    }

    @Test
    void indexZeroIsEveryFlagTrueAsPolymerOrdersThem() {
        assertEquals(new PortalTints.Tripwire(false, true, true, true, true, true), PortalTints.Tripwire.at(false, 0));
        assertEquals(new PortalTints.Tripwire(true, false, false, false, false, false), PortalTints.Tripwire.at(true, 31));
        assertEquals("attached=true,disarmed=true,east=false,north=false,powered=false,south=false,west=false",
                PortalTints.Tripwire.at(true, 31).variant());
    }

    @Test
    void aDyeColourIsItsOwnNearest() {
        for (int tint = 0; tint < PortalTints.COUNT; tint++) {
            assertEquals(tint, PortalTints.nearest(PortalTints.rgb(tint)), PortalTints.DYES[tint]);
        }
    }

    @Test
    void definitionColoursSnapToTheDyeTheyLookLike() {
        assertEquals("light_blue", PortalTints.DYES[PortalTints.nearest(0x87CEEB)]);
        assertEquals("red", PortalTints.DYES[PortalTints.nearest(0xAF2B2B)]);
        assertEquals("orange", PortalTints.DYES[PortalTints.nearest(0xFF8B2B)]);
        assertEquals("white", PortalTints.DYES[PortalTints.nearest(0xE8E8E8)]);
        assertEquals("black", PortalTints.DYES[PortalTints.nearest(0x000000)]);
        assertEquals("purple", PortalTints.DYES[PortalTints.DEFAULT]);
    }

    @Test
    void aTintOutsideTheTableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PortalTints.state(PortalTints.Plane.X, 16));
        assertThrows(IllegalArgumentException.class, () -> PortalTints.model(PortalTints.Plane.Y, -1));
    }

    @Test
    void thePackHoldsEveryTextureModelAndTheTripwireFile() throws IOException {
        Map<String, byte[]> entries = TintPackWriter.entries(texture(), meta(), vanillaTripwire());
        assertTrue(entries.containsKey("pack.mcmeta"));
        assertEquals(1 + 16 * 2 + 48 + 1, entries.size());
        for (int tint = 0; tint < PortalTints.COUNT; tint++) {
            String png = "assets/customdimensions/textures/block/portal/" + PortalTints.DYES[tint] + ".png";
            assertTrue(entries.containsKey(png), png);
            assertArrayEquals(meta(), entries.get(png + ".mcmeta"));
            for (PortalTints.Plane plane : PortalTints.Plane.values()) {
                String model = "assets/customdimensions/models/block/portal/" + PortalTints.DYES[tint] + "_" + plane.id()
                        + ".json";
                JsonObject json = JsonParser.parseString(new String(entries.get(model), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                assertEquals(PortalTints.texture(tint), json.getAsJsonObject("textures").get("portal").getAsString());
                assertEquals(PortalTints.LIGHT, json.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .get("light_emission").getAsInt());
            }
        }
        JsonObject meta = JsonParser.parseString(new String(entries.get("pack.mcmeta"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("pack");
        assertEquals(TintPackWriter.FORMAT, meta.get("min_format").getAsInt());
    }

    @Test
    void theTripwireFileNamesAll128StatesAndKeepsVanillaWhereWeOwnNone() throws IOException {
        Map<String, byte[]> entries = TintPackWriter.entries(texture(), meta(), vanillaTripwire());
        JsonObject variants = JsonParser.parseString(new String(entries.get(TintPackWriter.TRIPWIRE),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        assertEquals(128, variants.size());
        int ours = 0;
        for (Map.Entry<String, com.google.gson.JsonElement> e : variants.entrySet()) {
            String model = e.getValue().getAsJsonObject().get("model").getAsString();
            if (model.startsWith("customdimensions:")) {
                ours++;
                assertTrue(e.getKey().contains("disarmed=true"), e.getKey());
            } else {
                assertTrue(model.startsWith("minecraft:block/tripwire"), e.getKey() + " -> " + model);
            }
        }
        assertEquals(48, ours);
        assertEquals(PortalTints.model(PortalTints.Plane.Z, 3),
                variants.getAsJsonObject(PortalTints.state(PortalTints.Plane.Z, 3).variant()).get("model").getAsString());
        assertEquals("minecraft:block/tripwire_attached_n",
                variants.getAsJsonObject("attached=true,disarmed=false,east=false,north=true,powered=true,south=false,west=false")
                        .get("model").getAsString());
    }

    @Test
    void aRecolouredPaneTakesTheDyesHueAndKeepsAlpha() throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(texture()));
        BufferedImage red = TintPackWriter.recolour(source, 0xB02E26);
        double r = 0;
        double g = 0;
        for (int y = 0; y < red.getHeight(); y++) {
            for (int x = 0; x < red.getWidth(); x++) {
                int argb = red.getRGB(x, y);
                assertEquals(source.getRGB(x, y) >>> 24, argb >>> 24);
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
            }
        }
        assertTrue(r > 2 * g, "red dominates: r=" + r + " g=" + g);
    }

    @Test
    void theZipIsByteForByteRepeatable() throws IOException {
        assertEquals(sha1(zip()), sha1(zip()));
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip()))) {
            String last = "";
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                assertTrue(e.getName().compareTo(last) > 0, e.getName() + " after " + last);
                last = e.getName();
            }
        }
    }

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TintPackWriter.write(texture(), meta(), vanillaTripwire(), out);
        return out.toByteArray();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A two-frame violet stand-in for the vanilla texture, translucent like it. */
    private static byte[] texture() throws IOException {
        BufferedImage image = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                int v = 60 + (x * 7 + y * 3) % 120;
                image.setRGB(x, y, (0xBF << 24) | ((v / 2) << 16) | ((v / 6) << 8) | v);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] meta() {
        return "{\n  \"animation\": {}\n}".getBytes(StandardCharsets.UTF_8);
    }

    /** Vanilla's tripwire.json shape: 32 variants by attached and the four sides. */
    private static JsonObject vanillaTripwire() {
        JsonObject variants = new JsonObject();
        for (int bits = 0; bits < 32; bits++) {
            boolean attached = (bits & 16) != 0;
            boolean east = (bits & 8) != 0;
            boolean north = (bits & 4) != 0;
            boolean south = (bits & 2) != 0;
            boolean west = (bits & 1) != 0;
            StringBuilder sides = new StringBuilder();
            if (north) sides.append('n');
            if (east) sides.append('e');
            if (south) sides.append('s');
            if (west) sides.append('w');
            JsonObject model = new JsonObject();
            model.addProperty("model", "minecraft:block/tripwire" + (attached ? "_attached" : "") + "_"
                    + (sides.isEmpty() ? "ns" : sides));
            variants.add("attached=" + attached + ",east=" + east + ",north=" + north + ",south=" + south + ",west=" + west,
                    model);
        }
        JsonObject root = new JsonObject();
        root.add("variants", variants);
        return root;
    }
}
