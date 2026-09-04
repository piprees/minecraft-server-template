package com.customdimensions.portal;

/**
 * What lighting a portal costs the igniter.
 *
 * <p>Vanilla damages a flint and steel by one; it does not destroy it. An eye
 * of ender IS consumed, but because it is placed INTO an
 * {@code end_portal_frame} — a placement, not an ignition. So an igniter is
 * spent only where a dimension asks for it.
 *
 * <p>Pure, so the decision is testable: the mixin that applies it is not.
 */
public enum IgniterSpend {
    /** Left as it was. */
    NOTHING,
    /** One point of durability, the vanilla flint-and-steel behaviour. */
    DAMAGE,
    /** One off the stack, for a dimension that asked for the item itself. */
    CONSUME;

    /**
     * @param damageable       the stack has durability to spend
     * @param creative         the player pays for nothing
     * @param consumesIgniter  this dimension wants the item, not its durability
     */
    public static IgniterSpend of(boolean damageable, boolean creative, boolean consumesIgniter) {
        if (creative) {
            return NOTHING;
        }
        if (consumesIgniter) {
            return CONSUME;
        }
        return damageable ? DAMAGE : NOTHING;
    }
}
