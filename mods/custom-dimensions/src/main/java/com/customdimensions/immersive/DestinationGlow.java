package com.customdimensions.immersive;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The light and colour of the world on the far side of a portal, sampled at
 * the same arrival column the block projection and {@code tickAudio} already
 * resolved.
 *
 * <p>This is the visual counterpart to the audio leak: the ambience a player
 * hears through an immersive portal comes from the destination's biome, and
 * so does the colour drifting out of the opening and how bright it is. A
 * portal onto a lit surface pours pale daylight; one onto a dark cave barely
 * shows.
 *
 * <h2>Never loads a chunk</h2>
 * {@link #sample} reads light and biome only, and is called exclusively from
 * paths that already hold the projector's chunk ticket and have resolved a
 * non-{@code NO_ARRIVAL} column — so the chunk is resident. Both reads go
 * through the same accessors {@code tickAudio} uses.
 */
public record DestinationGlow(int light, int tint) {

    /** Nothing sampled yet, or no destination resolved. */
    public static final DestinationGlow NONE = new DestinationGlow(-1, -1);

    /** Dimmest a destination's colour is drawn at, so a dark world still shows an edge. */
    public static final float MIN_BRIGHTNESS = 0.35f;

    /** How far a portal's own colour moves towards the destination's by default. */
    public static final double DEFAULT_TINT = 0.6;

    public boolean isPresent() {
        return this.light >= 0 && this.tint >= 0;
    }

    /**
     * The destination's light level and biome fog colour at the arrival
     * column. Light is read one block above the arrival floor row, which is
     * the air a player stands in rather than the ground they stand on.
     */
    public static DestinationGlow sample(ServerWorld targetWorld, BlockPos arrivalPos) {
        if (targetWorld == null || arrivalPos == null) {
            return NONE;
        }
        int light = Math.max(0, Math.min(15, targetWorld.getLightLevel(arrivalPos.up())));
        int fog = targetWorld.getBiome(arrivalPos).value().getFogColor() & 0xFFFFFF;
        return new DestinationGlow(light, fog);
    }

    /**
     * The portal's own colour moved {@code amount} of the way towards the
     * destination's, per channel. At 0 the configured colour is untouched, at
     * 1 the destination's replaces it.
     */
    public static int blend(int base, int destination, double amount) {
        double t = clamp01(amount);
        if (destination < 0 || t <= 0.0) {
            return base & 0xFFFFFF;
        }
        int r = mix((base >> 16) & 0xFF, (destination >> 16) & 0xFF, t);
        int g = mix((base >> 8) & 0xFF, (destination >> 8) & 0xFF, t);
        int b = mix(base & 0xFF, destination & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * A 0..15 light level as a colour multiplier. Floored at {@link
     * #MIN_BRIGHTNESS} — a pitch-dark destination must still draw an edge on
     * the frame, or the portal disappears rather than reading as dark.
     */
    public static float brightness(int light) {
        if (light < 0) {
            return 1.0f;
        }
        int clamped = Math.max(0, Math.min(15, light));
        return MIN_BRIGHTNESS + (1.0f - MIN_BRIGHTNESS) * (clamped / 15.0f);
    }

    /** A packed colour scaled by a brightness multiplier, per channel. */
    public static int shade(int colour, float brightness) {
        float scale = Math.max(0.0f, Math.min(1.0f, brightness));
        int r = Math.round(((colour >> 16) & 0xFF) * scale);
        int g = Math.round(((colour >> 8) & 0xFF) * scale);
        int b = Math.round((colour & 0xFF) * scale);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * This glow applied to a portal's configured colour: blended towards the
     * destination's, then scaled by how much light there is over there.
     * An absent glow leaves the configured colour exactly as it is.
     */
    public int applyTo(int portalColour, double tintAmount, boolean useLight) {
        if (!isPresent()) {
            return portalColour & 0xFFFFFF;
        }
        int blended = blend(portalColour, this.tint, tintAmount);
        return useLight ? shade(blended, brightness(this.light)) : blended;
    }

    private static int mix(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
