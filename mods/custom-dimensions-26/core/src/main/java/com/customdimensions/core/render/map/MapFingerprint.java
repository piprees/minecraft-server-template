package com.customdimensions.core.render.map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What a dimension's generated tiles depend on, as one SHA-256: the export
 * format, the palette, the seed, the radius and the compiled generator's own
 * encoding. Equal fingerprints mean the tiles on disk are still right.
 */
public final class MapFingerprint {
    /** Bumped when the tiles' form or drawing changes. */
    public static final int FORMAT = 1;

    private MapFingerprint() {}

    public static String of(long seed, int radius, String palette, String... generator) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, "format " + FORMAT);
            update(md, "palette " + palette);
            update(md, "seed " + seed);
            update(md, "radius " + radius);
            for (String part : generator) update(md, part == null ? "" : part);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest md, String part) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        md.update((byte) (bytes.length >>> 24));
        md.update((byte) (bytes.length >>> 16));
        md.update((byte) (bytes.length >>> 8));
        md.update((byte) bytes.length);
        md.update(bytes);
    }
}
