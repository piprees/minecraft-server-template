package com.customdimensions.client.render;

/**
 * A destination light level expressed as a SOURCE lightmap level.
 *
 * <p>The mesh carries the destination's own light levels and is drawn against
 * the source world's lightmap texture, and a dimension's {@code ambientLight}
 * lives in that texture rather than in the level. Each level is answered with
 * the source level that shades nearest to what vanilla's
 * {@code LightmapTextureManager.getBrightness} would give it in the
 * destination.
 */
public final class AmbientLift {

    /** No ambient light on the payload: the destination is lit as the source is. */
    public static final float UNSET = -1.0f;

    private AmbientLift() {}

    /**
     * The source level that shades as the destination would at {@code level}.
     *
     * <p>Identity when the two ambients agree, so an unchanged reading means
     * the value arrived and matched rather than never arriving. Always 0..15:
     * the source texture holds no texel outside its own range, so a destination
     * dimmer than the source's ambient floor reads at that floor.
     */
    public static int lift(int level, float destinationAmbient, float sourceAmbient) {
        int clamped = Math.max(0, Math.min(15, level));
        // A saturated source shades every level identically, so there is nothing
        // to aim at and the coordinate a shader pack reads stays truthful.
        if (destinationAmbient < 0.0f || sourceAmbient >= 1.0f) {
            return clamped;
        }
        float destination = Math.min(1.0f, destinationAmbient);
        if (destination == sourceAmbient) {
            return clamped;
        }
        return nearest(brightness(clamped, destination), sourceAmbient);
    }

    /** Vanilla's own: {@code lerp(level/15 / (4 - 3*level/15), ambient, 1)}. */
    static float brightness(int level, float ambient) {
        float scale = level / 15.0f;
        return ambient + (scale / (4.0f - 3.0f * scale)) * (1.0f - ambient);
    }

    /**
     * The level shading closest to {@code wanted}. Searched rather than solved:
     * the curve is steep near 15, so inverting it and rounding the level lands
     * on the wrong side of a tie, and only 16 levels exist to compare.
     */
    private static int nearest(float wanted, float ambient) {
        int best = 0;
        float bestGap = Float.MAX_VALUE;
        for (int level = 0; level <= 15; level++) {
            float gap = Math.abs(brightness(level, ambient) - wanted);
            if (gap < bestGap) {
                bestGap = gap;
                best = level;
            }
        }
        return best;
    }

    /**
     * The applied lift, for the emit line and {@code /state}. Reads as
     * {@code dst0.150/src0.000 0>6,7>9,15>15} — three probes, behind a
     * {@code dst} that says {@code unset} when no value arrived and prints the
     * number when one did.
     */
    public static String label(float destinationAmbient, float sourceAmbient) {
        String destination = destinationAmbient < 0.0f
                ? "unset" : String.format("%.3f", destinationAmbient);
        return "dst" + destination + "/src" + String.format("%.3f", sourceAmbient)
                + " " + probe(0, destinationAmbient, sourceAmbient)
                + "," + probe(7, destinationAmbient, sourceAmbient)
                + "," + probe(15, destinationAmbient, sourceAmbient);
    }

    private static String probe(int level, float destinationAmbient, float sourceAmbient) {
        return level + ">" + lift(level, destinationAmbient, sourceAmbient);
    }
}
