package com.customdimensions.core.portal;

/**
 * What lighting a portal costs the igniter. Vanilla damages a flint and steel
 * by one and does not destroy it; an eye of ender is consumed only because it
 * is placed into an end portal frame, which is not an ignition. So an igniter
 * is spent only where a dimension asks for it.
 */
public enum IgniterSpend {
    /** Left as it was. */
    NOTHING,
    /** One point of durability. */
    DAMAGE,
    /** One off the stack, for a dimension that asked for the item itself. */
    CONSUME;

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
