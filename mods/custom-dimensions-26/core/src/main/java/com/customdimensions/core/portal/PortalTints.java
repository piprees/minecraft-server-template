package com.customdimensions.core.portal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The colours a portal can show an unmodded client that loaded the server
 * pack: the sixteen dye colours, each on three planes, each pinned to one
 * disarmed tripwire state the pack remaps to a tinted pane. A tint index is
 * stored in the portal block's state, so the table never reorders.
 * <p>
 * The states come from the end of polymer-blocks' allocation order (it
 * hands out from the start), 24 from each tripwire type, so the first 8 of
 * each stay free for other Polymer mods.
 */
public final class PortalTints {
    /** Name and texture colour of each dye, in {@code DyeColor} order. */
    public static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    static final int[] RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};
    public static final int COUNT = DYES.length;
    /** The tint a portal block carries before it is told one: purple, the vanilla portal's own hue. */
    public static final int DEFAULT = 10;
    public static final String NAMESPACE = "customdimensions";
    /** Where the built pack sits inside the server jar. */
    public static final String PACK_RESOURCE = "customdimensions/server-pack.zip";
    /** The block light the tinted pane's faces glow at, the portal block's own. */
    public static final int LIGHT = 11;

    /** The three ways an opening stands: upright across X or Z, or flat. */
    public enum Plane {
        X, Z, Y;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One disarmed tripwire state, by the properties vanilla's tripwire has. */
    public record Tripwire(boolean attached, boolean north, boolean south, boolean east, boolean west, boolean powered) {
        /** Polymer-blocks' order: the index within the type's 32 disarmed states. */
        static Tripwire at(boolean attached, int index) {
            return new Tripwire(attached, bit(index, 4), bit(index, 3), bit(index, 2), bit(index, 1), bit(index, 0));
        }

        private static boolean bit(int index, int shift) {
            return ((index >> shift) & 1) == 0;
        }

        /** The variant key a blockstate file uses: every property, in name order. */
        public String variant() {
            return "attached=" + attached + ",disarmed=true,east=" + east + ",north=" + north + ",powered=" + powered
                    + ",south=" + south + ",west=" + west;
        }

        /** The same state's properties, by name. */
        public Map<String, Boolean> properties() {
            Map<String, Boolean> out = new LinkedHashMap<>();
            out.put("attached", attached);
            out.put("disarmed", true);
            out.put("east", east);
            out.put("north", north);
            out.put("powered", powered);
            out.put("south", south);
            out.put("west", west);
            return out;
        }
    }

    private PortalTints() {
    }

    /** The tripwire state that shows {@code tint} on {@code plane}. */
    public static Tripwire state(Plane plane, int tint) {
        check(tint);
        return switch (plane) {
            case X -> Tripwire.at(false, 16 + tint);
            case Y -> Tripwire.at(true, 16 + tint);
            case Z -> tint < 8 ? Tripwire.at(false, 8 + tint) : Tripwire.at(true, tint);
        };
    }

    /** The model a tinted pane uses, as a model id. */
    public static String model(Plane plane, int tint) {
        check(tint);
        return NAMESPACE + ":block/portal/" + DYES[tint] + "_" + plane.id();
    }

    /** The tinted texture of one dye, as a texture id. */
    public static String texture(int tint) {
        check(tint);
        return NAMESPACE + ":block/portal/" + DYES[tint];
    }

    public static int rgb(int tint) {
        check(tint);
        return RGB[tint];
    }

    /** The dye nearest {@code rgb} as the eye sees it (CIELAB distance). */
    public static int nearest(int rgb) {
        double[] lab = lab(rgb);
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < COUNT; i++) {
            double[] other = lab(RGB[i]);
            double d = sq(lab[0] - other[0]) + sq(lab[1] - other[1]) + sq(lab[2] - other[2]);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    static double[] lab(int rgb) {
        double r = linear((rgb >> 16) & 0xFF);
        double g = linear((rgb >> 8) & 0xFF);
        double b = linear(rgb & 0xFF);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void check(int tint) {
        if (tint < 0 || tint >= COUNT) {
            throw new IllegalArgumentException("tint " + tint + " is not a dye index");
        }
    }
}
