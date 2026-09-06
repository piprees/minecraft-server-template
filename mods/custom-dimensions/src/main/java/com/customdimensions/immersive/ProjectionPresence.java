package com.customdimensions.immersive;

/**
 * Whether one player's projection is drawn, held, or handed back.
 *
 * <p>The activation band is two blocks wide and ordinary loitering by a portal
 * crosses it repeatedly. Clearing on the way out and rebuilding on the way back
 * blanks the opening for the whole chain — packet, client tick, box walk, mesh
 * build — every crossing. So a player who leaves keeps their projection through
 * a grace window and only gets their real blocks back once they are plainly
 * gone.
 *
 * <p>Pure arithmetic: no world, no player, no state. Nothing held during grace
 * touches a chunk, so the window can never keep a destination resident.
 */
public final class ProjectionPresence {

    /** What this pass owes one player's projection. */
    public enum Presence {
        /** In range: build or refresh it. */
        PROJECT,
        /** Out of range, inside the grace window: leave the client's copy alone. */
        HOLD,
        /** Give the real blocks back. */
        CLEAR
    }

    /**
     * How long a projection outlives the player leaving the band.
     *
     * <p>Five seconds. A walking player crosses the two-block band in about
     * nine ticks and a sprinting one in seven, so no amount of ordinary
     * loitering spends this; someone who has genuinely walked off is twenty
     * blocks clear long before it expires, and is cut by {@code graceRadius}
     * first. Deliberately the same figure as {@code
     * ImmersiveProjector.TICKET_EXPIRY_TICKS} — one notion of a stale hold,
     * not two.
     */
    public static final int GRACE_TICKS = 100;

    /** This projection is not in the grace window. */
    public static final long NOT_LEFT = -1L;

    private ProjectionPresence() {
    }

    /**
     * What to do with one player's projection this tick.
     *
     * <p>{@code graceRadius} bounds the window by distance as well as time: a
     * projection is only ever held inside the band its chunk ticket already
     * covers, so grace can never outlive the data that would refresh it, and
     * someone sprinting away is cleared long before the timer.
     *
     * @param projecting whether this player already has a projection
     * @param leftAt     the tick they left the band, or {@link #NOT_LEFT}
     */
    public static Presence of(double distanceSq, int activateRadius, int deactivateRadius,
            int graceRadius, boolean projecting, long tick, long leftAt) {
        int radius = projecting ? deactivateRadius : activateRadius;
        if (distanceSq <= (double) radius * radius) {
            return Presence.PROJECT;
        }
        if (!projecting || distanceSq > (double) graceRadius * graceRadius) {
            return Presence.CLEAR;
        }
        return leftAt != NOT_LEFT && tick - leftAt >= GRACE_TICKS ? Presence.CLEAR : Presence.HOLD;
    }
}
