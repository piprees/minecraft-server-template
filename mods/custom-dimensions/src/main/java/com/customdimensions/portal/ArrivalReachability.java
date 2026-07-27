package com.customdimensions.portal;

/**
 * Can a portal built anywhere in the source world actually be arrived at?
 *
 * <h2>Why this exists</h2>
 * Arrival placement is {@code target = source * scale}. Nothing ever asked
 * whether the result lands somewhere a player can exist — and vanilla
 * forbids breaking AND placing blocks outside the world border, so a player
 * who arrives out of bounds cannot touch the portal frame, the portal, or
 * any block around them. Every diagnosis of that symptom points at
 * protection code; the cause is arithmetic in a config file.
 *
 * <p>Found live 2026-07-25 in {@code adventure:the_ember_fields}: the entry
 * transform MULTIPLIED by scale instead of dividing, so an overworld portal
 * at (236, −453) with {@code scale: 8.0} arrived at (1888, −3624) against a
 * player border of radius 1024. That root cause is fixed; this check remains
 * as the guard, because a badly authored scale/border pair can still put an
 * arrival out of bounds and the symptom ("I cannot break anything") points
 * nowhere near the cause.
 *
 * <p>Pure arithmetic over plain values: no world, no config objects, no
 * Minecraft runtime.
 */
public final class ArrivalReachability {

    /** A border radius meaning "no limit". */
    public static final int UNBOUNDED = -1;

    private ArrivalReachability() {
    }

    /**
     * The furthest a portal can be built from the source world's origin and
     * still arrive inside the destination's player border.
     *
     * <p>{@code margin} is subtracted from the destination radius so the
     * arrival's frame ring and egress pocket are inside too, not just its
     * centre cell — an arrival whose centre is one block inside the border
     * still cannot be built or stepped out of.
     *
     * @return the usable source radius, or {@link Integer#MAX_VALUE} when the
     *         destination is unbounded
     */
    public static int usableSourceRadius(double scale, int destBorderRadius, int margin) {
        if (destBorderRadius == UNBOUNDED) {
            return Integer.MAX_VALUE;
        }
        int usableDest = Math.max(0, destBorderRadius - margin);
        if (scale <= 0) {
            // A non-positive scale collapses every portal onto the origin.
            // Treat it as unbounded rather than dividing by zero; the config
            // parser is what should reject it.
            return Integer.MAX_VALUE;
        }
        // Entering DIVIDES by scale, so a source portal at radius R arrives
        // at R / scale. It fits when R / scale <= usableDest, i.e.
        // R <= usableDest * scale.
        return (int) Math.floor(usableDest * scale);
    }

    /**
     * Is every portal buildable in the source world reachable in the
     * destination?
     *
     * <p>Anchor dimensions are exempt and must not be passed here: their
     * arrival is a fixed configured position, not a scaled one, so the
     * source radius is irrelevant.
     *
     * @param sourceBorderRadius how far out a player may build in the source
     * @param destBorderRadius   the destination's PLAYER border radius —
     *                           {@code generation} is a different number and
     *                           is not what governs block interaction
     */
    public static boolean allArrivalsReachable(double scale, int sourceBorderRadius,
            int destBorderRadius, int margin) {
        if (destBorderRadius == UNBOUNDED) {
            return true;
        }
        if (sourceBorderRadius == UNBOUNDED) {
            // Unlimited source, limited destination: some portal is always
            // out of bounds.
            return false;
        }
        return usableSourceRadius(scale, destBorderRadius, margin) >= sourceBorderRadius;
    }

    /**
     * The destination border radius that would make every source portal
     * reachable — the number to put in config, or to compare a proposed one
     * against.
     */
    public static int requiredDestBorderRadius(double scale, int sourceBorderRadius, int margin) {
        if (sourceBorderRadius == UNBOUNDED) {
            return UNBOUNDED;
        }
        return (int) Math.ceil(sourceBorderRadius / scale) + margin;
    }
}
