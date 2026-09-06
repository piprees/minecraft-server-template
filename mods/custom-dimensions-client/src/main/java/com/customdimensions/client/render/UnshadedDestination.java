package com.customdimensions.client.render;

/**
 * Which layer the destination is drawn on, and the light it carries there.
 *
 * <p>An entity layer is shaded from the SOURCE world's lightmap and shadow map
 * ({@code TROUBLESHOOTING.md#t99}, {@code #t104}). The unshaded targets are
 * lit here instead, from the destination's own levels.
 *
 * <p>No Minecraft types: {@code RenderLayer}'s static initialiser needs a
 * bootstrapped client, so a choice expressed in layers is a choice no test can
 * read.
 */
public final class UnshadedDestination {

    /** The three families {@code RenderLayers.getBlockLayer} answers with. */
    public enum Captured { SOLID, CUTOUT, TRANSLUCENT }

    /** The layer the captured quads are drawn on. */
    public enum Target {
        ENTITY_SOLID,
        ENTITY_CUTOUT_NO_CULL,
        ENTITY_TRANSLUCENT_CULL,
        UNSHADED_OPAQUE,
        UNSHADED_BLENDED,
        ENTITY_BACKDROP,
        UNSHADED_BACKDROP
    }

    private UnshadedDestination() {}

    /**
     * Cutout and translucent both need alpha, and there is one unshaded blended
     * target rather than two because the difference between them is the alpha
     * test, which the beacon-beam program does not have.
     */
    public static Target of(Captured captured, boolean unshaded) {
        if (unshaded) {
            return captured == Captured.SOLID ? Target.UNSHADED_OPAQUE : Target.UNSHADED_BLENDED;
        }
        return switch (captured) {
            case TRANSLUCENT -> Target.ENTITY_TRANSLUCENT_CULL;
            case CUTOUT -> Target.ENTITY_CUTOUT_NO_CULL;
            case SOLID -> Target.ENTITY_SOLID;
        };
    }

    /**
     * The layer behind the destination. Its colour is the destination's own fog
     * colour, already finished, so an unshaded target is the one that shows it:
     * no lightmap texel, no diffuse, nothing to apply a second time.
     */
    public static Target backdrop(boolean unshaded) {
        return unshaded ? Target.UNSHADED_BACKDROP : Target.ENTITY_BACKDROP;
    }

    /**
     * The colour the destination's own fog fades toward — its authored fog
     * colour, falling back to its sky colour exactly as the backdrop's own
     * does, attenuated by the same gain so the two converge. Null when the
     * destination declares neither, which leaves the source world's fog alone.
     */
    public static float[] fogColour(int fogColor, int skyColor, double gain) {
        int argb = backdropColour(fogColor, skyColor, gain);
        if (argb < 0) {
            return null;
        }
        return new float[] {
            ((argb >> 16) & 0xFF) / 255.0f,
            ((argb >> 8) & 0xFF) / 255.0f,
            (argb & 0xFF) / 255.0f,
        };
    }

    /**
     * The one definition of the destination's own colour: its fog, or its sky
     * when it declares no fog, attenuated by the gain. -1 when it declares
     * neither. The fog binding and the backdrop draw both read this, so a gain
     * cannot reach one and miss the other.
     */
    public static int backdropColour(int fogColor, int skyColor, double gain) {
        int argb = fogColor >= 0 ? fogColor : skyColor;
        if (argb < 0) {
            return -1;
        }
        double scale = Math.max(0.0, Math.min(1.0, gain));
        return (attenuate((argb >> 16) & 0xFF, scale) << 16)
                | (attenuate((argb >> 8) & 0xFF, scale) << 8)
                | attenuate(argb & 0xFF, scale);
    }

    private static int attenuate(int channel, double scale) {
        return (int) Math.round(channel * scale);
    }

    /**
     * The multiplier an unshaded target puts on a vertex colour, from the
     * destination's own block and sky levels. Vanilla's own brightness curve,
     * the brighter channel winning, exactly as the lightmap composes them.
     *
     * <p>The levels reaching here are the destination's own, so {@code ambient}
     * is the DESTINATION's. Routing them through source-lightmap space first
     * would lose them entirely to a saturated source, which is the one thing
     * this target does not have to care about.
     */
    public static float scale(int block, int sky, float ambient) {
        float floor = Math.max(0.0f, Math.min(1.0f, ambient));
        return Math.max(AmbientLift.brightness(clamp(block), floor),
                AmbientLift.brightness(clamp(sky), floor));
    }

    /**
     * The scale at three levels, shaped like {@link AmbientLift#label}. Three
     * equal values mean the destination is drawn without its own light.
     */
    public static String label(float destinationAmbient) {
        if (destinationAmbient < 0.0f) {
            return "unset";
        }
        return probe(0, destinationAmbient) + "," + probe(7, destinationAmbient)
                + "," + probe(15, destinationAmbient);
    }

    private static String probe(int level, float ambient) {
        return level + ">" + String.format("%.3f", scale(0, level, ambient));
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(15, level));
    }
}
