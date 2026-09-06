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
        UNSHADED_BLENDED
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
     * The multiplier an unshaded target puts on a vertex colour, from the
     * destination's own block and sky levels. Vanilla's own brightness curve,
     * the brighter channel winning, exactly as the lightmap composes them.
     *
     * <p>The levels reaching here are already in source-lightmap space
     * ({@link AmbientLift}), so {@code ambient} is the SOURCE ambient the mesh
     * was lifted against and the pair reproduces the texel the lightmap holds.
     */
    public static float scale(int block, int sky, float ambient) {
        float floor = Math.max(0.0f, Math.min(1.0f, ambient));
        return Math.max(AmbientLift.brightness(clamp(block), floor),
                AmbientLift.brightness(clamp(sky), floor));
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(15, level));
    }
}
